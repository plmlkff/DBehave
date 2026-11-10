package io.dbehave.plugin.postgres.load.scenario;

import io.dbehave.plugin.postgres.load.Db;
import io.dbehave.plugin.postgres.load.ErrorKind;
import io.dbehave.plugin.postgres.load.Metrics;
import io.dbehave.plugin.postgres.load.ScenarioContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Проверка уровней изоляции. Переменная {@code ANOMALY} выбирает эксперимент:
 * <ul>
 *     <li>{@code lost-update} — чтение и запись счётчика с паузой; потерянные обновления видны при READ COMMITTED;</li>
 *     <li>{@code write-skew} — «дежурные врачи»: двое уходят с дежурства одновременно, смена остаётся пустой;
 *     возможно и при REPEATABLE READ, предотвращает только SERIALIZABLE;</li>
 *     <li>{@code non-repeatable-read} — два чтения суммы в одной транзакции дают разный результат.</li>
 * </ul>
 * Результат проверки уходит в {@code dbehave_check_value} и {@code dbehave_check_passed}.
 */
final class IsolationScenarios {
    private IsolationScenarios() {
    }

    static Scenario create(ScenarioContext ctx) {
        var config = ctx.config();
        var settings = new Settings(
                Scenarios.isolationLevel(config.oneOf("ISOLATION_LEVEL", "READ COMMITTED",
                        Set.of("READ COMMITTED", "REPEATABLE READ", "SERIALIZABLE"))),
                config.integer("THINK_MS", 5, 0, 10_000),
                config.bool("RETRY_ON_SERIALIZATION_FAILURE", false));
        String anomaly = config.oneOf("ANOMALY", "lost-update", Set.of("lost-update", "write-skew", "non-repeatable-read"));
        return switch (anomaly) {
            case "lost-update" -> new LostUpdate(ctx, settings,
                    config.integer("COUNTER_ROWS", 10, 1, 1_000_000),
                    config.oneOf("WRITE_MODE", "read-modify-write",
                            Set.of("read-modify-write", "select-for-update", "atomic-update")));
            case "write-skew" -> new WriteSkew(ctx, settings);
            default -> new NonRepeatableRead(ctx, settings);
        };
    }

    record Settings(int isolationLevel, int thinkMs, boolean retry) {
    }

    /** Выполняет транзакцию, при ошибке сериализации и включённом повторе — повторяет. */
    static boolean transaction(ScenarioContext ctx, Settings settings, Connection connection, String op, Metrics.SqlWork body) {
        while (true) {
            ErrorKind error = ctx.metrics().measure(op, () -> Db.inTransaction(connection, body));
            if (error == null) {
                return true;
            }
            if (!settings.retry() || error != ErrorKind.SERIALIZATION_FAILURE || ctx.stopping()) {
                return false;
            }
            ctx.metrics().event("retries");
        }
    }

    static Connection transactional(ScenarioContext ctx, Settings settings) throws SQLException {
        Connection connection = ctx.db().connect();
        try {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(settings.isolationLevel());
            return connection;
        } catch (SQLException e) {
            ctx.db().close(connection);
            throw e;
        }
    }

    static long single(PreparedStatement statement) throws SQLException {
        try (ResultSet rs = statement.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private record LostUpdate(ScenarioContext ctx, Settings settings, int counters, String writeMode,
                              AtomicLong committed) implements Scenario {
        LostUpdate(ScenarioContext ctx, Settings settings, int counters, String writeMode) {
            this(ctx, settings, counters, writeMode, new AtomicLong());
        }

        @Override
        public void prepare() throws SQLException {
            ctx.db().exec("drop table if exists counters",
                    "create table counters (id int primary key, value bigint not null)");
            ctx.db().fill("insert into counters select g::int, 0 from generate_series(?::bigint, ?::bigint) g",
                    counters, ctx.state());
        }

        @Override
        public ClientSession openSession(int clientIndex) throws SQLException {
            Connection connection = transactional(ctx, settings);
            String select = "select-for-update".equals(writeMode)
                    ? "select value from counters where id = ? for update"
                    : "select value from counters where id = ?";
            PreparedStatement read = connection.prepareStatement(select);
            PreparedStatement write = connection.prepareStatement("update counters set value = ? where id = ?");
            PreparedStatement increment = connection.prepareStatement("update counters set value = value + 1 where id = ?");
            Random random = new Random();
            return new ClientSession() {
                @Override
                public void step() {
                    int id = 1 + random.nextInt(counters);
                    boolean ok = transaction(ctx, settings, connection, "transaction", () -> {
                        if ("atomic-update".equals(writeMode)) {
                            Db.think(settings.thinkMs());
                            increment.setInt(1, id);
                            increment.executeUpdate();
                            return;
                        }
                        read.setInt(1, id);
                        long value = single(read);
                        Db.think(settings.thinkMs());
                        write.setLong(1, value + 1);
                        write.setInt(2, id);
                        write.executeUpdate();
                    });
                    if (ok) {
                        committed.incrementAndGet();
                    }
                }

                @Override
                public void close() {
                    ctx.db().close(connection);
                }
            };
        }

        @Override
        public void verify() throws SQLException {
            long expected = committed.get();
            long actual = ctx.db().queryLong("select coalesce(sum(value), 0) from counters");
            ctx.metrics().check("lost-update", expected, actual, Math.max(0, expected - actual));
        }
    }

    private record WriteSkew(ScenarioContext ctx, Settings settings, AtomicLong tickets) implements Scenario {
        private static final long SHIFTS = 100_000;

        WriteSkew(ScenarioContext ctx, Settings settings) {
            this(ctx, settings, new AtomicLong());
        }

        @Override
        public void prepare() throws SQLException {
            ctx.db().exec("drop table if exists doctors", """
                    create table doctors (
                        shift_id int not null,
                        doctor_id int not null,
                        on_call boolean not null,
                        primary key (shift_id, doctor_id))""");
            // У каждой смены два дежурных врача; инвариант — хотя бы один остаётся на дежурстве
            ctx.db().fill("""
                    insert into doctors
                    select ((g - 1) / 2 + 1)::int, ((g - 1) % 2 + 1)::int, true
                    from generate_series(?::bigint, ?::bigint) g""", SHIFTS * 2, ctx.state());
            ctx.db().exec("vacuum analyze doctors");
        }

        @Override
        public ClientSession openSession(int clientIndex) throws SQLException {
            Connection connection = transactional(ctx, settings);
            PreparedStatement count = connection.prepareStatement(
                    "select count(*) from doctors where shift_id = ? and on_call");
            PreparedStatement leave = connection.prepareStatement(
                    "update doctors set on_call = false where shift_id = ? and doctor_id = ?");
            return new ClientSession() {
                @Override
                public void step() {
                    // Соседние «тикеты» достаются разным клиентам почти одновременно: два врача одной смены
                    long ticket = tickets.getAndIncrement();
                    int shift = (int) (ticket / 2 + 1);
                    int doctor = (int) (ticket % 2 + 1);
                    if (shift > SHIFTS) {
                        Db.think(10);
                        return;
                    }
                    transaction(ctx, settings, connection, "transaction", () -> {
                        count.setInt(1, shift);
                        long onCall = single(count);
                        Db.think(settings.thinkMs());
                        if (onCall >= 2) {
                            leave.setInt(1, shift);
                            leave.setInt(2, doctor);
                            leave.executeUpdate();
                        }
                    });
                }

                @Override
                public void close() {
                    ctx.db().close(connection);
                }
            };
        }

        @Override
        public void verify() throws SQLException {
            long processed = Math.min(SHIFTS, (tickets.get() + 1) / 2);
            long empty = ctx.db().queryLong("""
                    select count(*) from (
                        select shift_id from doctors where shift_id <= %d
                        group by shift_id having not bool_or(on_call)) s""".formatted(processed));
            ctx.metrics().check("write-skew", processed, processed - empty, empty);
        }
    }

    private record NonRepeatableRead(ScenarioContext ctx, Settings settings, AtomicLong checks,
                                     AtomicLong anomalies) implements Scenario {
        private static final int ROWS = 100;

        NonRepeatableRead(ScenarioContext ctx, Settings settings) {
            this(ctx, settings, new AtomicLong(), new AtomicLong());
        }

        @Override
        public void prepare() throws SQLException {
            ctx.db().exec("drop table if exists balances",
                    "create table balances (id int primary key, amount bigint not null)");
            ctx.db().fill("insert into balances select g::int, 0 from generate_series(?::bigint, ?::bigint) g",
                    ROWS, ctx.state());
        }

        /** Чётные клиенты читают сумму дважды в одной транзакции, нечётные меняют строки. */
        @Override
        public ClientSession openSession(int clientIndex) throws SQLException {
            return clientIndex % 2 == 0 ? reader() : writer();
        }

        private ClientSession reader() throws SQLException {
            Connection connection = transactional(ctx, settings);
            PreparedStatement sum = connection.prepareStatement("select coalesce(sum(amount), 0) from balances");
            return new ClientSession() {
                @Override
                public void step() {
                    long[] reads = new long[2];
                    boolean ok = transaction(ctx, settings, connection, "read_transaction", () -> {
                        reads[0] = single(sum);
                        Db.think(settings.thinkMs());
                        reads[1] = single(sum);
                    });
                    if (ok) {
                        checks.incrementAndGet();
                        if (reads[0] != reads[1]) {
                            anomalies.incrementAndGet();
                            ctx.metrics().event("non_repeatable_reads");
                        }
                    }
                }

                @Override
                public void close() {
                    ctx.db().close(connection);
                }
            };
        }

        private ClientSession writer() throws SQLException {
            Connection connection = ctx.db().connect();
            PreparedStatement update = connection.prepareStatement(
                    "update balances set amount = amount + 1 where id = ?");
            Random random = new Random();
            return new ClientSession() {
                @Override
                public void step() {
                    ctx.metrics().measure("write", () -> {
                        update.setInt(1, 1 + random.nextInt(ROWS));
                        update.executeUpdate();
                    });
                }

                @Override
                public void close() {
                    ctx.db().close(connection);
                }
            };
        }

        @Override
        public void verify() {
            long total = checks.get();
            long bad = anomalies.get();
            ctx.metrics().check("non-repeatable-read", total, total - bad, bad);
        }
    }
}
