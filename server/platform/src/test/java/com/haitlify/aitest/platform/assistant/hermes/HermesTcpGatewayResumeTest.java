package com.haitlify.aitest.platform.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 恢复与历史读取：面向 pin（tui_gateway d923a5eb48）真实回包形状的 loopback 契约测试。
 *
 * <p>pin 的 {@code session.resume} 参数是 stored id，回包同时给出新的运行时 {@code session_id}
 * 与被恢复的持久标识（冷/温路径字段名不同：{@code resumed}/{@code session_key} 或 {@code stored_session_id}）；
 * 未知 id 回错误 4007。{@code session.history} 参数是运行时 id，回 {@code {count, messages}}，
 * 未知运行时 id 回 4001。这里不允许任何一种情况被解析成「空成功」。
 */
class HermesTcpGatewayResumeTest {

    @Test
    void resumeSessionReturnsParsedStateAndKeepsRawPayload() throws Exception {
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var request = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(request.path("id"),
                "{\"session_id\":\"live-r1\",\"resumed\":\"stored-9\",\"message_count\":3,"
                    + "\"messages\":[{\"role\":\"user\",\"text\":\"hi\"}],\"running\":false,"
                    + "\"status\":\"idle\",\"session_key\":\"stored-9\",\"info\":{\"model\":\"m\"}}"));
        })) {
            var gateway = stub.gateway(5);
            HermesGateway.Resume resume = gateway.resumeSession("stored-9");

            assertThat(resume.requestedStoredSessionId()).isEqualTo("stored-9");
            assertThat(resume.liveSessionId()).isEqualTo("live-r1");
            assertThat(resume.storedSessionId()).isEqualTo("stored-9");
            assertThat(resume.status()).isEqualTo("idle");
            assertThat(resume.running()).isFalse();
            assertThat(resume.messageCount()).isEqualTo(3);
            assertThat(resume.rawJson()).contains("\"messages\"").contains("live-r1");

            var sent = stub.awaitInbound(node -> "session.resume".equals(node.path("method").asString()));
            assertThat(sent.path("params").path("session_id").asString()).isEqualTo("stored-9");
            stub.awaitFinished();
        }
    }

    @Test
    void warmLiveReattachIdentifiesItselfWithStoredSessionId() throws Exception {
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var request = s.read(in);
            // 温路径（_live_session_payload）用 stored_session_id，且 running=true / status=working。
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(request.path("id"),
                "{\"session_id\":\"live-w1\",\"stored_session_id\":\"stored-9\",\"message_count\":7,"
                    + "\"messages\":[],\"running\":true,\"status\":\"working\"}"));
        })) {
            var gateway = stub.gateway(5);
            HermesGateway.Resume resume = gateway.resumeSession("stored-9");

            assertThat(resume.liveSessionId()).isEqualTo("live-w1");
            assertThat(resume.storedSessionId()).isEqualTo("stored-9");
            assertThat(resume.running()).isTrue();
            assertThat(resume.status()).isEqualTo("working");
            stub.awaitFinished();
        }
    }

    @Test
    void pinnedUnknownStoredIdIsAnExplicitFailure() throws Exception {
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var request = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcError(request.path("id"), 4007, "session not found"));
        })) {
            var gateway = stub.gateway(5);
            assertThatThrownBy(() -> gateway.resumeSession("stored-missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("4007");
            stub.awaitFinished();
        }
    }

    /**
     * pin 的 resume 状态词表包含 {@code resuming}（defer_history/lazy 路径，
     * tui_gateway/methods_session.py:963）。适配器必须按契约接受它并原样透出，
     * 不能因为词表缺项把一次合法恢复判成畸形回执。
     */
    @ParameterizedTest
    @ValueSource(strings = {"idle", "working", "waiting", "starting", "streaming", "resuming"})
    void everyPinResumeStatusIsAcceptedAndSurfacedVerbatim(String status) throws Exception {
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var request = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(request.path("id"),
                "{\"session_id\":\"live-s1\",\"resumed\":\"stored-9\",\"message_count\":1,"
                    + "\"messages\":[],\"running\":false,\"status\":\"" + status + "\"}"));
        })) {
            var gateway = stub.gateway(5);
            assertThat(gateway.resumeSession("stored-9").status()).isEqualTo(status);
            stub.awaitFinished();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}",
        "null",
        "[]",
        // 缺运行时 id
        "{\"resumed\":\"stored-9\",\"status\":\"idle\",\"running\":false,\"message_count\":1}",
        // 缺被恢复的持久标识：无法判定命中的是哪条会话
        "{\"session_id\":\"live-r1\",\"status\":\"idle\",\"running\":false,\"message_count\":1}",
        // 未知状态（pin 只产出 idle/working/waiting/starting）
        "{\"session_id\":\"live-r1\",\"resumed\":\"stored-9\",\"status\":\"thinking\",\"running\":false,\"message_count\":1}",
        // running 缺失/类型不对
        "{\"session_id\":\"live-r1\",\"resumed\":\"stored-9\",\"status\":\"idle\",\"message_count\":1}",
        "{\"session_id\":\"live-r1\",\"resumed\":\"stored-9\",\"status\":\"idle\",\"running\":\"false\",\"message_count\":1}",
        // message_count 缺失/类型不对/负数
        "{\"session_id\":\"live-r1\",\"resumed\":\"stored-9\",\"status\":\"idle\",\"running\":false}",
        "{\"session_id\":\"live-r1\",\"resumed\":\"stored-9\",\"status\":\"idle\",\"running\":false,\"message_count\":\"1\"}",
        "{\"session_id\":\"live-r1\",\"resumed\":\"stored-9\",\"status\":\"idle\",\"running\":false,\"message_count\":-1}",
        // 运行时 id 类型不对
        "{\"session_id\":7,\"resumed\":\"stored-9\",\"status\":\"idle\",\"running\":false,\"message_count\":1}"
    })
    void malformedResumeReceiptIsNeverAnEmptySuccess(String resultJson) throws Exception {
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var request = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(request.path("id"), resultJson));
        })) {
            var gateway = stub.gateway(5);
            assertThatThrownBy(() -> gateway.resumeSession("stored-9")).isInstanceOf(IllegalStateException.class);
            stub.awaitFinished();
        }
    }

    @Test
    void resumeRefusesToSilentlySwitchToAnotherSession() throws Exception {
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var request = s.read(in);
            // 形似成功，但命中的是另一条会话（pin 的标题/压缩 tip 回退能造成这种回包）。
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(request.path("id"),
                "{\"session_id\":\"live-x\",\"resumed\":\"stored-other\",\"message_count\":0,"
                    + "\"messages\":[],\"running\":false,\"status\":\"idle\"}"));
        })) {
            var gateway = stub.gateway(5);
            assertThatThrownBy(() -> gateway.resumeSession("stored-9"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stored-other");
            stub.awaitFinished();
        }
    }

    @Test
    void blankStoredIdFailsBeforeAnyWireCall() throws Exception {
        // 端口上没有对端：若实现先拨号，会得到 IllegalStateException（不可达），而不是 IllegalArgumentException。
        int freePort;
        try (ServerSocket probe = new ServerSocket(0)) {
            freePort = probe.getLocalPort();
        }
        var gateway = new HermesTcpGateway("127.0.0.1", freePort, 1, HermesWireStub.MAPPER);
        assertThatThrownBy(() -> gateway.resumeSession(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gateway.resumeSession(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gateway.history("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void historyReadsMessagesForALiveSession() throws Exception {
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var request = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(request.path("id"),
                "{\"count\":2,\"messages\":[{\"role\":\"user\",\"text\":\"a\"},"
                    + "{\"role\":\"assistant\",\"text\":\"b\",\"row_id\":\"r-2\"}]}"));
        })) {
            var gateway = stub.gateway(5);
            HermesGateway.History history = gateway.history("live-r1");

            assertThat(history.sessionId()).isEqualTo("live-r1");
            assertThat(history.count()).isEqualTo(2);
            assertThat(history.messages()).hasSize(2);
            assertThat(history.messages().get(1).path("row_id").asString()).isEqualTo("r-2");
            assertThat(history.rawJson()).contains("\"count\":2");

            var sent = stub.awaitInbound(node -> "session.history".equals(node.path("method").asString()));
            assertThat(sent.path("params").path("session_id").asString()).isEqualTo("live-r1");
            stub.awaitFinished();
        }
    }

    @Test
    void historyOfUnknownLiveSessionFailsInsteadOfReturningEmptyHistory() throws Exception {
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var request = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcError(request.path("id"), 4001, "session not found"));
        })) {
            var gateway = stub.gateway(5);
            assertThatThrownBy(() -> gateway.history("live-gone"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("4001");
            stub.awaitFinished();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "{\"count\":1}", "{\"messages\":[]}",
        "{\"count\":\"1\",\"messages\":[]}", "{\"count\":-1,\"messages\":[]}",
        "{\"count\":1,\"messages\":{}}"})
    void malformedHistoryReceiptIsNeverAnEmptyHistory(String resultJson) throws Exception {
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var request = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(request.path("id"), resultJson));
        })) {
            var gateway = stub.gateway(5);
            assertThatThrownBy(() -> gateway.history("live-r1")).isInstanceOf(IllegalStateException.class);
            stub.awaitFinished();
        }
    }
}
