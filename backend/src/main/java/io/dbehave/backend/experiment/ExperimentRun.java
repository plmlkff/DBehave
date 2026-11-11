package io.dbehave.backend.experiment;

import java.time.Instant;
import java.util.UUID;

public record ExperimentRun(
        UUID id,
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
        String grafanaUrlTemplate
) {
}
