package io.dbehave.backend;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
    @ApiResponse(responseCode = "200", description = "Список зарегистрированных плагинов")
    List<PluginSummary> plugins(){
        return registry.allPlugins().stream()
                .map(plugin -> new PluginSummary(
                        plugin.id(),
                        plugin.title())
                ).toList();
    }

    @GetMapping("/api/scenarios")
    @Operation(summary = "Получить сценарии выбранного плагина")
    @ApiResponse(responseCode = "200", description = "Список сценариев плагина")
    @ApiResponse(responseCode = "400", description = "Не указан обязательный параметр pluginId", content = @Content)
    @ApiResponse(responseCode = "404", description = "Плагин не найден", content = @Content)
    List<ScenarioSummary> scenarios(
            @Parameter(description = "Идентификатор плагина СУБД", example = "postgres")
            @RequestParam String pluginId
    ){
        var plugin = registry.plugin(pluginId);
        return registry.scenarios(plugin).stream()
                .map(scenario -> new ScenarioSummary(
                        scenario.id(),
                        scenario.title(),
                        scenario.description()
                )).toList();
    }

    @GetMapping("/api/ui/experiment")
    @Operation(summary = "Получить BDUI-дерево формы эксперимента")
    @ApiResponse(responseCode = "200", description = "Форма выбранного сценария")
    @ApiResponse(responseCode = "404", description = "Плагин или сценарий не найден", content = @Content)
    ExperimentScreen form(@RequestParam String pluginId, @RequestParam String scenarioId) {
        var plugin = registry.plugin(pluginId);
        return ExperimentScreen.from(plugin, registry.scenario(plugin, scenarioId));
    }

    @PostMapping("/api/experiments/validate")
    @Operation(summary = "Проверить конфигурацию эксперимента", description = "После проверки формируется внутренний план контейнеров.")
    @ApiResponse(responseCode = "200", description = "Конфигурация валидна, исполнение пока заглушено")
    @ApiResponse(responseCode = "400", description = "Неверная конфигурация",
            content = @Content(schema = @Schema(implementation = ValidationError.class)))
    @ApiResponse(responseCode = "404", description = "Плагин или сценарий не найден", content = @Content)
    ValidationResult validate(@RequestBody ExperimentRequest request) {
        experiments.prepare(request);
        return new ValidationResult("VALIDATED", "NOT_IMPLEMENTED");
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

    public record PluginSummary(String id, String title) {}

    public record ScenarioSummary(String id, String title, String description) {}
}
