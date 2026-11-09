package io.dbehave.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ExperimentController.class)
@Import({PluginRegistry.class, PluginConfiguration.class, ExperimentService.class})
class ExperimentControllerTest {
    @Autowired
    private MockMvc mvc;

    @Test
    void returnsFormForSelectedScenario() throws Exception {
        mvc.perform(get("/api/ui/experiment")
                        .param("pluginId", "postgres")
                        .param("scenarioId", "mixed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.scenarios[0].selected").value(true))
                .andExpect(jsonPath("$.form.sections[0].fields[0].name").value("settings.shared_buffers"))
                .andExpect(jsonPath("$.form.sections[0].fields[0].defaultValue").value(256))
                .andExpect(jsonPath("$.form.sections[1].fields[*].name", containsInAnyOrder(
                        "params.clients", "params.durationSec", "params.rows", "params.readPercent")))
                .andExpect(jsonPath("$.form.submit.url").value("/api/experiments/validate"));
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
                .andExpect(jsonPath("$.execution").value("NOT_IMPLEMENTED"));
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

    @Test
    void returnsNotFoundForUnknownScenario() throws Exception {
        mvc.perform(get("/api/ui/experiment")
                        .param("pluginId", "postgres")
                        .param("scenarioId", "unknown"))
                .andExpect(status().isNotFound());
    }
}
