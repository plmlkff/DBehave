package io.dbehave.plugin.clickhouse.load;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

final class SmallInserts implements Scenario {
    private final Config config;
    private final ClickHouse database;
    private final Metrics metrics;
    private final AtomicLong nextId = new AtomicLong();
    private final LongAdder acknowledgedRows = new LongAdder();

    SmallInserts(Config config, ClickHouse database, Metrics metrics) {
        this.config = config;
        this.database = database;
        this.metrics = metrics;
    }

    @Override
    public void prepare() {
        database.query("CREATE TABLE events (id UInt64, payload String) ENGINE = MergeTree ORDER BY id");
        if (config.bool("buffered")) {
            // Flush once any maximum is reached, or all minimums. Short time limits make the lag visible.
            database.query("CREATE TABLE input AS events ENGINE = Buffer(" + database.database()
                    + ", events, 4, 1, 5, 1000, 10000, 1048576, 10485760)");
        }
    }

    @Override
    public Runnable client(int index) {
        String payload = "x".repeat(config.integer("payloadBytes"));
        int batchSize = config.integer("batchSize");
        String insert = "INSERT INTO " + (config.bool("buffered") ? "input" : "events") + " FORMAT TabSeparated";
        return () -> {
            long firstId = nextId.getAndAdd(batchSize);
            String batch = batch(firstId, batchSize, payload);
            if (metrics.measure("insert", () -> database.query(insert, batch))) {
                acknowledgedRows.add(batchSize);
                metrics.recordsWritten("insert", batchSize);
            }
        };
    }

    static String batch(long firstId, int rows, String payload) {
        var data = new StringBuilder(rows * (payload.length() + 24));
        for (int row = 0; row < rows; row++) {
            data.append(firstId + row).append('\t').append(payload).append('\n');
        }
        return data.toString();
    }

    @Override
    public void verify() {
        if (config.bool("buffered")) {
            database.query("OPTIMIZE TABLE input");
        }
        long persisted = database.queryLong("SELECT count() FROM events");
        metrics.verifiedRows("acknowledged", acknowledgedRows.sum());
        metrics.verifiedRows("persisted", persisted);
        if (persisted < acknowledgedRows.sum()) {
            throw new IllegalStateException("После сброса Buffer потеряны подтверждённые строки: "
                    + persisted + " из " + acknowledgedRows.sum());
        }
    }
}
