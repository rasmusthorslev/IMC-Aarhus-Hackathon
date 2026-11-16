package imcbot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** Writes one JSON object per line to logs/run-<time>.jsonl. */
final class EventLog {

    private final ObjectMapper json = new ObjectMapper();
    private final PrintWriter out;

    EventLog() throws IOException {
        Path dir = Files.createDirectories(Path.of("logs"));
        String name = "run-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".jsonl";
        out = new PrintWriter(Files.newBufferedWriter(dir.resolve(name)), true);
    }

    synchronized void write(String type, Object... keysAndValues) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("t", Instant.now().toString());
        line.put("type", type);
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            line.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        try {
            out.println(json.writeValueAsString(line));
        } catch (JsonProcessingException e) {
            out.println("{\"type\":\"log_error\"}");
        }
    }
}
