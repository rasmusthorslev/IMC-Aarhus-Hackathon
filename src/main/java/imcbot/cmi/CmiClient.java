package imcbot.cmi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import imcbot.cmi.Models.OrderRequest;
import imcbot.cmi.Models.OrderResponse;
import imcbot.cmi.Models.Product;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Java port of the kit's BaseBot requests. Every request goes through one rate limiter. */
public final class CmiClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String JSON_TYPE = "application/json; charset=utf-8";

    private final String baseUrl;
    private final String username;
    private final String password;
    private final RateLimiter limiter;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final ObjectMapper json = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private volatile String token;

    public CmiClient(String baseUrl, String username, String password, double requestsPerSecond) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.username = username;
        this.password = password;
        this.limiter = new RateLimiter(requestsPerSecond);
    }

    public String username() {
        return username;
    }

    public void setRequestsPerSecond(double perSecond) {
        limiter.setRate(perSecond);
    }

    ObjectMapper json() {
        return json;
    }

    HttpClient http() {
        return http;
    }

    URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    synchronized String token() {
        if (token == null) {
            token = authenticate();
        }
        return token;
    }

    private synchronized void resetToken() {
        token = null;
    }

    private String authenticate() {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/user/authenticate"))
                .timeout(TIMEOUT)
                .header("Content-Type", JSON_TYPE)
                .POST(BodyPublishers.ofString(write(Map.of("username", username, "password", password))))
                .build();
        HttpResponse<String> response = execute(request);
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Login failed: " + response.statusCode() + " " + response.body());
        }
        return response.headers().firstValue("Authorization")
                .orElseThrow(() -> new IllegalStateException("Login returned no Authorization header"));
    }

    /** @return the exchange's reply, or empty if the order was rejected */
    public Optional<OrderResponse> sendOrder(OrderRequest order) {
        if (order.volume() <= 0) {
            throw new IllegalArgumentException("Order volume must be greater than 0: " + order);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("product", order.product());
        payload.put("side", order.side().name());
        payload.put("price", asNumber(order.price()));
        payload.put("volume", order.volume());
        HttpResponse<String> response = call("POST", "/api/order", payload);
        if (response.statusCode() == 200) {
            return Optional.of(read(response.body(), OrderResponse.class));
        }
        System.out.println("Order failed " + order + ": " + response.statusCode() + " " + response.body());
        return Optional.empty();
    }

    public boolean cancelOrder(String orderId) {
        HttpResponse<String> response = call("DELETE", "/api/order/" + orderId, null);
        return response.statusCode() == 200;
    }

    public List<OrderResponse> getOrders() {
        HttpResponse<String> response = call("GET", "/api/order/current-user", null);
        if (response.statusCode() != 200) {
            System.out.println("Failed to get orders: " + response.statusCode() + " " + response.body());
            return List.of();
        }
        List<OrderResponse> orders = new ArrayList<>();
        for (JsonNode node : readTree(response.body())) {
            orders.add(json.convertValue(node, OrderResponse.class));
        }
        return orders;
    }

    public void cancelAllOrders() {
        for (OrderResponse order : getOrders()) {
            cancelOrder(order.id());
        }
    }

    /** @return net position per product, or null if the request failed */
    public Map<String, Long> getPositions() {
        HttpResponse<String> response = call("GET", "/api/position/current-user", null);
        if (response.statusCode() != 200) {
            System.out.println("Failed to get positions: " + response.statusCode() + " " + response.body());
            return null;
        }
        Map<String, Long> positions = new HashMap<>();
        for (JsonNode node : readTree(response.body())) {
            JsonNode net = node.has("net_position") ? node.get("net_position")
                    : node.has("netPosition") ? node.get("netPosition") : node.get("volume");
            positions.put(node.get("product").asText(), net == null ? 0 : Math.round(net.asDouble()));
        }
        return positions;
    }

    public List<Product> getProducts() {
        HttpResponse<String> response = call("GET", "/api/product", null);
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Failed to get products: " + response.statusCode() + " " + response.body());
        }
        List<Product> products = new ArrayList<>();
        for (JsonNode node : readTree(response.body())) {
            products.add(json.convertValue(node, Product.class));
        }
        return products;
    }

    /** @return the exchange's news items, or an empty list if the request failed */
    public List<JsonNode> getNews() {
        HttpResponse<String> response = call("GET", "/api/news", null);
        List<JsonNode> items = new ArrayList<>();
        if (response.statusCode() == 200) {
            readTree(response.body()).forEach(items::add);
        }
        return items;
    }

    /** For exploring endpoints the kit doesn't wrap. */
    public HttpResponse<String> get(String path) {
        return call("GET", path, null);
    }

    private HttpResponse<String> call(String method, String path, Object payload) {
        for (int attempt = 0; ; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(uri(path))
                    .timeout(TIMEOUT)
                    .header("Content-Type", JSON_TYPE)
                    .header("Authorization", token())
                    .method(method, payload == null ? BodyPublishers.noBody() : BodyPublishers.ofString(write(payload)))
                    .build();
            HttpResponse<String> response = execute(request);
            if (attempt == 0 && (response.statusCode() == 401 || response.statusCode() == 403)) {
                resetToken();
                continue;
            }
            if (attempt < 3 && response.statusCode() == 429) {
                System.out.println("Rate limited (429), backing off");
                sleep(1000L << attempt);
                continue;
            }
            return response;
        }
    }

    private HttpResponse<String> execute(HttpRequest request) {
        limiter.acquire();
        try {
            return http.send(request, BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static Object asNumber(double value) {
        return value == Math.rint(value) ? (Object) (long) value : (Object) value;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private <T> T read(String body, Class<T> type) {
        try {
            return json.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not parse " + body, e);
        }
    }

    JsonNode readTree(String body) {
        try {
            return json.readTree(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not parse " + body, e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
