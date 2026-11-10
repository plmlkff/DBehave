package io.dbehave.plugin.postgres;

import io.dbehave.plugin.FieldSpec;
import io.dbehave.plugin.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresPluginTest {
    private final PostgresPlugin plugin = new PostgresPlugin();

    static List<Scenario> scenarios() {
        return PostgresScenarios.all();
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    void scenarioUsesOnlyCatalogSettingsAndUniqueKeys(Scenario scenario) {
        var keys = new HashSet<String>();
        for (FieldSpec spec : scenario.settings()) {
            assertThat(PgSettings.byKey(spec.key()).spec()).isEqualTo(spec);
            assertThat(keys.add(spec.key())).as("duplicate setting %s", spec.key()).isTrue();
        }
        var environmentNames = new HashSet<String>();
        for (FieldSpec spec : scenario.params()) {
            assertThat(environmentNames.add(PostgresPlugin.environmentName(spec.key())))
                    .as("duplicate param %s", spec.key()).isTrue();
        }
        assertThat(scenario.params()).extracting(FieldSpec::key).startsWith("clients", "durationSec", "warmupSec");
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    void defaultsProduceAPlanForEveryScenario(Scenario scenario) {
        var plan = plugin.prepare(scenario, defaults(scenario.settings()), defaults(scenario.params()));

        assertThat(plan.database().image()).isEqualTo("postgres:18");
        assertThat(plan.database().command()).startsWith("docker-entrypoint.sh", "postgres");
        assertThat(plan.database().command().stream().filter("-c"::equals)).hasSize(scenario.settings().size());
        assertThat(plan.loadGenerator().image()).isEqualTo(PostgresPlugin.LOAD_GENERATOR_IMAGE);
        assertThat(plan.loadGenerator().environment())
                .containsEntry("SCENARIO", scenario.id())
                .containsEntry("DB_HOST", "database")
                .containsEntry("DB_PASSWORD", plan.database().environment().get("POSTGRES_PASSWORD"))
                .containsKeys("CLIENTS", "DURATION_SEC", "WARMUP_SEC");
    }

    @Test
    void formatsSettingsForPostgres() {
        var scenario = PostgresScenarios.writeHeavy();
        var settings = defaults(scenario.settings());
        settings.put("shared_buffers", 256);
        settings.put("synchronous_commit", "off");
        settings.put("fsync", false);
        settings.put("checkpoint_completion_target", new BigDecimal("0.50"));

        var command = plugin.prepare(scenario, settings, defaults(scenario.params())).database().command();

        assertThat(command).contains("shared_buffers=256MB", "synchronous_commit=off", "fsync=off",
                "wal_buffers=-1", "wal_writer_delay=200ms", "checkpoint_timeout=300s",
                "checkpoint_completion_target=0.50", "commit_delay=0");
    }

    @Test
    void passesParamsAsUpperSnakeCaseEnvironment() {
        var scenario = PostgresScenarios.isolation();
        var params = defaults(scenario.params());
        params.put("isolationLevel", "REPEATABLE READ");
        params.put("retryOnSerializationFailure", true);

        var environment = plugin.prepare(scenario, defaults(scenario.settings()), params).loadGenerator().environment();

        assertThat(environment)
                .containsEntry("ISOLATION_LEVEL", "REPEATABLE READ")
                .containsEntry("RETRY_ON_SERIALIZATION_FAILURE", "true")
                .containsEntry("ANOMALY", "lost-update")
                .containsEntry("THINK_MS", "5");
    }

    @Test
    void rejectsUnknownAndDuplicateScenarios() {
        var unknown = new Scenario("unknown", "", "", List.of(), List.of());
        assertThatThrownBy(() -> plugin.prepare(unknown, Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PostgresPlugin(List.of(PostgresScenarios.mixed(), PostgresScenarios.mixed())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mixed");
    }

    @Test
    void convertsKeysToEnvironmentNames() {
        assertThat(PostgresPlugin.environmentName("durationSec")).isEqualTo("DURATION_SEC");
        assertThat(PostgresPlugin.environmentName("retryOnSerializationFailure"))
                .isEqualTo("RETRY_ON_SERIALIZATION_FAILURE");
        assertThat(PostgresPlugin.environmentName("clients")).isEqualTo("CLIENTS");
    }

    private static Map<String, Object> defaults(List<FieldSpec> specs) {
        var values = new HashMap<String, Object>();
        specs.forEach(spec -> values.put(spec.key(), spec.defaultValue()));
        return values;
    }
}
