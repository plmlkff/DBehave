package io.dbehave.plugin.mongo.load;

import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Indexes;
import org.bson.Document;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;

import static java.util.concurrent.TimeUnit.SECONDS;

final class WriteHeavy implements Scenario {
    private final MongoDatabase database;
    private final MongoCollection<Document> collection;
    private final Metrics metrics;
    private final int batchSize;
    private final int payloadBytes;
    private final int extraIndexes;

    WriteHeavy(Config config, MongoDatabase database, Metrics metrics) {
        this.database = database;
        this.metrics = metrics;
        collection = database.getCollection("events")
                .withWriteConcern(WriteConcern.W1.withJournal(config.bool("journalAcknowledged")));
        batchSize = config.integer("batchSize");
        payloadBytes = config.integer("payloadBytes");
        extraIndexes = config.integer("extraIndexes");
    }

    @Override
    public void prepare() {
        database.createCollection("events");
        var indexes = List.of(Indexes.ascending("clientId"), Indexes.ascending("ts"), Indexes.ascending("kind"),
                Indexes.hashed("payload"), Indexes.ascending("kind", "ts"));
        for (var index : indexes.subList(0, extraIndexes)) {
            collection.createIndex(index);
        }
    }

    @Override
    public Runnable client(int index) {
        var random = new Random(index);
        String payload = Data.payload(payloadBytes, random);
        var events = collection.withTimeout(10, SECONDS);
        return () -> metrics.measure("insert", () -> {
            var batch = new ArrayList<Document>(batchSize);
            var now = new Date();
            for (int i = 0; i < batchSize; i++) {
                batch.add(new Document("clientId", index).append("ts", now)
                        .append("kind", random.nextInt(100)).append("payload", payload));
            }
            events.insertMany(batch);
            metrics.recordsWritten("insert", batchSize);
        });
    }

    @Override
    public void verify(MongoDatabase admin) {
        metrics.storage("events", Data.storageStats(admin, collection));
    }
}
