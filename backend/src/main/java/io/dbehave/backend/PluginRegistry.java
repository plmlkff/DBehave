package io.dbehave.backend;

import io.dbehave.plugin.ContextCollector;
import io.dbehave.plugin.PluginDefinition;
import io.dbehave.plugin.Scenario;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
class PluginRegistry {
    private final PluginRepository repository;
    private final Map<String, ContextCollector> collectors;

    PluginRegistry(PluginRepository repository, List<ContextCollector> collectors) {
        this.repository = repository;
        this.collectors = collectors.stream()
                .collect(Collectors.toUnmodifiableMap(ContextCollector::id, Function.identity()));
    }

    List<PluginDefinition> plugins() {
        return repository.findAll();
    }

    List<String> collectorIds() {
        return collectors.keySet().stream().sorted().toList();
    }

    PluginDefinition plugin(String pluginId) {
        return repository.findById(pluginId).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Неизвестный плагин: " + pluginId));
    }

    Scenario scenario(PluginDefinition plugin, String scenarioId) {
        return plugin.scenarios().stream()
                .filter(scenario -> scenario.id().equals(scenarioId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Неизвестный сценарий: " + scenarioId));
    }

    ContextCollector collector(String id) {
        var collector = id == null ? null : collectors.get(id);
        if (collector == null) {
            throw new InvalidExperimentException(List.of(new FieldError(
                    "collectorId", "Коллектор не зарегистрирован: " + id)));
        }
        return collector;
    }

    PluginDefinition create(PluginDefinition definition) {
        validate(definition);
        if (!repository.insert(definition)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Плагин уже существует: " + definition.id());
        }
        return definition;
    }

    PluginDefinition update(String id, PluginDefinition definition) {
        if (!id.equals(definition.id())) {
            throw new InvalidExperimentException(List.of(new FieldError("id", "Идентификатор не совпадает с адресом запроса")));
        }
        validate(definition);
        if (!repository.update(definition)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Неизвестный плагин: " + id);
        }
        return definition;
    }

    private void validate(PluginDefinition definition) {
        PluginValidation.validate(definition);
        var collector = collector(definition.collectorId());
        var errors = new java.util.ArrayList<FieldError>();
        for (int index = 0; index < definition.scenarios().size(); index++) {
            var scenario = definition.scenarios().get(index);
            var settings = InputValidation.normalize("settings", scenario.settings(), null, errors);
            var params = InputValidation.normalize("params", scenario.params(), null, errors);
            try {
                collector.collect(definition, scenario, settings, params);
            } catch (IllegalArgumentException exception) {
                errors.add(new FieldError("scenarios[" + index + "].context", exception.getMessage()));
            }
        }
        InputValidation.rejectErrors(errors);
    }
}
