package com.haitlify.aitest.platform.ai.runtime.transport;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.haitlify.aitest.platform.ai.runtime.AiRuntimeModels.Capability;
import com.haitlify.aitest.platform.ai.runtime.AiRuntimeModels.Protocol;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AiProtocolTransportTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void resolvesExplicitProtocolEndpointsWithoutGuessingModelNames() {
        assertEquals(
            "https://provider.test/v1/chat/completions",
            AiEndpointResolver.resolve("https://provider.test/v1", Protocol.CHAT_COMPLETIONS).toString()
        );
        assertEquals(
            "https://provider.test/v1/responses",
            AiEndpointResolver.resolve("https://provider.test/v1/responses", Protocol.RESPONSES).toString()
        );
        assertThrows(IllegalArgumentException.class,
            () -> AiEndpointResolver.resolve("https://provider.test/v1/chat/completions", Protocol.RESPONSES));
    }

    @Test
    void omitsUndeclaredOptionalChatParameters() throws Exception {
        var request = new AiTransportRequest(
            Protocol.CHAT_COMPLETIONS,
            "https://provider.test/v1",
            "model-with-special-\"-name",
            "token-under-test",
            "Return JSON",
            List.of(Capability.TEXT),
            Map.of(),
            Duration.ofSeconds(3)
        );
        var body = mapper.readTree(new ChatCompletionsTransport().createPayload(request, mapper));
        assertEquals("model-with-special-\"-name", body.path("model").asText());
        assertEquals("Return JSON", body.path("messages").path(0).path("content").asText());
        assertFalse(body.has("temperature"));
        assertFalse(body.has("max_tokens"));
    }

    @Test
    void invokesRealLocalHttpServerAndNormalizesResponsesPayload() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/responses", exchange -> {
            assertEquals("Bearer live-test-token", exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            byte[] response = "{\"id\":\"resp-live-1\",\"status\":\"completed\",\"output\":[{\"type\":\"reasoning\",\"content\":[{\"type\":\"output_text\",\"text\":\"not-final\"}]},{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"{\\\"items\\\":[{\\\"title\\\":\\\"Observed\\\"}]}\"}]}],\"usage\":{\"input_tokens\":7,\"output_tokens\":9}}".getBytes();
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            var request = new AiTransportRequest(
                Protocol.RESPONSES, baseUrl, "real-test-model", "live-test-token", "Create two items",
                List.of(Capability.TEXT, Capability.JSON_SCHEMA), Map.of(), Duration.ofSeconds(3)
            );
            var result = new AiProtocolTransportClient(HttpClient.newHttpClient(), mapper)
                .execute(request);
            assertEquals(200, result.httpStatus());
            assertEquals("resp-live-1", result.response().providerRequestId());
            assertEquals("{\"items\":[{\"title\":\"Observed\"}]}", result.response().text());
            assertEquals(7, result.response().usage().inputTokens());
            assertTrue(result.response().hasUsableText());
        } finally {
            server.stop(0);
        }
    }

    // ---------------------------------------------------------------- T03：契约 schema 的协议接线

    @Test
    void chatNativeJsonSchemaUsesStrictResponseFormatWhenContractIsPresent() throws Exception {
        var schema = mapper.readTree("""
            {"type":"object","required":["draftTestPoints"],"properties":{"draftTestPoints":{"type":"array","minItems":1}}}
            """);
        var request = new AiTransportRequest(
            Protocol.CHAT_COMPLETIONS, "https://provider.test/v1", "model", "token", "Analyze",
            List.of(Capability.TEXT, Capability.JSON_SCHEMA), Map.of(), Duration.ofSeconds(3), schema
        );
        var body = mapper.readTree(new ChatCompletionsTransport().createPayload(request, mapper));
        assertEquals("json_schema", body.path("response_format").path("type").asText());
        assertTrue(body.path("response_format").path("json_schema").path("strict").asBoolean());
        assertEquals("structured_output", body.path("response_format").path("json_schema").path("name").asText());
        assertEquals("array", body.path("response_format").path("json_schema").path("schema")
            .path("properties").path("draftTestPoints").path("type").asText());
    }

    @Test
    void chatFallsBackToJsonObjectWithoutContractAndFailsClosedOnStrictUnsupportedKeywords() throws Exception {
        var jsonObjectOnly = new AiTransportRequest(
            Protocol.CHAT_COMPLETIONS, "https://provider.test/v1", "model", "token", "Analyze",
            List.of(Capability.TEXT, Capability.JSON_OBJECT, Capability.JSON_SCHEMA), Map.of(), Duration.ofSeconds(3)
        );
        var fallbackBody = mapper.readTree(new ChatCompletionsTransport().createPayload(jsonObjectOnly, mapper));
        assertEquals("json_object", fallbackBody.path("response_format").asText());

        var patterned = mapper.readTree("{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\",\"pattern\":\"^a\"}}}");
        var strictRequest = new AiTransportRequest(
            Protocol.CHAT_COMPLETIONS, "https://provider.test/v1", "model", "token", "Analyze",
            List.of(Capability.TEXT, Capability.JSON_SCHEMA), Map.of(), Duration.ofSeconds(3), patterned
        );
        assertThrows(IllegalArgumentException.class,
            () -> new ChatCompletionsTransport().createPayload(strictRequest, mapper));
    }

    @Test
    void responsesTextFormatCarriesTheCompiledContract() throws Exception {
        var schema = mapper.readTree("{\"type\":\"object\",\"required\":[\"items\"]}");
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        var seen = new java.util.concurrent.atomic.AtomicReference<String>("");
        server.createContext("/v1/responses", exchange -> {
            seen.set(new String(exchange.getRequestBody().readAllBytes()));
            byte[] response = "{\"id\":\"resp-1\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"{}\"}]}],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}".getBytes();
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            var live = new AiTransportRequest(
                Protocol.RESPONSES, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "model", "token", "Analyze",
                List.of(Capability.TEXT, Capability.JSON_SCHEMA), Map.of(), Duration.ofSeconds(3), schema
            );
            new AiProtocolTransportClient(HttpClient.newHttpClient(), mapper).execute(live);
            var body = mapper.readTree(seen.get());
            assertEquals("json_schema", body.path("text").path("format").path("type").asText());
            assertTrue(body.path("text").path("format").path("strict").asBoolean());
            assertEquals("items", body.path("text").path("format").path("schema").path("required").path(0).asText());
        } finally {
            server.stop(0);
        }
    }

    // ---------------------------------------------------------------- Task 4：streaming 立场（未验证 ⇒ 不得宣称支持）

    @Test
    void transportNeverRequestsStreamingForEitherProtocol() throws Exception {
        // Chat：payload 无 stream/stream_options——当前环境无已验证的 streaming 档案，
        // 平台结构性走非流式，且不得以现场请求冒充“streaming supported”。
        var chatRequest = new AiTransportRequest(
            Protocol.CHAT_COMPLETIONS, "https://provider.test/v1", "model", "token", "Analyze",
            List.of(Capability.TEXT), Map.of(), Duration.ofSeconds(3));
        var chatBody = mapper.readTree(new ChatCompletionsTransport().createPayload(chatRequest, mapper));
        assertFalse(chatBody.has("stream"));
        assertFalse(chatBody.has("stream_options"));

        // Responses：真实出站请求体同样不得携带 stream。
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        var seen = new java.util.concurrent.atomic.AtomicReference<String>("");
        server.createContext("/v1/responses", exchange -> {
            seen.set(new String(exchange.getRequestBody().readAllBytes()));
            byte[] response = "{\"id\":\"resp-1\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"{}\"}]}],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}".getBytes();
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            var live = new AiTransportRequest(
                Protocol.RESPONSES, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "model", "token", "Analyze", List.of(Capability.TEXT), Map.of(), Duration.ofSeconds(3));
            new AiProtocolTransportClient(HttpClient.newHttpClient(), mapper).execute(live);
            var responsesBody = mapper.readTree(seen.get());
            assertFalse(responsesBody.has("stream"));
            assertFalse(responsesBody.has("stream_options"));
        } finally {
            server.stop(0);
        }
    }
}
