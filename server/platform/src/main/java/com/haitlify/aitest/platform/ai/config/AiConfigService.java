package com.haitlify.aitest.platform.ai.config;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.haitlify.aitest.platform.ai.gateway.AiConnectionTestResult;
import com.haitlify.aitest.platform.ai.gateway.AiGateway;
import com.haitlify.aitest.platform.ai.gateway.AiGatewayConfig;
import com.haitlify.aitest.platform.ai.gateway.AiGenerationResult;
import com.haitlify.aitest.platform.security.SecretCipher;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
@Transactional
class AiConfigService {

    private static final String DEFAULT_CONFIG_KEY = "default";
    private static final long DEFAULT_MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final List<String> DEFAULT_ALLOWED_IMAGE_TYPES = List.of("image/png", "image/jpeg", "image/webp");
    private static final int DEFAULT_REQUEST_TIMEOUT_MS = 120000;
    private static final int DEFAULT_COMMAND_TIMEOUT_MS = 120000;

    private final AiSystemConfigRepository repository;
    private final AiGateway aiGateway;
    private final ObjectMapper objectMapper;
    private final AiRunPolicyService runPolicyService;
    private final SecretCipher secretCipher;

    AiConfigService(AiSystemConfigRepository repository, AiGateway aiGateway, ObjectMapper objectMapper, AiRunPolicyService runPolicyService, SecretCipher secretCipher) {
        this.repository = repository;
        this.aiGateway = aiGateway;
        this.objectMapper = objectMapper;
        this.runPolicyService = runPolicyService;
        this.secretCipher = secretCipher;
    }

    AiConfigResponse getConfig() {
        return repository.findByConfigKey(DEFAULT_CONFIG_KEY)
            .map(this::toResponse)
            .orElseGet(this::defaultResponse);
    }

    AiConfigResponse saveConfig(SaveAiConfigRequest request) {
        // T14：自定义 headers 不得覆盖核心头（Authorization/Host/Cookie 等由传输层掌握）。
        validateCustomHeaders(request.providers());
        AiSystemConfig current = repository.findByConfigKey(DEFAULT_CONFIG_KEY).orElse(null);
        ProviderState providerState = normalizeProviderState(request, current);
        StoredAiProvider activeProvider = providerState.activeProvider();
        Instant now = Instant.now();
        String apiKey = activeProvider.apiKey();
        boolean credentialConfigured = !apiKey.isBlank();
        int requestTimeoutMs = positiveOrDefault(activeProvider.requestTimeoutMs(), positiveOrDefault(request.requestTimeoutMs(), DEFAULT_REQUEST_TIMEOUT_MS));

        AiSystemConfig next = new AiSystemConfig(
            current == null ? null : current.getId(),
            DEFAULT_CONFIG_KEY,
            request.enabled() && activeProvider.enabled(),
            activeProvider.providerId(),
            activeProvider.baseURL(),
            blankToDefault(providerState.activeModel(), activeProvider.defaultModel()),
            blankToDefault(request.visionModel(), current == null ? "" : current.getVisionModel()),
            request.visionEnabled(),
            request.maxImageBytes() > 0
                ? request.maxImageBytes()
                : current == null ? DEFAULT_MAX_IMAGE_BYTES : current.getMaxImageBytes(),
            toJson(request.allowedImageTypes() == null || request.allowedImageTypes().isEmpty()
                ? current == null ? DEFAULT_ALLOWED_IMAGE_TYPES : parseArray(current.getAllowedImageTypesJson())
                : request.allowedImageTypes()),
            current == null ? "" : current.getEmbeddingModel(),
            secretCipher.encrypt(apiKey),
            credentialConfigured,
            requestTimeoutMs,
            current == null ? 0 : current.getMaxInputLength(),
            current == null ? 0 : current.getMaxOutputLength(),
            current == null ? 0 : current.getRetryCount(),
            current != null && current.isRequestLoggingEnabled(),
            current != null && current.isExternalDocumentSendAllowed(),
            stringifyProviderState(providerState),
            credentialConfigured ? maskedApiKey(apiKey) : null,
            com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystem(),
            com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystemName(),
            current == null ? now : current.getCreatedAt(),
            now
        );

        AiSystemConfig saved = repository.save(next);
        runPolicyService.syncDefaultPolicyPrimaryRoute(
            providerState.activeProviderId(),
            providerState.activeModel(),
            requestTimeoutMs
        );
        return toResponse(saved);
    }

    AiConnectionTestResponse testConnection(TestAiConfigRequest request) {
        ProviderState providerState = normalizeProviderState(
            new SaveAiConfigRequest(
                true,
                request.providerType(),
                request.baseUrl(),
                request.model(),
                "",
                false,
                0,
                List.of(),
                request.apiKey(),
                request.requestTimeoutMs(),
                request.providers(),
                request.activeProviderId(),
                request.activeModel(),
                Map.of(),
                Map.of(),
                request.commandTimeoutMs()
            ),
            repository.findByConfigKey(DEFAULT_CONFIG_KEY).orElse(null)
        );
        StoredAiProvider activeProvider = providerState.activeProvider();
        String providerType = activeProvider.providerId();
        String baseUrl = activeProvider.baseURL();
        String model = blankToDefault(providerState.activeModel(), activeProvider.defaultModel());
        String apiKey = activeProvider.apiKey();
        int timeoutMs = positiveOrDefault(activeProvider.requestTimeoutMs(), DEFAULT_REQUEST_TIMEOUT_MS);
        if (model.isBlank()) {
            return new AiConnectionTestResponse(false, "AI 测试连接失败: 请先填写默认模型");
        }

        // T11（T04 遗留）：连接测试必须是真实结构化探针——只 ping/数 token 的旧路径会
        // 把「HTTP 可达」冒充「模型可用」。成功仅在输出通过 JSON 合同校验时成立。
        // 这是预运行管理探针（草稿配置无 run 上下文），登记于 AiRuntimeConsumerCoverageTest
        // 的 SANCTIONED_PRE_RUN_PROBES；不得用于业务生成/交付。
        AiGenerationResult generation;
        try {
            ObjectNode probe = objectMapper.createObjectNode()
                .put("instruction", "连通性与结构化输出验证：仅返回 JSON 对象 {\"ok\":true}，不要输出任何其他内容。");
            generation = aiGateway.structuredGenerate(
                new AiGatewayConfig(providerType, baseUrl, model, apiKey, timeoutMs), probe);
        } catch (RuntimeException failure) {
            return new AiConnectionTestResponse(false,
                "AI 测试连接失败: " + safeMessage(failure.getMessage()));
        }
        if (generation == null || !generation.success()) {
            String reason = generation == null ? "无响应"
                : (generation.errorMessage() == null || generation.errorMessage().isBlank()
                    ? generation.retryReason() : generation.errorMessage());
            return new AiConnectionTestResponse(false, "AI 测试连接失败: " + safeMessage(reason));
        }
        boolean contractPassed = false;
        try {
            JsonNode parsed = objectMapper.readTree(generation.content() == null ? "" : generation.content());
            contractPassed = parsed != null && parsed.path("ok").asBoolean(false);
        } catch (Exception malformed) {
            contractPassed = false;
        }
        if (!contractPassed) {
            return new AiConnectionTestResponse(false, "AI 测试连接失败: 结构化探针输出未通过校验");
        }
        String requestId = generation.requestId() == null || generation.requestId().isBlank()
            ? "" : "（requestId: " + generation.requestId() + "）";
        return new AiConnectionTestResponse(true, "AI 测试连接成功：结构化探针通过" + requestId + "；配置未自动保存，请确认后手动保存并启用");
    }

    private static final java.util.Set<String> RESERVED_HEADERS = java.util.Set.of(
        "authorization", "host", "content-length", "cookie", "set-cookie", "proxy-authorization",
        "x-forwarded-for", "x-forwarded-host", "x-real-ip", "connection", "transfer-encoding");

    private void validateCustomHeaders(java.util.List<SaveAiProviderRequest> providers) {
        if (providers == null) {
            return;
        }
        for (SaveAiProviderRequest provider : providers) {
            if (provider == null || provider.customHeaders() == null) {
                continue;
            }
            for (String header : provider.customHeaders().keySet()) {
                if (header == null || header.isBlank()) {
                    continue;
                }
                if (RESERVED_HEADERS.contains(header.trim().toLowerCase(java.util.Locale.ROOT))) {
                    throw new IllegalArgumentException(
                        "reserved_header: 自定义 headers 不得覆盖核心头 " + header);
                }
            }
        }
    }

    private String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "未知原因";
        }
        // T14：出站文本先脱敏——错误回显（上游 401/500 响应体）可能携带密钥，绝不能进响应。
        String single = com.haitlify.aitest.platform.ai.security.AiPayloadSanitizer
            .mask(message.replaceAll("\\s+", " "));
        return single.length() <= 240 ? single : single.substring(0, 237) + "...";
    }

    private AiConfigResponse toResponse(AiSystemConfig config) {
        ProviderState providerState = readProviderState(config);
        StoredAiProvider activeProvider = providerState.activeProvider();
        return new AiConfigResponse(
            config.isEnabled(),
            activeProvider.providerId(),
            activeProvider.baseURL(),
            blankToDefault(providerState.activeModel(), activeProvider.defaultModel()),
            config.getVisionModel(),
            config.isVisionEnabled(),
            config.getMaxImageBytes(),
            parseArray(config.getAllowedImageTypesJson()),
            config.isCredentialConfigured(),
            maskedApiKey(activeProvider.apiKey()),
            config.getRequestTimeoutMs(),
            providerState.providers().stream().map(this::toProviderResponse).toList(),
            providerState.activeProviderId(),
            providerState.activeModel(),
            providerState.agentModelMap(),
            providerState.agentProviderMap(),
            providerState.commandTimeoutMs(),
            config.getUpdatedAt() == null ? null : config.getUpdatedAt().toString(),
            runPolicyService.listPolicies().items()
        );
    }

    private AiConfigResponse defaultResponse() {
        StoredAiProvider defaultProvider = defaultProvider("", "", "");
        return new AiConfigResponse(
            false,
            "openai-compatible",
            "",
            "",
            "",
            false,
            DEFAULT_MAX_IMAGE_BYTES,
            DEFAULT_ALLOWED_IMAGE_TYPES,
            false,
            null,
            DEFAULT_REQUEST_TIMEOUT_MS,
            List.of(toProviderResponse(defaultProvider)),
            defaultProvider.id(),
            "",
            Map.of(),
            Map.of(),
            DEFAULT_COMMAND_TIMEOUT_MS,
            null,
            runPolicyService.listPolicies().items()
        );
    }

    private ProviderState normalizeProviderState(SaveAiConfigRequest request, AiSystemConfig current) {
        ProviderState currentState = current == null ? null : readProviderState(current);
        Map<String, StoredAiProvider> currentProvidersById = new LinkedHashMap<>();
        if (currentState != null) {
            for (StoredAiProvider provider : currentState.providers()) {
                currentProvidersById.put(provider.id(), provider);
            }
        }

        List<StoredAiProvider> providers = new ArrayList<>();
        if (request.providers() != null && !request.providers().isEmpty()) {
            for (SaveAiProviderRequest provider : request.providers()) {
                String id = blankToDefault(provider.id(), "provider-" + providers.size());
                StoredAiProvider previous = currentProvidersById.get(id);
                String providerId = blankToDefault(provider.providerId(), previous == null ? "openai-compatible" : previous.providerId());
                String apiKey = provider.apiKey() == null || provider.apiKey().isBlank()
                    ? previous == null ? "" : previous.apiKey()
                    : provider.apiKey().trim();
                providers.add(new StoredAiProvider(
                    id,
                    providerId,
                    blankToDefault(provider.name(), providerName(providerId)),
                    blankToDefault(provider.baseURL(), previous == null ? "" : previous.baseURL()),
                    blankToDefault(provider.defaultModel(), previous == null ? "" : previous.defaultModel()),
                    apiKey,
                    provider.enabled() == null || provider.enabled(),
                    !apiKey.isBlank(),
                    positiveOrDefault(provider.requestTimeoutMs(), current == null ? DEFAULT_REQUEST_TIMEOUT_MS : current.getRequestTimeoutMs()),
                    provider.contextWindow() == null ? 0 : Math.max(0, provider.contextWindow()),
                    provider.skipTLSVerify() != null && provider.skipTLSVerify(),
                    provider.customHeaders() == null ? Map.of() : provider.customHeaders()
                ));
            }
        } else {
            String apiKey = request.apiKey() == null || request.apiKey().isBlank()
                ? current == null ? "" : secretCipher.decrypt(current.getApiKeyCiphertext())
                : request.apiKey().trim();
            providers.add(defaultProvider(
                blankToDefault(request.baseUrl(), current == null ? "" : current.getBaseUrl()),
                blankToDefault(request.model(), current == null ? "" : current.getTextModel()),
                apiKey,
                blankToDefault(request.providerType(), current == null ? "openai-compatible" : current.getProviderType()),
                request.enabled(),
                positiveOrDefault(request.requestTimeoutMs(), current == null ? DEFAULT_REQUEST_TIMEOUT_MS : current.getRequestTimeoutMs())
            ));
        }

        String activeProviderId = blankToDefault(request.activeProviderId(), currentState == null ? "" : currentState.activeProviderId());
        boolean activeProviderExists = false;
        for (StoredAiProvider provider : providers) {
            if (provider.id().equals(activeProviderId)) {
                activeProviderExists = true;
                break;
            }
        }
        if (activeProviderId.isBlank() || !activeProviderExists) {
            activeProviderId = providers.stream()
                .filter(StoredAiProvider::enabled)
                .findFirst()
                .orElse(providers.getFirst())
                .id();
        }
        StoredAiProvider activeProvider = providers.getFirst();
        for (StoredAiProvider provider : providers) {
            if (provider.id().equals(activeProviderId)) {
                activeProvider = provider;
                break;
            }
        }
        String activeModel = blankToDefault(request.activeModel(), blankToDefault(request.model(), activeProvider.defaultModel()));
        int commandTimeoutMs = positiveOrDefault(request.commandTimeoutMs(), currentState == null ? DEFAULT_COMMAND_TIMEOUT_MS : currentState.commandTimeoutMs());

        return new ProviderState(
            providers,
            activeProviderId,
            activeModel,
            request.agentModelMap() == null ? currentState == null ? Map.of() : currentState.agentModelMap() : request.agentModelMap(),
            request.agentProviderMap() == null ? currentState == null ? Map.of() : currentState.agentProviderMap() : request.agentProviderMap(),
            commandTimeoutMs,
            activeProvider
        );
    }

    private ProviderState readProviderState(AiSystemConfig config) {
        if (config.getProviderConfigJson() == null || config.getProviderConfigJson().isBlank()) {
            StoredAiProvider provider = defaultProvider(config.getBaseUrl(), config.getTextModel(), secretCipher.decrypt(config.getApiKeyCiphertext()), config.getProviderType(), config.isEnabled(), config.getRequestTimeoutMs());
            return new ProviderState(List.of(provider), provider.id(), provider.defaultModel(), Map.of(), Map.of(), DEFAULT_COMMAND_TIMEOUT_MS, provider);
        }
        try {
            JsonNode root = objectMapper.readTree(config.getProviderConfigJson());
            List<StoredAiProvider> providers = new ArrayList<>();
            for (JsonNode item : root.path("providers")) {
                providers.add(new StoredAiProvider(
                    blankToDefault(item.path("id").asText(""), "provider-" + providers.size()),
                    blankToDefault(item.path("providerId").asText(""), "openai-compatible"),
                    blankToDefault(item.path("name").asText(""), "Provider"),
                    blankToDefault(firstText(item, "baseURL", "baseUrl"), ""),
                    blankToDefault(item.path("defaultModel").asText(""), ""),
                    secretCipher.decrypt(blankToDefault(item.path("apiKey").asText(""), "")),
                    !item.has("enabled") || item.path("enabled").asBoolean(true),
                    item.path("credentialConfigured").asBoolean(!blankToDefault(item.path("apiKey").asText(""), "").isBlank()),
                    positiveOrDefault(item.path("requestTimeoutMs").asInt(0), config.getRequestTimeoutMs()),
                    item.path("contextWindow").asInt(0),
                    item.path("skipTLSVerify").asBoolean(false),
                    readStringMap(item.path("customHeaders"))
                ));
            }
            if (providers.isEmpty()) {
                StoredAiProvider provider = defaultProvider(config.getBaseUrl(), config.getTextModel(), secretCipher.decrypt(config.getApiKeyCiphertext()), config.getProviderType(), config.isEnabled(), config.getRequestTimeoutMs());
                return new ProviderState(List.of(provider), provider.id(), provider.defaultModel(), Map.of(), Map.of(), DEFAULT_COMMAND_TIMEOUT_MS, provider);
            }
            String activeProviderId = blankToDefault(root.path("activeProviderId").asText(""), providers.getFirst().id());
            StoredAiProvider activeProvider = providers.stream()
                .filter(provider -> provider.id().equals(activeProviderId))
                .findFirst()
                .orElse(providers.getFirst());
            String activeModel = blankToDefault(root.path("activeModel").asText(""), activeProvider.defaultModel());
            return new ProviderState(
                providers,
                activeProvider.id(),
                activeModel,
                readStringMap(root.path("agentModelMap")),
                readStringMap(root.path("agentProviderMap")),
                positiveOrDefault(root.path("commandTimeoutMs").asInt(0), DEFAULT_COMMAND_TIMEOUT_MS),
                activeProvider
            );
        } catch (JacksonException exception) {
            StoredAiProvider provider = defaultProvider(config.getBaseUrl(), config.getTextModel(), secretCipher.decrypt(config.getApiKeyCiphertext()), config.getProviderType(), config.isEnabled(), config.getRequestTimeoutMs());
            return new ProviderState(List.of(provider), provider.id(), provider.defaultModel(), Map.of(), Map.of(), DEFAULT_COMMAND_TIMEOUT_MS, provider);
        }
    }

    private String stringifyProviderState(ProviderState state) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("version", 2);
        root.put("activeProviderId", state.activeProviderId());
        root.put("activeModel", state.activeModel());
        root.put("commandTimeoutMs", state.commandTimeoutMs());
        root.set("agentModelMap", objectMapper.valueToTree(state.agentModelMap()));
        root.set("agentProviderMap", objectMapper.valueToTree(state.agentProviderMap()));
        ArrayNode providers = root.putArray("providers");
        for (StoredAiProvider provider : state.providers()) {
            ObjectNode item = providers.addObject();
            item.put("id", provider.id());
            item.put("providerId", provider.providerId());
            item.put("name", provider.name());
            item.put("baseURL", provider.baseURL());
            item.put("defaultModel", provider.defaultModel());
            item.put("apiKey", secretCipher.encrypt(provider.apiKey()));
            item.put("enabled", provider.enabled());
            item.put("credentialConfigured", provider.credentialConfigured());
            item.put("requestTimeoutMs", provider.requestTimeoutMs());
            item.put("contextWindow", provider.contextWindow());
            item.put("skipTLSVerify", provider.skipTLSVerify());
            item.set("customHeaders", objectMapper.valueToTree(provider.customHeaders()));
        }
        return root.toString();
    }

    private AiProviderResponse toProviderResponse(StoredAiProvider provider) {
        return new AiProviderResponse(
            provider.id(),
            provider.providerId(),
            provider.name(),
            provider.baseURL(),
            provider.defaultModel(),
            provider.enabled(),
            provider.credentialConfigured(),
            maskedApiKey(provider.apiKey()),
            provider.requestTimeoutMs(),
            provider.contextWindow(),
            provider.skipTLSVerify(),
            provider.customHeaders()
        );
    }

    private StoredAiProvider defaultProvider(String baseUrl, String model, String apiKey) {
        return defaultProvider(baseUrl, model, apiKey, "openai-compatible", false, DEFAULT_REQUEST_TIMEOUT_MS);
    }

    private StoredAiProvider defaultProvider(String baseUrl, String model, String apiKey, String providerType, boolean enabled, int requestTimeoutMs) {
        String providerId = blankToDefault(providerType, "openai-compatible");
        return new StoredAiProvider(
            "default",
            providerId,
            providerName(providerId),
            blankToDefault(baseUrl, ""),
            blankToDefault(model, ""),
            blankToDefault(apiKey, ""),
            enabled,
            !blankToDefault(apiKey, "").isBlank(),
            positiveOrDefault(requestTimeoutMs, DEFAULT_REQUEST_TIMEOUT_MS),
            0,
            false,
            Map.of()
        );
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private int positiveOrDefault(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private int positiveOrDefault(int value, int fallback) {
        return value <= 0 ? fallback : value;
    }

    private String maskedApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        String trimmed = apiKey.trim();
        if (trimmed.length() <= 4) {
            return "****";
        }
        return "****" + trimmed.substring(trimmed.length() - 4);
    }

    private String providerName(String providerId) {
        return switch (providerId) {
            case "openai" -> "OpenAI";
            case "anthropic" -> "Anthropic";
            case "google" -> "Google Gemini";
            case "ollama" -> "Ollama";
            case "openrouter" -> "OpenRouter";
            case "qwen" -> "Qwen";
            case "deepseek" -> "DeepSeek";
            case "kimi" -> "Kimi";
            case "zhipu" -> "Zhipu";
            case "doubao" -> "Doubao";
            default -> "OpenAI Compatible";
        };
    }

    private String firstText(JsonNode node, String first, String second) {
        String value = node.path(first).asText("");
        return value.isBlank() ? node.path(second).asText("") : value;
    }

    private Map<String, String> readStringMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, String> map = new LinkedHashMap<>();
        node.properties().forEach(entry -> map.put(entry.getKey(), entry.getValue().asText("")));
        return map;
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(
                values.stream()
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .toList()
            );
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Invalid allowed image types", exception);
        }
    }

    private List<String> parseArray(String json) {
        if (json == null || json.isBlank()) {
            return DEFAULT_ALLOWED_IMAGE_TYPES;
        }
        try {
            return objectMapper.readValue(
                json,
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)
            );
        } catch (JacksonException exception) {
            return DEFAULT_ALLOWED_IMAGE_TYPES;
        }
    }
}

record SaveAiConfigRequest(
    boolean enabled,
    String providerType,
    String baseUrl,
    String model,
    String visionModel,
    boolean visionEnabled,
    long maxImageBytes,
    List<String> allowedImageTypes,
    String apiKey,
    int requestTimeoutMs,
    List<SaveAiProviderRequest> providers,
    String activeProviderId,
    String activeModel,
    Map<String, String> agentModelMap,
    Map<String, String> agentProviderMap,
    int commandTimeoutMs
) {
}

record TestAiConfigRequest(
    String providerType,
    String baseUrl,
    String model,
    String apiKey,
    int requestTimeoutMs,
    List<SaveAiProviderRequest> providers,
    String activeProviderId,
    String activeModel,
    int commandTimeoutMs
) {
}

record SaveAiProviderRequest(
    String id,
    String providerId,
    String name,
    String baseURL,
    String defaultModel,
    String apiKey,
    Boolean enabled,
    Boolean credentialConfigured,
    Integer requestTimeoutMs,
    Integer contextWindow,
    Boolean skipTLSVerify,
    Map<String, String> customHeaders
) {
}

record AiConfigResponse(
    boolean enabled,
    String providerType,
    String baseUrl,
    String model,
    String visionModel,
    boolean visionEnabled,
    long maxImageBytes,
    List<String> allowedImageTypes,
    boolean credentialConfigured,
    String maskedApiKey,
    int requestTimeoutMs,
    List<AiProviderResponse> providers,
    String activeProviderId,
    String activeModel,
    Map<String, String> agentModelMap,
    Map<String, String> agentProviderMap,
    int commandTimeoutMs,
    String updatedAt,
    List<AiRunPolicyResponse> policies
) {
}

record AiProviderResponse(
    String id,
    String providerId,
    String name,
    String baseURL,
    String defaultModel,
    boolean enabled,
    boolean credentialConfigured,
    String maskedApiKey,
    int requestTimeoutMs,
    int contextWindow,
    boolean skipTLSVerify,
    Map<String, String> customHeaders
) {
}

record AiConnectionTestResponse(
    boolean success,
    String message
) {
}

record ProviderState(
    List<StoredAiProvider> providers,
    String activeProviderId,
    String activeModel,
    Map<String, String> agentModelMap,
    Map<String, String> agentProviderMap,
    int commandTimeoutMs,
    StoredAiProvider activeProvider
) {
}

record StoredAiProvider(
    String id,
    String providerId,
    String name,
    String baseURL,
    String defaultModel,
    String apiKey,
    boolean enabled,
    boolean credentialConfigured,
    int requestTimeoutMs,
    int contextWindow,
    boolean skipTLSVerify,
    Map<String, String> customHeaders
) {
}
