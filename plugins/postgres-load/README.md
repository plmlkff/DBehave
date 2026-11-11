# Нагрузчик PostgreSQL (`dbehave/postgres-load`)

Образ, который воркер запускает рядом с контейнером `postgres:18`. Нагрузчик готовит данные, гоняет клиентов
и отдаёт состояние и метрики по HTTP. Какие контейнеры и с какими параметрами запускать, решает плагин
[`plugins/postgres`](../postgres). Платформенный `PostgresContextCollector` использует те же настройки и единицы,
сохраняет готовый контекст двух образов в `worker.tasks`. Контракт и примеры API —
в [документации платформы](../../docs/plugin-platform.md). `PostgresPlugin.prepare()`
также доступен для прямого построения `ExperimentPlan`.

## Контракт с воркером

**Сеть.** Оба контейнера в одной сети, БД доступна нагрузчику по имени `database`.

**Вход — переменные окружения** (их формирует плагин):

| Переменная | Значение |
|---|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | подключение к БД (`database:5432`, `dbehave`) |
| `SCENARIO` | `write-heavy`, `read-heavy`, `mixed`, `long-tx-locks`, `isolation` |
| `CLIENTS`, `DURATION_SEC`, `WARMUP_SEC` | общие параметры нагрузки |
| остальные | параметры сценария: ключ формы в UPPER_SNAKE_CASE (`batchSize` → `BATCH_SIZE`) |
| `HTTP_PORT` | порт HTTP, по умолчанию `8080` |

**Выход — HTTP на порту 8080** (наружу публиковать не нужно, воркер ходит внутри сети):

- `GET /health` → всегда `200`, пока процесс жив:
  ```json
  {"status":"RUNNING","phase":"LOAD","progress":47,"error":null}
  ```
  - `status`: `STARTING` — ждёт БД и готовит данные, `RUNNING` — прогрев, нагрузка, проверки,
    `DONE` — успешно завершён, `FAILED` — ошибка, текст в `error`;
  - `phase`: `STARTING`, `PREPARING`, `WARMUP`, `LOAD`, `VERIFYING`, `DONE`, `FAILED`;
  - `progress`: процент текущей фазы.
- `GET /metrics` → метрики в текстовом формате Prometheus (список ниже).

**Жизненный цикл.**
1. Воркер запускает БД и нагрузчик (порядок не важен: нагрузчик сам ждёт БД до 2 минут).
2. Воркер опрашивает `/health` и забирает `/metrics` с выбранным интервалом и пересылает метрики
   в Prometheus, **добавляя метку `run_id`**.
3. Когда `status` стал `DONE` или `FAILED`, нагрузчик **не завершается сам**: воркер забирает метрики последний раз
   и останавливает контейнер (SIGTERM). Остановка занимает 1–2 секунды.
4. SIGTERM во время работы: клиенты останавливаются, `status` становится `FAILED`, процесс выходит.

## Метрики

Клиентские:

| Метрика | Тип | Метки | Что это |
|---|---|---|---|
| `dbehave_operations_total` | counter | `op` | успешные операции |
| `dbehave_operation_duration_seconds` | histogram | `op` | время успешных операций (для p50/p95/p99) |
| `dbehave_errors_total` | counter | `op`, `kind` | ошибки по видам: `serialization_failure`, `deadlock`, `lock_timeout`, `statement_timeout`, `idle_session_terminated`, `too_many_connections`, `connection_error`, `constraint_violation`, `other` |
| `dbehave_phase` | gauge | `phase` | 1 у текущей фазы |
| `dbehave_progress_percent` | gauge | | прогресс фазы |
| `dbehave_clients_active` | gauge | | клиенты с открытой сессией |
| `dbehave_load_start_timestamp_seconds`, `dbehave_load_end_timestamp_seconds` | gauge | | точное время начала прогрева и конца нагрузки |
| `dbehave_scenario_events_total` | counter | `name` | события сценария: `retries`, `non_repeatable_reads` |
| `dbehave_scenario_value` | gauge | `name` | значения сценария: `blocker_holding` (1 — блокировка удерживается) |
| `dbehave_check_value` | gauge | `check`, `kind` | итог проверки после нагрузки: `expected`, `actual`, `anomalies` |
| `dbehave_check_passed` | gauge | `check` | 1 — аномалий нет, 0 — найдены |

Со стороны PostgreSQL (опрос `pg_stat_*` раз в секунду; счётчики — прирост с начала нагрузки):

| Метрика | Тип | Метки |
|---|---|---|
| `dbehave_db_connections` | gauge | `state`: `active`, `idle`, `idle_in_transaction` |
| `dbehave_db_lock_waiters`, `dbehave_db_lock_wait_max_seconds` | gauge | |
| `dbehave_db_oldest_transaction_seconds` | gauge | |
| `dbehave_db_tuples` | gauge | `state`: `live`, `dead` |
| `dbehave_db_size_bytes` | gauge | |
| `dbehave_db_transactions_total` | counter | `result`: `commit`, `rollback` |
| `dbehave_db_blocks_total` | counter | `source`: `hit`, `read` |
| `dbehave_db_temp_bytes_total`, `dbehave_db_deadlocks_total` | counter | |
| `dbehave_db_wal_bytes_total`, `dbehave_db_wal_full_page_images_total` | counter | |
| `dbehave_db_checkpoints_total` | counter | `kind`: `timed`, `requested` |

## Сценарии

| `SCENARIO` | Операции (`op`) | Что показывает |
|---|---|---|
| `write-heavy` | `insert` | `synchronous_commit`, чекпоинты, цена индексов и пачек |
| `read-heavy` | `point`, `range`, `report` | индексы, `work_mem` (временные файлы), параллелизм, кеш |
| `mixed` | `read`, `update`, `insert` | горячие строки, блокировки, мёртвые версии, HOT-обновления |
| `long-tx-locks` | `blocker_tx`, `update` | ожидания блокировок, `lock_timeout`, `idle_in_transaction_session_timeout`, VACUUM |
| `isolation` | `transaction`, `read_transaction`, `write` | `ANOMALY` = `lost-update`, `write-skew`, `non-repeatable-read` на разных уровнях изоляции |

## Дашборд Grafana

[`grafana/postgres-run.json`](grafana/postgres-run.json) — один дашборд на все сценарии, переменные
`datasource` и `run_id`. Конкретный запуск открывается так: `/d/dbehave-postgres?var-run_id=<id>&from=<ms>&to=<ms>`.
Переменная `run_id` обновляет список запусков при открытии дашборда, а не при каждом обновлении временного диапазона:
это сохраняет переданный в URL ID нового запуска, пока Prometheus ещё получает его первые метрики. Панели при этом
обновляются каждую секунду; начальный временной диапазон — последние 5 минут.

В источнике данных Prometheus в Grafana нужно указать `jsonData.timeInterval`, равный интервалу, с которым воркер
пересылает метрики (например, `5s`). Иначе Grafana считает `rate()` по окну в минуту и сглаживает короткий запуск
в «треугольник». Пример — [`dev/grafana/datasources.yaml`](dev/grafana/datasources.yaml).

## Сборка и локальный запуск

```sh
# образ (из корня репозитория)
docker build -f plugins/postgres-load/Dockerfile -t dbehave/postgres-load:0.1.0 .

# стенд без воркера: БД + нагрузчик + Prometheus + Grafana (http://localhost:3001)
docker compose -f plugins/postgres-load/dev/compose.yaml up -d --build
docker compose -f plugins/postgres-load/dev/compose.yaml logs -f load

# повторить запуск после правок command у database или environment у load
docker compose -f plugins/postgres-load/dev/compose.yaml up -d --force-recreate database load
```

Порт Grafana по умолчанию 3001, чтобы стенд не конфликтовал с фронтендом платформы
на 3000. Переопределить: `GRAFANA_PORT=3300 docker compose -f plugins/postgres-load/dev/compose.yaml up -d`.

Стенд проводит один запуск сценария из `environment` сервиса `load` (по умолчанию write-heavy, 40 с).
Prometheus играет роль воркера: опрашивает нагрузчик раз в 2 с и ставит `run_id=local`, поэтому
повторные запуски видны на тех же графиках один за другим.

Добавить сценарий: класс в `scenario/`, ветка в `Scenarios.create`, описание полей в `PostgresScenarios` плагина.
