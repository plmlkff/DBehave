package io.dbehave.plugin.mongo;

import io.dbehave.plugin.FieldSpec;
import io.dbehave.plugin.Scenario;

import java.util.ArrayList;
import java.util.List;

import static io.dbehave.plugin.FieldSpec.bool;
import static io.dbehave.plugin.FieldSpec.enumeration;
import static io.dbehave.plugin.FieldSpec.integer;

final class MongoScenarios {
    static final FieldSpec CACHE_SIZE = integer("cacheSizeMB", "Кеш WiredTiger",
            "Лимит кеша WiredTiger. Лимит памяти контейнера должен оставлять запас для запросов и файлового кеша",
            256, 256, 16_384, "МиБ");
    static final FieldSpec BLOCK_COMPRESSOR = enumeration("blockCompressor", "Сжатие коллекции",
            "Алгоритм сжатия данных новой коллекции на диске", "snappy", "none", "snappy", "zstd");
    static final FieldSpec JOURNAL_ACKNOWLEDGED = bool("journalAcknowledged", "Подтверждение после записи в журнал",
            "При w: 1 клиент ждёт записи на диск при включении этой настройки", false);
    static final FieldSpec JOURNAL_COMPRESSOR = enumeration("journalCompressor", "Сжатие журнала",
            "Алгоритм сжатия журнала WiredTiger", "snappy", "none", "snappy", "zstd");
    static final FieldSpec PREFIX_COMPRESSION = bool("prefixCompression", "Сжатие префиксов индекса",
            "Сжимать общие префиксы ключей. Для сравнения включите индекс по SKU", true);
    static final FieldSpec ALLOW_DISK_USE = bool("allowDiskUse", "Разрешить отчёту временные файлы",
            "При превышении 100 МиБ на сортировку отчёт использует диск или возвращает ошибку", true);

    private MongoScenarios() {
    }

    static List<Scenario> all() {
        return List.of(
                new Scenario("write-heavy", "Интенсивная запись",
                        "Параллельные вставки пачками. Сравните подтверждение записи, стоимость индексов и сжатие.",
                        List.of(JOURNAL_ACKNOWLEDGED, BLOCK_COMPRESSOR, JOURNAL_COMPRESSOR),
                        load(integer("batchSize", "Документов в пачке", "Количество документов в одной вставке",
                                        1, 1, 1000, null),
                                integer("extraIndexes", "Дополнительные индексы", "Число индексов сверх _id",
                                        0, 0, 5, null),
                                payloadBytes(200))),
                new Scenario("read-heavy", "Интенсивное чтение",
                        "Выборки по ключу, агрегаты по категории и отчёты с сортировкой.",
                        List.of(CACHE_SIZE, ALLOW_DISK_USE),
                        load(datasetRows(1_000_000),
                                weight("pointWeight", "Вес точечных выборок", "Поиск по _id", 90),
                                weight("rangeWeight", "Вес агрегатов", "Число документов и среднее значение по категории", 8),
                                weight("reportWeight", "Вес отчётов", "Сортировка диапазона по value и payload", 2),
                                bool("categoryIndex", "Индекс по категории", "Создать индекс для агрегатов по категории", false),
                                distribution(),
                                integer("reportRows", "Документов в отчёте", "Размер диапазона для сортировки, не больше размера набора",
                                        100_000, 1_000, 1_000_000, null),
                                payloadBytes(64))),
                new Scenario("mixed", "Смешанная нагрузка",
                        "Чтения, атомарные обновления и вставки. Сравните равномерный доступ и горячие ключи.",
                        List.of(),
                        load(datasetRows(500_000),
                                weight("readWeight", "Вес чтений", "Поиск по _id", 70),
                                weight("updateWeight", "Вес обновлений", "Атомарное обновление одного документа", 20),
                                weight("insertWeight", "Вес вставок", "Вставка одного документа", 10),
                                distribution())),
                new Scenario("array-index", "Поиск по массиву в документе",
                        "Поиск заказов по SKU в массиве позиций. Сравните планы запросов и размер multikey-индекса.",
                        List.of(PREFIX_COMPRESSION),
                        load(integer("datasetRows", "Количество заказов", "Начальное число документов заказов",
                                        100_000, 1_000, 1_000_000, null),
                                bool("multikeyIndex", "Индекс по SKU", "Создать индекс на items.sku", true),
                                integer("itemsPerOrder", "Позиций в заказе", "Количество разных SKU в массиве",
                                        20, 2, 100, null)))
        );
    }

    private static List<FieldSpec> load(FieldSpec... fields) {
        var params = new ArrayList<>(List.of(
                integer("clients", "Клиенты", "Число параллельных клиентов", 16, 1, 256, null),
                integer("durationSec", "Длительность нагрузки", "Длительность основной нагрузки", 30, 5, 600, "с"),
                integer("warmupSec", "Прогрев", "Длительность прогрева перед замером", 5, 0, 120, "с")));
        params.addAll(List.of(fields));
        return params;
    }

    private static FieldSpec datasetRows(int defaultRows) {
        return integer("datasetRows", "Количество документов", "Начальный размер набора",
                defaultRows, 10_000, 10_000_000, null);
    }

    private static FieldSpec payloadBytes(int defaultBytes) {
        return integer("payloadBytes", "Размер полезных данных", "Размер поля payload",
                defaultBytes, 16, 4000, "Б");
    }

    private static FieldSpec distribution() {
        return enumeration("distribution", "Распределение обращений",
                "uniform — равномерно, hotspot — 90% обращений к 10% исходных документов",
                "uniform", "uniform", "hotspot");
    }

    private static FieldSpec weight(String key, String title, String description, int defaultValue) {
        return integer(key, title, description, defaultValue, 0, 100, null);
    }
}
