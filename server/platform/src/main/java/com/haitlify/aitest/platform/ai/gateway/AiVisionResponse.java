package com.haitlify.aitest.platform.ai.gateway;

public record AiVisionResponse(
    boolean success,
    String content,
    String requestId,
    int inputTokens,
    int outputTokens,
    String errorMessage
) {
}
