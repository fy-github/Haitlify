package com.haitlify.aitest.platform.ai.gateway;

/**
 * A fully materialized route entry in a run's strategy: the provider type, endpoint, credential,
 * model and per-call timeout needed to issue one gateway call, plus the human-readable connection
 * identity used in the visible event stream. This is the unit the {@link AiRouteExecutor} walks.
 */
public record AiRoute(
    String connectionId,
    String providerName,
    String providerType,
    String baseUrl,
    String model,
    String apiKey,
    int requestTimeoutMs
) {

    public AiGatewayConfig toGatewayConfig() {
        return new AiGatewayConfig(providerType, baseUrl, model, apiKey, requestTimeoutMs);
    }
}
