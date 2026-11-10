package io.dbehave.plugin.postgres.load;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.core.metrics.Histogram;
import io.prometheus.metrics.model.registry.PrometheusRegistry;

import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Метрики нагрузчика в формате Prometheus. Метку {@code run_id} добавляет воркер при пересылке.
 * Полный список метрик — в README модуля.
 */
public final class Metrics {
    private final PrometheusRegistry registry = new PrometheusRegistry();

    private final Counter operations = Counter.builder()
            .name("dbehave_operations_total")
            .help("Успешно выполненные операции")
            .labelNames("op")
            .register(registry);
    private final Counter errors = Counter.builder()
            .name("dbehave_errors_total")
            .help("Ошибки операций по видам (SQLSTATE)")
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
    private final Counter scenarioEvents = Counter.builder()
            .name("dbehave_scenario_events_total")
            .help("События сценария: убитый блокировщик, повторы транзакций, аномалии во время нагрузки")
            .labelNames("name")
            .register(registry);
    private final Gauge scenarioValue = Gauge.builder()
            .name("dbehave_scenario_value")
            .help("Текущие значения сценария, например удерживается ли блокировка")
            .labelNames("name")
            .register(registry);
    private final Gauge checkValue = Gauge.builder()
            .name("dbehave_check_value")
            .help("Результат проверки после нагрузки: expected, actual, anomalies")
            .labelNames("check", "kind")
            .register(registry);
    private final Gauge checkPassed = Gauge.builder()
            .name("dbehave_check_passed")
            .help("1 — проверка пройдена, 0 — найдены аномалии")
            .labelNames("check")
            .register(registry);

    private final Set<String> loggedErrors = ConcurrentHashMap.newKeySet();

    public PrometheusRegistry registry() {
        return registry;
    }

    @FunctionalInterface
    public interface SqlWork {
        void run() throws SQLException;
    }

    /**
     * Выполняет и замеряет операцию. Ошибка SQL засчитывается в {@code dbehave_errors_total}.
     *
     * @return {@code null}, если операция прошла, иначе вид ошибки
     * @throws ConnectionLostException если после ошибки соединение непригодно
     */
    public ErrorKind measure(String op, SqlWork work) {
        long start = System.nanoTime();
        try {
            work.run();
        } catch (SQLException e) {
            ErrorKind kind = ErrorKind.of(e);
            error(op, kind, e);
            if (kind.breaksConnection()) {
                throw new ConnectionLostException(e);
            }
            return kind;
        }
        latency.labelValues(op).observe((System.nanoTime() - start) / 1e9);
        operations.labelValues(op).inc();
        return null;
    }

    public void error(String op, ErrorKind kind, SQLException e) {
        errors.labelValues(op, kind.label()).inc();
        if (loggedErrors.add(op + "/" + kind)) {
            Log.warn("Первая ошибка %s в операции %s: [%s] %s".formatted(kind.label(), op, e.getSQLState(), e.getMessage()));
        }
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

    public void event(String name) {
        scenarioEvents.labelValues(name).inc();
    }

    public void value(String name, double value) {
        scenarioValue.labelValues(name).set(value);
    }

    public void check(String check, long expected, long actual, long anomalies) {
        checkValue.labelValues(check, "expected").set(expected);
        checkValue.labelValues(check, "actual").set(actual);
        checkValue.labelValues(check, "anomalies").set(anomalies);
        checkPassed.labelValues(check).set(anomalies == 0 ? 1 : 0);
        Log.info("Проверка %s: ожидалось %d, получено %d, аномалий %d".formatted(check, expected, actual, anomalies));
    }

    /** Соединение потеряно или закрыто сервером: сессию клиента нужно открыть заново. */
    public static final class ConnectionLostException extends RuntimeException {
        public ConnectionLostException(SQLException cause) {
            super(cause.getMessage(), cause);
        }
    }
}
