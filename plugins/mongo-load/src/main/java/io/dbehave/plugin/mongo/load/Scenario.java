package io.dbehave.plugin.mongo.load;

import com.mongodb.client.MongoDatabase;
import io.dbehave.plugin.load.RunState;

interface Scenario {
    void prepare();

    Runnable client(int index);

    default void verify(MongoDatabase admin) {
    }

    static Scenario create(Config config, MongoDatabase database, Metrics metrics, RunState state) {
        return switch (config.scenario()) {
            case "write-heavy" -> new WriteHeavy(config, database, metrics);
            case "read-heavy" -> new ReadHeavy(config, database, metrics, state);
            case "mixed" -> new Mixed(config, database, metrics, state);
            case "array-index" -> new ArrayIndex(config, database, metrics, state);
            default -> throw new IllegalArgumentException("Неизвестный сценарий: " + config.scenario());
        };
    }
}
