package com.haitlify.aitest.platform.assistant.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.haitlify.aitest.platform.assistant.ledger.AssistantAdmissionService;
import com.haitlify.aitest.platform.assistant.ledger.AssistantLedgerRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 派发段：先落 attempt 再提交 Runner；提交不可判定 ⇒ UNKNOWN 并冻结；终态只写一次。
 * Runner 用本地桩（HttpServer），不启动容器、不联网。
 */
@SpringBootTest(properties = "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=")
@ActiveProfiles("test")
class AssistantDispatchServiceTest {

    private static final HttpServer STUB = startStub();
    private static final AtomicInteger RUN_SEQ = new AtomicInteger();
    private static volatile String createMode = "ok";
    private static volatile String runStatus = "running";
    /** 每个 run 自己的状态：真实 Runner 停一个不影响另一个，桩不能全局串味。 */
    private static final java.util.Map<String, String> runStatusById = new java.util.concurrent.ConcurrentHashMap<>();
    /** 模拟 Runner 已回收：这些 runId 的 GET/stop 一律 404。 */
    private static final java.util.Set<String> lostRuns =
        java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile int failedCount = 0;
    private static final java.util.concurrent.atomic.AtomicInteger stopCalls =
        new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.List<String> stoppedTargets =
        java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private static volatile int passedCount = 1;

    @DynamicPropertySource
    static void runnerProps(DynamicPropertyRegistry registry) {
        registry.add("app.web-automation.runner.base-url", () -> "http://127.0.0.1:" + STUB.getAddress().getPort());
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/runs", AssistantDispatchServiceTest::handle);
            server.start();
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException("桩 Runner 启动失败", ex);
        }
    }

    private static boolean isLost(String path) {
        int from = path.lastIndexOf("/runs/") + 6;
        int to = path.indexOf('/', from);
        return lostRuns.contains(path.substring(from, to < 0 ? path.length() : to));
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String body;
        int status;
        if ("POST".equals(method) && "/runs".equals(path)) {
            if ("fail".equals(createMode)) {
                status = 500;
                body = "{\"message\":\"boom\"}";
            } else {
                status = 202;
                // 只在 stop 时写 per-run 状态；未写过的 run 回落到全局 runStatus（测试用它模拟终态）。
                body = "{\"runId\":\"run-" + RUN_SEQ.incrementAndGet() + "\",\"status\":\"queued\"}";
            }
        } else if (("POST".equals(method) && path.endsWith("/stop")
            || "GET".equals(method) && path.startsWith("/runs/")) && isLost(path)) {
            status = 404;
            body = "{}";
        } else if ("POST".equals(method) && path.endsWith("/stop")) {
            stopCalls.incrementAndGet();
            stoppedTargets.add(path.substring(path.lastIndexOf("/runs/") + 6, path.lastIndexOf('/')));
            boolean failed = "stopFail".equals(createMode);
            // 真实 Runner 收到 stop 后状态才变 cancelled；stopNoEffect 模拟「stop 送达但没生效」。
            if (!failed && !"stopNoEffect".equals(createMode)) {
                runStatusById.put(stoppedTargets.get(stoppedTargets.size() - 1), "cancelled");
            }
            status = failed ? 500 : 200;
            body = "{\"status\":\"cancelled\"}";
        } else if ("GET".equals(method) && path.startsWith("/runs/")) {
            status = 200;
            String target = path.substring("/runs/".length());
            body = "{\"runId\":\"" + target + "\",\"status\":\"" + runStatusById.getOrDefault(target, runStatus)
                + "\",\"summary\":{\"total\":" + (passedCount + failedCount) + ",\"passed\":" + passedCount
                + ",\"failed\":" + failedCount + ",\"error\":0}}";
        } else {
            status = 404;
            body = "{}";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("content-type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Autowired
    private AssistantDispatchService dispatch;

    @Autowired
    private AssistantAdmissionService admission;

    @Autowired
    private AssistantLedgerRepository repository;

    private long tenant;

    @BeforeEach
    void reset() {
        createMode = "ok";
        runStatus = "running";
        failedCount = 0;
        passedCount = 1;
        tenant = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1_000_000L;
    }

    private AssistantAdmissionService.AdmissionResult admit() {
        String requestId = UUID.randomUUID().toString();
        return admission.admit(new AssistantAdmissionService.AdmissionCommand(tenant, 5L, null, requestId,
            "a".repeat(64), "web.contact.create", "project:1/contacts", "rev-1", null));
    }

    @Test
    void dispatchBindsRunAndAllocatesResources() {
        AssistantAdmissionService.AdmissionResult admitted = admit();

        AssistantDispatchService.DispatchResult result = dispatch.dispatch(new AssistantDispatchService.DispatchCommand(
            tenant, 5L, admitted.requestId(), admitted.operationId(), "{\"cases\":[]}", null));

        assertThat(result.unknown()).isFalse();
        assertThat(result.runId()).startsWith("run-");
        assertThat(result.executionState()).isEqualTo("DISPATCHED");

        AssistantLedgerRepository.OperationRecord operation = repository.findOperation(admitted.operationId()).orElseThrow();
        assertThat(operation.executionState()).isEqualTo("DISPATCHED");
        assertThat(operation.dispatchGeneration()).isEqualTo(1);
        assertThat(repository.findRequest(tenant, admitted.requestId()).orElseThrow().admissionState())
            .isEqualTo("DISPATCHED");

        assertThat(repository.listAttempts(admitted.operationId())).singleElement()
            .satisfies(attempt -> {
                assertThat(attempt.generation()).isEqualTo(1);
                assertThat(attempt.runnerRunId()).isEqualTo(result.runId());
            });
        assertThat(repository.findUnreclaimed(java.time.Instant.now().plus(java.time.Duration.ofHours(3))))
            .filteredOn(resource -> resource.operationId().equals(admitted.operationId()))
            .extracting(AssistantLedgerRepository.ResourceRecord::resourceKind)
            .containsExactlyInAnyOrder("BROWSER", "TEST_DATA");
    }

    @Test
    void undecidableDispatchFreezesOperation() {
        AssistantAdmissionService.AdmissionResult admitted = admit();
        createMode = "fail";

        AssistantDispatchService.DispatchResult result = dispatch.dispatch(new AssistantDispatchService.DispatchCommand(
            tenant, 5L, admitted.requestId(), admitted.operationId(), "{\"cases\":[]}", null));

        assertThat(result.unknown()).isTrue();
        assertThat(result.runId()).isNull();
        assertThat(repository.findOperation(admitted.operationId()).orElseThrow().executionState()).isEqualTo("UNKNOWN");
        assertThat(repository.listAttempts(admitted.operationId())).singleElement()
            .satisfies(attempt -> assertThat(attempt.outcome()).isEqualTo("UNKNOWN"));
        assertThat(repository.countReconciliation(admitted.operationId(), "UNKNOWN_RAISED")).isEqualTo(1);
        assertThat(repository.findRequest(tenant, admitted.requestId()).orElseThrow().admissionState())
            .isEqualTo("UNKNOWN");

        assertThatThrownBy(() -> dispatch.dispatch(new AssistantDispatchService.DispatchCommand(
            tenant, 5L, admitted.requestId(), admitted.operationId(), "{\"cases\":[]}", null)))
            .isInstanceOf(AssistantAdmissionService.DispatchFrozenException.class);
    }

    @Test
    void synchronizeFollowsRunnerToTerminalStateOnce() {
        AssistantAdmissionService.AdmissionResult admitted = admit();
        dispatch.dispatch(new AssistantDispatchService.DispatchCommand(
            tenant, 5L, admitted.requestId(), admitted.operationId(), "{\"cases\":[]}", null));

        AssistantDispatchService.SyncResult running = dispatch.synchronize(admitted.operationId());
        assertThat(running.executionState()).isEqualTo("RUNNING");

        runStatus = "completed";
        AssistantDispatchService.SyncResult done = dispatch.synchronize(admitted.operationId());
        assertThat(done.executionState()).isEqualTo("SUCCEEDED");
        assertThat(repository.listAttempts(admitted.operationId())).singleElement()
            .satisfies(attempt -> assertThat(attempt.outcome()).isEqualTo("SUCCEEDED"));

        // 迟到的失败回执不得覆盖终态。
        runStatus = "failed";
        AssistantDispatchService.SyncResult late = dispatch.synchronize(admitted.operationId());
        assertThat(late.executionState()).isEqualTo("SUCCEEDED");
        assertThat(repository.findOperation(admitted.operationId()).orElseThrow().executionState())
            .isEqualTo("SUCCEEDED");
    }

    @Test
    void synchronizeBeforeDispatchIsNoOp() {
        AssistantAdmissionService.AdmissionResult admitted = admit();
        AssistantDispatchService.SyncResult result = dispatch.synchronize(admitted.operationId());
        assertThat(result.runnerStatus()).isEqualTo("NO_RUN");
        assertThat(result.executionState()).isEqualTo("PENDING");
    }

    @Test
    void secondDispatchOfSameOperationIsRejected() {
        AssistantAdmissionService.AdmissionResult admitted = admit();
        dispatch.dispatch(new AssistantDispatchService.DispatchCommand(
            tenant, 5L, admitted.requestId(), admitted.operationId(), "{\"cases\":[]}", null));

        assertThatThrownBy(() -> dispatch.dispatch(new AssistantDispatchService.DispatchCommand(
            tenant, 5L, admitted.requestId(), admitted.operationId(), "{\"cases\":[]}", null)))
            .isInstanceOf(AssistantAdmissionService.DispatchFrozenException.class)
            .hasMessageContaining("拒绝重复派发");
        assertThat(repository.listAttempts(admitted.operationId())).hasSize(1);
    }

    @Test
    void dispatchRejectsBlankPayloadBeforeTouchingRunner() {
        AssistantAdmissionService.AdmissionResult admitted = admit();
        assertThatThrownBy(() -> dispatch.dispatch(new AssistantDispatchService.DispatchCommand(
            tenant, 5L, admitted.requestId(), admitted.operationId(), "  ", null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("payload");
        assertThat(repository.listAttempts(admitted.operationId())).isEmpty();
    }

    @Test
    void failedCleanupIsNeverRewrittenAsCleaned() {
        AssistantAdmissionService.AdmissionResult admitted = admit();
        AssistantDispatchService.DispatchResult dispatched = dispatch.dispatch(new AssistantDispatchService.DispatchCommand(
            tenant, 5L, admitted.requestId(), admitted.operationId(), "{\"cases\":[]}", null));
        String resourceId = repository.listResources(admitted.operationId()).stream()
            .filter(resource -> "TEST_DATA".equals(resource.resourceKind()))
            .findFirst().orElseThrow().resourceId();

        repository.markResourceCleanupFailed(resourceId, "{\"error\":\"boom\"}");
        int cleaned = repository.markResourcesCleaned(admitted.operationId(), "{\"reason\":\"operation-terminal\"}");

        assertThat(cleaned).isEqualTo(1); // 只清 BROWSER；TEST_DATA 保持失败
        assertThat(repository.listResources(admitted.operationId()))
            .filteredOn(resource -> resource.resourceId().equals(resourceId))
            .singleElement()
            .satisfies(resource -> assertThat(resource.cleanupState()).isEqualTo("CLEANUP_FAILED"));
    }

    @Test
    void completedRunWithFailedAssertionsIsNotSuccess() {
        AssistantAdmissionService.AdmissionResult admitted = admit();
        dispatch.dispatch(new AssistantDispatchService.DispatchCommand(
            tenant, 5L, admitted.requestId(), admitted.operationId(), "{\"cases\":[]}", null));

        // Runner 的 completed 只代表「跑完了」：断言失败时 summary.failed>0，必须记 FAILED。
        runStatus = "completed";
        failedCount = 1;
        passedCount = 0;
        AssistantDispatchService.SyncResult finished = dispatch.synchronize(admitted.operationId());

        assertThat(finished.executionState()).isEqualTo("FAILED");
        assertThat(repository.listAttempts(admitted.operationId()).get(0).outcome()).isEqualTo("FAILED");
    }

    @Test
    void completedRunWithoutAnyCaseIsNotSuccess() {
        AssistantAdmissionService.AdmissionResult admitted = admit();
        dispatch.dispatch(new AssistantDispatchService.DispatchCommand(
            tenant, 5L, admitted.requestId(), admitted.operationId(), "{\"cases\":[]}", null));

        runStatus = "completed";
        failedCount = 0;
        passedCount = 0;
        AssistantDispatchService.SyncResult finished = dispatch.synchronize(admitted.operationId());

        assertThat(finished.executionState()).isEqualTo("FAILED");
    }

    @Test
    void stoppingAnUnknownRunActuallyCallsRunnerBeforeClosingTheBooks() {
        AssistantAdmissionService.AdmissionResult admitted = admit();
        AssistantDispatchService.DispatchResult dispatched = dispatch.dispatch(
            new AssistantDispatchService.DispatchCommand(
                tenant, 5L, admitted.requestId(), admitted.operationId(), "{\"cases\":[]}", null));
        repository.transitionExecution(admitted.operationId(), "DISPATCHED", "RUNNING");
        stopCalls.set(0);
        // 停止是异步的：调用前 run 仍在跑，stop 之后 Runner 才变 cancelled，平台回读到才落账。
        runStatus = "running";

        String state = dispatch.stopAndResolve(admitted.operationId(), "Runner 回读一直失败，人工停止", 9L);

        assertThat(state).isEqualTo("CANCELLED");
        assertThat(stopCalls.get()).isEqualTo(1);
        assertThat(repository.findOperation(admitted.operationId()).orElseThrow().executionState())
            .isEqualTo("CANCELLED");
        assertThat(repository.listAttempts(admitted.operationId()).get(0).outcome()).isEqualTo("CANCELLED");
        // 真正停下（回读拿到 cancelled）时也要落 run 汇总，否则报告只有终态没有计数。
        assertThat(repository.listAttempts(admitted.operationId()).get(0).runTotal()).isNotNull();
    }

    @Test
    void stopFailureDoesNotFakeACancellation() {
        AssistantAdmissionService.AdmissionResult admitted = admit();
        dispatch.dispatch(new AssistantDispatchService.DispatchCommand(
            tenant, 5L, admitted.requestId(), admitted.operationId(), "{\"cases\":[]}", null));
        repository.transitionExecution(admitted.operationId(), "DISPATCHED", "RUNNING");
        createMode = "stopFail";

        assertThatThrownBy(() -> dispatch.stopAndResolve(admitted.operationId(), "停止", 9L))
            .isInstanceOf(AssistantTaskService.ReportDecisionException.class)
            .hasMessageContaining("拒绝在账面取消");
        assertThat(repository.findOperation(admitted.operationId()).orElseThrow().executionState())
            .isEqualTo("RUNNING");
        createMode = "ok";
    }

    @Test
    void stopThatHasNotTakenEffectIsNotRecordedAsCancelled() {
        AssistantAdmissionService.AdmissionResult admitted = admit();
        dispatch.dispatch(new AssistantDispatchService.DispatchCommand(
            tenant, 5L, admitted.requestId(), admitted.operationId(), "{\"cases\":[]}", null));
        repository.transitionExecution(admitted.operationId(), "DISPATCHED", "RUNNING");
        runStatus = "running";
        createMode = "stopNoEffect";
        stopCalls.set(0);

        assertThatThrownBy(() -> dispatch.stopAndResolve(admitted.operationId(), "停止", 9L))
            .isInstanceOf(AssistantTaskService.ReportDecisionException.class)
            .hasMessageContaining("仍未确认终态");
        assertThat(repository.findOperation(admitted.operationId()).orElseThrow().executionState())
            .isEqualTo("RUNNING");
        assertThat(stopCalls.get()).isEqualTo(1);
        createMode = "ok";
        runStatus = "completed";
    }

    @Test
    void stoppingAnOperationStopsEveryUnfinishedRunFromEarlierGenerations() {
        AssistantAdmissionService.AdmissionResult admitted = admit();
        dispatch.dispatch(new AssistantDispatchService.DispatchCommand(
            tenant, 5L, admitted.requestId(), admitted.operationId(), "{\"cases\":[]}", null));
        // 造出「旧代次还在跑」的形态（等待用户 → resume → 重派发后旧 run 未收尾）。
        var firstAttempt = repository.listAttempts(admitted.operationId()).get(0);
        assertThat(firstAttempt.generation()).isEqualTo(1);
        String oldRunId = firstAttempt.runnerRunId();
        assertThat(oldRunId).isNotBlank();
        // 等待用户 → 恢复 → 重派发：新一代 run 起来，旧 run 仍未收尾。
        repository.transitionExecution(admitted.operationId(), "DISPATCHED", "WAITING_USER");
        repository.transitionExecution(admitted.operationId(), "WAITING_USER", "PENDING");
        dispatch.dispatch(new AssistantDispatchService.DispatchCommand(
            tenant, 5L, admitted.requestId(), admitted.operationId(), "{\"cases\":[]}", null));
        String newRunId = repository.listAttempts(admitted.operationId()).get(1).runnerRunId();
        assertThat(newRunId).isNotBlank();
        assertThat(newRunId).isNotEqualTo(oldRunId);
        repository.transitionExecution(admitted.operationId(), "DISPATCHED", "RUNNING");
        stopCalls.set(0);
        stoppedTargets.clear();
        runStatus = "running";
        var liveAttempts = repository.listAttempts(admitted.operationId());
        assertThat(liveAttempts).hasSize(2);
        assertThat(liveAttempts).allMatch(item -> item.endedAt() == null);

        dispatch.stopAndResolve(admitted.operationId(), "停止所有代次", 9L);

        // 每一代 run 都必须收到 stop，不能只停最新一代却把旧 run 记成终态。
        assertThat(stopCalls.get()).isEqualTo(2);
        assertThat(stoppedTargets).containsExactlyInAnyOrder(oldRunId, newRunId);
        assertThat(stoppedTargets).doesNotHaveDuplicates();
    }

    @Test
    void eachGenerationKeepsItsOwnOutcomeWhenTheNewestAlreadyFinished() {
        AssistantAdmissionService.AdmissionResult admitted = admit();
        dispatch.dispatch(new AssistantDispatchService.DispatchCommand(
            tenant, 5L, admitted.requestId(), admitted.operationId(), "{\"cases\":[]}", null));
        String oldRunId = repository.listAttempts(admitted.operationId()).get(0).runnerRunId();
        repository.transitionExecution(admitted.operationId(), "DISPATCHED", "WAITING_USER");
        repository.transitionExecution(admitted.operationId(), "WAITING_USER", "PENDING");
        dispatch.dispatch(new AssistantDispatchService.DispatchCommand(
            tenant, 5L, admitted.requestId(), admitted.operationId(), "{\"cases\":[]}", null));
        String newRunId = repository.listAttempts(admitted.operationId()).get(1).runnerRunId();
        repository.transitionExecution(admitted.operationId(), "DISPATCHED", "RUNNING");
        // 新代已自然成功、旧代还在跑：旧代被停掉后必须记它自己的 CANCELLED，不能跟着主结论写成 SUCCEEDED。
        runStatusById.put(newRunId, "completed");
        runStatusById.put(oldRunId, "running");
        stopCalls.set(0);
        stoppedTargets.clear();

        String outcome = dispatch.stopAndResolve(admitted.operationId(), "清理旧代", 9L);

        assertThat(outcome).isEqualTo("SUCCEEDED");
        var attempts = repository.listAttempts(admitted.operationId());
        assertThat(attempts.get(0).outcome()).isEqualTo("CANCELLED");
        assertThat(attempts.get(1).outcome()).isEqualTo("SUCCEEDED");
        assertThat(stoppedTargets).containsExactly(oldRunId);
    }

    @Test
    void lostGenerationIsRecordedAsUnknownInsteadOfBorrowingTheNewestOutcome() {
        AssistantAdmissionService.AdmissionResult admitted = admit();
        dispatch.dispatch(new AssistantDispatchService.DispatchCommand(
            tenant, 5L, admitted.requestId(), admitted.operationId(), "{\"cases\":[]}", null));
        String oldRunId = repository.listAttempts(admitted.operationId()).get(0).runnerRunId();
        repository.transitionExecution(admitted.operationId(), "DISPATCHED", "WAITING_USER");
        repository.transitionExecution(admitted.operationId(), "WAITING_USER", "PENDING");
        dispatch.dispatch(new AssistantDispatchService.DispatchCommand(
            tenant, 5L, admitted.requestId(), admitted.operationId(), "{\"cases\":[]}", null));
        String newRunId = repository.listAttempts(admitted.operationId()).get(1).runnerRunId();
        repository.transitionExecution(admitted.operationId(), "DISPATCHED", "RUNNING");
        lostRuns.add(oldRunId);
        runStatusById.put(newRunId, "completed");
        stopCalls.set(0);

        String outcome = dispatch.stopAndResolve(admitted.operationId(), "旧代已被回收", 9L);

        lostRuns.remove(oldRunId);
        assertThat(outcome).isEqualTo("SUCCEEDED");
        var attempts = repository.listAttempts(admitted.operationId());
        // 旧代 run 丢了：确认不了结果，只能记 UNKNOWN，绝不能跟着新代的 SUCCEEDED 走。
        assertThat(attempts.get(0).outcome()).isEqualTo("UNKNOWN");
        assertThat(attempts.get(0).runTotal()).isNull();
        assertThat(attempts.get(1).outcome()).isEqualTo("SUCCEEDED");
        // 派发期可能已有 request-not-admitted 事件，必须按内容确认 run_lost 这一条真的写了。
        assertThat(repository.listReconciliationDetails(admitted.operationId(), "MANUAL_DISPOSITION"))
            .anySatisfy(detail -> assertThat(detail).contains("run_lost").contains(oldRunId));
    }

    @Test
    void unconfirmedStopsAreNotRecordedAsTerminalForAnyGeneration() {
        AssistantAdmissionService.AdmissionResult admitted = admit();
        dispatch.dispatch(new AssistantDispatchService.DispatchCommand(
            tenant, 5L, admitted.requestId(), admitted.operationId(), "{\"cases\":[]}", null));
        repository.transitionExecution(admitted.operationId(), "DISPATCHED", "WAITING_USER");
        repository.transitionExecution(admitted.operationId(), "WAITING_USER", "PENDING");
        dispatch.dispatch(new AssistantDispatchService.DispatchCommand(
            tenant, 5L, admitted.requestId(), admitted.operationId(), "{\"cases\":[]}", null));
        repository.transitionExecution(admitted.operationId(), "DISPATCHED", "RUNNING");
        // stop 送达但没生效：回读不到终态时不能在账面写终态，更不能把一代的结论套到另一代。
        createMode = "stopNoEffect";
        runStatus = "running";
        stopCalls.set(0);

        assertThatThrownBy(() -> dispatch.stopAndResolve(admitted.operationId(), "停止", 9L))
            .isInstanceOf(AssistantTaskService.ReportDecisionException.class)
            .hasMessageContaining("仍未确认终态");

        createMode = "ok";
        runStatus = "completed";
        assertThat(repository.findOperation(admitted.operationId()).orElseThrow().executionState())
            .isEqualTo("RUNNING");
        assertThat(repository.listAttempts(admitted.operationId()))
            .allMatch(attempt -> attempt.endedAt() == null && "RUNNING".equals(attempt.outcome()));
        assertThat(stopCalls.get()).isEqualTo(2);
    }
}
