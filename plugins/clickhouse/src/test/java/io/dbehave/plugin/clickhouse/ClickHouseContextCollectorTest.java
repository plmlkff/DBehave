package io.dbehave.plugin.clickhouse;

import io.dbehave.plugin.FieldSpec;
import io.dbehave.plugin.PluginDefinition;
import io.dbehave.plugin.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;

class ClickHouseContextCollectorTest {
    private static final ClickHouseContextCollector COLLECTOR = new ClickHouseContextCollector();
    private static final PluginDefinition DEFINITION = ClickHouseContextCollector.defaultDefinition();

    static List<Scenario> scenarios() {
        return DEFINITION.scenarios();
    }

    @Test
    void catalogContainsFourScenariosAndResourceDefaults() {
        assertThat(DEFINITION.id()).isEqualTo(COLLECTOR.id());
        assertThat(scenarios()).extracting(Scenario::id)
                .containsExactly("small-inserts", "sorting-key", "bloom-filter", "replacing-merge-tree");
        assertThat(DEFINITION.databaseImageContext()).containsEntry("core_max", 1.5).containsEntry("mem_max", 1_610_612_736);
        assertThat(DEFINITION.loaderImageContext()).containsEntry("core_max", 0.5).containsEntry("mem_max", 268_435_456);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    void defaultsProduceWorkerContextsForEveryScenario(Scenario scenario) {
        var contexts = COLLECTOR.collect(DEFINITION, scenario, defaults(scenario.settings()), defaults(scenario.params()));

        assertThat(contexts.databaseImageContext().get("environment")).asInstanceOf(MAP)
                .containsEntry("CLICKHOUSE_DB", "dbehave")
                .containsEntry("CLICKHOUSE_USER", "dbehave")
                .containsEntry("CLICKHOUSE_PASSWORD", "dbehave")
                .containsEntry("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1")
                .containsEntry("CLICKHOUSE_WATCHDOG_ENABLE", "0");
        assertThat(contexts.databaseImageContext().get("command")).asList()
                .startsWith("bash", "-ec").hasSize(3);
        assertThat(contexts.loaderImageContext().get("environment")).asInstanceOf(MAP)
                .containsEntry("SCENARIO", scenario.id())
                .containsEntry("DB_HOST", "database")
                .containsEntry("DB_PORT", "8123")
                .containsEntry("DB_NAME", "dbehave")
                .containsEntry("DB_USER", "dbehave")
                .containsEntry("DB_PASSWORD", "dbehave")
                .containsEntry("CLIENTS", "4")
                .containsEntry("DURATION_SEC", "30")
                .containsEntry("WARMUP_SEC", "5")
                .containsEntry("MAX_THREADS", "2")
                .containsEntry("MAX_MEMORY_USAGE_MB", "256");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void transfersEachScenarioSwitchAsABooleanString(boolean enabled) {
        var switches = Map.of("small-inserts", "buffered", "sorting-key", "sortedKey",
                "bloom-filter", "bloomFilter", "replacing-merge-tree", "finalRead");
        for (var scenario : scenarios()) {
            var params = defaults(scenario.params());
            String key = switches.get(scenario.id());
            params.put(key, enabled);
            var contexts = COLLECTOR.collect(DEFINITION, scenario, defaults(scenario.settings()), params);
            assertThat(contexts.loaderImageContext().get("environment")).asInstanceOf(MAP)
                    .containsEntry(ClickHouseContextCollector.environmentKey(key), Boolean.toString(enabled));
        }
    }

    @Test
    void preservesCatalogOverridesAndDerivesLoaderCredentialsFromDatabase() {
        var database = Map.<String, Object>of("environment", Map.of("CLICKHOUSE_DB", "custom",
                        "CLICKHOUSE_USER", "tester", "CLICKHOUSE_PASSWORD", "secret"),
                "command", List.of("custom-entrypoint"), "core_max", 2, "mem_max", 2_147_483_648L);
        var loader = Map.<String, Object>of("environment", Map.of("DB_HOST", "custom-host", "CUSTOM", "value"),
                "command", List.of("custom-loader"));
        var definition = withContexts(loader, database);
        var scenario = scenarios().getFirst();
        var settings = defaults(scenario.settings());
        settings.put("maxThreads", new BigDecimal("4E+0"));

        var contexts = COLLECTOR.collect(definition, scenario, settings, defaults(scenario.params()));

        assertThat(contexts.databaseImageContext()).containsEntry("command", List.of("custom-entrypoint"))
                .containsEntry("core_max", 2).containsEntry("mem_max", 2_147_483_648L);
        assertThat(contexts.loaderImageContext()).containsEntry("command", List.of("custom-loader"));
        assertThat(contexts.loaderImageContext().get("environment")).asInstanceOf(MAP)
                .containsEntry("DB_HOST", "custom-host")
                .containsEntry("DB_NAME", "custom")
                .containsEntry("DB_USER", "tester")
                .containsEntry("DB_PASSWORD", "secret")
                .containsEntry("MAX_THREADS", "4")
                .containsEntry("CUSTOM", "value");
        assertThat(loader.get("environment")).asInstanceOf(MAP).hasSize(2);
    }

    @Test
    void rejectsRangeLargerThanDataset() {
        var scenario = scenarios().get(1);
        var params = defaults(scenario.params());
        params.putAll(Map.of("rangeRows", 20_000, "datasetRows", 10_000));
        assertThatThrownBy(() -> COLLECTOR.collect(DEFINITION, scenario, defaults(scenario.settings()), params))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Ширина диапазона");
    }

    @Test
    void rejectsCustomUsernameWhenUsingTheDefaultStartup() {
        var definition = withContexts(Map.of(), Map.of("environment", Map.of("CLICKHOUSE_USER", "other")));
        var scenario = scenarios().getFirst();
        assertThatThrownBy(() -> COLLECTOR.collect(definition, scenario,
                defaults(scenario.settings()), defaults(scenario.params())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("пользователя dbehave");
    }

    @ParameterizedTest
    @ValueSource(strings = {"dbName", "clickhousePassword", "scenario", "httpPort", "maxThreads", "maxMemoryUsageMB"})
    void rejectsScenarioParametersThatOverrideConnectionOrSettings(String key) {
        var scenario = new Scenario("small-inserts", "Test", "Test", List.of(),
                List.of(FieldSpec.string(key, key, key, "value")));
        assertThatThrownBy(() -> COLLECTOR.collect(DEFINITION, scenario, Map.of(), Map.of(key, "value")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Недопустимое имя параметра");
    }

    @Test
    void rejectsDuplicateEnvironmentNamesAndUnknownSettings() {
        var duplicate = new Scenario("small-inserts", "Test", "Test", List.of(), List.of(
                FieldSpec.string("batchSize", "Batch", "Batch", "1"),
                FieldSpec.string("batch_size", "Batch", "Batch", "1")));
        assertThatThrownBy(() -> COLLECTOR.collect(DEFINITION, duplicate, Map.of(),
                Map.of("batchSize", "1", "batch_size", "1")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Недопустимое имя параметра");
        var unknownSetting = new Scenario("small-inserts", "Test", "Test",
                List.of(FieldSpec.string("unknown", "Unknown", "Unknown", "1")), List.of());
        assertThatThrownBy(() -> COLLECTOR.collect(DEFINITION, unknownSetting, Map.of("unknown", "1"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Неизвестная настройка");
    }

    @Test
    void rejectsMalformedCatalogContainerConfiguration() {
        var scenario = scenarios().getFirst();
        for (var malformed : List.of(Map.<String, Object>of("environment", "text"),
                Map.<String, Object>of("environment", Map.of("NAME", 1)),
                Map.<String, Object>of("command", "text"),
                Map.<String, Object>of("command", List.of(1)))) {
            for (var definition : List.of(withContexts(malformed, Map.of()), withContexts(Map.of(), malformed))) {
                assertThatThrownBy(() -> COLLECTOR.collect(definition, scenario,
                        defaults(scenario.settings()), defaults(scenario.params())))
                        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Container");
            }
        }
    }

    private static PluginDefinition withContexts(Map<String, Object> loader, Map<String, Object> database) {
        return new PluginDefinition(DEFINITION.id(), DEFINITION.title(), DEFINITION.collectorId(),
                DEFINITION.loaderImageName(), DEFINITION.databaseImageName(), DEFINITION.grafanaUrlTemplate(),
                loader, database, scenarios());
    }

    private static Map<String, Object> defaults(List<FieldSpec> fields) {
        var values = new HashMap<String, Object>();
        fields.forEach(field -> values.put(field.key(), field.defaultValue()));
        return values;
    }
}
