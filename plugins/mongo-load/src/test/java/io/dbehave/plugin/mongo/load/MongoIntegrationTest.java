package io.dbehave.plugin.mongo.load;

import com.mongodb.ConnectionString;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import io.dbehave.plugin.load.RunState;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.dbehave.plugin.mongo.load.LoadGeneratorTest.scrape;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "DBEHAVE_TEST_MONGO_URI", matches = ".+")
class MongoIntegrationTest {
    private static final String URI = System.getenv("DBEHAVE_TEST_MONGO_URI");

    @ParameterizedTest
    @CsvSource({"write-heavy,insert", "read-heavy,point", "mixed,update", "array-index,lookup"})
    void runsEveryScenarioAgainstMongo(String scenario, String operation) throws Exception {
        try (var client = MongoClients.create(URI)) {
            var database = client.getDatabase(databaseName());
            var address = new ServerAddress(new ConnectionString(URI).getHosts().getFirst());
            var env = new HashMap<>(Map.of(
                    "DB_HOST", address.getHost(), "DB_PORT", Integer.toString(address.getPort()), "DB_NAME", database.getName(),
                    "SCENARIO", scenario, "CLIENTS", "2", "DURATION_SEC", "5", "WARMUP_SEC", "0", "DATASET_ROWS", "10000"));
            env.put("MONGO_JOURNAL_ACKNOWLEDGED", "true");
            env.put("BATCH_SIZE", "8");

            var metrics = new Metrics();
            var state = new RunState(metrics);
            try {
                Main.run(env, state, metrics);
                assertThat(state.status()).as(state.error()).isEqualTo(RunState.Status.DONE);
                assertThat(scrape(metrics)).contains("dbehave_operations_total{op=\"" + operation + "\"}",
                        "dbehave_mongo_cache_bytes{kind=\"limit\"}", "dbehave_load_end_timestamp_seconds",
                        "dbehave_run_info{engine=\"mongo\",");
                if (scenario.equals("write-heavy")) {
                    assertThat(scrape(metrics)).contains("dbehave_records_written_total{op=\"insert\"}",
                            "dbehave_mongo_storage_bytes_per_document", "journalCompressor=snappy")
                            .containsPattern("(?m)^dbehave_mongo_journal_written_bytes_total [1-9]");
                }
            } finally {
                database.drop();
            }
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DBEHAVE_TEST_MONGO_UNCOMPRESSED_URI", matches = ".+")
    void journalCompressionReducesBytesForTheSameNumberOfWrites() {
        try (var compressed = MongoClients.create(URI);
             var uncompressed = MongoClients.create(System.getenv("DBEHAVE_TEST_MONGO_UNCOMPRESSED_URI"))) {
            assertThat(journalBytesForFixedBatchCount(compressed))
                    .isPositive().isLessThan(journalBytesForFixedBatchCount(uncompressed));
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DBEHAVE_TEST_MONGO_UNCOMPRESSED_URI", matches = ".+")
    void prefixCompressionReducesSizeOfTheSameMultikeyIndex() {
        try (var compressed = MongoClients.create(URI);
             var uncompressed = MongoClients.create(System.getenv("DBEHAVE_TEST_MONGO_UNCOMPRESSED_URI"))) {
            assertThat(skuIndexBytes(compressed)).isPositive().isLessThan(skuIndexBytes(uncompressed));
        }
    }

    private static long journalBytesForFixedBatchCount(MongoClient client) {
        var database = client.getDatabase(databaseName());
        var admin = client.getDatabase("admin");
        var config = new Config(Map.of("SCENARIO", "write-heavy", "MONGO_JOURNAL_ACKNOWLEDGED", "true",
                "BATCH_SIZE", "64", "PAYLOAD_BYTES", "2048"));
        try {
            var scenario = new WriteHeavy(config, database, new Metrics());
            scenario.prepare();
            var step = scenario.client(0);
            long before = journalBytes(admin);
            for (int batch = 0; batch < 64; batch++) {
                step.run();
            }
            long bytes = journalBytes(admin) - before;
            assertThat(database.getCollection("events").countDocuments()).isEqualTo(4096);
            return bytes;
        } finally {
            database.drop();
        }
    }

    private static long journalBytes(MongoDatabase admin) {
        return admin.runCommand(new Document("serverStatus", 1))
                .getEmbedded(List.of("wiredTiger", "log", "log bytes written"), Number.class).longValue();
    }

    private static long skuIndexBytes(MongoClient client) {
        var database = client.getDatabase(databaseName());
        var config = new Config(Map.of("SCENARIO", "array-index", "DATASET_ROWS", "10000", "ITEMS_PER_ORDER", "20"));
        var metrics = new Metrics();
        try {
            new ArrayIndex(config, database, metrics, new RunState(metrics)).prepare();
            var stats = Data.storageStats(client.getDatabase("admin"), database.getCollection("orders"));
            return stats.getEmbedded(List.of("indexSizes", "items.sku_1"), Number.class).longValue();
        } finally {
            database.drop();
        }
    }

    @Test
    void multikeyIndexScansFewerDocumentsForTheSameResults() throws Exception {
        try (var client = MongoClients.create(URI)) {
            var admin = client.getDatabase("admin");
            for (boolean indexed : new boolean[]{false, true}) {
                var database = client.getDatabase(databaseName());
                var metrics = new Metrics();
                var scenario = new ArrayIndex(new Config(Map.of("SCENARIO", "array-index", "DATASET_ROWS", "1024",
                        "ITEMS_PER_ORDER", "20", "MULTIKEY_INDEX", Boolean.toString(indexed))),
                        database, metrics, new RunState(metrics));
                try {
                    scenario.prepare();
                    scenario.client(0).run();
                    scenario.verify(admin);
                    assertThat(scrape(metrics)).contains(
                            "dbehave_mongo_query_explain{kind=\"returned\"} 2.0",
                            "dbehave_mongo_query_explain{kind=\"documents\"} " + (indexed ? "2.0" : "1024.0"));
                } finally {
                    database.drop();
                }
            }
        }
    }

    @Test
    void reportSpillsOrReturnsMemoryLimitAccordingToAllowDiskUse() throws Exception {
        try (var client = MongoClients.create(URI)) {
            MongoDatabase database = client.getDatabase(databaseName());
            var env = new HashMap<>(Map.of("SCENARIO", "read-heavy", "DATASET_ROWS", "100000", "PAYLOAD_BYTES", "2048",
                    "POINT_WEIGHT", "0", "RANGE_WEIGHT", "0", "REPORT_WEIGHT", "100"));
            try {
                var enabled = new Metrics();
                var spills = new ReadHeavy(new Config(env), database, enabled, new RunState(enabled));
                spills.prepare();
                spills.client(0).run();
                spills.verify(client.getDatabase("admin"));
                assertThat(scrape(enabled)).contains("dbehave_operations_total{op=\"report\"} 1.0",
                        "dbehave_mongo_report_used_disk{op=\"report\"} 1.0");

                env.put("MONGO_ALLOW_DISK_USE", "false");
                var disabled = new Metrics();
                var fails = new ReadHeavy(new Config(env), database, disabled, new RunState(disabled));
                fails.client(0).run();
                fails.verify(client.getDatabase("admin"));
                assertThat(scrape(disabled)).contains("dbehave_errors_total{kind=\"memory_limit\",op=\"report\"} 1.0",
                        "dbehave_mongo_report_used_disk{op=\"report\"} 0.0");
            } finally {
                database.drop();
            }
        }
    }

    private static String databaseName() {
        return "dbehave_test_" + UUID.randomUUID().toString().replace("-", "");
    }
}
