package io.dbehave.backend;

import io.dbehave.plugin.ExperimentPlan;
import io.dbehave.plugin.FieldSpec;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

@Service
class ExperimentService {
    private final PluginRegistry registry;

    ExperimentService(PluginRegistry registry) {
        this.registry = registry;
    }

    ExperimentPlan prepare(ExperimentRequest request) {
        List<FieldError> errors = new ArrayList<>();
        requireId(request.pluginId(), "pluginId", errors);
        requireId(request.scenarioId(), "scenarioId", errors);
        rejectErrors(errors);

        var plugin = registry.plugin(request.pluginId());
        var scenario = registry.scenario(plugin, request.scenarioId());
        var settings = normalize("settings", scenario.settings(), request.settings(), errors);
        var params = normalize("params", scenario.params(), request.params(), errors);
        rejectErrors(errors);
        return plugin.prepare(scenario, settings, params);
    }

    private static void requireId(String value, String name, List<FieldError> errors) {
        if (value == null || value.isBlank()) {
            errors.add(new FieldError(name, "Укажите идентификатор"));
        }
    }

    private static void rejectErrors(List<FieldError> errors) {
        if (!errors.isEmpty()) {
            throw new InvalidExperimentException(errors);
        }
    }

    private static Map<String, Object> normalize(
            String section, List<FieldSpec> specs, Map<String, Object> input, List<FieldError> errors
    ) {
        Map<String, Object> values = input == null ? Map.of() : input;
        var knownKeys = new HashSet<String>();
        var normalized = new HashMap<String, Object>();
        for (FieldSpec spec : specs) {
            knownKeys.add(spec.key());
            Object value = values.getOrDefault(spec.key(), spec.defaultValue());
            String error = validate(spec, value);
            if (error == null) {
                normalized.put(spec.key(), value);
            } else {
                errors.add(new FieldError(section + "." + spec.key(), error));
            }
        }
        for (String key : values.keySet()) {
            if (!knownKeys.contains(key)) {
                errors.add(new FieldError(section + "." + key, "Неизвестный параметр"));
            }
        }
        return Map.copyOf(normalized);
    }

    private static String validate(FieldSpec spec, Object value) {
        return switch (spec.type()) {
            case INTEGER -> validateInteger(spec, value);
            case BOOLEAN -> value instanceof Boolean ? null : "Ожидается логическое значение";
            case STRING -> value instanceof String ? null : "Ожидается строка";
            case ENUM -> value instanceof String option && spec.options().contains(option)
                    ? null : "Выберите одно из допустимых значений";
        };
    }

    private static String validateInteger(FieldSpec spec, Object value) {
        if (!(value instanceof Integer || value instanceof Long || value instanceof BigInteger)) {
            return "Ожидается целое число";
        }
        var integer = new BigInteger(value.toString());
        if (spec.min() != null && integer.compareTo(BigInteger.valueOf(spec.min())) < 0) {
            return "Минимальное значение — " + spec.min();
        }
        if (spec.max() != null && integer.compareTo(BigInteger.valueOf(spec.max())) > 0) {
            return "Максимальное значение — " + spec.max();
        }
        return null;
    }
}
