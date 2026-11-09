package io.dbehave.plugin.postgres;

import io.dbehave.plugin.DatabasePlugin;
import io.dbehave.plugin.ExperimentPlan;
import io.dbehave.plugin.ExperimentPlan.ContainerSpec;
import io.dbehave.plugin.Scenario;

import java.util.List;
import java.util.Map;

import static io.dbehave.plugin.FieldSpec.integer;

public final class PostgresPlugin implements DatabasePlugin {
    private static final String DATABASE_NAME = "dbehave_experiment";
    private static final String DATABASE_USER = "dbehave";
    private static final String LOAD_GENERATOR_IMAGE = "dbehave/postgres-load";
    private static final Scenario MIXED = new Scenario(
            "mixed", "Смешанная нагрузка",
            "Сценарий создаёт тестовую таблицу и выполняет чтения и записи. Доля записей дополняет долю чтений до 100%.",
            "mixed",
            List.of(
                    integer("shared_buffers", "shared_buffers", "Память для общего кеша страниц", 256, 16, 1024, "МБ"),
                    integer("max_connections", "max_connections", "Максимум подключений к тестовой БД", 100, 10, 500, null)
            ),
            List.of(
                    integer("clients", "Параллельные клиенты", "Число исполнителей нагрузки", 32, 1, 128, null),
                    integer("durationSec", "Длительность", "Продолжительность измерения", 60, 1, 600, "с"),
                    integer("rows", "Количество строк", "Начальный объём тестовой таблицы", 1_000_000, 1, 10_000_000, "строк"),
                    integer("readPercent", "Доля чтений", "Доля записей рассчитывается автоматически", 70, 0, 100, "%")
            )
    );

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
        return List.of(MIXED);
    }

    @Override
    public ExperimentPlan prepare(Scenario scenario, Map<String, Object> settings, Map<String, Object> params) {
        // The executor supplies the database network alias and matching credentials for both containers
        ContainerSpec database = new ContainerSpec("postgres:18", Map.of(
                "POSTGRES_DB", DATABASE_NAME,
                "POSTGRES_USER", DATABASE_USER,
                "DBEHAVE_SHARED_BUFFERS", settings.get("shared_buffers") + "MB",
                "DBEHAVE_MAX_CONNECTIONS", settings.get("max_connections").toString()
        ), List.of("sh", "-ec", """
                exec docker-entrypoint.sh postgres \
                  -c "shared_buffers=${DBEHAVE_SHARED_BUFFERS}" \
                  -c "max_connections=${DBEHAVE_MAX_CONNECTIONS}"
                """));
        String loadGeneratorImage = LOAD_GENERATOR_IMAGE + ":" + scenario.loadGeneratorTag();
        ContainerSpec loadGenerator = new ContainerSpec(loadGeneratorImage, Map.of(
                "DB_HOST", "database",
                "DB_PORT", "5432",
                "DB_NAME", DATABASE_NAME,
                "DB_USER", DATABASE_USER,
                "SCENARIO", scenario.id(),
                "CLIENTS", params.get("clients").toString(),
                "DURATION_SEC", params.get("durationSec").toString(),
                "ROWS", params.get("rows").toString(),
                "READ_PERCENT", params.get("readPercent").toString()
        ), List.of());
        return new ExperimentPlan(database, loadGenerator);
    }
}
