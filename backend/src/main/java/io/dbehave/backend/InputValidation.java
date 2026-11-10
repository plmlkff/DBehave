package io.dbehave.backend;

import io.dbehave.plugin.FieldSpec;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class InputValidation {
    private static final Set<String> RESOURCE_KEYS = Set.of("core_max", "mem_max");

    private InputValidation() {
    }

    static void rejectErrors(List<FieldError> errors) {
        if (!errors.isEmpty()) {
            throw new InvalidExperimentException(errors);
        }
    }

    static Map<String, Object> normalize(
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
        return normalized;
    }

    static String validate(FieldSpec spec, Object value) {
        return switch (spec.type()) {
            case INTEGER -> isInteger(value) ? bounds(spec, (Number) value) : "Ожидается целое число";
            case NUMBER -> value instanceof Number number ? bounds(spec, number) : "Ожидается число";
            case BOOLEAN -> value instanceof Boolean ? null : "Ожидается логическое значение";
            case STRING -> value instanceof String ? null : "Ожидается строка";
            case ENUM -> value instanceof String option && spec.options().contains(option)
                    ? null : "Выберите одно из допустимых значений";
        };
    }

    static Map<String, Object> resources(
            String section, Map<String, Object> defaults, Map<String, Object> overrides, List<FieldError> errors
    ) {
        var result = new HashMap<String, Object>();
        for (String key : RESOURCE_KEYS) {
            if (defaults.containsKey(key)) {
                result.put(key, defaults.get(key));
            }
        }
        if (overrides != null) {
            overrides.forEach((key, value) -> {
                if (RESOURCE_KEYS.contains(key)) {
                    result.put(key, value);
                } else {
                    errors.add(new FieldError(section + "." + key, "Неизвестный параметр"));
                }
            });
        }
        result.forEach((key, value) -> {
            boolean valid = false;
            try {
                if (key.equals("core_max") && value instanceof Number number) {
                    valid = new BigDecimal(number.toString()).signum() > 0;
                } else if (key.equals("mem_max") && isInteger(value)) {
                    var bytes = new BigInteger(value.toString());
                    valid = bytes.signum() > 0 && bytes.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0;
                }
            } catch (NumberFormatException ignored) {
                // NaN and infinity are not valid resource limits.
            }
            if (!valid) {
                errors.add(new FieldError(section + "." + key, key.equals("core_max")
                        ? "Ожидается положительное конечное число ядер"
                        : "Ожидается положительное целое число байт до " + Long.MAX_VALUE));
            }
        });
        return result;
    }

    private static boolean isInteger(Object value) {
        return value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof BigInteger;
    }

    private static String bounds(FieldSpec spec, Number value) {
        BigDecimal number;
        try {
            number = new BigDecimal(value.toString());
        } catch (NumberFormatException exception) {
            return "Ожидается конечное число";
        }
        if (spec.min() != null && number.compareTo(spec.min()) < 0) {
            return "Минимальное значение — " + spec.min();
        }
        if (spec.max() != null && number.compareTo(spec.max()) > 0) {
            return "Максимальное значение — " + spec.max();
        }
        return null;
    }
}
