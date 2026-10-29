package hu.projectycg.controller.video;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class VideoProcessingClient {
    private final VideoProcessingProperties properties;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public VideoProcessingClient(VideoProcessingProperties properties) { this.properties = properties; }

    public void createProxy(String gameName, Path source, Path output, Consumer<JsonNode> events) throws IOException {
        process("proxy", Map.of("gameName", gameName, "inputVideo", source.toString(),
                "outputVideo", output.toString()), events);
    }

    public void sampleFrames(String gameName, Path source, Path output, BigDecimal intervalSeconds,
            Consumer<JsonNode> events) throws IOException {
        process("sample", Map.of("gameName", gameName, "inputVideo", source.toString(),
                "outputDirectory", output.toString(), "intervalSeconds", intervalSeconds), events);
    }

    public void detectBossFights(String gameName, Path frames, Path output, Consumer<JsonNode> events) throws IOException {
        process("boss-detection", Map.of("gameName", gameName, "framesDirectory", frames.toString(),
                "outputFile", output.toString()), events);
    }

    public void refineBossFights(String gameName, Path video, Path detections, Path output,
            Consumer<JsonNode> events) throws IOException {
        process("boss-refinement", Map.of("gameName", gameName, "inputVideo", video.toString(),
                "detectionsFile", detections.toString(), "outputDirectory", output.toString()), events);
    }

    private void process(String operation, Map<String, ?> request, Consumer<JsonNode> events) throws IOException {
        var connection = (HttpURLConnection) properties.baseUrl().resolve("/api/" + operation).toURL().openConnection();
        connection.setConnectTimeout(Math.toIntExact(properties.connectTimeout().toMillis()));
        // Read timeout applies between events, so multi-hour encodes remain supported.
        connection.setReadTimeout(Math.toIntExact(properties.idleTimeout().toMillis()));
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/x-ndjson");
        connection.setDoOutput(true);
        var body = mapper.writeValueAsBytes(request);
        connection.setFixedLengthStreamingMode(body.length);
        try {
            try (var stream = connection.getOutputStream()) { stream.write(body); }
            if (connection.getResponseCode() != 200) {
                String detail = "";
                if (connection.getErrorStream() != null) {
                    try (var error = connection.getErrorStream()) {
                        detail = new String(error.readNBytes(4096), StandardCharsets.UTF_8);
                    }
                }
                throw new IOException("Video service returned HTTP " + connection.getResponseCode() + ": " + detail);
            }
            if (connection.getContentType() == null || !connection.getContentType().startsWith("application/x-ndjson")) {
                throw new IOException("Video service returned an unexpected content type.");
            }
            boolean completed = false;
            try (var reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    var event = mapper.readTree(line);
                    String state = event.path("state").asString("");
                    if ("heartbeat".equals(state)) continue;
                    if (!java.util.Set.of("started", "running", "completed", "failed", "warning").contains(state)) {
                        throw new IOException("Video service returned an invalid progress event.");
                    }
                    events.accept(event);
                    if ("failed".equals(state)) throw new IOException(event.path("detail").asString(operation + " failed."));
                    if ("completed".equals(state)) { completed = true; break; }
                }
            }
            if (!completed) throw new IOException("Video service disconnected before confirming " + operation + " completion.");
        } finally { connection.disconnect(); }
    }
}
