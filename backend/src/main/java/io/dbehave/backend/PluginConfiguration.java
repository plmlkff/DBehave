package io.dbehave.backend;

import io.dbehave.plugin.ContextCollector;
import io.dbehave.plugin.GenericContextCollector;
import io.dbehave.plugin.postgres.PostgresContextCollector;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class PluginConfiguration {
    @Bean
    ContextCollector postgresCollector() {
        return new PostgresContextCollector();
    }

    @Bean
    ContextCollector genericCollector() {
        return new GenericContextCollector();
    }
}
