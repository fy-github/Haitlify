package com.haitlify.aitest.platform.assistant.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.haitlify.aitest.platform.assistant.hermes.HermesTcpGateway;
import com.haitlify.aitest.platform.assistant.hermes.HermesWireStub;
import tools.jackson.databind.JsonNode;

/**
 * 会话级复用与恢复：全部用真实 loopback 端点驱动真实的 {@link HermesTcpGateway}，零模型调用。
 *
 * <p>断言落在 wire 上（收到几帧 {@code session.create}、{@code session.resume} 的参数、
 * {@code prompt.submit} 的目标会话），而不是被测类的内部字段，因此实现细节可改，契约不可改。
 */
class HermesConversationServiceTest {

    private static final Duration WAIT = Duration.ofSeconds(2);

    @Test
    void oneConversationReusesTheSameLiveSessionAcrossTurns() throws Exception {
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var create = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(create.path("id"),
                "{\"session_id\":\"live-1\",\"stored_session_id\":\"stored-1\"}"));
            var first = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(first.path("id"), "{\"status\":\"streaming\"}"));
            HermesWireStub.send(out, complete("live-1", "第一轮"));
            var second = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(second.path("id"), "{\"status\":\"streaming\"}"));
            HermesWireStub.send(out, complete("live-1", "第二轮"));
            s.read(in); // 保持传输直到测试结束，避免终态事件与断连竞态
        })) {
            var service = new HermesConversationService(stub.gateway(5), HermesWireStub.MAPPER);

            var first = service.submit("conv-1", "/state/workspace", "第一问");
            assertThat(first.sessionId()).isEqualTo("live-1");
            assertThat(first.storedSessionId()).isEqualTo("stored-1");
            assertThat(first.state()).isEqualTo(HermesAssistantRuntime.State.SUBMITTED);
            assertThat(first.acceptance()).isEqualTo(HermesAssistantRuntime.Acceptance.ACCEPTED_NOW);
            assertThat(service.awaitFinalText("conv-1", WAIT)).isEqualTo("第一轮");

            var second = service.submit("conv-1", "/state/workspace", "第二问");
            assertThat(second.sessionId()).isEqualTo("live-1");
            assertThat(second.storedSessionId()).isEqualTo("stored-1");
            assertThat(service.awaitFinalText("conv-1", WAIT)).isEqualTo("第二轮");

            assertThat(methods(stub)).filteredOn("session.create"::equals).hasSize(1);
            var submits = frames(stub, "prompt.submit");
            assertThat(submits).hasSize(2);
            assertThat(submits).allSatisfy(frame ->
                assertThat(frame.path("params").path("session_id").asString()).isEqualTo("live-1"));
            assertThat(frames(stub, "session.resume")).isEmpty();
        }
    }

    @Test
    void deadLiveSessionIsResumedFromTheStoredIdInsteadOfCreatingANewOne() throws Exception {
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var create = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(create.path("id"),
                "{\"session_id\":\"live-1\",\"stored_session_id\":\"stored-1\"}"));
            var first = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(first.path("id"), "{\"status\":\"streaming\"}"));
            HermesWireStub.send(out, complete("live-1", "第一轮"));
            // 第二轮打到已回收的 live id：pin 回 4001 session not found。
            var dead = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcError(dead.path("id"), 4001, "session not found"));
            var resume = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(resume.path("id"),
                "{\"session_id\":\"live-2\",\"resumed\":\"stored-1\",\"message_count\":2,"
                    + "\"messages\":[],\"running\":false,\"status\":\"idle\"}"));
            var retry = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(retry.path("id"), "{\"status\":\"streaming\"}"));
            HermesWireStub.send(out, complete("live-2", "第二轮"));
            s.read(in);
        })) {
            var service = new HermesConversationService(stub.gateway(5), HermesWireStub.MAPPER);

            service.submit("conv-1", "/state/workspace", "第一问");
            assertThat(service.awaitFinalText("conv-1", WAIT)).isEqualTo("第一轮");

            var resumed = service.submit("conv-1", "/state/workspace", "第二问");
            assertThat(resumed.sessionId()).isEqualTo("live-2");
            assertThat(resumed.storedSessionId()).isEqualTo("stored-1");
            assertThat(service.awaitFinalText("conv-1", WAIT)).isEqualTo("第二轮");

            var resumes = frames(stub, "session.resume");
            assertThat(resumes).hasSize(1);
            assertThat(resumes.getFirst().path("params").path("session_id").asString()).isEqualTo("stored-1");
            assertThat(frames(stub, "session.create")).hasSize(1);
            assertThat(frames(stub, "prompt.submit")).hasSize(3);
            assertThat(frames(stub, "prompt.submit").getLast().path("params").path("session_id").asString())
                .isEqualTo("live-2");
        }
    }

    @Test
    void failedResumeFailsExplicitlyAndNeverFakesRecoveryWithANewSession() throws Exception {
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var create = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(create.path("id"),
                "{\"session_id\":\"live-1\",\"stored_session_id\":\"stored-1\"}"));
            var first = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(first.path("id"), "{\"status\":\"streaming\"}"));
            HermesWireStub.send(out, complete("live-1", "第一轮"));
            var dead = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcError(dead.path("id"), 4001, "session not found"));
            var resume = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcError(resume.path("id"), 4007, "session not found"));
            s.read(in);
        })) {
            var service = new HermesConversationService(stub.gateway(5), HermesWireStub.MAPPER);

            service.submit("conv-1", "/state/workspace", "第一问");
            assertThat(service.awaitFinalText("conv-1", WAIT)).isEqualTo("第一轮");

            assertThatThrownBy(() -> service.submit("conv-1", "/state/workspace", "第二问"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("4007")
                .hasMessageContaining("未自动新建会话");

            // 关键：恢复失败不得用 session.create 冒充「已恢复同一会话」，
            // 也不得把这一轮改投到别的会话（两帧 prompt.submit 都指向已回收的 live-1）。
            assertThat(frames(stub, "session.create")).hasSize(1);
            var submits = frames(stub, "prompt.submit");
            assertThat(submits).hasSize(2);
            assertThat(submits).allSatisfy(frame ->
                assertThat(frame.path("params").path("session_id").asString()).isEqualTo("live-1"));
        }
    }

    @Test
    void adoptingAKnownStoredIdResumesWithoutCreating() throws Exception {
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var resume = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(resume.path("id"),
                "{\"session_id\":\"live-r1\",\"stored_session_id\":\"stored-9\",\"message_count\":4,"
                    + "\"messages\":[],\"running\":false,\"status\":\"idle\"}"));
            var submit = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(submit.path("id"), "{\"status\":\"streaming\"}"));
            HermesWireStub.send(out, complete("live-r1", "续聊"));
            s.read(in);
        })) {
            var service = new HermesConversationService(stub.gateway(5), HermesWireStub.MAPPER);

            var submission = service.adopt("conv-9", "stored-9", "继续");
            assertThat(submission.sessionId()).isEqualTo("live-r1");
            assertThat(submission.storedSessionId()).isEqualTo("stored-9");
            assertThat(service.awaitFinalText("conv-9", WAIT)).isEqualTo("续聊");

            assertThat(frames(stub, "session.create")).isEmpty();
            assertThat(frames(stub, "session.resume")).hasSize(1);
            assertThat(frames(stub, "prompt.submit").getFirst().path("params").path("session_id").asString())
                .isEqualTo("live-r1");
        }
    }

    @Test
    void aQueuedReceiptIsNeverAttributedToThisTurnInsteadOfWaitingForATerminal() throws Exception {
        // 刻意收紧（不是放宽）：原用例 asserted「queued 也算受理、只是等不到终态要超时」，
        // 那等于默认「queued 之后到达的终态可以属于本轮」——这正是假成功的入口。
        // pin 的 prompt.submit 不回 turn_id、message.complete 只带 session_id，所以 queued 是
        // 「提交时该会话并不空闲」的唯一结构化证据：只允许一个结论——归属不可判定，立即 fail-closed、
        // 不进入等待、绝不把任何 message.complete 文本当成本轮答案。
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var create = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(create.path("id"),
                "{\"session_id\":\"live-1\",\"stored_session_id\":\"stored-1\"}"));
            var busy = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(busy.path("id"), "{\"status\":\"queued\"}"));
            // 上一轮的终态在 queued 之后到达：没有任何字段能证明它属于本轮。
            HermesWireStub.send(out, complete("live-1", "上一轮的答案"));
            // 「结果未知」之后 live id 已被丢弃（与 abandon 同一语义）：对账后的下一轮只能先
            // session.resume 做空闲判定，再提交到恢复出来的会话上。
            var resume = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(resume.path("id"),
                "{\"session_id\":\"live-1\",\"stored_session_id\":\"stored-1\",\"message_count\":2,"
                    + "\"messages\":[],\"running\":false,\"status\":\"idle\"}"));
            // 对账后的再次提交（归属重新可判定时才允许出现）。
            var retry = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(retry.path("id"), "{\"status\":\"streaming\"}"));
            HermesWireStub.send(out, complete("live-1", "对账后的本轮"));
            s.read(in);
        })) {
            var service = new HermesConversationService(stub.gateway(5), HermesWireStub.MAPPER);

            Throwable queuedFailure = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.submit("conv-1", "/state/workspace", "忙时提交"));
            assertThat(queuedFailure)
                .as("queued 回执不得被当成本轮已受理")
                .isInstanceOf(IllegalStateException.class);
            assertThat(queuedFailure.getMessage()).contains("对账");
            assertThat(causeMessages(queuedFailure)).noneMatch(message -> message.contains("上一轮的答案"));

            // 没有 inFlight：等待者无从消费那帧旧终态，更不可能把它返回给调用方。
            Throwable awaitFailure = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.awaitFinalText("conv-1", Duration.ofMillis(200)));
            assertThat(awaitFailure).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有等待终态的提交");
            assertThat(causeMessages(awaitFailure)).noneMatch(message -> message.contains("上一轮的答案"));

            // 归属不可判定 ⇒ 未对账前不得再提交（否则可能与那条仍在跑的轮次并存）。
            assertThatThrownBy(() -> service.submit("conv-1", "/state/workspace", "抢跑"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("对账");
            assertThat(frames(stub, "prompt.submit")).hasSize(1);

            // 闸门只允许显式对账放开；放开后仍是同一条 live 会话，旧终态由提交前屏障丢掉。
            service.reconcile("conv-1");
            assertThat(service.submit("conv-1", "/state/workspace", "对账后再问").sessionId()).isEqualTo("live-1");
            assertThat(service.awaitFinalText("conv-1", WAIT)).isEqualTo("对账后的本轮");
            assertThat(frames(stub, "session.resume"))
                .as("「结果未知」只有一种恢复语义：丢掉 live，下一轮必须经 resume 的空闲判定重新挂载")
                .hasSize(1);
        }
    }

    @Test
    void aQueuedReceiptAfterAnIdleLookingResumeNeverReturnsThePreviousTurnsText() throws Exception {
        // 复现复核探针 [P2]：resume 回来的状态看起来空闲，但服务端此刻已被别的轮次占住
        // （auto-continue / 另一客户端同会话），提交只拿到 queued。旧实现照常进入等待，
        // 随后到达的上一轮终态被当成本轮答案——必须失败，且不得返回该文本。
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var resume = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(resume.path("id"),
                "{\"session_id\":\"live-r1\",\"stored_session_id\":\"stored-9\",\"message_count\":8,"
                    + "\"messages\":[],\"running\":false,\"status\":\"idle\"}"));
            var submit = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(submit.path("id"), "{\"status\":\"queued\"}"));
            HermesWireStub.send(out, complete("live-r1", "上一轮（被别的轮次跑完）的答案"));
            s.read(in);
        })) {
            var service = new HermesConversationService(stub.gateway(5), HermesWireStub.MAPPER);

            Throwable failure = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.adopt("conv-9", "stored-9", "新一轮"));
            assertThat(failure)
                .as("queued 意味着提交时会话并不空闲，本轮终态不可归属")
                .isInstanceOf(IllegalStateException.class);
            assertThat(failure.getMessage()).contains("对账");

            Throwable awaitFailure = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.awaitFinalText("conv-9", Duration.ofMillis(200)));
            assertThat(awaitFailure).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有等待终态的提交");
            assertThat(awaitFailure.getMessage()).doesNotContain("上一轮");
            assertThat(frames(stub, "prompt.submit")).hasSize(1);
            assertThat(frames(stub, "session.create")).isEmpty();
        }
    }

    @Test
    void aWaitingResumedSessionIsNeverSubmittedEvenWhenRunningIsFalse() throws Exception {
        // pin 的 waiting 当且仅当该会话有一个未回答的 server→client 请求（审批/澄清），
        // server_requests.open_request_count 明确写 must not be treated as idle；
        // running=false + status=waiting 是合法组合（running 只反映 session["running"]）。
        // 此时提交只会被排到「上一轮尚未产生终态」之后，所以必须当成忙态、有界等待后 fail-closed。
        var resumes = new java.util.concurrent.atomic.AtomicInteger();
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            for (int i = 0; i < 200; i++) {
                var frame = s.read(in);
                if (frame == null) return;
                if ("session.resume".equals(frame.path("method").asString(""))) {
                    resumes.incrementAndGet();
                    HermesWireStub.send(out, HermesWireStub.jsonRpcResult(frame.path("id"),
                        "{\"session_id\":\"live-w1\",\"stored_session_id\":\"stored-w\",\"message_count\":5,"
                            + "\"messages\":[],\"running\":false,\"status\":\"waiting\"}"));
                } else {
                    // 走到这里说明实现向一条被人工审批挡住的会话提交了：queued 是唯一合法回执。
                    HermesWireStub.send(out, HermesWireStub.jsonRpcResult(frame.path("id"), "{\"status\":\"queued\"}"));
                }
            }
        })) {
            var service = new HermesConversationService(
                stub.gateway(5), HermesWireStub.MAPPER, 256, Duration.ofMillis(300));

            long started = System.nanoTime();
            assertThatThrownBy(() -> service.adopt("conv-w", "stored-w", "审批未答时提交"))
                .as("waiting 表示上一轮还没结束，绝不是可提交的空闲态")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("waiting")
                .hasMessageContaining("对账");
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                .as("等待空闲必须有界").isLessThan(Duration.ofSeconds(5));

            assertThat(resumes.get()).isGreaterThan(1);
            assertThat(frames(stub, "prompt.submit")).isEmpty();
            assertThat(frames(stub, "session.create")).isEmpty();
        }
    }

    @Test
    void aSecondInFlightTurnIsRejectedAndTheSlotReopensAfterTheTerminal() throws Exception {
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var create = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(create.path("id"),
                "{\"session_id\":\"live-1\",\"stored_session_id\":\"stored-1\"}"));
            var first = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(first.path("id"), "{\"status\":\"streaming\"}"));
            HermesWireStub.send(out, complete("live-1", "第一轮"));
            var second = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(second.path("id"), "{\"status\":\"streaming\"}"));
            HermesWireStub.send(out, complete("live-1", "第二轮"));
            s.read(in);
        })) {
            var service = new HermesConversationService(stub.gateway(5), HermesWireStub.MAPPER);
            service.submit("conv-1", "/state/workspace", "第一问");

            assertThatThrownBy(() -> service.submit("conv-1", "/state/workspace", "并发第二问"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未终态");
            assertThat(frames(stub, "prompt.submit")).hasSize(1);

            assertThat(service.awaitFinalText("conv-1", WAIT)).isEqualTo("第一轮");
            assertThat(service.submit("conv-1", "/state/workspace", "第三问").sessionId()).isEqualTo("live-1");
            assertThat(service.awaitFinalText("conv-1", WAIT)).isEqualTo("第二轮");
        }
    }

    @Test
    void concurrentSubmitOnTheSameConversationIsRejectedWhileTheOtherThreadIsInFlight() throws Exception {
        var firstSubmitOnWire = new java.util.concurrent.CountDownLatch(1);
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var create = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(create.path("id"),
                "{\"session_id\":\"live-1\",\"stored_session_id\":\"stored-1\"}"));
            var first = s.read(in);
            firstSubmitOnWire.countDown();
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(first.path("id"), "{\"status\":\"streaming\"}"));
            HermesWireStub.send(out, complete("live-1", "第一轮"));
            s.read(in);
        })) {
            var service = new HermesConversationService(stub.gateway(5), HermesWireStub.MAPPER);
            var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                var inFlight = executor.submit(() -> service.submit("conv-1", "/state/workspace", "并发第一问"));
                assertThat(firstSubmitOnWire.await(3, java.util.concurrent.TimeUnit.SECONDS))
                    .as("第一条提交必须真的出现在 wire 上").isTrue();

                // 另一个线程（这里是主线程）在第一位提交未终态时必须被明确拒绝，而不是再开一轮。
                assertThatThrownBy(() -> service.submit("conv-1", "/state/workspace", "并发第二问"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("未终态");

                assertThat(inFlight.get(3, java.util.concurrent.TimeUnit.SECONDS).sessionId()).isEqualTo("live-1");
                assertThat(service.awaitFinalText("conv-1", WAIT)).isEqualTo("第一轮");
                assertThat(frames(stub, "prompt.submit")).hasSize(1);
                assertThat(frames(stub, "session.create")).hasSize(1);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void differentConversationsStayIndependent() throws Exception {
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            // 帧序：createA, submitA, createB, submitB（两个会话各自建会话、各自提交一轮）。
            var createA = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(createA.path("id"),
                "{\"session_id\":\"live-a\",\"stored_session_id\":\"stored-a\"}"));
            var a = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(a.path("id"), "{\"status\":\"streaming\"}"));
            var createB = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(createB.path("id"),
                "{\"session_id\":\"live-b\",\"stored_session_id\":\"stored-b\"}"));
            var b = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(b.path("id"), "{\"status\":\"streaming\"}"));
            HermesWireStub.send(out, complete("live-a", "A 的回复"));
            HermesWireStub.send(out, complete("live-b", "B 的回复"));
            s.read(in);
        })) {
            var service = new HermesConversationService(stub.gateway(5), HermesWireStub.MAPPER);

            assertThat(service.submit("conv-a", "/state/workspace", "A 问").sessionId()).isEqualTo("live-a");
            assertThat(service.submit("conv-b", "/state/workspace", "B 问").sessionId()).isEqualTo("live-b");

            assertThat(service.awaitFinalText("conv-a", WAIT)).isEqualTo("A 的回复");
            assertThat(service.awaitFinalText("conv-b", WAIT)).isEqualTo("B 的回复");
            assertThat(frames(stub, "session.create")).hasSize(2);
        }
    }

    @Test
    void onlyThisLiveSessionsCompleteMessageIsAFinalText() throws Exception {
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var create = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(create.path("id"),
                "{\"session_id\":\"live-1\",\"stored_session_id\":\"stored-1\"}"));
            var submit = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(submit.path("id"), "{\"status\":\"streaming\"}"));
            // 别的会话的终态、工具完成、进度增量都不是本轮终态；只有最后一条 message.complete 算。
            HermesWireStub.send(out, event("live-other", "message.complete",
                "{\"text\":\"别人的回复\",\"status\":\"complete\"}"));
            HermesWireStub.send(out, event("live-1", "tool.complete", "{\"tool_id\":\"t1\",\"result\":\"非终态\"}"));
            HermesWireStub.send(out, delta("live-1", "进度"));
            HermesWireStub.send(out, complete("live-1", "本轮终态"));
            s.read(in);
        })) {
            var service = new HermesConversationService(stub.gateway(5), HermesWireStub.MAPPER);
            service.submit("conv-1", "/state/workspace", "问");
            assertThat(service.awaitFinalText("conv-1", WAIT)).isEqualTo("本轮终态");
        }
    }

    @Test
    void failedOrInterruptedTerminalIsNeverSuccess() throws Exception {
        for (String status : List.of("error", "interrupted", "cancelled")) {
            try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
                var create = s.read(in);
                HermesWireStub.send(out, HermesWireStub.jsonRpcResult(create.path("id"),
                    "{\"session_id\":\"live-1\",\"stored_session_id\":\"stored-1\"}"));
                var submit = s.read(in);
                HermesWireStub.send(out, HermesWireStub.jsonRpcResult(submit.path("id"), "{\"status\":\"streaming\"}"));
                HermesWireStub.send(out, event("live-1", "message.complete",
                    "{\"text\":\"看起来像回复\",\"status\":\"" + status + "\"}"));
                s.read(in);
            })) {
                var service = new HermesConversationService(stub.gateway(5), HermesWireStub.MAPPER);
                service.submit("conv-1", "/state/workspace", "问");
                assertThatThrownBy(() -> service.awaitFinalText("conv-1", WAIT))
                    .as("status=%s 不得当成成功", status)
                    .isInstanceOf(IllegalStateException.class);
            }
        }
    }

    @Test
    void twoTerminalsForOneTurnAreAmbiguousAndFail() throws Exception {
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var create = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(create.path("id"),
                "{\"session_id\":\"live-1\",\"stored_session_id\":\"stored-1\"}"));
            var submit = s.read(in);
            // 两帧终态都在受理回执之前落到网关缓冲：这样「同一轮两个终态」一定在同一次消费里被判为歧义，
            // 不会因为两帧分属不同消费批次而漏判（这是事件消费的批次语义，与本类无关，故测试必须确定化）。
            HermesWireStub.send(out, complete("live-1", "第一个终态"));
            HermesWireStub.send(out, complete("live-1", "第二个终态"));
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(submit.path("id"), "{\"status\":\"streaming\"}"));
            s.read(in);
        })) {
            var service = new HermesConversationService(stub.gateway(5), HermesWireStub.MAPPER);
            service.submit("conv-1", "/state/workspace", "问");
            assertThatThrownBy(() -> service.awaitFinalText("conv-1", WAIT))
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void aLostTerminalStreamIsAnExplicitFailureNotSilence() throws Exception {
        HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var create = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(create.path("id"),
                "{\"session_id\":\"live-1\",\"stored_session_id\":\"stored-1\"}"));
            var submit = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(submit.path("id"), "{\"status\":\"streaming\"}"));
            // 脚本结束即关闭连接：终态永远不会到达。
        });
        try {
            var service = new HermesConversationService(stub.gateway(5), HermesWireStub.MAPPER);
            service.submit("conv-1", "/state/workspace", "问");
            assertThatThrownBy(() -> service.awaitFinalText("conv-1", WAIT))
                .isInstanceOf(IllegalStateException.class);
        } finally {
            stub.close();
        }
    }

    @Test
    void upstreamPayloadAndSecretsNeverReachTheCallerMessage() throws Exception {
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var create = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(create.path("id"),
                "{\"session_id\":\"live-1\",\"stored_session_id\":\"stored-1\"}"));
            var submit = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcError(submit.path("id"), 5001,
                "rejected for token sk-live-DO-NOT-LEAK"));
            // 结果未知：live 已丢弃，对账后的下一轮先 resume（空闲判定）再提交。
            var resume = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(resume.path("id"),
                "{\"session_id\":\"live-1\",\"stored_session_id\":\"stored-1\",\"message_count\":2,"
                    + "\"messages\":[],\"running\":false,\"status\":\"idle\"}"));
            var retry = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(retry.path("id"), "{\"status\":\"streaming\"}"));
            HermesWireStub.send(out, complete("live-1", "对账后重试"));
            s.read(in);
        })) {
            var service = new HermesConversationService(stub.gateway(5), HermesWireStub.MAPPER);

            Throwable failure = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.submit("conv-1", "/state/workspace", "问"));
            assertThat(failure).isInstanceOf(IllegalStateException.class);
            assertThat(failure.getMessage()).doesNotContain("DO-NOT-LEAK");
            assertThat(causeMessages(failure)).noneMatch(message -> message.contains("DO-NOT-LEAK"));
            assertThat(failure.getMessage()).contains("对账");

            // 上一次提交结果未知：未对账前不得再提交（否则可能同一会话两轮并存）。
            assertThatThrownBy(() -> service.submit("conv-1", "/state/workspace", "抢跑"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("对账");

            service.reconcile("conv-1");
            assertThat(service.submit("conv-1", "/state/workspace", "重试").sessionId()).isEqualTo("live-1");
            assertThat(service.awaitFinalText("conv-1", WAIT)).isEqualTo("对账后重试");
        }
    }

    @Test
    void conversationTableIsBoundedAndEvictsOnlyIdleBindings() throws Exception {
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            for (String live : List.of("live-a", "live-b", "live-c")) {
                var create = s.read(in);
                HermesWireStub.send(out, HermesWireStub.jsonRpcResult(create.path("id"),
                    "{\"session_id\":\"" + live + "\",\"stored_session_id\":\"stored-" + live + "\"}"));
                var submit = s.read(in);
                HermesWireStub.send(out, HermesWireStub.jsonRpcResult(submit.path("id"), "{\"status\":\"streaming\"}"));
                HermesWireStub.send(out, complete(live, live + " 回复"));
            }
            s.read(in);
        })) {
            var service = new HermesConversationService(stub.gateway(5), HermesWireStub.MAPPER, 2);

            for (String key : List.of("conv-a", "conv-b", "conv-c")) {
                service.submit(key, "/state/workspace", key + " 问");
                assertThat(service.awaitFinalText(key, WAIT)).endsWith("回复");
            }

            assertThat(service.conversationCount()).isEqualTo(2);
            assertThat(frames(stub, "session.create")).hasSize(3);
        }
    }

    @Test
    void capacityLimitRejectsNewConversationsWhenEveryBindingIsBusy() throws Exception {
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var create = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(create.path("id"),
                "{\"session_id\":\"live-a\",\"stored_session_id\":\"stored-a\"}"));
            var submit = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(submit.path("id"), "{\"status\":\"streaming\"}"));
            s.read(in);
        })) {
            var service = new HermesConversationService(stub.gateway(5), HermesWireStub.MAPPER, 1);
            service.submit("conv-a", "/state/workspace", "A 问");

            assertThatThrownBy(() -> service.submit("conv-b", "/state/workspace", "B 问"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("上限");
            assertThat(service.conversationCount()).isEqualTo(1);
            assertThat(frames(stub, "session.create")).hasSize(1);
        }
    }

    @Test
    void serviceHoldsNoCredentialShapedState() {
        for (java.lang.reflect.Field field : HermesConversationService.class.getDeclaredFields()) {
            assertThat(field.getName().toLowerCase())
                .as("会话服务不得缓存凭据字段：%s", field.getName())
                .doesNotContain("token").doesNotContain("secret").doesNotContain("password")
                .doesNotContain("apikey").doesNotContain("credential");
        }
    }

    // ------------------------------------------------------------------------------------
    // F1：恢复进「仍在运行」的会话时，不得把上一轮的终态当作本轮成功。
    // pin 的 prompt.submit 不回 turn_id，终态事件只带 session_id，所以归属只能靠
    // 「提交前会话必空闲 + 提交前清空旧终态」推断；任一前提不成立就必须 fail-closed。
    // ------------------------------------------------------------------------------------

    @Test
    void aRunningRecoveredSessionIsNeverSubmittedAndTheStaleTerminalCannotBecomeSuccess() throws Exception {
        var resumes = new java.util.concurrent.atomic.AtomicInteger();
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            // 会话一直在跑：每次重读状态都回 working。服务只允许在有界期限内等待，绝不直接提交。
            for (int i = 0; i < 200; i++) {
                var frame = s.read(in);
                if (frame == null) return;
                if ("session.resume".equals(frame.path("method").asString(""))) {
                    resumes.incrementAndGet();
                    HermesWireStub.send(out, HermesWireStub.jsonRpcResult(frame.path("id"),
                        "{\"session_id\":\"live-r1\",\"stored_session_id\":\"stored-9\",\"message_count\":4,"
                            + "\"messages\":[],\"running\":true,\"status\":\"working\"}"));
                    // 上一轮还没结束，它的终态随时可能到达——它绝不是本轮的答案。
                    HermesWireStub.send(out, complete("live-r1", "上一轮的答案"));
                } else {
                    // 走到这里说明实现直接在非空闲会话上提交了：回 queued（合法但绝不是完成）。
                    HermesWireStub.send(out, HermesWireStub.jsonRpcResult(frame.path("id"), "{\"status\":\"queued\"}"));
                }
            }
        })) {
            var service = new HermesConversationService(
                stub.gateway(5), HermesWireStub.MAPPER, 256, Duration.ofMillis(250));

            long started = System.nanoTime();
            assertThatThrownBy(() -> service.adopt("conv-9", "stored-9", "新一轮"))
                .as("恢复出仍在运行的会话时必须明确拒绝，而不是提交后拿旧终态当答案")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("对账");
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                .as("等待空闲必须有界").isLessThan(Duration.ofSeconds(5));

            // wire 断言：一次 prompt.submit 都不许发出（也就没有 queued 可言）。
            assertThat(frames(stub, "prompt.submit")).isEmpty();
            assertThat(frames(stub, "session.create")).isEmpty();
            assertThat(resumes.get()).isGreaterThan(1);
        }
    }

    @Test
    void aRecoveredSessionThatBecomesIdleIsSubmittedAfterTheWait() throws Exception {
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var busy = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(busy.path("id"),
                "{\"session_id\":\"live-r1\",\"stored_session_id\":\"stored-9\",\"message_count\":4,"
                    + "\"messages\":[],\"running\":true,\"status\":\"working\"}"));
            var idle = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(idle.path("id"),
                "{\"session_id\":\"live-r1\",\"stored_session_id\":\"stored-9\",\"message_count\":6,"
                    + "\"messages\":[],\"running\":false,\"status\":\"idle\"}"));
            var submit = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(submit.path("id"), "{\"status\":\"streaming\"}"));
            HermesWireStub.send(out, complete("live-r1", "空闲后的一轮"));
            s.read(in);
        })) {
            var service = new HermesConversationService(
                stub.gateway(5), HermesWireStub.MAPPER, 256, Duration.ofSeconds(2));

            var submission = service.adopt("conv-9", "stored-9", "继续");
            assertThat(submission.sessionId()).isEqualTo("live-r1");
            assertThat(service.awaitFinalText("conv-9", WAIT)).isEqualTo("空闲后的一轮");

            var methods = methods(stub);
            int lastResume = methods.lastIndexOf("session.resume");
            assertThat(lastResume).isGreaterThanOrEqualTo(0);
            assertThat(methods.indexOf("prompt.submit")).as("提交必须发生在读到空闲之后").isGreaterThan(lastResume);
            assertThat(frames(stub, "session.create")).isEmpty();
        }
    }

    @Test
    void aTerminalBufferedBeforeSubmitIsNeverThisTurnsAnswer() throws Exception {
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            // 旧轮次的终态在恢复之前就已经缓冲在网关里（旧消息，不属于本轮）。
            HermesWireStub.send(out, complete("live-r1", "上一轮的答案"));
            var resume = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(resume.path("id"),
                "{\"session_id\":\"live-r1\",\"stored_session_id\":\"stored-9\",\"message_count\":8,"
                    + "\"messages\":[],\"running\":false,\"status\":\"idle\"}"));
            var submit = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(submit.path("id"), "{\"status\":\"streaming\"}"));
            // 本轮只有增量、没有终态：必须超时失败，不能把上面的旧终态当成本轮成功。
            HermesWireStub.send(out, delta("live-r1", "本轮还在生成"));
            HermesWireStub.send(out, delta("live-r1", "仍然在生成"));
            s.read(in);
        })) {
            var service = new HermesConversationService(stub.gateway(5), HermesWireStub.MAPPER);

            assertThat(service.adopt("conv-9", "stored-9", "新一轮").sessionId()).isEqualTo("live-r1");
            assertThatThrownBy(() -> service.awaitFinalText("conv-9", Duration.ofMillis(400)))
                .as("旧终态不得被当成本轮答案")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("终态");
            assertThat(frames(stub, "prompt.submit")).hasSize(1);
        }
    }

    @Test
    void thisTurnsTerminalWinsOverATerminalBufferedBeforeSubmit() throws Exception {
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            HermesWireStub.send(out, complete("live-r1", "上一轮的答案"));
            var resume = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(resume.path("id"),
                "{\"session_id\":\"live-r1\",\"stored_session_id\":\"stored-9\",\"message_count\":8,"
                    + "\"messages\":[],\"running\":false,\"status\":\"idle\"}"));
            var submit = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(submit.path("id"), "{\"status\":\"streaming\"}"));
            HermesWireStub.send(out, complete("live-r1", "本轮答案"));
            s.read(in);
        })) {
            var service = new HermesConversationService(stub.gateway(5), HermesWireStub.MAPPER);

            assertThat(service.adopt("conv-9", "stored-9", "新一轮").sessionId()).isEqualTo("live-r1");
            assertThat(service.awaitFinalText("conv-9", WAIT)).isEqualTo("本轮答案");
        }
    }

    // ------------------------------------------------------------------------------------
    // F2：只有 JSON-RPC 顶层 error.code 恰好是整数 4001 才算「会话已回收」。
    // 嵌套 data.code、多个互相矛盾的码、无法解析的码都算未知失败（对账），不得 resume 重投。
    // ------------------------------------------------------------------------------------

    @Test
    void aNestedDataCodeIsNeverMistakenForSessionRecycling() throws Exception {
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var create = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(create.path("id"),
                "{\"session_id\":\"live-1\",\"stored_session_id\":\"stored-1\"}"));
            var first = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(first.path("id"), "{\"status\":\"streaming\"}"));
            HermesWireStub.send(out, complete("live-1", "第一轮"));
            // 第二轮打在已有 live 会话上：嵌套 data.code 在顶层 code 之前，按文本取第一个 code 会误判成 4001。
            var second = s.read(in);
            HermesWireStub.send(out, rawJsonRpcError(second.path("id"),
                "{\"data\":{\"code\":4001},\"code\":5001,\"message\":\"upstream rejected\"}"));
            s.read(in);
        })) {
            var service = new HermesConversationService(stub.gateway(5), HermesWireStub.MAPPER);
            service.submit("conv-1", "/state/workspace", "第一问");
            assertThat(service.awaitFinalText("conv-1", WAIT)).isEqualTo("第一轮");

            assertThatThrownBy(() -> service.submit("conv-1", "/state/workspace", "第二问"))
                .as("顶层 5001 是未知失败，不是会话回收")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("5001")
                .hasMessageContaining("对账");

            // 关键：不得把它当成会话回收去做 resume（更不许拿 stored 重投）。
            assertThat(frames(stub, "session.resume")).isEmpty();
            assertThat(frames(stub, "session.create")).hasSize(1);
            assertThat(frames(stub, "prompt.submit")).hasSize(2);
            assertThatThrownBy(() -> service.submit("conv-1", "/state/workspace", "抢跑"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("对账");
        }
    }

    @Test
    void aTopLevelCodeOtherThan4001IsNeverSessionRecycling() throws Exception {
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var create = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(create.path("id"),
                "{\"session_id\":\"live-1\",\"stored_session_id\":\"stored-1\"}"));
            var first = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(first.path("id"), "{\"status\":\"streaming\"}"));
            HermesWireStub.send(out, complete("live-1", "第一轮"));
            var second = s.read(in);
            // 正文里带着 4001（例如上游提到别的会话），但顶层是 5001：不得当成会话回收。
            HermesWireStub.send(out, rawJsonRpcError(second.path("id"),
                "{\"code\":5001,\"message\":\"boom (whisper session 4001 unavailable)\"}"));
            s.read(in);
        })) {
            var service = new HermesConversationService(stub.gateway(5), HermesWireStub.MAPPER);
            service.submit("conv-1", "/state/workspace", "第一问");
            assertThat(service.awaitFinalText("conv-1", WAIT)).isEqualTo("第一轮");

            assertThatThrownBy(() -> service.submit("conv-1", "/state/workspace", "第二问"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("5001")
                .hasMessageContaining("对账");

            assertThat(frames(stub, "session.resume")).isEmpty();
            assertThat(frames(stub, "session.create")).hasSize(1);
            assertThat(frames(stub, "prompt.submit")).hasSize(2);
        }
    }

    // ------------------------------------------------------------------------------------
    // F3：对账闸门不得被容量淘汰；无绑定的 reconcile 必须显式报错。
    // ------------------------------------------------------------------------------------

    @Test
    void aNeedsReconcileBindingSurvivesCapacityPressureInsteadOfBeingEvicted() throws Exception {
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var create = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(create.path("id"),
                "{\"session_id\":\"live-a\",\"stored_session_id\":\"stored-a\"}"));
            var submit = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcError(submit.path("id"), 5001, "upstream rejected"));
            // 结果未知：live 已丢弃，对账之后先 resume 再提交（同一条持久会话）。
            var resume = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(resume.path("id"),
                "{\"session_id\":\"live-a\",\"stored_session_id\":\"stored-a\",\"message_count\":2,"
                    + "\"messages\":[],\"running\":false,\"status\":\"idle\"}"));
            // 对账之后才允许重投：这一帧对应 reconcile 后的那次提交。
            var retry = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(retry.path("id"), "{\"status\":\"streaming\"}"));
            HermesWireStub.send(out, complete("live-a", "对账后的一轮"));
            s.read(in);
        })) {
            var service = new HermesConversationService(stub.gateway(5), HermesWireStub.MAPPER, 1);

            assertThatThrownBy(() -> service.submit("conv-a", "/state/workspace", "A 问"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("对账");

            // 唯一绑定待对账：容量压力下必须明确拒绝新会话，绝不能淘汰掉对账闸门。
            assertThatThrownBy(() -> service.submit("conv-b", "/state/workspace", "B 问"))
                .as("不得淘汰待对账绑定腾位")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("上限");
            assertThat(service.conversationCount()).isEqualTo(1);
            assertThat(frames(stub, "session.create")).hasSize(1);

            // 闸门仍在：在没有 reconcile 之前，原会话依旧被拦。
            assertThatThrownBy(() -> service.submit("conv-a", "/state/workspace", "抢跑"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("对账");

            service.reconcile("conv-a");
            assertThat(service.submit("conv-a", "/state/workspace", "重试").sessionId()).isEqualTo("live-a");
            assertThat(service.awaitFinalText("conv-a", WAIT)).isEqualTo("对账后的一轮");
            assertThat(frames(stub, "session.create")).hasSize(1);
        }
    }

    @Test
    void reconcileOnAMissingBindingFailsInsteadOfSilentlyReturning() throws Exception {
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            for (String live : List.of("live-a", "live-b", "live-c")) {
                var create = s.read(in);
                HermesWireStub.send(out, HermesWireStub.jsonRpcResult(create.path("id"),
                    "{\"session_id\":\"" + live + "\",\"stored_session_id\":\"stored-" + live + "\"}"));
                var submit = s.read(in);
                HermesWireStub.send(out, HermesWireStub.jsonRpcResult(submit.path("id"), "{\"status\":\"streaming\"}"));
                HermesWireStub.send(out, complete(live, live + " 回复"));
            }
            s.read(in);
        })) {
            var service = new HermesConversationService(stub.gateway(5), HermesWireStub.MAPPER, 2);

            for (String key : List.of("conv-a", "conv-b", "conv-c")) {
                service.submit(key, "/state/workspace", key + " 问");
                assertThat(service.awaitFinalText(key, WAIT)).endsWith("回复");
            }
            // conv-a 已被容量淘汰：绑定确实丢了，reconcile 必须说出来，而不是静默成功。
            assertThat(service.conversationCount()).isEqualTo(2);
            assertThatThrownBy(() -> service.reconcile("conv-a"))
                .as("绑定已丢失时 reconcile 必须报错")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有绑定");
            assertThatThrownBy(() -> service.reconcile("从来没有提交过的会话"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有绑定");
        }
    }

    // ------------------------------------------------------------------------------------
    // Low：live 被回收但没有持久 id 时，恢复不可证明——不得走「新建会话」分支。
    // ------------------------------------------------------------------------------------

    @Test
    void aRecycledLiveSessionWithoutStoredIdFailsClosedInsteadOfCreatingANewSession() throws Exception {
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var create = s.read(in);
            // 网关只回了运行时 id，没有持久 id：这条会话没有可证明的恢复依据。
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(create.path("id"), "{\"session_id\":\"live-1\"}"));
            var first = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(first.path("id"), "{\"status\":\"streaming\"}"));
            HermesWireStub.send(out, complete("live-1", "第一轮"));
            // 第二轮打到已回收的 live id：pin 回 4001，而绑定里没有 stored id。
            var dead = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcError(dead.path("id"), 4001, "session not found"));
            s.read(in);
        })) {
            var service = new HermesConversationService(stub.gateway(5), HermesWireStub.MAPPER);
            service.submit("conv-1", "/state/workspace", "第一问");
            assertThat(service.awaitFinalText("conv-1", WAIT)).isEqualTo("第一轮");

            assertThatThrownBy(() -> service.submit("conv-1", "/state/workspace", "第二问"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未自动新建会话");

            assertThat(frames(stub, "session.create")).as("不得用新会话冒充同一条会话").hasSize(1);
            assertThat(frames(stub, "session.resume")).isEmpty();
            assertThat(frames(stub, "prompt.submit")).hasSize(2);
        }
    }

    // ------------------------------------------------------------------------------------
    // 放弃一轮：受理之后本地失败时必须有一条「有主的释放」路径，且不替本轮推断终态。
    // ------------------------------------------------------------------------------------

    /**
     * {@code abandon} 之后：占位被释放（没有消费者能再取到终态）、闸门变成「必须对账」、
     * 而且恢复只能经 {@code session.resume} 的空闲判定重新挂载——不得沿用可能仍在跑的 live id。
     */
    @Test
    void abandonReleasesTheSlotAndOnlyReconcileOpensTheGateAgain() throws Exception {
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var create = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(create.path("id"),
                "{\"session_id\":\"live-1\",\"stored_session_id\":\"stored-1\"}"));
            var first = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(first.path("id"), "{\"status\":\"streaming\"}"));
            // 这一轮不会再有人等终态：调用方在受理之后本地失败，于是放弃它。
            var resume = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(resume.path("id"),
                "{\"session_id\":\"live-2\",\"resumed\":\"stored-1\",\"message_count\":1,\"messages\":[],"
                    + "\"running\":false,\"status\":\"idle\"}"));
            var second = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(second.path("id"), "{\"status\":\"streaming\"}"));
            HermesWireStub.send(out, complete("live-2", "对账后的一轮"));
            s.read(in);
        })) {
            var service = new HermesConversationService(stub.gateway(5), HermesWireStub.MAPPER);
            service.submit("conv-1", "/state/workspace", "第一问");
            assertThat(service.needsReconcile("conv-1")).isFalse();
            assertThat(frames(stub, "session.create")).hasSize(1);

            service.abandon("conv-1", "受理未落账（测试）");

            assertThat(service.needsReconcile("conv-1")).as("放弃之后必须明确要求对账").isTrue();
            assertThatThrownBy(() -> service.awaitFinalText("conv-1", WAIT))
                .as("占位已释放：不再有可等待的提交，也就不会把任何上游终态归因给这一轮")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有等待终态的提交");
            assertThatThrownBy(() -> service.submit("conv-1", "/state/workspace", "抢跑"))
                .as("闸门仍关着，且原因可读")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("受理未落账（测试）");
            assertThat(frames(stub, "prompt.submit")).as("放弃之后不得再打 wire").hasSize(1);

            service.reconcile("conv-1");
            var recovered = service.submit("conv-1", "/state/workspace", "第二问");
            assertThat(recovered.sessionId())
                .as("只能经 session.resume 重新挂载，不得沿用可能仍在跑的那条 live 会话").isEqualTo("live-2");
            assertThat(recovered.storedSessionId()).isEqualTo("stored-1");
            assertThat(service.awaitFinalText("conv-1", WAIT)).isEqualTo("对账后的一轮");
            assertThat(frames(stub, "session.create")).as("恢复不得新建会话").hasSize(1);
            assertThat(frames(stub, "session.resume")).hasSize(1);
        }
    }

    /** 没有绑定时 {@code abandon} 是无操作：它总在错误收尾路径上被调用，不许再造一个待对账闸门。 */
    @Test
    void abandonOnAMissingBindingIsANoOpInsteadOfFabricatingAGate() throws Exception {
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            var create = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(create.path("id"),
                "{\"session_id\":\"live-1\",\"stored_session_id\":\"stored-1\"}"));
            var submit = s.read(in);
            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(submit.path("id"), "{\"status\":\"streaming\"}"));
            HermesWireStub.send(out, complete("live-1", "正常一轮"));
            s.read(in);
        })) {
            var service = new HermesConversationService(stub.gateway(5), HermesWireStub.MAPPER);

            service.abandon("从来没有提交过的会话", "无绑定");

            assertThat(service.conversationCount()).isZero();
            assertThat(service.needsReconcile("从来没有提交过的会话")).isFalse();
            service.submit("conv-1", "/state/workspace", "问");
            assertThat(service.awaitFinalText("conv-1", WAIT)).isEqualTo("正常一轮");
        }
    }

    /**
     * 「结果未知」只有<b>一种</b>恢复语义：{@code abandon}（受理之后本地失败）与 {@code failTurn}
     * （受理方式不是 {@code ACCEPTED_NOW}、上游失败）都必须丢掉 live id，下一轮只能经
     * {@code session.resume} 的空闲判定重新挂载。
     *
     * <p>为什么必须统一：两条路径都表示「本轮可能已经在网关侧跑起来了，而本端拿不到它的终态」。
     * 若一条丢 live、另一条留着，下一轮就会在同一场景下走两个分支——live 直投（只会拿到 {@code queued}
     * 再 fail-closed，把同一条输入反复 steer 进仍在跑的那一轮）或 resume 空闲判定（有界等待后正常提交）。
     * 统一到 resume 一侧是更保守的那个：忙就拒绝，而不是把重试塞进旧轮次。
     */
    @Test
    void anUnknownOutcomeRecoversOnlyThroughResumeWhetherItCameFromAbandonOrAFailedSubmit() throws Exception {
        var resumes = new java.util.concurrent.atomic.AtomicInteger();
        List<String> submitted = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        try (HermesWireStub stub = new HermesWireStub((s, in, out) -> {
            for (int i = 0; i < 200; i++) {
                var frame = s.read(in);
                if (frame == null) return;
                String method = frame.path("method").asString("");
                String text = frame.path("params").path("text").asString("");
                switch (method) {
                    case "session.create" -> HermesWireStub.send(out, HermesWireStub.jsonRpcResult(frame.path("id"),
                        "{\"session_id\":\"live-1\",\"stored_session_id\":\"stored-1\"}"));
                    case "session.resume" -> {
                        resumes.incrementAndGet();
                        HermesWireStub.send(out, HermesWireStub.jsonRpcResult(frame.path("id"),
                            "{\"session_id\":\"live-r\",\"stored_session_id\":\"stored-1\",\"message_count\":2,"
                                + "\"messages\":[],\"running\":false,\"status\":\"idle\"}"));
                    }
                    case "prompt.submit" -> {
                        String sessionId = frame.path("params").path("session_id").asString("");
                        submitted.add(sessionId + "|" + text);
                        if (text.startsWith("忙")) {
                            // 忙会话的提交只会被排队：本轮终态不可归属 → fail-closed。
                            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(frame.path("id"),
                                "{\"status\":\"queued\"}"));
                        } else {
                            HermesWireStub.send(out, HermesWireStub.jsonRpcResult(frame.path("id"),
                                "{\"status\":\"streaming\"}"));
                            if (text.startsWith("恢复")) {
                                HermesWireStub.send(out, complete(sessionId, "恢复后的" + text));
                            }
                        }
                    }
                    default -> HermesWireStub.send(out, HermesWireStub.jsonRpcError(frame.path("id"), -32601,
                        "unsupported method"));
                }
            }
        })) {
            var service = new HermesConversationService(stub.gateway(5), HermesWireStub.MAPPER);

            // 路径 A：受理之后本地失败 —— 调用方放弃这一轮。
            service.submit("conv-a", "/state/workspace", "放弃的一轮");
            service.abandon("conv-a", "受理未落账（测试）");
            // 路径 B：受理方式不是 ACCEPTED_NOW —— 运行时自己判定「结果未知」。
            assertThatThrownBy(() -> service.submit("conv-b", "/state/workspace", "忙时提交"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("对账");

            assertThat(service.needsReconcile("conv-a")).isTrue();
            assertThat(service.needsReconcile("conv-b")).isTrue();
            service.reconcile("conv-a");
            service.reconcile("conv-b");

            service.submit("conv-a", "/state/workspace", "恢复甲");
            assertThat(service.awaitFinalText("conv-a", WAIT)).isEqualTo("恢复后的恢复甲");
            service.submit("conv-b", "/state/workspace", "恢复乙");
            assertThat(service.awaitFinalText("conv-b", WAIT)).isEqualTo("恢复后的恢复乙");

            assertThat(resumes.get()).as("两条「结果未知」路径都必须经 session.resume 重新挂载").isEqualTo(2);
            assertThat(submitted).filteredOn(entry -> entry.startsWith("live-1|恢复"))
                .as("不得把新一轮直接提交到可能仍在跑的原 live 会话").isEmpty();
            assertThat(submitted).filteredOn(entry -> entry.startsWith("live-r|恢复")).hasSize(2);
        }
    }

    private static String rawJsonRpcError(JsonNode id, String errorJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id.toString() + ",\"error\":" + errorJson + "}";
    }

    private static List<String> methods(HermesWireStub stub) {
        synchronized (stub.inbound) {
            return stub.inbound.stream().map(node -> node.path("method").asString("")).toList();
        }
    }

    private static List<JsonNode> frames(HermesWireStub stub, String method) {
        synchronized (stub.inbound) {
            return stub.inbound.stream()
                .filter(node -> method.equals(node.path("method").asString("")))
                .toList();
        }
    }

    private static List<String> causeMessages(Throwable failure) {
        List<String> messages = new java.util.ArrayList<>();
        for (Throwable cursor = failure; cursor != null; cursor = cursor.getCause()) {
            if (cursor.getMessage() != null) messages.add(cursor.getMessage());
        }
        return messages;
    }

    private static String complete(String session, String text) {
        return event(session, "message.complete", "{\"text\":\"" + text + "\",\"status\":\"complete\"}");
    }

    private static String delta(String session, String text) {
        return event(session, "message.delta", "{\"text\":\"" + text + "\"}");
    }

    private static String event(String session, String type, String payloadJson) {
        return "{\"jsonrpc\":\"2.0\",\"method\":\"event\",\"params\":{\"session_id\":\"" + session
            + "\",\"type\":\"" + type + "\",\"payload\":" + payloadJson + "}}";
    }

    /** 编译期引用一次真实网关类型，确保测试确实跑在 TCP 传输之上。 */
    @SuppressWarnings("unused")
    private static Class<?> transportUnderTest() {
        return HermesTcpGateway.class;
    }
}
