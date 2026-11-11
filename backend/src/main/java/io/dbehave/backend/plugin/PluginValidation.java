package io.dbehave.backend.plugin;

import io.dbehave.backend.validation.FieldError;
import io.dbehave.backend.validation.InputValidation;
import io.dbehave.plugin.FieldSpec;
import io.dbehave.plugin.PluginDefinition;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public final class PluginValidation {
    private PluginValidation() {
    }

    public static void validate(PluginDefinition plugin) {
        var errors = new ArrayList<FieldError>();
        boolean validId = matches(plugin.id(), "[A-Za-z0-9_.-]{1,128}")
                && !".".equals(plugin.id()) && !"..".equals(plugin.id());
        require(validId, "id", "Укажите id из 1–128 букв, цифр, точек, дефисов или подчёркиваний. Значения . и .. недопустимы", errors);
        require(notBlank(plugin.title()), "title", "Укажите название", errors);
        require(notBlank(plugin.collectorId()), "collectorId", "Укажите коллектор", errors);
        image(plugin.loaderImageName(), "loaderImageName", errors);
        image(plugin.databaseImageName(), "databaseImageName", errors);
        require(validGrafanaUrl(plugin.grafanaUrlTemplate()),
                "grafanaUrlTemplate", "Укажите HTTP(S)-ссылку с {runId}", errors);
        InputValidation.resources("loaderImageContext", plugin.loaderImageContext(), Map.of(), errors);
        InputValidation.resources("databaseImageContext", plugin.databaseImageContext(), Map.of(), errors);
        require(!plugin.scenarios().isEmpty(), "scenarios", "Укажите хотя бы один сценарий", errors);
        var ids = new HashSet<String>();
        for (int index = 0; index < plugin.scenarios().size(); index++) {
            var scenario = plugin.scenarios().get(index);
            String path = "scenarios[" + index + "]";
            require(notBlank(scenario.id()) && ids.add(scenario.id()),
                    path + ".id", "Укажите уникальный id сценария", errors);
            require(notBlank(scenario.title()), path + ".title", "Укажите название сценария", errors);
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
            require(notBlank(field.key()) && keys.add(field.key()),
                    name + ".key", "Укажите уникальный ключ поля", errors);
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

    private static void image(String value, String name, List<FieldError> errors) {
        require(matches(value, "[A-Za-z0-9][A-Za-z0-9._/:@-]*"), name, "Укажите имя Docker-образа", errors);
    }

    private static boolean validGrafanaUrl(String template) {
        if (template == null || !template.contains("{runId}")) {
            return false;
        }
        try {
            var uri = URI.create(template.replace("{runId}", "00000000-0000-0000-0000-000000000000"));
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && notBlank(uri.getHost());
        } catch (IllegalArgumentException exception) {
            return false;
        }
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
