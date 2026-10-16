package com.haitlify.aitest.platform.ai.gateway;

public record AiGenerationResult(
    boolean success,
    String content,
    String requestId,
    int inputTokens,
    int outputTokens,
    String errorMessage,
    boolean retryable,
    String retryReason,
    long retryAfterMs
) {
    public AiGenerationResult(boolean success, String content, String requestId, int inputTokens, int outputTokens, String errorMessage) {
        this(success, content, requestId, inputTokens, outputTokens, errorMessage, false, null, -1);
    }

    public AiGenerationResult(boolean success, String content, String requestId, int inputTokens, int outputTokens,
                              String errorMessage, boolean retryable, String retryReason) {
        this(success, content, requestId, inputTokens, outputTokens, errorMessage, retryable, retryReason, -1);
    }
}
