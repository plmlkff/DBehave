package io.dbehave.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import io.dbehave.plugin.postgres.PostgresContextCollector;
import java.util.Optional;
import static org.mockito.Mockito.when;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ExperimentController.class)
@Import({PluginRegistry.class, PluginConfiguration.class, ExperimentService.class, JsonConfiguration.class})
class ExperimentControllerTest {
    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PluginRepository plugins;
    @MockitoBean
    private TaskRepository tasks;

    @BeforeEach
    void catalog() {
        when(plugins.findById("postgres")).thenReturn(Optional.of(PostgresContextCollector.defaultDefinition()));
    }

    @Test
    void returnsFormForSelectedScenario() throws Exception {
        mvc.perform(get("/api/ui/experiment")
                        .param("pluginId", "postgres")
                        .param("scenarioId", "mixed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.scenarios[*].id", containsInAnyOrder(
                        "mixed", "read-heavy", "write-heavy", "long-tx-locks", "isolation")))
                .andExpect(jsonPath("$.scenarios[0].selected").value(true))
                .andExpect(jsonPath("$.form.sections[0].fields[0].name").value("settings.shared_buffers"))
                .andExpect(jsonPath("$.form.sections[0].fields[0].defaultValue").value(256))
                .andExpect(jsonPath("$.form.sections[1].fields[*].name", containsInAnyOrder(
                        "params.clients", "params.durationSec", "params.rows", "params.readPercent")))
                .andExpect(jsonPath("$.form.sections[0].id").value("settings"))
                .andExpect(jsonPath("$.form.sections[1].id").value("params"))
                .andExpect(jsonPath("$.form.sections[2].fields[0].name").value("loaderImageContext.core_max"))
                .andExpect(jsonPath("$.form.sections[2].fields[0].minExclusive").value(true))
                .andExpect(jsonPath("$.form.sections[3].fields[1].name").value("databaseImageContext.mem_max"))
                .andExpect(jsonPath("$.title").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.form.sections[0].title").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.form.submit").doesNotHaveJsonPath());
    }

    @Test
    void validatesConfigurationWithDefaults() throws Exception {
        mvc.perform(post("/api/experiments/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pluginId":"postgres","scenarioId":"mixed"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andExpect(jsonPath("$.execution").value("READY"));
    }

    @Test
    void returnsReadHeavyFormWithTypedFields() throws Exception {
        mvc.perform(get("/api/ui/experiment")
                        .param("pluginId", "postgres")
                        .param("scenarioId", "read-heavy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenarios[0].selected").value(false))
                .andExpect(jsonPath("$.scenarios[1].selected").value(true))
                .andExpect(jsonPath("$.form.sections[0].fields[2].type").value("NUMBER"))
                .andExpect(jsonPath("$.form.sections[0].fields[2].min").value(0.1))
                .andExpect(jsonPath("$.form.sections[1].fields[3].type").value("BOOLEAN"))
                .andExpect(jsonPath("$.form.sections[1].fields[3].defaultValue").value(false))
                .andExpect(jsonPath("$.form.sections[1].fields[4].type").value("ENUM"))
                .andExpect(jsonPath("$.form.sections[1].fields[4].options", containsInAnyOrder("uniform", "hotspot")));
    }

    @Test
    void validatesReadHeavyTypesAndDecimalBounds() throws Exception {
        mvc.perform(post("/api/experiments/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "pluginId": "postgres",
                                  "scenarioId": "read-heavy",
                                  "settings": {"random_page_cost": 1.25},
                                  "params": {"categoryIndex": true, "distribution": "hotspot"}
                                }
                                """))
                .andExpect(status().isOk());

        mvc.perform(post("/api/experiments/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "pluginId": "postgres",
                                  "scenarioId": "read-heavy",
                                  "settings": {"random_page_cost": 100.00000000000000001},
                                  "params": {"categoryIndex": "true", "distribution": "unknown"}
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", containsInAnyOrder(
                        "settings.random_page_cost", "params.categoryIndex", "params.distribution")));
    }

    @ParameterizedTest
    @CsvSource({
            "write-heavy, params.batchSize",
            "long-tx-locks, params.holdSec",
            "isolation, params.isolationLevel"
    })
    void exposesAndValidatesNewScenarios(String scenarioId, String field) throws Exception {
        mvc.perform(get("/api/ui/experiment")
                        .param("pluginId", "postgres")
                        .param("scenarioId", scenarioId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.form.scenarioId").value(scenarioId))
                .andExpect(jsonPath("$.form.sections[1].fields[*].name", hasItem(field)));

        mvc.perform(post("/api/experiments/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pluginId":"postgres","scenarioId":"%s"}
                                """.formatted(scenarioId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"));
    }

    @Test
    void returnsErrorsForInvalidFields() throws Exception {
        mvc.perform(post("/api/experiments/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "pluginId": "postgres",
                                  "scenarioId": "mixed",
                                  "settings": {"shared_buffers": 5, "unknown": 1},
                                  "params": {"clients": "16"}
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[*].field", containsInAnyOrder(
                        "settings.shared_buffers", "settings.unknown", "params.clients")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"pluginId\":\"postgres\",\"scenarioId\":\"mixed\",\"setting\":{\"shared_buffers\":128}}",
            "{\"pluginId\":\"postgres\",\"scenarioId\":\"mixed\",\"params\":{\"rows\":100,\"rows\":200}}"
    })
    void rejectsAmbiguousRequestBodies(String body) throws Exception {
        mvc.perform(post("/api/experiments/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void returnsNotFoundForUnknownScenario() throws Exception {
        mvc.perform(get("/api/ui/experiment")
                        .param("pluginId", "postgres")
                        .param("scenarioId", "unknown"))
                .andExpect(status().isNotFound());
    }
}
