package io.dbehave.plugin.clickhouse;

import io.dbehave.plugin.FieldSpec;
import io.dbehave.plugin.Scenario;

import java.util.ArrayList;
import java.util.List;

import static io.dbehave.plugin.FieldSpec.bool;
import static io.dbehave.plugin.FieldSpec.integer;

final class ClickHouseScenarios {
    private static final List<FieldSpec> QUERY_SETTINGS = List.of(
            integer("maxThreads", "Потоки одного запроса",
                    "max_threads ограничивает параллелизм запросов, включая подготовку набора данных",
                    2, 1, 16, null),
            integer("maxMemoryUsageMB", "Память одного запроса",
                    "max_memory_usage ограничивает память запроса. Оставьте запас для фоновых слияний и файлового кеша",
                    256, 64, 4096, "МиБ"));

    private ClickHouseScenarios() {
    }

    static List<Scenario> all() {
        return List.of(
                new Scenario("small-inserts", "Мелкие вставки и Buffer",
                        "Частые INSERT создают много небольших частей MergeTree и нагружают фоновые слияния. "
                                + "Сравните прямую запись с Buffer и размер пачки. Buffer подтверждает запись в памяти, "
                                + "а данные сбрасывает в MergeTree позднее. В конце выполняется принудительный сброс и проверка строк.",
                        QUERY_SETTINGS,
                        load(integer("batchSize", "Строк в INSERT", "Размер одной пачки, отправляемой клиентом",
                                        100, 1, 10_000, "строк"),
                                bool("buffered", "Запись через Buffer",
                                        "Накапливать вставки в памяти перед записью в MergeTree. При аварии буфер может потеряться", false),
                                integer("payloadBytes", "Размер полезных данных", "Длина поля payload в каждой строке",
                                        64, 16, 1024, "Б"))),
                new Scenario("sorting-key", "Порядок сортировки и отбор гранул",
                        "Повторяются агрегаты по узкому диапазону ключей. При ORDER BY по этому ключу "
                                + "разреженный первичный индекс исключает лишние гранулы. При сортировке по другому полю "
                                + "читается больше данных. Сравните read_rows, read_bytes и задержку при одинаковом диапазоне.",
                        QUERY_SETTINGS,
                        load(datasetRows(300_000, 5_000_000),
                                bool("sortedKey", "Сортировать по полю фильтра",
                                        "Включено — ключ фильтра входит в начало ORDER BY. Выключено — данные упорядочены по другому полю", true),
                                integer("rangeRows", "Ширина диапазона", "Количество соседних значений ключа в одном запросе",
                                        1000, 1, 10_000, "строк"))),
                new Scenario("bloom-filter", "Точечный поиск и bloom_filter",
                        "Поиск по высококардинальному строковому полю, не входящему в ключ сортировки. "
                                + "Индекс bloom_filter помогает пропускать гранулы, в которых искомого значения нет. "
                                + "Сравните объём прочитанных данных и задержку с индексом и без него.",
                        QUERY_SETTINGS,
                        load(datasetRows(300_000, 5_000_000),
                                bool("bloomFilter", "Индекс bloom_filter",
                                        "Создать data skipping index bloom_filter для поля точечного поиска", true))),
                new Scenario("replacing-merge-tree", "Версии строк и FINAL",
                        "ReplacingMergeTree хранит несколько версий одного ключа до слияния частей. "
                                + "Фоновые слияния приостановлены, чтобы результат сравнения был воспроизводим. "
                                + "Без FINAL агрегат учитывает все версии, с FINAL — только последнюю. "
                                + "Сравните корректность результата и цену дедупликации при чтении.",
                        QUERY_SETTINGS,
                        load(datasetRows(100_000, 1_000_000),
                                integer("versions", "Версий одного ключа", "Число вставляемых версий каждого логического объекта",
                                        3, 2, 10, null),
                                bool("finalRead", "Чтение с FINAL", "Дедуплицировать версии во время SELECT", true)))
        );
    }

    private static List<FieldSpec> load(FieldSpec... fields) {
        var params = new ArrayList<>(List.of(
                integer("clients", "Клиенты", "Число параллельных HTTP-клиентов", 4, 1, 32, null),
                integer("durationSec", "Длительность нагрузки", "Длительность основной нагрузки", 30, 5, 600, "с"),
                integer("warmupSec", "Прогрев", "Нагрузка перед началом измерения", 5, 0, 120, "с")));
        params.addAll(List.of(fields));
        return params;
    }

    private static FieldSpec datasetRows(int defaultRows, int maxRows) {
        return integer("datasetRows", "Логических строк", "Количество уникальных ключей в исходном наборе",
                defaultRows, 10_000, maxRows, "строк");
    }
}
