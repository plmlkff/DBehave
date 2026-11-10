package io.dbehave.backend.plugin;

import io.dbehave.plugin.PluginDefinition;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class PluginRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    PluginRepository(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<PluginDefinition> findAll() {
        return jdbc.query("SELECT definition FROM dbehave.plugins ORDER BY id",
                (row, index) -> json.readValue(row.getString("definition"), PluginDefinition.class));
    }

    public Optional<PluginDefinition> findById(String id) {
        return jdbc.query("SELECT definition FROM dbehave.plugins WHERE id = ?",
                        (row, index) -> json.readValue(row.getString("definition"), PluginDefinition.class), id)
                .stream().findFirst();
    }

    public boolean insert(PluginDefinition definition) {
        return jdbc.update("""
                INSERT INTO dbehave.plugins (id, definition)
                VALUES (?, CAST(? AS jsonb))
                ON CONFLICT (id) DO NOTHING
                """, definition.id(), json.writeValueAsString(definition)) == 1;
    }

    public boolean update(PluginDefinition definition) {
        return jdbc.update("""
                UPDATE dbehave.plugins
                SET definition = CAST(? AS jsonb)
                WHERE id = ?
                """, json.writeValueAsString(definition), definition.id()) == 1;
    }
}
