package com.mercatus.order.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.json.JsonMapper;

import static com.mercatus.order.support.OrderFixture.*;

public final class RemoteStub implements AutoCloseable {

    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();
    public final List<String> paths = new CopyOnWriteArrayList<>();
    public final List<String> correlations = new CopyOnWriteArrayList<>();
    public volatile CyclicBarrier catalogBarrier;

    public RemoteStub() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }

        server.setExecutor(executor);
        server.createContext("/api/v1", exchange -> {
            String path = exchange.getRequestURI().toASCIIString();
            paths.add(path);

            String correlation = exchange.getRequestHeaders().getFirst("X-Correlation-Id");

            if (correlation != null) {
                correlations.add(correlation);
            }

            Reply reply = replies.getOrDefault(path, new Reply(404, Map.of(), 0));

            try {
                if (path.equals("/api/v1/skus?code=A") && catalogBarrier != null) {
                    catalogBarrier.await(10, TimeUnit.SECONDS);
                }

                if (reply.delayMillis > 0) {
                    Thread.sleep(reply.delayMillis);
                }

                byte[] bytes = (reply.body instanceof String text ? text : json.writeValueAsString(reply.body))
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(reply.status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (Exception ex) {
                exchange.close();
            } finally {
                exchange.close();
            }
        });

        reset();
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1";
    }

    public void reset() {
        catalogBarrier = null;
        paths.clear();
        correlations.clear();
        replies.clear();

        reply("/customers/" + CUSTOMER, 200, customerBody());
        reply("/skus?code=A", 200, skuBody("A", "42.50"));
        reply("/skus?code=B", 200, skuBody("B", "10.00"));
    }

    public void reply(String path, int status, Object body) {
        delay(path, status, body, 0);
    }

    public void delay(String path, int status, Object body, long millis) {
        replies.put("/api/v1" + path, new Reply(status, body, millis));
    }

    public static Map<String, Object> customerBody() {
        var body = new HashMap<String, Object>();
        body.put("id", CUSTOMER);
        body.put("name", "Example Customer");
        body.put("email", "customer@example.com");
        body.put("status", "ACTIVE");
        body.put("addresses", new ArrayList<>(List.of(address())));
        body.put("phone", "ignored additive field");

        return body;
    }

    public static Map<String, Object> skuBody(String code, String price) {
        return new HashMap<>(Map.of("code", code, "price", new java.math.BigDecimal(price), "currency", "BRL",
                "availableForSale", true, "status", "ACTIVE", "productName", "Example Product",
                "variationAttributes", Map.of("size", "M")));
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }

    private record Reply(int status, Object body, long delayMillis) {
    }
}
