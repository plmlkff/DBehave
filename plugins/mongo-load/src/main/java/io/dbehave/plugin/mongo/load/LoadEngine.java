package io.dbehave.plugin.mongo.load;

import com.mongodb.MongoException;
import com.mongodb.client.MongoDatabase;
import io.dbehave.plugin.load.Log;
import io.dbehave.plugin.load.RunState;
import org.bson.Document;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;

final class LoadEngine {
    private final Config config;
    private final MongoDatabase admin;
    private final RunState state;
    private final Metrics metrics;
    private final boolean sampleJournal;
    private long previousPagesRead = -1;
    private long previousJournalBytes = -1;
    private boolean samplingFailed;

    LoadEngine(Config config, MongoDatabase admin, RunState state, Metrics metrics) {
        this.config = config;
        this.admin = admin.withTimeout(2, SECONDS);
        this.state = state;
        this.metrics = metrics;
        sampleJournal = config.scenario().equals("write-heavy");
    }

    void run(Scenario scenario) throws InterruptedException {
        var running = new AtomicBoolean(true);
        var failure = new AtomicReference<RuntimeException>();
        sampleStorage();
        try (var clients = Executors.newVirtualThreadPerTaskExecutor();
             var sampler = Executors.newSingleThreadScheduledExecutor()) {
            try {
                sampler.scheduleAtFixedRate(this::sampleStorage, 1, 1, SECONDS);
                metrics.loadStarted();
                state.phase(config.integer("warmupSec") > 0 ? RunState.Phase.WARMUP : RunState.Phase.LOAD);
                for (int i = 0; i < config.integer("clients"); i++) {
                    var action = scenario.client(i);
                    clients.execute(() -> {
                        metrics.clientOpened();
                        try {
                            while (running.get() && !state.cancelled()) {
                                action.run();
                            }
                        } catch (RuntimeException error) {
                            failure.compareAndSet(null, error);
                        } finally {
                            metrics.clientClosed();
                        }
                    });
                }
                if (config.integer("warmupSec") > 0) {
                    phase(RunState.Phase.WARMUP, config.integer("warmupSec"), failure);
                }
                phase(RunState.Phase.LOAD, config.integer("durationSec"), failure);
            } finally {
                running.set(false);
                metrics.loadFinished();
                sampler.shutdownNow();
            }
        }
        state.throwIfCancelled();
        throwIfFailed(failure);
        if (sampleJournal) {
            sampleStorage();
        }
    }

    private void phase(RunState.Phase phase, int seconds, AtomicReference<RuntimeException> failure)
            throws InterruptedException {
        if (state.phase() != phase) {
            state.phase(phase);
        }
        long start = System.nanoTime();
        long duration = SECONDS.toNanos(seconds);
        for (long elapsed = 0; elapsed < duration; elapsed = System.nanoTime() - start) {
            state.throwIfCancelled();
            throwIfFailed(failure);
            state.progress((int) (elapsed * 100 / duration));
            Thread.sleep(100);
        }
        state.progress(100);
    }

    private static void throwIfFailed(AtomicReference<RuntimeException> failure) {
        var error = failure.get();
        if (error != null) {
            throw error;
        }
    }

    private void sampleStorage() {
        try {
            var result = admin.runCommand(new Document("serverStatus", 1));
            var cache = result.getEmbedded(List.of("wiredTiger", "cache"), Document.class);
            long pagesRead = cache.get("pages read into cache", Number.class).longValue();
            metrics.cache(cache, previousPagesRead < 0 ? 0 : Math.max(0, pagesRead - previousPagesRead));
            previousPagesRead = pagesRead;
            if (sampleJournal) {
                long bytes = result.getEmbedded(List.of("wiredTiger", "log", "log bytes written"), Number.class).longValue();
                if (previousJournalBytes >= 0) {
                    metrics.journalWritten(Math.max(0, bytes - previousJournalBytes));
                }
                previousJournalBytes = bytes;
            }
        } catch (MongoException error) {
            if (!samplingFailed) {
                Log.warn("Не удалось опросить WiredTiger, код " + error.getCode());
                samplingFailed = true;
            }
        }
    }
}
