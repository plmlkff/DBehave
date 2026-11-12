package io.dbehave.backend.task;

import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonFormat;

public record WorkerTask(
        @JsonFormat(shape = JsonFormat.Shape.STRING) Long id,
        String state,
        Instant createdAt,
        Instant updatedAt,
        ExecutionContext context,
        Instant heartbeat,
        String workerId,
        String error
) {
}
