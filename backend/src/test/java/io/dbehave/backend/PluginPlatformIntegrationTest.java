package io.dbehave.backend;

import io.dbehave.backend.experiment.ExperimentRequest;
import io.dbehave.backend.experiment.ExperimentService;
import io.dbehave.backend.task.ExecutionContext;
import io.dbehave.backend.task.TaskRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
    @Autowired
    private ExperimentService experiments;
    @Autowired
    private TaskRepository tasks;

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
                .andExpect(jsonPath("$[*].id", hasItem("postgres")))
                .andExpect(jsonPath("$[*].id", hasItem("mongo")))
                .andExpect(jsonPath("$[*].id", hasItem("clickhouse")));
        mvc.perform(get("/api/plugins/postgres"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collectorId").value("postgres"))
                .andExpect(jsonPath("$.loaderImageName").value("dbehave/postgres-load:0.1.0"))
                .andExpect(jsonPath("$.grafanaUrlTemplate").value("http://localhost:3001/d/dbehave-postgres?var-run_id={runId}"))
                .andExpect(jsonPath("$.scenarios[*].id", hasItem("mixed")));
        mvc.perform(get("/api/context-collectors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasItem("generic")))
                .andExpect(jsonPath("$", hasItem("postgres")))
                .andExpect(jsonPath("$", hasItem("mongo")))
                .andExpect(jsonPath("$", hasItem("clickhouse")));

        assertThat(jdbc.queryForObject(
                "select jsonb_typeof(definition) from dbehave.plugins where id = 'postgres'", String.class))
                .isEqualTo("object");
        assertThat(jdbc.queryForObject("select to_regclass('worker.tasks')::text", String.class))
                .isEqualTo("worker.tasks");
    }

    @Test
    @Transactional
    @SuppressWarnings("unchecked")
    void migratesLegacyScenarioMetadataAndPreservesCustomizedImageConfiguration() throws Exception {
        var plugin = genericPlugin();
        plugin.put("title", "Customized legacy plugin");
        plugin.put("loaderImageName", "example/legacy-load:{scenarioTag}");
        var scenarios = (List<Map<String, Object>>) plugin.get("scenarios");
        var scenario = new LinkedHashMap<>(scenarios.getFirst());
        scenario.put("loadGeneratorTag", "legacy-tag");
        plugin.put("scenarios", List.of(scenario));
        jdbc.update("INSERT INTO dbehave.plugins (id, definition) VALUES (?, CAST(? AS jsonb))",
                plugin.get("id"), json.writeValueAsString(plugin));

        jdbc.execute((ConnectionCallback<Void>) connection -> {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V3__align_catalog_with_shared_loader_image.sql"));
            return null;
        });

        mvc.perform(get("/api/plugins/{id}", plugin.get("id")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Customized legacy plugin"))
                .andExpect(jsonPath("$.loaderImageName").value("example/legacy-load:{scenarioTag}"))
                .andExpect(jsonPath("$.scenarios[0].id").value("custom-scenario"))
                .andExpect(jsonPath("$.scenarios[0].loadGeneratorTag").doesNotExist());
        mvc.perform(post("/api/experiments").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("pluginId", plugin.get("id"), "scenarioId", "custom-scenario"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("loaderImageName")));
    }

    @Test
    void registersAnArbitraryScenarioAndBuildsBduiFromTheDatabase() throws Exception {
        var plugin = genericPlugin();
        var id = (String) plugin.get("id");
        register(plugin);

        mvc.perform(get("/api/plugins/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loaderImageName").value("example/load:custom"))
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
                    "settings", List.of(), "params", List.of(field))));
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
            var plugin = postgresPlugin();
            switch (invalidConfiguration) {
                case "environment" -> plugin.put("databaseImageContext", Map.of("environment", "not-an-object"));
                case "command" -> plugin.put("databaseImageContext", Map.of("command", List.of()));
                case "setting" -> plugin.put("scenarios", List.of(Map.of(
                        "id", "custom-scenario", "title", "Custom scenario", "description", "Invalid PostgreSQL setting",
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
        var plugin = postgresPlugin();
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
        var originalRunId = Long.valueOf((String) originalRun.get("id"));
        var originalContext = persistedContext(originalRunId);

        plugin.put("title", "Updated plugin");
        plugin.put("databaseImageName", "example/database:2");
        plugin.put("loaderImageName", "example/load-v2:custom");
        plugin.put("scenarios", List.of(Map.of(
                "id", "custom-scenario", "title", "Updated scenario", "description", "Updated schema",
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
        var nextContext = persistedContext(Long.valueOf((String) nextRun.get("id")));
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
        var runId = Long.valueOf((String) response.get("id"));
        assertThat(response.get("grafanaUrl"))
                .isEqualTo("https://grafana.example.test/d/custom?var-run_id=" + runId + "&kiosk=1");

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
        var context = persistedContext(Long.valueOf((String) result.get("id")));
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
                                  "params": {"datasetRows": 100000, "readWeight": 80}
                                }
                                """))
                .andExpect(status().isCreated()).andReturn());
        var context = persistedContext(Long.valueOf((String) result.get("id")));
        assertThat(context.get("databaseImageName")).isEqualTo("postgres:18");
        assertThat(context.get("loaderImageName")).isEqualTo("dbehave/postgres-load:0.1.0");
        assertThat(imageContext(imageContext(context, "databaseImageContext"), "environment"))
                .containsEntry("DBEHAVE_SHARED_BUFFERS", "128MB")
                .containsEntry("DBEHAVE_MAX_CONNECTIONS", "100")
                .containsEntry("DBEHAVE_WORK_MEM", "4096kB")
                .containsEntry("DBEHAVE_AUTOVACUUM", "on");
        assertThat(imageContext(imageContext(context, "loaderImageContext"), "environment"))
                .containsEntry("DATASET_ROWS", "100000")
                .containsEntry("READ_WEIGHT", "80")
                .containsEntry("CLIENTS", "16")
                .containsEntry("DB_PASSWORD", "dbehave")
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
        var runId = Long.valueOf((String) created.get("id"));
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
    @SuppressWarnings("unchecked")
    void retainsRunDisplayMetadataAndGrafanaLinkAfterCatalogChanges() throws Exception {
        var plugin = genericPlugin();
        register(plugin);
        var created = response(mvc.perform(post("/api/experiments").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("pluginId", plugin.get("id"), "scenarioId", "custom-scenario"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pluginId").value(plugin.get("id")))
                .andExpect(jsonPath("$.pluginTitle").value(plugin.get("title")))
                .andExpect(jsonPath("$.scenarioId").value("custom-scenario"))
                .andExpect(jsonPath("$.scenarioTitle").value("Custom scenario"))
                .andExpect(jsonPath("$.context").doesNotExist())
                .andReturn());
        var id = Long.valueOf((String) created.get("id"));

        plugin.put("title", "Renamed plugin");
        plugin.put("grafanaUrlTemplate", "https://other.example.test/?run={runId}");
        var scenario = new LinkedHashMap<>(((List<Map<String, Object>>) plugin.get("scenarios")).getFirst());
        scenario.put("title", "Renamed scenario");
        plugin.put("scenarios", List.of(scenario));
        mvc.perform(put("/api/plugins/{id}", plugin.get("id")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(plugin)))
                .andExpect(status().isOk());
        jdbc.update("DELETE FROM dbehave.plugins WHERE id = ?", plugin.get("id"));

        mvc.perform(get("/api/experiments/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pluginTitle").value(created.get("pluginTitle")))
                .andExpect(jsonPath("$.scenarioTitle").value("Custom scenario"))
                .andExpect(jsonPath("$.grafanaUrl").value(created.get("grafanaUrl")))
                .andExpect(jsonPath("$.context").doesNotExist());
        mvc.perform(get("/api/experiments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')].grafanaUrl".formatted(id), hasItem(created.get("grafanaUrl"))))
                .andExpect(jsonPath("$[*].context").doesNotExist());
    }

    @Test
    void readsLegacyTasksWithNullDisplayMetadataAndLiveWorkerStatus() throws Exception {
        var id = tasks.insert(new ExecutionContext("legacy/load:1", "legacy/database:1",
                Map.of(), Map.of("environment", Map.of("PASSWORD", "secret")))).id();
        jdbc.update("""
                UPDATE worker.tasks SET state = 'FAILED', worker_id = 'legacy-worker',
                    heartbeat = CURRENT_TIMESTAMP, error = 'Legacy failure' WHERE id = ?
                """, id);

        mvc.perform(get("/api/experiments/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("FAILED"))
                .andExpect(jsonPath("$.workerId").value("legacy-worker"))
                .andExpect(jsonPath("$.heartbeat").isNotEmpty())
                .andExpect(jsonPath("$.error").value("Legacy failure"))
                .andExpect(jsonPath("$.grafanaUrl").value(nullValue()))
                .andExpect(jsonPath("$.pluginId").value(nullValue()))
                .andExpect(jsonPath("$.pluginTitle").value(nullValue()))
                .andExpect(jsonPath("$.scenarioId").value(nullValue()))
                .andExpect(jsonPath("$.scenarioTitle").value(nullValue()))
                .andExpect(jsonPath("$.context").doesNotExist());
        mvc.perform(get("/api/experiments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')].state".formatted(id), hasItem("FAILED")))
                .andExpect(jsonPath("$[*].context").doesNotExist());
    }

    @Test
    void rollsBackWorkerTaskWhenDisplayMetadataCannotBeSaved() throws Exception {
        var plugin = genericPlugin();
        register(plugin);
        long before = taskCount();
        String constraint = "reject_experiment_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("ALTER TABLE dbehave.experiments ADD CONSTRAINT " + constraint
                + " CHECK (plugin_id <> '" + plugin.get("id") + "')");
        try {
            assertThatThrownBy(() -> experiments.start(new ExperimentRequest(
                    (String) plugin.get("id"), "custom-scenario", null, null)))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(taskCount()).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM dbehave.experiments WHERE plugin_id = ?",
                    Long.class, plugin.get("id"))).isZero();
        } finally {
            jdbc.execute("ALTER TABLE dbehave.experiments DROP CONSTRAINT " + constraint);
        }
    }

    @Test
    @Transactional
    void listsOnlyTheLatestHundredTasksInDeterministicOrder() throws Exception {
        var created = Timestamp.from(Instant.parse("2100-01-01T00:00:00Z"));
        var ids = new java.util.ArrayList<Long>();
        for (int index = 1; index <= 105; index++) {
            var id = tasks.insert(new ExecutionContext("legacy/load:1", "legacy/database:1", Map.of(), Map.of())).id();
            ids.add(id);
            jdbc.update("UPDATE worker.tasks SET created_at = ? WHERE id = ?", created, id);
        }
        mvc.perform(get("/api/experiments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(100))
                .andExpect(jsonPath("$[0].id").value(ids.get(104).toString()))
                .andExpect(jsonPath("$[99].id").value(ids.get(5).toString()));
    }

    @Test
    @Transactional
    void deletesOnlyDisplayMetadataWhenItsWorkerTaskIsDeleted() throws Exception {
        var plugin = genericPlugin();
        register(plugin);
        var created = experiments.start(new ExperimentRequest((String) plugin.get("id"), "custom-scenario", null, null));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dbehave.experiments WHERE task_id = ?",
                Long.class, created.id())).isEqualTo(1L);

        jdbc.update("DELETE FROM worker.tasks WHERE id = ?", created.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dbehave.experiments WHERE task_id = ?",
                Long.class, created.id())).isZero();
        mvc.perform(get("/api/experiments/{id}", created.id())).andExpect(status().isNotFound());
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
        mvc.perform(get("/api/experiments/{id}", Long.MAX_VALUE)).andExpect(status().isNotFound());
        mvc.perform(get("/api/plugins/missing-plugin")).andExpect(status().isNotFound());
        assertThat(taskCount()).isEqualTo(countBefore);
    }

    @Test
    void rejectsJsonbIncompatibleCpuLimitsDuringBothValidationAndLaunch() throws Exception {
        var plugin = genericPlugin();
        register(plugin);
        for (String value : List.of("1e1000000", "1e-1000000", "1e1000")) {
            assertRejectedBeforeQueue(Map.of("pluginId", plugin.get("id"), "scenarioId", "custom-scenario",
                    "loaderImageContext", Map.of("core_max", new BigDecimal(value))));
        }
    }

    @Test
    void rejectsJsonbIncompatibleGenericNumbersAndStringsBeforeQueueing() throws Exception {
        var numbers = genericPluginWithParameter("NUMBER", 1);
        register(numbers);
        for (String value : List.of("1e1000000", "1e-1000000", "1e1000")) {
            assertRejectedBeforeQueue(Map.of("pluginId", numbers.get("id"), "scenarioId", "custom-scenario",
                    "params", Map.of("value", new BigDecimal(value))));
        }
        var strings = genericPluginWithParameter("STRING", "safe");
        register(strings);
        assertRejectedBeforeQueue(Map.of("pluginId", strings.get("id"), "scenarioId", "custom-scenario",
                "params", Map.of("value", "before\0after")));

        var postgres = genericPluginWithParameter("NUMBER", 1);
        postgres.put("collectorId", "postgres");
        register(postgres);
        assertRejectedBeforeQueue(Map.of("pluginId", postgres.get("id"), "scenarioId", "custom-scenario",
                "params", Map.of("value", new BigDecimal("1e1000000"))));
    }

    @Test
    void rejectsInvalidCatalogTextNumbersAndPathSegmentIdsWithoutSavingRows() throws Exception {
        for (String invalid : List.of(".", "..", "title", "key", "number")) {
            var plugin = genericPlugin();
            switch (invalid) {
                case ".", ".." -> plugin.put("id", invalid);
                case "title" -> plugin.put("title", "invalid\0title");
                case "key" -> plugin.put("databaseImageContext", Map.of("invalid\0key", "value"));
                case "number" -> plugin.put("databaseImageContext", Map.of("number", new BigDecimal("1e1000")));
                default -> throw new AssertionError(invalid);
            }
            mvc.perform(post("/api/plugins").contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(plugin)))
                    .andExpect(status().isBadRequest());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM dbehave.plugins WHERE id = ?", Long.class,
                    plugin.get("id"))).isZero();
        }
    }

    @Test
    void rejectsInvalidCatalogUpdateWithoutReplacingTheSavedDefinition() throws Exception {
        var plugin = genericPlugin();
        register(plugin);
        String saved = jdbc.queryForObject("SELECT definition::text FROM dbehave.plugins WHERE id = ?",
                String.class, plugin.get("id"));
        plugin.put("title", "invalid\0title");
        mvc.perform(put("/api/plugins/{id}", plugin.get("id")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(plugin)))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT definition::text FROM dbehave.plugins WHERE id = ?",
                String.class, plugin.get("id"))).isEqualTo(saved);
    }

    @Test
    void rejectsMalformedUnicodeKeysWithASerializableValidationError() throws Exception {
        var plugin = genericPlugin();
        plugin.put("databaseImageContext", Map.of("invalid_key_marker", "\0"));
        String body = json.writeValueAsString(plugin).replace("invalid_key_marker", "\\uD800");
        mvc.perform(post("/api/plugins").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dbehave.plugins WHERE id = ?", Long.class,
                plugin.get("id"))).isZero();
    }

    @Test
    void rejectsZeroOperationWeightsAndMalformedLoaderCommands() throws Exception {
        assertRejectedBeforeQueue(Map.of("pluginId", "postgres", "scenarioId", "mixed",
                "params", Map.of("readWeight", 0, "updateWeight", 0, "insertWeight", 0)));
        assertRejectedBeforeQueue(Map.of("pluginId", "postgres", "scenarioId", "read-heavy",
                "params", Map.of("pointWeight", 0, "rangeWeight", 0, "reportWeight", 0)));
        for (Object command : List.of("not-an-array", List.of(123))) {
            var plugin = postgresPlugin();
            plugin.put("loaderImageContext", Map.of("command", command));
            mvc.perform(post("/api/plugins").contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(plugin)))
                    .andExpect(status().isBadRequest());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM dbehave.plugins WHERE id = ?", Long.class,
                    plugin.get("id"))).isZero();
        }
    }

    @Test
    @Transactional
    void returnsTheNormalizedConfigurationAfterTheCatalogIsRemoved() throws Exception {
        var plugin = genericPlugin();
        register(plugin);
        var created = startRun(Map.of(
                "pluginId", plugin.get("id"), "scenarioId", "custom-scenario",
                "params", Map.of("rps", 250),
                "loaderImageContext", Map.of("core_max", 0.75)));
        String id = (String) created.get("id");
        jdbc.update("DELETE FROM dbehave.plugins WHERE id = ?", plugin.get("id"));

        mvc.perform(get("/api/experiments/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configuration.pluginId").value(plugin.get("id")))
                .andExpect(jsonPath("$.configuration.scenarioId").value("custom-scenario"))
                .andExpect(jsonPath("$.configuration.settings.cacheSize").value(64))
                .andExpect(jsonPath("$.configuration.params.rps").value(250))
                .andExpect(jsonPath("$.configuration.loaderImageContext.core_max").value(0.75))
                .andExpect(jsonPath("$.configuration.loaderImageContext.mem_max").value(268_435_456))
                .andExpect(jsonPath("$.configuration.databaseImageContext.core_max").value(1))
                .andExpect(jsonPath("$.configuration.databaseImageContext.mem_max").value(1_073_741_824))
                .andExpect(jsonPath("$.grafanaUrlTemplate").value(plugin.get("grafanaUrlTemplate")));
        mvc.perform(get("/api/experiments"))
                .andExpect(jsonPath("$[?(@.id == '%s')].configuration.params.rps".formatted(id), hasItem(250)));
        assertThat(created.get("configuration")).isEqualTo(response(mvc.perform(
                get("/api/experiments/{id}", id)).andReturn()).get("configuration"));
    }

    @Test
    @Transactional
    void removesOneRunFromHistoryWhilePreservingWorkerExecutionAndTimestamps() throws Exception {
        var created = startRun(Map.of("pluginId", "postgres", "scenarioId", "mixed"));
        Long id = Long.valueOf((String) created.get("id"));
        var before = jdbc.queryForMap("SELECT * FROM worker.tasks WHERE id = ?", id);

        mvc.perform(delete("/api/experiments/{id}", id)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/experiments/{id}", id)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/experiments/{id}", Long.MAX_VALUE)).andExpect(status().isNotFound());
        mvc.perform(get("/api/experiments/{id}", id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/experiments"))
                .andExpect(jsonPath("$[*].id", not(hasItem(id.toString()))));
        assertThat(jdbc.queryForMap("SELECT * FROM worker.tasks WHERE id = ?", id)).isEqualTo(before);

        assertThat(jdbc.update("""
                UPDATE worker.tasks SET state = 'RUNNING', worker_id = 'integration-worker',
                    heartbeat = clock_timestamp() WHERE id = ? AND state = 'QUEUED'
                """, id)).isEqualTo(1);
        assertThat(jdbc.update("""
                UPDATE worker.tasks SET state = 'SUCCEEDED', heartbeat = clock_timestamp()
                WHERE id = ? AND state = 'RUNNING' AND worker_id = 'integration-worker'
                """, id)).isEqualTo(1);
        mvc.perform(get("/api/experiments/{id}", id)).andExpect(status().isNotFound());
    }

    @Test
    @Transactional
    void clearsTheEntireHistoryIncludingLegacyTasksBeyondTheFirstHundred() throws Exception {
        jdbc.update("""
                INSERT INTO worker.tasks (state, context)
                SELECT 'SUCCEEDED', CAST(? AS jsonb)
                FROM generate_series(1, 105)
                """, json.writeValueAsString(Map.of(
                "loaderImageName", "example/load:1", "databaseImageName", "example/database:1",
                "loaderImageContext", Map.of(), "databaseImageContext", Map.of())));
        long tasksBefore = taskCount();
        mvc.perform(get("/api/experiments")).andExpect(jsonPath("$", hasSize(100)));

        mvc.perform(delete("/api/experiments")).andExpect(status().isNoContent());
        mvc.perform(delete("/api/experiments")).andExpect(status().isNoContent());
        mvc.perform(get("/api/experiments")).andExpect(jsonPath("$", hasSize(0)));
        assertThat(taskCount()).isEqualTo(tasksBefore);

        var created = startRun(Map.of("pluginId", "postgres", "scenarioId", "mixed"));
        mvc.perform(get("/api/experiments"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(created.get("id")));
    }

    @Test
    @Transactional
    void comparesRunsUsingTheSavedTemplateAndPreservesIdOrder() throws Exception {
        var plugin = genericPlugin();
        String template = "https://grafana.example.test/d/custom?orgId=1&var-run_id={runId}&var-label=a%2Bb#panel";
        plugin.put("grafanaUrlTemplate", template);
        register(plugin);
        var first = startRun(Map.of("pluginId", plugin.get("id"), "scenarioId", "custom-scenario"));
        var second = startRun(Map.of("pluginId", plugin.get("id"), "scenarioId", "custom-scenario"));
        jdbc.update("DELETE FROM dbehave.plugins WHERE id = ?", plugin.get("id"));

        mvc.perform(post("/api/experiments/compare").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("runIds", List.of(second.get("id"), first.get("id"), second.get("id"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grafanaUrl").value(
                        "https://grafana.example.test/d/custom?orgId=1&var-run_id=" + second.get("id")
                                + "&var-run_id=" + first.get("id") + "&var-label=a%2Bb&kiosk=1#panel"));
    }

    @Test
    @Transactional
    void comparesDifferentPluginsWhenTheirSavedTemplateAndScenarioAreTheSame() throws Exception {
        var firstPlugin = genericPlugin();
        var secondPlugin = genericPlugin();
        register(firstPlugin);
        register(secondPlugin);
        var first = startRun(Map.of("pluginId", firstPlugin.get("id"), "scenarioId", "custom-scenario"));
        var second = startRun(Map.of("pluginId", secondPlugin.get("id"), "scenarioId", "custom-scenario"));

        mvc.perform(post("/api/experiments/compare").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("runIds", List.of(first.get("id"), second.get("id"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grafanaUrl").value(
                        "https://grafana.example.test/d/custom?var-run_id=" + first.get("id")
                                + "&var-run_id=" + second.get("id") + "&kiosk=1"));
    }

    @Test
    @Transactional
    void exposesClickHouseFormsAndQueuesEveryScenarioWithSavedParameters() throws Exception {
        mvc.perform(get("/api/plugins/clickhouse"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collectorId").value("clickhouse"))
                .andExpect(jsonPath("$.scenarios", hasSize(4)));

        var switches = Map.of("small-inserts", "buffered", "sorting-key", "sortedKey",
                "bloom-filter", "bloomFilter", "replacing-merge-tree", "finalRead");
        for (var entry : switches.entrySet()) {
            String scenarioId = entry.getKey();
            String switchKey = entry.getValue();
            mvc.perform(get("/api/ui/experiment").param("pluginId", "clickhouse").param("scenarioId", scenarioId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.form.sections[0].fields[*].name", hasItem("settings.maxThreads")))
                    .andExpect(jsonPath("$.form.sections[1].fields[*].name", hasItem("params." + switchKey)));

            var run = startRun(Map.of("pluginId", "clickhouse", "scenarioId", scenarioId,
                    "params", Map.of(switchKey, false), "settings", Map.of("maxThreads", 1)));
            Long id = Long.valueOf((String) run.get("id"));
            var context = persistedContext(id);
            assertThat(context).containsEntry("databaseImageName", "clickhouse/clickhouse-server:25.8.4.13")
                    .containsEntry("loaderImageName", "dbehave/clickhouse-load:0.1.0");
            assertThat(imageContext(context, "databaseImageContext"))
                    .containsEntry("core_max", new BigDecimal("1.5")).containsEntry("mem_max", 1_610_612_736);
            assertThat(imageContext(imageContext(context, "loaderImageContext"), "environment"))
                    .containsEntry("SCENARIO", scenarioId)
                    .containsEntry("MAX_THREADS", "1")
                    .containsEntry("MAX_MEMORY_USAGE_MB", "256")
                    .containsEntry(io.dbehave.plugin.clickhouse.ClickHouseContextCollector.environmentKey(switchKey), "false");
            mvc.perform(get("/api/experiments/{id}", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.configuration.settings.maxThreads").value(1))
                    .andExpect(jsonPath("$.configuration.params." + switchKey).value(false))
                    .andExpect(jsonPath("$.configuration.params.clients").value(4))
                    .andExpect(jsonPath("$.grafanaUrl").value(
                            "http://localhost:3001/d/dbehave-clickhouse?var-run_id=" + id + "&kiosk=1"));
        }
    }

    @Test
    @Transactional
    void comparesClickHouseRunsAndRejectsInvalidInputBeforeQueueing() throws Exception {
        var first = startRun(Map.of("pluginId", "clickhouse", "scenarioId", "small-inserts",
                "params", Map.of("buffered", false)));
        var second = startRun(Map.of("pluginId", "clickhouse", "scenarioId", "small-inserts",
                "params", Map.of("buffered", true)));
        mvc.perform(post("/api/experiments/compare").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("runIds", List.of(first.get("id"), second.get("id"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grafanaUrl").value("http://localhost:3001/d/dbehave-clickhouse?var-run_id="
                        + first.get("id") + "&var-run_id=" + second.get("id") + "&kiosk=1"));

        var otherScenario = startRun(Map.of("pluginId", "clickhouse", "scenarioId", "bloom-filter"));
        assertComparisonRejected(first.get("id"), otherScenario.get("id"));
        assertRejectedBeforeQueue(Map.of("pluginId", "clickhouse", "scenarioId", "small-inserts",
                "params", Map.of("buffered", "true")));
        assertRejectedBeforeQueue(Map.of("pluginId", "clickhouse", "scenarioId", "sorting-key",
                "params", Map.of("datasetRows", 9999)));
        assertRejectedBeforeQueue(Map.of("pluginId", "clickhouse", "scenarioId", "bloom-filter",
                "settings", Map.of("maxThreads", 0)));
        assertRejectedBeforeQueue(Map.of("pluginId", "clickhouse", "scenarioId", "replacing-merge-tree",
                "params", Map.of("versions", 11)));
    }

    @Test
    @Transactional
    void queuesMongoAndComparesRunsOnTheMongoDashboard() throws Exception {
        var mongo = startRun(Map.of("pluginId", "mongo", "scenarioId", "read-heavy",
                "settings", Map.of("cacheSizeMB", 512, "allowDiskUse", false),
                "params", Map.of("datasetRows", 100_000, "payloadBytes", 2048)));
        String dashboard = "http://localhost:3001/d/dbheave-mongo?var-run_id=";
        assertThat(mongo.get("grafanaUrl")).isEqualTo(dashboard + mongo.get("id") + "&kiosk=1");
        var context = persistedContext(Long.valueOf((String) mongo.get("id")));
        assertThat(context).containsEntry("databaseImageName", "mongo:8.0")
                .containsEntry("loaderImageName", "dbehave/mongo-load:0.1.0");
        assertThat(imageContext(context, "databaseImageContext").get("command"))
                .isEqualTo(List.of("mongod", "--bind_ip_all", "--wiredTigerCacheSizeGB=0.5"));
        assertThat(imageContext(imageContext(context, "loaderImageContext"), "environment"))
                .containsEntry("SCENARIO", "read-heavy")
                .containsEntry("MONGO_CACHE_SIZE_MB", "512")
                .containsEntry("MONGO_ALLOW_DISK_USE", "false")
                .containsEntry("PAYLOAD_BYTES", "2048");

        var secondMongo = startRun(Map.of("pluginId", "mongo", "scenarioId", "read-heavy"));
        mvc.perform(post("/api/experiments/compare").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("runIds", List.of(mongo.get("id"), secondMongo.get("id"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grafanaUrl").value(dashboard + mongo.get("id")
                        + "&var-run_id=" + secondMongo.get("id") + "&kiosk=1"));

        var postgres = startRun(Map.of("pluginId", "postgres", "scenarioId", "read-heavy"));
        assertComparisonRejected(mongo.get("id"), postgres.get("id"));
    }

    @Test
    @Transactional
    void queuesMongoCompressionSettingsAndRejectsInvalidValues() throws Exception {
        var journal = startRun(Map.of("pluginId", "mongo", "scenarioId", "write-heavy",
                "settings", Map.of("journalCompressor", "zstd")));
        var journalContext = persistedContext(Long.valueOf((String) journal.get("id")));
        assertThat(imageContext(journalContext, "databaseImageContext").get("command")).asList()
                .contains("--wiredTigerJournalCompressor=zstd", "--wiredTigerCollectionBlockCompressor=snappy");

        var prefix = startRun(Map.of("pluginId", "mongo", "scenarioId", "array-index",
                "settings", Map.of("prefixCompression", false)));
        var prefixContext = persistedContext(Long.valueOf((String) prefix.get("id")));
        assertThat(imageContext(prefixContext, "databaseImageContext").get("command")).asList()
                .contains("--wiredTigerIndexPrefixCompression=false");

        assertRejectedBeforeQueue(Map.of("pluginId", "mongo", "scenarioId", "write-heavy",
                "settings", Map.of("journalCompressor", "invalid")));
        assertRejectedBeforeQueue(Map.of("pluginId", "mongo", "scenarioId", "array-index",
                "settings", Map.of("prefixCompression", "false")));
    }

    @Test
    @Transactional
    void rejectsComparisonOfDifferentScenariosAndSavedTemplates() throws Exception {
        var first = startRun(Map.of("pluginId", "postgres", "scenarioId", "mixed"));
        var differentScenario = startRun(Map.of("pluginId", "postgres", "scenarioId", "read-heavy"));
        assertComparisonRejected(first.get("id"), differentScenario.get("id"));

        jdbc.update("""
                UPDATE dbehave.plugins SET definition = jsonb_set(definition, '{grafanaUrlTemplate}', to_jsonb(CAST(? AS text)))
                WHERE id = 'postgres'
                """, "https://other.example.test/d/changed?var-run_id={runId}");
        var differentTemplate = startRun(Map.of("pluginId", "postgres", "scenarioId", "mixed"));
        assertComparisonRejected(first.get("id"), differentTemplate.get("id"));
    }

    @Test
    @Transactional
    void addsKioskToLegacyLinksWithoutChangingTheirSavedTemplate() throws Exception {
        var created = startRun(Map.of("pluginId", "postgres", "scenarioId", "mixed"));
        Long id = Long.valueOf((String) created.get("id"));
        String savedUrl = "http://localhost:3001/d/dbehave-postgres?var-run_id=" + id + "&kiosk=tv&filter=a%2Bb#panel";
        jdbc.update("UPDATE dbehave.experiments SET grafana_url = ? WHERE task_id = ?", savedUrl, id);
        String expected = "http://localhost:3001/d/dbehave-postgres?var-run_id=" + id + "&filter=a%2Bb&kiosk=1#panel";

        mvc.perform(get("/api/experiments/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grafanaUrl").value(expected))
                .andExpect(jsonPath("$.grafanaUrlTemplate").value("http://localhost:3001/d/dbehave-postgres?var-run_id={runId}"));
        mvc.perform(get("/api/experiments"))
                .andExpect(jsonPath("$[?(@.id == '%s')].grafanaUrl".formatted(id), hasItem(expected)));
        assertThat(jdbc.queryForObject("SELECT grafana_url FROM dbehave.experiments WHERE task_id = ?", String.class, id))
                .isEqualTo(savedUrl);
    }

    @Test
    @Transactional
    void removesOnlySelectedRunsAndRollsBackWhenAnyIdIsUnknown() throws Exception {
        var first = startRun(Map.of("pluginId", "postgres", "scenarioId", "mixed"));
        var second = startRun(Map.of("pluginId", "postgres", "scenarioId", "mixed"));
        var retained = startRun(Map.of("pluginId", "postgres", "scenarioId", "read-heavy"));
        String selection = json.writeValueAsString(Map.of("runIds", List.of(first.get("id"), second.get("id"), first.get("id"))));

        mvc.perform(delete("/api/experiments").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("runIds", List.of(first.get("id"), Long.MAX_VALUE)))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/experiments/{id}", first.get("id"))).andExpect(status().isOk());

        mvc.perform(delete("/api/experiments").contentType(MediaType.APPLICATION_JSON).content(selection))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/experiments").contentType(MediaType.APPLICATION_JSON).content(selection))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/experiments/{id}", first.get("id"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/experiments/{id}", second.get("id"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/experiments/{id}", retained.get("id"))).andExpect(status().isOk());
    }

    @Test
    @Transactional
    void rejectsInvalidSelectionsWithoutClearingHistory() throws Exception {
        var retained = startRun(Map.of("pluginId", "postgres", "scenarioId", "mixed"));
        for (String body : List.of("null", "{}", "{\"runIds\":null}", "{\"runIds\":[]}",
                "{\"runIds\":[null]}", "{\"runIds\":[\"invalid-uuid\"]}")) {
            mvc.perform(delete("/api/experiments").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
            mvc.perform(get("/api/experiments/{id}", retained.get("id"))).andExpect(status().isOk());
        }
    }

    @Test
    @Transactional
    void keepsLegacyTasksReadableAndDeletableWithoutInventingTheirOriginalInput() throws Exception {
        Long id = tasks.insert(new ExecutionContext("example/load:1", "example/database:1", Map.of(), Map.of())).id();

        mvc.perform(get("/api/experiments/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configuration").value(nullValue()))
                .andExpect(jsonPath("$.grafanaUrlTemplate").value(nullValue()));
        mvc.perform(post("/api/experiments/compare").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("runIds", List.of(id)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(delete("/api/experiments/{id}", id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/experiments/{id}", id)).andExpect(status().isNotFound());
    }

    @Test
    @Transactional
    void validatesComparisonIdsAndReturnsNotFoundForHiddenRuns() throws Exception {
        for (String body : List.of("{}", "{\"runIds\":null}", "{\"runIds\":[]}",
                "{\"runIds\":[null]}", "{\"runIds\":[\"invalid-uuid\"]}")) {
            mvc.perform(post("/api/experiments/compare").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        var created = startRun(Map.of("pluginId", "postgres", "scenarioId", "mixed"));
        for (Object id : List.of(String.valueOf(Long.MAX_VALUE), created.get("id"))) {
            if (id.equals(created.get("id"))) {
                mvc.perform(delete("/api/experiments/{id}", id)).andExpect(status().isNoContent());
            }
            mvc.perform(post("/api/experiments/compare").contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("runIds", List.of(id)))))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    @Transactional
    void preservesHistoricalMetricLabelsInDetailsAndComparison() throws Exception {
        var old = startRun(Map.of("pluginId", "postgres", "scenarioId", "mixed"));
        var recent = startRun(Map.of("pluginId", "postgres", "scenarioId", "mixed"));
        var id = Long.valueOf((String) old.get("id"));
        String historical = "00000000-0000-0000-0000-000000000042";
        jdbc.update("UPDATE worker.tasks SET legacy_run_id = ? WHERE id = ?", historical, id);
        jdbc.update("UPDATE dbehave.experiments SET grafana_url = ? WHERE task_id = ?",
                "http://localhost:3001/d/dbehave-postgres?var-run_id=" + historical, id);
        mvc.perform(get("/api/experiments/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.metricsId").value(historical));
        mvc.perform(post("/api/experiments/compare").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("runIds", List.of(id.toString(), recent.get("id"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grafanaUrl").value("http://localhost:3001/d/dbehave-postgres?var-run_id="
                        + historical + "&var-run_id=" + recent.get("id") + "&kiosk=1"));
    }

    private void assertComparisonRejected(Object first, Object second) throws Exception {
        mvc.perform(post("/api/experiments/compare").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("runIds", List.of(first, second)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private Map<String, Object> startRun(Map<String, Object> request) throws Exception {
        return response(mvc.perform(post("/api/experiments").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isCreated()).andReturn());
    }

    private void assertRejectedBeforeQueue(Map<String, Object> request) throws Exception {
        long tasksBefore = taskCount();
        long metadataBefore = jdbc.queryForObject("SELECT count(*) FROM dbehave.experiments", Long.class);
        for (String path : List.of("/api/experiments/validate", "/api/experiments")) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }
        assertThat(taskCount()).isEqualTo(tasksBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dbehave.experiments", Long.class)).isEqualTo(metadataBefore);
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
    private Map<String, Object> persistedContext(Long runId) {
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

    private static Map<String, Object> postgresPlugin() {
        var plugin = genericPlugin();
        plugin.put("collectorId", "postgres");
        plugin.put("scenarios", List.of(Map.of(
                "id", "custom-scenario", "title", "Custom scenario", "description", "PostgreSQL schema",
                "settings", List.of(integerField("shared_buffers", 128, 16, 8192)),
                "params", List.of(integerField("rps", 100, 1, 10000)))));
        return plugin;
    }

    private static Map<String, Object> genericPlugin() {
        var definition = new LinkedHashMap<String, Object>();
        definition.put("id", "integration-" + UUID.randomUUID());
        definition.put("title", "A plugin defined entirely in the database");
        definition.put("collectorId", "generic");
        definition.put("loaderImageName", "example/load:custom");
        definition.put("databaseImageName", "example/database:1");
        definition.put("grafanaUrlTemplate", "https://grafana.example.test/d/custom?var-run_id={runId}");
        definition.put("loaderImageContext", Map.of("command", List.of("load"), "core_max", 0.25, "mem_max", 268_435_456));
        definition.put("databaseImageContext", Map.of("configuration", Map.of("feature", true),
                "core_max", 1, "mem_max", 1_073_741_824));
        definition.put("scenarios", List.of(Map.of(
                "id", "custom-scenario", "title", "Custom scenario", "description", "Database-defined schema",
                "settings", List.of(integerField("cacheSize", 64, 1, 1024)),
                "params", List.of(integerField("rps", 100, 1, 10000)))));
        return definition;
    }

    private static Map<String, Object> genericPluginWithParameter(String type, Object defaultValue) {
        var plugin = genericPlugin();
        plugin.put("scenarios", List.of(Map.of(
                "id", "custom-scenario", "title", "Custom scenario", "description", "Generic parameter",
                "settings", List.of(), "params", List.of(Map.of(
                        "key", "value", "title", "Value", "type", type, "defaultValue", defaultValue,
                        "options", List.of())))));
        return plugin;
    }

    private static Map<String, Object> integerField(String key, int defaultValue, int min, int max) {
        return Map.of("key", key, "title", key, "description", key, "type", "INTEGER",
                "defaultValue", defaultValue, "min", min, "max", max, "options", List.of());
    }
}
