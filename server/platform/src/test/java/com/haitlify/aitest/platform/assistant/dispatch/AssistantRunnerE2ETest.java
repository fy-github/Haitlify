package com.haitlify.aitest.platform.assistant.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.haitlify.aitest.platform.assistant.compile.AssistantScriptCompiler;
import com.haitlify.aitest.platform.assistant.draft.AssistantDraftService;
import com.haitlify.aitest.platform.assistant.ledger.AssistantLedgerRepository;

/**
 * A1 真实实跑：平台链路（受理 → 编译 → 派发 → 回填）打到**真实 web-runner**（隔离栈），
 * 由 Playwright 跑仓库夹具。默认跳过，需显式开启（避免 CI 依赖容器）：
 *
 * <pre>
 *   A1_RUNNER_E2E=true A1_RUNNER_BASE_URL=http://127.0.0.1:19324 \
 *   ./mvnw -o -Dtest=AssistantRunnerE2ETest test
 * </pre>
 *
 * 断言只认 Runner 的终态与账本回填，不认「提交成功」。
 */
@SpringBootTest(properties = "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=")
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "A1_RUNNER_E2E", matches = "true")
class AssistantRunnerE2ETest {

    @DynamicPropertySource
    static void runnerProps(DynamicPropertyRegistry registry) {
        String baseUrl = System.getenv().getOrDefault("A1_RUNNER_BASE_URL", "http://127.0.0.1:19324");
        registry.add("app.web-automation.runner.base-url", () -> baseUrl);
    }

    @Autowired
    private AssistantTaskService tasks;

    @Autowired
    private AssistantLedgerRepository repository;

    @Autowired
    private AssistantDraftService drafts;

    @Test
    void executesFixtureCaseThroughRealRunner() throws Exception {
        long tenant = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1_000_000L;
        AssistantScriptCompiler.CaseSpec spec = new AssistantScriptCompiler.CaseSpec(
            "A1-E2E-READONLY", "只读探索夹具（加固栈）", "http://127.0.0.1:9323/fixtures/icc-readonly-exploration.html",
            List.of(
                new AssistantScriptCompiler.Step("s1", "goto", "/fixtures/icc-readonly-exploration.html", null),
                new AssistantScriptCompiler.Step("s2", "input", "#menu-search", "威胁情报"),
                new AssistantScriptCompiler.Step("s3", "click", "#threat-menu", null),
                new AssistantScriptCompiler.Step("s4", "assertVisible", "#target-overlay", null),
                new AssistantScriptCompiler.Step("s5", "click", "#dismiss-overlay", null),
                new AssistantScriptCompiler.Step("s6", "click", "#readonly-tab", null),
                new AssistantScriptCompiler.Step("s7", "assertVisible", "text=设备 A · 只读 · 在线", null),
                new AssistantScriptCompiler.Step("s8", "assertText", "[data-testid=\"mutation-counter\"]", "0")));

        String draftId = drafts.confirm(drafts.create(new AssistantDraftService.CreateDraftCommand(
            tenant, 1L, 11L, "A0 §5.9 云地勘只读夹具", "只读探索夹具（真实 Runner）", spec, "web.case.execute", "project:1/A1-E2E-READONLY", "rev-1", null)).draftId(), tenant, 11L).draftId();
        AssistantTaskService.TaskResult started = tasks.run(new AssistantTaskService.TaskRequest(tenant, 11L, 1L,
            UUID.randomUUID().toString(), draftId, "web.case.execute", "project:1/A1-E2E-READONLY", "rev-1", spec, null));
        assertThat(started.created()).isTrue();
        assertThat(started.runId()).isNotBlank();

        AssistantDispatchService.SyncResult finished = tasks.await(started.operationId(), Duration.ofMinutes(3));
        String evidenceJson = "{\"operationId\":\"" + started.operationId() + "\",\"runId\":\""
            + started.runId() + "\",\"executionState\":\"" + finished.executionState() + "\",\"digest\":\""
            + started.digest() + "\"}";
        Path evidence = Path.of(System.getenv().getOrDefault("A1_E2E_OUT",
            System.getProperty("java.io.tmpdir") + "/a1-e2e-result.json"));
        Files.writeString(evidence, evidenceJson);
        System.out.println("A1_E2E_RESULT=" + evidenceJson);

        assertThat(finished.executionState()).isEqualTo("SUCCEEDED");
        AssistantLedgerRepository.AttemptRecord attempt = repository.listAttempts(started.operationId()).get(0);
        assertThat(attempt.runnerRunId()).isEqualTo(started.runId());
        assertThat(attempt.outcome()).isEqualTo("SUCCEEDED");
        assertThat(attempt.endedAt()).isNotNull();
    }
}
