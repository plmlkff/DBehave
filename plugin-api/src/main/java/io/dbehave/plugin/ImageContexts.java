package io.dbehave.plugin;

import java.util.Map;

public record ImageContexts(
        Map<String, Object> loaderImageContext,
        Map<String, Object> databaseImageContext
) {
    public ImageContexts {
        loaderImageContext = Map.copyOf(loaderImageContext);
        databaseImageContext = Map.copyOf(databaseImageContext);
    }
}
