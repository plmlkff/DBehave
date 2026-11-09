# DBehave

- `backend` — Spring Boot API для BDUI-форм и проверки конфигурации экспериментов.
- `plugin-api` — интерфейс СУБД-плагинов и модели конфигурации контейнеров.
- `plugins/postgres` — пример плагина PostgreSQL 18 со сценарием смешанной нагрузки.
- `infra` — Compose для бэкенда и служебной PostgreSQL 18.

```sh
docker compose -f infra/compose.yaml up --build
```

* [Swagger UI](http://localhost:8080/swagger-ui.html)
* [Примеры HTTP-запросов](docs/examples/experiments.http)
