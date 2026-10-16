package com.haitlify.aitest.platform.webautomation;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.web-automation")
public record WebAutomationProperties(
    Runner runner
) {

    public WebAutomationProperties {
        runner = runner == null ? new Runner(false, "http://127.0.0.1:9323", 120000) : runner;
    }

    public record Runner(
        boolean enabled,
        String baseUrl,
        int timeoutMs
    ) {
    }
}
