package com.haitlify.aitest.platform.ai.tool.mcp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import com.haitlify.aitest.platform.security.SecretCipher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public final class AiMcpRemoteTransport {
    private static final String JSON_RPC_VERSION = "2.0";
    private static final String MCP_PROTOCOL_VERSION = "2024-11-05";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final SecretCipher secretCipher;

    @Autowired
    AiMcpRemoteTransport(ObjectMapper objectMapper, SecretCipher secretCipher) {
        this(HttpClient.newHttpClient(), objectMapper, secretCipher);
    }

    AiMcpRemoteTransport(HttpClient httpClient, ObjectMapper objectMapper, SecretCipher secretCipher) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.secretCipher = secretCipher;
    }

    public AiMcpObservedResult call(McpRemoteRequest request) throws Exception {
        if (!supportsHttp(request.serverType())) {
            throw new IllegalArgumentException("MCP server type is not supported for remote HTTP transport: " + request.serverType());
        }
        if (!isNoAuth(request.authMode())) {
            if (request.authSecretRef() == null || request.authSecretRef().isBlank() || request.authSecretCiphertext() == null || request.authSecretCiphertext().isBlank()) {
                throw new IllegalArgumentException("MCP auth mode requires authSecretRef and stored secret");
            }
        }
        URI endpoint = URI.create(request.endpoint());
        String sessionId = initialize(endpoint, request.timeout(), request.authMode(), request.authSecretCiphertext());
        notifyInitialized(endpoint, request.timeout(), sessionId, request.authMode(), request.authSecretCiphertext());
        listTools(endpoint, request.timeout(), sessionId, request.authMode(), request.authSecretCiphertext());
        String callId = UUID.randomUUID().toString();
        ObjectNode call = rpc("tools/call", callId, request.toolName(), request.arguments());
        HttpResponse<String> response = send(endpoint, call, request.timeout(), sessionId, request.authMode(), request.authSecretCiphertext());
        JsonNode body = parse(response);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("MCP HTTP status " + response.statusCode());
        }
        if (body.path("error").isObject()) {
            throw new IllegalStateException("MCP tool error: " + body.path("error").path("message").asText("unknown"));
        }
        JsonNode result = body.path("result");
        if (!result.isObject()) throw new IllegalStateException("MCP response result is missing");
        if (result.path("isError").asBoolean(false)) throw new IllegalStateException("MCP tool returned isError=true");
        return new AiMcpObservedResult(result.deepCopy(), response.statusCode(), sessionId, callId);
    }

    /** T09 bullet 3：获取远端 tools/list（schema/元数据的权威来源）。 */
    public tools.jackson.databind.node.ArrayNode tools(McpRemoteRequest request) throws Exception {
        if (!supportsHttp(request.serverType())) {
            throw new IllegalArgumentException("MCP server type is not supported for remote HTTP transport: " + request.serverType());
        }
        if (!isNoAuth(request.authMode())) {
            if (request.authSecretRef() == null || request.authSecretRef().isBlank() || request.authSecretCiphertext() == null || request.authSecretCiphertext().isBlank()) {
                throw new IllegalArgumentException("MCP auth mode requires authSecretRef and stored secret");
            }
        }
        URI endpoint = URI.create(request.endpoint());
        String sessionId = initialize(endpoint, request.timeout(), request.authMode(), request.authSecretCiphertext());
        notifyInitialized(endpoint, request.timeout(), sessionId, request.authMode(), request.authSecretCiphertext());
        HttpResponse<String> response = send(endpoint, rpc("tools/list", objectMapper.createObjectNode()), request.timeout(), sessionId, request.authMode(), request.authSecretCiphertext());
        JsonNode body = parse(response);
        if (response.statusCode() < 200 || response.statusCode() >= 300 || body.path("error").isObject() || !body.path("result").isObject()) {
            throw new IllegalStateException("MCP tools/list failed");
        }
        if (!body.path("result").path("tools").isArray()) throw new IllegalStateException("MCP tools/list result is missing tools");
        return (tools.jackson.databind.node.ArrayNode) body.path("result").path("tools");
    }

    /** T09 bullet 2：取消——向同一会话发送 notifications/cancelled，尽力而为（远端可忽略）。 */
    void cancel(McpRemoteRequest request, String sessionId, String callId, String reason) throws Exception {
        if (!supportsHttp(request.serverType())) {
            throw new IllegalArgumentException("MCP server type is not supported for remote HTTP transport: " + request.serverType());
        }
        ObjectNode params = objectMapper.createObjectNode()
            .put("requestId", callId)
            .put("reason", reason == null ? "" : reason);
        ObjectNode notification = objectMapper.createObjectNode()
            .put("jsonrpc", JSON_RPC_VERSION)
            .put("method", "notifications/cancelled")
            .set("params", params);
        HttpResponse<String> response = send(URI.create(request.endpoint()), notification, request.timeout(), sessionId,
            request.authMode(), request.authSecretCiphertext());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("MCP cancelled notification failed");
        }
    }

    private String initialize(URI endpoint, Duration timeout, String authMode, String authSecretCiphertext) throws Exception {
        ObjectNode params = objectMapper.createObjectNode()
            .put("protocolVersion", MCP_PROTOCOL_VERSION)
            .set("capabilities", objectMapper.createObjectNode())
            .set("clientInfo", objectMapper.createObjectNode().put("name", "haitlify-ai-test-platform").put("version", "1"));
        HttpResponse<String> response = send(endpoint, rpc("initialize", params), timeout, null, authMode, authSecretCiphertext);
        JsonNode body = parse(response);
        if (response.statusCode() < 200 || response.statusCode() >= 300 || body.path("error").isObject()) {
            throw new IllegalStateException("MCP initialize failed");
        }
        return response.headers().firstValue("Mcp-Session-Id").orElse("");
    }

    private void notifyInitialized(URI endpoint, Duration timeout, String sessionId, String authMode, String authSecretCiphertext) throws Exception {
        ObjectNode notification = objectMapper.createObjectNode()
            .put("jsonrpc", JSON_RPC_VERSION)
            .put("method", "notifications/initialized")
            .set("params", objectMapper.createObjectNode());
        HttpResponse<String> response = send(endpoint, notification, timeout, sessionId, authMode, authSecretCiphertext);
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("MCP initialized notification failed");
    }
    private void listTools(URI endpoint, Duration timeout, String sessionId, String authMode, String authSecretCiphertext) throws Exception {
        HttpResponse<String> response = send(endpoint, rpc("tools/list", objectMapper.createObjectNode()), timeout, sessionId, authMode, authSecretCiphertext);
        JsonNode body = parse(response);
        if (response.statusCode() < 200 || response.statusCode() >= 300 || body.path("error").isObject() || !body.path("result").isObject()) {
            throw new IllegalStateException("MCP tools/list failed");
        }
        if (!body.path("result").path("tools").isArray()) throw new IllegalStateException("MCP tools/list result is missing tools");
    }
    private ObjectNode rpc(String method, String callId, String toolName, JsonNode arguments) {
        ObjectNode params = objectMapper.createObjectNode()
            .put("name", toolName)
            .set("arguments", arguments == null ? objectMapper.createObjectNode() : arguments.deepCopy());
        return rpc(method, callId, params);
    }

    private ObjectNode rpc(String method, JsonNode params) {
        return rpc(method, UUID.randomUUID().toString(), params);
    }

    private ObjectNode rpc(String method, String id, JsonNode params) {
        return objectMapper.createObjectNode()
            .put("jsonrpc", JSON_RPC_VERSION)
            .put("id", id)
            .put("method", method)
            .set("params", params);
    }

    private HttpResponse<String> send(URI endpoint, ObjectNode payload, Duration timeout, String sessionId, String authMode, String authSecretCiphertext) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
            .timeout(timeout == null ? Duration.ofSeconds(30) : timeout)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
        if (!isNoAuth(authMode)) builder.header("Authorization", "Bearer " + secretCipher.decrypt(authSecretCiphertext));
        if (sessionId != null && !sessionId.isBlank()) builder.header("Mcp-Session-Id", sessionId);
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode parse(HttpResponse<String> response) throws Exception {
        String body = response.body() == null ? "" : response.body().strip();
        if (body.startsWith("data:")) body = body.substring(body.indexOf(':') + 1).strip();
        return objectMapper.readTree(body);
    }

    private boolean supportsHttp(String serverType) {
        return "http".equalsIgnoreCase(serverType)
            || "sse".equalsIgnoreCase(serverType)
            || "streamable_http".equalsIgnoreCase(serverType)
            || "streamable-http".equalsIgnoreCase(serverType);
    }

    private boolean isNoAuth(String authMode) {
        return authMode == null || authMode.isBlank() || "none".equalsIgnoreCase(authMode);
    }
}
