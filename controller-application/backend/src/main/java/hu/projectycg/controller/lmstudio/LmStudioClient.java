package hu.projectycg.controller.lmstudio;

import hu.projectycg.controller.knowledge.KnowledgeBaseException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class LmStudioClient {
    private final LmStudioProperties properties;
    private final HttpClient http;
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public LmStudioClient(LmStudioProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
    }

    public String chat(String prompt) {
        var body = mapper.createObjectNode()
                .put("model", properties.model())
                .put("input", prompt)
                .put("reasoning", "off")
                .put("stream", true)
                .put("store", false);
        if (properties.integrationId() != null && !properties.integrationId().isBlank()) {
            body.putArray("integrations").add(properties.integrationId());
        }
        var request = HttpRequest.newBuilder(properties.baseUrl().resolve("/api/v1/chat"))
                .timeout(properties.requestTimeout())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8));
        if (properties.apiToken() != null && !properties.apiToken().isBlank()) {
            request.header("Authorization", "Bearer " + properties.apiToken());
        }
        try {
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                // Do not expose upstream bodies, which can contain prompts or credentials.
                String hint = switch (response.statusCode()) {
                    case 401 -> " Set LM_STUDIO_API_TOKEN to a valid LM Studio API token.";
                    case 403 -> " Allow the configured MCP server for API use in LM Studio and check API token permissions.";
                    default -> " Check its model, API token, and MCP server configuration.";
                };
                throw new KnowledgeBaseException(KnowledgeBaseException.Code.AI_HTTP_ERROR,
                        "LM Studio returned HTTP " + response.statusCode() + "." + hint);
            }
            return streamedFinalMessage(response.body());
        } catch (HttpTimeoutException exception) {
            throw new KnowledgeBaseException(KnowledgeBaseException.Code.AI_TIMEOUT,
                    "LM Studio did not respond within the configured timeout. Its processing may still be running.",
                    exception);
        } catch (IOException exception) {
            throw new KnowledgeBaseException(KnowledgeBaseException.Code.AI_UNAVAILABLE,
                    "Could not communicate with LM Studio. Check that its local server is running.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new KnowledgeBaseException(KnowledgeBaseException.Code.AI_UNAVAILABLE,
                    "The LM Studio request was interrupted.", exception);
        }
    }

    private String streamedFinalMessage(String response) {
        String event = null;
        var data = new StringBuilder();
        try {
            var reader = new BufferedReader(new StringReader(response));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if ("error".equals(event)) {
                        throw new KnowledgeBaseException(KnowledgeBaseException.Code.INVALID_AI_RESPONSE,
                                "LM Studio reported an error while streaming the chat response.");
                    }
                    if ("chat.end".equals(event)) {
                        return finalMessage(chatEndResult(data.toString()));
                    }
                    event = null;
                    data.setLength(0);
                } else if (line.startsWith("event:")) {
                    event = line.substring("event:".length()).strip();
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(line.substring("data:".length()).stripLeading());
                }
            }
            if ("chat.end".equals(event)) {
                return finalMessage(chatEndResult(data.toString()));
            }
            if ("error".equals(event)) {
                throw new KnowledgeBaseException(KnowledgeBaseException.Code.INVALID_AI_RESPONSE,
                        "LM Studio reported an error while streaming the chat response.");
            }
        } catch (IOException exception) {
            // StringReader does not perform I/O, but BufferedReader declares checked failures.
            throw invalidEnvelope();
        }
        throw invalidEnvelope();
    }

    private JsonNode chatEndResult(String data) {
        try {
            JsonNode event = mapper.readTree(data);
            if (event != null && "chat.end".equals(event.path("type").asString())
                    && event.path("result").isObject()) {
                return event.path("result");
            }
        } catch (JacksonException exception) {
            throw invalidEnvelope();
        }
        throw invalidEnvelope();
    }

    private String finalMessage(JsonNode root) {
        if (root == null || !root.isObject() || root.has("error") || !root.path("output").isArray()) {
            throw invalidEnvelope();
        }
        JsonNode lastMessage = null;
        for (JsonNode item : root.path("output")) {
            if ("message".equals(item.path("type").asString())) {
                lastMessage = item;
            }
        }
        // Native chat output can include reasoning and tool results before the final message.
        if (lastMessage == null || !lastMessage.path("content").isString()
                || lastMessage.path("content").asString().isBlank()) {
            throw invalidEnvelope();
        }
        return lastMessage.path("content").asString();
    }

    private KnowledgeBaseException invalidEnvelope() {
        return new KnowledgeBaseException(KnowledgeBaseException.Code.INVALID_AI_RESPONSE,
                "LM Studio returned an invalid chat response or no final text message.");
    }
}
