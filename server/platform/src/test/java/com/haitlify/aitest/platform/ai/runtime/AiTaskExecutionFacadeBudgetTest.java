package com.haitlify.aitest.platform.ai.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.haitlify.aitest.platform.ai.gateway.AiGateway;
import com.haitlify.aitest.platform.ai.gateway.AiGatewayConfig;
import com.haitlify.aitest.platform.ai.gateway.AiGenerationResult;
import com.haitlify.aitest.platform.ai.quota.AiBudgetService;

import tools.jackson.databind.ObjectMapper;

/**
 * T13 批次 2：预算护栏接入 facade——预算不足时「未发出模型请求」是事实（gateway 零调用），
 * 每 attempt 预留 + 结算；未知 usage/未知价格按预留额计入；多 scope 任一失败时前序预留释放。
 */
class AiTaskExecutionFacadeBudgetTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JdbcTemplate jdbcTemplate;
    private AiBudgetService budgetService;
    private AiGateway gateway;
    private AiExecutionLedger ledger;
    private AiAttemptRepository attempts;
    private final LocalDate day = LocalDate.of(2026, 10, 10);

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:facade-budget;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
            create table if not exists ai_budget_ledger(
              ledger_id bigint auto_increment primary key, scope_key varchar(160) not null, window_day date not null,
              reserved_tokens bigint default 0, settled_tokens bigint default 0,
              reserved_calls int default 0, settled_calls int default 0,
              limit_tokens bigint not null, limit_calls int not null, updated_at timestamp,
              constraint uk_ai_budget_ledger_scope unique (scope_key, window_day))
            """);
        jdbcTemplate.execute("""
            create table if not exists ai_budget_reservations(
              reservation_id varchar(128) primary key, scope_key varchar(160) not null, window_day date not null,
              reserved_tokens bigint not null, reserved_calls int not null, settled_tokens bigint,
              settled_calls int, usage_known boolean default false, pricing_mode varchar(32) not null,
              state varchar(32) not null, attempt_ref varchar(160) default '', created_at timestamp, settled_at timestamp)
            """);
        jdbcTemplate.execute("delete from ai_budget_reservations");
        jdbcTemplate.execute("delete from ai_budget_ledger");
        budgetService = new AiBudgetService(jdbcTemplate);
        gateway = mock(AiGateway.class);
        ledger = mock(AiExecutionLedger.class);
        attempts = mock(AiAttemptRepository.class);
        when(attempts.begin(any(), any(), any(), any(), any(), any())).thenReturn("attempt-1");
    }

    private AiTaskExecutionFacade facadeWithLimits(long tokens, int calls) {
        com.haitlify.aitest.platform.ai.gateway.AiGatewaySettingsService settingsService =
            mock(com.haitlify.aitest.platform.ai.gateway.AiGatewaySettingsService.class);
        when(settingsService.loadDefaultTextSettings()).thenReturn(
            new com.haitlify.aitest.platform.ai.gateway.ResolvedAiGatewaySettings(
                "openai", "http://localhost/", "test-model", "", 60_000));
        AiTaskExecutionFacade facade = new AiTaskExecutionFacade(
            settingsService, gateway, ledger, attempts, budgetService, tokens, calls);
        // 用固定时钟把预算窗口钉在测试日（测试与 facade 同包，直接调用包内方法）。
        facade.useClock(Clock.fixed(day.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        return facade;
    }

    @Test
    void budgetExhaustionBlocksBeforeAnyModelRequest() throws Exception {
        // global 上限 100 tokens：一次 prompt 即耗尽。
        AiTaskExecutionFacade facade = facadeWithLimits(100, 100);
        when(ledger.remainingBudgetMs(any(String.class), anyLong(), any(Clock.class))).thenReturn(60_000L);

        AiTaskExecutionFacade.TaskExecution task = new AiTaskExecutionFacade.TaskExecution(
            "bug_analysis", 1L, "tester", "run-1",
            objectMapper.readTree("{\"instruction\":\"" + "x".repeat(400) + "\"}"), null);
        AiGenerationResult result = facade.generate(task);
        assertThat(result.success()).isFalse();
        assertThat(result.retryReason()).isEqualTo("budget_exceeded");
        assertThat(result.errorMessage()).contains("未发出模型请求");
        verify(gateway, never()).structuredGenerate(any(), any());
        // 被阻断的 attempt 如实记为失败（BUDGET_EXCEEDED）。
        verify(attempts).finalize(any(), org.mockito.ArgumentMatchers.argThat(outcome ->
            "BUDGET_EXCEEDED".equals(outcome.retryReason())));
    }

    @Test
    void successfulCallsSettleEveryScopeAndUnknownUsageCountsFully() {
        AiTaskExecutionFacade facade = facadeWithLimits(1_000_000, 100);
        when(ledger.remainingBudgetMs(any(String.class), anyLong(), any(Clock.class))).thenReturn(60_000L);
        when(gateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, "{}", "req-1", 0, 0, null, false, "", 0));

        AiGenerationResult result = facade.generate(new AiTaskExecutionFacade.TaskExecution(
            "bug_analysis", 42L, "tester", "run-42",
            objectMapper.readTree("{\"instruction\":\"ping\"}"), null));
        assertThat(result.success()).isTrue();

        // global / project / task 三个 scope 都有结算；usage 未知 ⇒ 按预留额计入。
        Map<String, Object> global = budgetService.snapshot(AiBudgetService.globalScope(), day);
        Map<String, Object> project = budgetService.snapshot(AiBudgetService.projectScope(42L), day);
        Map<String, Object> task = budgetService.snapshot(AiBudgetService.taskScope("bug_analysis"), day);
        assertThat(global).isNotEmpty();
        assertThat(project).isNotEmpty();
        assertThat(task).isNotEmpty();
        assertThat(((Number) global.get("settled_tokens")).longValue()).isGreaterThan(0);
        assertThat(((Number) global.get("reserved_tokens")).longValue()).isZero();
        assertThat(((Number) project.get("settled_calls")).intValue()).isEqualTo(1);
    }

    @Test
    void anExhaustedLaterScopeReleasesTheEarlierReservations() {
        // 统一上限 5000 tokens；task scope 预先占掉 4900 ⇒ 本次约 4100 的预留必然超出，
        // 此时 global/project 的预留必须原样释放（调用没有发生，不得计费）。
        AiTaskExecutionFacade facade = facadeWithLimits(5_000, 100);
        when(ledger.remainingBudgetMs(any(String.class), anyLong(), any(Clock.class))).thenReturn(60_000L);
        budgetService.reserve(AiBudgetService.taskScope("bug_analysis"), day,
            new AiBudgetService.BudgetLimit(5_000, 100), 4_900, 1, "seed");

        AiGenerationResult result = facade.generate(new AiTaskExecutionFacade.TaskExecution(
            "bug_analysis", 43L, "tester", "run-43",
            objectMapper.readTree("{\"instruction\":\"ping\"}"), null));
        assertThat(result.retryReason()).isEqualTo("budget_exceeded");
        verify(gateway, never()).structuredGenerate(any(), any());

        // global 与 project 的预留已释放：reserved 归零、没有结算。
        Map<String, Object> global = budgetService.snapshot(AiBudgetService.globalScope(), day);
        Map<String, Object> project = budgetService.snapshot(AiBudgetService.projectScope(43L), day);
        assertThat(((Number) global.get("reserved_tokens")).longValue()).isZero();
        assertThat(((Number) global.get("settled_tokens")).longValue()).isZero();
        assertThat(((Number) project.get("reserved_tokens")).longValue()).isZero();
        assertThat(((Number) project.get("settled_tokens")).longValue()).isZero();
        Integer released = jdbcTemplate.queryForObject(
            "select count(*) from ai_budget_reservations where state = 'RELEASED'", Integer.class);
        assertThat(released).isEqualTo(2);
    }
}
