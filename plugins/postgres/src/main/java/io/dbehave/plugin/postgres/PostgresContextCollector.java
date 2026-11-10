package io.dbehave.plugin.postgres;

import io.dbehave.plugin.ContextCollector;
import io.dbehave.plugin.ImageContexts;
import io.dbehave.plugin.PluginDefinition;
import io.dbehave.plugin.Scenario;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** PostgreSQL-specific conversion; scenario schemas and image names come from the catalog. */
public final class PostgresContextCollector implements ContextCollector {
    private static final String DATABASE_NAME = "dbehave_experiment";
    private static final String DATABASE_USER = "dbehave";
    private static final Set<String> RESERVED_LOADER_ENVIRONMENT_KEYS = Set.of(
            "SCENARIO", "DB_HOST", "DB_PORT", "DB_NAME", "DB_USER", "DB_PASSWORD", "PGPASSWORD");

    @Override
    public String id() {
        return "postgres";
    }

    @Override
    public ImageContexts collect(
            PluginDefinition plugin, Scenario scenario,
            Map<String, Object> settings, Map<String, Object> params
    ) {
        var database = new HashMap<>(plugin.databaseImageContext());
        var databaseEnvironment = environment(database);
        databaseEnvironment.putIfAbsent("POSTGRES_DB", DATABASE_NAME);
        databaseEnvironment.putIfAbsent("POSTGRES_USER", DATABASE_USER);
        var command = command(database);
        var settingEnvironmentKeys = new HashSet<String>();
        for (var field : scenario.settings()) {
            String key = field.key();
            if (!key.matches("[A-Za-z_][A-Za-z0-9_.]*")) {
                throw new IllegalArgumentException("Unsupported PostgreSQL setting: " + key);
            }
            String value = settings.get(key).toString() + (key.equals("shared_buffers") ? "MB" : "");
            String environmentKey = "DBEHAVE_" + environmentKey(key);
            if (!settingEnvironmentKeys.add(environmentKey)) {
                throw new IllegalArgumentException("Duplicate database setting environment key: " + environmentKey);
            }
            databaseEnvironment.put(environmentKey, value);
            command.add("-c");
            command.add(key + "=" + value);
        }
        database.put("environment", Map.copyOf(databaseEnvironment));
        database.put("command", List.copyOf(command));

        var loader = new HashMap<>(plugin.loaderImageContext());
        var loaderEnvironment = new HashMap<String, String>();
        loaderEnvironment.put("DB_HOST", "database");
        loaderEnvironment.put("DB_PORT", "5432");
        loaderEnvironment.put("DB_NAME", databaseEnvironment.get("POSTGRES_DB"));
        loaderEnvironment.put("DB_USER", databaseEnvironment.get("POSTGRES_USER"));
        if (databaseEnvironment.containsKey("POSTGRES_PASSWORD")) {
            loaderEnvironment.put("DB_PASSWORD", databaseEnvironment.get("POSTGRES_PASSWORD"));
            loaderEnvironment.put("PGPASSWORD", databaseEnvironment.get("POSTGRES_PASSWORD"));
        }
        loaderEnvironment.putAll(environment(loader));
        loaderEnvironment.put("SCENARIO", scenario.id());
        var parameterEnvironmentKeys = new HashSet<String>();
        for (var field : scenario.params()) {
            // Existing loader images expose datasetRows through ROWS, while BDUI retains its public key.
            String environmentKey = field.key().equals("datasetRows") ? "ROWS" : environmentKey(field.key());
            if (RESERVED_LOADER_ENVIRONMENT_KEYS.contains(environmentKey)) {
                throw new IllegalArgumentException("Reserved loader environment key: " + environmentKey);
            }
            if (!parameterEnvironmentKeys.add(environmentKey)) {
                throw new IllegalArgumentException("Duplicate parameter environment key: " + environmentKey);
            }
            loaderEnvironment.put(environmentKey, params.get(field.key()).toString());
        }
        loader.put("environment", Map.copyOf(loaderEnvironment));
        loader.putIfAbsent("command", List.of());
        return new ImageContexts(loader, database);
    }

    /** Used once to bootstrap the catalog, never to override a stored plugin definition. */
    public static PluginDefinition defaultDefinition() {
        var plugin = new PostgresPlugin();
        return new PluginDefinition(
                plugin.id(), plugin.title(), "postgres", "dbehave/postgres-load:{scenarioTag}", "postgres:18",
                "http://localhost:3000/d/postgres?var-run_id={runId}",
                Map.of(),
                Map.of("environment", Map.of("POSTGRES_PASSWORD", "dbehave-experiment")),
                plugin.scenarios()
        );
    }

    private static String environmentKey(String key) {
        return key.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("[^A-Za-z0-9_]", "_").toUpperCase(Locale.ROOT);
    }

    private static Map<String, String> environment(Map<String, Object> context) {
        var result = new HashMap<String, String>();
        if (context.get("environment") instanceof Map<?, ?> configured) {
            configured.forEach((key, value) -> {
                if (!(key instanceof String name) || !(value instanceof String text)) {
                    throw new IllegalArgumentException("Container environment must contain string keys and values");
                }
                result.put(name, text);
            });
        } else if (context.containsKey("environment")) {
            throw new IllegalArgumentException("Container environment must be an object");
        }
        return result;
    }

    private static List<String> command(Map<String, Object> context) {
        if (!context.containsKey("command")) {
            return new ArrayList<>(List.of("postgres"));
        }
        if (!(context.get("command") instanceof List<?> configured) || configured.isEmpty()) {
            throw new IllegalArgumentException("PostgreSQL command must be a nonempty array of strings");
        }
        var result = new ArrayList<String>();
        for (Object value : configured) {
            if (!(value instanceof String text)) {
                throw new IllegalArgumentException("PostgreSQL command must contain strings");
            }
            result.add(text);
        }
        return result;
    }
}
