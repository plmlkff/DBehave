package io.dbehave.plugin;

import java.util.List;
import java.util.Map;

public interface DatabasePlugin {
    String id();

    String title();

    List<Scenario> scenarios();

    /** Builds a plan from settings and parameters already validated against the scenario */
    ExperimentPlan prepare(Scenario scenario, Map<String, Object> settings, Map<String, Object> params);
}
