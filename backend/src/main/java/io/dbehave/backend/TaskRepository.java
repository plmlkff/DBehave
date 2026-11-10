package io.dbehave.backend;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

@Repository
class TaskRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    TaskRepository(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    WorkerTask insert(UUID id, ExecutionContext context) {
        return jdbc.queryForObject("""
                INSERT INTO worker.tasks (id, state, context)
                VALUES (?, 'QUEUED', CAST(? AS jsonb))
                RETURNING *
                """, this::readTask, id, json.writeValueAsString(context));
    }

    Optional<WorkerTask> findById(UUID id) {
        return jdbc.query("SELECT * FROM worker.tasks WHERE id = ?", this::readTask, id)
                .stream().findFirst();
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
