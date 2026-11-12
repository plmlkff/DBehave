package io.dbehave.plugin.mongo.load;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Indexes;
import io.dbehave.plugin.load.RunState;
import org.bson.Document;

import java.util.ArrayList;
import java.util.Random;

import static com.mongodb.ExplainVerbosity.EXECUTION_STATS;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Projections.include;
import static java.util.concurrent.TimeUnit.SECONDS;

final class ArrayIndex implements Scenario {
    private static final int SEARCH_SKUS = 32;
    private static final int MATCH_INTERVAL = 16;

    private final MongoCollection<Document> orders;
    private final Metrics metrics;
    private final RunState state;
    private final int rows;
    private final int itemsPerOrder;
    private final boolean multikeyIndex;

    ArrayIndex(Config config, MongoDatabase database, Metrics metrics, RunState state) {
        orders = database.getCollection("orders");
        this.metrics = metrics;
        this.state = state;
        rows = config.integer("datasetRows");
        itemsPerOrder = config.integer("itemsPerOrder");
        multikeyIndex = config.bool("multikeyIndex");
    }

    @Override
    public void prepare() {
        Data.fill(orders, rows, state, this::order);
        if (multikeyIndex) {
            orders.createIndex(Indexes.ascending("items.sku"));
        }
    }

    @Override
    public Runnable client(int index) {
        var random = new Random(index);
        var collection = orders.withTimeout(10, SECONDS);
        return () -> metrics.measure("lookup", () -> {
            try (var cursor = collection.find(eq("items.sku", sku(random.nextInt(SEARCH_SKUS))))
                    .projection(include("_id")).iterator()) {
                while (cursor.hasNext()) {
                    cursor.next();
                }
            }
        });
    }

    @Override
    public void verify(MongoDatabase admin) {
        var result = orders.find(eq("items.sku", sku(0))).projection(include("_id")).explain(EXECUTION_STATS);
        metrics.arrayExplain(result.get("executionStats", Document.class));
        metrics.storage("orders", Data.storageStats(admin, orders));
    }

    private Document order(int id) {
        var items = new ArrayList<Document>(itemsPerOrder);
        // Доля заказов с каждым поисковым SKU не зависит от длины массива.
        if (id % MATCH_INTERVAL == 0) {
            items.add(new Document("sku", sku(id / MATCH_INTERVAL % SEARCH_SKUS)).append("quantity", 1));
        }
        for (int i = items.size(); i < itemsPerOrder; i++) {
            int number = SEARCH_SKUS + (id * 101 + i) % 65_536;
            items.add(new Document("sku", sku(number)).append("quantity", 1));
        }
        return new Document("_id", id).append("items", items);
    }

    private static String sku(int number) {
        return "catalog/electronics/components/accessories/sku-" + number;
    }
}
