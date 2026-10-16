package com.haitlify.aitest.platform.ai.gateway;

public record AiVisionRequest(
    String prompt,
    String imageContentType,
    byte[] imageBytes,
    int maxOutputTokens
) {
}
