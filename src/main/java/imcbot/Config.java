package imcbot;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Settings from bot.properties. The bot rereads the file while running, so edits apply live. */
public record Config(Properties props) {

    public static Config load(Path file) throws IOException {
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            props.load(reader);
        }
        return new Config(props);
    }

    private String text(String key) {
        String value = props.getProperty(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing setting in bot.properties: " + key);
        }
        return value.trim();
    }

    private double number(String key) {
        return Double.parseDouble(text(key));
    }

    public String url() {
        return secret("CMI_URL");
    }

    public String username() {
        return secret("CMI_USERNAME");
    }

    public String password() {
        return secret("CMI_PASSWORD");
    }

    private static final Path ENV_FILE = Path.of(".env");
    private static Map<String, String> envFile;

    /** Login details come from environment variables, or from a local .env file kept out of git. */
    private static String secret(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            value = readEnvFile().get(name);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing " + name + ": set it in the environment or in .env (see .env.example)");
        }
        return value.trim();
    }

    private static synchronized Map<String, String> readEnvFile() {
        if (envFile == null) {
            envFile = new HashMap<>();
            if (Files.exists(ENV_FILE)) {
                try {
                    for (String line : Files.readAllLines(ENV_FILE)) {
                        String trimmed = line.trim();
                        int eq = trimmed.indexOf('=');
                        if (!trimmed.isEmpty() && !trimmed.startsWith("#") && eq > 0) {
                            envFile.put(trimmed.substring(0, eq).trim(),
                                    trimmed.substring(eq + 1).trim().replaceAll("^\"|\"$", ""));
                        }
                    }
                } catch (IOException e) {
                    throw new IllegalStateException("Could not read .env", e);
                }
            }
        }
        return envFile;
    }

    public double requestsPerSecond() {
        return number("requests_per_second");
    }

    public boolean dryRun() {
        return Boolean.parseBoolean(text("dry_run"));
    }

    public boolean paused() {
        return Boolean.parseBoolean(text("paused"));
    }

    public long cycleMillis() {
        return (long) number("cycle_ms");
    }

    public LocalTime sessionOpen() {
        return LocalTime.parse(text("session_open"));
    }

    public LocalTime sessionClose() {
        return LocalTime.parse(text("session_close"));
    }

    public List<String> products() {
        return Arrays.stream(text("products").split(",")).map(String::trim).toList();
    }

    public long maxPosition() {
        return (long) number("max_position");
    }

    /** A product's own limit if set (max_position.3_FLOW=8), otherwise max_position. */
    public long maxPosition(String product) {
        String own = props.getProperty("max_position." + product, "").trim();
        return own.isEmpty() ? maxPosition() : (long) Double.parseDouble(own);
    }

    public long quoteSize() {
        return (long) number("quote_size");
    }

    public long takeSize() {
        return (long) number("take_size");
    }

    /** Typical 15-minute move of a product, the unit all edges are measured in. */
    public double typicalMove(String product) {
        return number("typical_move." + product);
    }

    /** Quote this many uncertainties away from fair value. */
    public double quoteK() {
        return number("quote_k");
    }

    /** Take orders this many uncertainties through fair value. */
    public double takeK() {
        return number("take_k");
    }

    /** At the position limit, quotes shift by this many half-spreads towards unwinding. */
    public double skewK() {
        return number("skew_k");
    }

    /** Share of the last 5 minutes' move added to the current value. */
    public double momentumK() {
        return Double.parseDouble(props.getProperty("momentum_k", "0").trim());
    }

    /** How long to stop quoting after a news item. */
    public double newsPauseSeconds() {
        return number("news_pause_seconds");
    }

    /** Leave a resting quote alone unless its target has moved at least this many ticks. */
    public double repriceTicks() {
        return number("reprice_ticks");
    }

    /** How much of fair value comes from our model; the rest is the market mid. */
    public double modelWeight() {
        return number("model_weight");
    }

    public double arbMinEdge() {
        return number("arb_min_edge");
    }

    public long arbSize() {
        return (long) number("arb_size");
    }

    public double forecastWeight() {
        return number("forecast_weight");
    }

    /** Predicted settlement values set by hand or from a forecast, keyed by product. */
    public Map<String, Double> forecasts() {
        Map<String, Double> forecasts = new HashMap<>();
        for (String product : products()) {
            String value = props.getProperty("forecast." + product, "").trim();
            if (!value.isEmpty()) {
                forecasts.put(product, Double.parseDouble(value));
            }
        }
        return forecasts;
    }

    public Map<String, Double> typicalMoves() {
        Map<String, Double> moves = new HashMap<>();
        for (String product : products()) {
            moves.put(product, typicalMove(product));
        }
        return moves;
    }

    /** Products not to trade, for example while their fair value looks wrong. */
    public List<String> disabled() {
        String value = props.getProperty("disabled", "").trim();
        return value.isEmpty() ? List.of() : Arrays.stream(value.split(",")).map(String::trim).toList();
    }
}
