package io.dbehave.plugin.postgres.load;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/** Всё, что нужно сценарию: параметры, подключения, метрики, состояние и флаг остановки нагрузки. */
public record ScenarioContext(Config config, Db db, Metrics metrics, RunState state, AtomicBoolean stop) {
    public boolean stopping() {
        return stop.get() || state.cancelled();
    }

    /** Пауза, которая прерывается остановкой нагрузки. */
    public void pause(Duration duration) {
        long deadline = System.nanoTime() + duration.toNanos();
        while (!stopping()) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return;
            }
            Db.think((int) Math.min(100, Math.max(1, left / 1_000_000)));
        }
    }
}
