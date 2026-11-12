package io.dbehave.plugin.postgres.load;

import io.dbehave.plugin.load.Log;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.model.registry.PrometheusRegistry;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Раз в секунду читает статистику PostgreSQL ({@code pg_stat_*}) через отдельное соединение.
 * Накопительные счётчики PostgreSQL становятся счётчиками Prometheus: считаем прирост с начала нагрузки.
 */
final class DbSampler implements AutoCloseable {
    private static final String ACTIVITY = """
            select count(*) filter (where state = 'active'),
                   count(*) filter (where state = 'idle'),
                   count(*) filter (where state like 'idle in transaction%'),
                   count(*) filter (where wait_event_type = 'Lock'),
                   coalesce(extract(epoch from max(now() - query_start) filter (where wait_event_type = 'Lock')), 0),
                   coalesce(extract(epoch from max(now() - xact_start)), 0)
            from pg_stat_activity
            where datname = current_database() and backend_type = 'client backend' and pid <> pg_backend_pid()""";
    private static final String DATABASE = """
            select xact_commit, xact_rollback, blks_hit, blks_read, temp_bytes, deadlocks,
                   pg_database_size(current_database())
            from pg_stat_database where datname = current_database()""";
    private static final String WAL = "select wal_bytes, wal_fpi from pg_stat_wal";
    private static final String CHECKPOINTER = "select num_timed, num_requested from pg_stat_checkpointer";
    private static final String TUPLES = "select coalesce(sum(n_live_tup), 0), coalesce(sum(n_dead_tup), 0) from pg_stat_user_tables";

    private final Db db;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("db-sampler").daemon().factory());
    private final Map<List<Object>, Double> previous = new HashMap<>();
    private Connection connection;

    private final Gauge connections;
    private final Gauge lockWaiters;
    private final Gauge lockWaitMax;
    private final Gauge oldestTransaction;
    private final Gauge tuples;
    private final Gauge databaseSize;
    private final Counter transactions;
    private final Counter blocks;
    private final Counter tempBytes;
    private final Counter deadlocks;
    private final Counter walBytes;
    private final Counter walFullPages;
    private final Counter checkpoints;

    DbSampler(Db db, PrometheusRegistry registry) {
        this.db = db;
        connections = gauge(registry, "dbehave_db_connections", "Сессии клиентов по состоянию", "state");
        lockWaiters = gauge(registry, "dbehave_db_lock_waiters", "Сессии, ожидающие блокировку");
        lockWaitMax = gauge(registry, "dbehave_db_lock_wait_max_seconds", "Самое долгое текущее ожидание блокировки");
        oldestTransaction = gauge(registry, "dbehave_db_oldest_transaction_seconds", "Возраст самой старой открытой транзакции");
        tuples = gauge(registry, "dbehave_db_tuples", "Живые и мёртвые версии строк в пользовательских таблицах", "state");
        databaseSize = gauge(registry, "dbehave_db_size_bytes", "Размер тестовой базы");
        transactions = counter(registry, "dbehave_db_transactions_total", "Транзакции по pg_stat_database", "result");
        blocks = counter(registry, "dbehave_db_blocks_total", "Чтения блоков: hit — из кеша, read — с диска или из кеша ОС", "source");
        tempBytes = counter(registry, "dbehave_db_temp_bytes_total", "Байты во временных файлах (сортировки и хеши на диске)");
        deadlocks = counter(registry, "dbehave_db_deadlocks_total", "Обнаруженные взаимоблокировки");
        walBytes = counter(registry, "dbehave_db_wal_bytes_total", "Объём записанного WAL");
        walFullPages = counter(registry, "dbehave_db_wal_full_page_images_total", "Полные страницы в WAL (после чекпоинтов)");
        checkpoints = counter(registry, "dbehave_db_checkpoints_total", "Чекпоинты: timed — по времени, requested — по объёму WAL", "kind");
    }

    void start() {
        scheduler.scheduleAtFixedRate(this::sampleSafely, 0, 1, TimeUnit.SECONDS);
    }

    private void sampleSafely() {
        try {
            if (connection == null || connection.isClosed()) {
                connection = db.connect();
            }
            sample();
        } catch (SQLException e) {
            Log.warn("Не удалось снять статистику PostgreSQL: " + e.getMessage());
            db.close(connection);
            connection = null;
        } catch (RuntimeException e) {
            Log.error("Ошибка сбора статистики", e);
        }
    }

    private void sample() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery(ACTIVITY)) {
                rs.next();
                connections.labelValues("active").set(rs.getLong(1));
                connections.labelValues("idle").set(rs.getLong(2));
                connections.labelValues("idle_in_transaction").set(rs.getLong(3));
                lockWaiters.set(rs.getLong(4));
                lockWaitMax.set(rs.getDouble(5));
                oldestTransaction.set(rs.getDouble(6));
            }
            try (ResultSet rs = statement.executeQuery(DATABASE)) {
                rs.next();
                increase(transactions, "commit", rs.getDouble(1));
                increase(transactions, "rollback", rs.getDouble(2));
                increase(blocks, "hit", rs.getDouble(3));
                increase(blocks, "read", rs.getDouble(4));
                increase(tempBytes, null, rs.getDouble(5));
                increase(deadlocks, null, rs.getDouble(6));
                databaseSize.set(rs.getDouble(7));
            }
            try (ResultSet rs = statement.executeQuery(WAL)) {
                rs.next();
                increase(walBytes, null, rs.getDouble(1));
                increase(walFullPages, null, rs.getDouble(2));
            }
            try (ResultSet rs = statement.executeQuery(CHECKPOINTER)) {
                rs.next();
                increase(checkpoints, "timed", rs.getDouble(1));
                increase(checkpoints, "requested", rs.getDouble(2));
            }
            try (ResultSet rs = statement.executeQuery(TUPLES)) {
                rs.next();
                tuples.labelValues("live").set(rs.getDouble(1));
                tuples.labelValues("dead").set(rs.getDouble(2));
            }
        }
    }

    /** Первый замер запоминает исходное значение, дальше в счётчик идёт только прирост. */
    private void increase(Counter counter, String label, double current) {
        Double before = previous.put(List.of(counter, label == null ? "" : label), current);
        if (before == null || current < before) {
            if (label != null) {
                counter.labelValues(label).inc(0);
            }
            return;
        }
        if (label == null) {
            counter.inc(current - before);
        } else {
            counter.labelValues(label).inc(current - before);
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        db.close(connection);
    }

    private static Gauge gauge(PrometheusRegistry registry, String name, String help, String... labels) {
        var builder = Gauge.builder().name(name).help(help);
        return (labels.length == 0 ? builder : builder.labelNames(labels)).register(registry);
    }

    private static Counter counter(PrometheusRegistry registry, String name, String help, String... labels) {
        var builder = Counter.builder().name(name).help(help);
        return (labels.length == 0 ? builder : builder.labelNames(labels)).register(registry);
    }
}
