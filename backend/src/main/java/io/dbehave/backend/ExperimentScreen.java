package io.dbehave.backend;

import io.dbehave.plugin.DatabasePlugin;
import io.dbehave.plugin.FieldSpec;
import io.dbehave.plugin.Scenario;

import java.util.List;

public record ExperimentScreen(
        int schemaVersion,
        String screenId,
        String title,
        List<ScenarioCard> scenarios,
        Form form
) {
    static ExperimentScreen from(DatabasePlugin plugin, Scenario selected) {
        var cards = plugin.scenarios().stream()
                .map(scenario -> new ScenarioCard(
                        scenario.id(), scenario.title(), scenario.description(), scenario.id().equals(selected.id())))
                .toList();
        var form = new Form(
                plugin.id(), plugin.title(), selected.id(), selected.description(),
                List.of(
                        Section.from("settings", "Настройки базы данных", selected.settings()),
                        Section.from("params", "Параметры нагрузки", selected.params())
                ),
                new Submit("Запустить эксперимент", "POST", "/api/experiments/validate")
        );
        return new ExperimentScreen(1, "new-experiment", "Новый эксперимент", cards, form);
    }

    public record ScenarioCard(String id, String title, String description, boolean selected) {
    }

    public record Form(
            String pluginId, String pluginTitle, String scenarioId, String description,
            List<Section> sections, Submit submit
    ) {
    }

    public record Section(String id, String title, List<Field> fields) {
        static Section from(String id, String title, List<FieldSpec> specs) {
            return new Section(id, title, specs.stream().map(spec -> Field.from(id, spec)).toList());
        }
    }

    public record Field(
            String name, String title, String description, FieldSpec.Type type, Object defaultValue,
            Integer min, Integer max, String unit, List<String> options
    ) {
        static Field from(String section, FieldSpec spec) {
            return new Field(
                    section + "." + spec.key(), spec.title(), spec.description(), spec.type(), spec.defaultValue(),
                    spec.min(), spec.max(), spec.unit(), spec.options()
            );
        }
    }

    public record Submit(String label, String method, String url) {
    }
}
