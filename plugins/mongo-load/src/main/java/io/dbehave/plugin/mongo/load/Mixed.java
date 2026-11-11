package io.dbehave.plugin.mongo.load;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.dbehave.plugin.load.RunState;
import org.bson.Document;

import java.util.Date;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Updates.combine;
import static com.mongodb.client.model.Updates.inc;
import static com.mongodb.client.model.Updates.set;
import static java.util.concurrent.TimeUnit.SECONDS;

final class Mixed implements Scenario {
    private static final int PAYLOAD_BYTES = 64;

    private final MongoCollection<Document> collection;
    private final Metrics metrics;
    private final RunState state;
    private final int rows;
    private final boolean hotspot;
    private final Data.Weights weights;
    private final AtomicLong idSequence;

    Mixed(Config config, MongoDatabase database, Metrics metrics, RunState state) {
        collection = database.getCollection("items");
        this.metrics = metrics;
        this.state = state;
        rows = config.integer("datasetRows");
        hotspot = config.hotspot();
        weights = new Data.Weights(config.integer("readWeight"), config.integer("updateWeight"),
                config.integer("insertWeight"));
        idSequence = new AtomicLong(rows);
    }

    @Override
    public void prepare() {
        var random = new Random(0);
        Data.fill(collection, rows, state, id -> Data.item(id, id % Data.CATEGORIES, PAYLOAD_BYTES, random));
    }

    @Override
    public Runnable client(int index) {
        var random = new Random(index);
        var items = collection.withTimeout(10, SECONDS);
        return () -> {
            switch (weights.pick(random)) {
                case 0 -> metrics.measure("read", () -> items.find(eq("_id", Data.pickId(random, rows, hotspot))).first());
                case 1 -> metrics.measure("update", () -> items.updateOne(eq("_id", Data.pickId(random, rows, hotspot)),
                        combine(inc("value", 1), set("updatedAt", new Date()))));
                default -> metrics.measure("insert", () -> items.insertOne(
                        Data.item(idSequence.incrementAndGet(), random.nextInt(Data.CATEGORIES), PAYLOAD_BYTES, random)));
            }
        };
    }
}
