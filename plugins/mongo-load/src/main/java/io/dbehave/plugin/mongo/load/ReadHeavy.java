package io.dbehave.plugin.mongo.load;

import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Indexes;
import io.dbehave.plugin.load.RunState;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static com.mongodb.ExplainVerbosity.EXECUTION_STATS;
import static com.mongodb.client.model.Aggregates.match;
import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gte;
import static com.mongodb.client.model.Filters.lt;
import static java.util.concurrent.TimeUnit.SECONDS;

final class ReadHeavy implements Scenario {
    private final MongoCollection<Document> collection;
    private final Metrics metrics;
    private final RunState state;
    private final int rows;
    private final int span;
    private final int payloadBytes;
    private final boolean categoryIndex;
    private final boolean hotspot;
    private final boolean allowDiskUse;
    private final Data.Weights weights;

    ReadHeavy(Config config, MongoDatabase database, Metrics metrics, RunState state) {
        collection = database.getCollection("items");
        this.metrics = metrics;
        this.state = state;
        rows = config.integer("datasetRows");
        span = Math.min(rows, config.integer("reportRows"));
        payloadBytes = config.integer("payloadBytes");
        categoryIndex = config.bool("categoryIndex");
        hotspot = config.hotspot();
        allowDiskUse = config.bool("allowDiskUse");
        weights = new Data.Weights(config.integer("pointWeight"), config.integer("rangeWeight"),
                config.integer("reportWeight"));
    }

    @Override
    public void prepare() {
        var random = new Random(0);
        Data.fill(collection, rows, state, id -> Data.item(id, id % Data.CATEGORIES, payloadBytes, random));
        if (categoryIndex) {
            collection.createIndex(Indexes.ascending("category"));
        }
    }

    @Override
    public Runnable client(int index) {
        var random = new Random(index);
        var items = collection.withTimeout(10, SECONDS);
        return () -> {
            switch (weights.pick(random)) {
                case 0 -> metrics.measure("point", () -> items.find(eq("_id", Data.pickId(random, rows, hotspot))).first());
                case 1 -> metrics.measure("range", () -> items.aggregate(List.of(
                        match(eq("category", random.nextInt(Data.CATEGORIES))),
                        new Document("$group", new Document("_id", null).append("count", new Document("$sum", 1))
                                .append("avg", new Document("$avg", "$value"))))).first());
                default -> metrics.measure("report", () -> items.aggregate(report(1 + random.nextInt(rows - span + 1)))
                        .allowDiskUse(allowDiskUse).first());
            }
        };
    }

    @Override
    public void verify(MongoDatabase admin) {
        if (weights.third() == 0) {
            return;
        }
        try {
            var result = collection.withTimeout(30, SECONDS).aggregate(report(1))
                    .allowDiskUse(allowDiskUse).explain(EXECUTION_STATS);
            metrics.reportUsedDisk(usedDisk(result));
        } catch (MongoException error) {
            if (allowDiskUse || !Metrics.kind(error).equals("memory_limit")) {
                throw error;
            }
            metrics.reportUsedDisk(false);
        }
    }

    private List<Bson> report(long from) {
        return List.of(
                match(and(gte("_id", from), lt("_id", from + span))),
                new Document("$setWindowFields", new Document("sortBy", new Document("value", 1).append("payload", 1))
                        .append("output", new Document("rn", new Document("$sum", 1)
                                .append("window", new Document("documents", List.of("unbounded", "current")))))),
                new Document("$group", new Document("_id", null).append("avg", new Document("$avg", "$rn"))));
    }

    private static boolean usedDisk(Object value) {
        return switch (value) {
            case Map<?, ?> document -> Boolean.TRUE.equals(document.get("usedDisk"))
                    || document.values().stream().anyMatch(ReadHeavy::usedDisk);
            case List<?> stages -> stages.stream().anyMatch(ReadHeavy::usedDisk);
            case null, default -> false;
        };
    }
}
