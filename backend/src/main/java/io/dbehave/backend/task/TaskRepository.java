package io.dbehave.backend.task;

import io.dbehave.backend.experiment.ExperimentRun;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class TaskRepository {
    private static final String EXPERIMENT_QUERY = """
            SELECT task.id, task.state, task.created_at, task.updated_at,
                   task.heartbeat, task.worker_id, task.error,
                   experiment.grafana_url, experiment.plugin_id, experiment.plugin_title,
                   experiment.scenario_id, experiment.scenario_title
            FROM worker.tasks AS task
            LEFT JOIN dbehave.experiments AS experiment ON experiment.task_id = task.id
            """;

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    TaskRepository(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public WorkerTask insert(UUID id, ExecutionContext context) {
        return jdbc.queryForObject("""
                INSERT INTO worker.tasks (id, state, context)
                VALUES (?, 'QUEUED', CAST(? AS jsonb))
                RETURNING *
                """, this::readTask, id, json.writeValueAsString(context));
    }

    public Optional<WorkerTask> findById(UUID id) {
        return jdbc.query("SELECT * FROM worker.tasks WHERE id = ?", this::readTask, id)
                .stream().findFirst();
    }

    public void insertExperiment(UUID id, String pluginId, String pluginTitle,
                          String scenarioId, String scenarioTitle, String grafanaUrl) {
        jdbc.update("""
                INSERT INTO dbehave.experiments
                    (task_id, plugin_id, plugin_title, scenario_id, scenario_title, grafana_url)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, pluginId, pluginTitle, scenarioId, scenarioTitle, grafanaUrl);
    }

    public Optional<ExperimentRun> findExperimentById(UUID id) {
        return jdbc.query(EXPERIMENT_QUERY + " WHERE task.id = ?", this::readExperiment, id)
                .stream().findFirst();
    }

    public List<ExperimentRun> findRecentExperiments() {
        return jdbc.query(EXPERIMENT_QUERY + " ORDER BY task.created_at DESC, task.id DESC LIMIT 100",
                this::readExperiment);
    }

    private ExperimentRun readExperiment(ResultSet row, int index) throws SQLException {
        Timestamp heartbeat = row.getTimestamp("heartbeat");
        return new ExperimentRun(
                row.getObject("id", UUID.class), row.getString("state"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant(),
                heartbeat == null ? null : heartbeat.toInstant(), row.getString("worker_id"), row.getString("error"),
                row.getString("grafana_url"), row.getString("plugin_id"), row.getString("plugin_title"),
                row.getString("scenario_id"), row.getString("scenario_title")
        );
    }

    private WorkerTask readTask(ResultSet row, int index) throws SQLException {
        Timestamp heartbeat = row.getTimestamp("heartbeat");
        return new WorkerTask(
                row.getObject("id", UUID.class),
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
