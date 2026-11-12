package io.dbehave.plugin.clickhouse;

import io.dbehave.plugin.ContextCollector;
import io.dbehave.plugin.ImageContexts;
import io.dbehave.plugin.PluginDefinition;
import io.dbehave.plugin.Scenario;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Converts the persisted ClickHouse catalog and normalized form input into worker container contexts. */
public final class ClickHouseContextCollector implements ContextCollector {
    public static final String DATABASE_IMAGE = "clickhouse/clickhouse-server:25.8.4.13";
    public static final String LOADER_IMAGE = "dbehave/clickhouse-load:0.1.0";
    public static final String GRAFANA_TEMPLATE = "http://localhost:3001/d/dbehave-clickhouse?var-run_id={runId}";
    private static final Set<String> QUERY_SETTINGS = Set.of("maxThreads", "maxMemoryUsageMB");

    @Override
    public String id() {
        return "clickhouse";
    }

    public static PluginDefinition defaultDefinition() {
        return new PluginDefinition("clickhouse", "ClickHouse 25.8", "clickhouse", LOADER_IMAGE, DATABASE_IMAGE,
                GRAFANA_TEMPLATE,
                Map.of("core_max", 0.5, "mem_max", 268_435_456),
                Map.of("core_max", 1.5, "mem_max", 1_610_612_736, "command", ClickHouseStartup.command()),
                ClickHouseScenarios.all());
    }

    @Override
    public ImageContexts collect(
            PluginDefinition plugin, Scenario scenario, Map<String, Object> settings, Map<String, Object> params
    ) {
        if (params.containsKey("rangeRows") && params.containsKey("datasetRows")
                && decimal(params.get("rangeRows")).compareTo(decimal(params.get("datasetRows"))) > 0) {
            throw new IllegalArgumentException("Ширина диапазона не должна превышать размер набора данных");
        }

        var database = new HashMap<>(plugin.databaseImageContext());
        var databaseEnv = environment(database);
        databaseEnv.putIfAbsent("CLICKHOUSE_DB", "dbehave");
        databaseEnv.putIfAbsent("CLICKHOUSE_USER", "dbehave");
        databaseEnv.putIfAbsent("CLICKHOUSE_PASSWORD", "dbehave");
        databaseEnv.putIfAbsent("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1");
        databaseEnv.putIfAbsent("CLICKHOUSE_WATCHDOG_ENABLE", "0");
        database.putIfAbsent("command", ClickHouseStartup.command());
        if (database.get("command").equals(ClickHouseStartup.command())
                && !databaseEnv.get("CLICKHOUSE_USER").equals("dbehave")) {
            throw new IllegalArgumentException("Стандартный запуск ClickHouse использует пользователя dbehave");
        }
        database.put("environment", Map.copyOf(databaseEnv));
        validateCommand(database);

        var loader = new HashMap<>(plugin.loaderImageContext());
        var loaderEnv = environment(loader);
        loaderEnv.putIfAbsent("DB_HOST", "database");
        loaderEnv.putIfAbsent("DB_PORT", "8123");
        loaderEnv.putIfAbsent("DB_NAME", databaseEnv.get("CLICKHOUSE_DB"));
        loaderEnv.putIfAbsent("DB_USER", databaseEnv.get("CLICKHOUSE_USER"));
        loaderEnv.putIfAbsent("DB_PASSWORD", databaseEnv.get("CLICKHOUSE_PASSWORD"));
        loaderEnv.put("SCENARIO", scenario.id());

        for (var field : scenario.settings()) {
            if (!QUERY_SETTINGS.contains(field.key())) {
                throw new IllegalArgumentException("Неизвестная настройка ClickHouse: " + field.key());
            }
            loaderEnv.put(environmentKey(field.key()), format(settings.get(field.key())));
        }
        var parameterKeys = new HashSet<String>();
        for (var field : scenario.params()) {
            String key = environmentKey(field.key());
            if (key.startsWith("DB_") || key.startsWith("CLICKHOUSE_")
                    || Set.of("SCENARIO", "HTTP_PORT", "MAX_THREADS", "MAX_MEMORY_USAGE_MB").contains(key)
                    || !parameterKeys.add(key)) {
                throw new IllegalArgumentException("Недопустимое имя параметра ClickHouse: " + field.key());
            }
            loaderEnv.put(key, format(params.get(field.key())));
        }
        loader.put("environment", Map.copyOf(loaderEnv));
        validateCommand(loader);
        return new ImageContexts(loader, database);
    }

    public static String environmentKey(String key) {
        return key.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("[^A-Za-z0-9_]", "_").toUpperCase(Locale.ROOT);
    }

    private static BigDecimal decimal(Object value) {
        return new BigDecimal(value.toString());
    }

    private static String format(Object value) {
        return value instanceof Number ? decimal(value).toPlainString() : value.toString();
    }

    private static Map<String, String> environment(Map<String, Object> context) {
        var environment = new HashMap<String, String>();
        if (!(context.getOrDefault("environment", Map.of()) instanceof Map<?, ?> configured)) {
            throw new IllegalArgumentException("Container environment must be an object");
        }
        configured.forEach((key, value) -> {
            if (!(key instanceof String name) || !(value instanceof String text)) {
                throw new IllegalArgumentException("Container environment must contain string keys and values");
            }
            environment.put(name, text);
        });
        return environment;
    }

    private static void validateCommand(Map<String, Object> context) {
        if (context.containsKey("command") && (!(context.get("command") instanceof List<?> command)
                || command.stream().anyMatch(value -> !(value instanceof String)))) {
            throw new IllegalArgumentException("Container command must be an array of strings");
        }
    }
}
