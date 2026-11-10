# DBehave

- `backend` — Spring Boot API
- `frontend` — React-интерфейс; в Docker готовую сборку раздаёт Nginx
- `plugin-api` — интерфейс СУБД-плагинов и модели конфигурации контейнеров
- `plugins` — реализованные плагины
- `infra` — общий docker compose

```sh
docker compose -f infra/compose.yaml up --build -d
```

* [Интерфейс DBehave](http://localhost:3000)
* [Swagger UI](http://localhost:8080/swagger-ui.html)
* [Примеры HTTP-запросов](docs/examples/experiments.http)

Пересобрать и запустить только frontend (backend должен быть запущен):

```sh
docker compose -f infra/compose.yaml up --build -d --no-deps frontend
```
