package io.dbehave.backend.task;

import io.dbehave.backend.experiment.ExperimentRun;
import io.dbehave.backend.experiment.ExperimentRequest;
import io.dbehave.backend.experiment.GrafanaUrl;
import io.dbehave.backend.validation.JsonbValidation;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class TaskRepository {
    private static final String EXPERIMENT_QUERY = """
            SELECT task.id, COALESCE(task.legacy_run_id, task.id::text) AS metrics_id, task.state, task.created_at, task.updated_at,
                   task.heartbeat, task.worker_id, task.error,
                   experiment.grafana_url, experiment.plugin_id, experiment.plugin_title,
                   experiment.scenario_id, experiment.scenario_title,
                   experiment.configuration, experiment.grafana_url_template
            FROM worker.tasks AS task
            LEFT JOIN dbehave.experiments AS experiment ON experiment.task_id = task.id
            WHERE NOT EXISTS (
                SELECT 1 FROM dbehave.hidden_experiments AS hidden WHERE hidden.task_id = task.id
            )
            """;

    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final JsonbValidation jsonb;

    TaskRepository(JdbcTemplate jdbc, JsonMapper json, JsonbValidation jsonb) {
        this.jdbc = jdbc;
        this.json = json;
        this.jsonb = jsonb;
    }

    public WorkerTask insert(ExecutionContext context) {
        jsonb.validate(context, "context");
        return jdbc.queryForObject("""
                INSERT INTO worker.tasks (state, context)
                VALUES ('QUEUED', CAST(? AS jsonb))
                RETURNING *
                """, this::readTask, json.writeValueAsString(context));
    }

    public Optional<WorkerTask> findById(Long id) {
        return jdbc.query("SELECT * FROM worker.tasks WHERE id = ?", this::readTask, id)
                .stream().findFirst();
    }

    public void insertExperiment(
            Long id, String pluginId, String pluginTitle,
            String scenarioId, String scenarioTitle, String grafanaUrl,
            ExperimentRequest configuration, String grafanaUrlTemplate
    ) {
        jsonb.validate(configuration, "configuration");
        jdbc.update("""
                INSERT INTO dbehave.experiments
                    (task_id, plugin_id, plugin_title, scenario_id, scenario_title, grafana_url,
                     configuration, grafana_url_template)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                """, id, pluginId, pluginTitle, scenarioId, scenarioTitle, grafanaUrl,
                json.writeValueAsString(configuration), grafanaUrlTemplate);
    }

    public Optional<ExperimentRun> findExperimentById(Long id) {
        return jdbc.query(EXPERIMENT_QUERY + " AND task.id = ?", this::readExperiment, id)
                .stream().findFirst();
    }

    public List<ExperimentRun> findExperimentsByIds(List<Long> ids) {
        return jdbc.query(EXPERIMENT_QUERY + " AND task.id = ANY (?)",
                statement -> statement.setArray(1, statement.getConnection().createArrayOf("bigint", ids.toArray())),
                this::readExperiment);
    }

    public List<ExperimentRun> findRecentExperiments() {
        return jdbc.query(EXPERIMENT_QUERY + " ORDER BY task.created_at DESC, task.id DESC LIMIT 100",
                this::readExperiment);
    }

    public boolean hideExperiment(Long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                WITH existing AS (
                    SELECT id FROM worker.tasks WHERE id = ? FOR KEY SHARE
                ), hidden AS (
                    INSERT INTO dbehave.hidden_experiments (task_id)
                    SELECT id FROM existing
                    ON CONFLICT (task_id) DO NOTHING
                )
                SELECT EXISTS (SELECT 1 FROM existing)
                """, Boolean.class, id));
    }

    public List<Long> lockTaskIds(List<Long> ids) {
        return jdbc.query("SELECT id FROM worker.tasks WHERE id = ANY (?) ORDER BY id FOR KEY SHARE",
                statement -> statement.setArray(1, statement.getConnection().createArrayOf("bigint", ids.toArray())),
                (row, index) -> row.getLong("id"));
    }

    public void hideExperiments(List<Long> ids) {
        jdbc.update("""
                INSERT INTO dbehave.hidden_experiments (task_id)
                SELECT id FROM unnest(?) AS selected(id) ORDER BY id
                ON CONFLICT (task_id) DO NOTHING
                """, statement -> statement.setArray(1,
                statement.getConnection().createArrayOf("bigint", ids.toArray())));
    }

    public void hideAllExperiments() {
        jdbc.update("""
                INSERT INTO dbehave.hidden_experiments (task_id)
                SELECT task.id FROM worker.tasks AS task
                WHERE NOT EXISTS (
                    SELECT 1 FROM dbehave.hidden_experiments AS hidden WHERE hidden.task_id = task.id
                )
                ORDER BY task.id
                FOR KEY SHARE OF task
                ON CONFLICT (task_id) DO NOTHING
                """);
    }

    private ExperimentRun readExperiment(ResultSet row, int index) throws SQLException {
        Timestamp heartbeat = row.getTimestamp("heartbeat");
        String configuration = row.getString("configuration");
        return new ExperimentRun(
                row.getLong("id"),
                row.getString("state"),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant(),
                heartbeat == null ? null : heartbeat.toInstant(),
                row.getString("worker_id"),
                row.getString("error"),
                GrafanaUrl.withKiosk(row.getString("grafana_url")),
                row.getString("plugin_id"),
                row.getString("plugin_title"),
                row.getString("scenario_id"),
                row.getString("scenario_title"),
                configuration == null ? null : json.readValue(configuration, ExperimentRequest.class),
                row.getString("grafana_url_template"),
                row.getString("metrics_id")
        );
    }

    private WorkerTask readTask(ResultSet row, int index) throws SQLException {
        Timestamp heartbeat = row.getTimestamp("heartbeat");
        return new WorkerTask(
                row.getLong("id"),
                row.getString("state"),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant(),
                json.readValue(row.getString("context"), ExecutionContext.class),
                heartbeat == null ? null : heartbeat.toInstant(),
                row.getString("worker_id"),
                row.getString("error")
        );
    }
}
