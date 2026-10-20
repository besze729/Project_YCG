package hu.projectycg.controller.lmstudio;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ycg.lm-studio")
public record LmStudioProperties(
        URI baseUrl, String model, Duration connectTimeout, Duration requestTimeout,
        String apiToken, String integrationId) {

    public LmStudioProperties {
        if (baseUrl == null || baseUrl.getHost() == null
                || !("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))) {
            throw new IllegalArgumentException("LM Studio base URL must be an absolute HTTP or HTTPS URL.");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("LM Studio model must not be blank.");
        }
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                || requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("LM Studio timeouts must be positive.");
        }
    }
}
