package io.dbehave.plugin;

import java.math.BigDecimal;
import java.util.List;

public record FieldSpec(
        String key,
        String title,
        String description,
        Type type,
        Object defaultValue,
        BigDecimal min,
        BigDecimal max,
        String unit,
        List<String> options
) {
    public enum Type {
        INTEGER, NUMBER, BOOLEAN, STRING, ENUM
    }

    public FieldSpec {
        options = List.copyOf(options);
    }

    public static FieldSpec integer(
            String key, String title, String description, int defaultValue, int min, int max, String unit
    ) {
        return new FieldSpec(key, title, description, Type.INTEGER, defaultValue,
                BigDecimal.valueOf(min), BigDecimal.valueOf(max), unit, List.of());
    }

    public static FieldSpec number(
            String key, String title, String description, double defaultValue, double min, double max, String unit
    ) {
        return new FieldSpec(key, title, description, Type.NUMBER, BigDecimal.valueOf(defaultValue),
                BigDecimal.valueOf(min), BigDecimal.valueOf(max), unit, List.of());
    }

    public static FieldSpec bool(String key, String title, String description, boolean defaultValue) {
        return new FieldSpec(key, title, description, Type.BOOLEAN, defaultValue, null, null, null, List.of());
    }

    public static FieldSpec string(String key, String title, String description, String defaultValue) {
        return new FieldSpec(key, title, description, Type.STRING, defaultValue, null, null, null, List.of());
    }

    public static FieldSpec enumeration(
            String key, String title, String description, String defaultValue, String... options
    ) {
        return new FieldSpec(key, title, description, Type.ENUM, defaultValue, null, null, null, List.of(options));
    }
}
