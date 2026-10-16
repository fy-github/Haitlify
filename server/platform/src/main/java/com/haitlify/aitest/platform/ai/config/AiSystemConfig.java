package com.haitlify.aitest.platform.ai.config;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("ai_system_configs")
class AiSystemConfig {

    @Id
    @Column("id")
    private Long id;
    @Column("config_key")
    private String configKey;
    @Column("enabled")
    private boolean enabled;
    @Column("provider_type")
    private String providerType;
    @Column("base_url")
    private String baseUrl;
    @Column("text_model")
    private String textModel;
    @Column("vision_model")
    private String visionModel;
    @Column("vision_enabled")
    private boolean visionEnabled;
    @Column("max_image_bytes")
    private long maxImageBytes;
    @Column("allowed_image_types_json")
    private String allowedImageTypesJson;
    @Column("embedding_model")
    private String embeddingModel;
    @Column("api_key_ciphertext")
    private String apiKeyCiphertext;
    @Column("credential_configured")
    private boolean credentialConfigured;
    @Column("request_timeout_ms")
    private int requestTimeoutMs;
    @Column("max_input_length")
    private int maxInputLength;
    @Column("max_output_length")
    private int maxOutputLength;
    @Column("retry_count")
    private int retryCount;
    @Column("request_logging_enabled")
    private boolean requestLoggingEnabled;
    @Column("external_document_send_allowed")
    private boolean externalDocumentSendAllowed;
    @Column("provider_config_json")
    private String providerConfigJson;
    @Column("masked_config_json")
    private String maskedConfigJson;
    @Column("actor_id")
    private String actorId;
    @Column("actor_name")
    private String actorName;
    @Column("created_at")
    private Instant createdAt;
    @Column("updated_at")
    private Instant updatedAt;

    AiSystemConfig() {
    }

    AiSystemConfig(
        Long id,
        String configKey,
        boolean enabled,
        String providerType,
        String baseUrl,
        String textModel,
        String visionModel,
        boolean visionEnabled,
        long maxImageBytes,
        String allowedImageTypesJson,
        String embeddingModel,
        String apiKeyCiphertext,
        boolean credentialConfigured,
        int requestTimeoutMs,
        int maxInputLength,
        int maxOutputLength,
        int retryCount,
        boolean requestLoggingEnabled,
        boolean externalDocumentSendAllowed,
        String providerConfigJson,
        String maskedConfigJson,
        String actorId,
        String actorName,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.id = id;
        this.configKey = configKey;
        this.enabled = enabled;
        this.providerType = providerType;
        this.baseUrl = baseUrl;
        this.textModel = textModel;
        this.visionModel = visionModel;
        this.visionEnabled = visionEnabled;
        this.maxImageBytes = maxImageBytes;
        this.allowedImageTypesJson = allowedImageTypesJson;
        this.embeddingModel = embeddingModel;
        this.apiKeyCiphertext = apiKeyCiphertext;
        this.credentialConfigured = credentialConfigured;
        this.requestTimeoutMs = requestTimeoutMs;
        this.maxInputLength = maxInputLength;
        this.maxOutputLength = maxOutputLength;
        this.retryCount = retryCount;
        this.requestLoggingEnabled = requestLoggingEnabled;
        this.externalDocumentSendAllowed = externalDocumentSendAllowed;
        this.providerConfigJson = providerConfigJson;
        this.maskedConfigJson = maskedConfigJson;
        this.actorId = actorId;
        this.actorName = actorName;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    Long getId() {
        return id;
    }

    String getConfigKey() {
        return configKey;
    }

    boolean isEnabled() {
        return enabled;
    }

    String getProviderType() {
        return providerType;
    }

    String getBaseUrl() {
        return baseUrl;
    }

    String getTextModel() {
        return textModel;
    }

    String getVisionModel() {
        return visionModel;
    }

    boolean isVisionEnabled() {
        return visionEnabled;
    }

    long getMaxImageBytes() {
        return maxImageBytes;
    }

    String getAllowedImageTypesJson() {
        return allowedImageTypesJson;
    }

    String getEmbeddingModel() {
        return embeddingModel;
    }

    String getApiKeyCiphertext() {
        return apiKeyCiphertext;
    }

    boolean isCredentialConfigured() {
        return credentialConfigured;
    }

    int getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    int getMaxInputLength() {
        return maxInputLength;
    }

    int getMaxOutputLength() {
        return maxOutputLength;
    }

    int getRetryCount() {
        return retryCount;
    }

    boolean isRequestLoggingEnabled() {
        return requestLoggingEnabled;
    }

    boolean isExternalDocumentSendAllowed() {
        return externalDocumentSendAllowed;
    }

    String getProviderConfigJson() {
        return providerConfigJson;
    }

    String getMaskedConfigJson() {
        return maskedConfigJson;
    }

    String getActorId() {
        return actorId;
    }

    String getActorName() {
        return actorName;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
