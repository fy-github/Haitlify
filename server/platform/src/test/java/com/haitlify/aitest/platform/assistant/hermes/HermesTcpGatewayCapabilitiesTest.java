package com.haitlify.aitest.platform.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.JsonNode;

/**
 * 缺陷 A：pin 的 {@code client.capabilities}（{@code tui_gateway/methods_voice.py:442-450}）是
 * 「本连接会应答服务端请求」的唯一宣告；{@code tui_gateway/server_requests.py} 只对宣告过的
 * transport 下发 approval/clarify。因此：
 *
 * <ul>
 *   <li>连接建立后必须先宣告，且严格校验回包（至少 approval+clarify，{@code declines_not_shown}
 *       若出现必须是 true）；</li>
 *   <li>旧网关回 -32601 或回包不满足时，交互能力必须记为「不可用」，服务端请求按 fail-closed
 *       处理（审批一律 deny），绝不静默当作已宣告。</li>
 * </ul>
 */
class HermesTcpGatewayCapabilitiesTest {

    private static JsonNode json(String raw) {
        return HermesWireStub.MAPPER.readTree(raw);
    }

    @Test
    void connectionAdvertisesServerRequestsBeforeAnyBusinessCall() throws Exception {
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var rpc = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(rpc.path("id"), "{\"session_id\":\"live-b\"}"));
        })) {
            var gateway = stub.gateway(5);
            assertThat(gateway.createSession(".").sessionId()).isEqualTo("live-b");

            JsonNode advertised = stub.awaitInbound(frame ->
                HermesWireStub.CAPABILITIES_METHOD.equals(frame.path("method").asString("")));
            assertThat(advertised.path("jsonrpc").asString()).isEqualTo("2.0");
            assertThat(advertised.path("id").isIntegralNumber()).isTrue();
            assertThat(advertised.path("params").path("server_requests").isBoolean()).isTrue();
            assertThat(advertised.path("params").path("server_requests").booleanValue()).isTrue();

            // 宣告必须先于第一个业务调用（同一连接上按到达顺序）。
            assertThat(stub.inbound.getFirst().path("method").asString())
                .isEqualTo(HermesWireStub.CAPABILITIES_METHOD);
            assertThat(stub.inbound.get(1).path("method").asString()).isEqualTo("session.create");
            stub.awaitFinished();
        }
    }

    @Test
    void capabilitiesAreAdvertisedExactlyOncePerTransport() throws Exception {
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            for (String sessionId : new String[] {"live-1", "live-2"}) {
                var rpc = s.read(in);
                HermesWireStub.send(out,
                    HermesWireStub.jsonRpcResult(rpc.path("id"), "{\"session_id\":\"" + sessionId + "\"}"));
            }
        })) {
            var gateway = stub.gateway(5);
            gateway.createSession(".");
            gateway.createSession(".");
            stub.awaitFinished();

            assertThat(stub.inbound.stream()
                .filter(frame -> HermesWireStub.CAPABILITIES_METHOD.equals(frame.path("method").asString("")))
                .count()).isEqualTo(1);
        }
    }

    @Test
    void legacyGatewayWithoutTheMethodIsNotTreatedAsAdvertising() throws Exception {
        assertServerRequestsFailClosed(HermesWireStub.Capability.error(-32601));
    }

    @Test
    void advertisementMissingApprovalIsNotProofOfSupport() throws Exception {
        assertServerRequestsFailClosed(HermesWireStub.Capability.result(
            "{\"server_requests\":[\"clarify\",\"setup_beat\"],\"declines_not_shown\":true}"));
    }

    @Test
    void advertisementMissingClarifyIsNotProofOfSupport() throws Exception {
        assertServerRequestsFailClosed(HermesWireStub.Capability.result(
            "{\"server_requests\":[\"approval\",\"setup_beat\"],\"declines_not_shown\":true}"));
    }

    @Test
    void declinesNotShownFalseIsNotAnAdvertisement() throws Exception {
        assertServerRequestsFailClosed(HermesWireStub.Capability.result(
            "{\"server_requests\":[\"approval\",\"clarify\"],\"declines_not_shown\":false}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}",
        "null",
        "[]",
        "{\"server_requests\":\"approval,clarify\"}",
        "{\"server_requests\":[1,2]}",
        "{\"server_requests\":[\"approval\",\"\"]}",
        "{\"server_requests\":[],\"declines_not_shown\":true}",
        "{\"server_requests\":[\"approval\",\"clarify\"],\"declines_not_shown\":\"true\"}",
        "{\"server_requests\":[\"approval\",\"clarify\"],\"declines_not_shown\":null}",
        "{\"server_requests\":[\"Approval\",\"Clarify\"],\"declines_not_shown\":true}"
    })
    void malformedAdvertisementIsNeverAccepted(String resultJson) throws Exception {
        assertServerRequestsFailClosed(HermesWireStub.Capability.result(resultJson));
    }

    /** 宣告成功后，网关给出明确的「可用」状态而不是默认值。 */
    @Test
    void confirmedAdvertisementIsReportedAsAvailable() throws Exception {
        assertInteractionState(HermesWireStub.Capability.advertised(), true, "approval", "clarify");
    }

    @Test
    void legacyGatewayReportsInteractionUnavailableWithAReason() throws Exception {
        assertInteractionState(HermesWireStub.Capability.error(-32601), false, "-32601");
    }

    @Test
    void partialAdvertisementReportsInteractionUnavailable() throws Exception {
        assertInteractionState(
            HermesWireStub.Capability.result("{\"server_requests\":[\"clarify\"],\"declines_not_shown\":true}"),
            false, "approval");
    }

    /**
     * 连接保持打开（脚本等测试放行）时才断言状态：传输一关，宣告本来就该失效，
     * 那会把「宣告失败」与「连接没了」混在一起。
     */
    private void assertInteractionState(HermesWireStub.Capability capability, boolean expectedAvailable,
                                        String... detailFragments) throws Exception {
        var release = new CompletableFuture<Void>();
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var rpc = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(rpc.path("id"), "{\"session_id\":\"live-b\"}"));
            release.get(2, TimeUnit.SECONDS);
        }, capability)) {
            var gateway = stub.gateway(5);
            try {
                assertThat(gateway.createSession(".").sessionId()).isEqualTo("live-b");
                // 传输仍然可用；交互能力要么被确认，要么带着原因被记为不可用。
                assertThat(gateway.available()).isTrue();
                assertThat(gateway.interactionAvailable()).isEqualTo(expectedAvailable);
                for (String fragment : detailFragments) {
                    assertThat(gateway.interactionDetail()).contains(fragment);
                }
            } finally {
                release.complete(null);
            }
            stub.awaitFinished();
        }
    }

     /**
     * 同一个真实 loopback 连接：宣告失败后 gateway 仍然可做普通 RPC，但到达的 approval 必须
     * 走 fail-closed（deny），不能派发给注入的处理器。
     */
    private void assertServerRequestsFailClosed(HermesWireStub.Capability capability) throws Exception {
        var answered = new CompletableFuture<JsonNode>();
        var dispatched = new AtomicInteger();
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var rpc = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(rpc.path("id"), "{\"session_id\":\"live-b\"}"));
            HermesWireStub.send(out, "{\"jsonrpc\":\"2.0\",\"id\":\"srq-0123456789ab\",\"method\":\"approval\","
                + "\"params\":{\"session_id\":\"live-b\",\"request_id\":\"appr-1\",\"command\":\"curl secret\","
                + "\"choices\":[\"once\",\"deny\"]}}");
            answered.complete(s.read(in));
        }, capability)) {
            var gateway = stub.gateway(5, request -> {
                dispatched.incrementAndGet();
                return json("{\"choice\":\"once\"}");
            });
            assertThat(gateway.createSession(".").sessionId()).isEqualTo("live-b");

            JsonNode frame = answered.get(2, TimeUnit.SECONDS);
            assertThat(frame.path("id").asString()).isEqualTo("srq-0123456789ab");
            assertThat(frame.path("result").path("choice").asString()).isEqualTo("deny");
            assertThat(dispatched).hasValue(0);
            stub.awaitFinished();
        }
    }
}
