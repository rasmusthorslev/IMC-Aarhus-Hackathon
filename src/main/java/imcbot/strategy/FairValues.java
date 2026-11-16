package imcbot.strategy;

import imcbot.data.EnerginetClient.Minute;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Fair value per product from Energinet minutes, using the rules sheet: each product pays the
 * average of the last 5 minutes before the close, and WIND_RANGE pays the afternoon's
 * (max - min wind) / 3.
 */
public final class FairValues {

    public static final String WIND = "1_WIND";
    public static final String CO2 = "2_CO2";
    public static final String FLOW = "3_FLOW";
    public static final String ETF = "4_ETF";
    public static final String RANGE = "5_WIND_RANGE";

    private static final int SETTLE_MINUTES = 5;
    private static final int RECENT_MINUTES = 1;
    private static final int MOMENTUM_MINUTES = 5;
    private static final double MIN_UNCERTAINTY_IN_MOVES = 0.3;

    /**
     * @param fair best estimate of the settlement value
     * @param uncertainty rough standard deviation of the settlement around it
     * @param floor the lowest the settlement can still be, or -infinity if unknown
     */
    public record Estimate(double fair, double uncertainty, double floor) {
    }

    /** Settings the model needs, so it doesn't depend on the config file format. */
    public record Inputs(LocalTime open, LocalTime close, Map<String, Double> typicalMoves,
                         Map<String, Double> forecasts, double forecastWeight, double momentumK) {
    }

    private FairValues() {
    }

    public static Map<String, Estimate> estimate(List<Minute> minutes, ZonedDateTime now, Inputs in) {
        Map<String, Estimate> result = new HashMap<>();
        if (minutes.isEmpty()) {
            return result;
        }
        ZonedDateTime close = now.with(in.close());
        double minutesLeft = Math.max(0, Duration.between(now, close).toSeconds() / 60.0);

        result.put(WIND, spot(minutes, m -> m.wind() / 3, WIND, close, minutesLeft, in));
        result.put(CO2, spot(minutes, m -> m.co2() * 10, CO2, close, minutesLeft, in));
        result.put(FLOW, spot(minutes, m -> 1000 + m.exchangeSum() / 3, FLOW, close, minutesLeft, in));

        Estimate w = result.get(WIND);
        Estimate c = result.get(CO2);
        Estimate f = result.get(FLOW);
        result.put(ETF, new Estimate(w.fair() + c.fair() + f.fair(),
                Math.sqrt(sq(w.uncertainty()) + sq(c.uncertainty()) + sq(f.uncertainty())),
                Double.NEGATIVE_INFINITY));

        result.put(RANGE, range(minutes, now.with(in.open()), close, minutesLeft, in));
        return result;
    }

    /** A product paying the average of the last 5 minutes before the close. */
    private static Estimate spot(List<Minute> minutes, ToDoubleFunction<Minute> value, String product,
                                 ZonedDateTime close, double minutesLeft, Inputs in) {
        double current = minutes.subList(Math.max(0, minutes.size() - RECENT_MINUTES), minutes.size())
                .stream().mapToDouble(value).average().orElseThrow();
        // Wind and what follows it trend for hours, so lean towards the last few minutes' move.
        if (minutes.size() > MOMENTUM_MINUTES) {
            double before = value.applyAsDouble(minutes.get(minutes.size() - 1 - MOMENTUM_MINUTES));
            current += in.momentumK() * (current - before);
        }

        ZonedDateTime settleStart = close.minusMinutes(SETTLE_MINUTES);
        double knownSum = 0;
        int known = 0;
        for (Minute m : minutes) {
            if (!m.time().isBefore(settleStart) && m.time().isBefore(close)) {
                knownSum += value.applyAsDouble(m);
                known++;
            }
        }
        int unknown = Math.max(0, SETTLE_MINUTES - known);
        double fair = (knownSum + current * unknown) / SETTLE_MINUTES;

        Double forecast = in.forecasts().get(product);
        if (forecast != null) {
            double weight = in.forecastWeight() * unknown / SETTLE_MINUTES;
            fair = weight * forecast + (1 - weight) * fair;
        }

        double move = in.typicalMoves().get(product);
        double uncertainty = move * Math.sqrt(minutesLeft / 15.0) * unknown / SETTLE_MINUTES;
        return new Estimate(fair, Math.max(uncertainty, MIN_UNCERTAINTY_IN_MOVES * move), Double.NEGATIVE_INFINITY);
    }

    /**
     * Expected final range: the range so far plus the median amount it still grew from this point
     * in the afternoon over the last 47 days. The range so far is a hard floor. A random walk badly
     * underestimates this, because wind trends over hours.
     */
    private static Estimate range(List<Minute> minutes, ZonedDateTime open, ZonedDateTime close,
                                  double minutesLeft, Inputs in) {
        List<Double> session = new ArrayList<>();
        for (Minute m : minutes) {
            if (!m.time().isBefore(open) && m.time().isBefore(close)) {
                session.add(m.wind() / 3);
            }
        }
        double last = minutes.get(minutes.size() - 1).wind() / 3;
        double high = session.stream().mapToDouble(Double::doubleValue).max().orElse(last);
        double low = session.stream().mapToDouble(Double::doubleValue).min().orElse(last);

        double sessionMinutes = Duration.between(open, close).toSeconds() / 60.0;
        double elapsed = Math.max(0, Math.min(sessionMinutes, sessionMinutes - minutesLeft));
        // The table comes from 165-minute afternoons. For a shorter window, look up the same share
        // of the session and scale down: ranges grow roughly with time to the power 0.7.
        double tableMinute = elapsed / sessionMinutes * HISTORY_SESSION_MINUTES;
        double scale = Math.pow(sessionMinutes / HISTORY_SESSION_MINUTES, 0.7);
        double increment = interpolate(tableMinute, MEDIAN_INCREMENT) * scale;
        double spread = interpolate(tableMinute, SD_INCREMENT) * scale;

        double fair = high - low + increment;
        Double forecast = in.forecasts().get(RANGE);
        if (forecast != null) {
            fair = Math.max(high - low, in.forecastWeight() * forecast + (1 - in.forecastWeight()) * fair);
        }
        double move = in.typicalMoves().get(RANGE);
        return new Estimate(fair, Math.max(0.5 * spread, MIN_UNCERTAINTY_IN_MOVES * move), high - low);
    }

    /** Minutes after the open, with the median and spread of the range's further growth (in /3 units). */
    private static final double HISTORY_SESSION_MINUTES = 165;
    private static final double[] CHECKPOINTS = {0, 30, 60, 90, 120, 150, 165};
    private static final double[] MEDIAN_INCREMENT = {154, 117, 79, 55, 25, 5, 0};
    private static final double[] SD_INCREMENT = {165, 161, 145, 120, 61, 22, 0};

    private static double interpolate(double minute, double[] values) {
        for (int i = 1; i < CHECKPOINTS.length; i++) {
            if (minute <= CHECKPOINTS[i]) {
                double share = (minute - CHECKPOINTS[i - 1]) / (CHECKPOINTS[i] - CHECKPOINTS[i - 1]);
                return values[i - 1] + share * (values[i] - values[i - 1]);
            }
        }
        return values[values.length - 1];
    }

    private static double sq(double x) {
        return x * x;
    }
}
