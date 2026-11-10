package io.dbehave.plugin;

import java.util.List;
import java.util.Map;

public record ExperimentPlan(ContainerSpec database, ContainerSpec loadGenerator) {
    public record ContainerSpec(String image, Map<String, String> environment, List<String> command) {
        public ContainerSpec {
            environment = Map.copyOf(environment);
            command = List.copyOf(command);
        }
    }
}
