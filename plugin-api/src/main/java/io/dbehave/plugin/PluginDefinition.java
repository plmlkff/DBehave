package io.dbehave.plugin;

import java.util.List;
import java.util.Map;

/** Persisted plugin metadata and the BDUI contract used to validate user input. */
public record PluginDefinition(
        String id,
        String title,
        String collectorId,
        String loaderImageName,
        String databaseImageName,
        String grafanaUrlTemplate,
        Map<String, Object> loaderImageContext,
        Map<String, Object> databaseImageContext,
        List<Scenario> scenarios
) {
    public PluginDefinition {
        loaderImageContext = loaderImageContext == null ? Map.of() : Map.copyOf(loaderImageContext);
        databaseImageContext = databaseImageContext == null ? Map.of() : Map.copyOf(databaseImageContext);
        scenarios = List.copyOf(scenarios);
    }
}
