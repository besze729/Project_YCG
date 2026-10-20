package hu.projectycg.controller.knowledge;

import java.net.URI;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class KnowledgeBaseResponseValidator {
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    public JsonNode validate(String content) {
        JsonNode root;
        try {
            root = mapper.readTree(content);
        } catch (JacksonException exception) {
            throw invalid("The model response must contain only one valid JSON object.");
        }
        if (root == null || !root.isObject() || !root.path("bosses").isArray()
                || !root.path("sources").isArray()) {
            throw invalid("The model response must be an object with bosses and sources arrays.");
        }
        for (JsonNode boss : root.path("bosses")) {
            if (!boss.isObject() || !boss.path("displayName").isString()
                    || boss.path("displayName").asString().isBlank() || !boss.has("classification")) {
                throw invalid("Each boss must have a non-blank displayName and a classification field.");
            }
            // Classification intentionally accepts every JSON value, including null.
        }
        for (JsonNode source : root.path("sources")) {
            if (!source.isString() || !isSourceUrl(source.asString())) {
                throw invalid("Each source must be an absolute HTTP or HTTPS URL.");
            }
        }
        return root;
    }

    private boolean isSourceUrl(String value) {
        try {
            URI uri = URI.create(value);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private KnowledgeBaseException invalid(String message) {
        return new KnowledgeBaseException(KnowledgeBaseException.Code.INVALID_AI_RESPONSE, message);
    }
}
