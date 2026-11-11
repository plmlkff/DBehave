package io.dbehave.backend.experiment;

import io.dbehave.backend.plugin.PluginRegistry;
import io.dbehave.backend.plugin.PluginValidation;
import io.dbehave.backend.task.ExecutionContext;
import io.dbehave.backend.task.TaskRepository;
import io.dbehave.backend.validation.FieldError;
import io.dbehave.backend.validation.InputValidation;
import io.dbehave.backend.validation.InvalidExperimentException;
import io.dbehave.backend.validation.JsonbValidation;
import io.dbehave.plugin.ImageContexts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ExperimentService {
    private final PluginRegistry registry;
    private final TaskRepository tasks;
    private final JsonbValidation jsonb;

    ExperimentService(PluginRegistry registry, TaskRepository tasks, JsonbValidation jsonb) {
        this.registry = registry;
        this.tasks = tasks;
        this.jsonb = jsonb;
    }

    public PreparedExperiment prepare(ExperimentRequest request) {
        var errors = new ArrayList<FieldError>();
        if (request.pluginId() == null || request.pluginId().isBlank()) {
            errors.add(new FieldError("pluginId", "Укажите идентификатор"));
        }
        if (request.scenarioId() == null || request.scenarioId().isBlank()) {
            errors.add(new FieldError("scenarioId", "Укажите идентификатор"));
        }
        InputValidation.rejectErrors(errors);

        var plugin = registry.plugin(request.pluginId());
        jsonb.validate(plugin, "plugin");
        PluginValidation.validate(plugin);
        var scenario = registry.scenario(plugin, request.scenarioId());
        var collector = registry.collector(plugin.collectorId());
        var settings = InputValidation.normalize(
                "settings", scenario.settings(), request.settings(), errors);
        var params = InputValidation.normalize(
                "params", scenario.params(), request.params(), errors);
        var loaderLimits = InputValidation.resources(
                "loaderImageContext", plugin.loaderImageContext(), request.loaderImageContext(), errors);
        var databaseLimits = InputValidation.resources(
                "databaseImageContext", plugin.databaseImageContext(), request.databaseImageContext(), errors);
        InputValidation.rejectErrors(errors);
        jsonb.validate(Map.of("settings", settings, "params", params,
                "loaderImageContext", loaderLimits, "databaseImageContext", databaseLimits), "input");
        var savedSettings = Map.copyOf(settings);
        var savedParams = Map.copyOf(params);

        ImageContexts collected;
        try {
            collected = collector.collect(plugin, scenario, settings, params);
        } catch (IllegalArgumentException exception) {
            throw new InvalidExperimentException(List.of(new FieldError("context", exception.getMessage())));
        }
        var loaderContext = new HashMap<>(collected.loaderImageContext());
        var databaseContext = new HashMap<>(collected.databaseImageContext());
        loaderContext.putAll(loaderLimits);
        databaseContext.putAll(databaseLimits);
        var effectiveLoaderLimits = InputValidation.resources("loaderImageContext", loaderContext, null, errors);
        var effectiveDatabaseLimits = InputValidation.resources("databaseImageContext", databaseContext, null, errors);
        InputValidation.rejectErrors(errors);

        var context = new ExecutionContext(
                plugin.loaderImageName(), plugin.databaseImageName(), loaderContext, databaseContext);
        jsonb.validate(context, "context");
        var configuration = new ExperimentRequest(plugin.id(), scenario.id(), savedSettings, savedParams,
                Map.copyOf(effectiveLoaderLimits), Map.copyOf(effectiveDatabaseLimits));
        return new PreparedExperiment(context,
                plugin.grafanaUrlTemplate(), plugin.id(), plugin.title(), scenario.id(), scenario.title(), configuration);
    }

    @Transactional
    public ExperimentRun start(ExperimentRequest request) {
        var prepared = prepare(request);
        var id = UUID.randomUUID();
        String grafanaUrl = GrafanaUrl.withKiosk(
                prepared.grafanaUrlTemplate().replace("{runId}", id.toString()));
        var task = tasks.insert(id, prepared.context());
        tasks.insertExperiment(
                id, prepared.pluginId(), prepared.pluginTitle(),
                prepared.scenarioId(), prepared.scenarioTitle(), grafanaUrl,
                prepared.configuration(), prepared.grafanaUrlTemplate());
        return new ExperimentRun(
                task.id(), task.state(), task.createdAt(), task.updatedAt(),
                task.heartbeat(), task.workerId(), task.error(), grafanaUrl,
                prepared.pluginId(), prepared.pluginTitle(), prepared.scenarioId(), prepared.scenarioTitle(),
                prepared.configuration(), prepared.grafanaUrlTemplate());
    }

    public ExperimentRun status(UUID id) {
        return tasks.findExperimentById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Запуск не найден: " + id));
    }

    public List<ExperimentRun> history() {
        return tasks.findRecentExperiments();
    }

    public void hide(UUID id) {
        if (!tasks.hideExperiment(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Запуск не найден: " + id);
        }
    }

    public void clearHistory() {
        tasks.hideAllExperiments();
    }

    @Transactional
    public void hideSelected(List<UUID> runIds) {
        var ids = selectedIds(runIds);
        var existing = new HashSet<>(tasks.lockTaskIds(ids));
        for (UUID id : ids) {
            if (!existing.contains(id)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Запуск не найден: " + id);
            }
        }
        tasks.hideExperiments(ids);
    }

    private static List<UUID> selectedIds(List<UUID> runIds) {
        var errors = new ArrayList<FieldError>();
        if (runIds == null || runIds.isEmpty()) {
            errors.add(new FieldError("runIds", "Выберите хотя бы один запуск"));
        } else {
            for (int index = 0; index < runIds.size(); index++) {
                if (runIds.get(index) == null) {
                    errors.add(new FieldError("runIds[" + index + "]", "Укажите идентификатор запуска"));
                }
            }
        }
        InputValidation.rejectErrors(errors);
        return List.copyOf(new LinkedHashSet<>(runIds));
    }

    public String compare(List<UUID> runIds) {
        var ids = selectedIds(runIds);
        var errors = new ArrayList<FieldError>();
        var runs = tasks.findExperimentsByIds(ids).stream()
                .collect(Collectors.toMap(ExperimentRun::id, Function.identity()));
        for (UUID id : ids) {
            if (!runs.containsKey(id)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Запуск не найден: " + id);
            }
        }
        var first = runs.get(ids.getFirst());
        for (UUID id : ids) {
            var run = runs.get(id);
            if (run.grafanaUrlTemplate() == null || run.grafanaUrlTemplate().isBlank()
                    || run.scenarioId() == null || run.scenarioId().isBlank()) {
                errors.add(new FieldError("runIds", "Для выбранного запуска не сохранены данные дашборда и сценария"));
            } else if (!run.grafanaUrlTemplate().equals(first.grafanaUrlTemplate())
                    || !run.scenarioId().equals(first.scenarioId())) {
                errors.add(new FieldError("runIds", "Выберите запуски одного сценария с одинаковым шаблоном дашборда"));
            }
        }
        InputValidation.rejectErrors(errors);
        try {
            return GrafanaUrl.fromTemplate(first.grafanaUrlTemplate(), ids);
        } catch (IllegalArgumentException exception) {
            throw new InvalidExperimentException(List.of(new FieldError("runIds", exception.getMessage())));
        }
    }

    public record PreparedExperiment(
            ExecutionContext context,
            String grafanaUrlTemplate,
            String pluginId,
            String pluginTitle,
            String scenarioId,
            String scenarioTitle,
            ExperimentRequest configuration
    ) {
    }
}
