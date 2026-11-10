package io.dbehave.plugin.postgres.load.scenario;

import io.dbehave.plugin.postgres.load.ScenarioContext;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/** Смешанная нагрузка: чтение, обновление и вставка в заданном соотношении, каждая операция — своя транзакция. */
final class MixedScenario implements Scenario {
    private final ScenarioContext ctx;
    private final long rows;
    private final boolean hotspot;
    private final int fillfactor;
    private final Map<String, Integer> weights = new LinkedHashMap<>();
    private final int totalWeight;

    MixedScenario(ScenarioContext ctx) {
        this.ctx = ctx;
        var config = ctx.config();
        this.rows = config.integer("DATASET_ROWS", 500_000, 1000, 100_000_000);
        this.hotspot = "hotspot".equals(config.oneOf("DISTRIBUTION", "uniform", Scenarios.DISTRIBUTIONS));
        this.fillfactor = config.integer("FILLFACTOR", 100, 10, 100);
        weights.put("read", config.integer("READ_WEIGHT", 70, 0, 1000));
        weights.put("update", config.integer("UPDATE_WEIGHT", 20, 0, 1000));
        weights.put("insert", config.integer("INSERT_WEIGHT", 10, 0, 1000));
        this.totalWeight = Scenarios.total(weights);
    }

    @Override
    public void prepare() throws SQLException {
        ItemsTable.create(ctx, rows, fillfactor, false);
        ctx.db().exec("drop sequence if exists items_new_id",
                "create sequence items_new_id start with " + (rows + 1));
    }

    @Override
    public ClientSession openSession(int clientIndex) throws SQLException {
        Connection connection = ctx.db().connect();
        try {
            PreparedStatement read = connection.prepareStatement(
                    "select id, category, value, payload from items where id = ?");
            PreparedStatement update = connection.prepareStatement(
                    "update items set value = value + 1, updated_at = now() where id = ?");
            PreparedStatement insert = connection.prepareStatement(
                    "insert into items (id, category, value, payload) values (nextval('items_new_id'), ?, ?, ?)");
            Random random = new Random();
            return new ClientSession() {
                @Override
                public void step() {
                    String op = Scenarios.pick(random, weights, totalWeight);
                    ctx.metrics().measure(op, () -> {
                        switch (op) {
                            case "read" -> {
                                read.setLong(1, Scenarios.pickId(random, rows, hotspot));
                                ReadHeavyScenario.consume(read);
                            }
                            case "update" -> {
                                update.setLong(1, Scenarios.pickId(random, rows, hotspot));
                                update.executeUpdate();
                            }
                            default -> {
                                insert.setInt(1, random.nextInt(1000));
                                insert.setBigDecimal(2, BigDecimal.valueOf(random.nextInt(100_000), 2));
                                insert.setString(3, Scenarios.randomText(random, 64));
                                insert.executeUpdate();
                            }
                        }
                    });
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
