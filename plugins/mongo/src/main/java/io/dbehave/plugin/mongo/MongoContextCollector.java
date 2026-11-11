package io.dbehave.plugin.mongo;

import io.dbehave.plugin.ContextCollector;
import io.dbehave.plugin.ImageContexts;
import io.dbehave.plugin.PluginDefinition;
import io.dbehave.plugin.Scenario;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MongoContextCollector implements ContextCollector {
    public static final String DATABASE_IMAGE = "mongo:8.0";
    public static final String LOADER_IMAGE = "dbehave/mongo-load:0.1.0";
    public static final String GRAFANA_TEMPLATE = "http://localhost:3001/d/dbheave-mongo?var-run_id={runId}";

    @Override
    public String id() {
        return "mongo";
    }

    public static PluginDefinition defaultDefinition() {
        return new PluginDefinition("mongo", "MongoDB 8.0", "mongo", LOADER_IMAGE, DATABASE_IMAGE, GRAFANA_TEMPLATE,
                Map.of(), Map.of(), MongoScenarios.all());
    }

    @Override
    public ImageContexts collect(
            PluginDefinition plugin, Scenario scenario, Map<String, Object> settings, Map<String, Object> params
    ) {
        validateWeights(params, List.of("pointWeight", "rangeWeight", "reportWeight"));
        validateWeights(params, List.of("readWeight", "updateWeight", "insertWeight"));

        var database = new HashMap<>(plugin.databaseImageContext());
        var loader = new HashMap<>(plugin.loaderImageContext());
        var command = command(database, List.of("mongod", "--bind_ip_all"));
        if (command.isEmpty()) {
            throw new IllegalArgumentException("MongoDB command must not be empty");
        }
        var env = environment(loader);
        env.putIfAbsent("DB_HOST", "database");
        env.putIfAbsent("DB_PORT", "27017");
        env.putIfAbsent("DB_NAME", "dbehave");
        env.put("SCENARIO", scenario.id());

        var parameterKeys = new HashSet<String>();
        for (var field : scenario.params()) {
            String key = environmentKey(field.key());
            if (key.startsWith("DB_") || key.startsWith("MONGO_") || key.equals("SCENARIO") || key.equals("HTTP_PORT")
                    || !parameterKeys.add(key)) {
                throw new IllegalArgumentException("Недопустимое имя параметра MongoDB: " + field.key());
            }
            env.put(key, format(params.get(field.key())));
        }

        var mongoSettings = new HashMap<String, Object>();
        mongoSettings.put(MongoScenarios.CACHE_SIZE.key(), MongoScenarios.CACHE_SIZE.defaultValue());
        for (var field : scenario.settings()) {
            mongoSettings.put(field.key(), settings.get(field.key()));
        }
        mongoSettings.forEach((key, value) -> {
            String argument = format(value);
            switch (key) {
                case "cacheSizeMB" -> {
                    var gibibytes = new BigDecimal(argument).divide(BigDecimal.valueOf(1024));
                    command.add("--wiredTigerCacheSizeGB=" + gibibytes.toPlainString());
                }
                case "blockCompressor" -> command.add("--wiredTigerCollectionBlockCompressor=" + argument);
                case "journalCompressor" -> command.add("--wiredTigerJournalCompressor=" + argument);
                case "prefixCompression" -> command.add("--wiredTigerIndexPrefixCompression=" + argument);
                case "allowDiskUse", "journalAcknowledged" -> { }
                default -> throw new IllegalArgumentException("Неизвестная настройка MongoDB: " + key);
            }
            env.put("MONGO_" + environmentKey(key), argument);
        });

        database.put("environment", Map.copyOf(environment(database)));
        database.put("command", List.copyOf(command));
        loader.put("environment", Map.copyOf(env));
        loader.put("command", List.copyOf(command(loader, List.of())));
        return new ImageContexts(loader, database);
    }

    public static String environmentKey(String key) {
        return key.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("[^A-Za-z0-9_]", "_").toUpperCase(Locale.ROOT);
    }

    private static String format(Object value) {
        return value instanceof Number number ? new BigDecimal(number.toString()).toPlainString() : value.toString();
    }

    private static void validateWeights(Map<String, Object> params, List<String> keys) {
        if (params.keySet().containsAll(keys)
                && keys.stream().map(key -> new BigDecimal(params.get(key).toString())).allMatch(v -> v.signum() == 0)) {
            throw new IllegalArgumentException("Сумма весов операций должна быть больше нуля (" + String.join(", ", keys) + ")");
        }
    }

    private static Map<String, String> environment(Map<String, Object> context) {
        var env = new HashMap<String, String>();
        if (!(context.getOrDefault("environment", Map.of()) instanceof Map<?, ?> configured)) {
            throw new IllegalArgumentException("Container environment must be an object");
        }
        configured.forEach((key, value) -> {
            if (!(key instanceof String name) || !(value instanceof String text)) {
                throw new IllegalArgumentException("Container environment must contain string keys and values");
            }
            env.put(name, text);
        });
        return env;
    }

    private static List<String> command(Map<String, Object> context, List<String> defaults) {
        if (!(context.getOrDefault("command", defaults) instanceof List<?> configured)
                || configured.stream().anyMatch(value -> !(value instanceof String))) {
            throw new IllegalArgumentException("Container command must be an array of strings");
        }
        return new ArrayList<>(configured.stream().map(String.class::cast).toList());
    }
}
