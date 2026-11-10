package io.dbehave.backend;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

public record ExperimentRequest(
        @Schema(example = "postgres", requiredMode = Schema.RequiredMode.REQUIRED) String pluginId,
        @Schema(example = "mixed", requiredMode = Schema.RequiredMode.REQUIRED) String scenarioId,
        @Schema(description = "Настройки БД. Пропущенные ключи берутся из формы.", example = "{\"shared_buffers\":256}")
        Map<String, Object> settings,
        @Schema(description = "Параметры нагрузки. Пропущенные ключи берутся из формы.", example = "{\"datasetRows\":100000}")
        Map<String, Object> params
) {
}
