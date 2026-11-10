# DBehave

Платформа хранит плагины и BDUI-схемы в PostgreSQL, проверяет ввод, собирает
контекст исполнения и записывает задачи в `worker.tasks` той же базы.

- `backend` — каталог, формы, валидация, запуск и polling API.
- `frontend` — React-форма настройки и проверки эксперимента; Nginx в Docker.
- `plugin-api` — общие модели и интерфейс `ContextCollector`.
- `plugins/postgres` — PostgreSQL-плагин, пять сценариев и коллектор платформы.
- [`plugins/postgres-load`](plugins/postgres-load/README.md) — настоящий нагрузчик,
  метрики, контракт с воркером и Grafana-дашборд.

## Запуск

Нужны Docker с запущенным Docker Engine и Docker Compose. Из корня репозитория:

```sh
# Бэкенд и служебная БД
docker compose -f infra/compose.yaml up --build -d backend

# Проверить запуск
docker compose -f infra/compose.yaml logs -f backend
```

API: [http://localhost:8080](http://localhost:8080).
Документация: [Swagger UI](http://localhost:8080/swagger-ui.html).
Flyway создаёт таблицы автоматически; PostgreSQL-плагин регистрируется при старте.

Для формы настройки запустите также фронтенд:

```sh
docker compose -f infra/compose.yaml up --build -d frontend
```

Интерфейс: [http://localhost:3000](http://localhost:3000). Форма сейчас проверяет
конфигурацию через `/validate`; создание задачи доступно через API ниже.

Образ нагрузчика нужно собрать отдельно для Docker-окружения воркера:

```sh
docker build -f plugins/postgres-load/Dockerfile -t dbehave/postgres-load:0.1.0 .
```

Все пять сценариев используют этот образ и выбираются переменной `SCENARIO`.
Платформа ставит задачи в очередь; отдельный воркер запускает контейнеры,
ограничивает ресурсы, обновляет статус и пересылает метрики с меткой `run_id`.
Без воркера задача остаётся `QUEUED`.

## Основные запросы

Обычный поток — каталог, сценарии, форма, запуск, затем polling. Дополнительные
административные методы описаны в [контракте платформы](docs/plugin-platform.md).
Все примеры для HTTP-клиента IDE: [experiments.http](docs/examples/experiments.http).

```sh
# 1. Плагины: [{"id":"postgres","title":"PostgreSQL 18"}]
curl -fsS http://localhost:8080/api/plugins

# 2. Сценарии плагина
curl -fsS 'http://localhost:8080/api/scenarios?pluginId=postgres'

# 3. BDUI выбранного сценария: поля settings, params и лимиты обоих образов
curl -fsS 'http://localhost:8080/api/ui/experiment?pluginId=postgres&scenarioId=mixed'

# 4. Запуск: 201, id, state=QUEUED, grafanaUrl, createdAt, updatedAt
curl -fsS -X POST http://localhost:8080/api/experiments \
  -H 'Content-Type: application/json' \
  -d '{
    "pluginId":"postgres",
    "scenarioId":"mixed",
    "settings":{"shared_buffers":128},
    "params":{"clients":8,"durationSec":10,"warmupSec":0,"datasetRows":10000,"readWeight":70,"updateWeight":20,"insertWeight":10},
    "loaderImageContext":{"core_max":0.5,"mem_max":536870912},
    "databaseImageContext":{"core_max":2,"mem_max":1073741824}
  }'

# 5. Подставьте id из ответа запуска
curl -fsS 'http://localhost:8080/api/experiments/<id>'
```

Все настройки и параметры можно пропустить: применятся defaults из BDUI.
Для проверки без записи задачи отправьте то же тело на
`POST /api/experiments/validate` — ответ `{"status":"VALIDATED","execution":"READY"}`.
Ошибки ввода — 400 с `fieldErrors`, неизвестные сущности — 404.
`core_max` измеряется в ядрах, `mem_max` — в байтах.

## Grafana и нагрузчик без воркера

Готовый локальный стенд нагрузчика, PostgreSQL 18, Prometheus и Grafana:

```sh
docker compose -f plugins/postgres-load/dev/compose.yaml up --build -d
```

Grafana: [http://localhost:3001/d/dbehave-postgres?var-run_id=local](http://localhost:3001/d/dbehave-postgres?var-run_id=local).
Порт 3000 остаётся фронтенду; изменить порт Grafana можно через `GRAFANA_PORT`.
Стенд выполняет один запуск с `run_id=local` и не читает очередь платформы.
Начальный плагин возвращает ссылку на тот же дашборд с UUID созданной задачи:
данные для UUID появятся, когда воркер отправит соответствующие метрики.
Адрес Grafana можно поменять через `PUT /api/plugins/postgres` или в каталоге БД.

## Локальная разработка и проверки

Для Java-части нужна Java 25. Для запуска API без сборки контейнера поднимите отдельную
временную служебную БД на порту 15432:

```sh
docker run --rm --name dbehave-meta-dev -p 127.0.0.1:15432:5432 \
  -e POSTGRES_DB=dbehave -e POSTGRES_USER=dbehave \
  -e POSTGRES_PASSWORD=dbehave-local postgres:18
```

В другом терминале:

```sh
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15432/dbehave \
SPRING_DATASOURCE_USERNAME=dbehave \
SPRING_DATASOURCE_PASSWORD=dbehave-local \
./gradlew :backend:bootRun
```

Для фронтенда нужен Node.js 24+: `cd frontend && npm ci && npm run dev`;
[подробнее](frontend/README.md).

```sh
./gradlew build
npm --prefix frontend ci
npm --prefix frontend test
npm --prefix frontend run build
npm --prefix frontend run lint
```

Полный Java-прогон с интеграционными тестами требует отдельной PostgreSQL-базы:

```sh
DBEHAVE_TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/dbehave_test \
DBEHAVE_TEST_DATABASE_USERNAME=postgres \
DBEHAVE_TEST_DATABASE_PASSWORD=postgres \
./gradlew build
```

Без `DBEHAVE_TEST_DATABASE_URL` интеграционные тесты пропускаются. CI запускает их
на PostgreSQL 18. Тесты создают плагины и задачи: используйте отдельную базу.
При обновлении с предыдущей платформы миграция заменяет только нетронутый
стартовый PostgreSQL-плагин. Пользовательские правки и существующие задачи сохраняются.
