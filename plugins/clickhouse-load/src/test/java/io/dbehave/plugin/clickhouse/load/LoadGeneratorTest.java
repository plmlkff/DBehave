package io.dbehave.plugin.clickhouse.load;

import com.sun.net.httpserver.HttpServer;
import io.dbehave.plugin.load.HttpEndpoints;
import io.dbehave.plugin.load.RunState;
import io.prometheus.metrics.expositionformats.PrometheusTextFormatWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoadGeneratorTest {
    @Test
    void readsCatalogDefaultsAndOverridesWithoutPublishingCredentials() {
        var config = new Config(Map.of("SCENARIO", "small-inserts", "BUFFERED", "true", "BATCH_SIZE", "8",
                "MAX_MEMORY_USAGE_MB", "128", "DB_PASSWORD", "private"));
        assertThat(config.integer("clients")).isEqualTo(4);
        assertThat(config.integer("batchSize")).isEqualTo(8);
        assertThat(config.integer("maxMemoryUsageMB")).isEqualTo(128);
        assertThat(config.bool("buffered")).isTrue();
        assertThat(config.paramsLabel()).contains("buffered=true").doesNotContain("private", "DB_PASSWORD");
        assertThat(config.settingsLabel()).contains("maxMemoryUsageMB=128");
    }

    @ParameterizedTest
    @CsvSource({"BATCH_SIZE,0", "CLIENTS,1.5", "BUFFERED,yes", "MAX_THREADS,0", "SCENARIO,unknown"})
    void rejectsInvalidEnvironment(String name, String value) {
        assertThatThrownBy(() -> new Config(Map.of(name, value))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsesBothStringAndNumericServerSummaryValues() {
        assertThat(ClickHouse.Summary.parse("{\"read_rows\":\"1024\",\"read_bytes\":8192,\"elapsed_ns\":\"9000000\"}"))
                .isEqualTo(new ClickHouse.Summary(1024, 8192, 9_000_000));
        assertThat(ClickHouse.Summary.parse("{}" )).isEqualTo(new ClickHouse.Summary(0, 0, 0));
    }

    @Test
    void sendsQuerySettingsAndCredentialsAndReadsServerSummary() throws IOException {
        var uri = new AtomicReference<String>();
        var body = new AtomicReference<String>();
        var password = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            uri.set(exchange.getRequestURI().toString());
            password.set(exchange.getRequestHeaders().getFirst("X-ClickHouse-Key"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), UTF_8));
            exchange.getResponseHeaders().set("X-ClickHouse-Summary", "{\"read_rows\":\"100\",\"read_bytes\":\"800\"}");
            byte[] result = "42\n".getBytes(UTF_8);
            exchange.sendResponseHeaders(200, result.length);
            exchange.getResponseBody().write(result);
            exchange.close();
        });
        server.start();
        var env = Map.of("DB_HOST", "127.0.0.1", "DB_PORT", Integer.toString(server.getAddress().getPort()),
                "DB_PASSWORD", "private", "MAX_MEMORY_USAGE_MB", "128", "MAX_THREADS", "3");
        try (var database = new ClickHouse(env, new Config(env))) {
            var result = database.query("SELECT 42");
            assertThat(result.body()).isEqualTo("42\n");
            assertThat(result.summary().readRows()).isEqualTo(100);
            assertThat(result.summary().readBytes()).isEqualTo(800);
            assertThat(uri.get()).contains("max_threads=3", "max_memory_usage=134217728", "wait_end_of_query=1",
                    "async_insert=0", "log_queries=0", "use_query_cache=0", "use_query_condition_cache=0");
            assertThat(uri.get()).doesNotContain("private");
            assertThat(body.get()).isEqualTo("SELECT 42\n");
            assertThat(password.get()).isEqualTo("private");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void publishesSuccessfulQueriesAndErrorsSeparately() throws IOException {
        var metrics = new Metrics();
        var state = new RunState(metrics);
        state.phase(RunState.Phase.LOAD);
        assertThat(metrics.measure("range", () -> new ClickHouse.Result("7", new ClickHouse.Summary(1024, 8192, 5000000))))
                .isTrue();
        assertThat(metrics.measure("range", () -> { throw new ClickHouse.QueryException(241, "memory"); })).isFalse();
        assertThat(metrics.successfulOperations()).isEqualTo(1);
        assertThat(scrape(metrics)).contains("dbehave_operations_total{op=\"range\"} 1.0",
                "dbehave_errors_total{kind=\"memory_limit\",op=\"range\"} 1.0",
                "dbehave_clickhouse_read_rows_total{op=\"range\"} 1024.0",
                "dbehave_clickhouse_read_bytes_total{op=\"range\"} 8192.0",
                "dbehave_clickhouse_query_seconds_total{op=\"range\"} 0.005");
        assertThat(new String(HttpEndpoints.healthJson(state), UTF_8)).contains("\"status\":\"RUNNING\"");
    }

    @Test
    void buildsBatchesWithDistinctSequentialIds() {
        assertThat(SmallInserts.batch(10, 3, "xx")).isEqualTo("10\txx\n11\txx\n12\txx\n");
    }

    @Test
    void marksInvalidConfigurationAsFailed() {
        var metrics = new Metrics();
        var state = new RunState(metrics);
        Main.run(Map.of("SCENARIO", "missing"), state, metrics);
        assertThat(state.status()).isEqualTo(RunState.Status.FAILED);
    }

    @ParameterizedTest
    @CsvSource({"241,memory_limit", "252,too_many_parts", "159,query_timeout", "-1,connection_error", "60,query_error"})
    void classifiesClickHouseErrors(int code, String kind) {
        assertThat(Metrics.kind(code)).isEqualTo(kind);
    }

    static String scrape(Metrics metrics) throws IOException {
        var output = new ByteArrayOutputStream();
        PrometheusTextFormatWriter.create().write(output, metrics.registry().scrape());
        return output.toString(UTF_8);
    }
}
