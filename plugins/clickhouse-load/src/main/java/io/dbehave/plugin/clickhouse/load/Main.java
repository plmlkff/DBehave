package io.dbehave.plugin.clickhouse.load;

import io.dbehave.plugin.load.HttpEndpoints;
import io.dbehave.plugin.load.Log;
import io.dbehave.plugin.load.RunState;

import java.io.IOException;
import java.util.Map;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws IOException {
        var env = System.getenv();
        var metrics = new Metrics();
        var state = new RunState(metrics);
        var http = HttpEndpoints.start(Config.port(env, "HTTP_PORT", 8080), state, metrics);
        var runner = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            state.cancel();
            runner.interrupt();
            try {
                runner.join(4_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                http.stop();
            }
        }, "shutdown"));
        run(env, state, metrics);
    }

    static void run(Map<String, String> env, RunState state, Metrics metrics) {
        try {
            var config = new Config(env);
            try (var database = new ClickHouse(env, config)) {
                database.awaitReady(state);
                metrics.runInfo("clickhouse", config.scenario(), config.settingsLabel(), config.paramsLabel());
                try (var scenario = Scenario.create(config, database, metrics)) {
                    state.phase(RunState.Phase.PREPARING);
                    scenario.prepare();
                    new LoadEngine(config, database, state, metrics).run(scenario);
                    state.phase(RunState.Phase.VERIFYING);
                    scenario.verify();
                    metrics.storage(database);
                }
                state.done();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            state.failed("Нагрузчик прерван");
        } catch (RuntimeException failure) {
            Log.error("Запуск завершился ошибкой", failure);
            state.failed(failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        }
    }
}
