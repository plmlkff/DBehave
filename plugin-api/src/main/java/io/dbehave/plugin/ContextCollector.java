package io.dbehave.plugin;

import java.util.Map;

/** Converts inputs already validated against a stored scenario into container contexts. */
public interface ContextCollector {
    String id();

    ImageContexts collect(
            PluginDefinition plugin, Scenario scenario,
            Map<String, Object> settings, Map<String, Object> params
    );
}
