package com.haitlify.aitest.platform.assistant.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.haitlify.aitest.platform.assistant.compile.AssistantScriptCompiler;
import com.haitlify.aitest.platform.assistant.dispatch.AssistantDispatchService;
import com.haitlify.aitest.platform.assistant.dispatch.AssistantTaskService;
import com.haitlify.aitest.platform.assistant.draft.AssistantDraftService;
import com.haitlify.aitest.platform.assistant.ledger.AssistantLedgerRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** 报告：事实快照 + 追加修订；接受/驳回只改 report_state，不改执行事实。 */
@SpringBootTest(properties = "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=")
@ActiveProfiles("test")
class AssistantReportServiceTest {

    private static final HttpServer STUB = startStub();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("app.web-automation.runner.base-url", () -> "http://127.0.0.1:" + STUB.getAddress().getPort());
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/runs", AssistantReportServiceTest::handle);
            server.start();
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException("桩 Runner 启动失败", ex);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        boolean create = "POST".equals(exchange.getRequestMethod());
        String body = create ? "{\"runId\":\"run-report-1\",\"status\":\"queued\"}"
            : "{\"status\":\"completed\",\"summary\":{\"total\":1,\"passed\":1,\"failed\":0,\"error\":0}}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("content-type", "application/json");
        exchange.sendResponseHeaders(create ? 202 : 200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Autowired
    private AssistantTaskService tasks;

    @Autowired
    private AssistantDraftService drafts;

    @Autowired
    private AssistantReportService reports;

    @Autowired
    private AssistantLedgerRepository ledger;

    @Autowired
    private AssistantReportRepository reportsRepository;

    /** 报告接受前必须把资源收尾：按真实入口逐个清理（带证据）。 */
    private void cleanResources(String operationId) {
        ledger.listResources(operationId).stream()
            .filter(resource -> "ALLOCATED".equals(resource.cleanupState())
                || "CLEANING".equals(resource.cleanupState()))
            .forEach(resource -> tasks.markResourceCleaned(operationId, resource.resourceId(),
                "{\"readBack\":\"absent\"}", 9L));
    }

    @Test
    void generatesRevisionedReportsWithoutTouchingExecutionFacts() {
        long tenant = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1_000_000L;
        AssistantScriptCompiler.CaseSpec spec = new AssistantScriptCompiler.CaseSpec("case-report", "报告夹具",
            "http://127.0.0.1:19399/f.html",
            List.of(new AssistantScriptCompiler.Step("s1", "goto", "/f.html", null),
                new AssistantScriptCompiler.Step("s2", "assertVisible", "#summary", null)));
        String draftId = drafts.confirm(drafts.create(new AssistantDraftService.CreateDraftCommand(
            tenant, 1L, 5L, "报告测试需求", "夹具", spec, "web.case.execute", "project:1/case-report", "rev-1", null)).draftId(), tenant, 5L).draftId();
        AssistantTaskService.TaskResult result = tasks.run(new AssistantTaskService.TaskRequest(tenant, 5L, 1L,
            UUID.randomUUID().toString(), draftId, "web.case.execute", "project:1/case-report", "rev-1", spec, null));
        AssistantDispatchService.SyncResult done = tasks.await(result.operationId(), Duration.ofSeconds(5));
        assertThat(done.executionState()).isEqualTo("SUCCEEDED");

        // 报告接受要求正文与账本一致：先收尾资源，再生成报告。
        cleanResources(result.operationId());
        AssistantReportService.ReportView first = reports.generate(result.operationId(), 5L);
        assertThat(first.revision()).isEqualTo(1);
        assertThat(first.bodyJson()).contains("\"executionState\":\"SUCCEEDED\"")
            .contains("\"coverage\"").contains("\"revisionCount\":1").contains("\"assertions\":1")
            .contains("run-report-1").contains("BROWSER").contains("TEST_DATA");

        AssistantReportService.ReportView second = reports.generate(result.operationId(), 5L);
        assertThat(second.revision()).isEqualTo(2);
        assertThat(reports.latest(result.operationId()).revision()).isEqualTo(2);

        tasks.decideReport(result.operationId(), reports.latest(result.operationId()).reportId(), true, 9L);
        // 接受是终局：不允许再生成新修订把 ACCEPTED 打回 AWAITING。
        assertThatThrownBy(() -> reports.generate(result.operationId(), 5L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("已接受");
        assertThat(ledger.findOperation(result.operationId()).orElseThrow().executionState()).isEqualTo("SUCCEEDED");
    }

    @Test
    void nonTerminalExecutionReportsAStateConflictTypeSoHttpCanMap409() {
        long tenant = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1_000_000L;
        String operationId = ledger.createOperation(UUID.randomUUID().toString(), tenant, 1L, "web.case.execute",
            "project:1/case-report", "rev-1", "a".repeat(64), "b".repeat(64)).record().operationId();

        // 业务状态冲突必须是专用类型：控制器据此映射 409，基础设施异常仍为 500（复核 N5/P2）。
        assertThatThrownBy(() -> reports.generate(operationId, 5L))
            .isInstanceOf(com.haitlify.aitest.platform.assistant.ledger.AssistantStateConflictException.class)
            .hasMessageContaining("终态");
    }

    @Test
    void missingReportIsReported() {
        assertThatThrownBy(() -> reports.latest(UUID.randomUUID().toString()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("尚无报告");
    }

    @Test
    void decisionIsPersistedOnTheAcceptedRevision() {
        long tenant = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1_000_000L;
        AssistantScriptCompiler.CaseSpec spec = new AssistantScriptCompiler.CaseSpec("case-decide", "决策夹具",
            "http://127.0.0.1:19399/f.html",
            List.of(new AssistantScriptCompiler.Step("s1", "goto", "/f.html", null),
                new AssistantScriptCompiler.Step("s2", "assertVisible", "#summary", null)));
        String draftId = drafts.confirm(drafts.create(new AssistantDraftService.CreateDraftCommand(
            tenant, 1L, 5L, "决策测试需求", "夹具", spec,
            "web.case.execute", "project:1/case-report", "rev-1", null)).draftId(), tenant, 5L).draftId();
        AssistantTaskService.TaskResult result = tasks.run(new AssistantTaskService.TaskRequest(tenant, 5L, 1L,
            UUID.randomUUID().toString(), draftId, "web.case.execute", "project:1/case-report", "rev-1", spec, null));
        tasks.await(result.operationId(), Duration.ofSeconds(5));
        // 报告是「清理后的事实快照」：先收尾，再生成。
        cleanResources(result.operationId());
        AssistantReportService.ReportView report = reports.generate(result.operationId(), 5L);

        tasks.decideReport(result.operationId(), report.reportId(), true, 9L);

        AssistantReportRepository.ReportRow decided = reportsRepository
            .find(result.operationId(), report.revision()).orElseThrow();
        assertThat(decided.reportId()).isEqualTo(report.reportId());
        assertThat(decided.decision()).isEqualTo("ACCEPTED");
        assertThat(decided.decidedAt()).isNotNull();
        // 正文不写决策态：决策只在 operation/report 轴，避免「已接受但正文写着旧状态」。
        assertThat(decided.bodyJson()).doesNotContain("reportState");
        assertThat(ledger.findOperation(result.operationId()).orElseThrow().reportState()).isEqualTo("ACCEPTED");
    }

    @Test
    void staleRevisionCannotBeDecidedAndSecondDecisionIsRejected() {
        long tenant = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1_000_000L;
        AssistantScriptCompiler.CaseSpec spec = new AssistantScriptCompiler.CaseSpec("case-stale", "过期修订",
            "http://127.0.0.1:19399/f.html",
            List.of(new AssistantScriptCompiler.Step("s1", "goto", "/f.html", null),
                new AssistantScriptCompiler.Step("s2", "assertVisible", "#summary", null)));
        String draftId = drafts.confirm(drafts.create(new AssistantDraftService.CreateDraftCommand(
            tenant, 1L, 5L, "过期修订需求", "夹具", spec,
            "web.case.execute", "project:1/case-report", "rev-1", null)).draftId(), tenant, 5L).draftId();
        AssistantTaskService.TaskResult result = tasks.run(new AssistantTaskService.TaskRequest(tenant, 5L, 1L,
            UUID.randomUUID().toString(), draftId, "web.case.execute", "project:1/case-report", "rev-1", spec, null));
        tasks.await(result.operationId(), Duration.ofSeconds(5));
        cleanResources(result.operationId());

        AssistantReportService.ReportView first = reports.generate(result.operationId(), 5L);
        AssistantReportService.ReportView second = reports.generate(result.operationId(), 5L);
        assertThat(second.revision()).isEqualTo(first.revision() + 1);
        // 生成新修订后决策状态回到 AWAITING（新修订需要重新人工确认）。
        assertThat(ledger.findOperation(result.operationId()).orElseThrow().reportState()).isEqualTo("AWAITING");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> tasks.decideReport(result.operationId(), first.reportId(), true, 5L))
            .isInstanceOf(AssistantTaskService.ReportDecisionException.class)
            .hasMessageContaining("新修订");

        cleanResources(result.operationId());

        tasks.decideReport(result.operationId(), second.reportId(), true, 5L);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> tasks.decideReport(result.operationId(), second.reportId(), false, 5L))
            .isInstanceOf(AssistantTaskService.ReportDecisionException.class);
    }

    @Test
    void unrecoverableResourceIsTheExplicitExitForManualRequired() {
        long tenant = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1_000_000L;
        AssistantScriptCompiler.CaseSpec spec = new AssistantScriptCompiler.CaseSpec("case-unrec", "不可恢复夹具",
            "http://127.0.0.1:19399/f.html",
            List.of(new AssistantScriptCompiler.Step("s1", "goto", "/f.html", null),
                new AssistantScriptCompiler.Step("s2", "assertVisible", "#summary", null)));
        String draftId = drafts.confirm(drafts.create(new AssistantDraftService.CreateDraftCommand(
            tenant, 1L, 5L, "不可恢复需求", "夹具", spec, "web.case.execute",
            "project:1/case-unrec", "rev-1", null)).draftId(), tenant, 5L).draftId();
        AssistantTaskService.TaskResult result = tasks.run(new AssistantTaskService.TaskRequest(tenant, 5L, 1L,
            UUID.randomUUID().toString(), draftId, "web.case.execute", "project:1/case-unrec", "rev-1", spec, null));
        tasks.await(result.operationId(), Duration.ofSeconds(5));

        // 超时后资源会被对账标成 MANUAL_REQUIRED；人工确认救不回来时必须显式判 UNRECOVERABLE 才能收尾。
        ledger.listResources(result.operationId())
            .forEach(resource -> ledger.markResourceManualRequired(resource.resourceId(), "{\"reason\":\"timeout\"}"));
        AssistantReportService.ReportView report = reports.generate(result.operationId(), 5L);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> tasks.decideReport(result.operationId(), report.reportId(), true, 5L))
            .isInstanceOf(AssistantTaskService.ReportDecisionException.class)
            .hasMessageContaining("未清理");

        ledger.listResources(result.operationId()).forEach(resource ->
            tasks.markResourceUnrecoverable(result.operationId(), resource.resourceId(), "浏览器会话已丢失，无法自动回收", 9L));
        // 资源状态变了：报告要重新生成，接受时才会与账本一致。
        AssistantReportService.ReportView settled = reports.generate(result.operationId(), 5L);
        tasks.decideReport(result.operationId(), settled.reportId(), true, 5L);
        assertThat(ledger.findOperation(result.operationId()).orElseThrow().reportState()).isEqualTo("ACCEPTED");
    }

    @Test
    void rejectedRevisionCanBeSupersededByANewOne() {
        long tenant = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1_000_000L;
        AssistantScriptCompiler.CaseSpec spec = new AssistantScriptCompiler.CaseSpec("case-reject", "驳回夹具",
            "http://127.0.0.1:19399/f.html",
            List.of(new AssistantScriptCompiler.Step("s1", "goto", "/f.html", null),
                new AssistantScriptCompiler.Step("s2", "assertVisible", "#summary", null)));
        String draftId = drafts.confirm(drafts.create(new AssistantDraftService.CreateDraftCommand(
            tenant, 1L, 5L, "驳回需求", "夹具", spec, "web.case.execute",
            "project:1/case-reject", "rev-1", null)).draftId(), tenant, 5L).draftId();
        AssistantTaskService.TaskResult result = tasks.run(new AssistantTaskService.TaskRequest(tenant, 5L, 1L,
            UUID.randomUUID().toString(), draftId, "web.case.execute", "project:1/case-reject", "rev-1", spec, null));
        tasks.await(result.operationId(), Duration.ofSeconds(5));
        cleanResources(result.operationId());

        AssistantReportService.ReportView r1 = reports.generate(result.operationId(), 5L);
        tasks.decideReport(result.operationId(), r1.reportId(), false, 5L);
        assertThat(ledger.findOperation(result.operationId()).orElseThrow().reportState()).isEqualTo("REJECTED");

        // 驳回不是死路：可以补一份新修订，再重新决策。
        AssistantReportService.ReportView r2 = reports.generate(result.operationId(), 5L);
        assertThat(r2.revision()).isEqualTo(r1.revision() + 1);
        assertThat(ledger.findOperation(result.operationId()).orElseThrow().reportState()).isEqualTo("AWAITING");
        tasks.decideReport(result.operationId(), r2.reportId(), true, 5L);
        assertThat(ledger.findOperation(result.operationId()).orElseThrow().reportState()).isEqualTo("ACCEPTED");
    }

    @Test
    void diagnosticsListFailedAttemptsWithoutGuessingCauses() {
        AssistantLedgerRepository.AttemptRecord ok = new AssistantLedgerRepository.AttemptRecord(
            "a-ok", "op-1", 1, null, null, "run-ok", null, null, "SUCCEEDED", 4, 4, 0, 0);
        AssistantLedgerRepository.AttemptRecord bad = new AssistantLedgerRepository.AttemptRecord(
            "a-bad", "op-1", 2, null, null, "run-bad", null, null, "FAILED", 6, 3, 2, 1);

        Map<String, Object> diagnostics = AssistantReportService.buildDiagnostics("FAILED", List.of(ok, bad));

        assertThat(diagnostics.get("executionState")).isEqualTo("FAILED");
        assertThat(diagnostics.get("stepTotals")).isEqualTo(Map.of("passed", 7, "failed", 2, "error", 1));
        assertThat((List<?>) diagnostics.get("failedAttempts")).hasSize(1);
        assertThat(diagnostics.toString()).contains("run-bad").doesNotContain("run-ok");
        assertThat(diagnostics.get("note").toString()).doesNotContain("可能");
    }

    @Test
    void diagnosticsFlagUnresolvedGenerationsInsteadOfReadingAsAllGreen() {
        AssistantLedgerRepository.AttemptRecord lost = new AssistantLedgerRepository.AttemptRecord(
            "a-lost", "op-2", 1, null, null, "run-lost", null, null, "UNKNOWN", null, null, null, null);
        AssistantLedgerRepository.AttemptRecord ok = new AssistantLedgerRepository.AttemptRecord(
            "a-ok", "op-2", 2, null, null, "run-ok", null, null, "SUCCEEDED", 3, 3, 0, 0);

        Map<String, Object> diagnostics = AssistantReportService.buildDiagnostics("SUCCEEDED", List.of(lost, ok));

        // run 丢失的代次既不算通过也不算失败，诊断必须单列并改口径，不能写「无失败步骤」。
        assertThat(diagnostics.get("note").toString()).contains("UNKNOWN").doesNotContain("无失败步骤");
        assertThat((List<?>) diagnostics.get("unknownAttempts")).hasSize(1);
        assertThat(diagnostics.toString()).contains("run-lost");
        assertThat(diagnostics.get("stepTotals")).isEqualTo(Map.of("passed", 3, "failed", 0, "error", 0));
    }
}
