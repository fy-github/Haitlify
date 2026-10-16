package com.haitlify.aitest.platform.ai.gateway;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
class OpenAiCompatibleAdapter implements AiProviderAdapter {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    OpenAiCompatibleAdapter() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(), new ObjectMapper());
    }

    OpenAiCompatibleAdapter(HttpClient httpClient) {
        this(httpClient, new ObjectMapper());
    }

    OpenAiCompatibleAdapter(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String providerType) {
        return "openai-compatible".equals(providerType) || "openai".equals(providerType);
    }

    @Override
    public AiConnectionTestResult testConnection(AiGatewayConfig config) {
        try {
            JsonNode payload = objectMapper.createObjectNode()
                .put("model", config.model())
                .put("max_completion_tokens", 1)
                .set("messages", objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                    .put("role", "user")
                    .put("content", "ping")));
            HttpRequest.Builder requestBuilder = requestBuilder(resolveUri(config.baseUrl()), config.requestTimeoutMs())
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));

            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return new AiConnectionTestResult(true, "连接成功");
            }
            String detail = extractErrorDetail(response.body());
            String message = "连接失败: HTTP " + response.statusCode();
            if (!detail.isBlank()) {
                message += "，" + detail;
            }
            return new AiConnectionTestResult(false, message);
        } catch (Exception exception) {
            return new AiConnectionTestResult(false, "连接失败: " + exception.getMessage());
        }
    }

    @Override
    public AiGenerationResult structuredGenerate(AiGatewayConfig config, Object prompt) {
        long started = System.currentTimeMillis();
        try {
            HttpRequest.Builder requestBuilder = requestBuilder(resolveUri(config.baseUrl()), config.requestTimeoutMs())
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString("""
                    {
                      "model": "%s",
                      "messages": [
                        {
                          "role": "user",
                          "content": %s
                        }
                      ],
                      "temperature": 0.1
                    }
                    """.formatted(config.model(), objectMapper.writeValueAsString(String.valueOf(prompt)))));

            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String detail = extractErrorDetail(response.body());
                // T05：业务文案规则优先（no-channel/content-policy），否则回退状态码分类；规则版本 v1。
                com.haitlify.aitest.platform.ai.runtime.AiFailureClassifier.Classification messageRule =
                    com.haitlify.aitest.platform.ai.runtime.AiFailureClassifier.classifyMessage(detail);
                AiRetryReason reason = messageRule != null ? messageRule.reason() : classifyFailure(response.statusCode(), null);
                String message = "AI 生成失败: " + httpErrorMessage(response.statusCode());
                if (!detail.isBlank()) {
                    message += "，" + detail;
                }
                // T05 usage 语义：4xx（请求被拒、必然未处理）记 0；5xx/429/408（可能已被上游处理）记 -1=usage unknown。
                int unknownTokens = (response.statusCode() >= 500 || response.statusCode() == 429 || response.statusCode() == 408)
                    ? -1 : 0;
                return new AiGenerationResult(false, "", "", unknownTokens, unknownTokens, message, reason.retryable(), reason.name(),
                    parseRetryAfterSeconds(response));
            }
            JsonNode body = objectMapper.readTree(response.body());
            String content = extractMessageContent(body);
            int inputTokens = body.path("usage").path("prompt_tokens").asInt(0);
            int outputTokens = body.path("usage").path("completion_tokens").asInt(0);
            String requestId = response.headers().firstValue("x-request-id").orElse("req-" + UUID.randomUUID());
            if (content.isBlank()) {
                // T05 分类（v1）：refusal/content_filter ⇒ REFUSAL；finish_reason=length ⇒ INCOMPLETE_OUTPUT；
                // 其余空内容 ⇒ EMPTY_OUTPUT（可重试一次——空内容多为上游瞬态）。
                var classification = com.haitlify.aitest.platform.ai.runtime.AiFailureClassifier.classifyUnusableBody(
                    body.path("choices").path(0).path("finish_reason").asText(""), hasRefusal(body));
                return new AiGenerationResult(false, "", requestId, inputTokens, outputTokens,
                    "AI 生成失败: 上游响应的模型内容为空或不可解析", classification.retryable(), classification.reason().name());
            }
            return new AiGenerationResult(true, content, requestId, inputTokens, outputTokens, null);
        } catch (HttpTimeoutException exception) {
            AiRetryReason reason = classifyFailure(0, exception);
            // T05 usage 语义：请求已发出且未见响应 ⇒ 计费可能已发生，usage 记 -1（unknown），不得伪装成 0（exactly-once）。
            return new AiGenerationResult(false, "", "", -1, -1, "AI 生成失败: 请求超时，请减少单次输入内容或在 AI 配置中提高请求超时时间", reason.retryable(), reason.name());
        } catch (Exception exception) {
            AiRetryReason reason = classifyFailure(0, exception);
            return new AiGenerationResult(false, "", "", -1, -1, "AI 生成失败: " + exception.getMessage(), reason.retryable(), reason.name());
        } finally {
            long ignoredDurationMs = System.currentTimeMillis() - started;
        }
    }

    private String extractMessageContent(JsonNode body) {
        JsonNode content = body.path("choices").path(0).path("message").path("content");
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode part : content) {
                JsonNode value = part.path("text");
                if (value.isTextual()) {
                    text.append(value.asText());
                }
            }
            if (!text.isEmpty()) {
                return text.toString();
            }
            return serializeContent(content);
        }
        if (content.isObject()) {
            if (content.path("text").isTextual()) {
                return content.path("text").asText();
            }
            return serializeContent(content);
        }
        return "";
    }

    private String serializeContent(JsonNode content) {
        try {
            return objectMapper.writeValueAsString(content);
        } catch (Exception exception) {
            return "";
        }
    }

    @Override
    public AiVisionResponse visionAnalyze(AiGatewayConfig config, AiVisionRequest request) {
        String validationError = validateVisionRequest(config, request);
        if (!validationError.isBlank()) {
            return new AiVisionResponse(false, "", "", 0, 0, validationError);
        }

        try {
            String imageUrl = "data:%s;base64,%s".formatted(
                request.imageContentType(),
                Base64.getEncoder().encodeToString(request.imageBytes())
            );
            Map<String, Object> payload = Map.of(
                "model", config.visionModel().trim(),
                "messages", List.of(Map.of(
                    "role", "user",
                    "content", List.of(
                        Map.of("type", "text", "text", request.prompt()),
                        Map.of("type", "image_url", "image_url", Map.of("url", imageUrl))
                    )
                )),
                "temperature", 0.1,
                "max_tokens", request.maxOutputTokens() > 0 ? request.maxOutputTokens() : 1024
            );
            HttpRequest.Builder requestBuilder = requestBuilder(resolveUri(config.baseUrl()), config.requestTimeoutMs())
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));

            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new AiVisionResponse(false, "", "", 0, 0, "AI 视觉分析失败: HTTP " + response.statusCode());
            }
            JsonNode body = objectMapper.readTree(response.body());
            String content = body.path("choices").path(0).path("message").path("content").asText("");
            int inputTokens = body.path("usage").path("prompt_tokens").asInt(0);
            int outputTokens = body.path("usage").path("completion_tokens").asInt(0);
            String requestId = response.headers().firstValue("x-request-id").orElse("req-" + UUID.randomUUID());
            return new AiVisionResponse(true, content, requestId, inputTokens, outputTokens, null);
        } catch (Exception exception) {
            return new AiVisionResponse(false, "", "", 0, 0, "AI 视觉分析失败: " + exception.getMessage());
        }
    }

    private String validateVisionRequest(AiGatewayConfig config, AiVisionRequest request) {
        if (config.visionModel() == null || config.visionModel().isBlank()) {
            return "Missing vision model";
        }
        if (request.prompt() == null || request.prompt().isBlank()) {
            return "Missing vision prompt";
        }
        if (request.imageContentType() == null || request.imageContentType().isBlank()) {
            return "Missing image content type";
        }
        if (request.imageBytes() == null || request.imageBytes().length == 0) {
            return "Missing image content";
        }
        return "";
    }

    private URI resolveUri(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (!normalized.endsWith("/chat/completions")) {
            normalized = normalized + "/chat/completions";
        }
        return URI.create(normalized);
    }

    private HttpRequest.Builder requestBuilder(URI uri, int requestTimeoutMs) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri);
        if (requestTimeoutMs > 0) {
            builder.timeout(Duration.ofMillis(requestTimeoutMs));
        }
        return builder;
    }

    private AiRetryReason classifyFailure(int statusCode, Throwable error) {
        return com.haitlify.aitest.platform.ai.runtime.AiFailureClassifier.classify(statusCode, error).reason();
    }

    private boolean hasRefusal(JsonNode body) {
        JsonNode refusal = body.path("choices").path(0).path("message").path("refusal");
        return refusal.isTextual() && !refusal.asText().isBlank();
    }

    /** T05：解析 Retry-After（数字秒；HTTP-date 形式由策略层处理时另行提供）。缺省/非法返回 -1。 */
    private long parseRetryAfterSeconds(HttpResponse<String> response) {
        String value = response.headers().firstValue("retry-after").orElse("");
        if (value.isBlank()) {
            return -1;
        }
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds < 0 ? -1 : seconds * 1_000L;
        } catch (NumberFormatException notSeconds) {
            // L3：HTTP-date（RFC1123）形式亦有生产入口——按当前时间换算剩余毫秒。
            try {
                java.time.ZonedDateTime at = java.time.ZonedDateTime.parse(value.trim(),
                    java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
                long delay = java.time.Duration.between(java.time.Instant.now(), at.toInstant()).toMillis();
                return delay < 0 ? 0 : delay;
            } catch (java.time.format.DateTimeParseException notDate) {
                return -1;
            }
        }
    }

    private String httpErrorMessage(int statusCode) {
        if (statusCode == 401 || statusCode == 403) {
            return "HTTP " + statusCode + "，当前 API Key、Base URL 或模型没有访问权限，请在 AI 配置中检查模型名称与密钥权限";
        }
        if (statusCode == 404) {
            return "HTTP 404，模型或接口地址不存在，请检查 AI 配置中的 Base URL 与模型名称";
        }
        if (statusCode == 408 || statusCode == 429 || statusCode >= 500) {
            return "HTTP " + statusCode + "，AI 服务暂时不可用或限流，请稍后重试";
        }
        return "HTTP " + statusCode;
    }

    private String extractErrorDetail(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String message = root.path("error").path("message").asText("");
            if (!message.isBlank()) {
                return message;
            }
            message = root.path("message").asText("");
            if (!message.isBlank()) {
                return message;
            }
        } catch (Exception ignored) {
            return responseBody.length() > 160 ? responseBody.substring(0, 160) + "…" : responseBody;
        }
        return responseBody.length() > 160 ? responseBody.substring(0, 160) + "…" : responseBody;
    }
}
