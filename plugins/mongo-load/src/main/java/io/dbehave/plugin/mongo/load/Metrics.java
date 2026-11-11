package io.dbehave.plugin.mongo.load;

import com.mongodb.MongoException;
import com.mongodb.MongoOperationTimeoutException;
import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import io.dbehave.plugin.load.LoadMetrics;
import io.dbehave.plugin.load.Log;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;
import org.bson.Document;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class Metrics extends LoadMetrics {
    private final Set<String> loggedErrors = ConcurrentHashMap.newKeySet();
    private final Gauge cacheBytes = gauge("dbehave_mongo_cache_bytes", "Объём кеша WiredTiger", "kind");
    private final Counter cacheReads = Counter.builder()
            .name("dbehave_mongo_cache_pages_read_total")
            .help("Страницы, прочитанные в кеш WiredTiger за время нагрузки")
            .register(registry());
    private final Gauge collectionBytes = gauge("dbehave_mongo_collection_bytes", "Размер коллекции", "kind");
    private final Gauge storageBytesPerDocument = gauge("dbehave_mongo_storage_bytes_per_document",
            "Размер данных на диске в пересчёте на документ");
    private final Gauge explain = gauge("dbehave_mongo_query_explain", "Статистика контрольного запроса по SKU", "kind");
    private final Gauge reportUsedDisk = gauge("dbehave_mongo_report_used_disk",
            "Контрольный отчёт использовал временные файлы, 1 — да, 0 — нет");

    void measure(String op, Runnable work) {
        long start = System.nanoTime();
        try {
            work.run();
            recordOperation(op, (System.nanoTime() - start) / 1e9);
        } catch (MongoException error) {
            String kind = kind(error);
            recordError(op, kind);
            if (loggedErrors.add(op + "/" + kind)) {
                Log.warn("Первая ошибка " + kind + " в операции " + op + ", код " + error.getCode());
            }
        }
    }

    static String kind(MongoException error) {
        if (error instanceof MongoSocketException || error instanceof MongoTimeoutException) {
            return "connection_error";
        }
        if (error instanceof MongoOperationTimeoutException) {
            return "operation_timeout";
        }
        return switch (error.getCode()) {
            case 292, 16819 -> "memory_limit";
            case 50 -> "operation_timeout";
            case 112 -> "write_conflict";
            case 11000 -> "duplicate_key";
            default -> "other";
        };
    }

    void cache(Document cache, long pagesRead) {
        cacheBytes.labelValues("used").set(value(cache, "bytes currently in the cache"));
        cacheBytes.labelValues("limit").set(value(cache, "maximum bytes configured"));
        cacheReads.inc(pagesRead);
    }

    void storage(Document stats) {
        double stored = value(stats, "storageSize");
        collectionBytes.labelValues("data").set(stored);
        collectionBytes.labelValues("indexes").set(value(stats, "totalIndexSize"));
        double count = value(stats, "count");
        if (count > 0) {
            storageBytesPerDocument.set(stored / count);
        }
    }

    void arrayExplain(Document stats) {
        explain.labelValues("documents").set(value(stats, "totalDocsExamined"));
        explain.labelValues("keys").set(value(stats, "totalKeysExamined"));
        explain.labelValues("returned").set(value(stats, "nReturned"));
    }

    void reportUsedDisk(boolean used) {
        reportUsedDisk.set(used ? 1 : 0);
    }

    private Gauge gauge(String name, String help, String... labels) {
        return Gauge.builder().name(name).help(help).labelNames(labels).register(registry());
    }

    private static double value(Document document, String key) {
        return document.get(key, Number.class).doubleValue();
    }
}
