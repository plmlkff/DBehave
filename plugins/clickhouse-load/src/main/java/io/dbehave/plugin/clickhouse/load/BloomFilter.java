package io.dbehave.plugin.clickhouse.load;

import java.util.SplittableRandom;

final class BloomFilter implements Scenario {
    private final Config config;
    private final ClickHouse database;
    private final Metrics metrics;

    BloomFilter(Config config, ClickHouse database, Metrics metrics) {
        this.config = config;
        this.database = database;
        this.metrics = metrics;
    }

    @Override
    public void prepare() {
        String index = config.bool("bloomFilter") ? ", INDEX token_bloom token TYPE bloom_filter(0.01) GRANULARITY 1" : "";
        database.query("CREATE TABLE events (id UInt64, token String, amount UInt64" + index
                + ") ENGINE = MergeTree ORDER BY id SETTINGS index_granularity = 1024");
        database.query("INSERT INTO events SELECT number, lower(hex(MD5(toString(number)))), number + 1 FROM numbers("
                + config.integer("datasetRows") + ")");
    }

    @Override
    public Runnable client(int index) {
        var random = new SplittableRandom(42L + index);
        return () -> {
            long id = random.nextLong(config.integer("datasetRows"));
            metrics.measure("lookup", () -> database.query(lookupQuery(id)));
        };
    }

    static String lookupQuery(long id) {
        return "SELECT sum(amount) FROM events WHERE token = lower(hex(MD5('" + id + "')))";
    }

    @Override
    public void verify() {
        long value = database.queryLong(lookupQuery(42));
        if (value != 43) {
            throw new IllegalStateException("Неверный результат поиска по token: " + value);
        }
        metrics.verifiedRows("lookup_matches", 1);
    }
}
