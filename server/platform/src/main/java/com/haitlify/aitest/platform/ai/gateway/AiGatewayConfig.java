package com.haitlify.aitest.platform.ai.gateway;

public record AiGatewayConfig(
    String providerType,
    String baseUrl,
    String model,
    String visionModel,
    String apiKey,
    int requestTimeoutMs
) {
    public AiGatewayConfig(String providerType, String baseUrl, String model, String apiKey, int requestTimeoutMs) {
        this(providerType, baseUrl, model, "", apiKey, requestTimeoutMs);
    }
}
