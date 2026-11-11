package io.dbehave.backend.experiment;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class GrafanaUrl {
    private static final String RUN_ID = "{runId}";

    private GrafanaUrl() {
    }

    public static String fromTemplate(String template, List<UUID> runIds) {
        if (template == null || runIds == null || runIds.isEmpty()) {
            throw new IllegalArgumentException("Для сравнения нужны шаблон Grafana и идентификаторы запусков");
        }
        for (UUID runId : runIds) {
            if (runId == null) {
                throw new IllegalArgumentException("Идентификатор запуска не может быть пустым");
            }
        }

        var uri = URI.create(template.replace(RUN_ID, "00000000-0000-0000-0000-000000000000"));
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null) {
            throw new IllegalArgumentException("Укажите HTTP(S)-ссылку Grafana");
        }

        int queryStart = template.indexOf('?');
        int fragmentStart = template.indexOf('#');
        if (queryStart < 0 || (fragmentStart >= 0 && queryStart > fragmentStart)) {
            throw new IllegalArgumentException("Шаблон Grafana должен содержать query-переменную var-* со значением {runId}");
        }
        String prefix = template.substring(0, queryStart + 1);
        String fragment = fragmentStart < 0 ? "" : template.substring(fragmentStart);
        if (decode(prefix).contains(RUN_ID) || decode(fragment).contains(RUN_ID)) {
            throw new IllegalArgumentException("Подстановка {runId} поддерживается только в query-переменных Grafana");
        }

        int queryEnd = fragmentStart < 0 ? template.length() : fragmentStart;
        String query = template.substring(queryStart + 1, queryEnd);
        var parameters = new ArrayList<String>();
        boolean foundRunVariable = false;
        for (String parameter : query.split("&", -1)) {
            int separator = parameter.indexOf('=');
            String rawName = separator < 0 ? parameter : parameter.substring(0, separator);
            String name = decode(rawName);
            String value = separator < 0 ? "" : decode(parameter.substring(separator + 1));
            if (name.contains(RUN_ID) || (value.contains(RUN_ID)
                    && !(name.startsWith("var-") && name.length() > 4 && value.equals(RUN_ID)))) {
                throw new IllegalArgumentException("Подстановка {runId} должна быть полным значением query-переменной var-*");
            }
            if (value.equals(RUN_ID)) {
                foundRunVariable = true;
                for (UUID runId : runIds) {
                    parameters.add(rawName + "=" + runId);
                }
            } else {
                parameters.add(parameter);
            }
        }
        if (!foundRunVariable) {
            throw new IllegalArgumentException("В шаблоне Grafana отсутствует query-переменная var-* со значением {runId}");
        }
        return withKiosk(prefix + String.join("&", parameters) + fragment);
    }

    public static String withKiosk(String url) {
        if (url == null) {
            return null;
        }
        int fragmentStart = url.indexOf('#');
        String fragment = fragmentStart < 0 ? "" : url.substring(fragmentStart);
        String base = fragmentStart < 0 ? url : url.substring(0, fragmentStart);
        int queryStart = base.indexOf('?');
        String prefix = queryStart < 0 ? base : base.substring(0, queryStart);
        String query = queryStart < 0 ? "" : base.substring(queryStart + 1);
        var parameters = new ArrayList<String>();
        if (!query.isEmpty()) {
            for (String parameter : query.split("&", -1)) {
                int separator = parameter.indexOf('=');
                String name = decode(separator < 0 ? parameter : parameter.substring(0, separator));
                if (!name.equals("kiosk")) {
                    parameters.add(parameter);
                }
            }
        }
        parameters.add("kiosk");
        return prefix + "?" + String.join("&", parameters) + fragment;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
