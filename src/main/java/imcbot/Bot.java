package imcbot;

import com.fasterxml.jackson.databind.JsonNode;
import imcbot.cmi.CmiClient;
import imcbot.cmi.MarketStream;
import imcbot.cmi.Models.Order;
import imcbot.cmi.Models.OrderBook;
import imcbot.cmi.Models.OrderRequest;
import imcbot.cmi.Models.OrderResponse;
import imcbot.cmi.Models.Product;
import imcbot.cmi.Models.Side;
import imcbot.cmi.Models.Trade;
import imcbot.data.EnerginetClient;
import imcbot.data.EnerginetClient.Minute;
import imcbot.strategy.FairValues;
import imcbot.strategy.FairValues.Estimate;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The trading loop. Every cycle it reloads bot.properties, refreshes fair values from Energinet,
 * then for each product: takes clearly mispriced orders, runs the ETF arbitrage, and keeps one
 * bid and one ask resting around fair value, skewed against its position.
 */
final class Bot {

    private static final long PUBLISH_DELAY_MILLIS = 7_000;
    private static final long DATA_RETRY_MILLIS = 2_000;
    private static final long DATA_FAILURE_WAIT_MILLIS = 15_000;
    private static final long SYNC_MILLIS = 30_000;
    private static final long NEWS_MILLIS = 30_000;
    private static final long PRODUCT_CHECK_MILLIS = 60_000;
    private static final long STALE_DATA_MILLIS = 180_000;
    /** When over the limit, pay up to this share of the uncertainty beyond fair to get back inside. */
    private static final double OVER_LIMIT_PAY_UP = 0.05;
    private static final long STREAM_SILENCE_MILLIS = 60_000;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final CmiClient client;
    private final EnerginetClient energinet = new EnerginetClient();
    private final Map<String, OrderBook> books = new ConcurrentHashMap<>();
    private final Map<String, Product> products = new HashMap<>();
    private final EventLog log;
    private Config config;
    private final Map<String, Long> positions = new ConcurrentHashMap<>();
    private final List<OrderResponse> myOrders = new CopyOnWriteArrayList<>();
    private volatile boolean ordersStale = true;
    private Map<String, Estimate> fairs = Map.of();
    private long lastDataFetch;
    private long nextDataFetch;
    private final TreeMap<ZonedDateTime, Minute> history = new TreeMap<>();
    private long lastSync;
    private long lastNewsCheck;
    private long lastProductCheck;
    private long newsPauseUntil;
    private final Set<String> seenNews = new HashSet<>();
    private MarketStream stream;

    Bot(CmiClient client, Config config) throws Exception {
        this.client = client;
        this.config = config;
        this.log = new EventLog();
    }

    void run() throws InterruptedException {
        for (Product product : client.getProducts()) {
            products.put(product.symbol(), product);
        }
        stream = new MarketStream(client, new MarketStream.Listener() {
            @Override
            public void onOrderbook(OrderBook orderbook) {
                books.put(orderbook.product(), orderbook);
            }

            @Override
            public void onTrades(List<Trade> trades) {
                recordTrades(trades);
            }
        });
        stream.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down: cancelling all orders");
            client.cancelAllOrders();
        }));

        while (true) {
            long started = System.currentTimeMillis();
            try {
                cycle();
            } catch (Exception e) {
                System.out.println("Cycle error: " + e);
                log.write("error", "message", String.valueOf(e));
            }
            Thread.sleep(Math.max(200, config.cycleMillis() - (System.currentTimeMillis() - started)));
        }
    }

    private void cycle() throws Exception {
        config = Config.load(Path.of("bot.properties"));
        client.setRequestsPerSecond(config.requestsPerSecond());
        if (stream.millisSinceLastEvent() > STREAM_SILENCE_MILLIS) {
            System.out.println("Market stream silent, reconnecting");
            stream.dropConnection();
        }
        if (config.paused()) {
            client.cancelAllOrders();
            System.out.println(now() + " PAUSED (paused=true in bot.properties)");
            return;
        }
        checkNews();
        if (System.currentTimeMillis() < newsPauseUntil) {
            System.out.println(now() + " NEWS PAUSE, quoting again in "
                    + (newsPauseUntil - System.currentTimeMillis()) / 1000 + "s");
            return;
        }
        checkProducts();
        try {
            refreshFairValues();
        } catch (Exception e) {
            System.out.println(now() + " Energinet fetch failed, keeping last fair values: " + e);
            log.write("error", "message", "energinet: " + e);
        }
        if (fairs.isEmpty()) {
            return;
        }
        if (System.currentTimeMillis() - lastDataFetch > STALE_DATA_MILLIS) {
            System.out.println(now() + " Fair values are stale, pulling all quotes until data returns");
            client.cancelAllOrders();
            myOrders.clear();
            return;
        }
        syncWithExchange();

        // Takes go first for every product: the house's stale quotes are the best edge, and the
        // fastest team to take them wins. Quote updates use the request budget only afterwards.
        for (String product : config.products()) {
            takeIfMispriced(product, positions);
        }
        arbitrage(positions);
        StringBuilder status = new StringBuilder(now());
        for (String product : config.products()) {
            status.append(tradeProduct(product, positions, myOrders));
        }
        System.out.println(status);
    }

    /** Warns when the exchange lists products we have no formula for, or drops ones we trade. */
    private void checkProducts() {
        if (System.currentTimeMillis() - lastProductCheck < PRODUCT_CHECK_MILLIS) {
            return;
        }
        lastProductCheck = System.currentTimeMillis();
        List<String> listed = client.getProducts().stream().map(Product::symbol).toList();
        for (String symbol : listed) {
            if (!config.products().contains(symbol)) {
                System.out.println(now() + " WARNING: new product " + symbol + " is not traded (no formula)");
            }
        }
        for (String symbol : config.products()) {
            if (!listed.contains(symbol)) {
                System.out.println(now() + " WARNING: product " + symbol + " is no longer listed");
            }
        }
    }

    /**
     * Polls the news feed. A new item means prices are about to move, so pull every quote and wait
     * before quoting again. The first check only records what is already there.
     */
    private void checkNews() {
        if (System.currentTimeMillis() - lastNewsCheck < NEWS_MILLIS) {
            return;
        }
        boolean first = lastNewsCheck == 0;
        lastNewsCheck = System.currentTimeMillis();
        boolean fresh = false;
        for (JsonNode item : client.getNews()) {
            if (seenNews.add(item.toString()) && !first) {
                fresh = true;
                System.out.println(now() + " NEWS: " + item);
                log.write("news", "item", item.toString());
            }
        }
        if (fresh) {
            newsPauseUntil = System.currentTimeMillis() + (long) (config.newsPauseSeconds() * 1000);
            client.cancelAllOrders();
            myOrders.clear();
        }
    }

    /**
     * The exchange allows about 1 request a second, so positions come from our own fills on the
     * stream and orders from what we sent, with a full check against the exchange now and then.
     */
    private void syncWithExchange() {
        boolean due = System.currentTimeMillis() - lastSync > SYNC_MILLIS;
        if (due) {
            Map<String, Long> fresh = client.getPositions();
            if (fresh != null) {
                positions.clear();
                positions.putAll(fresh);
            }
            lastSync = System.currentTimeMillis();
        }
        if (due || ordersStale) {
            ordersStale = false;
            List<OrderResponse> fresh = client.getOrders();
            myOrders.clear();
            myOrders.addAll(fresh);
        }
    }

    /**
     * Energinet publishes one row a minute and asks for about one request per update. So: fetch
     * the history once, then only the last few minutes, keep them locally, and after a new minute
     * wait before checking again.
     */
    private void refreshFairValues() throws Exception {
        long nowMillis = System.currentTimeMillis();
        if (nowMillis < nextDataFetch) {
            return;
        }
        nextDataFetch = nowMillis + DATA_FAILURE_WAIT_MILLIS;
        ZonedDateTime now = ZonedDateTime.now(EnerginetClient.DK);
        ZonedDateTime open = now.with(config.sessionOpen());
        ZonedDateTime latestBefore = history.isEmpty() ? null : history.lastKey();
        ZonedDateTime from = latestBefore != null ? latestBefore.minusMinutes(3)
                : now.minusMinutes(70).isBefore(open) ? now.minusMinutes(70) : open;
        for (Minute minute : energinet.fetchSince(from)) {
            history.put(minute.time(), minute);
        }
        if (history.isEmpty()) {
            System.out.println("No Energinet data yet");
            return;
        }
        boolean fresh = latestBefore == null || history.lastKey().isAfter(latestBefore);
        // Energinet publishes each minute about 7-10 s after it ends: check again from second 7 of
        // the next minute, then every couple of seconds until it appears.
        nextDataFetch = fresh ? (nowMillis / 60_000 + 1) * 60_000 + PUBLISH_DELAY_MILLIS : nowMillis + DATA_RETRY_MILLIS;
        List<Minute> minutes = new ArrayList<>(history.values());
        fairs = FairValues.estimate(minutes, now, new FairValues.Inputs(config.sessionOpen(), config.sessionClose(),
                config.typicalMoves(), config.forecasts(), config.forecastWeight(), config.momentumK()));
        lastDataFetch = nowMillis;
        Map<String, Object> summary = new HashMap<>();
        fairs.forEach((p, e) -> summary.put(p, Map.of("fair", e.fair(), "unc", e.uncertainty(), "floor",
                Double.isInfinite(e.floor()) ? "none" : e.floor())));
        log.write("fair", "data_minute", history.lastKey().toLocalTime().toString(), "values", summary);
    }

    /** Our model blended with the market mid, when the spread is narrow enough to trust. */
    private double anchoredFair(String product, Estimate estimate, OrderBook book) {
        Order bestBid = book.bestOtherBid();
        Order bestAsk = book.bestOtherAsk();
        double fair = estimate.fair();
        boolean sensibleSpread = bestBid != null && bestAsk != null
                && bestAsk.price() - bestBid.price() <= 2 * config.typicalMove(product);
        if (sensibleSpread) {
            double mid = (bestBid.price() + bestAsk.price()) / 2;
            fair = config.modelWeight() * fair + (1 - config.modelWeight()) * mid;
        }
        return fair;
    }

    /**
     * Trades immediately against any order clearly on the wrong side of fair value. The position
     * is updated by our fill arriving on the stream, not here, so it isn't counted twice.
     */
    private void takeIfMispriced(String product, Map<String, Long> positions) {
        Estimate estimate = fairs.get(product);
        OrderBook book = books.get(product);
        if (estimate == null || book == null || config.disabled().contains(product)) {
            return;
        }
        double tick = Math.max(book.tickSize(), 1e-9);
        long max = config.maxPosition(product);
        long position = positions.getOrDefault(product, 0L);
        double fair = anchoredFair(product, estimate, book);
        double takeEdge = Math.max(tick, config.takeK() * estimate.uncertainty());
        // Sweep: one order at the worst still-mispriced level takes every level up to it, so a
        // single request (we get about one a second) can clear several stale quotes.
        // Over the limit (after the limit was lowered), get back inside at any price no worse than fair.
        double payUp = OVER_LIMIT_PAY_UP * estimate.uncertainty();
        double buyEdge = position < -max ? -payUp : takeEdge;
        double sellEdge = position > max ? -payUp : takeEdge;
        Sweep buy = sweep(book.sellOrders(), price -> price <= fair - buyEdge);
        Sweep sell = sweep(book.buyOrders(), price -> price >= fair + sellEdge);
        if (buy.volume() > 0 && position < max) {
            long room = position < -max ? -max - position : max - position;
            take(product, Side.BUY, buy.limitPrice(), Math.min(Math.min(config.takeSize(), buy.volume()), room), fair);
        } else if (sell.volume() > 0 && position > -max) {
            long room = position > max ? position - max : max + position;
            take(product, Side.SELL, sell.limitPrice(), Math.min(Math.min(config.takeSize(), sell.volume()), room), fair);
        }
    }

    private record Sweep(double limitPrice, long volume) {
    }

    /** Adds up other people's volume at levels that pass the test, best level first. */
    private static Sweep sweep(List<Order> levels, java.util.function.DoublePredicate mispriced) {
        double limit = Double.NaN;
        long volume = 0;
        for (Order level : levels) {
            if (level.otherVolume() <= 0) {
                continue;
            }
            if (!mispriced.test(level.price())) {
                break;
            }
            limit = level.price();
            volume += level.otherVolume();
        }
        return new Sweep(limit, volume);
    }

    /** Takes mispriced orders, then keeps one bid and one ask resting. Returns a status snippet. */
    private String tradeProduct(String product, Map<String, Long> positions, List<OrderResponse> myOrders) {
        Estimate estimate = fairs.get(product);
        OrderBook book = books.get(product);
        if (estimate == null || book == null) {
            return String.format(" | %s waiting", product);
        }
        if (config.disabled().contains(product)) {
            cancelSide(product, Side.BUY, myOrders);
            cancelSide(product, Side.SELL, myOrders);
            return String.format(" | %s off", product);
        }
        double tick = Math.max(book.tickSize(), 1e-9);
        long max = config.maxPosition(product);
        long position = positions.getOrDefault(product, 0L);
        Order bestBid = book.bestOtherBid();
        Order bestAsk = book.bestOtherAsk();

        double fair = anchoredFair(product, estimate, book);
        double halfSpread = Math.max(tick, config.quoteK() * estimate.uncertainty());

        double skew = config.skewK() * halfSpread * position / (double) max;
        double bid = Math.floor((fair - halfSpread - skew) / tick) * tick;
        double ask = Math.ceil((fair + halfSpread - skew) / tick) * tick;
        if (bestBid != null) {
            bid = Math.min(bid, bestBid.price() + tick);
        }
        if (bestAsk != null) {
            ask = Math.max(ask, bestAsk.price() - tick);
            bid = Math.min(bid, bestAsk.price() - tick);
        }
        if (bestBid != null) {
            ask = Math.max(ask, bestBid.price() + tick);
        }
        ask = Math.max(ask, Math.ceil((estimate.floor() + tick) / tick) * tick);
        Product info = products.get(product);
        if (info != null) {
            bid = Math.max(bid, info.minPrice() == null ? bid : info.minPrice());
            ask = Math.min(ask, info.maxPrice() == null ? ask : info.maxPrice());
        }

        long size = config.quoteSize();
        double keepWithin = config.repriceTicks() * tick;
        quote(product, Side.BUY, position + size <= max ? bid : null, size, keepWithin, myOrders);
        quote(product, Side.SELL, position - size >= -max ? ask : null, size, keepWithin, myOrders);
        return String.format(" | %s fair %.0f±%.0f mkt %s/%s me %.0f/%.0f pos %d", product, fair,
                estimate.uncertainty(), price(bestBid), price(bestAsk), bid, ask, position);
    }

    /** ETF = WIND + CO2 + FLOW at settlement, so a gap between them is locked-in profit. */
    private void arbitrage(Map<String, Long> positions) {
        OrderBook etf = books.get(FairValues.ETF);
        List<OrderBook> parts = new ArrayList<>();
        for (String product : List.of(FairValues.WIND, FairValues.CO2, FairValues.FLOW)) {
            OrderBook book = books.get(product);
            if (book == null) {
                return;
            }
            parts.add(book);
        }
        if (etf == null || config.disabled().contains(FairValues.ETF)) {
            return;
        }

        Order etfBid = etf.bestOtherBid();
        List<Order> partAsks = parts.stream().map(OrderBook::bestOtherAsk).toList();
        if (etfBid != null && !partAsks.contains(null)) {
            double edge = etfBid.price() - partAsks.stream().mapToDouble(Order::price).sum();
            long volume = Math.min(Math.min(config.arbSize(), etfBid.otherVolume()),
                    config.maxPosition(FairValues.ETF) + positions.getOrDefault(FairValues.ETF, 0L));
            for (int i = 0; i < parts.size(); i++) {
                String part = parts.get(i).product();
                volume = Math.min(volume, Math.min(partAsks.get(i).otherVolume(),
                        config.maxPosition(part) - positions.getOrDefault(part, 0L)));
            }
            if (edge >= config.arbMinEdge() && volume > 0) {
                log.write("arb", "direction", "sell ETF buy parts", "edge", edge, "volume", volume);
                long filled = take(FairValues.ETF, Side.SELL, etfBid.price(), volume, Double.NaN);
                for (int i = 0; i < parts.size() && filled > 0; i++) {
                    take(parts.get(i).product(), Side.BUY, partAsks.get(i).price(), filled, Double.NaN);
                }
                return;
            }
        }

        Order etfAsk = etf.bestOtherAsk();
        List<Order> partBids = parts.stream().map(OrderBook::bestOtherBid).toList();
        if (etfAsk != null && !partBids.contains(null)) {
            double edge = partBids.stream().mapToDouble(Order::price).sum() - etfAsk.price();
            long volume = Math.min(Math.min(config.arbSize(), etfAsk.otherVolume()),
                    config.maxPosition(FairValues.ETF) - positions.getOrDefault(FairValues.ETF, 0L));
            for (int i = 0; i < parts.size(); i++) {
                String part = parts.get(i).product();
                volume = Math.min(volume, Math.min(partBids.get(i).otherVolume(),
                        config.maxPosition(part) + positions.getOrDefault(part, 0L)));
            }
            if (edge >= config.arbMinEdge() && volume > 0) {
                log.write("arb", "direction", "buy ETF sell parts", "edge", edge, "volume", volume);
                long filled = take(FairValues.ETF, Side.BUY, etfAsk.price(), volume, Double.NaN);
                for (int i = 0; i < parts.size() && filled > 0; i++) {
                    take(parts.get(i).product(), Side.SELL, partBids.get(i).price(), filled, Double.NaN);
                }
            }
        }
    }

    /** Trades immediately at the given price and cancels whatever doesn't fill. */
    private long take(String product, Side side, double price, long volume, double fair) {
        if (volume <= 0) {
            return 0;
        }
        log.write("take", "product", product, "side", side, "price", price, "volume", volume, "fair", fair,
                "dry_run", config.dryRun());
        System.out.printf("%s TAKE %s %d %s @ %.0f (fair %.1f)%n", now(), side, volume, product, price, fair);
        if (config.dryRun()) {
            return 0;
        }
        return client.sendOrder(new OrderRequest(product, side, price, volume)).map(response -> {
            if (response.remaining() > 0) {
                client.cancelOrder(response.id());
            }
            return Math.round(response.filled());
        }).orElse(0L);
    }

    /**
     * Keeps one resting order near {@code price} on this side, or none if price is null. An
     * existing order within {@code keepWithin} of the target stays, to save requests.
     */
    private void quote(String product, Side side, Double price, long size, double keepWithin,
                       List<OrderResponse> myOrders) {
        List<OrderResponse> existing = myOrders.stream()
                .filter(o -> product.equals(o.product()) && o.side() == side && o.remaining() > 0)
                .toList();
        if (price != null && existing.size() == 1 && Math.abs(existing.get(0).price() - price) < keepWithin) {
            return;
        }
        for (OrderResponse order : existing) {
            if (!config.dryRun()) {
                client.cancelOrder(order.id());
            }
            myOrders.remove(order);
        }
        if (price == null) {
            return;
        }
        log.write("quote", "product", product, "side", side, "price", price, "volume", size,
                "dry_run", config.dryRun());
        if (!config.dryRun()) {
            client.sendOrder(new OrderRequest(product, side, price, size))
                    .filter(response -> response.remaining() > 0)
                    .ifPresent(myOrders::add);
        }
    }

    private void cancelSide(String product, Side side, List<OrderResponse> myOrders) {
        quote(product, side, null, 0, 0, myOrders);
    }

    private void recordTrades(List<Trade> trades) {
        for (Trade trade : trades) {
            boolean bought = client.username().equals(trade.buyer());
            boolean sold = client.username().equals(trade.seller());
            log.write(bought || sold ? "my_fill" : "trade", "product", trade.product(), "price", trade.price(),
                    "volume", trade.volume(), "buyer", trade.buyer(), "seller", trade.seller(),
                    "aggressor", trade.aggressor(), "fair", fairOf(trade.product()));
            if (bought || sold) {
                positions.merge(trade.product(), Math.round(bought ? trade.volume() : -trade.volume()), Long::sum);
                System.out.printf("%s FILL %s %.0f %s @ %.0f vs %s (fair %.1f)%n", now(), bought ? "BOUGHT" : "SOLD",
                        trade.volume(), trade.product(), trade.price(), bought ? trade.seller() : trade.buyer(),
                        fairOf(trade.product()));
            }
        }
    }

    private double fairOf(String product) {
        Estimate estimate = fairs.get(product);
        return estimate == null ? Double.NaN : estimate.fair();
    }

    private static String price(Order order) {
        return order == null ? "-" : String.format("%.0f", order.price());
    }

    private static String now() {
        return LocalTime.now(EnerginetClient.DK).format(CLOCK);
    }
}
