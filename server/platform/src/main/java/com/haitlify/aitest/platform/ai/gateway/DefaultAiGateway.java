package com.haitlify.aitest.platform.ai.gateway;

import java.util.List;

import org.springframework.stereotype.Component;

@Component
class DefaultAiGateway implements AiGateway {

    private final List<AiProviderAdapter> adapters;

    DefaultAiGateway(List<AiProviderAdapter> adapters) {
        this.adapters = adapters;
    }

    @Override
    public AiConnectionTestResult testConnection(AiGatewayConfig config) {
        return adapters.stream()
            .filter(adapter -> adapter.supports(config.providerType()))
            .findFirst()
            .map(adapter -> adapter.testConnection(config))
            .orElseGet(() -> new AiConnectionTestResult(false, "不支持的 AI 提供商: " + config.providerType()));
    }

    @Override
    public AiGenerationResult structuredGenerate(AiGatewayConfig config, Object prompt) {
        return adapters.stream()
            .filter(adapter -> adapter.supports(config.providerType()))
            .findFirst()
            .map(adapter -> adapter.structuredGenerate(config, prompt))
            .orElseGet(() -> new AiGenerationResult(false, "", "", 0, 0, "不支持的 AI 提供商: " + config.providerType()));
    }

    @Override
    public AiVisionResponse visionAnalyze(AiGatewayConfig config, AiVisionRequest request) {
        return adapters.stream()
            .filter(adapter -> adapter.supports(config.providerType()))
            .findFirst()
            .map(adapter -> adapter.visionAnalyze(config, request))
            .orElseGet(() -> new AiVisionResponse(false, "", "", 0, 0, "不支持的 AI 提供商: " + config.providerType()));
    }
}
