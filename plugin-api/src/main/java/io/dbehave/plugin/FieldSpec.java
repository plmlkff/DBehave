package io.dbehave.plugin;

import java.util.List;

public record FieldSpec(
        String key,
        String title,
        String description,
        Type type,
        Object defaultValue,
        Integer min,
        Integer max,
        String unit,
        List<String> options
) {
    public enum Type {
        INTEGER, BOOLEAN, STRING, ENUM
    }

    public FieldSpec {
        options = List.copyOf(options);
    }

    public static FieldSpec integer(
            String key, String title, String description, int defaultValue, int min, int max, String unit
    ) {
        return new FieldSpec(key, title, description, Type.INTEGER, defaultValue, min, max, unit, List.of());
    }
}
