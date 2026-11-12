package io.dbehave.backend.config;

import io.dbehave.plugin.ContextCollector;
import io.dbehave.plugin.GenericContextCollector;
import io.dbehave.plugin.clickhouse.ClickHouseContextCollector;
import io.dbehave.plugin.mongo.MongoContextCollector;
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
    ContextCollector mongoCollector() {
        return new MongoContextCollector();
    }

    @Bean
    ContextCollector clickhouseCollector() {
        return new ClickHouseContextCollector();
    }

    @Bean
    ContextCollector genericCollector() {
        return new GenericContextCollector();
    }
}
