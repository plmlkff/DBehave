package io.dbehave.plugin.postgres.load;

import io.dbehave.plugin.postgres.load.scenario.Scenario;
import io.dbehave.plugin.postgres.load.scenario.Scenarios;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Нагрузчик PostgreSQL. Порядок работы:
 * <ol>
 *     <li>поднимает HTTP с {@code /health} и {@code /metrics};</li>
 *     <li>ждёт готовности БД, готовит схему и данные (status STARTING);</li>
 *     <li>прогрев, нагрузка и проверки (status RUNNING);</li>
 *     <li>status DONE или FAILED; процесс продолжает отвечать, пока воркер не заберёт метрики и не остановит контейнер.</li>
 * </ol>
 */
public final class Main {
    private static final Duration DB_READY_TIMEOUT = Duration.ofMinutes(2);

    private Main() {
    }

    public static void main(String[] args) {
        Metrics metrics = new Metrics();
        RunState state = new RunState(metrics);
        Config config = Config.fromSystem();

        HttpEndpoints http;
        try {
            http = HttpEndpoints.start(config.httpPort(), state, metrics);
        } catch (Exception e) {
            Log.error("Не удалось открыть HTTP-порт", e);
            System.exit(1);
            return;
        }

        Thread runner = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Log.info("Получен сигнал остановки");
            state.cancel();
            try {
                runner.join(15_000);
            } catch (InterruptedException ignored) {
                // выходим как есть
            }
            http.stop();
        }, "shutdown"));

        run(config, state, metrics);
        // Дальше процесс живёт за счёт HTTP-сервера, пока воркер не остановит контейнер
    }

    static void run(Config config, RunState state, Metrics metrics) {
        Db db = new Db(config);
        var ctx = new ScenarioContext(config, db, metrics, state, new AtomicBoolean());
        DbSampler sampler = null;
        try {
            Log.info("Сценарий: " + config.scenario());
            Scenario scenario = Scenarios.create(ctx); // сразу проверяет параметры
            sampler = new DbSampler(db, metrics.registry());
            db.awaitReady(DB_READY_TIMEOUT, state);

            state.phase(RunState.Phase.PREPARING);
            scenario.prepare();

            sampler.start();
            new LoadEngine(ctx).run(scenario);

            state.phase(RunState.Phase.VERIFYING);
            scenario.verify();
            state.done();
            Log.info("Готово");
        } catch (RunState.CancelledException e) {
            state.failed("Нагрузчик остановлен до завершения");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            state.failed("Нагрузчик прерван");
        } catch (Exception e) {
            Log.error("Запуск завершился ошибкой", e);
            state.failed(describe(e));
        } finally {
            ctx.stop().set(true);
            if (sampler != null) {
                sampler.close();
            }
            db.abortAll();
        }
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
