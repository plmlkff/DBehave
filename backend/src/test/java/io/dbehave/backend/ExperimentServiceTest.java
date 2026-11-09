package io.dbehave.backend;

import io.dbehave.plugin.postgres.PostgresPlugin;
import org.junit.jupiter.api.Test;

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
}
