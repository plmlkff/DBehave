package io.dbehave.backend.plugin;

import io.dbehave.plugin.mongo.MongoContextCollector;
import io.dbehave.plugin.postgres.PostgresContextCollector;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
class PluginCatalogInitializer implements ApplicationRunner {
    private final PluginRepository plugins;

    PluginCatalogInitializer(PluginRepository plugins) {
        this.plugins = plugins;
    }

    @Override
    public void run(ApplicationArguments args) {
        plugins.insert(PostgresContextCollector.defaultDefinition());
        plugins.insert(MongoContextCollector.defaultDefinition());
    }
}
