# DBehave

Платформа для настройки экспериментов с СУБД и передачи запусков в очередь воркеров.

## Запуск

Нужен запущенный Docker с Docker Compose. Из корня репозитория выполните одну команду.

```sh
docker compose -f infra/compose.yaml up --build --wait
```

Compose соберёт и запустит фронтенд, API и служебную PostgreSQL, создаст таблицы
и зарегистрирует PostgreSQL-плагин. Команда завершится после готовности API.

- [Интерфейс](http://localhost:3000)
- [Swagger и запросы к API](http://localhost:8080/swagger-ui.html)

Остановка выполняется следующей командой. Данные сохраняются в Docker-томе.

```sh
docker compose -f infra/compose.yaml down
```

Платформа записывает запуски в `worker.tasks`. Выполнение контейнеров и отправку
метрик обеспечивает подключаемый воркер. До обработки воркером задача имеет статус `QUEUED`.

## Документация

- [Примеры запросов, включая параметры и лимиты контейнеров](docs/examples/experiments.http)
- [Плагины и контракт воркера](docs/plugin-platform.md)
- [Разработка фронтенда](frontend/README.md)
- [Нагрузчик PostgreSQL и отдельный стенд с Grafana](plugins/postgres-load/README.md)
