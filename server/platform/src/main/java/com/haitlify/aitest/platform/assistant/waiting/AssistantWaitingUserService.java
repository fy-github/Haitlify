package com.haitlify.aitest.platform.assistant.waiting;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.haitlify.aitest.platform.assistant.ledger.AssistantLedgerRepository;
import com.haitlify.aitest.platform.assistant.ledger.AssistantStateConflictException;
import com.haitlify.aitest.platform.assistant.security.AssistantActorResolver;

/**
 * 等待用户（计划 §4.3 / §6.3）：MFA、人工登录、缺输入这类阻塞必须有明确状态与到期处理，
 * 不能用「助手正在处理」含糊过去，也不能让模型自行宣布已恢复。
 *
 * <p>确定性规则：
 * <ul>
 *   <li>只有 PENDING / DISPATCHED / RUNNING 可以进入 WAITING_USER；终态与 UNKNOWN 拒绝。</li>
 *   <li>WAITING_USER 非终态：报告不能生成（沿用 isTerminal 门），Runner 回执也推不动它；
 *       唯一能写终态的是「先回读、必要时停止 Runner」的 stop 路径（SUCCEEDED/FAILED 如实记录，
 *       用户在账面放弃或超时且无活 run 才是 CANCELLED）。</li>
 *   <li>用户补齐后回到 PENDING，必须由新的受权派发推进，不直接续跑旧代次。</li>
 *   <li>用户放弃或超时一律 CANCELLED，并保留原因；到期清扫是确定性的，不靠模型重试。</li>
 * </ul>
 */
@Service
public class AssistantWaitingUserService {

    public record WaitingView(String operationId, String executionState, String reason,
                              Instant requestedAt, Instant deadlineAt) {}

    private static final Set<String> ALLOWED_FROM = Set.of("PENDING", "DISPATCHED", "RUNNING");
    private static final Duration MIN_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration MAX_TIMEOUT = Duration.ofHours(24);

    private final AssistantLedgerRepository ledger;
    private final com.haitlify.aitest.platform.auth.ProjectAccessService projectAccess;

    public AssistantWaitingUserService(AssistantLedgerRepository ledger,
                                       com.haitlify.aitest.platform.auth.ProjectAccessService projectAccess) {
        this.ledger = ledger;
        this.projectAccess = projectAccess;
    }

    @org.springframework.transaction.annotation.Transactional
    public WaitingView requireUser(String operationId, String reason, Duration timeout) {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId 不能为空");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("等待用户必须给出原因（例如需要本人完成 MFA）");
        }
        if (reason.length() > 500) {
            throw new IllegalArgumentException("等待原因最多 500 字符");
        }
        Duration effective = timeout == null ? Duration.ofMinutes(30) : timeout;
        if (effective.compareTo(MIN_TIMEOUT) < 0 || effective.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("等待时长必须在 1 分钟到 24 小时之间：" + effective);
        }
        AssistantLedgerRepository.OperationRecord operation = ledger.findOperationForUpdate(operationId)
            .orElseThrow(() -> new IllegalArgumentException("operation 不存在：" + operationId));
        String from = operation.executionState();
        if (!ALLOWED_FROM.contains(from)) {
            throw new AssistantStateConflictException("只有 PENDING/DISPATCHED/RUNNING 可以进入等待用户（当前 " + from + "）");
        }
        Instant requestedAt = Instant.now();
        Instant deadlineAt = requestedAt.plus(effective);
        if (!ledger.markWaitingUser(operationId, from, reason, requestedAt, deadlineAt)) {
            throw new AssistantStateConflictException("状态已变化，进入等待失败：" + operationId);
        }
        return new WaitingView(operationId, "WAITING_USER", reason, requestedAt, deadlineAt);
    }

    /** 用户补齐后回到 PENDING；随后必须用新的 requestId 派发，旧代次不会自动续跑。 */
    @org.springframework.transaction.annotation.Transactional
    public WaitingView resume(String operationId) {
        AssistantLedgerRepository.OperationRecord operation = ledger.findOperationForUpdate(operationId)
            .orElseThrow(() -> new IllegalArgumentException("operation 不存在：" + operationId));
        if (!"WAITING_USER".equals(operation.executionState())) {
            throw new AssistantStateConflictException("只有 WAITING_USER 可以恢复（当前 " + operation.executionState() + "）");
        }
        if (!ledger.leaveWaitingUser(operationId, "PENDING")) {
            throw new AssistantStateConflictException("状态已变化，恢复失败：" + operationId);
        }
        return new WaitingView(operationId, "PENDING", null, null, null);
    }

    /** 用户放弃等待：转 CANCELLED 并保留原因。 */
    @org.springframework.transaction.annotation.Transactional
    public WaitingView cancel(String operationId, String reason) {
        AssistantLedgerRepository.OperationRecord operation = ledger.findOperationForUpdate(operationId)
            .orElseThrow(() -> new IllegalArgumentException("operation 不存在：" + operationId));
        if (!"WAITING_USER".equals(operation.executionState())) {
            throw new AssistantStateConflictException("只有 WAITING_USER 可以取消（当前 " + operation.executionState() + "）");
        }
        String merged = reason == null || reason.isBlank() ? "cancelled_by_user" : reason.trim();
        if (merged.length() > 500) {
            merged = merged.substring(0, 500);
        }
        // 已派发过 Runner 的 operation 不能靠「取消等待」直接写 CANCELLED：
        // run 可能仍在跑，账面取消会与执行事实冲突。必须先走 stop 路径（停不掉就不做账面取消）。
        boolean mayHaveLiveRun = ledger.listAttempts(operationId).stream()
            .anyMatch(attempt -> attempt.runnerRunId() != null && attempt.endedAt() == null);
        if (mayHaveLiveRun) {
            throw new AssistantStateConflictException(
                "该 operation 有未收尾的 Runner 执行，请先停止执行再取消等待：" + operationId);
        }
        AssistantLedgerRepository.WaitingInfo info = ledger.waitingInfo(operationId).orElse(null);
        String combined = info == null || info.reason() == null || info.reason().isBlank()
            ? merged
            : info.reason() + " -> " + merged;
        // 先读原因再写回：原等待原因与本次取消原因都要留痕。
        String stored = combined.length() > 500 ? combined.substring(0, 500) : combined;
        if (!ledger.cancelWaitingUser(operationId, stored)) {
            throw new AssistantStateConflictException("状态已变化，取消失败：" + operationId);
        }
        return new WaitingView(operationId, "CANCELLED", combined, info == null ? null : info.requestedAt(),
            info == null ? null : info.deadlineAt());
    }

    /** 确定性清扫：所有到期的等待一律 CANCELLED（定时对账使用，跨租户）。 */
    public List<String> expireOverdue() {
        return ledger.expireOverdueWaitingUser(Instant.now());
    }

    /**
     * 运维入口的清扫：只清指定租户，并要求有操作人（留痕）。
     * 不开放跨租户的手动清扫，避免任意登录用户触发全平台状态变更。
     */
    public List<String> expireOverdue(long tenantId, Long actorId) {
        if (tenantId <= 0) {
            throw new IllegalArgumentException("tenantId 必须为正数：" + tenantId);
        }
        if (actorId == null || actorId <= 0) {
            throw new AssistantActorResolver.ActorResolutionException("缺少操作人，拒绝手动清扫");
        }
        // 手动清扫是租户级动作：只允许管理员，普通成员只能操作自己有权限的 operation。
        if (!projectAccess.isAdmin()) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN, "只有管理员可以手动清扫等待中的任务");
        }
        List<String> expired = ledger.expireOverdueWaitingUser(Instant.now(), tenantId);
        for (String operationId : expired) {
            // 手动清扫必须留痕：谁在什么时候清了哪条等待。
            ledger.appendReconciliation(operationId, null, "MANUAL_DISPOSITION", actorId,
                "{\"reason\":\"waiting-user-timeout-manual\",\"tenantId\":" + tenantId + "}");
        }
        return expired;
    }

    public WaitingView describe(String operationId) {
        AssistantLedgerRepository.OperationRecord operation = ledger.findOperation(operationId)
            .orElseThrow(() -> new IllegalArgumentException("operation 不存在：" + operationId));
        AssistantLedgerRepository.WaitingInfo info = ledger.waitingInfo(operationId).orElse(null);
        // 终态仍保留原因（审计要看「等过什么」），但不再回报已失效的截止时间，
        // 避免前端把已取消的任务显示成「仍在等待」。
        // UNKNOWN（超时且有活 run 被冻结）同样不再「在等」：时间字段一律隐藏。
        boolean terminal = java.util.Set.of("SUCCEEDED", "FAILED", "CANCELLED", "UNKNOWN")
            .contains(operation.executionState());
        return new WaitingView(operationId, operation.executionState(),
            info == null ? null : info.reason(),
            terminal || info == null ? null : info.requestedAt(),
            terminal || info == null ? null : info.deadlineAt());
    }
}
