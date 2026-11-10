package io.dbehave.plugin.postgres.load.scenario;

import io.dbehave.plugin.postgres.load.Db;
import io.dbehave.plugin.postgres.load.ScenarioContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Интенсивная запись: каждая итерация — одна транзакция с INSERT на batchSize строк. */
final class WriteHeavyScenario implements Scenario {
    private static final List<String> EXTRA_INDEXES = List.of(
            "create index on events (client_id)",
            "create index on events (ts)",
            "create index on events (kind)",
            "create index on events (md5(payload))",
            "create index on events (kind, ts)");

    private final ScenarioContext ctx;
    private final int batchSize;
    private final int extraIndexes;
    private final int payloadBytes;

    WriteHeavyScenario(ScenarioContext ctx) {
        this.ctx = ctx;
        this.batchSize = ctx.config().integer("BATCH_SIZE", 1, 1, 1000);
        this.extraIndexes = ctx.config().integer("EXTRA_INDEXES", 0, 0, EXTRA_INDEXES.size());
        this.payloadBytes = ctx.config().integer("PAYLOAD_BYTES", 200, 1, 100_000);
    }

    @Override
    public void prepare() throws SQLException {
        ctx.db().exec("drop table if exists events", """
                create table events (
                    id bigserial primary key,
                    client_id int not null,
                    ts timestamptz not null default now(),
                    kind int not null,
                    payload text not null)""");
        for (int i = 0; i < extraIndexes; i++) {
            ctx.db().exec(EXTRA_INDEXES.get(i));
        }
        ctx.state().progress(100);
    }

    @Override
    public ClientSession openSession(int clientIndex) throws SQLException {
        Connection connection = ctx.db().connect();
        try {
            connection.setAutoCommit(false);
            String values = String.join(", ", Collections.nCopies(batchSize, "(?, ?, ?)"));
            PreparedStatement insert = connection.prepareStatement(
                    "insert into events (client_id, kind, payload) values " + values);
            Random random = new Random();
            String payload = Scenarios.randomText(random, payloadBytes);
            return new ClientSession() {
                @Override
                public void step() {
                    ctx.metrics().measure("insert", () -> Db.inTransaction(connection, () -> {
                        for (int row = 0; row < batchSize; row++) {
                            insert.setInt(row * 3 + 1, clientIndex);
                            insert.setInt(row * 3 + 2, random.nextInt(100));
                            insert.setString(row * 3 + 3, payload);
                        }
                        insert.executeUpdate();
                    }));
                }

                @Override
                public void close() {
                    ctx.db().close(connection);
                }
            };
        } catch (SQLException e) {
            ctx.db().close(connection);
            throw e;
        }
    }
}
