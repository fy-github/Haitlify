package com.haitlify.aitest.platform.document;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.figma")
public record FigmaDesignProperties(
    boolean enabled,
    String token,
    String baseUrl,
    int timeoutMs
) {
}
