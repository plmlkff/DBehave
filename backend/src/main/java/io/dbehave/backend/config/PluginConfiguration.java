package io.dbehave.backend.config;

import io.dbehave.plugin.ContextCollector;
import io.dbehave.plugin.GenericContextCollector;
import io.dbehave.plugin.postgres.PostgresContextCollector;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PluginConfiguration {
    @Bean
    ContextCollector postgresCollector() {
        return new PostgresContextCollector();
    }

    @Bean
    ContextCollector genericCollector() {
        return new GenericContextCollector();
    }
}
