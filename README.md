# DBehave

- `backend` — Spring Boot API
- `plugin-api` — интерфейс СУБД-плагинов и модели конфигурации контейнеров
- `plugins` — реализованные плагины:
  - `plugins/postgres` — описание сценариев PostgreSQL и план контейнеров
  - [`plugins/postgres-load`](plugins/postgres-load/README.md) — образ-нагрузчик, контракт с воркером, метрики и дашборд Grafana
- `infra` — общий docker compose

```sh
docker compose -f infra/compose.yaml up --build
```

* [Swagger UI](http://localhost:8080/swagger-ui.html)
* [Примеры HTTP-запросов](docs/examples/experiments.http)
