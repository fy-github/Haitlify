package com.haitlify.aitest.platform.ai.runtime.transport;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.haitlify.aitest.platform.ai.runtime.AiRuntimeModels.Capability;
import com.haitlify.aitest.platform.ai.runtime.AiRuntimeModels.Protocol;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

public record AiTransportRequest(
    Protocol protocol,
    String baseUrl,
    String model,
    String bearerToken,
    String prompt,
    List<Capability> capabilities,
    Map<String, Object> parameterPolicy,
    Duration timeout,
    tools.jackson.databind.JsonNode outputSchema
) {
    /** 兼容既有调用方：未携带输出契约 schema 时行为与之前完全一致。 */
    public AiTransportRequest(
        Protocol protocol,
        String baseUrl,
        String model,
        String bearerToken,
        String prompt,
        List<Capability> capabilities,
        Map<String, Object> parameterPolicy,
        Duration timeout
    ) {
        this(protocol, baseUrl, model, bearerToken, prompt, capabilities, parameterPolicy, timeout, null);
    }

    public AiTransportRequest {
        if (protocol == null || baseUrl == null || baseUrl.isBlank() || model == null || model.isBlank()) {
            throw new IllegalArgumentException("protocol/baseUrl/model required");
        }
        if (bearerToken == null || bearerToken.isBlank()) throw new IllegalArgumentException("bearerToken required");
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        parameterPolicy = parameterPolicy == null ? Map.of() : Map.copyOf(parameterPolicy);
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
    }
}

final class AiEndpointResolver {
    private AiEndpointResolver() {}

    static URI resolve(String baseUrl, Protocol protocol) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        if (normalized.isBlank() || protocol == null) throw new IllegalArgumentException("baseUrl/protocol required");
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        String suffix = protocol == Protocol.CHAT_COMPLETIONS ? "/chat/completions" : "/responses";
        if (normalized.endsWith("/chat/completions") || normalized.endsWith("/responses")) {
            if (!normalized.endsWith(suffix)) throw new IllegalArgumentException("endpoint does not match explicit protocol");
            return URI.create(normalized);
        }
        return URI.create(normalized + suffix);
    }
}

final class ChatCompletionsTransport {
    String createPayload(AiTransportRequest request, ObjectMapper mapper) throws Exception {
        var payload = mapper.createObjectNode()
            .put("model", request.model())
            .set("messages", mapper.createArrayNode().add(mapper.createObjectNode()
                .put("role", "user")
                .put("content", request.prompt())));
        addOptionalParameters(payload, request, mapper);
        return mapper.writeValueAsString(payload);
    }

    HttpRequest createRequest(AiTransportRequest request, ObjectMapper mapper) throws Exception {
        String payload = createPayload(request, mapper);
        return HttpRequest.newBuilder(AiEndpointResolver.resolve(request.baseUrl(), Protocol.CHAT_COMPLETIONS))
            .timeout(request.timeout())
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + request.bearerToken())
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();
    }

    private void addOptionalParameters(ObjectNode payload, AiTransportRequest request, tools.jackson.databind.ObjectMapper mapper) {
        // T03：契约 schema 走 native json_schema（按 CHAT_JSON_SCHEMA 目标编译；strict 方言不支持的关键字
        // 一律抛错、不静默降级）；无 schema 或仅 JSON_OBJECT 能力时退回 json_object；两者皆无则不发格式。
        if (request.outputSchema() != null && !request.outputSchema().isNull()
                && request.capabilities().contains(Capability.JSON_SCHEMA)) {
            var compiled = new com.haitlify.aitest.platform.ai.runtime.output.AiSchemaCompiler(mapper)
                .compile(request.outputSchema(), com.haitlify.aitest.platform.ai.runtime.output.AiSchemaCompiler.Target.CHAT_JSON_SCHEMA);
            ObjectNode jsonSchemaBlock = mapper.createObjectNode()
                .put("name", "structured_output")
                .put("strict", true);
            jsonSchemaBlock.set("schema", mapper.readTree(compiled.schemaJsonBytes()));
            payload.set("response_format", mapper.createObjectNode()
                .put("type", "json_schema")
                .set("json_schema", jsonSchemaBlock));
        } else if (request.capabilities().contains(Capability.JSON_OBJECT)) {
            payload.put("response_format", "json_object");
        }
        if (request.parameterPolicy().containsKey("temperature")) payload.set("temperature", mapper.valueToTree(request.parameterPolicy().get("temperature")));
        if (request.parameterPolicy().containsKey("max_tokens")) payload.put("max_tokens", ((Number) request.parameterPolicy().get("max_tokens")).intValue());
    }
}
