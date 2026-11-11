package io.dbehave.plugin.postgres.load;

import io.dbehave.plugin.load.Log;
import io.dbehave.plugin.load.RunState;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/** Подключения к тестовой БД. У каждого клиента своё соединение, пул не нужен. */
public final class Db {
    private final Config config;
    private final Set<Connection> open = ConcurrentHashMap.newKeySet();

    public Db(Config config) {
        this.config = config;
    }

    public Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(config.jdbcUrl(), config.user(), config.password());
        open.add(connection);
        return connection;
    }

    public void close(Connection connection) {
        if (connection == null) {
            return;
        }
        open.remove(connection);
        try {
            connection.close();
        } catch (SQLException ignored) {
            // соединение уже разорвано
        }
    }

    /** Принудительно рвёт все соединения: нужно, если клиент висит на блокировке и не реагирует на остановку. */
    public void abortAll() {
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        for (Connection connection : open) {
            try {
                connection.abort(executor);
            } catch (SQLException ignored) {
                // всё равно закрываем остальные
            }
        }
        open.clear();
        executor.shutdown();
    }

    /** Ждёт, пока БД начнёт принимать подключения: контейнер БД стартует параллельно с нагрузчиком. */
    public void awaitReady(Duration timeout, RunState state) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        SQLException last = null;
        while (System.nanoTime() < deadline) {
            state.throwIfCancelled();
            try (Connection connection = DriverManager.getConnection(config.jdbcUrl(), config.user(), config.password());
                 Statement statement = connection.createStatement()) {
                statement.execute("select 1");
                Log.info("БД готова: " + config.jdbcUrl());
                return;
            } catch (SQLException e) {
                last = e;
                Thread.sleep(500);
            }
        }
        throw new IllegalStateException("БД не ответила за " + timeout.toSeconds() + " с: "
                + (last == null ? "нет подключения" : last.getMessage()), last);
    }

    /** Выполнить один или несколько SQL-операторов в отдельном соединении с autocommit. */
    public void exec(String... sql) throws SQLException {
        Connection connection = connect();
        try (Statement statement = connection.createStatement()) {
            for (String s : sql) {
                statement.execute(s);
            }
        } finally {
            close(connection);
        }
    }

    public long queryLong(String sql) throws SQLException {
        Connection connection = connect();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } finally {
            close(connection);
        }
    }

    public String queryText(String sql) throws SQLException {
        Connection connection = connect();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        } finally {
            close(connection);
        }
    }

    /**
     * Заполняет таблицу пачками {@code insert ... select ... from generate_series(?, ?) g}, чтобы показывать прогресс.
     *
     * @param insertSql запрос с двумя параметрами: первый и последний номер строки пачки
     */
    public void fill(String insertSql, long rows, RunState state) throws SQLException {
        final long chunk = 100_000;
        Connection connection = connect();
        try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
            for (long from = 1; from <= rows; from += chunk) {
                state.throwIfCancelled();
                long to = Math.min(rows, from + chunk - 1);
                statement.setLong(1, from);
                statement.setLong(2, to);
                statement.executeUpdate();
                state.progress((int) (to * 100 / rows));
            }
        } finally {
            close(connection);
        }
        Log.info("Загружено строк: " + rows);
    }

    /** Транзакция с откатом при ошибке. Соединение должно быть в режиме autocommit=false. */
    public static void inTransaction(Connection connection, Metrics.SqlWork work) throws SQLException {
        try {
            work.run();
            connection.commit();
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // соединение могло быть разорвано, исходная ошибка важнее
            }
            throw e;
        }
    }

    /** Пауза внутри транзакции: расширяет окно гонки в сценариях блокировок и изоляции. */
    public static void think(int millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
