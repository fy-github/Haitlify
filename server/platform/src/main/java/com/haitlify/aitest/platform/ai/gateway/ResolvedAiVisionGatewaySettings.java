package com.haitlify.aitest.platform.ai.gateway;

/** 视觉任务的网关设置快照（含 vision model）；加载路径与文本设置同源（ai_system_configs 默认档案）。 */
public record ResolvedAiVisionGatewaySettings(
    String providerType,
    String baseUrl,
    String textModel,
    String visionModel,
    String apiKey,
    int timeoutMs
) {
    public AiGatewayConfig toVisionGatewayConfig() {
        return new AiGatewayConfig(providerType, baseUrl, textModel, visionModel, apiKey, timeoutMs);
    }
}
