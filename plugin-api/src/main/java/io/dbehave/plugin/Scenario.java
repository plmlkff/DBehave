package io.dbehave.plugin;

import java.util.List;

public record Scenario(
        String id,
        String title,
        String description,
        String loadGeneratorTag,
        List<FieldSpec> settings,
        List<FieldSpec> params
) {
    public Scenario {
        settings = List.copyOf(settings);
        params = List.copyOf(params);
    }
}
