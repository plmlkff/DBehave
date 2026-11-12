package io.dbehave.plugin.mongo.load;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.dbehave.plugin.load.RunState;
import org.bson.Document;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.function.IntFunction;

import static java.util.concurrent.TimeUnit.SECONDS;

final class Data {
    private static final int SEED_BATCH = 512;
    static final int CATEGORIES = 1000;

    private Data() {
    }

    static void fill(MongoCollection<Document> collection, int rows, RunState state, IntFunction<Document> document) {
        var batch = new ArrayList<Document>(SEED_BATCH);
        for (int first = 1; first <= rows; first += SEED_BATCH) {
            state.throwIfCancelled();
            int last = Math.min(rows, first + SEED_BATCH - 1);
            for (int id = first; id <= last; id++) {
                batch.add(document.apply(id));
            }
            collection.insertMany(batch);
            batch.clear();
            state.progress(last * 100 / rows);
        }
    }

    static Document item(long id, int category, int payloadBytes, Random random) {
        return new Document("_id", id)
                .append("category", category)
                .append("value", random.nextInt(100_000) / 100.0)
                .append("payload", payload(payloadBytes, random));
    }

    static String payload(int bytes, Random random) {
        byte[] data = new byte[(bytes + 1) / 2];
        random.nextBytes(data);
        return HexFormat.of().formatHex(data).substring(0, bytes);
    }

    static long pickId(Random random, int rows, boolean hotspot) {
        int limit = hotspot && random.nextInt(10) < 9 ? rows / 10 : rows;
        return 1L + random.nextInt(limit);
    }

    static Document storageStats(MongoDatabase admin, MongoCollection<Document> collection) {
        admin.withTimeout(120, SECONDS).runCommand(new Document("fsync", 1));
        return collection.aggregate(List.of(new Document("$collStats", new Document("storageStats", new Document()))))
                .first().get("storageStats", Document.class);
    }

    record Weights(int first, int second, int third) {
        Weights {
            if (first + second + third == 0) {
                throw new IllegalArgumentException("Сумма весов операций должна быть больше нуля");
            }
        }

        int pick(Random random) {
            int choice = random.nextInt(first + second + third);
            if (choice < first) {
                return 0;
            }
            return choice < first + second ? 1 : 2;
        }
    }
}
