package com.haitlify.aitest.platform.ai.runtime;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

/**
 * T05 执行台账：按 runId 记录整体截止时刻与尝试计数，提供「最小剩余预算」：
 * 每次调用的有效超时 = min(per-call 超时, 距整体截止的剩余毫秒)；剩余 &lt;= 0 时任何调用都不得启动。
 * 同一 run 下的 repair / 工具等所有模型调用共用同一台账（同一 runId），取消与预算检查一致生效。
 */
@Component
public class AiExecutionLedger {

    private final Map<String, RunLedger> ledgers = new ConcurrentHashMap<>();

    public void open(String runId, Instant deadline) {
        if (runId == null) {
            return;
        }
        ledgers.put(runId, new RunLedger(deadline));
    }

    /**
     * 只在该 run 尚无台账时开启——已开台账（更早的整体截止已定）绝不被后续调用覆盖，
     * 否则后到的节点会把整体预算窗口悄悄重置（T06 运行时固定序列第 3 步用本方法）。
     */
    public void openIfAbsent(String runId, Instant deadline) {
        if (runId == null) {
            return;
        }
        ledgers.putIfAbsent(runId, new RunLedger(deadline));
    }

    /**
     * 本次调用的有效超时（毫秒）。无台账 ⇒ 仅受 per-call 约束（per-call &lt;= 0 时为 0，即拒绝——
     * 生产路由恒携带正超时；L8 修正文档与实际一致）。距截止剩余 &lt;= 0 ⇒ 返回 0（任何调用都不得启动）。
     */
    public long remainingBudgetMs(String runId, long perCallTimeoutMs, Clock clock) {
        RunLedger ledger = runId == null ? null : ledgers.get(runId);
        if (ledger == null || ledger.deadline == null) {
            return Math.max(0, perCallTimeoutMs);
        }
        long remaining = Duration.between(clock.instant(), ledger.deadline).toMillis();
        if (remaining <= 0) {
            return 0;
        }
        long perCall = perCallTimeoutMs <= 0 ? remaining : perCallTimeoutMs;
        return Math.min(perCall, remaining);
    }

    /** 记录一次真实发起的调用（取消/预算拒绝前不调用本方法）。 */
    public int tryStartAttempt(String runId) {
        RunLedger ledger = runId == null ? null : ledgers.get(runId);
        return ledger == null ? 0 : ledger.attempts.incrementAndGet();
    }

    public int attemptCount(String runId) {
        RunLedger ledger = runId == null ? null : ledgers.get(runId);
        return ledger == null ? 0 : ledger.attempts.get();
    }

    public void close(String runId) {
        if (runId != null) {
            ledgers.remove(runId);
        }
    }

    public int openLedgerCount() {
        return ledgers.size();
    }

    private static final class RunLedger {
        private final Instant deadline;
        private final AtomicInteger attempts = new AtomicInteger();

        private RunLedger(Instant deadline) {
            this.deadline = deadline;
        }
    }
}
