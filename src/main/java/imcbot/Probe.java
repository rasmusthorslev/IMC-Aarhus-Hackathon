package imcbot;

import imcbot.cmi.CmiClient;
import imcbot.cmi.MarketStream;
import imcbot.cmi.Models.OrderBook;
import imcbot.cmi.Models.Trade;
import imcbot.data.EnerginetClient;
import imcbot.data.EnerginetClient.Minute;
import java.net.http.HttpResponse;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Read-only look at the exchange and the data: sends no orders. */
final class Probe {

    private Probe() {
    }

    static void run(CmiClient client, long seconds) throws Exception {
        System.out.println("Products: " + client.getProducts());
        System.out.println("Positions: " + client.getPositions());
        System.out.println("Orders: " + client.getOrders());
        for (String path : List.of("/api/profit/current-user", "/api/news", "/api/message", "/api/announcement",
                "/api/session", "/api/round", "/api/trade")) {
            HttpResponse<String> response = client.get(path);
            String body = response.body();
            System.out.println(path + " -> " + response.statusCode() + " "
                    + (body.length() > 600 ? body.substring(0, 600) + "..." : body));
        }

        List<Minute> minutes = new EnerginetClient().fetchSince(ZonedDateTime.now(EnerginetClient.DK).minusMinutes(20));
        System.out.println("Energinet minutes: " + minutes.size());
        for (Minute m : minutes.subList(Math.max(0, minutes.size() - 5), minutes.size())) {
            System.out.printf("  %s wind %.0f (/3 = %.1f)  co2 %.0f (x10 = %.0f)  exchange %.0f (1000+/3 = %.1f)%n",
                    m.time().toLocalTime(), m.wind(), m.wind() / 3, m.co2(), m.co2() * 10,
                    m.exchangeSum(), 1000 + m.exchangeSum() / 3);
        }

        Map<String, OrderBook> books = new ConcurrentHashMap<>();
        MarketStream stream = new MarketStream(client, new MarketStream.Listener() {
            @Override
            public void onOrderbook(OrderBook orderbook) {
                books.put(orderbook.product(), orderbook);
            }

            @Override
            public void onTrades(List<Trade> trades) {
                trades.forEach(t -> System.out.printf("TRADE %s %.0f @ %.1f buyer %s seller %s aggressor %s%n",
                        t.product(), t.volume(), t.price(), t.buyer(), t.seller(), t.aggressor()));
            }
        });
        stream.start();
        Thread.sleep(seconds * 1000);
        stream.close();
        books.values().forEach(b -> System.out.printf("BOOK %s tick %.2f  bids %s  asks %s%n", b.product(), b.tickSize(),
                b.buyOrders().subList(0, Math.min(4, b.buyOrders().size())),
                b.sellOrders().subList(0, Math.min(4, b.sellOrders().size()))));
    }
}
