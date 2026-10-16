package com.haitlify.aitest.platform.ai.runtime.transport;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.haitlify.aitest.platform.ai.runtime.AiProtocolResponse;
import com.haitlify.aitest.platform.ai.runtime.ChatCompletionsProtocolAdapter;
import com.haitlify.aitest.platform.ai.runtime.ResponsesProtocolAdapter;
import com.haitlify.aitest.platform.ai.runtime.AiRuntimeModels.Protocol;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public final class AiProtocolTransportClient {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public AiProtocolTransportClient(ObjectMapper objectMapper) {
        this(HttpClient.newHttpClient(), objectMapper);
    }

    public AiProtocolTransportClient(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public AiTransportResult execute(AiTransportRequest request) throws Exception {
        HttpRequest httpRequest = switch (request.protocol()) {
            case CHAT_COMPLETIONS -> createChatRequest(request);
            case RESPONSES -> createResponsesRequest(request);
        };
        long startedNanos = System.nanoTime();
        long[] ttfbNanos = new long[] {-1L};
        HttpResponse<String> response = httpClient.send(httpRequest, info -> {
            // BodyHandler 在响应头到达时被调用：非流式请求下即首个可读字节的上界。
            ttfbNanos[0] = System.nanoTime() - startedNanos;
            return HttpResponse.BodyHandlers.ofString().apply(info);
        });
        long durationNanos = System.nanoTime() - startedNanos;
        JsonNode body = objectMapper.readTree(response.body());
        AiProtocolResponse normalized = request.protocol() == Protocol.CHAT_COMPLETIONS
            ? new ChatCompletionsProtocolAdapter().normalize(body)
            : new ResponsesProtocolAdapter().normalize(body);
        Long ttfbMillis = ttfbNanos[0] < 0 ? null : ttfbNanos[0] / 1_000_000L;
        return new AiTransportResult(response.statusCode(), normalized, durationNanos / 1_000_000L, ttfbMillis);
    }

    private HttpRequest createChatRequest(AiTransportRequest request) throws Exception {
        return new ChatCompletionsTransport().createRequest(request, objectMapper);
    }

    private HttpRequest createResponsesRequest(AiTransportRequest request) throws Exception {
        var payload = objectMapper.createObjectNode()
            .put("model", request.model())
            .put("input", request.prompt());
        // T03：契约 schema 按 RESPONSES_TEXT_FORMAT 目标编译为 text.format 的完整形态；
        // 无 schema 时退回 text.format.type=json_object（仅 JSON_OBJECT 能力）；strict 编译失败即抛错。
        if (request.outputSchema() != null && !request.outputSchema().isNull()
                && request.capabilities().contains(com.haitlify.aitest.platform.ai.runtime.AiRuntimeModels.Capability.JSON_SCHEMA)) {
            var compiled = new com.haitlify.aitest.platform.ai.runtime.output.AiSchemaCompiler(objectMapper)
                .compile(request.outputSchema(), com.haitlify.aitest.platform.ai.runtime.output.AiSchemaCompiler.Target.RESPONSES_TEXT_FORMAT);
            var format = objectMapper.createObjectNode()
                .put("type", "json_schema")
                .put("name", "structured_output")
                .put("strict", true);
            format.set("schema", objectMapper.readTree(compiled.schemaJsonBytes()));
            payload.set("text", objectMapper.createObjectNode().set("format", format));
        } else if (request.capabilities().contains(com.haitlify.aitest.platform.ai.runtime.AiRuntimeModels.Capability.JSON_OBJECT)) {
            payload.set("text", objectMapper.createObjectNode().set(
                "format", objectMapper.createObjectNode().put("type", "json_object")));
        }
        if (request.parameterPolicy().containsKey("max_output_tokens")) {
            payload.put("max_output_tokens", ((Number) request.parameterPolicy().get("max_output_tokens")).intValue());
        }
        return HttpRequest.newBuilder(AiEndpointResolver.resolve(request.baseUrl(), Protocol.RESPONSES))
            .timeout(request.timeout())
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + request.bearerToken())
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
            .build();
    }
}
