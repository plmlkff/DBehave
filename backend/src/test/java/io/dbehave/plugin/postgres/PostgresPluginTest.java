package io.dbehave.plugin.postgres;

import io.dbehave.plugin.postgres.PostgresScenario.ContainerField;
import io.dbehave.plugin.postgres.PostgresScenario.Definition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.dbehave.plugin.FieldSpec.integer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresPluginTest {
    @Test
    void preparesScenarioUsingOnlyItsDeclaredFields() {
        PostgresScenario oneShot = () -> new Definition(
                "one-shot", "One-shot", "A single operation", "single",
                List.of(new ContainerField(
                        integer("extension.limit", "Limit", "Extension memory limit", 4, 1, 16, "МБ"),
                        "DBEHAVE_EXTENSION_LIMIT", "MB")),
                List.of(new ContainerField(integer("rows", "Rows", "Number of rows", 100, 1, 1000, null), "ROWS"))
        );
        var plugin = new PostgresPlugin(List.of(oneShot));
        var scenario = plugin.scenarios().getFirst();

        var plan = plugin.prepare(scenario, Map.of("extension.limit", 8), Map.of("rows", 100));

        assertThat(scenario.params()).extracting("key").containsExactly("rows");
        assertThat(plan.database().environment()).containsEntry("DBEHAVE_EXTENSION_LIMIT", "8MB");
        assertThat(plan.database().command().getLast()).contains("extension.limit=${DBEHAVE_EXTENSION_LIMIT}");
        assertThat(plan.loadGenerator().image()).isEqualTo("dbehave/postgres-load:single");
        assertThat(plan.loadGenerator().environment())
                .containsEntry("ROWS", "100")
                .doesNotContainKeys("CLIENTS", "DURATION_SEC");
    }

    @Test
    void rejectsDuplicateScenarioIdsAtRegistration() {
        assertThatThrownBy(() -> new PostgresPlugin(List.of(new MixedScenario(), new MixedScenario())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mixed");
    }
}
