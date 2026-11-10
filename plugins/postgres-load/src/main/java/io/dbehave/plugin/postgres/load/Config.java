package io.dbehave.plugin.postgres.load;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Параметры запуска из переменных окружения. Их формирует плагин в бэкенде
 * ({@code PostgresPlugin}): имена совпадают с ключами формы в UPPER_SNAKE_CASE.
 */
public final class Config {
    private final Map<String, String> env;

    public Config(Map<String, String> env) {
        this.env = Map.copyOf(env);
    }

    public static Config fromSystem() {
        return new Config(System.getenv());
    }

    public String jdbcUrl() {
        return "jdbc:postgresql://%s:%d/%s".formatted(string("DB_HOST", "database"), integer("DB_PORT", 5432),
                string("DB_NAME", "dbehave"));
    }

    public String user() {
        return string("DB_USER", "dbehave");
    }

    public String password() {
        return string("DB_PASSWORD", "dbehave");
    }

    public String scenario() {
        return required("SCENARIO");
    }

    public int httpPort() {
        return integer("HTTP_PORT", 8080);
    }

    public int clients() {
        return integer("CLIENTS", 16, 1, 1024);
    }

    public Duration duration() {
        return Duration.ofSeconds(integer("DURATION_SEC", 30, 1, 3600));
    }

    public Duration warmup() {
        return Duration.ofSeconds(integer("WARMUP_SEC", 5, 0, 600));
    }

    public String required(String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Не задана переменная окружения " + name);
        }
        return value.trim();
    }

    public String string(String name, String defaultValue) {
        String value = env.get(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    public String oneOf(String name, String defaultValue, Set<String> allowed) {
        String value = string(name, defaultValue);
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException("%s=%s, допустимо: %s".formatted(name, value, allowed));
        }
        return value;
    }

    public int integer(String name, int defaultValue) {
        return integer(name, defaultValue, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    public int integer(String name, int defaultValue, int min, int max) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("%s=%s: ожидается целое число".formatted(name, value));
        }
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException("%s=%d: допустимо от %d до %d".formatted(name, parsed, min, max));
        }
        return parsed;
    }

    public boolean bool(String name, boolean defaultValue) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> throw new IllegalArgumentException("%s=%s: ожидается true или false".formatted(name, value));
        };
    }
}
