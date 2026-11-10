package io.dbehave.backend;

import io.dbehave.plugin.DatabasePlugin;
import io.dbehave.plugin.postgres.PostgresPlugin;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class PluginConfiguration {
    @Bean
    DatabasePlugin postgresPlugin() {
        return new PostgresPlugin();
    }
}
