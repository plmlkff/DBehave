package io.dbehave.plugin.postgres.load.scenario;

import io.dbehave.plugin.postgres.load.ScenarioContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Интенсивное чтение. Операции:
 * {@code point} — выборка по ключу, {@code range} — агрегат по категории (без индекса это полное сканирование),
 * {@code report} — сортировка диапазона в 100 тыс. строк оконной функцией (при малом work_mem уходит на диск).
 */
final class ReadHeavyScenario implements Scenario {
    private static final long REPORT_SPAN = 100_000;

    private final ScenarioContext ctx;
    private final long rows;
    private final boolean categoryIndex;
    private final boolean hotspot;
    private final Map<String, Integer> weights = new LinkedHashMap<>();
    private final int totalWeight;

    ReadHeavyScenario(ScenarioContext ctx) {
        this.ctx = ctx;
        var config = ctx.config();
        this.rows = config.integer("DATASET_ROWS", 1_000_000, 1000, 100_000_000);
        this.categoryIndex = config.bool("CATEGORY_INDEX", false);
        this.hotspot = "hotspot".equals(config.oneOf("DISTRIBUTION", "uniform", Scenarios.DISTRIBUTIONS));
        weights.put("point", config.integer("POINT_WEIGHT", 90, 0, 1000));
        weights.put("range", config.integer("RANGE_WEIGHT", 8, 0, 1000));
        weights.put("report", config.integer("REPORT_WEIGHT", 2, 0, 1000));
        this.totalWeight = Scenarios.total(weights);
    }

    @Override
    public void prepare() throws SQLException {
        ItemsTable.create(ctx, rows, 100, categoryIndex);
    }

    @Override
    public ClientSession openSession(int clientIndex) throws SQLException {
        Connection connection = ctx.db().connect();
        try {
            PreparedStatement point = connection.prepareStatement(
                    "select id, category, value, payload from items where id = ?");
            PreparedStatement range = connection.prepareStatement(
                    "select count(*), avg(value) from items where category = ?");
            PreparedStatement report = connection.prepareStatement("""
                    select avg(rn) from (
                        select row_number() over (order by value, payload) as rn
                        from items where id between ? and ?) s""");
            Random random = new Random();
            long span = Math.min(rows, REPORT_SPAN);
            return new ClientSession() {
                @Override
                public void step() {
                    String op = Scenarios.pick(random, weights, totalWeight);
                    ctx.metrics().measure(op, () -> {
                        switch (op) {
                            case "point" -> {
                                point.setLong(1, Scenarios.pickId(random, rows, hotspot));
                                consume(point);
                            }
                            case "range" -> {
                                range.setInt(1, random.nextInt(1000));
                                consume(range);
                            }
                            default -> {
                                long from = 1 + random.nextLong(Math.max(1, rows - span + 1));
                                report.setLong(1, from);
                                report.setLong(2, from + span - 1);
                                consume(report);
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

    static void consume(PreparedStatement statement) throws SQLException {
        try (ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                rs.getObject(1);
            }
        }
    }
}
