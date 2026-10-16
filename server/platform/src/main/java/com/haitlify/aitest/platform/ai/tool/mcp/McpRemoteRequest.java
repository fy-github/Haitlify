package com.haitlify.aitest.platform.ai.tool.mcp;

import java.time.Duration;

import tools.jackson.databind.JsonNode;

public record McpRemoteRequest(
    String serverType,
    String endpoint,
    String authMode,
    String authSecretRef,
    String authSecretCiphertext,
    int timeoutMs,
    String toolName,
    JsonNode arguments
) {
    public Duration timeout() {
        return Duration.ofMillis(timeoutMs <= 0 ? 30_000L : timeoutMs);
    }
}
