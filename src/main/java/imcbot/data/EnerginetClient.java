package imcbot.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Fetches Energinet's minute-by-minute power system data (PowerSystemRightNow). */
public final class EnerginetClient {

    public static final ZoneId DK = ZoneId.of("Europe/Copenhagen");
    private static final String URL = "https://api.energidataservice.dk/dataset/PowerSystemRightNow";
    private static final String COLUMNS = "Minutes1DK,CO2Emission,OffshoreWindPower,OnshoreWindPower,Exchange_Sum";
    private static final DateTimeFormatter START_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    /** One minute of data. Wind is onshore plus offshore, in MW. */
    public record Minute(ZonedDateTime time, double wind, double co2, double exchangeSum) {
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();

    /** @return complete minutes from {@code start} (Danish time) onwards, oldest first */
    public List<Minute> fetchSince(ZonedDateTime start) throws IOException, InterruptedException {
        String query = "start=" + enc(start.withZoneSameInstant(DK).format(START_FORMAT))
                + "&limit=0&sort=" + enc("Minutes1UTC asc") + "&columns=" + enc(COLUMNS);
        HttpRequest request = HttpRequest.newBuilder(URI.create(URL + "?" + query))
                .timeout(Duration.ofSeconds(30)).GET().build();
        String body = http.send(request, BodyHandlers.ofString()).body();
        List<Minute> minutes = new ArrayList<>();
        for (JsonNode row : json.readTree(body).path("records")) {
            if (hasNull(row, "Minutes1DK", "CO2Emission", "OffshoreWindPower", "OnshoreWindPower", "Exchange_Sum")) {
                continue;
            }
            minutes.add(new Minute(
                    LocalDateTime.parse(row.get("Minutes1DK").asText()).atZone(DK),
                    row.get("OnshoreWindPower").asDouble() + row.get("OffshoreWindPower").asDouble(),
                    row.get("CO2Emission").asDouble(),
                    row.get("Exchange_Sum").asDouble()));
        }
        return minutes;
    }

    private static boolean hasNull(JsonNode row, String... fields) {
        for (String field : fields) {
            if (!row.hasNonNull(field)) {
                return true;
            }
        }
        return false;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
