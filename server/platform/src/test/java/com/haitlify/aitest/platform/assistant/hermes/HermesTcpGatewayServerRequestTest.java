package com.haitlify.aitest.platform.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/**
 * 服务端请求（审批 / 澄清）处理：pin 的 {@code server_requests} 帧形，字符串 id {@code srq-<12 hex>}。
 *
 * <p>本切片只要求「识别 + 显式派发 + 默认拒绝」：处理器没有注入时审批一律 deny、澄清一律取消，
 * 未知方法仍然 -32601，回包必须是合法 JSON-RPC 且 id 类型原样保留。
 */
class HermesTcpGatewayServerRequestTest {

    private static JsonNode json(String raw) {
        return HermesWireStub.MAPPER.readTree(raw);
    }

    @Test
    void approvalIsDispatchedToTheInjectedHandlerAndEchoesItsStringId() throws Exception {
        var answered = new CompletableFuture<JsonNode>();
        List<HermesServerRequest> seen = Collections.synchronizedList(new ArrayList<>());
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var rpc = s.read(in);
            HermesWireStub.send(out, "{\"jsonrpc\":\"2.0\",\"id\":\"srq-0123456789ab\",\"method\":\"approval\","
                + "\"params\":{\"session_id\":\"live-a\",\"request_id\":\"appr-1\",\"command\":\"rm -rf /tmp/x\","
                + "\"choices\":[\"once\",\"session\",\"deny\"]}}");
            answered.complete(s.read(in));
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(rpc.path("id"), "{\"session_id\":\"live-b\"}"));
        })) {
            var gateway = stub.gateway(5, request -> {
                seen.add(request);
                return json("{\"choice\":\"session\"}");
            });
            assertThat(gateway.createSession(".").sessionId()).isEqualTo("live-b");

            JsonNode frame = answered.get(2, TimeUnit.SECONDS);
            assertThat(frame.path("jsonrpc").asString()).isEqualTo("2.0");
            assertThat(frame.path("id").isTextual()).isTrue();
            assertThat(frame.path("id").asString()).isEqualTo("srq-0123456789ab");
            assertThat(frame.has("error")).isFalse();
            assertThat(frame.path("result").path("choice").asString()).isEqualTo("session");

            assertThat(seen).hasSize(1);
            HermesServerRequest request = seen.getFirst();
            assertThat(request.kind()).isEqualTo(HermesServerRequest.Kind.APPROVAL);
            assertThat(request.method()).isEqualTo("approval");
            assertThat(request.sessionId()).isEqualTo("live-a");
            assertThat(request.requestId()).isEqualTo("appr-1");
            assertThat(request.params().path("command").asString()).isEqualTo("rm -rf /tmp/x");
            assertThat(request.id().asString()).isEqualTo("srq-0123456789ab");
            stub.awaitFinished();
        }
    }

    @Test
    void withoutAnInjectedHandlerApprovalIsDeniedAndClarifyIsCancelled() throws Exception {
        var approvalAnswer = new CompletableFuture<JsonNode>();
        var clarifyAnswer = new CompletableFuture<JsonNode>();
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var rpc = s.read(in);
            HermesWireStub.send(out, "{\"jsonrpc\":\"2.0\",\"id\":\"srq-aaaa00000001\",\"method\":\"approval\","
                + "\"params\":{\"session_id\":\"live-a\",\"request_id\":\"appr-1\",\"command\":\"curl secret\"}}");
            approvalAnswer.complete(s.read(in));
            HermesWireStub.send(out, "{\"jsonrpc\":\"2.0\",\"id\":\"srq-bbbb00000002\",\"method\":\"clarify\","
                + "\"params\":{\"session_id\":\"live-a\",\"questions\":[{\"qid\":\"q1\",\"question\":\"which?\","
                + "\"choices\":[\"a\",\"b\"],\"multi_select\":false}]}}");
            clarifyAnswer.complete(s.read(in));
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(rpc.path("id"), "{\"session_id\":\"live-b\"}"));
        })) {
            var gateway = stub.gateway(5);
            assertThat(gateway.createSession(".").sessionId()).isEqualTo("live-b");

            JsonNode approval = approvalAnswer.get(2, TimeUnit.SECONDS);
            assertThat(approval.path("id").asString()).isEqualTo("srq-aaaa00000001");
            assertThat(approval.has("error")).isFalse();
            assertThat(approval.path("result").path("choice").asString()).isEqualTo("deny");

            JsonNode clarify = clarifyAnswer.get(2, TimeUnit.SECONDS);
            assertThat(clarify.path("id").asString()).isEqualTo("srq-bbbb00000002");
            assertThat(clarify.has("error")).isFalse();
            // pin 的 resolve_response：缺少 answers 键即 cancel-all；这里绝不编造答案。
            assertThat(clarify.path("result").isObject()).isTrue();
            assertThat(clarify.path("result").has("answers")).isFalse();
            stub.awaitFinished();
        }
    }

    @Test
    void clarifyIsDispatchedWithItsQuestions() throws Exception {
        var answered = new CompletableFuture<JsonNode>();
        List<HermesServerRequest> seen = Collections.synchronizedList(new ArrayList<>());
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var rpc = s.read(in);
            HermesWireStub.send(out, "{\"jsonrpc\":\"2.0\",\"id\":\"srq-cccc00000003\",\"method\":\"clarify\","
                + "\"params\":{\"session_id\":\"live-a\",\"questions\":["
                + "{\"qid\":\"q1\",\"question\":\"env?\",\"choices\":[\"staging\",\"prod\"],\"multi_select\":false},"
                + "{\"qid\":\"q2\",\"question\":\"notes?\",\"choices\":null,\"multi_select\":true}]}}");
            answered.complete(s.read(in));
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(rpc.path("id"), "{\"session_id\":\"live-b\"}"));
        })) {
            var gateway = stub.gateway(5, request -> {
                seen.add(request);
                return json("{\"answers\":{\"q1\":\"staging\"}}");
            });
            assertThat(gateway.createSession(".").sessionId()).isEqualTo("live-b");

            JsonNode frame = answered.get(2, TimeUnit.SECONDS);
            assertThat(frame.path("id").asString()).isEqualTo("srq-cccc00000003");
            assertThat(frame.path("result").path("answers").path("q1").asString()).isEqualTo("staging");

            HermesServerRequest request = seen.getFirst();
            assertThat(request.kind()).isEqualTo(HermesServerRequest.Kind.CLARIFY);
            assertThat(request.sessionId()).isEqualTo("live-a");
            assertThat(request.params().path("questions")).hasSize(2);
            assertThat(request.requestId()).isEmpty();
            stub.awaitFinished();
        }
    }

    @Test
    void unknownServerRequestMethodIsStillMethodNotFound() throws Exception {
        var first = new CompletableFuture<JsonNode>();
        var second = new CompletableFuture<JsonNode>();
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var rpc = s.read(in);
            // 真实服务端 id 是字符串；顺带压一个恶意数字 id，与待决 RPC id 相同。
            HermesWireStub.send(out, "{\"jsonrpc\":\"2.0\",\"id\":\"srq-dddd00000004\",\"method\":\"preview.act\","
                + "\"params\":{\"session_id\":\"live-a\",\"action\":\"click\"}}");
            first.complete(s.read(in));
            HermesWireStub.send(out, "{\"jsonrpc\":\"2.0\",\"id\":" + rpc.path("id").asLong()
                + ",\"method\":\"preview.act\",\"params\":{\"session_id\":\"live-a\",\"action\":\"click\"}}");
            second.complete(s.read(in));
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(rpc.path("id"), "{\"status\":\"streaming\"}"));
        })) {
            var gateway = stub.gateway(5, request -> {
                throw new AssertionError("未知方法不得派发给处理器：" + request.method());
            });
            assertThat(json(gateway.submitPrompt("live-a", "hello").rawJson())
                .path("result").path("status").asString()).isEqualTo("streaming");

            for (var future : List.of(first, second)) {
                JsonNode rejection = future.get(2, TimeUnit.SECONDS);
                assertThat(rejection.path("error").path("code").asInt()).isEqualTo(-32601);
                assertThat(rejection.has("result")).isFalse();
            }
            assertThat(first.get().path("id").asString()).isEqualTo("srq-dddd00000004");
            assertThat(second.get().path("id").isIntegralNumber()).isTrue();
            stub.awaitFinished();
        }
    }

    @Test
    void numericServerRequestIdCannotStealAPendingCall() throws Exception {
        var answered = new CompletableFuture<JsonNode>();
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var rpc = s.read(in);
            HermesWireStub.send(out, "{\"jsonrpc\":\"2.0\",\"id\":" + rpc.path("id").asLong()
                + ",\"method\":\"approval\",\"params\":{\"session_id\":\"live-a\",\"request_id\":\"appr-9\"}}");
            answered.complete(s.read(in));
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(rpc.path("id"), "{\"status\":\"streaming\"}"));
        })) {
            var gateway = stub.gateway(5);
            assertThat(json(gateway.submitPrompt("live-a", "hello").rawJson())
                .path("result").path("status").asString()).isEqualTo("streaming");

            JsonNode frame = answered.get(2, TimeUnit.SECONDS);
            assertThat(frame.path("id").isIntegralNumber()).isTrue();
            // 默认处理器：这是服务端请求的回包，而不是把待决 RPC 唤醒。
            assertThat(frame.path("result").path("choice").asString()).isEqualTo("deny");
            stub.awaitFinished();
        }
    }

    @Test
    void handlerFailureBecomesAnErrorFrameAndNeverAnApproval() throws Exception {
        var answered = new CompletableFuture<JsonNode>();
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var rpc = s.read(in);
            HermesWireStub.send(out, "{\"jsonrpc\":\"2.0\",\"id\":\"srq-eeee00000005\",\"method\":\"approval\","
                + "\"params\":{\"session_id\":\"live-a\",\"request_id\":\"appr-1\"}}");
            answered.complete(s.read(in));
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(rpc.path("id"), "{\"session_id\":\"live-b\"}"));
        })) {
            var gateway = stub.gateway(5, request -> {
                throw new IllegalStateException("handler exploded");
            });
            assertThat(gateway.createSession(".").sessionId()).isEqualTo("live-b");

            JsonNode frame = answered.get(2, TimeUnit.SECONDS);
            assertThat(frame.path("id").asString()).isEqualTo("srq-eeee00000005");
            assertThat(frame.has("result")).isFalse();
            assertThat(frame.path("error").path("code").asInt()).isEqualTo(-32603);
            stub.awaitFinished();
        }
    }

    @Test
    void serverRequestWithoutSessionIdIsInvalidParamsAndNeverDispatched() throws Exception {
        var answered = new CompletableFuture<JsonNode>();
        var dispatched = new AtomicInteger();
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var rpc = s.read(in);
            HermesWireStub.send(out, "{\"jsonrpc\":\"2.0\",\"id\":\"srq-ffff00000006\",\"method\":\"approval\","
                + "\"params\":{\"request_id\":\"appr-1\"}}");
            answered.complete(s.read(in));
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(rpc.path("id"), "{\"session_id\":\"live-b\"}"));
        })) {
            var gateway = stub.gateway(5, request -> {
                dispatched.incrementAndGet();
                return json("{\"choice\":\"once\"}");
            });
            assertThat(gateway.createSession(".").sessionId()).isEqualTo("live-b");

            JsonNode frame = answered.get(2, TimeUnit.SECONDS);
            assertThat(frame.path("id").asString()).isEqualTo("srq-ffff00000006");
            assertThat(frame.path("error").path("code").asInt()).isEqualTo(-32602);
            assertThat(frame.has("result")).isFalse();
            assertThat(dispatched).hasValue(0);
            stub.awaitFinished();
        }
    }

    @Test
    void invalidIdTypeGetsInvalidRequestWithNullId() throws Exception {
        var answered = new CompletableFuture<JsonNode>();
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var rpc = s.read(in);
            HermesWireStub.send(out, "{\"jsonrpc\":\"2.0\",\"id\":[1],\"method\":\"approval\","
                + "\"params\":{\"session_id\":\"live-a\",\"request_id\":\"appr-1\"}}");
            answered.complete(s.read(in));
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(rpc.path("id"), "{\"session_id\":\"live-b\"}"));
        })) {
            var gateway = stub.gateway(5);
            assertThat(gateway.createSession(".").sessionId()).isEqualTo("live-b");

            JsonNode frame = answered.get(2, TimeUnit.SECONDS);
            assertThat(frame.path("id").isNull()).isTrue();
            assertThat(frame.path("error").path("code").asInt()).isEqualTo(-32600);
            assertThat(frame.has("result")).isFalse();
            stub.awaitFinished();
        }
    }

    @Test
    void notificationShapedServerRequestIsNeverAnswered() throws Exception {
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var rpc = s.read(in);
            HermesWireStub.send(out, "{\"jsonrpc\":\"2.0\",\"method\":\"approval\","
                + "\"params\":{\"session_id\":\"live-a\",\"request_id\":\"appr-1\"}}");
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(rpc.path("id"), "{\"session_id\":\"live-b\"}"));
        })) {
            var gateway = stub.gateway(5, request -> {
                throw new AssertionError("无 id 的帧不是请求，不能派发：" + request.method());
            });
            assertThat(gateway.createSession(".").sessionId()).isEqualTo("live-b");
            stub.awaitFinished();
            // 网关只写过两帧：连接时的 capability 宣告 + 自己的 session.create，没有为通知回包。
            assertThat(stub.inbound).hasSize(2);
            assertThat(stub.inbound.get(0).path("method").asString())
                .isEqualTo(HermesWireStub.CAPABILITIES_METHOD);
            assertThat(stub.inbound.get(1).path("method").asString()).isEqualTo("session.create");
            assertThat(stub.inbound).noneMatch(frame -> frame.has("result") || frame.has("error"));
        }
    }
}
