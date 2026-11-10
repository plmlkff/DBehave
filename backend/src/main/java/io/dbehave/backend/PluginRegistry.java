package io.dbehave.backend;

import io.dbehave.plugin.DatabasePlugin;
import io.dbehave.plugin.Scenario;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
class PluginRegistry {
    private final Map<String, DatabasePlugin> plugins;

    PluginRegistry(List<DatabasePlugin> plugins) {
        this.plugins = plugins.stream()
                .collect(Collectors.toUnmodifiableMap(DatabasePlugin::id, Function.identity()));
    }

    DatabasePlugin plugin(String pluginId) {
        DatabasePlugin plugin = plugins.get(pluginId);
        if (plugin == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Неизвестный плагин: " + pluginId);
        }
        return plugin;
    }

    List<DatabasePlugin> allPlugins(){
        return List.copyOf(plugins.values());
    }

    Scenario scenario(DatabasePlugin plugin, String scenarioId) {
        return plugin.scenarios().stream()
                .filter(scenario -> scenario.id().equals(scenarioId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Неизвестный сценарий: " + scenarioId));
    }

    List<Scenario> scenarios(DatabasePlugin plugin){
        return List.copyOf(plugin.scenarios());
    }
}
