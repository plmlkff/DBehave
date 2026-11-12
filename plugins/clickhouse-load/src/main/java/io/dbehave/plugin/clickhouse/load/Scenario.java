package io.dbehave.plugin.clickhouse.load;

interface Scenario extends AutoCloseable {
    void prepare();

    Runnable client(int index);

    void verify();

    @Override
    default void close() {
    }

    static Scenario create(Config config, ClickHouse database, Metrics metrics) {
        return switch (config.scenario()) {
            case "small-inserts" -> new SmallInserts(config, database, metrics);
            case "sorting-key" -> new SortingKey(config, database, metrics);
            case "bloom-filter" -> new BloomFilter(config, database, metrics);
            case "replacing-merge-tree" -> new ReplacingMergeTree(config, database, metrics);
            default -> throw new IllegalArgumentException("Неизвестный сценарий: " + config.scenario());
        };
    }
}
