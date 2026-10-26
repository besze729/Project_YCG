package hu.projectycg.controller.video;

import java.net.URI;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ycg.video")
public record VideoProcessingProperties(URI baseUrl, Path outputRoot,
        Duration connectTimeout, Duration idleTimeout,
        BigDecimal samplingIntervalSeconds) {
    public VideoProcessingProperties {
        if (baseUrl == null || !"http".equals(baseUrl.getScheme())
                || !("127.0.0.1".equals(baseUrl.getHost()) || "localhost".equals(baseUrl.getHost()))) {
            throw new IllegalArgumentException("Video service must use a local HTTP URL.");
        }
        if (outputRoot == null || connectTimeout == null || connectTimeout.isNegative()
                || connectTimeout.isZero() || idleTimeout == null || idleTimeout.isNegative()
                || idleTimeout.isZero()) throw new IllegalArgumentException("Invalid video service configuration.");
        if (samplingIntervalSeconds == null || samplingIntervalSeconds.signum() <= 0
                || samplingIntervalSeconds.stripTrailingZeros().scale() > 3) {
            throw new IllegalArgumentException("Sampling interval must be positive seconds with millisecond precision.");
        }
    }
}
