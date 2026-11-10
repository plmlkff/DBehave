package io.dbehave.plugin.postgres;

import io.dbehave.plugin.GenericContextCollector;
import io.dbehave.plugin.PluginDefinition;
import io.dbehave.plugin.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.dbehave.plugin.FieldSpec.integer;
import static io.dbehave.plugin.FieldSpec.string;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresContextCollectorTest {
    private final PostgresContextCollector collector = new PostgresContextCollector();

    @ParameterizedTest
    @MethodSource("upstreamScenarios")
    void matchesUpstreamContainerContractForEveryScenario(Scenario scenario) {
        var plugin = new PostgresPlugin();
        var definition = PostgresContextCollector.defaultDefinition();
        var settings = new HashMap<String, Object>();
        var params = new HashMap<String, Object>();
        scenario.settings().forEach(field -> settings.put(field.key(), field.defaultValue()));
        scenario.params().forEach(field -> params.put(field.key(), field.defaultValue()));

        var expected = plugin.prepare(scenario, settings, params);
        var actual = collector.collect(definition, scenario, settings, params);

        assertThat(definition.databaseImageName()).isEqualTo(expected.database().image());
        assertThat(definition.loaderImageName()).isEqualTo(expected.loadGenerator().image());
        assertThat(actual.databaseImageContext().get("command")).isEqualTo(expected.database().command());
        assertThat(actual.loaderImageContext().get("command")).isEqualTo(expected.loadGenerator().command());
        assertThat(environment(actual.databaseImageContext())).containsAllEntriesOf(expected.database().environment());
        assertThat(environment(actual.loaderImageContext())).containsAllEntriesOf(expected.loadGenerator().environment());
    }

    @Test
    void formatsKnownPostgresUnitsBooleansAndAutomaticValuesForStoredScenario() {
        var scenario = new Scenario("settings-from-db", "Settings from DB", "",
                PgSettings.specs(PgSettings.SHARED_BUFFERS, PgSettings.WORK_MEM, PgSettings.WAL_BUFFERS,
                        PgSettings.LOCK_TIMEOUT, PgSettings.CHECKPOINT_TIMEOUT, PgSettings.FSYNC,
                        PgSettings.RANDOM_PAGE_COST), List.of());

        var actual = collector.collect(plugin(scenario, Map.of(), Map.of()), scenario,
                Map.of("shared_buffers", 256, "work_mem", 4096, "wal_buffers", -1,
                        "lock_timeout", 1500, "checkpoint_timeout", 120, "fsync", false,
                        "random_page_cost", new BigDecimal("1E+1")), Map.of());

        assertThat(actual.databaseImageContext().get("command")).isEqualTo(List.of(
                "docker-entrypoint.sh", "postgres", "-c", "shared_buffers=256MB", "-c", "work_mem=4096kB",
                "-c", "wal_buffers=-1", "-c", "lock_timeout=1500ms", "-c", "checkpoint_timeout=120s",
                "-c", "fsync=off", "-c", "random_page_cost=10"));
    }

    @Test
    void convertsNewStoredScenarioWithoutHardcodedScenarioIds() {
        var scenario = new Scenario(
                "custom-from-catalog", "Custom workload", "Configured in the database",
                List.of(integer("shared_buffers", "Buffers", "", 256, 16, 1024, "MB"),
                        string("extension.mode", "Extension mode", "", "standard")),
                List.of(integer("targetRps", "RPS", "", 20, 1, 1000, null),
                        string("distribution", "Distribution", "", "uniform"))
        );
        var plugin = plugin(scenario, Map.of(), Map.of());

        var contexts = collector.collect(plugin, scenario,
                Map.of("shared_buffers", 128, "extension.mode", "adaptive"),
                Map.of("targetRps", 75, "distribution", "hotspot"));

        assertThat(contexts.databaseImageContext().get("command")).isEqualTo(List.of(
                "docker-entrypoint.sh", "postgres", "-c", "shared_buffers=128MB", "-c", "extension.mode=adaptive"));
        assertThat(environment(contexts.databaseImageContext()))
                .containsEntry("DBEHAVE_SHARED_BUFFERS", "128MB")
                .containsEntry("DBEHAVE_EXTENSION_MODE", "adaptive");
        assertThat(environment(contexts.loaderImageContext()))
                .containsEntry("SCENARIO", "custom-from-catalog")
                .containsEntry("TARGET_RPS", "75")
                .containsEntry("DISTRIBUTION", "hotspot")
                .doesNotContainKeys("CLIENTS", "ROWS");
    }

    @Test
    void preservesReadHeavyDatasetRowsContractForDefaultsAndOverrides() {
        var plugin = PostgresContextCollector.defaultDefinition();
        var scenario = plugin.scenarios().stream().filter(value -> value.id().equals("read-heavy"))
                .findFirst().orElseThrow();
        var settings = new HashMap<String, Object>();
        var params = new HashMap<String, Object>();
        scenario.settings().forEach(field -> settings.put(field.key(), field.defaultValue()));
        scenario.params().forEach(field -> params.put(field.key(), field.defaultValue()));

        var defaults = collector.collect(plugin, scenario, settings, params);

        assertThat(environment(defaults.loaderImageContext()))
                .containsEntry("DATASET_ROWS", "1000000")
                .doesNotContainKey("ROWS");

        params.put("datasetRows", 2_000_000);
        var overridden = collector.collect(plugin, scenario, settings, params);

        assertThat(environment(overridden.loaderImageContext()))
                .containsEntry("DATASET_ROWS", "2000000")
                .doesNotContainKey("ROWS");
    }

    @Test
    void passesSettingValuesAsLiteralArgumentsWithoutShellEvaluation() {
        String value = "$(touch /tmp/unexpected); 'quoted' \"double\" ${HOME}";
        var scenario = new Scenario("literal-values", "Literal values", "",
                List.of(string("application_name", "Name", "", "default")), List.of());

        var contexts = collector.collect(plugin(scenario, Map.of(), Map.of()), scenario,
                Map.of("application_name", value), Map.of());

        assertThat(contexts.databaseImageContext().get("command"))
                .isEqualTo(List.of("docker-entrypoint.sh", "postgres", "-c", "application_name=" + value));
        assertThat(environment(contexts.databaseImageContext()))
                .containsEntry("DBEHAVE_APPLICATION_NAME", value);
    }

    @Test
    void mergesConfiguredContextsAndEnvironmentWithoutMutatingThem() {
        var databaseEnvironment = new HashMap<String, Object>(Map.of(
                "POSTGRES_DB", "custom-db", "POSTGRES_USER", "custom-user",
                "POSTGRES_PASSWORD", "custom-password", "CUSTOM_DATABASE_ENV", "keep-db"));
        var loaderEnvironment = new HashMap<String, Object>(Map.of(
                "DB_HOST", "custom-database-alias", "CUSTOM_LOADER_ENV", "keep-loader",
                "SCENARIO", "replace-me", "TARGET_RPS", "replace-me"));
        var loaderCommand = new ArrayList<>(List.of("/loader", "--json"));
        var loaderContext = new HashMap<String, Object>(Map.of(
                "environment", loaderEnvironment, "command", loaderCommand,
                "core_max", new BigDecimal("0.5"), "mem_max", 134217728L, "custom", "loader-value"));
        var databaseContext = new HashMap<String, Object>(Map.of(
                "environment", databaseEnvironment, "core_max", 2, "mem_max", 536870912L,
                "custom", "database-value"));
        var scenario = new Scenario("merged", "Merged", "", List.of(),
                List.of(integer("targetRps", "RPS", "", 10, 1, 100, null)));
        var plugin = plugin(scenario, loaderContext, databaseContext);

        var contexts = collector.collect(plugin, scenario, Map.of(), Map.of("targetRps", 35));

        assertThat(environment(contexts.loaderImageContext()))
                .containsEntry("DB_HOST", "custom-database-alias")
                .containsEntry("DB_NAME", "custom-db")
                .containsEntry("DB_USER", "custom-user")
                .containsEntry("DB_PASSWORD", "custom-password")
                .containsEntry("PGPASSWORD", "custom-password")
                .containsEntry("CUSTOM_LOADER_ENV", "keep-loader")
                .containsEntry("SCENARIO", "merged")
                .containsEntry("TARGET_RPS", "35");
        assertThat(environment(contexts.databaseImageContext()))
                .containsEntry("CUSTOM_DATABASE_ENV", "keep-db");
        assertThat(contexts.loaderImageContext())
                .containsEntry("custom", "loader-value")
                .containsEntry("core_max", new BigDecimal("0.5"))
                .containsEntry("mem_max", 134217728L)
                .containsEntry("command", List.of("/loader", "--json"));
        assertThat(contexts.databaseImageContext())
                .containsEntry("custom", "database-value")
                .containsEntry("core_max", 2)
                .containsEntry("mem_max", 536870912L);
        assertThat(loaderEnvironment).containsEntry("SCENARIO", "replace-me").containsEntry("TARGET_RPS", "replace-me");
        assertThat(databaseEnvironment).doesNotContainKey("DBEHAVE_SHARED_BUFFERS");
        assertThat(databaseContext).doesNotContainKey("command");
        assertThat(loaderCommand).containsExactly("/loader", "--json");
        assertThat(plugin.loaderImageContext()).isEqualTo(loaderContext);
        assertThat(plugin.databaseImageContext()).isEqualTo(databaseContext);
    }

    @Test
    void appendsDatabaseSettingsToConfiguredCommandWithoutMutatingIt() {
        var baseCommand = new ArrayList<>(List.of("postgres", "-c", "log_statement=all"));
        var scenario = new Scenario("custom-command", "Custom command", "",
                List.of(integer("max_connections", "Connections", "", 100, 10, 500, null)), List.of());
        var plugin = plugin(scenario, Map.of(), Map.of("command", baseCommand));

        var contexts = collector.collect(plugin, scenario, Map.of("max_connections", 50), Map.of());

        assertThat(contexts.databaseImageContext().get("command"))
                .isEqualTo(List.of("postgres", "-c", "log_statement=all", "-c", "max_connections=50"));
        assertThat(baseCommand).containsExactly("postgres", "-c", "log_statement=all");
    }

    @Test
    void rejectsSettingNamesThatCannotBePostgresConfigurationKeys() {
        var scenario = new Scenario("invalid-setting", "Invalid setting", "",
                List.of(string("name; echo unsafe", "Invalid", "", "value")), List.of());

        assertThatThrownBy(() -> collector.collect(plugin(scenario, Map.of(), Map.of()), scenario,
                Map.of("name; echo unsafe", "value"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported PostgreSQL setting");
    }

    @Test
    void rejectsDistinctParameterKeysMappingToTheSameEnvironmentVariable() {
        var scenario = new Scenario("collision", "Collision", "", List.of(),
                List.of(integer("targetRps", "First RPS", "", 10, 1, 100, null),
                        integer("target_rps", "Second RPS", "", 20, 1, 100, null)));

        assertThatThrownBy(() -> collector.collect(plugin(scenario, Map.of(), Map.of()), scenario,
                Map.of(), Map.of("targetRps", 10, "target_rps", 20)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate parameter environment key: TARGET_RPS");
    }

    @Test
    void treatsDatasetRowsAndRowsAsSeparateParameters() {
        var scenario = new Scenario("separate-rows", "Separate rows", "", List.of(),
                List.of(integer("datasetRows", "Dataset rows", "", 10, 1, 100, null),
                        integer("rows", "Other rows", "", 20, 1, 100, null)));

        var contexts = collector.collect(plugin(scenario, Map.of(), Map.of()), scenario,
                Map.of(), Map.of("datasetRows", 10, "rows", 20));

        assertThat(environment(contexts.loaderImageContext()))
                .containsEntry("DATASET_ROWS", "10")
                .containsEntry("ROWS", "20");
    }

    @ParameterizedTest
    @ValueSource(strings = {"scenario", "SCENARIO", "dbHost", "dbPort", "dbName", "dbUser", "dbPassword", "PGPASSWORD"})
    void rejectsParametersOverwritingReservedLoaderEnvironment(String key) {
        var scenario = new Scenario("reserved", "Reserved", "", List.of(),
                List.of(string(key, "Reserved environment variable", "", "value")));

        assertThatThrownBy(() -> collector.collect(plugin(scenario, Map.of(), Map.of()), scenario,
                Map.of(), Map.of(key, "value")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Reserved loader environment key:");
    }

    @Test
    void rejectsDistinctSettingsMappingToTheSameHelperEnvironmentVariable() {
        var scenario = new Scenario("setting-collision", "Setting collision", "",
                List.of(string("extension.mode", "Extension setting", "", "a"),
                        string("extension_mode", "Other setting", "", "b")), List.of());

        assertThatThrownBy(() -> collector.collect(plugin(scenario, Map.of(), Map.of()), scenario,
                Map.of("extension.mode", "a", "extension_mode", "b"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate database setting environment key: DBEHAVE_EXTENSION_MODE");
    }

    @Test
    void genericCollectorReplacesPayloadsAndPreservesBaseContextAndLimits() {
        var scenario = new Scenario("arbitrary", "Arbitrary", "", List.of(), List.of());
        var plugin = plugin(scenario,
                Map.of("params", Map.of("stale", true), "scenarioId", "old", "command", List.of("loader"),
                        "core_max", new BigDecimal("1.5")),
                Map.of("settings", Map.of("stale", true), "environment", Map.of("CUSTOM", "value"),
                        "mem_max", 268435456L));
        var settings = new HashMap<String, Object>(Map.of("customSetting", "configured"));
        var params = new HashMap<String, Object>(Map.of("rps", 30));

        var contexts = new GenericContextCollector().collect(plugin, scenario, settings, params);
        settings.put("customSetting", "changed-after-collection");
        params.put("rps", 100);

        assertThat(contexts.loaderImageContext())
                .containsEntry("scenarioId", "arbitrary")
                .containsEntry("params", Map.of("rps", 30))
                .containsEntry("command", List.of("loader"))
                .containsEntry("core_max", new BigDecimal("1.5"));
        assertThat(contexts.databaseImageContext())
                .containsEntry("settings", Map.of("customSetting", "configured"))
                .containsEntry("environment", Map.of("CUSTOM", "value"))
                .containsEntry("mem_max", 268435456L);
        assertThat(plugin.loaderImageContext()).containsEntry("scenarioId", "old");
        assertThat(plugin.databaseImageContext()).containsEntry("settings", Map.of("stale", true));
    }

    private static PluginDefinition plugin(
            Scenario scenario, Map<String, Object> loaderContext, Map<String, Object> databaseContext
    ) {
        return new PluginDefinition("stored-plugin", "Stored plugin", "postgres", "custom-loader:v2",
                "custom-postgres:v3", "https://grafana.example/d/test?var-run_id={runId}",
                loaderContext, databaseContext, List.of(scenario));
    }

    private static Stream<Scenario> upstreamScenarios() {
        return new PostgresPlugin().scenarios().stream();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> environment(Map<String, Object> context) {
        return (Map<String, String>) context.get("environment");
    }
}
