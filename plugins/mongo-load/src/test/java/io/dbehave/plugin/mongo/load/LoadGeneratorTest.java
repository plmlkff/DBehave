package io.dbehave.plugin.mongo.load;

import com.mongodb.MongoException;
import io.dbehave.plugin.load.HttpEndpoints;
import io.dbehave.plugin.load.RunState;
import io.prometheus.metrics.expositionformats.PrometheusTextFormatWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoadGeneratorTest {
    @ParameterizedTest
    @CsvSource({"292,memory_limit", "16819,memory_limit", "11000,duplicate_key", "112,write_conflict", "50,operation_timeout"})
    void classifiesMongoErrors(int code, String kind) {
        assertThat(Metrics.kind(new MongoException(code, "test"))).isEqualTo(kind);
    }

    @Test
    void exposesOperationMetricsAndHealth() throws IOException {
        var metrics = new Metrics();
        var state = new RunState(metrics);
        state.phase(RunState.Phase.LOAD);
        metrics.measure("report", () -> {});
        metrics.measure("report", () -> { throw new MongoException(292, "test"); });

        String samples = scrape(metrics);
        assertThat(samples).contains(
                "dbehave_operations_total{op=\"report\"} 1.0",
                "dbehave_operation_duration_seconds_count{op=\"report\"} 1",
                "dbehave_errors_total{kind=\"memory_limit\",op=\"report\"} 1.0");
        assertThat(samples.lines()).noneMatch(line -> line.startsWith("dbehave_mongo_report_used_disk")
                || line.startsWith("dbehave_mongo_storage_bytes_per_document"));
        assertThat(new String(HttpEndpoints.healthJson(state), UTF_8)).contains(
                "\"status\":\"RUNNING\"", "\"phase\":\"LOAD\"");
    }

    @Test
    void readsDefaultsAndTypedOverridesWithoutPublishingConnectionSettings() {
        var config = new Config(Map.of("SCENARIO", "read-heavy", "MONGO_ALLOW_DISK_USE", "false",
                "PAYLOAD_BYTES", "2048", "DB_PASSWORD", "private"));
        assertThat(config.integer("reportRows")).isEqualTo(100_000);
        assertThat(config.integer("payloadBytes")).isEqualTo(2048);
        assertThat(config.bool("allowDiskUse")).isFalse();
        assertThat(config.paramsLabel()).contains("payloadBytes=2048").doesNotContain("private", "DB_PASSWORD");
        assertThat(config.settingsLabel()).contains("allowDiskUse=false");
    }

    @Test
    void rejectsInvalidParameters() {
        assertThatThrownBy(() -> new Config(Map.of("BATCH_SIZE", "0")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("BATCH_SIZE");
        assertThatThrownBy(() -> new Config(Map.of("MONGO_JOURNAL_ACKNOWLEDGED", "yes")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("MONGO_JOURNAL_ACKNOWLEDGED");
        assertThatThrownBy(() -> new Data.Weights(0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    static String scrape(Metrics metrics) throws IOException {
        var output = new ByteArrayOutputStream();
        PrometheusTextFormatWriter.create().write(output, metrics.registry().scrape());
        return output.toString(UTF_8);
    }
}
