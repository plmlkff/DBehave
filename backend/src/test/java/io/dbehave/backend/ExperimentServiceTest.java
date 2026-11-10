package io.dbehave.backend;

import io.dbehave.plugin.postgres.PostgresPlugin;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExperimentServiceTest {
    @Test
    void mapsDefaultsAndOverridesToSeparateContainers() {
        var registry = new PluginRegistry(List.of(new PostgresPlugin()));
        var service = new ExperimentService(registry);

        var plan = service.prepare(new ExperimentRequest(
                "postgres", "mixed", Map.of("shared_buffers", 128), Map.of("rows", 100_000)));

        assertThat(plan.database().image()).isEqualTo("postgres:18");
        assertThat(plan.database().environment())
                .containsEntry("DBEHAVE_SHARED_BUFFERS", "128MB")
                .containsEntry("DBEHAVE_MAX_CONNECTIONS", "100");
        assertThat(plan.loadGenerator().image()).isEqualTo("dbehave/postgres-load:mixed");
        assertThat(plan.loadGenerator().environment())
                .containsEntry("SCENARIO", "mixed")
                .containsEntry("ROWS", "100000")
                .containsEntry("CLIENTS", "32");
    }

    @Test
    void mapsReadHeavyConfigurationToItsContainers() {
        var registry = new PluginRegistry(List.of(new PostgresPlugin()));
        var service = new ExperimentService(registry);

        var plan = service.prepare(new ExperimentRequest(
                "postgres", "read-heavy", Map.of("random_page_cost", new BigDecimal("1.25")),
                Map.of("categoryIndex", true, "distribution", "hotspot")));

        assertThat(plan.database().environment()).containsEntry("DBEHAVE_RANDOM_PAGE_COST", "1.25");
        assertThat(plan.loadGenerator().image()).isEqualTo("dbehave/postgres-load:read-heavy");
        assertThat(plan.loadGenerator().environment())
                .containsEntry("ROWS", "1000000")
                .containsEntry("CATEGORY_INDEX", "true")
                .containsEntry("DISTRIBUTION", "hotspot")
                .doesNotContainKey("READ_PERCENT");
    }

    @Test
    void mapsWriteHeavyConfigurationToItsContainers() {
        var service = new ExperimentService(new PluginRegistry(List.of(new PostgresPlugin())));
        var plan = service.prepare(new ExperimentRequest(
                "postgres", "write-heavy", Map.of("synchronous_commit", "off"), Map.of("batchSize", 100)));

        assertThat(plan.database().environment()).containsEntry("DBEHAVE_SYNCHRONOUS_COMMIT", "off");
        assertThat(plan.database().command().getLast())
                .contains("synchronous_commit=${DBEHAVE_SYNCHRONOUS_COMMIT}");
        assertThat(plan.loadGenerator().image()).isEqualTo("dbehave/postgres-load:write-heavy");
        assertThat(plan.loadGenerator().environment())
                .containsEntry("BATCH_SIZE", "100")
                .containsEntry("EXTRA_INDEXES", "0")
                .containsEntry("PAYLOAD_BYTES", "200");
    }

    @Test
    void mapsLockConfigurationToItsContainers() {
        var service = new ExperimentService(new PluginRegistry(List.of(new PostgresPlugin())));
        var plan = service.prepare(new ExperimentRequest(
                "postgres", "long-tx-locks", Map.of("lock_timeout", 500), Map.of("blockerMode", "active")));

        assertThat(plan.database().environment()).containsEntry("DBEHAVE_LOCK_TIMEOUT", "500");
        assertThat(plan.database().command().getLast()).contains("lock_timeout=${DBEHAVE_LOCK_TIMEOUT}");
        assertThat(plan.loadGenerator().image()).isEqualTo("dbehave/postgres-load:long-tx-locks");
        assertThat(plan.loadGenerator().environment())
                .containsEntry("HOLD_SEC", "15")
                .containsEntry("BLOCKER_MODE", "active");
    }

    @Test
    void passesIsolationLevelToTheLoadGenerator() {
        var service = new ExperimentService(new PluginRegistry(List.of(new PostgresPlugin())));
        var plan = service.prepare(new ExperimentRequest(
                "postgres", "isolation", Map.of(), Map.of("isolationLevel", "REPEATABLE READ")));

        assertThat(plan.loadGenerator().image()).isEqualTo("dbehave/postgres-load:isolation");
        assertThat(plan.loadGenerator().environment())
                .containsEntry("ISOLATION_LEVEL", "REPEATABLE READ")
                .containsEntry("COUNTER_ROWS", "10")
                .containsEntry("WRITE_MODE", "read-modify-write");
    }
}
