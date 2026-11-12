package io.dbehave.plugin.clickhouse.load;

import java.util.SplittableRandom;

final class ReplacingMergeTree implements Scenario {
    private final Config config;
    private final ClickHouse database;
    private final Metrics metrics;
    private boolean mergesStopped;

    ReplacingMergeTree(Config config, ClickHouse database, Metrics metrics) {
        this.config = config;
        this.database = database;
        this.metrics = metrics;
    }

    @Override
    public void prepare() {
        database.query("CREATE TABLE events (id UInt64, version UInt32, amount UInt64) "
                + "ENGINE = ReplacingMergeTree(version) ORDER BY id SETTINGS index_granularity = 1024");
        database.query("SYSTEM STOP MERGES events");
        mergesStopped = true;
        for (int version = 1; version <= config.integer("versions"); version++) {
            database.query("INSERT INTO events SELECT number, " + version + ", " + version + " FROM numbers("
                    + config.integer("datasetRows") + ")");
        }
    }

    @Override
    public Runnable client(int index) {
        var random = new SplittableRandom(42L + index);
        String finalClause = config.bool("finalRead") ? " FINAL" : "";
        return () -> {
            long first = random.nextLong(config.integer("datasetRows") - 999L);
            metrics.measure("deduplicate", () -> database.query("SELECT sum(amount) FROM events" + finalClause
                    + " WHERE id >= " + first + " AND id < " + (first + 1000)));
        };
    }

    @Override
    public void verify() {
        long physical = database.queryLong("SELECT count() FROM events");
        String[] logical = database.query("SELECT count(), sum(amount) FROM events FINAL").body().strip().split("\t");
        long logicalCount = Long.parseLong(logical[0]);
        long logicalSum = Long.parseLong(logical[1]);
        long rows = config.integer("datasetRows");
        long versions = config.integer("versions");
        if (physical != rows * versions || logicalCount != rows || logicalSum != rows * versions) {
            throw new IllegalStateException("Неверный результат ReplacingMergeTree: physical=" + physical
                    + ", logical=" + logicalCount + ", sum=" + logicalSum);
        }
        metrics.verifiedRows("physical", physical);
        metrics.verifiedRows("logical", logicalCount);
        metrics.verifiedRows("visible", config.bool("finalRead") ? logicalCount : physical);
    }

    @Override
    public void close() {
        if (mergesStopped) {
            database.query("SYSTEM START MERGES events");
            mergesStopped = false;
        }
    }
}
