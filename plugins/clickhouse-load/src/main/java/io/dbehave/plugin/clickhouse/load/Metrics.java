package io.dbehave.plugin.clickhouse.load;

import io.dbehave.plugin.load.LoadMetrics;
import io.dbehave.plugin.load.Log;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

final class Metrics extends LoadMetrics {
    private final LongAdder successfulOperations = new LongAdder();
    private final Set<String> loggedErrors = ConcurrentHashMap.newKeySet();
    private final Counter readRows = counter("dbehave_clickhouse_read_rows_total", "Строки, прочитанные запросами нагрузки");
    private final Counter readBytes = counter("dbehave_clickhouse_read_bytes_total", "Байты, прочитанные запросами нагрузки");
    private final Counter serverSeconds = counter("dbehave_clickhouse_query_seconds_total", "Серверное время запросов нагрузки");
    private final Gauge parts = gauge("dbehave_clickhouse_active_parts", "Активные части MergeTree");
    private final Gauge merges = gauge("dbehave_clickhouse_active_merges", "Выполняемые слияния");
    private final Gauge storedRows = gauge("dbehave_clickhouse_stored_rows", "Физические строки в активных частях");
    private final Gauge storedBytes = gauge("dbehave_clickhouse_stored_bytes", "Объём активных частей на диске");
    private final Gauge verifiedRows = Gauge.builder().name("dbehave_clickhouse_verified_rows")
            .help("Контрольное число строк после нагрузки").labelNames("kind").register(registry());

    boolean measure(String operation, Supplier<ClickHouse.Result> work) {
        long start = System.nanoTime();
        try {
            var result = work.get();
            recordOperation(operation, (System.nanoTime() - start) / 1e9);
            successfulOperations.increment();
            readRows.labelValues(operation).inc(result.summary().readRows());
            readBytes.labelValues(operation).inc(result.summary().readBytes());
            serverSeconds.labelValues(operation).inc(result.summary().elapsedNanos() / 1e9);
            return true;
        } catch (ClickHouse.QueryException error) {
            String kind = kind(error.code());
            recordError(operation, kind);
            if (loggedErrors.add(operation + "/" + kind)) {
                String detail = error.getMessage().replace('\n', ' ').replace('\r', ' ');
                Log.warn("Первая ошибка " + kind + " в операции " + operation + ", код " + error.code()
                        + ": " + detail.substring(0, Math.min(300, detail.length())));
            }
            return false;
        }
    }

    long successfulOperations() {
        return successfulOperations.sum();
    }

    static String kind(int code) {
        return switch (code) {
            case -1 -> "connection_error";
            case 241 -> "memory_limit";
            case 252 -> "too_many_parts";
            case 159 -> "query_timeout";
            default -> "query_error";
        };
    }

    void storage(ClickHouse database) {
        String[] values = database.query("SELECT count(), coalesce(sum(rows), 0), coalesce(sum(bytes_on_disk), 0) "
                + "FROM system.parts WHERE active AND database = '" + database.database() + "'").body().strip().split("\t");
        parts.set(Double.parseDouble(values[0]));
        storedRows.set(Double.parseDouble(values[1]));
        storedBytes.set(Double.parseDouble(values[2]));
        merges.set(database.queryLong("SELECT count() FROM system.merges WHERE database = '" + database.database() + "'"));
    }

    void verifiedRows(String kind, long rows) {
        verifiedRows.labelValues(kind).set(rows);
    }

    private Counter counter(String name, String help) {
        return Counter.builder().name(name).help(help).labelNames("op").register(registry());
    }

    private Gauge gauge(String name, String help) {
        return Gauge.builder().name(name).help(help).register(registry());
    }
}
