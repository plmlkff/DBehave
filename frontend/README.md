# DBehave Frontend

React + TypeScript + Vite, архитектура Feature-Sliced Design.

## Локальный запуск

Из каталога `frontend`:

```sh
npm ci
npm run dev
```

Адрес: `http://localhost:5173`. Backend должен быть доступен на порту 8080.

`npm run build` — сборка в `dist`, `npm run lint` — проверка кода, `npm test` — проверки подготовки запроса и валидации формы.

Форма поддерживает BDUI-секции `settings`, `params`, `loaderImageContext` и `databaseImageContext`.
В секциях контейнеров `core_max` задаёт положительное число ядер CPU, `mem_max` — положительное целое число байт памяти.
При пустом поле сервер использует лимит из описания плагина. Поле с `minExclusive: true` требует значение строго больше `min`.

## Переменные окружения

При необходимости скопируйте `.env.example` в `.env.local`:

- `API_PROXY_TARGET=http://127.0.0.1:8080` — адрес backend для прокси Vite, используется только при `npm run dev`.
- `VITE_API_BASE_URL=/api` — путь API для запросов браузера.

После изменения переменных перезапустите Vite.

## Запуск в Docker и Nginx

Из корня репозитория:

```sh
docker compose -f infra/compose.yaml up --build --wait
```

Интерфейс: `http://localhost:3000`. Запускаются frontend, backend и служебная БД.

Node.js собирает `dist`, затем Nginx раздаёт сайт и пересылает `/api` на `backend:8080`. Настройки — в `nginx.conf`. В Docker путь API задан как `/api`; `.env.local` и `API_PROXY_TARGET` не используются.

Пересобрать и запустить только frontend (backend должен быть запущен):

```sh
docker compose -f infra/compose.yaml up --build -d --no-deps frontend
```

## Структура

- `src/app` — запуск, роутер и глобальные стили.
- `src/pages` — страницы.
- `src/widgets` — крупные блоки интерфейса.
- `src/features` — действия пользователя и формы.
- `src/entities` — модели данных и запросы к API.
- `src/shared` — общие компоненты, утилиты и HTTP-клиент.

Алиас `@` указывает на `src`.

## Запуск эксперимента

Форма отправляет параметры на `POST /api/experiments` и открывает страницу
запуска. Статус обновляется автоматически, ссылка Grafana доступна на странице
запуска и в истории. История загружается из `GET /api/experiments` и сохраняется
после перезагрузки браузера.
