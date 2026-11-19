package com.haitlify.aitest.platform.assistant.waiting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.haitlify.aitest.platform.assistant.ledger.AssistantAdmissionService;
import com.haitlify.aitest.platform.assistant.ledger.AssistantLedgerRepository;
import com.haitlify.aitest.platform.assistant.reconcile.AssistantReconciliationService;
import com.haitlify.aitest.platform.assistant.report.AssistantReportService;

/**
 * 等待用户（计划 §4.3 / §6.3）：MFA / 人工登录 / 缺输入要有明确状态与到期处理。
 *
 * <p>断言的都是「不会假装」的边界：非终态不能出报告、终态不能进等待、超时只能取消。
 */
@SpringBootTest(properties = "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=")
@ActiveProfiles("test")
class AssistantWaitingUserServiceTest {

    @Autowired
    private AssistantAdmissionService admission;

    @Autowired
    private AssistantWaitingUserService waiting;

    @Autowired
    private AssistantLedgerRepository ledger;

    @Autowired
    private AssistantReportService reports;

    @Autowired
    private AssistantReconciliationService reconciliation;

    private String admitPending(long tenant) {
        return admission.admit(new AssistantAdmissionService.AdmissionCommand(tenant, 5L, 1L,
            UUID.randomUUID().toString(), "a".repeat(64), "web.case.execute",
            "project:1/waiting", "rev-1", null)).operationId();
    }

    @Test
    void marksPendingOperationAsWaitingUserAndBlocksReportGeneration() {
        long tenant = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1_000_000L;
        String operationId = admitPending(tenant);

        AssistantWaitingUserService.WaitingView view = waiting.requireUser(operationId,
            "需要本人完成 MFA", Duration.ofMinutes(15));

        assertThat(view.executionState()).isEqualTo("WAITING_USER");
        assertThat(view.deadlineAt()).isAfter(view.requestedAt());
        AssistantLedgerRepository.WaitingInfo info = ledger.waitingInfo(operationId).orElseThrow();
        assertThat(info.reason()).isEqualTo("需要本人完成 MFA");

        // 等待中不是终态：报告不能生成（否则会把「等人」写成执行结果）。
        assertThatThrownBy(() -> reports.generate(operationId, 5L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("终态");
    }

    @Test
    void resumingFromWaitingReturnsToPendingInsteadOfSilentlyRerunning() {
        long tenant = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1_000_000L;
        String operationId = admitPending(tenant);
        waiting.requireUser(operationId, "缺测试账号口令", Duration.ofMinutes(10));

        AssistantWaitingUserService.WaitingView resumed = waiting.resume(operationId);

        assertThat(resumed.executionState()).isEqualTo("PENDING");
        // 等待字段必须清空：残留的 deadline 会让后续清扫误判。
        AssistantLedgerRepository.WaitingInfo info = ledger.waitingInfo(operationId).orElseThrow();
        assertThat(info.reason()).isNull();
        assertThat(info.deadlineAt()).isNull();
        // 恢复只是回到待派发；重复恢复已经不在等待态，必须拒绝。
        assertThatThrownBy(() -> waiting.resume(operationId))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("只有 WAITING_USER");
    }

    @Test
    void cancellingWaitingKeepsBothReasonsForAudit() {
        long tenant = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1_000_000L;
        String operationId = admitPending(tenant);
        waiting.requireUser(operationId, "需要本人完成验证码", Duration.ofMinutes(10));

        AssistantWaitingUserService.WaitingView cancelled = waiting.cancel(operationId, "用户放弃等待");

        assertThat(cancelled.executionState()).isEqualTo("CANCELLED");
        AssistantLedgerRepository.WaitingInfo info = ledger.waitingInfo(operationId).orElseThrow();
        assertThat(info.reason()).contains("需要本人完成验证码").contains("用户放弃等待");
        assertThat(ledger.findOperation(operationId).orElseThrow().executionState()).isEqualTo("CANCELLED");
    }

    @Test
    void expiredWaitingIsCancelledWithTimeoutMarker() {
        long tenant = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1_000_000L;
        String operationId = admitPending(tenant);
        // 直接写入已过期的 deadline：模拟「等过了期限」，不靠 sleep。
        Instant requested = Instant.now().minus(Duration.ofMinutes(30));
        ledger.markWaitingUser(operationId, "PENDING", "等待人工登录", requested, Instant.now().minusSeconds(1));

        var expired = waiting.expireOverdue();

        assertThat(expired).contains(operationId);
        assertThat(ledger.findOperation(operationId).orElseThrow().executionState()).isEqualTo("CANCELLED");
        assertThat(ledger.waitingInfo(operationId).orElseThrow().reason()).contains("waiting_timeout");
    }

    @Test
    void reconciliationSweepExpiresOverdueWaitingWithoutManualTrigger() {
        long tenant = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1_000_000L;
        String operationId = admitPending(tenant);
        ledger.markWaitingUser(operationId, "PENDING", "等待人工登录",
            Instant.now().minus(Duration.ofMinutes(10)), Instant.now().minusSeconds(1));

        // 计划 §4.3 的「到期处理」必须由对账兜底，不能只靠人点按钮（复核 D1）。
        int expired = reconciliation.expireWaitingUsers();

        assertThat(expired).isGreaterThanOrEqualTo(1);
        assertThat(ledger.findOperation(operationId).orElseThrow().executionState()).isEqualTo("CANCELLED");
    }

    @Test
    void expiryTruncatesLongReasonsInsteadOfFailingTheWholeSweep() {
        long tenant = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1_000_000L;
        String operationId = admitPending(tenant);
        String reason = "x".repeat(500);
        ledger.markWaitingUser(operationId, "PENDING", reason,
            Instant.now().minus(Duration.ofMinutes(10)), Instant.now().minusSeconds(1));

        var expired = waiting.expireOverdue();

        // 500 字符原文 + 后缀会超过 VARCHAR(500)：必须截断，而不是抛错中断整轮（复核 D2）。
        assertThat(expired).contains(operationId);
        String stored = ledger.waitingInfo(operationId).orElseThrow().reason();
        assertThat(stored).hasSizeLessThanOrEqualTo(500).endsWith("[waiting_timeout]");
    }

    @Test
    void expiryOfWaitingWithALiveRunFreezesAsUnknownInsteadOfPretendingCancelled() {
        long tenant = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1_000_000L;
        String operationId = admitPending(tenant);
        ledger.transitionExecution(operationId, "PENDING", "DISPATCHED");
        String attemptId = UUID.randomUUID().toString();
        ledger.insertAttempt(attemptId, operationId, 1, Instant.now());
        ledger.bindAttemptRun(attemptId, "run-maybe-live");
        // 直接从 DISPATCHED 进入等待并写一个已过期 deadline（markWaiting 自带 CAS 迁移）。
        ledger.markWaitingUser(operationId, "DISPATCHED", "等待人工登录",
            Instant.now().minus(Duration.ofMinutes(30)), Instant.now().minusSeconds(1));

        waiting.expireOverdue();

        // 还有未收尾 run：不能写「已取消」，冻结为 UNKNOWN 交对账（与 D3 同纪律）。
        assertThat(ledger.findOperation(operationId).orElseThrow().executionState()).isEqualTo("UNKNOWN");
        // UNKNOWN 下已不是「在等」：describe 不能再回已过期的时间字段（否则前端会显示过期截止）。
        AssistantWaitingUserService.WaitingView frozen = waiting.describe(operationId);
        assertThat(frozen.executionState()).isEqualTo("UNKNOWN");
        assertThat(frozen.deadlineAt()).isNull();
        assertThat(frozen.requestedAt()).isNull();
        assertThat(ledger.waitingInfo(operationId).orElseThrow().reason()).contains("waiting_timeout");
    }

    @Test
    void expiryCancelsWaitingWhenTheAttemptAlreadyEnded() {
        long tenant = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1_000_000L;
        String operationId = admitPending(tenant);
        ledger.transitionExecution(operationId, "PENDING", "DISPATCHED");
        String attemptId = UUID.randomUUID().toString();
        ledger.insertAttempt(attemptId, operationId, 1, Instant.now());
        ledger.bindAttemptRun(attemptId, "run-already-ended");
        ledger.finishAttempt(attemptId, "CANCELLED");
        ledger.markWaitingUser(operationId, "DISPATCHED", "等待人工登录",
            Instant.now().minus(Duration.ofMinutes(30)), Instant.now().minusSeconds(1));

        waiting.expireOverdue();

        // 没有活 run 就该如实取消；把 ended_at 判断去掉会让本用例变红（变异敏感）。
        assertThat(ledger.findOperation(operationId).orElseThrow().executionState()).isEqualTo("CANCELLED");
    }

    @Test
    void refusesToCancelWaitingWhileARunnerRunIsStillUnfinished() {
        long tenant = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1_000_000L;
        String operationId = admitPending(tenant);
        ledger.transitionExecution(operationId, "PENDING", "DISPATCHED");
        String attemptId = UUID.randomUUID().toString();
        ledger.insertAttempt(attemptId, operationId, 1, Instant.now());
        ledger.bindAttemptRun(attemptId, "run-still-live");
        ledger.transitionExecution(operationId, "DISPATCHED", "WAITING_USER");

        // 账面取消不能绕过「先停 Runner」的纪律（复核 D3）。
        assertThatThrownBy(() -> waiting.cancel(operationId, "放弃等待"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("停止执行");
        assertThat(ledger.findOperation(operationId).orElseThrow().executionState()).isEqualTo("WAITING_USER");
    }

    @Test
    void rejectsTerminalOperationsAndInvalidWaitingRequests() {
        long tenant = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1_000_000L;
        String operationId = admitPending(tenant);
        assertThatThrownBy(() -> waiting.requireUser(operationId, "  ", Duration.ofMinutes(10)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("原因");
        assertThatThrownBy(() -> waiting.requireUser(operationId, "MFA", Duration.ofSeconds(10)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("1 分钟");
        assertThatThrownBy(() -> waiting.requireUser(operationId, "MFA", Duration.ofHours(48)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("24 小时");

        ledger.transitionExecution(operationId, "PENDING", "CANCELLED");
        assertThatThrownBy(() -> waiting.requireUser(operationId, "MFA", Duration.ofMinutes(10)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PENDING/DISPATCHED/RUNNING");
    }
}
