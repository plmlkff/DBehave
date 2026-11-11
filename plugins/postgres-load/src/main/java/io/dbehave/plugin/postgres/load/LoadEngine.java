package io.dbehave.plugin.postgres.load;

import io.dbehave.plugin.load.Log;
import io.dbehave.plugin.load.RunState;
import io.dbehave.plugin.postgres.load.scenario.Scenario;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Генерация нагрузки: N клиентов, каждый — виртуальный поток со своим соединением.
 * Клиент выполняет итерации подряд (закрытый цикл), пока не закончатся прогрев и нагрузка.
 */
final class LoadEngine {
    private static final long STOP_GRACE_MS = 5_000;

    private final ScenarioContext ctx;

    LoadEngine(ScenarioContext ctx) {
        this.ctx = ctx;
    }

    void run(Scenario scenario) throws InterruptedException {
        Config config = ctx.config();
        RunState state = ctx.state();
        long warmupNanos = config.warmup().toNanos();
        long durationNanos = config.duration().toNanos();

        ctx.metrics().loadStarted();
        state.phase(warmupNanos > 0 ? RunState.Phase.WARMUP : RunState.Phase.LOAD);
        Log.info("Клиенты: %d, прогрев: %d с, нагрузка: %d с".formatted(config.clients(),
                config.warmup().toSeconds(), config.duration().toSeconds()));

        List<Thread> clients = new ArrayList<>();
        for (int i = 0; i < config.clients(); i++) {
            int index = i;
            clients.add(Thread.ofVirtual().name("client-" + i).start(() -> clientLoop(scenario, index)));
        }

        long start = System.nanoTime();
        long loadStart = start + warmupNanos;
        long end = loadStart + durationNanos;
        while (!state.cancelled()) {
            long now = System.nanoTime();
            if (now >= end) {
                break;
            }
            if (state.phase() == RunState.Phase.WARMUP && now >= loadStart) {
                state.phase(RunState.Phase.LOAD);
            }
            state.progress(state.phase() == RunState.Phase.WARMUP
                    ? (int) ((now - start) * 100 / warmupNanos)
                    : (int) ((now - loadStart) * 100 / durationNanos));
            Thread.sleep(200);
        }

        ctx.stop().set(true);
        ctx.metrics().loadFinished();
        state.progress(100);
        stop(clients);
        state.throwIfCancelled();
    }

    private void clientLoop(Scenario scenario, int index) {
        while (!ctx.stopping()) {
            Scenario.ClientSession session = null;
            try {
                session = scenario.openSession(index);
                ctx.metrics().clientOpened();
                while (!ctx.stopping()) {
                    session.step();
                }
            } catch (Metrics.ConnectionLostException e) {
                // ошибка уже посчитана в measure; откроем сессию заново
                pause(200);
            } catch (SQLException e) {
                if (!ctx.stopping()) {
                    ctx.metrics().error("session", ErrorKind.of(e), e);
                    pause(500);
                }
            } catch (RuntimeException e) {
                if (!ctx.stopping()) {
                    Log.error("Клиент " + index + " упал, перезапуск сессии", e);
                    pause(500);
                }
            } finally {
                if (session != null) {
                    session.close();
                    ctx.metrics().clientClosed();
                }
            }
        }
    }

    /** Ждём, пока клиенты доделают итерацию; зависших на блокировках освобождаем, разрывая соединения. */
    private void stop(List<Thread> clients) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STOP_GRACE_MS);
        for (Thread client : clients) {
            long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            client.join(Math.max(1, left));
        }
        if (clients.stream().anyMatch(Thread::isAlive)) {
            Log.warn("Часть клиентов не остановилась за 5 с, разрываем соединения");
            ctx.db().abortAll();
            for (Thread client : clients) {
                client.join(STOP_GRACE_MS);
            }
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
