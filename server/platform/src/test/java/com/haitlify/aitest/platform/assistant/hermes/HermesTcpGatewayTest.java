package com.haitlify.aitest.platform.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Hermes 独立容器场景：平台与 Hermes 之间只有一条 TCP JSON-RPC 连接。
 * 这里用真实 socket 桩验证协议与 fail-closed 语义（不 mock 传输层）。
 */
class HermesTcpGatewayTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ServerSocket stub;
    private Thread stubThread;

    @AfterEach
    void tearDown() throws Exception {
        if (stub != null) {
            stub.close();
        }
        if (stubThread != null) {
            stubThread.join(2000);
        }
    }

    private int startStub(String responseForPrompt) throws Exception {
        return startStub(responseForPrompt, "{\"status\":\"interrupted\"}");
    }

    private int startStub(String responseForPrompt, String interruptResult) throws Exception {
        stub = new ServerSocket(0);
        stubThread = new Thread(() -> {
            try (Socket socket = stub.accept();
                 BufferedReader reader = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter writer = new BufferedWriter(
                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    JsonNode request = mapper.readTree(line);
                    String method = request.path("method").asString("");
                    String result;
                    switch (method) {
                        case "session.create" -> result = "{\"session_id\":\"s-1\"}";
                        case "prompt.submit" -> result = responseForPrompt;
                        case "session.interrupt" -> result = interruptResult;
                        default -> result = "{}";
                    }
                    writer.write("{\"jsonrpc\":\"2.0\",\"id\":" + request.path("id").asLong()
                        + ",\"result\":" + result + "}\n");
                    writer.flush();
                }
            } catch (Exception ignored) {
                // 测试结束关闭
            }
        });
        stubThread.setDaemon(true);
        stubThread.start();
        return stub.getLocalPort();
    }

    @Test
    void speaksJsonRpcOverTcpAndKeepsRawPayloads() throws Exception {
        int port = startStub("{\"turn_id\":\"t-9\",\"status\":\"accepted\"}");
        HermesTcpGateway gateway = new HermesTcpGateway("127.0.0.1", port, 5, mapper);

        assertThat(gateway.available()).isTrue();
        HermesGateway.Session session = gateway.createSession("/state/workspace");
        assertThat(session.sessionId()).isEqualTo("s-1");
        assertThat(session.rawJson()).contains("s-1");

        HermesGateway.Turn turn = gateway.submitPrompt("s-1", "只读探索");
        assertThat(turn.turnRef()).isEqualTo("t-9");
        assertThat(turn.rawJson()).contains("accepted");

        assertThat(gateway.interrupt("s-1")).isTrue();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "{\"error\":\"session_not_found\"}",
        "{\"status\":\"interrupted\",\"error\":\"failed\"}",
        "{\"status\":\"interrupted\",\"ok\":false}",
        "{\"status\":\"interrupted\",\"ok\":\"true\"}",
        "{\"ok\":true}", "{}", "null", "[]", "{\"status\":true}"
    })
    void malformedOrContradictoryInterruptReceiptsAreNeverSuccess(String result) throws Exception {
        int port = startStub("{}", result);
        var gateway = new HermesTcpGateway("127.0.0.1", port, 1, mapper);
        assertThat(gateway.interrupt("s-1")).isFalse();
    }

    @Test
    void topLevelStatusCannotImpersonateMissingResult() throws Exception {
        stub = new ServerSocket(0);
        stubThread = new Thread(() -> {
            try (Socket socket = stub.accept();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(
                     socket.getInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                     socket.getOutputStream(), StandardCharsets.UTF_8))) {
                JsonNode request = mapper.readTree(reader.readLine());
                writer.write("{\"jsonrpc\":\"2.0\",\"id\":" + request.path("id").asLong()
                    + ",\"error\":null,\"status\":\"interrupted\"}\n");
                writer.flush();
            } catch (Exception ex) {
                throw new AssertionError(ex);
            }
        });
        stubThread.setDaemon(true);
        stubThread.start();
        var gateway = new HermesTcpGateway("127.0.0.1", stub.getLocalPort(), 1, mapper);
        assertThat(gateway.interrupt("s-1")).isFalse();
    }

    @Test
    void streamingReceiptDoesNotInventATurnReference() throws Exception {
        int port = startStub("{\"status\":\"streaming\"}");
        HermesTcpGateway gateway = new HermesTcpGateway("127.0.0.1", port, 5, mapper);
        assertThat(gateway.submitPrompt("s-1", "任务").turnRef()).isNull();
    }

    @Test
    void unreachableHermesIsNotAvailableAndCallsFail() throws Exception {
        int freePort;
        try (ServerSocket probe = new ServerSocket(0)) {
            freePort = probe.getLocalPort();
        }
        HermesTcpGateway gateway = new HermesTcpGateway("127.0.0.1", freePort, 1, mapper);
        assertThat(gateway.available()).isFalse();
        assertThatThrownBy(() -> gateway.createSession("/tmp")).isInstanceOf(IllegalStateException.class);
    }

    /**
     * 结构化错误：只暴露 JSON-RPC <b>顶层</b>整数 {@code error.code}。判定会话回收只能靠它，
     * 所以这里逐个钉死「哪些形状算顶层整数码、哪些必须记 null（不可判定）」。
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
        // 顶层整数码：可判定
        "{\"code\":4001,\"message\":\"session not found\"}|4001",
        "{\"data\":{\"code\":4001},\"code\":5001,\"message\":\"nested first\"}|5001",
        "{\"code\":5001,\"data\":{\"code\":4001}}|5001",
        // 只有嵌套/字符串/浮点/缺失/超范围：不可判定（null），绝不退化成某个具体码
        "{\"data\":{\"code\":4001},\"message\":\"only nested\"}|",
        "{\"code\":\"4001\"}|",
        "{\"code\":4001.0}|",
        "{\"code\":true}|",
        "{\"message\":\"session 4001 not found\"}|",
        "{\"code\":99999999999}|",
        "[]|"
    })
    void onlyATopLevelIntegerErrorCodeIsStructured(String errorJson, String expectedCode) throws Exception {
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var request = s.read(in);
            HermesWireStub.send(out, "{\"jsonrpc\":\"2.0\",\"id\":" + request.path("id")
                + ",\"error\":" + errorJson + "}");
        })) {
            var gateway = stub.gateway(5);
            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> gateway.submitPrompt("live-1", "hi"));

            assertThat(thrown).isInstanceOf(HermesGateway.UpstreamRpcError.class);
            var error = (HermesGateway.UpstreamRpcError) thrown;
            assertThat(error.method()).isEqualTo("prompt.submit");
            if (expectedCode == null || expectedCode.isBlank()) {
                assertThat(error.topLevelCode()).as("不可判定的错误形状必须为 null：%s", errorJson).isNull();
            } else {
                assertThat(error.topLevelCode()).isEqualTo(Integer.valueOf(expectedCode));
                assertThat(thrown.getMessage()).contains(expectedCode);
            }
            // 仍是 IllegalStateException 家族：既有调用方按 ILS 处理的路径不会变。
            assertThat(thrown).isInstanceOf(IllegalStateException.class);
            stub.awaitFinished();
        }
    }
}
