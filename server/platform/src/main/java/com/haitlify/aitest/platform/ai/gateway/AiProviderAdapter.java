package com.haitlify.aitest.platform.ai.gateway;

interface AiProviderAdapter {

    boolean supports(String providerType);

    AiConnectionTestResult testConnection(AiGatewayConfig config);

    AiGenerationResult structuredGenerate(AiGatewayConfig config, Object prompt);

    AiVisionResponse visionAnalyze(AiGatewayConfig config, AiVisionRequest request);
}
