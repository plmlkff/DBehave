package io.dbehave.plugin.postgres.load;

import io.dbehave.plugin.load.HttpEndpoints;
import io.dbehave.plugin.load.RunState;
import io.dbehave.plugin.postgres.load.scenario.Scenarios;
import io.prometheus.metrics.expositionformats.PrometheusTextFormatWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoadGeneratorTest {
    @ParameterizedTest
    @CsvSource({
            "40001, SERIALIZATION_FAILURE",
            "40P01, DEADLOCK",
            "55P03, LOCK_TIMEOUT",
            "57014, STATEMENT_TIMEOUT",
            "25P03, IDLE_SESSION_TERMINATED",
            "53300, TOO_MANY_CONNECTIONS",
            "08006, CONNECTION_ERROR",
            "23505, CONSTRAINT_VIOLATION",
            "42P01, OTHER"
    })
    void classifiesSqlStates(String state, ErrorKind expected) {
        assertThat(ErrorKind.of(new SQLException("x", state))).isEqualTo(expected);
    }

    @Test
    void measureCountsErrorsAndDetectsBrokenConnections() {
        var metrics = new Metrics();
        assertThat(metrics.measure("op", () -> {})).isNull();
        assertThat(metrics.measure("op", () -> {
            throw new SQLException("conflict", "40001");
        })).isEqualTo(ErrorKind.SERIALIZATION_FAILURE);
        assertThatThrownBy(() -> metrics.measure("op", () -> {
            throw new SQLException("gone", "08006");
        })).isInstanceOf(Metrics.ConnectionLostException.class);

        String scrape = scrape(metrics);
        assertThat(scrape)
                .contains("dbehave_operations_total{op=\"op\"} 1")
                .contains("dbehave_errors_total{kind=\"serialization_failure\",op=\"op\"} 1")
                .contains("dbehave_operation_duration_seconds_bucket");
    }

    @Test
    void healthReflectsPhaseAndEscapesErrors() {
        var state = new RunState(new Metrics());
        assertThat(health(state)).isEqualTo("{\"status\":\"STARTING\",\"phase\":\"STARTING\",\"progress\":0,\"error\":null}");

        state.phase(RunState.Phase.LOAD);
        state.progress(47);
        assertThat(health(state)).contains("\"status\":\"RUNNING\"", "\"phase\":\"LOAD\"", "\"progress\":47");

        state.failed("relation \"items\" does not exist\nline 2");
        assertThat(health(state)).contains("\"status\":\"FAILED\"",
                "\"error\":\"relation \\\"items\\\" does not exist\\nline 2\"");
    }

    @Test
    void readsTypedConfiguration() {
        var config = new Config(Map.of("SCENARIO", "write-heavy", "CLIENTS", "8", "CATEGORY_INDEX", "true",
                "DB_HOST", "db"));
        assertThat(config.clients()).isEqualTo(8);
        assertThat(config.bool("CATEGORY_INDEX", false)).isTrue();
        assertThat(config.jdbcUrl()).isEqualTo("jdbc:postgresql://db:5432/dbehave");
        assertThat(config.duration().toSeconds()).isEqualTo(30);
    }

    @Test
    void loadParamsExcludeConnectionAndImageVariables() {
        var config = new Config(Map.of("SCENARIO", "write-heavy", "CLIENTS", "8", "BATCH_SIZE", "10",
                "DB_PASSWORD", "secret", "PGPASSWORD", "secret", "LC_ALL", "C", "LANGUAGE", "en", "PWD", "/app"));
        assertThat(config.loadParams()).containsExactly(Map.entry("batch_size", "10"), Map.entry("clients", "8"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLIENTS=abc", "CLIENTS=0", "BATCH_SIZE=5000", "DISTRIBUTION=zipf"})
    void rejectsInvalidParameters(String assignment) {
        String[] parts = assignment.split("=");
        String scenario = parts[0].equals("DISTRIBUTION") ? "read-heavy" : "write-heavy";
        var config = new Config(Map.of("SCENARIO", scenario, parts[0], parts[1]));
        var ctx = new ScenarioContext(config, new Db(config), new Metrics(), new RunState(new Metrics()), new AtomicBoolean());
        assertThatThrownBy(() -> {
            config.clients();
            Scenarios.create(ctx);
        }).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createsEveryScenarioFromDefaults() {
        for (String scenario : new String[]{"write-heavy", "read-heavy", "mixed", "long-tx-locks", "isolation"}) {
            var config = new Config(Map.of("SCENARIO", scenario));
            var ctx = new ScenarioContext(config, new Db(config), new Metrics(), new RunState(new Metrics()), new AtomicBoolean());
            assertThat(Scenarios.create(ctx)).isNotNull();
        }
        var unknown = new Config(Map.of("SCENARIO", "nope"));
        assertThatThrownBy(() -> Scenarios.create(new ScenarioContext(unknown, new Db(unknown), new Metrics(),
                new RunState(new Metrics()), new AtomicBoolean()))).isInstanceOf(IllegalArgumentException.class);
    }

    private static String health(RunState state) {
        return new String(HttpEndpoints.healthJson(state), StandardCharsets.UTF_8);
    }

    private static String scrape(Metrics metrics) {
        try {
            var out = new ByteArrayOutputStream();
            PrometheusTextFormatWriter.create().write(out, metrics.registry().scrape());
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
