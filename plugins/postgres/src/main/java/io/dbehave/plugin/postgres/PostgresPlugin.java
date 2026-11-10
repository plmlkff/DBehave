package io.dbehave.plugin.postgres;

import io.dbehave.plugin.DatabasePlugin;
import io.dbehave.plugin.ExperimentPlan;
import io.dbehave.plugin.ExperimentPlan.ContainerSpec;
import io.dbehave.plugin.FieldSpec;
import io.dbehave.plugin.Scenario;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Плагин PostgreSQL: описывает сценарии и строит план из двух контейнеров.
 * <ul>
 *     <li>БД — официальный образ {@value #DATABASE_IMAGE}, настройки передаются через {@code postgres -c};</li>
 *     <li>нагрузчик — образ {@value #LOAD_GENERATOR_IMAGE}, параметры передаются переменными окружения.</li>
 * </ul>
 * Воркер должен поместить оба контейнера в одну сеть, где БД доступна по имени {@value #DATABASE_HOST}.
 * Контракт нагрузчика описан в {@code plugins/postgres-load/README.md}.
 */
public final class PostgresPlugin implements DatabasePlugin {
    public static final String DATABASE_IMAGE = "postgres:18";
    public static final String LOAD_GENERATOR_IMAGE = "dbehave/postgres-load:0.1.0";
    public static final String DATABASE_HOST = "database";

    static final String DATABASE_NAME = "dbehave";
    static final String DATABASE_USER = "dbehave";
    // Контейнер одноразовый и недоступен снаружи, поэтому секретность пароля не важна
    static final String DATABASE_PASSWORD = "dbehave";

    private final List<Scenario> scenarios;
    private final Map<String, Scenario> scenariosById;

    public PostgresPlugin() {
        this(PostgresScenarios.all());
    }

    PostgresPlugin(List<Scenario> scenarios) {
        this.scenarios = List.copyOf(scenarios);
        this.scenariosById = scenarios.stream()
                .collect(Collectors.toUnmodifiableMap(Scenario::id, Function.identity()));
    }

    @Override
    public String id() {
        return "postgres";
    }

    @Override
    public String title() {
        return "PostgreSQL 18";
    }

    @Override
    public List<Scenario> scenarios() {
        return scenarios;
    }

    @Override
    public ExperimentPlan prepare(Scenario scenario, Map<String, Object> settings, Map<String, Object> params) {
        Scenario known = scenariosById.get(scenario.id());
        if (known == null) {
            throw new IllegalArgumentException("Unsupported PostgreSQL scenario: " + scenario.id());
        }
        return new ExperimentPlan(database(known, settings), loadGenerator(known, params));
    }

    private static ContainerSpec database(Scenario scenario, Map<String, Object> settings) {
        // docker-entrypoint.sh в начале команды: она одинаково работает, если воркер передаёт её как CMD
        // (entrypoint образа запустит её как есть) и как ENTRYPOINT
        var command = new ArrayList<>(List.of("docker-entrypoint.sh", "postgres"));
        for (FieldSpec spec : scenario.settings()) {
            Object value = Objects.requireNonNull(settings.get(spec.key()), spec.key());
            command.add("-c");
            command.add(spec.key() + "=" + PgSettings.byKey(spec.key()).format(value));
        }
        var environment = Map.of(
                "POSTGRES_DB", DATABASE_NAME,
                "POSTGRES_USER", DATABASE_USER,
                "POSTGRES_PASSWORD", DATABASE_PASSWORD);
        return new ContainerSpec(DATABASE_IMAGE, environment, command);
    }

    private static ContainerSpec loadGenerator(Scenario scenario, Map<String, Object> params) {
        var environment = new LinkedHashMap<String, String>();
        environment.put("DB_HOST", DATABASE_HOST);
        environment.put("DB_PORT", "5432");
        environment.put("DB_NAME", DATABASE_NAME);
        environment.put("DB_USER", DATABASE_USER);
        environment.put("DB_PASSWORD", DATABASE_PASSWORD);
        environment.put("SCENARIO", scenario.id());
        for (FieldSpec spec : scenario.params()) {
            Object value = Objects.requireNonNull(params.get(spec.key()), spec.key());
            environment.put(environmentName(spec.key()), formatParam(value));
        }
        return new ContainerSpec(LOAD_GENERATOR_IMAGE, environment, List.of());
    }

    /** {@code durationSec} → {@code DURATION_SEC}. */
    static String environmentName(String key) {
        return key.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }

    private static String formatParam(Object value) {
        return value instanceof Number number ? new BigDecimal(number.toString()).toPlainString() : value.toString();
    }
}
