package io.dbehave.backend.experiment;

import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonFormat;

public record ExperimentRun(
        @JsonFormat(shape = JsonFormat.Shape.STRING) Long id,
        String state,
        Instant createdAt,
        Instant updatedAt,
        Instant heartbeat,
        String workerId,
        String error,
        String grafanaUrl,
        String pluginId,
        String pluginTitle,
        String scenarioId,
        String scenarioTitle,
        ExperimentRequest configuration,
        String grafanaUrlTemplate,
        String metricsId
) {
}
