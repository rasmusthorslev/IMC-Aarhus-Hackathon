package imcbot.cmi;

import java.util.List;

/** The exchange's data types, ported from the kit's models.py. */
public final class Models {

    private Models() {
    }

    public enum Side {
        BUY,
        SELL
    }

    public record Product(String symbol, double tickSize, double startingPrice, Double contractSize,
                          Double minPrice, Double maxPrice) {
    }

    public record Trade(String timestamp, String sessionId, String product, String aggressor, double volume,
                        double price, String id, String buyer, String seller) {
    }

    public record OrderRequest(String product, Side side, double price, long volume) {
    }

    public record OrderResponse(String id, String status, String product, Side side, double price, double volume,
                                double filled, String user, String timestamp, String message) {

        public double remaining() {
            return volume - filled;
        }
    }

    /** One price level: total volume there, including ours, and how much of it is ours. */
    public record Order(double price, long volume, long ownVolume) {

        public long otherVolume() {
            return volume - ownVolume;
        }
    }

    /** Buy orders highest first, sell orders lowest first. Both include our own orders. */
    public record OrderBook(String product, double tickSize, List<Order> buyOrders, List<Order> sellOrders) {

        public Order bestOtherBid() {
            return buyOrders.stream().filter(o -> o.otherVolume() > 0).findFirst().orElse(null);
        }

        public Order bestOtherAsk() {
            return sellOrders.stream().filter(o -> o.otherVolume() > 0).findFirst().orElse(null);
        }
    }
}
