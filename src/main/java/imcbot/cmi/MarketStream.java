package imcbot.cmi;

import com.fasterxml.jackson.databind.JsonNode;
import imcbot.cmi.Models.Order;
import imcbot.cmi.Models.OrderBook;
import imcbot.cmi.Models.Trade;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Java port of the kit's SSE thread: reads the market stream and reconnects when it drops. */
public final class MarketStream {

    public interface Listener {
        void onOrderbook(OrderBook orderbook);

        void onTrades(List<Trade> trades);
    }

    private final CmiClient client;
    private final Listener listener;
    private final Thread thread;
    private volatile boolean closed;
    private volatile Stream<String> current;
    private volatile long lastEventMillis = System.currentTimeMillis();

    public MarketStream(CmiClient client, Listener listener) {
        this.client = client;
        this.listener = listener;
        this.thread = new Thread(this::run, "market-stream");
        this.thread.setDaemon(true);
    }

    public void start() {
        thread.start();
    }

    public void close() {
        closed = true;
        dropConnection();
    }

    public long millisSinceLastEvent() {
        return System.currentTimeMillis() - lastEventMillis;
    }

    /** Forces a reconnect, for when the stream has gone silent. */
    public void dropConnection() {
        Stream<String> stream = current;
        if (stream != null) {
            stream.close();
        }
    }

    private void run() {
        while (!closed) {
            try {
                consume();
            } catch (Exception e) {
                if (!closed) {
                    System.out.println("Market stream error, reconnecting: " + e);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException interrupted) {
                        return;
                    }
                }
            }
        }
    }

    private void consume() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(client.uri("/api/market/stream"))
                .header("Authorization", client.token())
                .header("Accept", "text/event-stream; charset=utf-8")
                .GET()
                .build();
        HttpResponse<Stream<String>> response = client.http().send(request, BodyHandlers.ofLines());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("Market stream returned " + response.statusCode());
        }
        lastEventMillis = System.currentTimeMillis();
        String event = null;
        StringBuilder data = new StringBuilder();
        try (Stream<String> lines = response.body()) {
            current = lines;
            Iterator<String> it = lines.iterator();
            while (!closed && it.hasNext()) {
                String line = it.next();
                lastEventMillis = System.currentTimeMillis();
                if (line.isEmpty()) {
                    dispatch(event, data.toString());
                    event = null;
                    data.setLength(0);
                } else if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(line.substring(5).trim());
                }
            }
        } finally {
            current = null;
        }
    }

    private void dispatch(String event, String data) {
        if (event == null || data.isEmpty()) {
            return;
        }
        try {
            if (event.equals("order")) {
                listener.onOrderbook(parseBook(client.readTree(data)));
            } else if (event.equals("trade")) {
                List<Trade> trades = new ArrayList<>();
                JsonNode node = client.readTree(data);
                for (JsonNode trade : node.isArray() ? node : List.of(node)) {
                    trades.add(client.json().convertValue(trade, Trade.class));
                }
                listener.onTrades(trades);
            }
        } catch (RuntimeException e) {
            System.out.println("Could not handle " + event + " event: " + e);
        }
    }

    private static OrderBook parseBook(JsonNode node) {
        List<Order> buys = levels(node.get("buyOrders"));
        List<Order> sells = levels(node.get("sellOrders"));
        buys.sort(Comparator.comparingDouble(Order::price).reversed());
        sells.sort(Comparator.comparingDouble(Order::price));
        return new OrderBook(node.get("productsymbol").asText(), node.get("tickSize").asDouble(), buys, sells);
    }

    private static List<Order> levels(JsonNode side) {
        List<Order> levels = new ArrayList<>();
        if (side == null) {
            return levels;
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = side.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> level = it.next();
            levels.add(new Order(Double.parseDouble(level.getKey()),
                    level.getValue().path("marketVolume").asLong(),
                    level.getValue().path("userVolume").asLong()));
        }
        return levels;
    }
}
