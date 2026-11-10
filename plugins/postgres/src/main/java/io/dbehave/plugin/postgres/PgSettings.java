package io.dbehave.plugin.postgres;

import io.dbehave.plugin.FieldSpec;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.dbehave.plugin.FieldSpec.bool;
import static io.dbehave.plugin.FieldSpec.enumeration;
import static io.dbehave.plugin.FieldSpec.integer;
import static io.dbehave.plugin.FieldSpec.number;

/** Каталог настроек PostgreSQL 18, которые можно менять в экспериментах. Значения по умолчанию совпадают с PostgreSQL. */
final class PgSettings {
    // Память
    static final PgSetting SHARED_BUFFERS = new PgSetting(integer("shared_buffers", "shared_buffers",
            "Общий кеш страниц PostgreSQL. Чем больше кеш, тем реже чтение с диска. Обычно около 25% памяти сервера.",
            128, 16, 8192, "МБ"), "MB");
    static final PgSetting WORK_MEM = new PgSetting(integer("work_mem", "work_mem",
            "Память на одну сортировку или хеш-таблицу. Если её не хватает, данные уходят во временные файлы на диске.",
            4096, 64, 1_048_576, "кБ"), "kB");
    static final PgSetting MAINTENANCE_WORK_MEM = new PgSetting(integer("maintenance_work_mem", "maintenance_work_mem",
            "Память для служебных операций: создание индексов, VACUUM.",
            64, 1, 2048, "МБ"), "MB");
    static final PgSetting EFFECTIVE_CACHE_SIZE = new PgSetting(integer("effective_cache_size", "effective_cache_size",
            "Подсказка планировщику, сколько данных закешировано в PostgreSQL и ОС. Память не выделяет, но влияет на выбор индексов.",
            4096, 8, 65_536, "МБ"), "MB");

    // WAL и надёжность
    static final PgSetting SYNCHRONOUS_COMMIT = new PgSetting(enumeration("synchronous_commit", "synchronous_commit",
            "Ждать ли записи WAL на диск перед подтверждением COMMIT. off быстрее, но при сбое сервера теряются "
                    + "последние транзакции (данные при этом не портятся).",
            "on", "on", "off", "local"), null);
    static final PgSetting FSYNC = new PgSetting(bool("fsync", "fsync",
            "Сбрасывать ли данные на диск. Выключение ускоряет запись, но при сбое ОС или питания база может испортиться. "
                    + "В продакшене не выключать.",
            true), null);
    static final PgSetting FULL_PAGE_WRITES = new PgSetting(bool("full_page_writes", "full_page_writes",
            "Записывать целые страницы в WAL после чекпоинта. Защищает от частично записанных страниц, но увеличивает объём WAL.",
            true), null);
    static final PgSetting WAL_COMPRESSION = new PgSetting(enumeration("wal_compression", "wal_compression",
            "Сжатие полных страниц в WAL: меньше WAL ценой процессора.",
            "off", "off", "pglz", "lz4", "zstd"), null);
    static final PgSetting WAL_BUFFERS = new PgSetting(integer("wal_buffers", "wal_buffers",
            "Буфер WAL в памяти. -1 — автоматически (1/32 от shared_buffers).",
            -1, -1, 262_144, "кБ"), "kB");
    static final PgSetting COMMIT_DELAY = new PgSetting(integer("commit_delay", "commit_delay",
            "Пауза перед сбросом WAL, чтобы объединить коммиты нескольких транзакций (групповой коммит).",
            0, 0, 100_000, "мкс"), null);
    static final PgSetting WAL_WRITER_DELAY = new PgSetting(integer("wal_writer_delay", "wal_writer_delay",
            "Как часто фоновый процесс сбрасывает WAL. При synchronous_commit=off это окно возможной потери (до 3× значения).",
            200, 1, 10_000, "мс"), "ms");
    static final PgSetting MAX_WAL_SIZE = new PgSetting(integer("max_wal_size", "max_wal_size",
            "Объём WAL, после которого запускается чекпоинт. Маленькое значение — частые чекпоинты, скачки задержки и рост WAL.",
            1024, 32, 65_536, "МБ"), "MB");
    static final PgSetting CHECKPOINT_TIMEOUT = new PgSetting(integer("checkpoint_timeout", "checkpoint_timeout",
            "Максимальный интервал между чекпоинтами.",
            300, 30, 3600, "с"), "s");
    static final PgSetting CHECKPOINT_COMPLETION_TARGET = new PgSetting(number(
            "checkpoint_completion_target", "checkpoint_completion_target",
            "Доля интервала, на которую растягивается запись чекпоинта. Больше — ровнее нагрузка на диск.",
            0.9, 0.0, 1.0, null), null);

    // Блокировки и тайм-ауты
    static final PgSetting LOCK_TIMEOUT = new PgSetting(integer("lock_timeout", "lock_timeout",
            "Сколько ждать блокировку, прежде чем отменить запрос. 0 — ждать бесконечно.",
            0, 0, 600_000, "мс"), "ms");
    static final PgSetting STATEMENT_TIMEOUT = new PgSetting(integer("statement_timeout", "statement_timeout",
            "Максимальное время выполнения запроса. 0 — без ограничения.",
            0, 0, 600_000, "мс"), "ms");
    static final PgSetting IDLE_IN_TRANSACTION_SESSION_TIMEOUT = new PgSetting(integer(
            "idle_in_transaction_session_timeout", "idle_in_transaction_session_timeout",
            "Закрыть сессию, которая открыла транзакцию и бездействует дольше этого времени. 0 — не закрывать.",
            0, 0, 600_000, "мс"), "ms");
    static final PgSetting DEADLOCK_TIMEOUT = new PgSetting(integer("deadlock_timeout", "deadlock_timeout",
            "Сколько ждать блокировку, прежде чем искать взаимоблокировку. Определяет, как быстро обнаружится deadlock.",
            1000, 1, 60_000, "мс"), "ms");

    // Планировщик и ввод-вывод
    static final PgSetting RANDOM_PAGE_COST = new PgSetting(number("random_page_cost", "random_page_cost",
            "Оценка стоимости случайного чтения страницы. Ниже (например, 1.1 для SSD) — планировщик чаще выбирает индексы.",
            4.0, 0.1, 100.0, null), null);
    static final PgSetting EFFECTIVE_IO_CONCURRENCY = new PgSetting(integer("effective_io_concurrency", "effective_io_concurrency",
            "Сколько одновременных запросов чтения может отправить один процесс (bitmap heap scan, предвыборка).",
            16, 0, 1000, null), null);
    static final PgSetting IO_METHOD = new PgSetting(enumeration("io_method", "io_method",
            "Асинхронный ввод-вывод из PostgreSQL 18: worker — фоновые процессы, sync — синхронное чтение, как в прошлых версиях.",
            "worker", "worker", "sync"), null);
    static final PgSetting MAX_PARALLEL_WORKERS_PER_GATHER = new PgSetting(integer(
            "max_parallel_workers_per_gather", "max_parallel_workers_per_gather",
            "Сколько параллельных процессов может помогать одному запросу. 0 — без параллелизма.",
            2, 0, 16, null), null);
    static final PgSetting JIT = new PgSetting(bool("jit", "jit",
            "JIT-компиляция выражений. Ускоряет тяжёлую аналитику, но на коротких запросах добавляет накладные расходы.",
            true), null);
    static final PgSetting ENABLE_SEQSCAN = new PgSetting(bool("enable_seqscan", "enable_seqscan",
            "Разрешить последовательное сканирование. Выключение заставляет планировщик искать другой план.",
            true), null);

    // Автовакуум
    static final PgSetting AUTOVACUUM = new PgSetting(bool("autovacuum", "autovacuum",
            "Автоматическая очистка мёртвых версий строк и обновление статистики.",
            true), null);
    static final PgSetting AUTOVACUUM_NAPTIME = new PgSetting(integer("autovacuum_naptime", "autovacuum_naptime",
            "Пауза между запусками автовакуума.",
            60, 1, 3600, "с"), "s");
    static final PgSetting AUTOVACUUM_VACUUM_SCALE_FACTOR = new PgSetting(number(
            "autovacuum_vacuum_scale_factor", "autovacuum_vacuum_scale_factor",
            "Доля изменённых строк таблицы, после которой запускается автовакуум.",
            0.2, 0.0, 1.0, null), null);

    // Подключения
    static final PgSetting MAX_CONNECTIONS = new PgSetting(integer("max_connections", "max_connections",
            "Максимум подключений. Если клиентов больше, лишние получат ошибку too_many_connections.",
            100, 10, 1000, null), null);

    static final List<PgSetting> ALL = List.of(
            SHARED_BUFFERS, WORK_MEM, MAINTENANCE_WORK_MEM, EFFECTIVE_CACHE_SIZE,
            SYNCHRONOUS_COMMIT, FSYNC, FULL_PAGE_WRITES, WAL_COMPRESSION, WAL_BUFFERS, COMMIT_DELAY, WAL_WRITER_DELAY,
            MAX_WAL_SIZE, CHECKPOINT_TIMEOUT, CHECKPOINT_COMPLETION_TARGET,
            LOCK_TIMEOUT, STATEMENT_TIMEOUT, IDLE_IN_TRANSACTION_SESSION_TIMEOUT, DEADLOCK_TIMEOUT,
            RANDOM_PAGE_COST, EFFECTIVE_IO_CONCURRENCY, IO_METHOD, MAX_PARALLEL_WORKERS_PER_GATHER, JIT, ENABLE_SEQSCAN,
            AUTOVACUUM, AUTOVACUUM_NAPTIME, AUTOVACUUM_VACUUM_SCALE_FACTOR,
            MAX_CONNECTIONS);

    private static final Map<String, PgSetting> BY_KEY = ALL.stream()
            .collect(Collectors.toUnmodifiableMap(PgSetting::key, Function.identity()));

    private PgSettings() {
    }

    static PgSetting byKey(String key) {
        PgSetting setting = BY_KEY.get(key);
        if (setting == null) {
            throw new IllegalArgumentException("Unknown PostgreSQL setting: " + key);
        }
        return setting;
    }

    static List<FieldSpec> specs(PgSetting... settings) {
        return Arrays.stream(settings).map(PgSetting::spec).toList();
    }
}
