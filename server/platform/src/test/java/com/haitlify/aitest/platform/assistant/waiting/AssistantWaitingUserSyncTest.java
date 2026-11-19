package com.haitlify.aitest.platform.assistant.waiting;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.haitlify.aitest.platform.assistant.dispatch.AssistantDispatchService;
import com.haitlify.aitest.platform.assistant.ledger.AssistantAdmissionService;
import com.haitlify.aitest.platform.assistant.ledger.AssistantLedgerRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 从等待恢复后不能续跑旧代次：`/sync` 读到旧 attempt 的 runId 时，
 * 不能走「崩溃修复」把 PENDING 推回 DISPATCHED/RUNNING（复核 D9）。
 */
@SpringBootTest(properties = "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=")
@ActiveProfiles("test")
class AssistantWaitingUserSyncTest {

    private static final HttpServer STUB = startStub();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("app.web-automation.runner.base-url", () -> "http://127.0.0.1:" + STUB.getAddress().getPort());
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/runs", AssistantWaitingUserSyncTest::handle);
            server.start();
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException("桩 Runner 启动失败", ex);
        }
    }

    /** 即使 Runner 说「已完成」，恢复后的 PENDING 也不能被同步推进。 */
    private static void handle(HttpExchange exchange) throws IOException {
        String body = "{\"status\":\"completed\",\"summary\":{\"total\":1,\"passed\":1,\"failed\":0,\"error\":0}}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("content-type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Autowired
    private AssistantAdmissionService admission;

    @Autowired
    private AssistantWaitingUserService waiting;

    @Autowired
    private AssistantLedgerRepository ledger;

    @Autowired
    private AssistantDispatchService dispatch;

    @Autowired
    private tools.jackson.databind.ObjectMapper objectMapper;

    private String singleReconciliationDetail(String operationId, String eventType) {
        return ledger.listReconciliationDetails(operationId, eventType).get(0);
    }

    @Test
    void resumedOperationAwaitsRedispatchInsteadOfRevivingTheOldRun() {
        long tenant = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1_000_000L;
        String operationId = admission.admit(new AssistantAdmissionService.AdmissionCommand(tenant, 5L, 1L,
            UUID.randomUUID().toString(), "d".repeat(64), "web.case.execute",
            "project:1/waiting-sync", "rev-1", null)).operationId();
        // 造出「已派发、有 runId、未收尾」的历史代次。
        ledger.transitionExecution(operationId, "PENDING", "DISPATCHED");
        String attemptId = UUID.randomUUID().toString();
        ledger.insertAttempt(attemptId, operationId, 1, Instant.now());
        ledger.bindAttemptRun(attemptId, "run-old-generation");
        ledger.transitionExecution(operationId, "DISPATCHED", "WAITING_USER");
        waiting.resume(operationId);

        AssistantDispatchService.SyncResult result = dispatch.synchronize(operationId);

        assertThat(result.runnerStatus()).isEqualTo("AWAITING_REDISPATCH");
        assertThat(result.executionState()).isEqualTo("PENDING");
        assertThat(ledger.findOperation(operationId).orElseThrow().executionState()).isEqualTo("PENDING");
    }

    @Test
    void stoppingWaitingRecordsTheRunsRealOutcomeWhenItAlreadyFinished() {
        long tenant = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1_000_000L;
        String operationId = admission.admit(new AssistantAdmissionService.AdmissionCommand(tenant, 5L, 1L,
            UUID.randomUUID().toString(), "f".repeat(64), "web.case.execute",
            "project:1/waiting-sync", "rev-1", null)).operationId();
        ledger.transitionExecution(operationId, "PENDING", "DISPATCHED");
        String attemptId = UUID.randomUUID().toString();
        ledger.insertAttempt(attemptId, operationId, 1, Instant.now());
        ledger.bindAttemptRun(attemptId, "run-finished-first");
        waiting.requireUser(operationId, "等待人工登录", Duration.ofMinutes(10));

        // 桩 Runner 返回 completed：停请求没赶上，账面必须记 SUCCEEDED，而不是硬写 CANCELLED。
        String outcome = dispatch.stopAndResolve(operationId, "用户停止等待", 5L);

        assertThat(outcome).isEqualTo("SUCCEEDED");
        assertThat(ledger.findOperation(operationId).orElseThrow().executionState()).isEqualTo("SUCCEEDED");
        // 终态不再携带等待信息。
        assertThat(ledger.waitingInfo(operationId).orElseThrow().deadlineAt()).isNull();
        // 停止路径也要落 run 汇总，否则报告 attempts[].summary 为空、diagnostics 全 0（复核 P3-1）。
        var attempt = ledger.listAttempts(operationId).stream()
            .filter(item -> "run-finished-first".equals(item.runnerRunId())).findFirst().orElseThrow();
        assertThat(attempt.runTotal()).isNotNull();
        assertThat(attempt.runPassed()).isEqualTo(1);
        // 原等待原因保留在人工处置事件里（复核 P3-2），且 detail 必须是可解析的 JSON。
        assertThat(ledger.countReconciliation(operationId, "MANUAL_RESOLUTION")).isEqualTo(1);
        var detail = objectMapper.readTree(singleReconciliationDetail(operationId, "MANUAL_RESOLUTION"));
        assertThat(detail.path("outcome").asText()).isEqualTo("SUCCEEDED");
        assertThat(detail.path("waitingReason").asText()).contains("等待人工登录");
        // 终态迁移不能再清掉 waiting_reason（改回清空会让本行变红）。
        assertThat(waiting.describe(operationId).reason()).contains("等待人工登录");
    }

    @Test
    void describeKeepsTheReasonButHidesStaleTimingForTerminalOperations() {
        long tenant = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1_000_000L;
        String operationId = admission.admit(new AssistantAdmissionService.AdmissionCommand(tenant, 5L, 1L,
            UUID.randomUUID().toString(), "a".repeat(64), "web.case.execute",
            "project:1/waiting-sync", "rev-1", null)).operationId();
        waiting.requireUser(operationId, "等待人工登录", Duration.ofMinutes(10));
        String live = waiting.describe(operationId).deadlineAt() == null ? "missing" : "present";

        waiting.cancel(operationId, "用户放弃");

        AssistantWaitingUserService.WaitingView view = waiting.describe(operationId);
        assertThat(live).isEqualTo("present");
        assertThat(view.executionState()).isEqualTo("CANCELLED");
        // 原因保留（审计），时间字段隐藏（已失效）。
        assertThat(view.reason()).contains("等待人工登录");
        assertThat(view.deadlineAt()).isNull();
        assertThat(view.requestedAt()).isNull();
    }

    @Test
    void waitingUserNeedsAnExplicitReasonWithBoundedTimeout() {
        long tenant = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1_000_000L;
        String operationId = admission.admit(new AssistantAdmissionService.AdmissionCommand(tenant, 5L, 1L,
            UUID.randomUUID().toString(), "e".repeat(64), "web.case.execute",
            "project:1/waiting-sync", "rev-1", null)).operationId();

        waiting.requireUser(operationId, "等待人工登录", Duration.ofMinutes(30));
        assertThat(ledger.findOperation(operationId).orElseThrow().executionState()).isEqualTo("WAITING_USER");
    }
}
