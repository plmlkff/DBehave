package io.dbehave.plugin.postgres.load;

/** Состояние нагрузчика, которое отдаёт {@code /health} и дублируют метрики фазы и прогресса. */
public final class RunState {
    public enum Status { STARTING, RUNNING, DONE, FAILED }

    public enum Phase { STARTING, PREPARING, WARMUP, LOAD, VERIFYING, DONE, FAILED }

    private final Metrics metrics;
    private volatile Phase phase = Phase.STARTING;
    private volatile int progress;
    private volatile String error;
    private volatile boolean cancelled;

    public RunState(Metrics metrics) {
        this.metrics = metrics;
        metrics.phase(phase);
    }

    public synchronized void phase(Phase next) {
        phase = next;
        progress(0);
        metrics.phase(next);
        Log.info("Фаза: " + next);
    }

    public void progress(int percent) {
        progress = Math.max(0, Math.min(100, percent));
        metrics.progress(progress);
    }

    public synchronized void done() {
        phase(Phase.DONE);
        progress(100);
    }

    public synchronized void failed(String message) {
        error = message;
        phase(Phase.FAILED);
    }

    public void cancel() {
        cancelled = true;
    }

    public boolean cancelled() {
        return cancelled;
    }

    public void throwIfCancelled() {
        if (cancelled) {
            throw new CancelledException();
        }
    }

    public Status status() {
        return switch (phase) {
            case STARTING, PREPARING -> Status.STARTING;
            case WARMUP, LOAD, VERIFYING -> Status.RUNNING;
            case DONE -> Status.DONE;
            case FAILED -> Status.FAILED;
        };
    }

    public Phase phase() {
        return phase;
    }

    public int progress() {
        return progress;
    }

    public String error() {
        return error;
    }

    public boolean finished() {
        Status status = status();
        return status == Status.DONE || status == Status.FAILED;
    }

    /** Остановка по SIGTERM. */
    public static final class CancelledException extends RuntimeException {
        public CancelledException() {
            super("Нагрузчик остановлен", null, false, false);
        }
    }
}
