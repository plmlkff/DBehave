package io.dbehave.plugin.postgres.load.scenario;

import io.dbehave.plugin.postgres.load.ScenarioContext;

import java.sql.Connection;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Выбор сценария по переменной {@code SCENARIO} и общие помощники сценариев. */
public final class Scenarios {
    private Scenarios() {
    }

    public static Scenario create(ScenarioContext ctx) {
        String id = ctx.config().scenario();
        return switch (id) {
            case "write-heavy" -> new WriteHeavyScenario(ctx);
            case "read-heavy" -> new ReadHeavyScenario(ctx);
            case "mixed" -> new MixedScenario(ctx);
            case "long-tx-locks" -> new LongTxLocksScenario(ctx);
            case "isolation" -> IsolationScenarios.create(ctx);
            default -> throw new IllegalArgumentException("Неизвестный сценарий: " + id);
        };
    }

    static final Set<String> DISTRIBUTIONS = Set.of("uniform", "hotspot");

    /** Номер строки от 1 до rows. При hotspot 90% обращений приходятся на первые 10% строк. */
    static long pickId(Random random, long rows, boolean hotspot) {
        if (hotspot && random.nextInt(10) < 9) {
            return 1 + random.nextLong(Math.max(1, rows / 10));
        }
        return 1 + random.nextLong(rows);
    }

    /** Взвешенный выбор операции. */
    static String pick(Random random, Map<String, Integer> weights, int total) {
        int roll = random.nextInt(total);
        for (var entry : weights.entrySet()) {
            roll -= entry.getValue();
            if (roll < 0) {
                return entry.getKey();
            }
        }
        throw new IllegalStateException("weights");
    }

    static int total(Map<String, Integer> weights) {
        int total = weights.values().stream().mapToInt(Integer::intValue).sum();
        if (total <= 0) {
            throw new IllegalArgumentException("Сумма весов операций должна быть больше нуля");
        }
        return total;
    }

    static String randomText(Random random, int length) {
        var sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append((char) ('a' + random.nextInt(26)));
        }
        return sb.toString();
    }

    static int isolationLevel(String name) {
        return switch (name) {
            case "READ COMMITTED" -> Connection.TRANSACTION_READ_COMMITTED;
            case "REPEATABLE READ" -> Connection.TRANSACTION_REPEATABLE_READ;
            case "SERIALIZABLE" -> Connection.TRANSACTION_SERIALIZABLE;
            default -> throw new IllegalArgumentException("Неизвестный уровень изоляции: " + name);
        };
    }
}
