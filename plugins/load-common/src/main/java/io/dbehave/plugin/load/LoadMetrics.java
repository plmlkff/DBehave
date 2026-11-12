package io.dbehave.plugin.load;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.core.metrics.Histogram;
import io.prometheus.metrics.model.registry.PrometheusRegistry;

public class LoadMetrics {
    private final PrometheusRegistry registry = new PrometheusRegistry();

    private final Counter operations = Counter.builder()
            .name("dbehave_operations_total")
            .help("Успешно выполненные операции")
            .labelNames("op")
            .register(registry);
    private final Counter errors = Counter.builder()
            .name("dbehave_errors_total")
            .help("Ошибки операций по видам")
            .labelNames("op", "kind")
            .register(registry);
    private final Histogram latency = Histogram.builder()
            .name("dbehave_operation_duration_seconds")
            .help("Время выполнения успешных операций")
            .labelNames("op")
            .classicOnly()
            .classicUpperBounds(0.0005, 0.001, 0.002, 0.005, 0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1, 2, 5, 10, 30, 60)
            .register(registry);
    private final Gauge phase = Gauge.builder()
            .name("dbehave_phase")
            .help("Текущая фаза: 1 у активной, 0 у остальных")
            .labelNames("phase")
            .register(registry);
    private final Gauge progress = Gauge.builder()
            .name("dbehave_progress_percent")
            .help("Прогресс текущей фазы, 0–100")
            .register(registry);
    private final Gauge clientsActive = Gauge.builder()
            .name("dbehave_clients_active")
            .help("Клиенты, у которых сейчас открыта сессия")
            .register(registry);
    private final Gauge loadStart = Gauge.builder()
            .name("dbehave_load_start_timestamp_seconds")
            .help("Unix-время начала прогрева")
            .register(registry);
    private final Gauge loadEnd = Gauge.builder()
            .name("dbehave_load_end_timestamp_seconds")
            .help("Unix-время окончания нагрузки")
            .register(registry);
    private final Counter records = Counter.builder()
            .name("dbehave_records_written_total")
            .help("Число записей в успешно подтверждённых пачках")
            .labelNames("op")
            .register(registry);
    private final Gauge runInfo = Gauge.builder()
            .name("dbehave_run_info")
            .help("Описание запуска: движок, сценарий, настройки БД и параметры нагрузки; значение всегда 1")
            .labelNames("engine", "scenario", "settings", "params")
            .register(registry);

    public PrometheusRegistry registry() {
        return registry;
    }

    protected void recordOperation(String op, double seconds) {
        latency.labelValues(op).observe(seconds);
        operations.labelValues(op).inc();
    }

    protected void recordError(String op, String kind) {
        errors.labelValues(op, kind).inc();
    }

    public void recordsWritten(String op, long count) {
        records.labelValues(op).inc(count);
    }

    public void phase(RunState.Phase current) {
        for (RunState.Phase p : RunState.Phase.values()) {
            phase.labelValues(p.name()).set(p == current ? 1 : 0);
        }
    }

    public void progress(int percent) {
        progress.set(percent);
    }

    public void clientOpened() {
        clientsActive.inc();
    }

    public void clientClosed() {
        clientsActive.dec();
    }

    public void loadStarted() {
        loadStart.set(System.currentTimeMillis() / 1000.0);
    }

    public void loadFinished() {
        loadEnd.set(System.currentTimeMillis() / 1000.0);
    }

    public void runInfo(String engine, String scenario, String settings, String params) {
        runInfo.labelValues(engine, scenario, settings, params).set(1);
    }
}
