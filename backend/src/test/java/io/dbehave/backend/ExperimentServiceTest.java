package io.dbehave.backend;

import io.dbehave.plugin.postgres.PostgresPlugin;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExperimentServiceTest {
    private final ExperimentService service = new ExperimentService(new PluginRegistry(List.of(new PostgresPlugin())));

    @Test
    void mapsDefaultsAndOverridesToSeparateContainers() {
        var plan = service.prepare(new ExperimentRequest(
                "postgres", "mixed", Map.of("shared_buffers", 256), Map.of("datasetRows", 100_000)));

        assertThat(plan.database().image()).isEqualTo("postgres:18");
        assertThat(plan.database().command()).contains("shared_buffers=256MB", "max_connections=100");
        assertThat(plan.loadGenerator().image()).isEqualTo(PostgresPlugin.LOAD_GENERATOR_IMAGE);
        assertThat(plan.loadGenerator().environment())
                .containsEntry("SCENARIO", "mixed")
                .containsEntry("DATASET_ROWS", "100000")
                .containsEntry("CLIENTS", "16");
    }

    @Test
    void mapsReadHeavyConfigurationToItsContainers() {
        var plan = service.prepare(new ExperimentRequest(
                "postgres", "read-heavy", Map.of("random_page_cost", new BigDecimal("1.25")),
                Map.of("categoryIndex", true, "distribution", "hotspot")));

        assertThat(plan.database().command()).contains("random_page_cost=1.25");
        assertThat(plan.loadGenerator().environment())
                .containsEntry("DATASET_ROWS", "1000000")
                .containsEntry("CATEGORY_INDEX", "true")
                .containsEntry("DISTRIBUTION", "hotspot")
                .doesNotContainKey("READ_WEIGHT");
    }

    @Test
    void mapsWriteHeavyConfigurationToItsContainers() {
        var plan = service.prepare(new ExperimentRequest(
                "postgres", "write-heavy", Map.of("synchronous_commit", "off"), Map.of("batchSize", 100)));

        assertThat(plan.database().command()).contains("synchronous_commit=off", "fsync=on");
        assertThat(plan.loadGenerator().environment())
                .containsEntry("BATCH_SIZE", "100")
                .containsEntry("EXTRA_INDEXES", "0")
                .containsEntry("PAYLOAD_BYTES", "200");
    }

    @Test
    void mapsLockConfigurationToItsContainers() {
        var plan = service.prepare(new ExperimentRequest(
                "postgres", "long-tx-locks", Map.of("lock_timeout", 500), Map.of("blockerMode", "active")));

        assertThat(plan.database().command()).contains("lock_timeout=500ms");
        assertThat(plan.loadGenerator().environment())
                .containsEntry("HOLD_SEC", "15")
                .containsEntry("BLOCKER_MODE", "active");
    }

    @Test
    void passesIsolationLevelToTheLoadGenerator() {
        var plan = service.prepare(new ExperimentRequest(
                "postgres", "isolation", Map.of(), Map.of("isolationLevel", "REPEATABLE READ")));

        assertThat(plan.loadGenerator().environment())
                .containsEntry("ISOLATION_LEVEL", "REPEATABLE READ")
                .containsEntry("ANOMALY", "lost-update")
                .containsEntry("COUNTER_ROWS", "10")
                .containsEntry("WRITE_MODE", "read-modify-write");
    }
}
