package io.dbehave.backend.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;

@Configuration(proxyBeanMethods = false)
public class JsonConfiguration {
    @Bean
    JsonMapperBuilderCustomizer rejectDuplicateJsonKeys() {
        return builder -> builder.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }
}
