package io.dbehave.backend.task;

import java.util.Map;

public record ExecutionContext(
        String loaderImageName,
        String databaseImageName,
        Map<String, Object> loaderImageContext,
        Map<String, Object> databaseImageContext
) {
    public ExecutionContext {
        loaderImageContext = Map.copyOf(loaderImageContext);
        databaseImageContext = Map.copyOf(databaseImageContext);
    }
}
