package io.dbehave.plugin.postgres.load;

import io.dbehave.plugin.load.Log;
import io.dbehave.plugin.load.LoadMetrics;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;

import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Метрики нагрузчика в формате Prometheus. Метку {@code run_id} добавляет воркер при пересылке.
 * Полный список метрик — в README модуля.
 */
public final class Metrics extends LoadMetrics {
    private final Counter scenarioEvents = Counter.builder()
            .name("dbehave_scenario_events_total")
            .help("События сценария: убитый блокировщик, повторы транзакций, аномалии во время нагрузки")
            .labelNames("name")
            .register(registry());
    private final Gauge scenarioValue = Gauge.builder()
            .name("dbehave_scenario_value")
            .help("Текущие значения сценария, например удерживается ли блокировка")
            .labelNames("name")
            .register(registry());
    private final Gauge checkValue = Gauge.builder()
            .name("dbehave_check_value")
            .help("Результат проверки после нагрузки: expected, actual, anomalies")
            .labelNames("check", "kind")
            .register(registry());
    private final Gauge checkPassed = Gauge.builder()
            .name("dbehave_check_passed")
            .help("1 — проверка пройдена, 0 — найдены аномалии")
            .labelNames("check")
            .register(registry());

    private final Set<String> loggedErrors = ConcurrentHashMap.newKeySet();

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
        recordOperation(op, (System.nanoTime() - start) / 1e9);
        return null;
    }

    public void error(String op, ErrorKind kind, SQLException e) {
        recordError(op, kind.label());
        if (loggedErrors.add(op + "/" + kind)) {
            Log.warn("Первая ошибка %s в операции %s: [%s] %s".formatted(kind.label(), op, e.getSQLState(), e.getMessage()));
        }
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
