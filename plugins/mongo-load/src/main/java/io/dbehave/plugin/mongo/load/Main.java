package io.dbehave.plugin.mongo.load;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoException;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import io.dbehave.plugin.load.HttpEndpoints;
import io.dbehave.plugin.load.Log;
import io.dbehave.plugin.load.RunState;
import org.bson.Document;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static java.util.concurrent.TimeUnit.SECONDS;

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
            var address = new ServerAddress(env.getOrDefault("DB_HOST", "database"), Config.port(env, "DB_PORT", 27017));
            var settings = MongoClientSettings.builder()
                    .applyToClusterSettings(cluster -> cluster.hosts(List.of(address)).serverSelectionTimeout(2, SECONDS))
                    .applyToConnectionPoolSettings(pool -> pool.maxSize(config.integer("clients") + 1))
                    .applyToSocketSettings(socket -> socket.connectTimeout(2, SECONDS))
                    .retryReads(false).retryWrites(false).build();
            try (var client = MongoClients.create(settings)) {
                var database = client.getDatabase(env.getOrDefault("DB_NAME", "dbehave"));
                var admin = client.getDatabase("admin");
                var scenario = Scenario.create(config, database, metrics, state);
                awaitReady(database, state);

                metrics.runInfo(config.scenario(), config.settingsLabel(), config.paramsLabel());
                state.phase(RunState.Phase.PREPARING);
                scenario.prepare();
                new LoadEngine(config, admin, state, metrics).run(scenario);

                state.phase(RunState.Phase.VERIFYING);
                scenario.verify(admin);
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

    private static void awaitReady(MongoDatabase database, RunState state) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (true) {
            state.throwIfCancelled();
            try {
                database.withTimeout(2, SECONDS).runCommand(new Document("ping", 1));
                return;
            } catch (MongoException unavailable) {
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException("MongoDB не готова к подключению", unavailable);
                }
                Thread.sleep(200);
            }
        }
    }
}
