package io.dbehave.plugin.clickhouse.load;

import io.dbehave.plugin.load.RunState;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;

/** One shared HTTP client serves the independent virtual load clients. */
final class ClickHouse implements AutoCloseable {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final URI endpoint;
    private final String user;
    private final String password;
    private final String database;

    ClickHouse(Map<String, String> env, Config config) {
        database = env.getOrDefault("DB_NAME", "dbehave");
        if (!database.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Недопустимое имя базы ClickHouse");
        }
        user = env.getOrDefault("DB_USER", "dbehave");
        password = env.getOrDefault("DB_PASSWORD", "dbehave");
        String host = env.getOrDefault("DB_HOST", "database");
        int port = Config.port(env, "DB_PORT", 8123);
        endpoint = URI.create("http://" + host + ":" + port + "/?database=" + encode(database)
                + "&max_threads=" + config.integer("maxThreads")
                + "&max_memory_usage=" + config.integer("maxMemoryUsageMB") * 1024L * 1024L
                + "&wait_end_of_query=1&buffer_size=1048576&async_insert=0&log_queries=0&use_query_cache=0"
                + "&use_query_condition_cache=0&max_execution_time=30");
    }

    String database() {
        return database;
    }

    Result query(String sql) {
        return query(sql, "");
    }

    Result query(String sql, String data) {
        return query(endpoint, sql, data);
    }

    private Result query(URI target, String sql, String data) {
        var request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(40))
                .header("X-ClickHouse-User", user).header("X-ClickHouse-Key", password)
                .POST(HttpRequest.BodyPublishers.ofString(sql + "\n" + data, UTF_8)).build();
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString(UTF_8));
            if (response.statusCode() != 200) {
                int code = response.headers().firstValue("X-ClickHouse-Exception-Code")
                        .map(Integer::parseInt).orElse(0);
                throw new QueryException(code, response.body());
            }
            String summary = response.headers().firstValue("X-ClickHouse-Summary").orElse("{}");
            return new Result(response.body(), Summary.parse(summary));
        } catch (IOException unavailable) {
            throw new QueryException(-1, "Нет соединения с ClickHouse", unavailable);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new QueryException(-1, "Запрос ClickHouse прерван", interrupted);
        }
    }

    long queryLong(String sql) {
        return Long.parseLong(query(sql).body().strip());
    }

    void awaitReady(RunState state) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (true) {
            state.throwIfCancelled();
            try {
                var initial = URI.create(endpoint.toString().replace("database=" + encode(database), "database=default"));
                query(initial, "SELECT 1", "");
                query(initial, "CREATE DATABASE IF NOT EXISTS " + database, "");
                return;
            } catch (QueryException unavailable) {
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException("ClickHouse не готова к подключению", unavailable);
                }
                Thread.sleep(200);
            }
        }
    }

    @Override
    public void close() {
        client.close();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, UTF_8);
    }

    record Result(String body, Summary summary) {
    }

    record Summary(long readRows, long readBytes, long elapsedNanos) {
        static Summary parse(String json) {
            return new Summary(number(json, "read_rows"), number(json, "read_bytes"), number(json, "elapsed_ns"));
        }

        private static long number(String json, String name) {
            var matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*\"?([0-9]+)").matcher(json);
            return matcher.find() ? Long.parseLong(matcher.group(1)) : 0;
        }
    }

    static final class QueryException extends RuntimeException {
        private final int code;

        QueryException(int code, String message) {
            super(message);
            this.code = code;
        }

        QueryException(int code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        int code() {
            return code;
        }
    }
}
