package io.dbehave.backend.plugin;

import io.dbehave.backend.validation.FieldError;
import io.dbehave.backend.validation.InputValidation;
import io.dbehave.backend.validation.InvalidExperimentException;
import io.dbehave.backend.validation.JsonbValidation;
import io.dbehave.plugin.ContextCollector;
import io.dbehave.plugin.PluginDefinition;
import io.dbehave.plugin.Scenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PluginRegistry {
    private final PluginRepository repository;
    private final Map<String, ContextCollector> collectors;
    private final JsonbValidation jsonb;

    public PluginRegistry(PluginRepository repository, List<ContextCollector> collectors, JsonbValidation jsonb) {
        this.repository = repository;
        this.jsonb = jsonb;
        this.collectors = collectors.stream()
                .collect(Collectors.toUnmodifiableMap(ContextCollector::id, Function.identity()));
    }

    public List<PluginDefinition> plugins() {
        return repository.findAll();
    }

    public List<String> collectorIds() {
        return collectors.keySet().stream().sorted().toList();
    }

    public PluginDefinition plugin(String pluginId) {
        return repository.findById(pluginId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Неизвестный плагин: " + pluginId));
    }

    public Scenario scenario(PluginDefinition plugin, String scenarioId) {
        return plugin.scenarios().stream()
                .filter(scenario -> scenario.id().equals(scenarioId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Неизвестный сценарий: " + scenarioId));
    }

    public ContextCollector collector(String id) {
        var collector = id == null ? null : collectors.get(id);
        if (collector == null) {
            throw new InvalidExperimentException(List.of(new FieldError(
                    "collectorId", "Коллектор не зарегистрирован: " + id)));
        }
        return collector;
    }

    public PluginDefinition create(PluginDefinition definition) {
        validate(definition);
        if (!repository.insert(definition)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Плагин уже существует: " + definition.id());
        }
        return definition;
    }

    public PluginDefinition update(String id, PluginDefinition definition) {
        if (!id.equals(definition.id())) {
            throw new InvalidExperimentException(List.of(
                    new FieldError("id", "Идентификатор не совпадает с адресом запроса")));
        }
        validate(definition);
        if (!repository.update(definition)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Неизвестный плагин: " + id);
        }
        return definition;
    }

    private void validate(PluginDefinition definition) {
        jsonb.validate(definition, "plugin");
        PluginValidation.validate(definition);
        var collector = collector(definition.collectorId());
        var errors = new ArrayList<FieldError>();
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
