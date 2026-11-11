package io.dbehave.backend.plugin;

import io.dbehave.backend.validation.FieldError;
import io.dbehave.backend.validation.InvalidExperimentException;
import io.dbehave.plugin.FieldSpec;
import io.dbehave.plugin.PluginDefinition;
import io.dbehave.plugin.Scenario;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class PluginValidationTest {
    @Test
    void acceptsAnArbitraryStoredScenarioWithAnImageAndResources() {
        assertThatCode(() -> PluginValidation.validate(plugin(
                "https://grafana.example.test/d/custom?var-run_id={runId}",
                List.of(scenario("custom", List.of(FieldSpec.integer("rps", "RPS", "", 100, 1, 1000, null)))),
                Map.of("core_max", 0.25, "mem_max", 2_147_483_648L))))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMalformedGrafanaLinksAndResourceDefaults() {
        var failure = catchThrowableOfType(() -> PluginValidation.validate(plugin(
                "javascript:alert('{runId}')", List.of(scenario("custom", List.of())), Map.of("mem_max", -1))),
                InvalidExperimentException.class);
        assertThat(failure.fieldErrors()).extracting(FieldError::field)
                .contains("grafanaUrlTemplate", "loaderImageContext.mem_max");

        var missingPlaceholder = catchThrowableOfType(() -> PluginValidation.validate(plugin(
                "https://grafana.example.test/d/custom", List.of(scenario("custom", List.of())), Map.of())),
                InvalidExperimentException.class);
        assertThat(missingPlaceholder.fieldErrors()).extracting(FieldError::field).contains("grafanaUrlTemplate");
    }

    @Test
    void requiresAConcreteImageSharedByAllScenarios() {
        var definition = new PluginDefinition("custom", "Custom", "generic", "example/load:{scenarioTag}",
                "example/database:1", "https://grafana.example.test/d/custom?var-run_id={runId}",
                Map.of(), Map.of(), List.of(scenario("custom", List.of())));
        var failure = catchThrowableOfType(() -> PluginValidation.validate(definition), InvalidExperimentException.class);
        assertThat(failure.fieldErrors()).extracting(FieldError::field).containsExactly("loaderImageName");
    }

    @Test
    void rejectsOnlyTheTwoPathSegmentIdsThatBrowsersNormalize() {
        var original = plugin("https://grafana.example.test/{runId}", List.of(scenario("custom", List.of())), Map.of());
        for (String id : List.of(".", "..")) {
            var definition = withId(original, id);
            var failure = catchThrowableOfType(() -> PluginValidation.validate(definition), InvalidExperimentException.class);
            assertThat(failure.fieldErrors()).extracting(FieldError::field).containsExactly("id");
        }
        for (String id : List.of("...", ".custom", "custom..name", "_custom", "-custom")) {
            assertThatCode(() -> PluginValidation.validate(withId(original, id))).doesNotThrowAnyException();
        }
    }

    @Test
    void rejectsAmbiguousSchemasAndInvalidDefaultsBeforeTheyReachUsers() {
        var inverted = new FieldSpec("count", "Count", "", FieldSpec.Type.INTEGER,
                5, BigDecimal.TEN, BigDecimal.ONE, null, List.of());
        var duplicate = FieldSpec.integer("count", "Count", "", 5, 1, 100, null);
        var scenario = scenario("duplicate", List.of(inverted, duplicate,
                FieldSpec.enumeration("mode", "Mode", "", "unsupported", "allowed")));
        var failure = catchThrowableOfType(() -> PluginValidation.validate(plugin(
                "https://grafana.example.test/d/custom?var-run_id={runId}", List.of(scenario, scenario), Map.of())),
                InvalidExperimentException.class);
        assertThat(failure.fieldErrors()).extracting(FieldError::field)
                .contains("scenarios[0].params[0]", "scenarios[0].params[1].key",
                        "scenarios[0].params[2].defaultValue", "scenarios[1].id");
    }

    private static Scenario scenario(String id, List<FieldSpec> params) {
        return new Scenario(id, "Custom", "", List.of(), params);
    }

    private static PluginDefinition plugin(String grafana, List<Scenario> scenarios, Map<String, Object> context) {
        return new PluginDefinition("custom", "Custom", "generic", "example/load:1",
                "example/database:1", grafana, context, Map.of(), scenarios);
    }

    private static PluginDefinition withId(PluginDefinition original, String id) {
        return new PluginDefinition(id, original.title(), original.collectorId(), original.loaderImageName(),
                original.databaseImageName(), original.grafanaUrlTemplate(), original.loaderImageContext(),
                original.databaseImageContext(), original.scenarios());
    }
}
