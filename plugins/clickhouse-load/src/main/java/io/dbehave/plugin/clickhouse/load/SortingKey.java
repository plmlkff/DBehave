package io.dbehave.plugin.clickhouse.load;

import java.util.SplittableRandom;

final class SortingKey implements Scenario {
    private final Config config;
    private final ClickHouse database;
    private final Metrics metrics;

    SortingKey(Config config, ClickHouse database, Metrics metrics) {
        this.config = config;
        this.database = database;
        this.metrics = metrics;
    }

    @Override
    public void prepare() {
        String order = config.bool("sortedKey") ? "id" : "cityHash64(id)";
        database.query("CREATE TABLE events (id UInt64, amount UInt64) ENGINE = MergeTree ORDER BY "
                + order + " SETTINGS index_granularity = 1024");
        database.query("INSERT INTO events SELECT number, number % 1000 FROM numbers(" + config.integer("datasetRows") + ")");
    }

    @Override
    public Runnable client(int index) {
        var random = new SplittableRandom(42L + index);
        return () -> {
            long first = random.nextLong(config.integer("datasetRows") - config.integer("rangeRows") + 1L);
            metrics.measure("range", () -> database.query(rangeQuery(first, config.integer("rangeRows"))));
        };
    }

    static String rangeQuery(long first, int rows) {
        return "SELECT sum(amount) FROM events WHERE id >= " + first + " AND id < " + (first + rows);
    }

    @Override
    public void verify() {
        long rows = database.queryLong("SELECT count() FROM events");
        if (rows != config.integer("datasetRows")) {
            throw new IllegalStateException("Неверное количество подготовленных строк: " + rows);
        }
        metrics.verifiedRows("dataset", rows);
    }
}
