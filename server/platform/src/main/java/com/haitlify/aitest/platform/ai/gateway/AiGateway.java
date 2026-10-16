package com.haitlify.aitest.platform.ai.gateway;

public interface AiGateway {

    AiConnectionTestResult testConnection(AiGatewayConfig config);

    AiGenerationResult structuredGenerate(AiGatewayConfig config, Object prompt);

    AiVisionResponse visionAnalyze(AiGatewayConfig config, AiVisionRequest request);
}
