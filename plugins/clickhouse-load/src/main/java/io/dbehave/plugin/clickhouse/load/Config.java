package io.dbehave.plugin.clickhouse.load;

import io.dbehave.plugin.FieldSpec;
import io.dbehave.plugin.clickhouse.ClickHouseContextCollector;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

final class Config {
    private final String scenario;
    private final Map<String, String> settings = new LinkedHashMap<>();
    private final Map<String, String> params = new LinkedHashMap<>();

    Config(Map<String, String> env) {
        scenario = env.getOrDefault("SCENARIO", "small-inserts");
        var definition = ClickHouseContextCollector.defaultDefinition().scenarios().stream()
                .filter(candidate -> candidate.id().equals(scenario))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Неизвестный сценарий: " + scenario));
        definition.settings().forEach(field -> settings.put(field.key(), read(field, env)));
        definition.params().forEach(field -> params.put(field.key(), read(field, env)));
        if (scenario.equals("sorting-key") && integer("rangeRows") > integer("datasetRows")) {
            throw new IllegalArgumentException("RANGE_ROWS не может превышать DATASET_ROWS");
        }
    }

    String scenario() {
        return scenario;
    }

    int integer(String key) {
        return Integer.parseInt(settings.getOrDefault(key, params.get(key)));
    }

    boolean bool(String key) {
        return Boolean.parseBoolean(params.get(key));
    }

    String settingsLabel() {
        return label(settings);
    }

    String paramsLabel() {
        return label(params);
    }

    static int port(Map<String, String> env, String key, int fallback) {
        try {
            int value = Integer.parseInt(env.getOrDefault(key, Integer.toString(fallback)));
            if (value >= 1 && value <= 65535) {
                return value;
            }
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalArgumentException(key + " должен быть числом от 1 до 65535");
    }

    private static String read(FieldSpec field, Map<String, String> env) {
        String name = ClickHouseContextCollector.environmentKey(field.key());
        String value = env.getOrDefault(name, field.defaultValue().toString());
        try {
            return switch (field.type()) {
                case INTEGER, NUMBER -> {
                    var number = new BigDecimal(value);
                    if (number.compareTo(field.min()) < 0 || number.compareTo(field.max()) > 0) {
                        throw new IllegalArgumentException();
                    }
                    yield field.type() == FieldSpec.Type.INTEGER
                            ? Integer.toString(number.intValueExact()) : number.toPlainString();
                }
                case BOOLEAN -> {
                    if (!value.equals("true") && !value.equals("false")) {
                        throw new IllegalArgumentException();
                    }
                    yield value;
                }
                case ENUM -> {
                    if (!field.options().contains(value)) {
                        throw new IllegalArgumentException();
                    }
                    yield value;
                }
                case STRING -> value;
            };
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            throw new IllegalArgumentException("Недопустимое значение " + name);
        }
    }

    private static String label(Map<String, String> values) {
        return values.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(", "));
    }
}
