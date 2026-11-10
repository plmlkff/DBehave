package io.dbehave.backend;

import io.dbehave.plugin.postgres.PostgresContextCollector;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExperimentServiceTest {
    @Test
    void mapsDefaultsAndOverridesToSeparateContainers() {
        var registry = registry();
        var service = new ExperimentService(registry, mock(TaskRepository.class));

        var plan = service.prepare(new ExperimentRequest(
                "postgres", "mixed", Map.of("shared_buffers", 128), Map.of("datasetRows", 100_000))).context();

        assertThat(plan.databaseImageName()).isEqualTo("postgres:18");
        assertThat(environment(plan.databaseImageContext()))
                .containsEntry("DBEHAVE_SHARED_BUFFERS", "128MB")
                .containsEntry("DBEHAVE_MAX_CONNECTIONS", "100");
        assertThat(plan.loaderImageName()).isEqualTo("dbehave/postgres-load:0.1.0");
        assertThat(environment(plan.loaderImageContext()))
                .containsEntry("SCENARIO", "mixed")
                .containsEntry("DATASET_ROWS", "100000")
                .containsEntry("CLIENTS", "16");
    }

    @Test
    void mapsReadHeavyConfigurationToItsContainers() {
        var registry = registry();
        var service = new ExperimentService(registry, mock(TaskRepository.class));

        var plan = service.prepare(new ExperimentRequest(
                "postgres", "read-heavy", Map.of("random_page_cost", new BigDecimal("1.25")),
                Map.of("categoryIndex", true, "distribution", "hotspot"))).context();

        assertThat(environment(plan.databaseImageContext())).containsEntry("DBEHAVE_RANDOM_PAGE_COST", "1.25");
        assertThat(plan.loaderImageName()).isEqualTo("dbehave/postgres-load:0.1.0");
        assertThat(environment(plan.loaderImageContext()))
                .containsEntry("DATASET_ROWS", "1000000")
                .containsEntry("CATEGORY_INDEX", "true")
                .containsEntry("DISTRIBUTION", "hotspot")
                .doesNotContainKey("READ_PERCENT");
    }

    @Test
    void mapsWriteHeavyConfigurationToItsContainers() {
        var service = new ExperimentService(registry(), mock(TaskRepository.class));
        var plan = service.prepare(new ExperimentRequest(
                "postgres", "write-heavy", Map.of("synchronous_commit", "off"), Map.of("batchSize", 100))).context();

        assertThat(environment(plan.databaseImageContext())).containsEntry("DBEHAVE_SYNCHRONOUS_COMMIT", "off");
        assertThat(command(plan.databaseImageContext()))
                .contains("synchronous_commit=off");
        assertThat(plan.loaderImageName()).isEqualTo("dbehave/postgres-load:0.1.0");
        assertThat(environment(plan.loaderImageContext()))
                .containsEntry("BATCH_SIZE", "100")
                .containsEntry("EXTRA_INDEXES", "0")
                .containsEntry("PAYLOAD_BYTES", "200");
    }

    @Test
    void mapsLockConfigurationToItsContainers() {
        var service = new ExperimentService(registry(), mock(TaskRepository.class));
        var plan = service.prepare(new ExperimentRequest(
                "postgres", "long-tx-locks", Map.of("lock_timeout", 500), Map.of("blockerMode", "active"))).context();

        assertThat(environment(plan.databaseImageContext())).containsEntry("DBEHAVE_LOCK_TIMEOUT", "500ms");
        assertThat(command(plan.databaseImageContext())).contains("lock_timeout=500ms");
        assertThat(plan.loaderImageName()).isEqualTo("dbehave/postgres-load:0.1.0");
        assertThat(environment(plan.loaderImageContext()))
                .containsEntry("HOLD_SEC", "15")
                .containsEntry("BLOCKER_MODE", "active");
    }

    @Test
    void passesIsolationLevelToTheLoadGenerator() {
        var service = new ExperimentService(registry(), mock(TaskRepository.class));
        var plan = service.prepare(new ExperimentRequest(
                "postgres", "isolation", Map.of(), Map.of("isolationLevel", "REPEATABLE READ"))).context();

        assertThat(plan.loaderImageName()).isEqualTo("dbehave/postgres-load:0.1.0");
        assertThat(environment(plan.loaderImageContext()))
                .containsEntry("ISOLATION_LEVEL", "REPEATABLE READ")
                .containsEntry("COUNTER_ROWS", "10")
                .containsEntry("WRITE_MODE", "read-modify-write");
    }

    private static PluginRegistry registry() {
        var repository = mock(PluginRepository.class);
        when(repository.findById("postgres")).thenReturn(Optional.of(PostgresContextCollector.defaultDefinition()));
        return new PluginRegistry(repository, List.of(new PostgresContextCollector()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> environment(Map<String, Object> context) {
        return (Map<String, String>) context.get("environment");
    }

    @SuppressWarnings("unchecked")
    private static List<String> command(Map<String, Object> context) {
        return (List<String>) context.get("command");
    }
}
