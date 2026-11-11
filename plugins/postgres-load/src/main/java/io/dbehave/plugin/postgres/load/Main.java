package io.dbehave.plugin.postgres.load;

import io.dbehave.plugin.load.HttpEndpoints;
import io.dbehave.plugin.load.Log;
import io.dbehave.plugin.load.RunState;
import io.dbehave.plugin.postgres.load.scenario.Scenario;
import io.dbehave.plugin.postgres.load.scenario.Scenarios;

import java.sql.SQLException;
import java.time.Duration;
import java.util.stream.Collectors;
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
            publishRunInfo(config, db, metrics);

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

    /**
     * Метрика dbehave_run_info: какие настройки этого запуска отличаются от умолчаний PostgreSQL.
     * По ней дашборд подписывает запуски на общих графиках, например «work_mem=32MB».
     */
    static void publishRunInfo(Config config, Db db, Metrics metrics) throws SQLException {
        // boot_val — встроенное значение; shared_buffers initdb переопределяет на 128MB (16384 страницы по 8 кБ)
        String settings = db.queryText("""
                select coalesce(string_agg(name || '=' || current_setting(name), ', ' order by name), '')
                from pg_settings
                where source = 'command line'
                  and setting is distinct from case name when 'shared_buffers' then '16384' else boot_val end""");
        String params = config.loadParams().entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));
        String changedSettings = settings.isEmpty() ? "по умолчанию" : settings;
        metrics.runInfo("postgres", config.scenario(), changedSettings, params);
        Log.info("Изменённые настройки PostgreSQL: " + changedSettings);
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
