# DBehave

- `backend` — Spring Boot API
- `plugin-api` — интерфейс СУБД-плагинов и модели конфигурации контейнеров
- `plugins` — реализованные плагины
- `infra` — общий docker compose

```sh
docker compose -f infra/compose.yaml up --build
```

* [Swagger UI](http://localhost:8080/swagger-ui.html)
* [Примеры HTTP-запросов](docs/examples/experiments.http)
