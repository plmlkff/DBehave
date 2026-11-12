# DB Load Worker

Асинхронный worker, принимающий задания на запуск нагрузочного тестирования БД. Для каждого задания создаёт отдельную Docker bridge-сеть `internal=true`, запускает в ней контейнер БД и контейнер нагрузчика, ожидает завершения нагрузчика и удаляет контейнеры и сеть.

Реализация: Python 3.12, FastAPI, `asyncio`, PostgreSQL (`asyncpg`), Docker Engine API (`docker` SDK). Backend и worker используют одну базу: backend создаёт задания в `worker.tasks` со статусом `QUEUED`, worker атомарно забирает их и записывает heartbeat и конечный статус. SQLite локально не используется.

## 1. Что ограничивается

Поля `cpu_cores` и `memory_mb` — **общий бюджет пары контейнеров задания**. По умолчанию он делится 50/50 между БД и нагрузчиком. Доли настраиваются через `db_cpu_fraction` и `db_memory_fraction`; сумма лимитов двух контейнеров не превышает запрос задания. Docker получает CPU quota через `nano_cpus`, жёсткий memory limit через `mem_limit`, а `memswap_limit` равен memory limit (дополнительный swap не выделяется).

Помимо этого:

- у каждого задания своя Docker-сеть `internal=true`; контейнеры одного задания видят БД по DNS-имени `database`, но задания не подключаются к общей сети;
- контейнеры не публикуют порты на хост и не имеют маршрута наружу через свою internal-сеть;
- bind mounts, host volumes, Docker socket, devices, host PID/network namespace и privileged mode не используются;
- корневая ФС контейнеров read-only;
- worker проверяет mounts после создания контейнера и отказывается запускать образ, если обнаруживает bind mount или чужой volume (в том числе не перекрытый `VOLUME` из Dockerfile); writable пути — это явно указанные tmpfs и собственный том задания для данных БД;
- для записи доступны только явно указанные tmpfs-пути (в памяти контейнера, под memory limit) и каталог данных БД `db_data_path`;
- каталог данных БД по умолчанию лежит на диске, в Docker volume, который worker создаёт для одного задания и удаляет при очистке (`WORKER_DB_DATA_STORAGE=volume`). На диске `fsync` стоит реальных денег, поэтому настройки WAL и надёжности влияют на результат так же, как в продакшене. Режим `tmpfs` держит данные в памяти: быстрее, но эти настройки почти перестают влиять, а данные должны поместиться в `WORKER_TMPFS_SIZE_MB`;
- у нагрузчика сброшены все Linux capabilities; у БД оставлены только `CHOWN`, `SETUID`, `SETGID`, `DAC_OVERRIDE`, `FOWNER`, необходимые распространённым entrypoint-скриптам для подготовки каталога данных и сброса привилегий; включён `no-new-privileges`, задан private cgroup namespace и лимит PID;
- Docker JSON logs ограничены `log_max_size × log_max_files`; состояние и текст ошибки исполнения записываются в `worker.tasks`;
- scheduler дополнительно резервирует суммарные CPU/RAM worker'а, чтобы несколько одновременных заданий не превысили заданную ёмкость узла;
- API закрыт bearer-токеном, образы ограничиваются allow-list префиксов.

**Важно о модели безопасности.** Docker-контейнеры используют общее ядро хоста. Эти меры сильно сокращают доступ контейнера к хосту, но не превращают Docker в полноценную VM-границу и не дают математической гарантии защиты от уязвимости ядра/container runtime. Для запуска произвольных, потенциально враждебных образов используйте отдельную VM/выделенный узел, rootless Docker, обновлённый Engine и ограниченный allow-list образов. Не размещайте worker на машине с ценными секретами. По умолчанию worker предупреждает, если daemon не rootless; `WORKER_REQUIRE_ROOTLESS_DOCKER=true` заставляет его отказаться от запуска на обычном daemon.

Внутри изолированной сети запрещён внешний egress. Это осознанно: контейнер нагрузчика должен использовать `database` как адрес БД и не загружать зависимости из интернета во время теста.

## 2. Запуск

Нужны Python 3.12+ и работающий Docker Engine с доступом текущего пользователя к Docker API.

```bash
python -m venv .venv
source .venv/bin/activate          # Windows PowerShell: .venv\Scripts\Activate.ps1
pip install -e '.[test]'
cp .env.example .env               # Windows PowerShell: Copy-Item .env.example .env
```

Укажите PostgreSQL-хост и учётные данные в `WORKER_DB_HOST`, `WORKER_DB_PORT`, `WORKER_DB_NAME`, `WORKER_DB_USER` и `WORKER_DB_PASSWORD`. Если worker запущен в Docker Compose, укажите имя сервиса PostgreSQL, а не `127.0.0.1`. Схему `worker.tasks` создаёт backend через Flyway.

Сгенерируйте секрет и положите его в `WORKER_API_TOKEN` в `.env`:

```bash
python -c "import secrets; print(secrets.token_urlsafe(48))"
```

Настройте `WORKER_ALLOWED_IMAGE_PREFIXES`. Например, для `postgres:16` и своего нагрузчика в `ghcr.io/acme/loadgen`:

```dotenv
WORKER_ALLOWED_IMAGE_PREFIXES=docker.io/library/,docker.io/dbehave/,ghcr.io/acme/
```

Запуск:

```bash
uvicorn worker.main:app --host 127.0.0.1 --port 8080
```

Для production не публикуйте API в интернет напрямую: поставьте его за TLS reverse proxy/firewall, используйте длинный токен, ограничьте registry allow-list и включите rootless daemon (`WORKER_REQUIRE_ROOTLESS_DOCKER=true`). При старте worker проверяет cgroup version, предупреждения об отсутствии CPU/RAM/swap enforcement и физическую ёмкость Docker daemon; при невозможности подтвердить ограничения он отказывается запускаться. Задачи создаются платформой в общей PostgreSQL-базе, worker читает `worker.tasks` и использует `WORKER_INSTANCE_ID` для claim/heartbeat. Локальный scheduler резервирует ресурсы только внутри одного процесса; запускайте не более одного worker на Docker daemon.

## 3. Создание задания

`POST /tasks`, заголовок `Authorization: Bearer <WORKER_API_TOKEN>`:

```json
{
  "db_image": "postgres:16",
  "loadgen_image": "ghcr.io/acme/pg-loadgen:1.2.0",
  "cpu_cores": 4,
  "memory_mb": 8192,
  "db_cpu_fraction": 0.6,
  "db_memory_fraction": 0.7,
  "db_env": {
    "POSTGRES_PASSWORD": "benchmark-only-password",
    "POSTGRES_USER": "bench",
    "POSTGRES_DB": "bench"
  },
  "loadgen_env": {
    "DB_HOST": "database",
    "DB_PORT": "5432",
    "DB_USER": "bench",
    "DB_PASSWORD": "benchmark-only-password",
    "DB_NAME": "bench",
    "DURATION": "60s"
  },
  "db_tmpfs_paths": [
    "/tmp",
    "/var/tmp",
    "/run",
    "/var/lib/postgresql/data"
  ],
  "loadgen_tmpfs_paths": ["/tmp", "/var/tmp", "/run"],
  "timeout_seconds": 3600,
  "db_startup_grace_seconds": 8,
  "status": "NEW"
}
```

Пример запроса через curl:

```bash
set -a; source .env; set +a
curl -X POST http://127.0.0.1:8080/tasks \
  -H "Authorization: Bearer $WORKER_API_TOKEN" \
  -H 'Content-Type: application/json' \
  --data-binary @task.json
```

Обычно задания создаются через DBehave backend (`POST /api/experiments`) и появляются в `worker.tasks` как `QUEUED`. Worker также сохраняет совместимый `POST /tasks` для ручной отправки; для него `id` не передаётся: БД выдаёт `bigserial`, ответ содержит числовой ID в виде строки. Статусы в общей очереди: `QUEUED → RUNNING → SUCCEEDED | FAILED`.

Получить состояние и ошибку задания через worker API:

```bash
curl http://127.0.0.1:8080/tasks/1 \
  -H "Authorization: Bearer $WORKER_API_TOKEN"
```

Проверка готовности worker: `GET /healthz` (без авторизации; не раскрывает конфигурацию).

## 4. Настройка образов

Образы должны быть подготовлены под ограничения:

- каталог данных БД указывается в `db_data_path` (том на диске) или в `db_tmpfs_paths` (память);
- нагрузчик должен запускаться как конечный процесс и завершаться с кодом `0` при успешном тесте;
- нагрузчик подключается к БД по `DB_HOST=db`; порт и остальные параметры передаются env-переменными;
- если у образа БД есть Docker `HEALTHCHECK`, worker ждёт состояния `healthy`; без healthcheck worker ждёт `db_startup_grace_seconds` и проверяет, что контейнер всё ещё запущен;
- корневая ФС read-only, поэтому все пути, куда приложению нужно писать, должны быть в списке tmpfs. Список путей не является bind mount и не открывает доступ к файловой системе worker;
- закрепляйте образы по digest в production, а не используйте изменяемые теги.

Для PostgreSQL worker сам выбирает каталог данных (`/var/lib/postgresql` для версии 18+, иначе `/var/lib/postgresql/data`) и, в зависимости от `WORKER_DB_DATA_STORAGE`, монтирует туда том задания или tmpfs. Для официальных MongoDB и ClickHouse автоматически выбираются `/data/db` и `/var/lib/clickhouse`; служебные каталоги MongoDB и логи ClickHouse размещаются в tmpfs. Для других СУБД укажите каталог данных, соответствующий конкретному образу (например, Redis — `/data`, MySQL — `/var/lib/mysql`).

Основной Compose уже разрешает `docker.io/clickhouse/` в allowlist образов.
Для отдельного worker или существующего `.env` добавьте этот префикс в
`WORKER_ALLOWED_IMAGE_PREFIXES`. Плагин ClickHouse содержит стартовую команду,
совместимую с read-only root; ограничения безопасности worker не ослабляются.

## 5. Конфигурация worker

| Переменная | Назначение | По умолчанию |
|---|---|---:|
| `WORKER_API_TOKEN` | Bearer-токен API, минимум 32 символа | обязательная |
| `WORKER_DB_HOST` | IP или DNS-имя PostgreSQL | `localhost` |
| `WORKER_DB_PORT` | Порт PostgreSQL | `5432` |
| `WORKER_DB_NAME` | Имя общей базы DBehave | `dbehave` |
| `WORKER_DB_USER` | Пользователь PostgreSQL | `dbehave` |
| `WORKER_DB_PASSWORD` | Пароль PostgreSQL | `dbehave-local` |
| `WORKER_INSTANCE_ID` | Идентификатор worker для claim и heartbeat | hostname |
| `WORKER_CAPACITY_CORES` | Общий CPU-бюджет всех заданий | `8` |
| `WORKER_CAPACITY_MEMORY_MB` | Общая RAM-ёмкость для заданий, MiB | `16384` |
| `WORKER_MAX_CONCURRENT_JOBS` | Максимум одновременно выполняемых заданий | `4` |
| `WORKER_ALLOWED_IMAGE_PREFIXES` | Разрешённые префиксы image references | `docker.io/library/` |
| `WORKER_TMPFS_SIZE_MB` | Максимальный размер каждого tmpfs mount на контейнер | `256` |
| `WORKER_DB_DATA_STORAGE` | Где хранить данные БД: `volume` — одноразовый Docker volume на диске, `tmpfs` — в памяти | `volume` |
| `WORKER_PIDS_LIMIT` | Максимум процессов/потоков на контейнер | `256` |
| `WORKER_REQUIRE_ROOTLESS_DOCKER` | Требовать rootless Docker daemon | `false` |
| `WORKER_SCHEDULER_POLL_SECONDS` | Частота опроса очереди | `1.0` |
| `WORKER_METRICS_SCRAPE_SECONDS` | Интервал сбора и отправки метрик worker и контейнеров задания | `2.0` |
| `WORKER_PROMETHEUS_PUSHGATEWAY_URL` | URL Pushgateway; если не задан, экспорт метрик отключён | — |

`WORKER_CAPACITY_*` нужно выставить ниже реальных ресурсов машины, оставив запас ОС, Docker daemon и самого worker. Например, не отдавайте контейнерам всю физическую RAM хоста.

### Метрики и Grafana

При заданном `WORKER_PROMETHEUS_PUSHGATEWAY_URL` worker отправляет общие, не зависящие от плагина метрики в Pushgateway:

- CPU time и RSS-память процесса worker, число активных задач и зарезервированные CPU/RAM;
- размер и свободное место файловой системы, видимой из контейнера worker; это не метрика свободного места диска Docker-хоста;
- объём Docker images, writable layers контейнеров и volumes по данным Docker Engine API;
- CPU time, working-set памяти и лимит RAM, число PID и block I/O для контейнеров БД и нагрузчика.

Метрики worker публикуются в группе `job=dbehave_worker,instance=<WORKER_INSTANCE_ID>`. Метрики контейнеров публикуются на время задания в `job=dbehave_worker_task` с метками `instance`, `run_id` и `role`; после завершения задания группа удаляется из Pushgateway, а уже собранные временные ряды остаются в Prometheus до истечения его retention. Block I/O не включает запись в memory-backed tmpfs. Docker storage метрики показывают используемый объём, но не свободную ёмкость диска демона.

В основном Compose-стенде Grafana автоматически загружает отдельный дашборд **DBehave · Worker resources** с CPU, памятью, диском и резервами worker. Статистика контейнеров задания показана в дашборде конкретного запуска, отфильтрована по `run_id` и использует тот же Prometheus и Pushgateway; сбор не зависит от метрик PostgreSQL-плагина. Интервал опроса задаётся `WORKER_METRICS_SCRAPE_SECONDS`; временной ряд CPU требует как минимум двух последовательных выборок.

## 6. Поведение при сбоях

- Backend сохраняет задание в PostgreSQL до того, как scheduler его заберёт.
- При перезапуске worker его собственные оставшиеся `RUNNING` помечаются `FAILED`; контейнеры/сети с managed labels удаляются до запуска scheduler.
- При таймауте, падении БД или ненулевом exit code нагрузчика задание становится `FAILED`.
- В `finally` worker останавливает/удаляет контейнеры и сеть; неудачная очистка логируется, а остатки удаляются при следующем старте.
- Повторный `POST` с уже существующим ID получает `409 Conflict`.

## 7. Тесты

```bash
pip install -e '.[test]'
pytest -q
```

Тесты проверяют валидацию задания, преобразование PostgreSQL execution context, env, tmpfs-путей и нормализацию image references. Для интеграционной проверки нужны PostgreSQL со схемой backend и отдельный Docker Engine; запуск контейнеров на текущей машине в unit-тестах не производится.
