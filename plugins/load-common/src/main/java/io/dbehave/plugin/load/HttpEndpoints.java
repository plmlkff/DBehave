package io.dbehave.plugin.load;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.prometheus.metrics.expositionformats.PrometheusTextFormatWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HTTP для воркера: {@code GET /health} — состояние в JSON, {@code GET /metrics} — метрики Prometheus.
 * Сервер работает, пока процесс не остановят, в том числе после DONE и FAILED.
 */
public final class HttpEndpoints {
    private final HttpServer server;
    private final ExecutorService executor;

    private HttpEndpoints(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    public static HttpEndpoints start(int port, RunState state, LoadMetrics metrics) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        var executor = Executors.newFixedThreadPool(2);
        server.setExecutor(executor);
        var writer = PrometheusTextFormatWriter.create();
        server.createContext("/health", exchange -> respond(exchange, "application/json", healthJson(state)));
        server.createContext("/metrics", exchange -> {
            var out = new ByteArrayOutputStream();
            writer.write(out, metrics.registry().scrape());
            respond(exchange, writer.getContentType(), out.toByteArray());
        });
        server.start();
        Log.info("HTTP: /health и /metrics на порту " + port);
        return new HttpEndpoints(server, executor);
    }

    public void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    public static byte[] healthJson(RunState state) {
        String error = state.error() == null ? "null" : "\"" + escape(state.error()) + "\"";
        return """
                {"status":"%s","phase":"%s","progress":%d,"error":%s}"""
                .formatted(state.status(), state.phase(), state.progress(), error)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, String contentType, byte[] body) throws IOException {
        try (exchange) {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    private static String escape(String text) {
        var sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append("\\u%04x".formatted((int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
