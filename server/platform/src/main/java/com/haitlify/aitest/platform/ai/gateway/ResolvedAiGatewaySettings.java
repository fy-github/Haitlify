package com.haitlify.aitest.platform.ai.gateway;

public record ResolvedAiGatewaySettings(
    String providerType,
    String baseUrl,
    String model,
    String apiKey,
    int timeoutMs
) {
    public AiGatewayConfig toGatewayConfig() {
        return new AiGatewayConfig(providerType, baseUrl, model, apiKey, timeoutMs);
    }
}
