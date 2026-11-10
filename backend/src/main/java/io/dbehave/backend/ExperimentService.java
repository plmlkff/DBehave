package io.dbehave.backend;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

@Service
class ExperimentService {
    private final PluginRegistry registry;
    private final TaskRepository tasks;

    ExperimentService(PluginRegistry registry, TaskRepository tasks) {
        this.registry = registry;
        this.tasks = tasks;
    }

    PreparedExperiment prepare(ExperimentRequest request) {
        var errors = new ArrayList<FieldError>();
        if (request.pluginId() == null || request.pluginId().isBlank()) {
            errors.add(new FieldError("pluginId", "Укажите идентификатор"));
        }
        if (request.scenarioId() == null || request.scenarioId().isBlank()) {
            errors.add(new FieldError("scenarioId", "Укажите идентификатор"));
        }
        InputValidation.rejectErrors(errors);

        var plugin = registry.plugin(request.pluginId());
        PluginValidation.validate(plugin);
        var scenario = registry.scenario(plugin, request.scenarioId());
        var collector = registry.collector(plugin.collectorId());
        var settings = InputValidation.normalize("settings", scenario.settings(), request.settings(), errors);
        var params = InputValidation.normalize("params", scenario.params(), request.params(), errors);
        var loaderLimits = InputValidation.resources("loaderImageContext", plugin.loaderImageContext(), request.loaderImageContext(), errors);
        var databaseLimits = InputValidation.resources("databaseImageContext", plugin.databaseImageContext(), request.databaseImageContext(), errors);
        InputValidation.rejectErrors(errors);

        io.dbehave.plugin.ImageContexts collected;
        try {
            collected = collector.collect(plugin, scenario, settings, params);
        } catch (IllegalArgumentException exception) {
            throw new InvalidExperimentException(List.of(new FieldError("context", exception.getMessage())));
        }
        var loaderContext = new HashMap<>(collected.loaderImageContext());
        var databaseContext = new HashMap<>(collected.databaseImageContext());
        loaderContext.putAll(loaderLimits);
        databaseContext.putAll(databaseLimits);
        InputValidation.resources("loaderImageContext", loaderContext, null, errors);
        InputValidation.resources("databaseImageContext", databaseContext, null, errors);
        InputValidation.rejectErrors(errors);
        return new PreparedExperiment(new ExecutionContext(plugin.loaderImageName(), plugin.databaseImageName(), loaderContext, databaseContext),
                plugin.grafanaUrlTemplate());
    }

    CreatedExperiment start(ExperimentRequest request) {
        var prepared = prepare(request);
        var id = UUID.randomUUID();
        String grafanaUrl = prepared.grafanaUrlTemplate().replace("{runId}", id.toString());
        var task = tasks.insert(id, prepared.context());
        return new CreatedExperiment(task.id(), task.state(), grafanaUrl, task.createdAt(), task.updatedAt());
    }

    TaskStatus status(UUID id) {
        var task = tasks.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Запуск не найден: " + id));
        return new TaskStatus(task.id(), task.state(), task.createdAt(), task.updatedAt(), task.heartbeat(), task.workerId(), task.error());
    }

    record PreparedExperiment(ExecutionContext context, String grafanaUrlTemplate) {
    }

    record CreatedExperiment(UUID id, String state, String grafanaUrl, Instant createdAt, Instant updatedAt) {
    }

    record TaskStatus(UUID id, String state, Instant createdAt, Instant updatedAt, Instant heartbeat, String workerId, String error) {
    }
}
