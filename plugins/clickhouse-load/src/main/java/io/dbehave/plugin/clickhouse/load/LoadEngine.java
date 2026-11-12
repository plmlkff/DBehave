package io.dbehave.plugin.clickhouse.load;

import io.dbehave.plugin.load.Log;
import io.dbehave.plugin.load.RunState;

import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;

final class LoadEngine {
    private final Config config;
    private final ClickHouse database;
    private final RunState state;
    private final Metrics metrics;
    private boolean samplingFailureLogged;

    LoadEngine(Config config, ClickHouse database, RunState state, Metrics metrics) {
        this.config = config;
        this.database = database;
        this.state = state;
        this.metrics = metrics;
    }

    void run(Scenario scenario) throws InterruptedException {
        var running = new AtomicBoolean(true);
        var failure = new AtomicReference<RuntimeException>();
        long beforeLoad;
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
                beforeLoad = metrics.successfulOperations();
                phase(RunState.Phase.LOAD, config.integer("durationSec"), failure);
            } finally {
                running.set(false);
                metrics.loadFinished();
                sampler.shutdownNow();
            }
        }
        state.throwIfCancelled();
        throwIfFailed(failure);
        if (metrics.successfulOperations() == beforeLoad) {
            throw new IllegalStateException("За основную фазу нагрузки не выполнено ни одной успешной операции");
        }
        sampleStorage();
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
            metrics.storage(database);
        } catch (ClickHouse.QueryException error) {
            if (!Thread.currentThread().isInterrupted() && !samplingFailureLogged) {
                Log.warn("Не удалось опросить system.parts/system.merges, код " + error.code());
                samplingFailureLogged = true;
            }
        }
    }
}
