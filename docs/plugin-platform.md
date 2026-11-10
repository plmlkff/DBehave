# Платформа плагинов и контракт воркера

## Каталог

В `dbehave.plugins` лежат `id` и `definition jsonb`. Определение содержит:

- `id`, `title`, `collectorId` — идентификатор, название и id бина `ContextCollector`.
- `loaderImageName`, `databaseImageName` — образы. В имени нагрузчика разрешён
  `{scenarioTag}`: берётся из `scenario.loadGeneratorTag`.
- `grafanaUrlTemplate` — HTTP(S)-ссылка с `{runId}`, например
  `https://grafana.example/d/postgres?var-run_id={runId}`.
- `loaderImageContext`, `databaseImageContext` — базовые JSON-объекты для образов;
  могут содержать env, argv, конфигурацию и ресурсные лимиты.
- `scenarios` — сценарии с `id`, `title`, `description`, `loadGeneratorTag`,
  списками `settings` и `params`.

Каждое поле схемы: `key`, `title`, `description`, `type`, `defaultValue`, `min`,
`max`, `unit`, `options`. Типы: `INTEGER`, `NUMBER`, `BOOLEAN`, `STRING`, `ENUM`.
Все объявленные поля имеют валидное значение по умолчанию. Пропущенные ключи
заполняются из схемы; явный `null`, неверные типы, значения вне границ и неизвестные
ключи отклоняются. `options` передавайте массивом, включая пустой для обычных полей.
Списки полей могут быть пустыми.

Изменять описание можно через POST/PUT или непосредственно в БД. Обновление каталога
действует сразу, кеша в процессе нет. При прямой SQL-правке необходимо сохранять
корректное определение и существующий `collectorId`; схема и лимиты повторно
проверяются при запуске. PUT заменяет определение целиком. Контексты существующих
задач остаются прежними.

## Коллекторы

`generic` передаёт образам структурированный JSON без специальной конвертации:

- в `databaseImageContext.settings` помещает нормализованные `settings`;
- в `loaderImageContext.params` — нормализованные `params`;
- в `loaderImageContext.scenarioId` — выбранный сценарий;
- остальные базовые ключи сохраняет.

`postgres` собирает env и argv из схемы, прочитанной из БД. Настройки БД становятся
аргументами `postgres -c key=value`; `shared_buffers` измеряется в MB. Параметры
нагрузчика становятся переменными `UPPER_SNAKE_CASE`, например `durationSec` →
`DURATION_SEC`; существующий `datasetRows` преобразуется в `ROWS`. Совпадающие
после конвертации имена и параметры, перезаписывающие `SCENARIO` или подключение
к БД, отклоняются. Добавляются `SCENARIO`, `DB_HOST=database`, `DB_PORT=5432`,
`DB_NAME`, `DB_USER`; из `POSTGRES_PASSWORD` выводятся `DB_PASSWORD` и `PGPASSWORD`.
Для образа БД воркер должен использовать сетевой alias `database`. Значения настроек
передаются отдельными аргументами без shell-подстановок.

Для специальной конвертации реализуйте `ContextCollector` из `plugin-api` и
зарегистрируйте его как Spring-бин. `id()` возвращает значение `collectorId`,
`collect(plugin, scenario, settings, params)` — `ImageContexts` для двух образов.
Коллектор получает уже проверенные поля и должен только строить контекст, без
побочных эффектов. Для недопустимой комбинации параметров можно выбросить
`IllegalArgumentException`: API вернёт 400 с ошибкой поля `context`, задача не
будет записана. Идентификаторы коллекторов должны быть уникальными.

Чтобы подключить плагин, использующий существующий коллектор, достаточно записи
каталога. Для нового способа конвертации понадобится новый бин и сборка бэкенда.
При регистрации и обновлении платформа проверяет сборку каждого сценария на
значениях по умолчанию, чтобы отклонить некорректную конфигурацию коллектора.
Образы выбирает платформа из каталога; коллектор возвращает только их контексты.

## Ресурсы и BDUI

В обоих контекстах можно задать:

- `core_max` — положительное конечное число ядер CPU, например `0.5` или `2`;
- `mem_max` — положительное целое число байт до `9223372036854775807`, например
  `536870912` для 512 MiB.

Значения из запроса заменяют соответствующие значения из определения плагина.
Пропуск сохраняет базовый лимит; если его тоже нет, ключ не записывается и лимит
определяется воркером. Чтобы убрать базовый лимит, обновите определение плагина.
`null` и нулевые значения отклоняются. Фронт не отправляет незаполненные поля.

BDUI содержит секции `settings`, `params`, `loaderImageContext`,
`databaseImageContext`. `fields[].name` — путь в запросе и в `fieldErrors`, например
`databaseImageContext.mem_max`. Для CPU `min=0`, `minExclusive=true`: значение должно
быть больше нуля; для остальных полей границы включительные. Присланные пользователем контексты допускают только два ресурсных
ключа; произвольные env и конфигурация определяются каталогом и коллектором.
Ресурсные значения накладываются платформой после коллектора.

## Очередь

`POST /api/experiments` сначала полностью проверяет и собирает контекст, затем
одним INSERT создаёт запись `worker.tasks`. Ответ содержит `id`, `state=QUEUED`,
`grafanaUrl` с подставленным UUID, `createdAt`, `updatedAt`; заголовок `Location`
указывает на polling endpoint. Повторный POST создаёт отдельный запуск.
`/validate` использует ту же сборку, возвращая `VALIDATED/READY` без INSERT.

Структура `worker.tasks`:

| Поле | Тип | Назначение |
|---|---|---|
| id | uuid, PK | Идентификатор запуска, совпадает с run id Grafana |
| state | varchar(64) | Начальное `QUEUED`, далее строка, управляемая воркером |
| created_at | timestamptz | Создание задачи |
| updated_at | timestamptz | Автоматически обновляется триггером при любом UPDATE |
| context | jsonb | Снимок исполнения |
| heartbeat | timestamptz, nullable | Последний heartbeat воркера |
| worker_id | varchar(255), nullable | Воркер, взявший задачу |
| error | text, nullable | Ошибка исполнения |

```json
{
  "loaderImageName": "dbehave/postgres-load:mixed",
  "databaseImageName": "postgres:18",
  "loaderImageContext": {
    "environment": {"SCENARIO": "mixed", "CLIENTS": "16"},
    "command": [],
    "core_max": 0.5,
    "mem_max": 536870912
  },
  "databaseImageContext": {
    "environment": {"POSTGRES_DB": "dbehave_experiment"},
    "command": ["postgres", "-c", "shared_buffers=128MB"],
    "core_max": 2,
    "mem_max": 1073741824
  }
}
```

Пример показан сокращённо: коллектор добавляет все необходимые env. Воркер читает
образы и их контексты напрямую из задачи; повторно получать плагин ему не нужно.
Платформа не выполняет контейнеры и не меняет статус после `QUEUED`.

Для нескольких воркеров задачу можно атомарно забрать таким запросом:

```sql
WITH next_task AS (
    SELECT id
    FROM worker.tasks
    WHERE state = 'QUEUED'
    ORDER BY created_at, id
    FOR UPDATE SKIP LOCKED
    LIMIT 1
)
UPDATE worker.tasks AS task
SET state = 'RUNNING', worker_id = :worker_id, heartbeat = clock_timestamp()
FROM next_task
WHERE task.id = next_task.id
RETURNING task.*;
```

Далее воркер обновляет heartbeat и завершает задачу, например состоянием
`SUCCEEDED` или `FAILED` с `error`. Политика повторных попыток и восстановления
после потерянного heartbeat остаётся на стороне воркера. Polling API каждый раз
читает БД и возвращает статус, времена, heartbeat, workerId и error. Ссылку Grafana
фронт сохраняет из ответа создания запуска.
