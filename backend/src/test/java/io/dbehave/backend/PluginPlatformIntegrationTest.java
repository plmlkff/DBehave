package io.dbehave.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Runs against a dedicated PostgreSQL database supplied by the caller. */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DBEHAVE_TEST_DATABASE_URL", matches = ".+")
class PluginPlatformIntegrationTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JsonMapper json;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("DBEHAVE_TEST_DATABASE_URL"));
        properties.add("spring.datasource.username",
                () -> System.getenv().getOrDefault("DBEHAVE_TEST_DATABASE_USERNAME", "postgres"));
        properties.add("spring.datasource.password",
                () -> System.getenv().getOrDefault("DBEHAVE_TEST_DATABASE_PASSWORD", ""));
    }

    @Test
    void migratesBothSchemasAndReadsThePersistedCatalog() throws Exception {
        mvc.perform(get("/api/plugins"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem("postgres")));
        mvc.perform(get("/api/plugins/postgres"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collectorId").value("postgres"))
                .andExpect(jsonPath("$.scenarios[*].id", hasItem("mixed")));
        mvc.perform(get("/api/context-collectors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasItem("generic")))
                .andExpect(jsonPath("$", hasItem("postgres")));

        assertThat(jdbc.queryForObject(
                "select jsonb_typeof(definition) from dbehave.plugins where id = 'postgres'", String.class))
                .isEqualTo("object");
        assertThat(jdbc.queryForObject("select to_regclass('worker.tasks')::text", String.class))
                .isEqualTo("worker.tasks");
    }

    @Test
    void registersAnArbitraryScenarioAndBuildsBduiFromTheDatabase() throws Exception {
        var plugin = genericPlugin();
        var id = (String) plugin.get("id");
        register(plugin);

        mvc.perform(get("/api/plugins/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loaderImageName").value("example/load:{scenarioTag}"))
                .andExpect(jsonPath("$.scenarios[0].id").value("custom-scenario"));
        mvc.perform(get("/api/ui/experiment").param("pluginId", id).param("scenarioId", "custom-scenario"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.form.pluginId").value(id))
                .andExpect(jsonPath("$.form.sections[0].fields[0].name").value("settings.cacheSize"))
                .andExpect(jsonPath("$.form.sections[1].fields[0].name").value("params.rps"))
                .andExpect(jsonPath("$.form.sections[*].id", hasItem("loaderImageContext")))
                .andExpect(jsonPath("$.form.sections[*].id", hasItem("databaseImageContext")));

        // A direct metadata change is visible immediately; the catalog has no process-local cache.
        jdbc.update("""
                update dbehave.plugins
                   set definition = jsonb_set(definition, '{title}', to_jsonb(cast(? as text)))
                 where id = ?
                """, "Updated database title", id);
        mvc.perform(get("/api/plugins/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Updated database title"));
    }

    @Test
    void rejectsRegistrationWithoutACollectorAndDuplicatePluginIds() throws Exception {
        var plugin = genericPlugin();
        plugin.put("collectorId", "collector-that-does-not-exist");
        mvc.perform(post("/api/plugins").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(plugin)))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from dbehave.plugins where id = ?", Long.class,
                plugin.get("id"))).isZero();

        plugin.put("collectorId", "generic");
        register(plugin);
        mvc.perform(post("/api/plugins").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(plugin)))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from dbehave.plugins where id = ?", Long.class,
                plugin.get("id"))).isEqualTo(1L);
    }

    @Test
    void rejectsMissingCollectorIdsAndInvalidFieldDefinitionsBeforePersistence() throws Exception {
        for (String collector : List.of("missing", "null", "blank")) {
            var plugin = genericPlugin();
            switch (collector) {
                case "missing" -> plugin.remove("collectorId");
                case "null" -> plugin.put("collectorId", null);
                case "blank" -> plugin.put("collectorId", "");
                default -> throw new AssertionError(collector);
            }
            mvc.perform(post("/api/plugins").contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(plugin)))
                    .andExpect(status().isBadRequest());
            assertThat(jdbc.queryForObject("select count(*) from dbehave.plugins where id = ?", Long.class,
                    plugin.get("id"))).isZero();
        }
        for (String type : List.of("INTEGER", "UNKNOWN_TYPE")) {
            var plugin = genericPlugin();
            var field = new LinkedHashMap<>(integerField("rps", 100, 1, 10000));
            field.put("type", type);
            if (type.equals("INTEGER")) {
                field.put("defaultValue", "not-an-integer");
            }
            plugin.put("scenarios", List.of(Map.of(
                    "id", "custom-scenario", "title", "Custom scenario", "description", "Invalid field schema",
                    "loadGeneratorTag", "custom", "settings", List.of(), "params", List.of(field))));
            mvc.perform(post("/api/plugins").contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(plugin)))
                    .andExpect(status().isBadRequest());
            assertThat(jdbc.queryForObject("select count(*) from dbehave.plugins where id = ?", Long.class,
                    plugin.get("id"))).isZero();
        }
    }

    @Test
    void rejectsInvalidPostgresCollectorConfigurationBeforeRegistration() throws Exception {
        for (String invalidConfiguration : List.of("environment", "command", "setting")) {
            var plugin = genericPlugin();
            plugin.put("collectorId", "postgres");
            switch (invalidConfiguration) {
                case "environment" -> plugin.put("databaseImageContext", Map.of("environment", "not-an-object"));
                case "command" -> plugin.put("databaseImageContext", Map.of("command", List.of()));
                case "setting" -> plugin.put("scenarios", List.of(Map.of(
                        "id", "custom-scenario", "title", "Custom scenario", "description", "Invalid PostgreSQL setting",
                        "loadGeneratorTag", "custom",
                        "settings", List.of(integerField("invalid setting;", 64, 1, 1024)),
                        "params", List.of())));
                default -> throw new AssertionError(invalidConfiguration);
            }
            mvc.perform(post("/api/plugins").contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(plugin)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("scenarios[0].context")));
            assertThat(jdbc.queryForObject("select count(*) from dbehave.plugins where id = ?", Long.class,
                    plugin.get("id"))).isZero();
        }
    }

    @Test
    void translatesCollectorErrorsFromDirectDatabaseEditsWithoutCreatingTasks() throws Exception {
        var plugin = genericPlugin();
        plugin.put("collectorId", "postgres");
        register(plugin);
        jdbc.update("""
                update dbehave.plugins
                   set definition = jsonb_set(definition, '{databaseImageContext,environment}', to_jsonb(cast(? as text)))
                 where id = ?
                """, "not-an-object", plugin.get("id"));
        long countBefore = taskCount();

        mvc.perform(post("/api/experiments").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("pluginId", plugin.get("id"), "scenarioId", "custom-scenario"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("context")));
        assertThat(taskCount()).isEqualTo(countBefore);
    }

    @Test
    void updatesPluginMetadataAndSchemasOnlyAfterValidation() throws Exception {
        var plugin = genericPlugin();
        register(plugin);
        var id = (String) plugin.get("id");
        var originalRun = response(mvc.perform(post("/api/experiments").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("pluginId", id, "scenarioId", "custom-scenario"))))
                .andExpect(status().isCreated()).andReturn());
        var originalRunId = UUID.fromString((String) originalRun.get("id"));
        var originalContext = persistedContext(originalRunId);

        plugin.put("title", "Updated plugin");
        plugin.put("databaseImageName", "example/database:2");
        plugin.put("loaderImageName", "example/load-v2:{scenarioTag}");
        plugin.put("scenarios", List.of(Map.of(
                "id", "custom-scenario", "title", "Updated scenario", "description", "Updated schema",
                "loadGeneratorTag", "custom",
                "settings", List.of(integerField("cacheSize", 128, 1, 1024)),
                "params", List.of(integerField("rps", 300, 1, 10000)))));
        mvc.perform(put("/api/plugins/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(plugin)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/ui/experiment").param("pluginId", id).param("scenarioId", "custom-scenario"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.form.pluginTitle").value("Updated plugin"))
                .andExpect(jsonPath("$.form.sections[0].fields[0].defaultValue").value(128))
                .andExpect(jsonPath("$.form.sections[1].fields[0].defaultValue").value(300));
        var nextRun = response(mvc.perform(post("/api/experiments").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("pluginId", id, "scenarioId", "custom-scenario"))))
                .andExpect(status().isCreated()).andReturn());
        var nextContext = persistedContext(UUID.fromString((String) nextRun.get("id")));
        assertThat(nextContext).containsEntry("databaseImageName", "example/database:2")
                .containsEntry("loaderImageName", "example/load-v2:custom");
        assertThat(imageContext(nextContext, "loaderImageContext")).containsEntry("params", Map.of("rps", 300));
        assertThat(persistedContext(originalRunId)).isEqualTo(originalContext);

        var validDefinition = jdbc.queryForObject("select definition::text from dbehave.plugins where id = ?", String.class, id);
        plugin.put("id", "mismatching-id");
        mvc.perform(put("/api/plugins/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(plugin)))
                .andExpect(status().isBadRequest());
        plugin.put("id", id);
        plugin.put("collectorId", "missing-collector");
        mvc.perform(put("/api/plugins/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(plugin)))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select definition::text from dbehave.plugins where id = ?", String.class, id))
                .isEqualTo(validDefinition);

        plugin.put("id", "missing-" + UUID.randomUUID());
        plugin.put("collectorId", "generic");
        mvc.perform(put("/api/plugins/{id}", plugin.get("id")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(plugin)))
                .andExpect(status().isNotFound());
    }

    @Test
    void queuesAnImmutableExecutionSnapshotWithInputsAndContainerLimits() throws Exception {
        var plugin = genericPlugin();
        register(plugin);
        var request = new LinkedHashMap<String, Object>();
        request.put("pluginId", plugin.get("id"));
        request.put("scenarioId", "custom-scenario");
        request.put("settings", Map.of("cacheSize", 128));
        request.put("params", Map.of("rps", 250));
        request.put("loaderImageContext", Map.of("core_max", 0.5, "mem_max", 536_870_912));
        request.put("databaseImageContext", Map.of("core_max", 2, "mem_max", 2_147_483_648L));
        var response = response(mvc.perform(post("/api/experiments").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("QUEUED"))
                .andReturn());
        var runId = UUID.fromString((String) response.get("id"));
        assertThat(response.get("grafanaUrl"))
                .isEqualTo("https://grafana.example.test/d/custom?var-run_id=" + runId);

        var context = persistedContext(runId);
        assertThat(context).containsOnlyKeys("loaderImageName", "databaseImageName",
                "loaderImageContext", "databaseImageContext");
        assertThat(context.get("loaderImageName")).isEqualTo("example/load:custom");
        assertThat(context.get("databaseImageName")).isEqualTo("example/database:1");
        assertThat(imageContext(context, "loaderImageContext"))
                .containsEntry("params", Map.of("rps", 250))
                .containsEntry("scenarioId", "custom-scenario")
                .containsEntry("command", List.of("load"));
        assertThat(((Number) imageContext(context, "loaderImageContext").get("core_max")).doubleValue())
                .isEqualTo(0.5);
        assertThat(((Number) imageContext(context, "loaderImageContext").get("mem_max")).longValue())
                .isEqualTo(536_870_912L);
        assertThat(imageContext(context, "databaseImageContext"))
                .containsEntry("settings", Map.of("cacheSize", 128))
                .containsEntry("configuration", Map.of("feature", true));
        assertThat(((Number) imageContext(context, "databaseImageContext").get("core_max")).doubleValue())
                .isEqualTo(2);
        assertThat(((Number) imageContext(context, "databaseImageContext").get("mem_max")).longValue())
                .isEqualTo(2_147_483_648L);

        jdbc.update("""
                update dbehave.plugins
                   set definition = jsonb_set(definition, '{databaseImageName}', to_jsonb(cast(? as text)))
                 where id = ?
                """, "example/database:changed", plugin.get("id"));
        assertThat(persistedContext(runId)).isEqualTo(context);
    }

    @Test
    void appliesSchemaAndResourceDefaultsWithoutEnqueueingDuringValidation() throws Exception {
        var plugin = genericPlugin();
        register(plugin);
        var body = json.writeValueAsString(Map.of("pluginId", plugin.get("id"), "scenarioId", "custom-scenario"));
        long countBefore = taskCount();
        mvc.perform(post("/api/experiments/validate").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"));
        assertThat(taskCount()).isEqualTo(countBefore);

        var result = response(mvc.perform(post("/api/experiments").contentType(MediaType.APPLICATION_JSON)
                        .content(body)).andExpect(status().isCreated()).andReturn());
        var context = persistedContext(UUID.fromString((String) result.get("id")));
        assertThat(imageContext(context, "databaseImageContext"))
                .containsEntry("settings", Map.of("cacheSize", 64));
        assertThat(imageContext(context, "loaderImageContext"))
                .containsEntry("params", Map.of("rps", 100));
        assertThat(((Number) imageContext(context, "loaderImageContext").get("core_max")).doubleValue())
                .isEqualTo(0.25);
        assertThat(((Number) imageContext(context, "databaseImageContext").get("mem_max")).longValue())
                .isEqualTo(1_073_741_824L);
    }

    @Test
    void queuesPostgresUsingTheConcreteCollectorAndThePersistedScenarioSchema() throws Exception {
        var result = response(mvc.perform(post("/api/experiments").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "pluginId": "postgres",
                                  "scenarioId": "mixed",
                                  "settings": {"shared_buffers": 128},
                                  "params": {"rows": 100000}
                                }
                                """))
                .andExpect(status().isCreated()).andReturn());
        var context = persistedContext(UUID.fromString((String) result.get("id")));
        assertThat(context.get("databaseImageName")).isEqualTo("postgres:18");
        assertThat(context.get("loaderImageName")).isEqualTo("dbehave/postgres-load:mixed");
        assertThat(imageContext(imageContext(context, "databaseImageContext"), "environment"))
                .containsEntry("DBEHAVE_SHARED_BUFFERS", "128MB")
                .containsEntry("DBEHAVE_MAX_CONNECTIONS", "100");
        assertThat(imageContext(imageContext(context, "loaderImageContext"), "environment"))
                .containsEntry("ROWS", "100000")
                .containsEntry("CLIENTS", "32")
                .containsEntry("SCENARIO", "mixed");
    }

    @Test
    void pollsWorkerOwnedStateHeartbeatErrorsAndUpdatedTimestamp() throws Exception {
        var plugin = genericPlugin();
        register(plugin);
        var created = response(mvc.perform(post("/api/experiments").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "pluginId", plugin.get("id"), "scenarioId", "custom-scenario"))))
                .andExpect(status().isCreated()).andReturn());
        var runId = UUID.fromString((String) created.get("id"));
        var queuedAt = jdbc.queryForObject("select updated_at from worker.tasks where id = ?", Timestamp.class, runId);
        jdbc.update("""
                update worker.tasks
                   set state = 'RUNNING', worker_id = 'test-worker', heartbeat = current_timestamp
                 where id = ?
                """, runId);
        mvc.perform(get("/api/experiments/{id}", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(runId.toString()))
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andExpect(jsonPath("$.workerId").value("test-worker"))
                .andExpect(jsonPath("$.heartbeat").isNotEmpty());
        assertThat(jdbc.queryForObject("select updated_at from worker.tasks where id = ?", Timestamp.class, runId))
                .isAfter(queuedAt);

        jdbc.update("update worker.tasks set state = 'FAILED', error = 'container exited with code 1' where id = ?", runId);
        mvc.perform(get("/api/experiments/{id}", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("FAILED"))
                .andExpect(jsonPath("$.error").value("container exited with code 1"));
    }

    @Test
    void rejectsInvalidInputsAndUnknownEntitiesWithoutQueueSideEffects() throws Exception {
        var plugin = genericPlugin();
        register(plugin);
        long countBefore = taskCount();
        for (Map<String, Object> invalid : List.of(
                Map.<String, Object>of("params", Map.of("rps", 0)),
                Map.<String, Object>of("settings", Map.of("cacheSize", "128")),
                Map.<String, Object>of("params", Map.of("undeclared", 1)),
                Map.<String, Object>of("loaderImageContext", Map.of("core_max", 0)),
                Map.<String, Object>of("databaseImageContext", Map.of("mem_max", -1)),
                Map.<String, Object>of("databaseImageContext", Map.of("mem_max", 1.5)),
                Map.<String, Object>of("loaderImageContext", Map.of("image", "untrusted:image")))) {
            var request = new LinkedHashMap<String, Object>(invalid);
            request.put("pluginId", plugin.get("id"));
            request.put("scenarioId", "custom-scenario");
            mvc.perform(post("/api/experiments").contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/experiments").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("pluginId", "missing-plugin", "scenarioId", "custom-scenario"))))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/experiments").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("pluginId", plugin.get("id"), "scenarioId", "missing-scenario"))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/experiments/{id}", UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get("/api/plugins/missing-plugin")).andExpect(status().isNotFound());
        assertThat(taskCount()).isEqualTo(countBefore);
    }

    private void register(Map<String, Object> plugin) throws Exception {
        mvc.perform(post("/api/plugins").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(plugin)))
                .andExpect(status().isCreated());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> response(MvcResult result) {
        return json.readValue(result.getResponse().getContentAsByteArray(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> persistedContext(UUID runId) {
        return json.readValue(jdbc.queryForObject(
                "select context::text from worker.tasks where id = ?", String.class, runId), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> imageContext(Map<String, Object> context, String image) {
        return (Map<String, Object>) context.get(image);
    }

    private long taskCount() {
        return jdbc.queryForObject("select count(*) from worker.tasks", Long.class);
    }

    private static Map<String, Object> genericPlugin() {
        var definition = new LinkedHashMap<String, Object>();
        definition.put("id", "integration-" + UUID.randomUUID());
        definition.put("title", "A plugin defined entirely in the database");
        definition.put("collectorId", "generic");
        definition.put("loaderImageName", "example/load:{scenarioTag}");
        definition.put("databaseImageName", "example/database:1");
        definition.put("grafanaUrlTemplate", "https://grafana.example.test/d/custom?var-run_id={runId}");
        definition.put("loaderImageContext", Map.of("command", List.of("load"), "core_max", 0.25, "mem_max", 268_435_456));
        definition.put("databaseImageContext", Map.of("configuration", Map.of("feature", true),
                "core_max", 1, "mem_max", 1_073_741_824));
        definition.put("scenarios", List.of(Map.of(
                "id", "custom-scenario", "title", "Custom scenario", "description", "Database-defined schema",
                "loadGeneratorTag", "custom",
                "settings", List.of(integerField("cacheSize", 64, 1, 1024)),
                "params", List.of(integerField("rps", 100, 1, 10000)))));
        return definition;
    }

    private static Map<String, Object> integerField(String key, int defaultValue, int min, int max) {
        return Map.of("key", key, "title", key, "description", key, "type", "INTEGER",
                "defaultValue", defaultValue, "min", min, "max", max, "options", List.of());
    }
}
