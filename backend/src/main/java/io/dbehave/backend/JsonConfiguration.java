package io.dbehave.backend;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadFeature;

@Configuration(proxyBeanMethods = false)
class JsonConfiguration {
    @Bean
    JsonMapperBuilderCustomizer rejectDuplicateJsonKeys() {
        return builder -> builder.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
    }
}
