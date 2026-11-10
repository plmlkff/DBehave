package io.dbehave.plugin.postgres;

import io.dbehave.plugin.FieldSpec;
import io.dbehave.plugin.Scenario;

import java.util.ArrayList;
import java.util.List;

import static io.dbehave.plugin.FieldSpec.bool;
import static io.dbehave.plugin.FieldSpec.enumeration;
import static io.dbehave.plugin.FieldSpec.integer;
import static io.dbehave.plugin.postgres.PgSettings.*;

/**
 * Сценарии PostgreSQL. Каждый параметр нагрузки попадает в нагрузчик переменной окружения
 * с тем же именем в UPPER_SNAKE_CASE: {@code durationSec} → {@code DURATION_SEC}.
 */
final class PostgresScenarios {
    private PostgresScenarios() {
    }

    static List<Scenario> all() {
        return List.of(writeHeavy(), readHeavy(), mixed(), longTxLocks(), isolation());
    }

    static Scenario writeHeavy() {
        return new Scenario("write-heavy", "Интенсивная запись",
                "Клиенты параллельно вставляют строки, каждая пачка фиксируется отдельной транзакцией. "
                        + "Смотрите на пропускную способность, задержки, объём WAL и чекпоинты.",
                specs(SHARED_BUFFERS, SYNCHRONOUS_COMMIT, FSYNC, FULL_PAGE_WRITES, WAL_COMPRESSION, WAL_BUFFERS,
                        COMMIT_DELAY, WAL_WRITER_DELAY, MAX_WAL_SIZE, CHECKPOINT_TIMEOUT, CHECKPOINT_COMPLETION_TARGET,
                        MAX_CONNECTIONS),
                load(16, 30,
                        integer("batchSize", "Строк в транзакции", "Сколько строк вставляется одним INSERT и одной транзакцией",
                                1, 1, 1000, "строк"),
                        integer("extraIndexes", "Дополнительные индексы", "Сколько индексов создать сверх первичного ключа",
                                0, 0, 5, null),
                        integer("payloadBytes", "Размер строки", "Объём полезных данных в каждой строке",
                                200, 16, 4000, "Б")));
    }

    static Scenario readHeavy() {
        return new Scenario("read-heavy", "Интенсивное чтение",
                "Клиенты выполняют точечные выборки по ключу, агрегаты по категории и тяжёлые отчёты с сортировкой. "
                        + "Смотрите на задержки по типам запросов, попадание в кеш и временные файлы.",
                specs(SHARED_BUFFERS, WORK_MEM, MAINTENANCE_WORK_MEM, EFFECTIVE_CACHE_SIZE, RANDOM_PAGE_COST,
                        EFFECTIVE_IO_CONCURRENCY, IO_METHOD, MAX_PARALLEL_WORKERS_PER_GATHER, JIT, ENABLE_SEQSCAN,
                        MAX_CONNECTIONS),
                load(16, 30,
                        datasetRows(1_000_000),
                        integer("pointWeight", "Вес точечных выборок", "SELECT по первичному ключу", 90, 0, 100, null),
                        integer("rangeWeight", "Вес агрегатов", "count и avg по категории: без индекса это полное сканирование",
                                8, 0, 100, null),
                        integer("reportWeight", "Вес отчётов", "Сортировка большого диапазона: проверяет work_mem",
                                2, 0, 100, null),
                        bool("categoryIndex", "Индекс по категории", "Создать индекс для агрегатов по категории", false),
                        distribution()));
    }

    static Scenario mixed() {
        return new Scenario("mixed", "Смешанная нагрузка",
                "Чтения, обновления и вставки в заданном соотношении. При горячих строках обновления начинают ждать "
                        + "блокировки, а мёртвые версии строк копятся быстрее.",
                specs(SHARED_BUFFERS, WORK_MEM, SYNCHRONOUS_COMMIT, MAX_WAL_SIZE, AUTOVACUUM, AUTOVACUUM_NAPTIME,
                        AUTOVACUUM_VACUUM_SCALE_FACTOR, DEADLOCK_TIMEOUT, MAX_CONNECTIONS),
                load(16, 30,
                        datasetRows(500_000),
                        integer("readWeight", "Вес чтений", "SELECT по первичному ключу", 70, 0, 100, null),
                        integer("updateWeight", "Вес обновлений", "UPDATE одной строки", 20, 0, 100, null),
                        integer("insertWeight", "Вес вставок", "INSERT новой строки", 10, 0, 100, null),
                        distribution(),
                        integer("fillfactor", "fillfactor таблицы", "Запас места на странице для HOT-обновлений",
                                100, 50, 100, "%")));
    }

    static Scenario longTxLocks() {
        return new Scenario("long-tx-locks", "Долгая транзакция и блокировки",
                "Один клиент держит транзакцию с заблокированными строками, остальные пытаются их обновить. "
                        + "Смотрите на ожидающие сессии, провалы пропускной способности и мёртвые строки.",
                specs(SHARED_BUFFERS, LOCK_TIMEOUT, STATEMENT_TIMEOUT, IDLE_IN_TRANSACTION_SESSION_TIMEOUT,
                        DEADLOCK_TIMEOUT, AUTOVACUUM, AUTOVACUUM_NAPTIME, MAX_CONNECTIONS),
                load(16, 40,
                        integer("holdSec", "Удержание блокировки", "Сколько блокировщик держит транзакцию открытой",
                                15, 1, 120, "с"),
                        integer("pauseSec", "Пауза между блокировками", "Пауза после COMMIT перед следующей блокировкой",
                                5, 0, 60, "с"),
                        integer("hotRows", "Заблокированные строки", "Сколько строк блокирует блокировщик",
                                100, 1, 5000, "строк"),
                        integer("hotShare", "Доля обращений к ним", "Вероятность, что клиент обновит заблокированную строку",
                                30, 0, 100, "%"),
                        enumeration("blockerMode", "Режим блокировщика",
                                "idle — открыл транзакцию и бездействует (забытая транзакция), "
                                        + "active — выполняет долгий запрос внутри транзакции",
                                "idle", "idle", "active")));
    }

    static Scenario isolation() {
        return new Scenario("isolation", "Проверка уровней изоляции",
                "Конкурирующие транзакции воспроизводят классическую аномалию, а проверка после нагрузки считает, "
                        + "сколько раз она случилась. Сравните уровни изоляции: аномалии превращаются в ошибки сериализации.",
                specs(SHARED_BUFFERS, LOCK_TIMEOUT, DEADLOCK_TIMEOUT, MAX_CONNECTIONS),
                load(16, 20,
                        enumeration("anomaly", "Аномалия",
                                "lost-update — потерянное обновление, write-skew — перекос записи, "
                                        + "non-repeatable-read — неповторяемое чтение",
                                "lost-update", "lost-update", "write-skew", "non-repeatable-read"),
                        enumeration("isolationLevel", "Уровень изоляции", "Задаётся в каждой транзакции сценария",
                                "READ COMMITTED", "READ COMMITTED", "REPEATABLE READ", "SERIALIZABLE"),
                        integer("thinkMs", "Пауза внутри транзакции", "Расширяет окно гонки между чтением и записью",
                                5, 0, 100, "мс"),
                        integer("counterRows", "Количество счётчиков",
                                "Для lost-update: меньше строк — выше конкуренция", 10, 1, 1000, "строк"),
                        enumeration("writeMode", "Способ обновления",
                                "Для lost-update: чтение и запись, блокировка строки (FOR UPDATE) или атомарный UPDATE",
                                "read-modify-write", "read-modify-write", "select-for-update", "atomic-update"),
                        bool("retryOnSerializationFailure", "Повторять при ошибке сериализации",
                                "Повторять транзакцию после ошибки 40001, как должно делать приложение", false)));
    }

    /** Общие параметры нагрузки (клиенты, длительность, прогрев) плюс параметры сценария. */
    private static List<FieldSpec> load(int clients, int durationSec, FieldSpec... scenarioParams) {
        var params = new ArrayList<FieldSpec>();
        params.add(integer("clients", "Клиенты", "Число параллельных клиентов, у каждого своё соединение с БД",
                clients, 1, 256, null));
        params.add(integer("durationSec", "Длительность нагрузки", "Сколько секунд идёт основная нагрузка",
                durationSec, 5, 600, "с"));
        params.add(integer("warmupSec", "Прогрев", "Нагрузка перед замером, чтобы прогрелись кеши",
                5, 0, 120, "с"));
        params.addAll(List.of(scenarioParams));
        return params;
    }

    private static FieldSpec datasetRows(int defaultRows) {
        return integer("datasetRows", "Количество строк", "Начальный объём тестовой таблицы",
                defaultRows, 10_000, 10_000_000, "строк");
    }

    private static FieldSpec distribution() {
        return enumeration("distribution", "Распределение обращений",
                "uniform — равномерно, hotspot — 90% обращений к 10% строк", "uniform", "uniform", "hotspot");
    }
}
