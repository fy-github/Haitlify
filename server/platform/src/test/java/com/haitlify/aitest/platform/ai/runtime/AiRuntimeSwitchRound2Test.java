package com.haitlify.aitest.platform.ai.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.haitlify.aitest.platform.ai.event.AiRunEventService;
import com.haitlify.aitest.platform.ai.gateway.AiGatewaySettingsService;
import com.haitlify.aitest.platform.ai.gateway.ResolvedAiGatewaySettings;
import com.haitlify.aitest.platform.ai.runtime.transport.AiProtocolTransportClient;

import tools.jackson.databind.ObjectMapper;

/**
 * T-R5 轮 2 回归（复核发现 3）：发布开关必须覆盖 job Runtime 路径——
 * 同一禁用配置下 DefaultAiRuntime 在发出任何请求前阻断（复核探针 C2 的仓库化版本）。
 */
class AiRuntimeSwitchRound2Test {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AiProtocolTransportClient transportClient;
    private AiAttemptRepository attempts;
    private AiJobService jobs;

    @BeforeEach
    void setUp() {
        AiGatewaySettingsService settingsService = mock(AiGatewaySettingsService.class);
        when(settingsService.loadDefaultTextSettings()).thenReturn(
            new ResolvedAiGatewaySettings("chat_completions", "http://127.0.0.1:1/v1", "model-x", "key-x", 5_000));
        transportClient = mock(AiProtocolTransportClient.class);
        attempts = mock(AiAttemptRepository.class);
        when(attempts.begin(any(), any(), any(), any(), any(), any())).thenReturn("attempt-1");
        jobs = mock(AiJobService.class);
        AiExecutionLedger ledger = mock(AiExecutionLedger.class);
        when(ledger.remainingBudgetMs(any(String.class), org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(5_000L);
        AiRunEventService events = mock(AiRunEventService.class);
        this.ledger = ledger;
        this.events = events;
    }

    private AiExecutionLedger ledger;
    private AiRunEventService events;

    private DefaultAiRuntime runtimeWith(AiRuntimeSwitches.Switches switches) {
        AiGatewaySettingsService settingsService = mock(AiGatewaySettingsService.class);
        when(settingsService.loadDefaultTextSettings()).thenReturn(
            new ResolvedAiGatewaySettings("chat_completions", "http://127.0.0.1:1/v1", "model-x", "key-x", 5_000));
        return new DefaultAiRuntime(settingsService, transportClient, attempts, ledger, jobs,
            events, objectMapper, switches);
    }

    private AiJobService.AiJob job(long projectId) {
        return new AiJobService.AiJob("job-" + UUID.randomUUID(), "requirement_analysis", projectId,
            "actor-1", "idem-1", "hash-1", "running", "worker-1", null, 1L, 1, "rev-1", null, "",
            java.time.Instant.now(), java.time.Instant.now(), null, null);
    }

    private AiRuntimeModels.AiTaskRequest request(long projectId) {
        try {
            return new AiRuntimeModels.AiTaskRequest("requirement_analysis", projectId, "actor-1", "run-1",
                null, null, null, List.of("ev-1"),
                objectMapper.readTree("{\"requirement\":\"需求文本\"}"), "idem-" + UUID.randomUUID());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void aDisabledTaskTypeBlocksTheJobRuntimeBeforeAnyRequest() throws Exception {
        DefaultAiRuntime runtime = runtimeWith(AiRuntimeSwitches.parse("requirement_analysis", ""));

        AiRuntime.RuntimeExecution result = runtime.execute(job(9L), "node-1", request(9L));

        assertThat(result.status()).isEqualTo("blocked");
        assertThat(result.terminalReason()).isEqualTo("runtime_disabled");
        verify(transportClient, never()).execute(any());
        verify(attempts, never()).begin(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aDisabledProjectBlocksTheJobRuntimeBeforeAnyRequest() throws Exception {
        DefaultAiRuntime runtime = runtimeWith(AiRuntimeSwitches.parse("", "7"));

        AiRuntime.RuntimeExecution result = runtime.execute(job(7L), "node-1", request(7L));

        assertThat(result.status()).isEqualTo("blocked");
        assertThat(result.terminalReason()).isEqualTo("runtime_disabled");
        verify(transportClient, never()).execute(any());
    }

    @Test
    void caseAndWhitespaceVariantsCannotBypassTheSwitch() {
        AiRuntimeSwitches.Switches switches = AiRuntimeSwitches.parse(" Bug_Analysis , x ", "");
        assertThat(switches.isDisabled("bug_analysis", null)).isTrue();
        assertThat(switches.isDisabled("BUG_ANALYSIS", null)).isTrue();
        assertThat(switches.isDisabled("  bug_analysis  ", null)).isTrue();
        assertThat(switches.isDisabled("other", null)).isFalse();
    }

    @Test
    void unicodeWhitespaceAndFullWidthVariantsCannotBypassTheSwitch() {
        // T-R5 轮 3：NBSP/U+2007/U+3000/零宽/全角字母都必须命中。
        AiRuntimeSwitches.Switches switches = AiRuntimeSwitches.parse("bug_analysis,\u00a0log_triage", " 7 ");
        assertThat(switches.isDisabled("bug_analysis\u00a0", null)).isTrue();
        assertThat(switches.isDisabled("\u3000bug_analysis", null)).isTrue();
        assertThat(switches.isDisabled("bug\u200b_analysis", null)).isTrue();
        assertThat(switches.isDisabled("ｂｕｇ＿ａｎａｌｙｓｉｓ", null)).isTrue();
        assertThat(switches.isDisabled("log_triage", null)).isTrue();
        assertThat(switches.isDisabled("x", 7L)).isTrue();
        assertThat(switches.isDisabled("other", 8L)).isFalse();
    }

    @Test
    void invisibleCombiningMarksAndConfusableLettersNormalizeIdentically() {
        // T-R5 轮 4：U+034F(CGJ)/U+FE0F(VS16)/U+0130(İ)/U+02B0(ʰ) 变体必须归一到同一标识符。
        assertThat(AiRuntimeSwitches.normalizeIdentifier("requirement\u034f_analysis"))
            .isEqualTo("requirement_analysis");
        assertThat(AiRuntimeSwitches.normalizeIdentifier("requirement_analysis\ufe0f"))
            .isEqualTo("requirement_analysis");
        assertThat(AiRuntimeSwitches.normalizeIdentifier("requirement_analys\u0130s"))
            .isEqualTo("requirement_analysis");
        // 'ʰ' 经 NFKC 折叠为 'h' ⇒ 得到的是另一个标识符，绝不等同真实类型（提交端拒绝）。
        assertThat(AiRuntimeSwitches.normalizeIdentifier("requirement_analysis\u02b0"))
            .isNotEqualTo("requirement_analysis");
        AiRuntimeSwitches.Switches switches = AiRuntimeSwitches.parse("requirement_analysis", "");
        assertThat(switches.isDisabled("requirement\u034f_analysis", null)).isTrue();
        assertThat(switches.isDisabled("requirement_analysis\ufe0f", null)).isTrue();
    }

    @Test
    void uppercaseFoldingCompatCharactersConvergeToTheCanonicalIdentifier() {
        // T-R5 轮 5：U+1D400 数学粗体 A 等 NFKC 折叠为大写 ASCII 的兼容字符必须迭代收敛。
        assertThat(AiRuntimeSwitches.normalizeIdentifier("bug_\ud835\udc00nalysis"))
            .isEqualTo("bug_analysis");
        assertThat(AiRuntimeSwitches.normalizeIdentifier("\ud835\udc01ug_analysis"))
            .isEqualTo("bug_analysis");
        // 配置侧写变体也必须命中（否则灰度静默失效）。
        AiRuntimeSwitches.Switches switches = AiRuntimeSwitches.parse("bug_\ud835\udc00nalysis", "");
        assertThat(switches.isDisabled("bug_analysis", null)).isTrue();
        assertThat(switches.isDisabled("bug_\ud835\udc00nalysis", null)).isTrue();
        // İ 组合点族仍然收敛（两类副作用互补）。
        assertThat(AiRuntimeSwitches.normalizeIdentifier("requirement_analys\u0130s"))
            .isEqualTo("requirement_analysis");
    }

    @Test
    void supplementaryPlaneMarksAreStrippedToo() {
        // T-R5 轮 6：U+101FD（Mn）/U+E0020（TAG Cf）在补充平面，必须按码点剥离。
        assertThat(AiRuntimeSwitches.normalizeIdentifier("bug_analysis\ud800\uddfd"))
            .isEqualTo("bug_analysis");
        assertThat(AiRuntimeSwitches.normalizeIdentifier("bug_analysis\udb40\udc20"))
            .isEqualTo("bug_analysis");
        AiRuntimeSwitches.Switches switches = AiRuntimeSwitches.parse("bug_analysis", "");
        assertThat(switches.isDisabled("bug_analysis\ud800\uddfd", null)).isTrue();
        assertThat(switches.isDisabled("bug_analysis\udb40\udc20", null)).isTrue();
    }

    @Test
    void theLegacyRouteExecutorPathIsGatedToo() {
        // T-R5 轮 2 / 收敛轮：legacy analysis 管道（经 AiRouteExecutor→统一 Runtime）同样受开关约束。
        var gateway = mock(com.haitlify.aitest.platform.ai.gateway.AiGateway.class);
        var eventService = mock(com.haitlify.aitest.platform.ai.event.AiRunEventService.class);
        var ledgerForGate = new AiExecutionLedger();
        var executor = new com.haitlify.aitest.platform.ai.gateway.AiRouteExecutor(
            AiTaskExecutionFacade.minimal(gateway, ledgerForGate), eventService,
            new AiCancellationRegistry(),
            ledgerForGate,
            java.time.Clock.systemUTC(),
            new AiRetryPolicy(java.time.Clock.systemUTC()),
            "bug_analysis", "");
        var strategy = new com.haitlify.aitest.platform.ai.gateway.AiRoutingStrategy(
            "snap-1", "bug_analysis",
            java.util.List.of(new com.haitlify.aitest.platform.ai.gateway.AiRoute(
                "conn-1", "openai", "openai", "http://127.0.0.1:1/v1", "m", "k", 5000)),
            1, 5000L, 0L, null);
        var ctx = new com.haitlify.aitest.platform.ai.gateway.AiRouteExecutionContext(
            "run-x", "snap-1", 0, 1, "chunk-1", "bug_analysis", 9L);

        var result = executor.execute(strategy, "prompt", ctx);

        assertThat(result.success()).isFalse();
        assertThat(result.terminalReason())
            .isEqualTo(com.haitlify.aitest.platform.ai.gateway.AiRetryReason.RUNTIME_DISABLED);
        verify(gateway, never()).structuredGenerate(any(), any());

        // T-R5 轮 3：ctx.taskType 命中而 strategy.taskType 不同 ⇒ 仍按 ctx 阻断（取值统一）。
        var ctxOnly = new com.haitlify.aitest.platform.ai.gateway.AiRouteExecutionContext(
            "run-y", "snap-1", 0, 1, "chunk-1", "bug_analysis", 9L);
        var otherStrategy = new com.haitlify.aitest.platform.ai.gateway.AiRoutingStrategy(
            "snap-1", "requirement_analysis",
            java.util.List.of(new com.haitlify.aitest.platform.ai.gateway.AiRoute(
                "conn-1", "openai", "openai", "http://127.0.0.1:1/v1", "m", "k", 5000)),
            1, 5000L, 0L, null);
        var ctxOnlyResult = executor.execute(otherStrategy, "prompt", ctxOnly);
        assertThat(ctxOnlyResult.terminalReason())
            .isEqualTo(com.haitlify.aitest.platform.ai.gateway.AiRetryReason.RUNTIME_DISABLED);
        verify(gateway, never()).structuredGenerate(any(), any());
    }

    @Test
    void anEnabledScopeStillReachesTransport() throws Exception {
        DefaultAiRuntime runtime = runtimeWith(AiRuntimeSwitches.parse("other_task", "8"));
        when(transportClient.execute(any())).thenThrow(new java.io.IOException("boom"));

        AiRuntime.RuntimeExecution result = runtime.execute(job(9L), "node-1", request(9L));

        // 未被开关阻断：走到了 transport（因此必然发生了一次真实的发送尝试）。
        assertThat(result.status()).isEqualTo("failed");
        verify(transportClient).execute(any());
    }
}
