package io.dbehave.plugin.postgres;

import io.dbehave.plugin.DatabasePlugin;
import io.dbehave.plugin.ExperimentPlan;
import io.dbehave.plugin.ExperimentPlan.ContainerSpec;
import io.dbehave.plugin.Scenario;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class PostgresPlugin implements DatabasePlugin {
    private static final String DATABASE_NAME = "dbehave_experiment";
    private static final String DATABASE_USER = "dbehave";
    private static final String LOAD_GENERATOR_IMAGE = "dbehave/postgres-load";

    private final List<Scenario> scenarios;
    private final Map<String, PostgresScenario> scenariosById;

    public PostgresPlugin() {
        this(List.of(new MixedScenario(), new ReadHeavyScenario(), new WriteHeavyScenario(),
                new LongTxLocksScenario(), new IsolationScenario()));
    }

    PostgresPlugin(List<PostgresScenario> scenarios) {
        this.scenarios = scenarios.stream().map(scenario -> scenario.definition().describe()).toList();
        this.scenariosById = scenarios.stream().collect(Collectors.toUnmodifiableMap(
                scenario -> scenario.definition().id(), Function.identity()));
    }

    @Override
    public String id() {
        return "postgres";
    }

    @Override
    public String title() {
        return "PostgreSQL 18";
    }

    @Override
    public List<Scenario> scenarios() {
        return scenarios;
    }

    @Override
    public ExperimentPlan prepare(Scenario scenario, Map<String, Object> settings, Map<String, Object> params) {
        var selected = scenariosById.get(scenario.id());
        if (selected == null) {
            throw new IllegalArgumentException("Unsupported PostgreSQL scenario: " + scenario.id());
        }
        var definition = selected.definition();

        // The executor supplies the database network alias and matching credentials for both containers
        var loadEnvironment = new HashMap<>(Map.of(
                "DB_HOST", "database",
                "DB_PORT", "5432",
                "DB_NAME", DATABASE_NAME,
                "DB_USER", DATABASE_USER,
                "SCENARIO", definition.id()
        ));
        loadEnvironment.putAll(selected.loadEnvironment(params));
        var loadGenerator = new ContainerSpec(
                LOAD_GENERATOR_IMAGE + ":" + definition.loadGeneratorTag(), loadEnvironment, List.of());
        return new ExperimentPlan(databaseContainer(definition, settings), loadGenerator);
    }

    private static ContainerSpec databaseContainer(PostgresScenario.Definition definition, Map<String, Object> settings) {
        var environment = new HashMap<>(Map.of("POSTGRES_DB", DATABASE_NAME, "POSTGRES_USER", DATABASE_USER));
        var command = new StringBuilder("exec docker-entrypoint.sh postgres");

        // Only setting names declared by the plugin enter the command, and values come from the environment
        for (var setting : definition.settings()) {
            String envKey = setting.environmentKey();
            environment.put(envKey, setting.environmentValue(settings));
            command.append(" -c \"").append(setting.spec().key()).append("=${").append(envKey).append("}\"");
        }

        return new ContainerSpec("postgres:18", environment, List.of("sh", "-ec", command.toString()));
    }
}
