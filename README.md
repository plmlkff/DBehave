# DBehave

Платформа хранит каталог плагинов и BDUI-схемы в PostgreSQL, проверяет ввод,
собирает контекст исполнения и ставит задачи в очередь той же базы.

- `backend` — Spring Boot API, каталог, валидация и очередь.
- `plugin-api` — `PluginDefinition`, `ContextCollector`, модели BDUI.
- `plugins/postgres` — PostgreSQL-коллектор и начальные пять сценариев.
- `infra` — Docker Compose для API и метабазы.

## Запуск

```sh
docker compose -f infra/compose.yaml up --build
```

[Swagger UI](http://localhost:8080/swagger-ui.html) ·
[HTTP-примеры](docs/examples/experiments.http) ·
[Контракт платформы и воркера](docs/plugin-platform.md)

При старте Flyway создаёт `dbehave.plugins` и `worker.tasks`. Начальный PostgreSQL-плагин
добавляется, только если его ещё нет: сохранённые правки не перезаписываются.
Для локального запуска без Compose задайте `SPRING_DATASOURCE_URL`,
`SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` и выполните
`./gradlew :backend:bootRun` (Java 25).

## API

| Метод | Адрес | Результат |
|---|---|---|
| GET | `/api/plugins` | Каталог с образами, сценариями и схемами полей |
| GET | `/api/context-collectors` | Доступные коллекторы |
| GET | `/api/plugins/{id}` | Один плагин |
| POST | `/api/plugins` | Регистрация плагина, 201 |
| PUT | `/api/plugins/{id}` | Полная замена определения, 200 |
| GET | `/api/ui/experiment?pluginId=postgres&scenarioId=mixed` | BDUI выбранного сценария и лимитов контейнеров |
| POST | `/api/experiments/validate` | Проверка и сборка без записи задачи |
| POST | `/api/experiments` | Запись `QUEUED` в `worker.tasks`, 201, id и `grafanaUrl` |
| GET | `/api/experiments/{id}` | Текущий статус, heartbeat, workerId, error и timestamps |

Фронт получает каталог, рисует форму, отправляет `settings`, `params` и необязательные
лимиты контейнеров. Из ответа запуска сохраняет `id` и `grafanaUrl`, затем опрашивает
статус. Невалидный ввод возвращает 400 с `fieldErrors`, отсутствующие сущности — 404,
повторная регистрация id — 409. Наличие коллектора проверяется при создании и обновлении.

## Проверки

```sh
./gradlew build
```

Полный прогон с интеграционными тестами требует отдельной PostgreSQL-базы:

```sh
DBEHAVE_TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/dbehave_test \
DBEHAVE_TEST_DATABASE_USERNAME=postgres \
DBEHAVE_TEST_DATABASE_PASSWORD=postgres \
./gradlew build
```

Без `DBEHAVE_TEST_DATABASE_URL` интеграционные тесты пропускаются. CI запускает их
на PostgreSQL 18. Тесты проверяют миграции, каталог, BDUI, регистрацию и обновление,
оба коллектора, ошибки без записи задач, CPU/память, сохранность снимка контекста и
опрос состояния после SQL-обновлений воркера. Используйте отдельную тестовую базу:
прогон создаёт в ней плагины и задачи.

Исполнение Docker-контейнеров выполняет отдельный воркер. Его реализация, сами образы
нагрузчиков и Grafana-дашборды в этот бэкенд не входят. Начальные имена нагрузчиков
`dbehave/postgres-load:*` и ссылка Grafana — примеры; замените их адресами ваших образов
и существующего борда перед реальным запуском.
