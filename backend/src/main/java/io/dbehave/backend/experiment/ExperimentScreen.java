package io.dbehave.backend.experiment;

import io.dbehave.plugin.FieldSpec;
import io.dbehave.plugin.PluginDefinition;
import io.dbehave.plugin.Scenario;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record ExperimentScreen(
        int schemaVersion,
        String screenId,
        List<ScenarioCard> scenarios,
        Form form
) {
    public static ExperimentScreen from(PluginDefinition plugin, Scenario selected) {
        var cards = plugin.scenarios().stream()
                .map(scenario -> new ScenarioCard(
                        scenario.id(), scenario.title(), scenario.description(), scenario.id().equals(selected.id())))
                .toList();
        var form = new Form(
                plugin.id(), plugin.title(), selected.id(), selected.description(),
                List.of(
                        Section.from("settings", selected.settings()),
                        Section.from("params", selected.params()),
                        Section.resources("loaderImageContext", plugin.loaderImageContext()),
                        Section.resources("databaseImageContext", plugin.databaseImageContext())
                )
        );
        return new ExperimentScreen(1, "new-experiment", cards, form);
    }

    public record ScenarioCard(String id, String title, String description, boolean selected) {
    }

    public record Form(
            String pluginId, String pluginTitle, String scenarioId, String description,
            List<Section> sections
    ) {
    }

    public record Section(String id, List<Field> fields) {
        static Section resources(String id, Map<String, Object> context) {
            return new Section(id, List.of(
                    new Field(
                            id + ".core_max", "Лимит CPU",
                            "Положительное число ядер. Пустое поле использует настройку плагина.",
                            FieldSpec.Type.NUMBER, context.get("core_max"),
                            BigDecimal.ZERO, null, "ядра", List.of(), true),
                    new Field(
                            id + ".mem_max", "Лимит памяти",
                            "Положительное целое число байт. Пустое поле использует настройку плагина.",
                            FieldSpec.Type.INTEGER, context.get("mem_max"),
                            BigDecimal.ONE, BigDecimal.valueOf(Long.MAX_VALUE), "байт", List.of(), false)
            ));
        }

        static Section from(String id, List<FieldSpec> specs) {
            return new Section(id, specs.stream().map(spec -> Field.from(id, spec)).toList());
        }
    }

    public record Field(
            String name, String title, String description, FieldSpec.Type type, Object defaultValue,
            BigDecimal min, BigDecimal max, String unit, List<String> options, boolean minExclusive
    ) {
        static Field from(String section, FieldSpec spec) {
            return new Field(
                    section + "." + spec.key(), spec.title(), spec.description(), spec.type(), spec.defaultValue(),
                    spec.min(), spec.max(), spec.unit(), spec.options(), false
            );
        }
    }
}
