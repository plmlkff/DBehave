package io.dbehave.plugin.postgres.load.scenario;

import io.dbehave.plugin.postgres.load.Db;
import io.dbehave.plugin.postgres.load.ScenarioContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Random;
import java.util.Set;

/**
 * Долгая транзакция. Клиент 0 — блокировщик: обновляет первые hotRows строк и держит транзакцию holdSec секунд
 * (idle — просто бездействует, active — выполняет pg_sleep). Остальные клиенты обновляют случайные строки,
 * с вероятностью hotShare — заблокированные. Пока транзакция открыта, VACUUM не может удалить мёртвые версии строк.
 */
final class LongTxLocksScenario implements Scenario {
    private static final int ACCOUNTS = 10_000;

    private final ScenarioContext ctx;
    private final int holdSec;
    private final int pauseSec;
    private final int hotRows;
    private final int hotShare;
    private final boolean activeBlocker;

    LongTxLocksScenario(ScenarioContext ctx) {
        this.ctx = ctx;
        var config = ctx.config();
        this.holdSec = config.integer("HOLD_SEC", 15, 1, 3600);
        this.pauseSec = config.integer("PAUSE_SEC", 5, 0, 3600);
        this.hotRows = config.integer("HOT_ROWS", 100, 1, ACCOUNTS);
        this.hotShare = config.integer("HOT_SHARE", 30, 0, 100);
        this.activeBlocker = "active".equals(config.oneOf("BLOCKER_MODE", "idle", Set.of("idle", "active")));
    }

    @Override
    public void prepare() throws SQLException {
        ctx.db().exec("drop table if exists accounts",
                "create table accounts (id int primary key, balance bigint not null)");
        ctx.db().fill("insert into accounts select g::int, 0 from generate_series(?::bigint, ?::bigint) g",
                ACCOUNTS, ctx.state());
        ctx.db().exec("vacuum analyze accounts");
        ctx.metrics().value("blocker_holding", 0);
    }

    @Override
    public ClientSession openSession(int clientIndex) throws SQLException {
        Connection connection = ctx.db().connect();
        try {
            return clientIndex == 0 ? blocker(connection) : worker(connection);
        } catch (SQLException e) {
            ctx.db().close(connection);
            throw e;
        }
    }

    private ClientSession blocker(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        PreparedStatement lock = connection.prepareStatement("update accounts set balance = balance where id <= ?");
        PreparedStatement sleep = connection.prepareStatement("select pg_sleep(?)");
        return new ClientSession() {
            @Override
            public void step() {
                try {
                    ctx.metrics().measure("blocker_tx", () -> Db.inTransaction(connection, () -> {
                        lock.setInt(1, hotRows);
                        lock.executeUpdate();
                        ctx.metrics().value("blocker_holding", 1);
                        if (activeBlocker) {
                            sleep.setDouble(1, holdSec);
                            ReadHeavyScenario.consume(sleep);
                        } else {
                            ctx.pause(Duration.ofSeconds(holdSec)); // «забытая» транзакция: сессия просто молчит
                        }
                    }));
                } finally {
                    ctx.metrics().value("blocker_holding", 0);
                }
                ctx.pause(Duration.ofSeconds(pauseSec));
            }

            @Override
            public void close() {
                ctx.db().close(connection);
            }
        };
    }

    private ClientSession worker(Connection connection) throws SQLException {
        PreparedStatement update = connection.prepareStatement(
                "update accounts set balance = balance + 1 where id = ?");
        Random random = new Random();
        return new ClientSession() {
            @Override
            public void step() {
                boolean hot = hotRows >= ACCOUNTS || random.nextInt(100) < hotShare;
                int id = hot ? 1 + random.nextInt(hotRows) : hotRows + 1 + random.nextInt(ACCOUNTS - hotRows);
                ctx.metrics().measure("update", () -> {
                    update.setInt(1, id);
                    update.executeUpdate();
                });
            }

            @Override
            public void close() {
                ctx.db().close(connection);
            }
        };
    }
}
