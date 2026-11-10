package io.dbehave.backend;

import io.dbehave.plugin.FieldSpec;
import io.dbehave.plugin.PluginDefinition;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

final class PluginValidation {
    private PluginValidation() {
    }

    static void validate(PluginDefinition plugin) {
        var errors = new ArrayList<FieldError>();
        require(matches(plugin.id(), "[A-Za-z0-9_.-]{1,128}"), "id", "Укажите id: 1–128 букв, цифр, точек, дефисов или подчёркиваний", errors);
        require(notBlank(plugin.title()), "title", "Укажите название", errors);
        require(notBlank(plugin.collectorId()), "collectorId", "Укажите коллектор", errors);
        image(plugin.loaderImageName(), "loaderImageName", true, errors);
        image(plugin.databaseImageName(), "databaseImageName", false, errors);
        boolean validUrl = false;
        try {
            if (plugin.grafanaUrlTemplate() != null && plugin.grafanaUrlTemplate().contains("{runId}")) {
                var uri = URI.create(plugin.grafanaUrlTemplate().replace("{runId}", "00000000-0000-0000-0000-000000000000"));
                validUrl = ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                        && notBlank(uri.getHost());
            }
        } catch (IllegalArgumentException ignored) {
            // Report invalid templates with the other field errors.
        }
        require(validUrl, "grafanaUrlTemplate", "Укажите HTTP(S)-ссылку с {runId}", errors);
        InputValidation.resources("loaderImageContext", plugin.loaderImageContext(), Map.of(), errors);
        InputValidation.resources("databaseImageContext", plugin.databaseImageContext(), Map.of(), errors);
        require(!plugin.scenarios().isEmpty(), "scenarios", "Укажите хотя бы один сценарий", errors);
        var ids = new HashSet<String>();
        for (int index = 0; index < plugin.scenarios().size(); index++) {
            var scenario = plugin.scenarios().get(index);
            String path = "scenarios[" + index + "]";
            require(notBlank(scenario.id()) && ids.add(scenario.id()), path + ".id", "Укажите уникальный id сценария", errors);
            require(notBlank(scenario.title()), path + ".title", "Укажите название сценария", errors);
            if (plugin.loaderImageName() != null && plugin.loaderImageName().contains("{scenarioTag}")) {
                require(matches(scenario.loadGeneratorTag(), "[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}"), path + ".loadGeneratorTag",
                        "Укажите допустимый Docker tag", errors);
            }
            fields(path + ".settings", scenario.settings(), errors);
            fields(path + ".params", scenario.params(), errors);
        }
        InputValidation.rejectErrors(errors);
    }

    private static void fields(String path, List<FieldSpec> fields, List<FieldError> errors) {
        var keys = new HashSet<String>();
        for (int index = 0; index < fields.size(); index++) {
            var field = fields.get(index);
            String name = path + "[" + index + "]";
            require(notBlank(field.key()) && keys.add(field.key()), name + ".key", "Укажите уникальный ключ поля", errors);
            require(notBlank(field.title()), name + ".title", "Укажите название поля", errors);
            require(field.type() != null, name + ".type", "Укажите тип поля", errors);
            require(field.min() == null || field.max() == null || field.min().compareTo(field.max()) <= 0,
                    name, "Минимальное значение превышает максимальное", errors);
            if (field.type() != null) {
                String error = InputValidation.validate(field, field.defaultValue());
                if (error != null) {
                    errors.add(new FieldError(name + ".defaultValue", error));
                }
            }
        }
    }

    private static void image(String value, String name, boolean scenarioTemplate, List<FieldError> errors) {
        String resolved = value == null ? null : scenarioTemplate ? value.replace("{scenarioTag}", "scenario") : value;
        require(matches(resolved, "[A-Za-z0-9][A-Za-z0-9._/:@-]*"), name, "Укажите имя Docker-образа", errors);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean matches(String value, String pattern) {
        return value != null && value.matches(pattern);
    }

    private static void require(boolean valid, String field, String message, List<FieldError> errors) {
        if (!valid) {
            errors.add(new FieldError(field, message));
        }
    }
}
