package io.dbehave.plugin;

import java.util.HashMap;
import java.util.Map;

/** For images that consume scenario parameters and database settings as JSON. */
public final class GenericContextCollector implements ContextCollector {
    @Override
    public String id() {
        return "generic";
    }

    @Override
    public ImageContexts collect(
            PluginDefinition plugin, Scenario scenario,
            Map<String, Object> settings, Map<String, Object> params
    ) {
        var loader = new HashMap<>(plugin.loaderImageContext());
        loader.put("scenarioId", scenario.id());
        loader.put("params", Map.copyOf(params));
        var database = new HashMap<>(plugin.databaseImageContext());
        database.put("settings", Map.copyOf(settings));
        return new ImageContexts(loader, database);
    }
}
