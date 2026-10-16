package com.haitlify.aitest.platform.ai.gateway;

import java.util.List;

import com.haitlify.aitest.platform.security.SecretCipher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AiGatewaySettingsService {

    private static final int MIN_TEXT_TIMEOUT_MS = 120000;

    private final JdbcTemplate jdbcTemplate;
    private final SecretCipher secretCipher;

    public AiGatewaySettingsService(JdbcTemplate jdbcTemplate, SecretCipher secretCipher) {
        this.jdbcTemplate = jdbcTemplate;
        this.secretCipher = secretCipher;
    }

    public ResolvedAiGatewaySettings loadDefaultTextSettings() {
        List<ResolvedAiGatewaySettings> settings = jdbcTemplate.query(
            "select enabled, provider_type, base_url, text_model, api_key_ciphertext, request_timeout_ms from ai_system_configs where config_key = 'default'",
            (rs, rowNum) -> {
                if (!rs.getBoolean("enabled")) {
                    throw new IllegalStateException("AI 配置未启用，请先在 AI 配置页启用并保存配置");
                }
                String providerType = blankToDefault(rs.getString("provider_type"), "openai-compatible");
                String baseUrl = blankToDefault(rs.getString("base_url"), "");
                String model = blankToDefault(rs.getString("text_model"), "");
                String apiKey = secretCipher.decrypt(blankToDefault(rs.getString("api_key_ciphertext"), ""));
                if (baseUrl.isBlank() || model.isBlank() || apiKey.isBlank()) {
                    throw new IllegalStateException("AI 配置未完成，请先在 AI 配置页保存 Base URL、模型和 API Key");
                }
                return new ResolvedAiGatewaySettings(
                    providerType,
                    baseUrl,
                    model,
                    apiKey,
                    Math.max(rs.getInt("request_timeout_ms"), MIN_TEXT_TIMEOUT_MS)
                );
            }
        );
        if (settings.isEmpty()) {
            throw new IllegalStateException("AI 配置未完成，请先在 AI 配置页保存 Base URL、模型和 API Key");
        }
        return settings.get(0);
    }

    /**
     * T07：视觉任务的设置快照（含 vision model）。与文本设置同一默认档案、同一解密路径；
     * 业务层不再各自手写 ai_system_configs 查询。
     */
    public ResolvedAiVisionGatewaySettings loadDefaultVisionSettings() {
        List<ResolvedAiVisionGatewaySettings> settings = jdbcTemplate.query(
            "select enabled, provider_type, base_url, text_model, vision_model, api_key_ciphertext, request_timeout_ms from ai_system_configs where config_key = 'default'",
            (rs, rowNum) -> {
                if (!rs.getBoolean("enabled")) {
                    throw new IllegalStateException("AI 配置未启用，请先在 AI 配置页启用并保存配置");
                }
                String providerType = blankToDefault(rs.getString("provider_type"), "openai-compatible");
                String baseUrl = blankToDefault(rs.getString("base_url"), "");
                String textModel = blankToDefault(rs.getString("text_model"), "");
                String visionModel = blankToDefault(rs.getString("vision_model"), "");
                String apiKey = secretCipher.decrypt(blankToDefault(rs.getString("api_key_ciphertext"), ""));
                if (baseUrl.isBlank() || textModel.isBlank() || apiKey.isBlank()) {
                    throw new IllegalStateException("AI 配置未完成，请先在 AI 配置页保存 Base URL、模型和 API Key");
                }
                return new ResolvedAiVisionGatewaySettings(
                    providerType,
                    baseUrl,
                    textModel,
                    visionModel,
                    apiKey,
                    Math.max(rs.getInt("request_timeout_ms"), 0)
                );
            }
        );
        if (settings.isEmpty()) {
            throw new IllegalStateException("AI 配置未完成，请先在 AI 配置页保存 Base URL、模型和 API Key");
        }
        return settings.get(0);
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
