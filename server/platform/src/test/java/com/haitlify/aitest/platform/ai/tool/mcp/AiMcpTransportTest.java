package com.haitlify.aitest.platform.ai.tool.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.haitlify.aitest.platform.security.SecretCipher;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * T09 bullet 1 + Exit Gate：真实本地 MCP fixture——initialize → notifications/initialized →
 * tools/list → tools/call 全链路；tools/call 结果携带 fixture 侧递增 nonce（远端状态），
 * 相同参数两次调用必须得到不同结果 ⇒ 任何「只回显 arguments 的旧 success 路径」都会被
 * 本测试判红（回显者拿不出 nonce）。另覆盖 list/call 错误、isError、超时、非 200 与 SSE 帧。
 */
class AiMcpTransportTest {

    private static HttpServer fixture;
    private static final AtomicInteger CALL_NONCE = new AtomicInteger(1000);
    private static final AtomicInteger SESSION_COUNTER = new AtomicInteger(0);
    private static final List<String> CANCELLED_FRAMES = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void startFixture() throws IOException {
        fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/mcp", exchange -> handle(exchange, "ok"));
        fixture.createContext("/mcp-list-error", exchange -> handle(exchange, "list_error"));
        fixture.createContext("/mcp-call-error", exchange -> handle(exchange, "call_error"));
        fixture.createContext("/mcp-is-error", exchange -> handle(exchange, "is_error"));
        fixture.createContext("/mcp-timeout", exchange -> handle(exchange, "timeout"));
        fixture.createContext("/mcp-http-500", exchange -> handle(exchange, "http_500"));
        fixture.createContext("/mcp-sse", exchange -> handle(exchange, "sse"));
        fixture.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        fixture.start();
    }

    @AfterAll
    static void stopFixture() {
        fixture.stop(0);
    }

    private static void handle(HttpExchange exchange, String mode) throws IOException {
        try {
            byte[] requestBytes = exchange.getRequestBody().readAllBytes();
            if ("timeout".equals(mode)) {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            String body = new String(requestBytes, StandardCharsets.UTF_8);
            JsonNode request = MAPPER.readTree(body);
            String method = request.path("method").asText("");
            String id = request.path("id").asText("");
            if ("http_500".equals(mode)) {
                respond(exchange, 500, "{}", false);
                return;
            }
            switch (method) {
                case "initialize" -> {
                    exchange.getResponseHeaders().add("Mcp-Session-Id",
                        "fixture-session-" + SESSION_COUNTER.incrementAndGet());
                    respond(exchange, 200, rpcResult(id, MAPPER.createObjectNode()
                        .put("protocolVersion", "2024-11-05")
                        .set("capabilities", MAPPER.createObjectNode())
                        .set("serverInfo", MAPPER.createObjectNode().put("name", "fixture").put("version", "1"))), false);
                }
                case "notifications/initialized" -> respond(exchange, 202, "", false);
                case "notifications/cancelled" -> {
                    CANCELLED_FRAMES.add(request.path("params").path("requestId").asText("")
                        + "|" + request.path("params").path("reason").asText(""));
                    respond(exchange, 202, "", false);
                }
                case "tools/list" -> {
                    if ("list_error".equals(mode)) {
                        respond(exchange, 200, rpcError(id, -32603, "fixture tools/list exploded"), false);
                        return;
                    }
                    ObjectNode result = MAPPER.createObjectNode();
                    result.putArray("tools").addObject()
                        .put("name", "fixture.lookup")
                        .put("description", "fixture lookup tool")
                        .set("inputSchema", MAPPER.createObjectNode().put("type", "object"));
                    respond(exchange, 200, rpcResult(id, result), false);
                }
                case "tools/call" -> {
                    if ("call_error".equals(mode)) {
                        respond(exchange, 200, rpcError(id, -32000, "fixture tool call exploded"), false);
                        return;
                    }
                    if ("is_error".equals(mode)) {
                        ObjectNode result = MAPPER.createObjectNode().put("isError", true);
                        result.putArray("content").addObject().put("type", "text").put("text", "boom");
                        respond(exchange, 200, rpcResult(id, result), false);
                        return;
                    }
                    // 远端状态：每次调用返回 fixture 自增 nonce——回显 arguments 的实现拿不到它。
                    ObjectNode result = MAPPER.createObjectNode();
                    result.putArray("content").addObject()
                        .put("type", "text")
                        .put("text", "fixture-echo-nonce:" + CALL_NONCE.incrementAndGet());
                    result.put("resultFrom", "fixture-state");
                    respond(exchange, 200, rpcResult(id, result), "sse".equals(mode));
                }
                default -> respond(exchange, 200, rpcResult(id, MAPPER.createObjectNode()), false);
            }
        } catch (Exception ignored) {
            respond(exchange, 500, "{}", false);
        }
    }

    private static String rpcResult(String id, JsonNode result) {
        ObjectNode node = MAPPER.createObjectNode()
            .put("jsonrpc", "2.0")
            .put("id", id);
        node.set("result", result);
        return MAPPER.writeValueAsString(node);
    }

    private static String rpcError(String id, int code, String message) {
        ObjectNode node = MAPPER.createObjectNode()
            .put("jsonrpc", "2.0")
            .put("id", id);
        node.set("error", MAPPER.createObjectNode().put("code", code).put("message", message));
        return MAPPER.writeValueAsString(node);
    }

    private static void respond(HttpExchange exchange, int status, String body, boolean sse) throws IOException {
        byte[] bytes = (sse ? "data: " + body : body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", sse ? "text/event-stream" : "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    private AiMcpRemoteTransport transport() {
        return new AiMcpRemoteTransport(HttpClient.newHttpClient(), new ObjectMapper(), mock(SecretCipher.class));
    }

    private McpRemoteRequest request(String path, int timeoutMs, JsonNode arguments) {
        return new McpRemoteRequest("http", base() + path, "none", null, null, timeoutMs, "fixture.lookup", arguments);
    }

    private static String base() {
        return "http://127.0.0.1:" + fixture.getAddress().getPort();
    }

    @Test
    void identicalArgumentsStillYieldServerSideVaryingResults() throws Exception {
        AiMcpRemoteTransport transport = transport();
        ObjectNode arguments = MAPPER.createObjectNode().put("query", "same-arguments");

        AiMcpObservedResult first = transport.call(request("/mcp", 5000, arguments));
        AiMcpObservedResult second = transport.call(request("/mcp", 5000, arguments));

        String firstText = first.result().path("content").get(0).path("text").asText();
        String secondText = second.result().path("content").get(0).path("text").asText();
        // Exit Gate：结果必须来自远端状态（nonce 递增）——相同参数两次调用结果不同，
        // 任何只回显 arguments 的旧 success 路径都拿不到 nonce，必然在此失败。
        assertThat(firstText).startsWith("fixture-echo-nonce:");
        assertThat(secondText).startsWith("fixture-echo-nonce:");
        assertThat(firstText).isNotEqualTo(secondText);
        assertThat(firstText).doesNotContain("same-arguments");
        assertThat(first.result().path("resultFrom").asText()).isEqualTo("fixture-state");
        assertThat(first.sessionId()).startsWith("fixture-session-");
        assertThat(first.callId()).isNotBlank();
        assertThat(first.httpStatus()).isEqualTo(200);
        // 审计字段：call id 必须来自真实调用（每次不同），不能是常量占位。
        assertThat(first.callId()).isNotEqualTo(second.callId());
    }

    @Test
    void sseFramedResponsesAreParsed() throws Exception {
        AiMcpObservedResult observed = transport().call(request("/mcp-sse", 5000, MAPPER.createObjectNode()));
        assertThat(observed.result().path("content").get(0).path("text").asText())
            .startsWith("fixture-echo-nonce:");
    }

    @Test
    void toolsListErrorsFailClosed() {
        assertThatThrownBy(() -> transport().call(request("/mcp-list-error", 5000, MAPPER.createObjectNode())))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("tools/list");
    }

    @Test
    void toolCallErrorsFailClosed() {
        assertThatThrownBy(() -> transport().call(request("/mcp-call-error", 5000, MAPPER.createObjectNode())))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("MCP tool error");
    }

    @Test
    void isErrorResultsFailClosed() {
        assertThatThrownBy(() -> transport().call(request("/mcp-is-error", 5000, MAPPER.createObjectNode())))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("isError");
    }

    @Test
    void non2xxStatusFailsClosed() {
        assertThatThrownBy(() -> transport().call(request("/mcp-http-500", 5000, MAPPER.createObjectNode())))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("MCP initialize failed");
    }

    @Test
    void timeoutsAreHonored() {
        assertThatThrownBy(() -> transport().call(request("/mcp-timeout", 300, MAPPER.createObjectNode())))
            .isInstanceOfAny(HttpTimeoutException.class, java.io.IOException.class);
    }

    @Test
    void unsupportedServerTypeIsRejectedBeforeAnyCall() {
        McpRemoteRequest stdio = new McpRemoteRequest("stdio", base() + "/mcp", "none", null, null, 1000,
            "fixture.lookup", MAPPER.createObjectNode());
        assertThatThrownBy(() -> transport().call(stdio))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not supported");
    }

    @Test
    void cancelSendsACancelledNotificationForTheCallId() throws Exception {
        AiMcpRemoteTransport transport = transport();
        AiMcpObservedResult observed = transport.call(request("/mcp", 5000, MAPPER.createObjectNode()));
        CANCELLED_FRAMES.clear();

        transport.cancel(request("/mcp", 5000, MAPPER.createObjectNode()), observed.sessionId(),
            observed.callId(), "operator cancelled");

        assertThat(CANCELLED_FRAMES).hasSize(1);
        assertThat(CANCELLED_FRAMES.get(0)).isEqualTo(observed.callId() + "|operator cancelled");
    }
    @Test
    void authModeWithoutStoredSecretIsRejected() {
        McpRemoteRequest secured = new McpRemoteRequest("http", base() + "/mcp", "bearer", "ref-1", "",
            1000, "fixture.lookup", MAPPER.createObjectNode());
        assertThatThrownBy(() -> transport().call(secured))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("authSecretRef");
    }
}
