package io.dbehave.backend.experiment;

import io.dbehave.backend.plugin.PluginRegistry;
import io.dbehave.backend.plugin.PluginRepository;
import io.dbehave.backend.task.ExecutionContext;
import io.dbehave.backend.task.TaskRepository;
import io.dbehave.backend.task.WorkerTask;
import io.dbehave.backend.validation.JsonbValidation;
import io.dbehave.backend.validation.InvalidExperimentException;
import io.dbehave.plugin.FieldSpec;
import io.dbehave.plugin.ContextCollector;
import io.dbehave.plugin.ImageContexts;
import io.dbehave.plugin.PluginDefinition;
import io.dbehave.plugin.Scenario;
import io.dbehave.plugin.postgres.PostgresContextCollector;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

class ExperimentServiceTest {
    @Test
    void rejectsExtremePostgresNumbersBeforeTheCollectorCanExpandThem() {
        var definition = postgresWithUnboundedNumbers(BigDecimal.ONE);
        var repository = mock(PluginRepository.class);
        when(repository.findById(definition.id())).thenReturn(Optional.of(definition));
        var collector = spy(new PostgresContextCollector());
        var jsonb = new JsonbValidation(JsonMapper.builder().build());
        var registry = new PluginRegistry(repository, List.of(collector), jsonb);
        var tasks = mock(TaskRepository.class);
        var service = new ExperimentService(registry, tasks, jsonb);
        clearInvocations(collector);

        var huge = new BigDecimal("1e1000000");
        for (var request : List.of(
                new ExperimentRequest(definition.id(), "custom", Map.of("extension.limit", huge), Map.of()),
                new ExperimentRequest(definition.id(), "custom", Map.of(), Map.of("customParam", huge)))) {
            assertThatThrownBy(() -> service.prepare(request)).isInstanceOf(InvalidExperimentException.class);
        }
        verifyNoInteractions(collector, tasks);
    }

    @Test
    void rejectsExtremePostgresDefaultsBeforeRegistrationCallsTheCollector() {
        var repository = mock(PluginRepository.class);
        var collector = spy(new PostgresContextCollector());
        var registry = new PluginRegistry(repository, List.of(collector),
                new JsonbValidation(JsonMapper.builder().build()));
        clearInvocations(collector);

        assertThatThrownBy(() -> registry.create(postgresWithUnboundedNumbers(new BigDecimal("1e1000000"))))
                .isInstanceOf(InvalidExperimentException.class);
        verifyNoInteractions(collector, repository);
    }

    @Test
    void savesDisplayMetadataFromTheSameCatalogSnapshotAsTheExecutionContext() {
        var original = PostgresContextCollector.defaultDefinition();
        var changed = new PluginDefinition(original.id(), "Changed title", original.collectorId(),
                original.loaderImageName(), original.databaseImageName(), "https://changed.example.test/{runId}",
                original.loaderImageContext(), original.databaseImageContext(), original.scenarios());
        var repository = mock(PluginRepository.class);
        when(repository.findById("postgres")).thenReturn(Optional.of(original), Optional.of(changed));
        var tasks = mock(TaskRepository.class);
        when(tasks.insert(any(UUID.class), any(ExecutionContext.class))).thenAnswer(invocation -> new WorkerTask(
                invocation.getArgument(0), "QUEUED", Instant.EPOCH, Instant.EPOCH,
                invocation.getArgument(1), null, null, null));
        var service = new ExperimentService(new PluginRegistry(repository, List.of(new PostgresContextCollector()), mock(JsonbValidation.class)), tasks, mock(JsonbValidation.class));

        var created = service.start(new ExperimentRequest("postgres", "mixed", null, null));

        assertThat(created.pluginTitle()).isEqualTo(original.title());
        assertThat(created.scenarioTitle()).isEqualTo(original.scenarios().stream()
                .filter(scenario -> scenario.id().equals("mixed")).findFirst().orElseThrow().title());
        assertThat(created.grafanaUrl()).isEqualTo(
                original.grafanaUrlTemplate().replace("{runId}", created.id().toString()) + "&kiosk");
        verify(repository).findById("postgres");
        verify(tasks).insertExperiment(created.id(), created.pluginId(), created.pluginTitle(),
                created.scenarioId(), created.scenarioTitle(), created.grafanaUrl(),
                created.configuration(), created.grafanaUrlTemplate());
        assertThat(created.configuration().settings()).containsEntry("shared_buffers", 128);
        assertThat(created.configuration().params()).containsEntry("clients", 16);
        assertThat(created.grafanaUrlTemplate()).isEqualTo(original.grafanaUrlTemplate());
    }

    @Test
    void snapshotsNormalizedInputsAndOnlyTheEffectiveContainerLimits() {
        var scenario = new Scenario("custom", "Custom", "",
                List.of(FieldSpec.integer("cache", "Cache", "", 64, 1, 1024, null)),
                List.of(FieldSpec.integer("rps", "RPS", "", 100, 1, 1000, null)));
        var plugin = new PluginDefinition("custom", "Custom", "custom", "loader:1", "database:1",
                "https://grafana.test/?var-run_id={runId}", Map.of(), Map.of(), List.of(scenario));
        var repository = mock(PluginRepository.class);
        when(repository.findById("custom")).thenReturn(Optional.of(plugin));
        var collector = mock(ContextCollector.class);
        when(collector.id()).thenReturn("custom");
        when(collector.collect(any(), any(), any(), any())).thenReturn(new ImageContexts(
                Map.of("core_max", 0.75, "mem_max", 1024, "environment", Map.of("TOKEN", "secret")),
                Map.of("core_max", 2, "mem_max", 2048)));
        var jsonb = mock(JsonbValidation.class);
        var service = new ExperimentService(new PluginRegistry(repository, List.of(collector), jsonb),
                mock(TaskRepository.class), jsonb);

        var prepared = service.prepare(new ExperimentRequest("custom", "custom", null, Map.of("rps", 250),
                Map.of("core_max", 0.5), null));

        assertThat(prepared.configuration().settings()).containsExactlyEntriesOf(Map.of("cache", 64));
        assertThat(prepared.configuration().params()).containsExactlyEntriesOf(Map.of("rps", 250));
        assertThat(prepared.configuration().loaderImageContext())
                .containsExactlyInAnyOrderEntriesOf(Map.of("core_max", 0.5, "mem_max", 1024));
        assertThat(prepared.configuration().databaseImageContext())
                .containsExactlyInAnyOrderEntriesOf(Map.of("core_max", 2, "mem_max", 2048));
    }

    @Test
    void comparesSavedTemplatesAndScenariosAcrossPluginsInDeduplicatedInputOrder() {
        var tasks = mock(TaskRepository.class);
        var registry = mock(PluginRegistry.class);
        var service = new ExperimentService(registry, tasks, mock(JsonbValidation.class));
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        String template = "https://grafana.test/d/board?var-run_id={runId}&from=now-1h#panel";
        when(tasks.findExperimentsByIds(List.of(first, second))).thenReturn(List.of(
                run(second, "second-plugin", "mixed", template), run(first, "first-plugin", "mixed", template)));

        assertThat(service.compare(List.of(first, second, first))).isEqualTo(
                "https://grafana.test/d/board?var-run_id=" + first + "&var-run_id=" + second + "&from=now-1h&kiosk#panel");
        verify(tasks).findExperimentsByIds(List.of(first, second));
        verifyNoInteractions(registry);
    }

    @Test
    void rejectsInvalidSelectionsBeforeReadingOrHidingHistory() {
        var tasks = mock(TaskRepository.class);
        var service = new ExperimentService(mock(PluginRegistry.class), tasks, mock(JsonbValidation.class));
        List<List<UUID>> invalid = Arrays.asList(null, List.of(), Arrays.asList(UUID.randomUUID(), null));
        for (var ids : invalid) {
            assertThatThrownBy(() -> service.compare(ids)).isInstanceOf(InvalidExperimentException.class);
            assertThatThrownBy(() -> service.hideSelected(ids)).isInstanceOf(InvalidExperimentException.class);
        }
        verifyNoInteractions(tasks);
    }

    @Test
    void returnsNotFoundWhenAnyComparedRunIsMissingOrHidden() {
        var tasks = mock(TaskRepository.class);
        var service = new ExperimentService(mock(PluginRegistry.class), tasks, mock(JsonbValidation.class));
        var id = UUID.randomUUID();
        when(tasks.findExperimentsByIds(List.of(id))).thenReturn(List.of());

        assertThatThrownBy(() -> service.compare(List.of(id)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void rejectsMissingMetadataMismatchesAndUnsupportedDashboardTemplates() {
        var tasks = mock(TaskRepository.class);
        var service = new ExperimentService(mock(PluginRegistry.class), tasks, mock(JsonbValidation.class));
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        String template = "https://grafana.test/?var-run_id={runId}";
        var baseline = run(first, "postgres", "mixed", template);
        for (var other : List.of(
                run(second, "postgres", "mixed", null), run(second, "postgres", null, template),
                run(second, "postgres", "read-heavy", template),
                run(second, "postgres", "mixed", "https://other.test/?var-run_id={runId}"))) {
            when(tasks.findExperimentsByIds(List.of(first, second))).thenReturn(List.of(baseline, other));
            assertThatThrownBy(() -> service.compare(List.of(first, second)))
                    .isInstanceOf(InvalidExperimentException.class);
        }
        when(tasks.findExperimentsByIds(List.of(first))).thenReturn(List.of(
                run(first, "postgres", "mixed", "https://grafana.test/run/{runId}")));
        assertThatThrownBy(() -> service.compare(List.of(first))).isInstanceOf(InvalidExperimentException.class);
    }

    @Test
    void hidesRunsIdempotentlyAndClearsHistoryThroughTombstones() {
        var tasks = mock(TaskRepository.class);
        var service = new ExperimentService(mock(PluginRegistry.class), tasks, mock(JsonbValidation.class));
        var id = UUID.randomUUID();
        when(tasks.hideExperiment(id)).thenReturn(true);

        service.hide(id);
        service.hide(id);
        service.clearHistory();

        verify(tasks, times(2)).hideExperiment(id);
        verify(tasks).hideAllExperiments();
        assertThatThrownBy(() -> service.hide(UUID.randomUUID()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void locksAllSelectedTasksBeforeHidingThemAndDeduplicatesIds() {
        var tasks = mock(TaskRepository.class);
        var service = new ExperimentService(mock(PluginRegistry.class), tasks, mock(JsonbValidation.class));
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        when(tasks.lockTaskIds(List.of(first, second))).thenReturn(List.of(second, first));

        service.hideSelected(List.of(first, second, first));

        var calls = inOrder(tasks);
        calls.verify(tasks).lockTaskIds(List.of(first, second));
        calls.verify(tasks).hideExperiments(List.of(first, second));
        verify(tasks, never()).hideAllExperiments();
    }

    @Test
    void doesNotHideAnyRunWhenTheSelectionContainsAnUnknownTask() {
        var tasks = mock(TaskRepository.class);
        var service = new ExperimentService(mock(PluginRegistry.class), tasks, mock(JsonbValidation.class));
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        when(tasks.lockTaskIds(List.of(first, second))).thenReturn(List.of(first));

        assertThatThrownBy(() -> service.hideSelected(List.of(first, second)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(tasks, never()).hideExperiments(any());
        verify(tasks, never()).hideAllExperiments();
    }

    @Test
    void mapsDefaultsAndOverridesToSeparateContainers() {
        var registry = registry();
        var service = new ExperimentService(registry, mock(TaskRepository.class), mock(JsonbValidation.class));

        var plan = service.prepare(new ExperimentRequest(
                "postgres", "mixed", Map.of("shared_buffers", 128), Map.of("datasetRows", 100_000))).context();

        assertThat(plan.databaseImageName()).isEqualTo("postgres:18");
        assertThat(environment(plan.databaseImageContext()))
                .containsEntry("DBEHAVE_SHARED_BUFFERS", "128MB")
                .containsEntry("DBEHAVE_MAX_CONNECTIONS", "100");
        assertThat(plan.loaderImageName()).isEqualTo("dbehave/postgres-load:0.1.0");
        assertThat(environment(plan.loaderImageContext()))
                .containsEntry("SCENARIO", "mixed")
                .containsEntry("DATASET_ROWS", "100000")
                .containsEntry("CLIENTS", "16");
    }

    @Test
    void mapsReadHeavyConfigurationToItsContainers() {
        var registry = registry();
        var service = new ExperimentService(registry, mock(TaskRepository.class), mock(JsonbValidation.class));

        var plan = service.prepare(new ExperimentRequest(
                "postgres", "read-heavy", Map.of("random_page_cost", new BigDecimal("1.25")),
                Map.of("categoryIndex", true, "distribution", "hotspot"))).context();

        assertThat(environment(plan.databaseImageContext())).containsEntry("DBEHAVE_RANDOM_PAGE_COST", "1.25");
        assertThat(plan.loaderImageName()).isEqualTo("dbehave/postgres-load:0.1.0");
        assertThat(environment(plan.loaderImageContext()))
                .containsEntry("DATASET_ROWS", "1000000")
                .containsEntry("CATEGORY_INDEX", "true")
                .containsEntry("DISTRIBUTION", "hotspot")
                .doesNotContainKey("READ_PERCENT");
    }

    @Test
    void mapsWriteHeavyConfigurationToItsContainers() {
        var service = new ExperimentService(registry(), mock(TaskRepository.class), mock(JsonbValidation.class));
        var plan = service.prepare(new ExperimentRequest(
                "postgres", "write-heavy", Map.of("synchronous_commit", "off"), Map.of("batchSize", 100))).context();

        assertThat(environment(plan.databaseImageContext())).containsEntry("DBEHAVE_SYNCHRONOUS_COMMIT", "off");
        assertThat(command(plan.databaseImageContext()))
                .contains("synchronous_commit=off");
        assertThat(plan.loaderImageName()).isEqualTo("dbehave/postgres-load:0.1.0");
        assertThat(environment(plan.loaderImageContext()))
                .containsEntry("BATCH_SIZE", "100")
                .containsEntry("EXTRA_INDEXES", "0")
                .containsEntry("PAYLOAD_BYTES", "200");
    }

    @Test
    void mapsLockConfigurationToItsContainers() {
        var service = new ExperimentService(registry(), mock(TaskRepository.class), mock(JsonbValidation.class));
        var plan = service.prepare(new ExperimentRequest(
                "postgres", "long-tx-locks", Map.of("lock_timeout", 500), Map.of("blockerMode", "active"))).context();

        assertThat(environment(plan.databaseImageContext())).containsEntry("DBEHAVE_LOCK_TIMEOUT", "500ms");
        assertThat(command(plan.databaseImageContext())).contains("lock_timeout=500ms");
        assertThat(plan.loaderImageName()).isEqualTo("dbehave/postgres-load:0.1.0");
        assertThat(environment(plan.loaderImageContext()))
                .containsEntry("HOLD_SEC", "15")
                .containsEntry("BLOCKER_MODE", "active");
    }

    @Test
    void passesIsolationLevelToTheLoadGenerator() {
        var service = new ExperimentService(registry(), mock(TaskRepository.class), mock(JsonbValidation.class));
        var plan = service.prepare(new ExperimentRequest(
                "postgres", "isolation", Map.of(), Map.of("isolationLevel", "REPEATABLE READ"))).context();

        assertThat(plan.loaderImageName()).isEqualTo("dbehave/postgres-load:0.1.0");
        assertThat(environment(plan.loaderImageContext()))
                .containsEntry("ISOLATION_LEVEL", "REPEATABLE READ")
                .containsEntry("COUNTER_ROWS", "10")
                .containsEntry("WRITE_MODE", "read-modify-write");
    }

    private static PluginRegistry registry() {
        var repository = mock(PluginRepository.class);
        when(repository.findById("postgres")).thenReturn(Optional.of(PostgresContextCollector.defaultDefinition()));
        return new PluginRegistry(repository, List.of(new PostgresContextCollector()), mock(JsonbValidation.class));
    }

    private static PluginDefinition postgresWithUnboundedNumbers(BigDecimal defaultValue) {
        var base = PostgresContextCollector.defaultDefinition();
        var setting = new FieldSpec("extension.limit", "Limit", "", FieldSpec.Type.NUMBER,
                defaultValue, null, null, null, List.of());
        var parameter = new FieldSpec("customParam", "Parameter", "", FieldSpec.Type.NUMBER,
                defaultValue, null, null, null, List.of());
        return new PluginDefinition("custom", "Custom", base.collectorId(), base.loaderImageName(),
                base.databaseImageName(), base.grafanaUrlTemplate(), Map.of(), Map.of(),
                List.of(new Scenario("custom", "Custom", "", List.of(setting), List.of(parameter))));
    }

    private static ExperimentRun run(UUID id, String pluginId, String scenarioId, String template) {
        return new ExperimentRun(id, "DONE", Instant.EPOCH, Instant.EPOCH, null, null, null,
                template == null ? null : template.replace("{runId}", id.toString()),
                pluginId, "Plugin", scenarioId, "Scenario", null, template);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> environment(Map<String, Object> context) {
        return (Map<String, String>) context.get("environment");
    }

    @SuppressWarnings("unchecked")
    private static List<String> command(Map<String, Object> context) {
        return (List<String>) context.get("command");
    }
}
