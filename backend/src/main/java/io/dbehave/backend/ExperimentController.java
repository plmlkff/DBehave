package io.dbehave.backend;

import io.dbehave.plugin.PluginDefinition;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
class ExperimentController {
    private final PluginRegistry registry;
    private final ExperimentService experiments;

    ExperimentController(PluginRegistry registry, ExperimentService experiments) {
        this.registry = registry;
        this.experiments = experiments;
    }

    @GetMapping("/api/plugins")
    @Operation(summary = "Получить список плагинов СУБД")
    List<PluginSummary> plugins() {
        return registry.plugins().stream().map(plugin -> new PluginSummary(plugin.id(), plugin.title())).toList();
    }

    @GetMapping("/api/scenarios")
    @Operation(summary = "Получить сценарии выбранного плагина")
    List<ScenarioSummary> scenarios(@RequestParam String pluginId) {
        return registry.plugin(pluginId).scenarios().stream()
                .map(scenario -> new ScenarioSummary(scenario.id(), scenario.title(), scenario.description())).toList();
    }

    @GetMapping("/api/context-collectors")
    @Operation(summary = "Идентификаторы доступных коллекторов")
    List<String> collectors() {
        return registry.collectorIds();
    }

    @GetMapping("/api/plugins/{id}")
    @Operation(summary = "Описание плагина из БД")
    PluginDefinition plugin(@PathVariable String id) {
        return registry.plugin(id);
    }

    @PostMapping("/api/plugins")
    @Operation(summary = "Зарегистрировать плагин", description = "Коллектор должен быть зарегистрирован как бин; ошибки схемы возвращают 400, повторный id — 409.")
    ResponseEntity<PluginDefinition> createPlugin(@RequestBody PluginDefinition definition) {
        var created = registry.create(definition);
        return ResponseEntity.created(URI.create("/api/plugins/" + created.id())).body(created);
    }

    @PutMapping("/api/plugins/{id}")
    @Operation(summary = "Заменить описание плагина", description = "Существующие контексты запусков сохраняются.")
    PluginDefinition updatePlugin(@PathVariable String id, @RequestBody PluginDefinition definition) {
        return registry.update(id, definition);
    }

    @GetMapping("/api/ui/experiment")
    @Operation(summary = "Получить BDUI-дерево формы эксперимента")
    ExperimentScreen form(@RequestParam String pluginId, @RequestParam String scenarioId) {
        var plugin = registry.plugin(pluginId);
        return ExperimentScreen.from(plugin, registry.scenario(plugin, scenarioId));
    }

    @PostMapping("/api/experiments/validate")
    @Operation(summary = "Проверить и собрать контекст без постановки задачи")
    ValidationResult validate(@RequestBody ExperimentRequest request) {
        experiments.prepare(request);
        return new ValidationResult("VALIDATED", "READY");
    }

    @PostMapping("/api/experiments")
    @Operation(summary = "Поставить запуск в очередь воркеров", description = "Возвращает id задачи и ссылку Grafana с подставленным run id.")
    ResponseEntity<ExperimentService.CreatedExperiment> start(@RequestBody ExperimentRequest request) {
        var created = experiments.start(request);
        return ResponseEntity.created(URI.create("/api/experiments/" + created.id())).body(created);
    }

    @GetMapping("/api/experiments/{id}")
    @Operation(summary = "Получить текущий статус запуска из таблицы воркера")
    ExperimentService.TaskStatus status(@PathVariable UUID id) {
        return experiments.status(id);
    }

    @ExceptionHandler(InvalidExperimentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ValidationError invalidExperiment(InvalidExperimentException exception) {
        return new ValidationError("VALIDATION_FAILED", exception.fieldErrors());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ValidationError unreadableRequest() {
        return new ValidationError("INVALID_REQUEST", List.of());
    }

    public record ValidationResult(String status, String execution) {
    }

    public record ValidationError(String code, List<FieldError> fieldErrors) {
    }

    public record PluginSummary(String id, String title) {
    }

    public record ScenarioSummary(String id, String title, String description) {
    }
}
