package io.dbehave.backend;

import java.time.Instant;
import java.util.UUID;

public record WorkerTask(
        UUID id,
        String state,
        Instant createdAt,
        Instant updatedAt,
        ExecutionContext context,
        Instant heartbeat,
        String workerId,
        String error
) {
}
