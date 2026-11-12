package io.dbehave.plugin.clickhouse.load;

import io.dbehave.plugin.load.RunState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static io.dbehave.plugin.clickhouse.load.LoadGeneratorTest.scrape;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "DBEHAVE_TEST_CLICKHOUSE_URL", matches = ".+")
class ClickHouseIntegrationTest {
    @ParameterizedTest
    @CsvSource({"small-inserts,BUFFERED,false,insert", "small-inserts,BUFFERED,true,insert",
            "sorting-key,SORTED_KEY,false,range", "sorting-key,SORTED_KEY,true,range",
            "bloom-filter,BLOOM_FILTER,false,lookup", "bloom-filter,BLOOM_FILTER,true,lookup",
            "replacing-merge-tree,FINAL_READ,false,deduplicate", "replacing-merge-tree,FINAL_READ,true,deduplicate"})
    void runsEveryScenarioAndSwitchAgainstClickHouse(String scenario, String name, String value, String operation)
            throws Exception {
        var env = environment(Map.of("SCENARIO", scenario, name, value, "DATASET_ROWS", "10000",
                "CLIENTS", "2", "DURATION_SEC", "5", "WARMUP_SEC", "0", "BATCH_SIZE", "16"));
        var metrics = new Metrics();
        var state = new RunState(metrics);
        try (var database = new ClickHouse(env, new Config(env))) {
            try {
                Main.run(env, state, metrics);
                assertThat(state.status()).as(state.error()).isEqualTo(RunState.Status.DONE);
                assertThat(scrape(metrics)).contains("dbehave_operations_total{op=\"" + operation + "\"}",
                        "dbehave_clickhouse_active_parts", "dbehave_load_end_timestamp_seconds",
                        "dbehave_run_info{engine=\"clickhouse\",");
                if (scenario.equals("small-inserts")) {
                    long persisted = database.queryLong("SELECT count() FROM events");
                    assertThat(persisted).isPositive().isEqualTo(database.queryLong("SELECT uniqExact(id) FROM events"));
                    assertThat(scrape(metrics)).contains("dbehave_clickhouse_verified_rows{kind=\"persisted\"} " + persisted + ".0");
                }
                if (scenario.equals("replacing-merge-tree")) {
                    assertThat(scrape(metrics)).contains("dbehave_clickhouse_verified_rows{kind=\"physical\"} 30000.0",
                            "dbehave_clickhouse_verified_rows{kind=\"logical\"} 10000.0",
                            "dbehave_clickhouse_verified_rows{kind=\"visible\"} " + (Boolean.parseBoolean(value) ? "10000.0" : "30000.0"));
                }
            } finally {
                database.query("DROP DATABASE IF EXISTS " + database.database());
            }
        }
    }

    @Test
    void sortingKeyPrunesReadRowsForTheSameRangeSum() {
        long ordered = rangeReadRows(true);
        long unordered = rangeReadRows(false);
        assertThat(ordered).isPositive().isLessThan(unordered);
    }

    private long rangeReadRows(boolean sorted) {
        var env = environment(Map.of("SCENARIO", "sorting-key", "DATASET_ROWS", "65536", "SORTED_KEY", Boolean.toString(sorted)));
        var config = new Config(env);
        var metrics = new Metrics();
        try (var database = new ClickHouse(env, config)) {
            try {
                ready(database, metrics);
                new SortingKey(config, database, metrics).prepare();
                var result = database.query(SortingKey.rangeQuery(100, 1000));
                assertThat(result.body().strip()).isEqualTo("499500");
                return result.summary().readRows();
            } finally {
                database.query("DROP DATABASE IF EXISTS " + database.database());
            }
        }
    }

    @Test
    void bloomFilterPrunesReadRowsForTheSameLookup() {
        long indexed = lookupReadRows(true);
        long unindexed = lookupReadRows(false);
        assertThat(indexed).isPositive().isLessThan(unindexed);
    }

    private long lookupReadRows(boolean indexed) {
        var env = environment(Map.of("SCENARIO", "bloom-filter", "DATASET_ROWS", "65536", "BLOOM_FILTER", Boolean.toString(indexed)));
        var config = new Config(env);
        var metrics = new Metrics();
        try (var database = new ClickHouse(env, config)) {
            try {
                ready(database, metrics);
                new BloomFilter(config, database, metrics).prepare();
                var result = database.query(BloomFilter.lookupQuery(12345));
                assertThat(result.body().strip()).isEqualTo("12346");
                return result.summary().readRows();
            } finally {
                database.query("DROP DATABASE IF EXISTS " + database.database());
            }
        }
    }

    @Test
    void bufferFlushPersistsAcknowledgedBatchesIncludingWarmup() throws Exception {
        var env = environment(Map.of("SCENARIO", "small-inserts", "BUFFERED", "true", "CLIENTS", "1",
                "BATCH_SIZE", "1", "DURATION_SEC", "5", "WARMUP_SEC", "1"));
        var metrics = new Metrics();
        var state = new RunState(metrics);
        try (var database = new ClickHouse(env, new Config(env))) {
            try {
                Main.run(env, state, metrics);
                assertThat(state.status()).as(state.error()).isEqualTo(RunState.Status.DONE);
                long rows = database.queryLong("SELECT count() FROM events");
                assertThat(rows).isPositive();
                assertThat(scrape(metrics)).contains("dbehave_clickhouse_verified_rows{kind=\"acknowledged\"} " + rows + ".0",
                        "dbehave_clickhouse_verified_rows{kind=\"persisted\"} " + rows + ".0");
            } finally {
                database.query("DROP DATABASE IF EXISTS " + database.database());
            }
        }
    }

    @Test
    void failsWhenAllLoadOperationsFail() {
        var env = environment(Map.of("SCENARIO", "sorting-key", "CLIENTS", "1", "DURATION_SEC", "5", "WARMUP_SEC", "0"));
        var config = new Config(env);
        var metrics = new Metrics();
        var state = new RunState(metrics);
        try (var database = new ClickHouse(env, config)) {
            try {
                ready(database, metrics);
                var scenario = new Scenario() {
                    public void prepare() { }
                    public Runnable client(int index) {
                        return () -> metrics.measure("range", () -> database.query("SELECT sum(amount) FROM missing"));
                    }
                    public void verify() { }
                };
                assertThatThrownBy(() -> new LoadEngine(config, database, state, metrics).run(scenario))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("ни одной успешной операции");
                assertThat(metrics.successfulOperations()).isZero();
            } finally {
                database.query("DROP DATABASE IF EXISTS " + database.database());
            }
        }
    }

    private static void ready(ClickHouse database, Metrics metrics) {
        try {
            database.awaitReady(new RunState(metrics));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static Map<String, String> environment(Map<String, String> parameters) {
        var uri = URI.create(System.getenv("DBEHAVE_TEST_CLICKHOUSE_URL"));
        var env = new HashMap<>(parameters);
        env.put("DB_HOST", uri.getHost());
        env.put("DB_PORT", Integer.toString(uri.getPort() < 0 ? 8123 : uri.getPort()));
        env.put("DB_NAME", "dbehave_test_" + UUID.randomUUID().toString().replace("-", ""));
        return env;
    }
}
