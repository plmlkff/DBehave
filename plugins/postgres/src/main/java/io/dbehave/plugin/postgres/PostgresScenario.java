package io.dbehave.plugin.postgres;

import io.dbehave.plugin.FieldSpec;
import io.dbehave.plugin.Scenario;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

interface PostgresScenario {
    Definition definition();

    default Map<String, String> loadEnvironment(Map<String, Object> params) {
        return definition().params().stream().collect(Collectors.toUnmodifiableMap(
                ContainerField::environmentKey, field -> field.environmentValue(params)));
    }

    record Definition(
            String id, String title, String description, String loadGeneratorTag,
            List<ContainerField> settings, List<ContainerField> params
    ) {
        public Definition {
            settings = List.copyOf(settings);
            params = List.copyOf(params);
        }

        Scenario describe() {
            return new Scenario(id, title, description, loadGeneratorTag,
                    settings.stream().map(ContainerField::spec).toList(),
                    params.stream().map(ContainerField::spec).toList());
        }
    }

    record ContainerField(FieldSpec spec, String environmentKey, String suffix) {
        ContainerField(FieldSpec spec, String environmentKey) {
            this(spec, environmentKey, "");
        }

        String environmentValue(Map<String, Object> values) {
            return Objects.requireNonNull(values.get(spec.key()), spec.key()).toString() + suffix;
        }
    }
}
