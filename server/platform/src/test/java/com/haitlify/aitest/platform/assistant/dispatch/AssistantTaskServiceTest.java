package com.haitlify.aitest.platform.assistant.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.haitlify.aitest.platform.assistant.compile.AssistantScriptCompiler;
import com.haitlify.aitest.platform.assistant.draft.AssistantDraftService;
import com.haitlify.aitest.platform.assistant.ledger.AssistantLedgerRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A1 窄链路：草稿 → 编译 → 受理 → 派发 → 终态 → 报告接受。
 * Runner 用本地桩（提交返回 runId，查询返回 completed），验证账本三轴状态与幂等重放。
 */
@SpringBootTest(properties = "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=")
@ActiveProfiles("test")
class AssistantTaskServiceTest {

    private static final HttpServer STUB = startStub();

    @DynamicPropertySource
    static void runnerProps(DynamicPropertyRegistry registry) {
        registry.add("app.web-automation.runner.base-url", () -> "http://127.0.0.1:" + STUB.getAddress().getPort());
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/runs", AssistantTaskServiceTest::handle);
            server.start();
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException("桩 Runner 启动失败", ex);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        boolean isCreate = "POST".equals(exchange.getRequestMethod()) && "/runs".equals(path);
        String body = isCreate
            ? "{\"runId\":\"run-task-1\",\"status\":\"queued\"}"
            : "{\"runId\":\"" + path.substring("/runs/".length())
                + "\",\"status\":\"completed\",\"summary\":{\"total\":1,\"passed\":1,\"failed\":0,\"error\":0}}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("content-type", "application/json");
        exchange.sendResponseHeaders(isCreate ? 202 : 200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Autowired
    private AssistantTaskService tasks;

    @Autowired
    private AssistantLedgerRepository repository;

    @Autowired
    private AssistantDraftService drafts;

    @Autowired
    private com.haitlify.aitest.platform.assistant.report.AssistantReportService reports;

    @Autowired
    private AssistantLedgerRepository ledger;

    private long tenant;

    /** 报告接受前必须把资源收尾：先按真实入口逐个清理（带证据）。 */
    private void cleanResources(String operationId) {
        ledger.listResources(operationId).stream()
            .filter(resource -> "ALLOCATED".equals(resource.cleanupState())
                || "CLEANING".equals(resource.cleanupState()))
            .forEach(resource -> tasks.markResourceCleaned(operationId, resource.resourceId(),
                "{\"readBack\":\"absent\"}", 9L));
    }

    @BeforeEach
    void setUp() {
        tenant = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1_000_000L;
    }

    private AssistantScriptCompiler.CaseSpec spec() {
        return new AssistantScriptCompiler.CaseSpec("case-a1", "A1 只读夹具", "http://127.0.0.1:19399/fixture.html",
            List.of(
                new AssistantScriptCompiler.Step("s1", "goto", "/fixture.html", null),
                new AssistantScriptCompiler.Step("s2", "assertText", "#summary", "共 3 条")));
    }

    private String confirmedDraft() {
        AssistantDraftService.DraftView draft = drafts.create(new AssistantDraftService.CreateDraftCommand(
            tenant, 1L, 9L, "A0 §5.5 候选 A 的夹具副本", "只读探索夹具需求（e2e 测试）", spec(), "web.case.execute", "project:1/case-a1", "rev-1", null));
        return drafts.confirm(draft.draftId(), tenant, 9L).draftId();
    }

    private AssistantTaskService.TaskRequest request(String requestId) {
        return new AssistantTaskService.TaskRequest(tenant, 9L, 1L, requestId, confirmedDraft(),
            "web.case.execute", "project:1/case-a1", "rev-1", spec(), null);
    }

    @Test
    void runsWholeChainAndReplayDoesNotRedispatch() {
        String requestId = UUID.randomUUID().toString();
        AssistantTaskService.TaskResult first = tasks.run(request(requestId));

        assertThat(first.created()).isTrue();
        assertThat(first.runId()).isEqualTo("run-task-1");
        assertThat(first.stepCount()).isEqualTo(2);
        assertThat(first.digest()).hasSize(64);

        AssistantDispatchService.SyncResult done = tasks.await(first.operationId(), Duration.ofSeconds(5));
        assertThat(done.executionState()).isEqualTo("SUCCEEDED");
        assertThat(repository.findOperation(first.operationId()).orElseThrow().reportState()).isEqualTo("NONE");

        cleanResources(first.operationId());
        reports.generate(first.operationId(), 9L);
        tasks.decideReport(first.operationId(), reports.latest(first.operationId()).reportId(), true, 9L);
        assertThat(repository.findOperation(first.operationId()).orElseThrow().reportState()).isEqualTo("ACCEPTED");

        // 同 requestId 重放：不再派发（attempts 仍只有 1 条），返回既有 operation。
        AssistantTaskService.TaskResult replay = tasks.run(request(requestId));
        assertThat(replay.created()).isFalse();
        assertThat(replay.operationId()).isEqualTo(first.operationId());
        assertThat(repository.listAttempts(first.operationId())).hasSize(1);
    }

    @Test
    void sameRequestIdWithDifferentSpecIsRejected() {
        String requestId = UUID.randomUUID().toString();
        tasks.run(request(requestId));

        AssistantScriptCompiler.CaseSpec changed = new AssistantScriptCompiler.CaseSpec("case-a1", "A1 只读夹具",
            "http://127.0.0.1:19399/fixture.html",
            List.of(new AssistantScriptCompiler.Step("s1", "goto", "/fixture.html", null),
                new AssistantScriptCompiler.Step("s2", "assertText", "#summary", "共 4 条")));

        // 为变更后的结构单独确认一份草稿，才能走到「同 requestId 不同输入」的幂等判定。
        String changedDraft = drafts.confirm(drafts.create(new AssistantDraftService.CreateDraftCommand(
            tenant, 1L, 9L, "A0 §5.5 候选 A 的夹具副本", "只读探索夹具需求（e2e 测试）", changed,
            "web.case.execute", "project:1/case-a1", "rev-1", null)).draftId(), tenant, 9L).draftId();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tasks.run(new AssistantTaskService.TaskRequest(
                tenant, 9L, 1L, requestId, changedDraft, "web.case.execute", "project:1/case-a1", "rev-1", changed, null)))
            .isInstanceOf(com.haitlify.aitest.platform.assistant.ledger.AssistantAdmissionService
                .IdempotencyConflictException.class);
    }

    @Test
    void reportDecisionDoesNotChangeExecutionFacts() {
        AssistantTaskService.TaskResult result = tasks.run(request(UUID.randomUUID().toString()));
        tasks.await(result.operationId(), Duration.ofSeconds(5));
        reports.generate(result.operationId(), 9L);
        cleanResources(result.operationId());
        tasks.decideReport(result.operationId(), reports.latest(result.operationId()).reportId(), false, 9L);

        AssistantLedgerRepository.OperationRecord operation = repository.findOperation(result.operationId()).orElseThrow();
        assertThat(operation.reportState()).isEqualTo("REJECTED");
        assertThat(operation.executionState()).isEqualTo("SUCCEEDED");
    }

    @Test
    void reportCannotBeAcceptedBeforeTerminalState() {
        String requestId = UUID.randomUUID().toString();
        AssistantTaskService.TaskResult started = tasks.run(request(requestId));
        // 桩 Runner 返回 completed，但这里还没 sync，执行事实仍是 DISPATCHED：
        // 报告是执行事实的快照，未到终态连生成都不允许（比「先生成再拒决策」更严）。
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> reports.generate(started.operationId(), 9L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("终态");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> tasks.decideReport(started.operationId(), "report-missing", true, 9L))
            .isInstanceOf(AssistantTaskService.ReportDecisionException.class)
            .hasMessageContaining("终态");
    }

    @Test
    void resourceCannotBeMarkedCleanedWhileStillRunning() {
        AssistantTaskService.TaskResult started = tasks.run(request(UUID.randomUUID().toString()));
        String resourceId = ledger.listResources(started.operationId()).get(0).resourceId();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> tasks.markResourceCleaned(started.operationId(), resourceId, "{\"readBack\":\"absent\"}", 9L))
            .isInstanceOf(AssistantTaskService.ReportDecisionException.class)
            .hasMessageContaining("尚未结束");
    }

    @Test
    void resourceCannotBeDeclaredUnrecoverableWhileRunning() {
        AssistantTaskService.TaskResult started = tasks.run(request(UUID.randomUUID().toString()));
        String resourceId = ledger.listResources(started.operationId()).get(0).resourceId();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> tasks.markResourceUnrecoverable(started.operationId(), resourceId, "会话丢了", 9L))
            .isInstanceOf(AssistantTaskService.ReportDecisionException.class)
            .hasMessageContaining("尚未结束");
    }

    @Test
    void rejectionIsAllowedEvenWhenResourcesAreStillOpen() {
        AssistantTaskService.TaskResult result = tasks.run(request(UUID.randomUUID().toString()));
        tasks.await(result.operationId(), Duration.ofSeconds(5));
        com.haitlify.aitest.platform.assistant.report.AssistantReportService.ReportView report = reports.generate(result.operationId(), 9L);

        // 驳回是审核意见：资源还没收尾也要能驳回（三轴独立）。
        tasks.decideReport(result.operationId(), report.reportId(), false, 9L);
        assertThat(ledger.findOperation(result.operationId()).orElseThrow().reportState()).isEqualTo("REJECTED");
    }

    @Test
    void manualResolutionIsRefusedForRunsThatStillHaveARunId() {
        AssistantTaskService.TaskResult started = tasks.run(request(UUID.randomUUID().toString()));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> tasks.resolveStalled(started.operationId(), "FAILED", "人工判定", 9L))
            .isInstanceOf(AssistantTaskService.ReportDecisionException.class)
            .hasMessageContaining("PENDING");
    }

    @Test
    void manualResolutionCannotClaimSuccessWithoutRunnerEvidence() {
        AssistantTaskService.TaskResult started = tasks.run(request(UUID.randomUUID().toString()));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> tasks.resolveStalled(started.operationId(), "SUCCEEDED", "我觉得过了", 9L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("FAILED");
    }
}
