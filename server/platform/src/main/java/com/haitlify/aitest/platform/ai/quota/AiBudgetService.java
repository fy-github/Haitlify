package com.haitlify.aitest.platform.ai.quota;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * T13：预算护栏——项目/用户/任务/全局 × 日窗口的 reserve/settle。
 *
 * <ul>
 *   <li>reserve 是单语句 CAS（{@code reserved + n <= limit}），并发争抢绝不超发；</li>
 *   <li>拿不到 token 上界（无法给出保守预留）时 fail-closed：阻断而不是偷偷无限调用；</li>
 *   <li>settle 时 usage 未知 ⇒ 按预留额全额结算，绝不按 0/免费处理；模型价格未知
 *       同样不能算作免费——只有显式 KNOWN_PRICE 才能按实际用量折算；</li>
 *   <li>失败重试、备用路由、repair 各自是独立 attempt ⇒ 各自 reserve/settle，全部计入。</li>
 * </ul>
 */
@Service
public class AiBudgetService {

    public enum PricingMode { KNOWN_PRICE, UNKNOWN_PRICE_CONSERVATIVE }

    public enum SettleOutcome { SETTLED, ALREADY_SETTLED, NOT_FOUND }

    public record BudgetLimit(long limitTokens, int limitCalls) {
    }

    public record Reservation(String reservationId, String scopeKey, LocalDate windowDay,
                              long reservedTokens, int reservedCalls) {
    }

    /** 预算拒绝：调用方必须如实阻断/记 blocked，绝不允许绕过继续调用。 */
    public static class BudgetExceededException extends RuntimeException {
        public BudgetExceededException(String message) {
            super(message);
        }
    }

    private final JdbcTemplate jdbcTemplate;

    public AiBudgetService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public static String projectScope(long projectId) {
        return "project:" + projectId;
    }

    public static String userScope(String userId) {
        return "user:" + userId;
    }

    public static String taskScope(String taskType) {
        return "task:" + taskType;
    }

    public static String globalScope() {
        return "global";
    }

    /**
     * 预留预算。{@code tokensUpperBound} 必须来自实际的保守估算——无法给出时调用方
     * 必须先阻断（本方法拒绝 {@code tokensUpperBound < 0}）。
     */
    public Reservation reserve(String scopeKey, LocalDate windowDay, BudgetLimit limit,
                               long tokensUpperBound, int calls, String attemptRef) {
        if (scopeKey == null || scopeKey.isBlank() || windowDay == null || limit == null) {
            throw new IllegalArgumentException("scope/window/limit required");
        }
        if (tokensUpperBound < 0 || calls < 0) {
            throw new IllegalArgumentException("reservation bound required:: 无法给出上界时不得预留（fail-closed）");
        }
        long tokens = Math.max(tokensUpperBound, 1);
        int callCount = Math.max(calls, 1);
        ensureRow(scopeKey, windowDay, limit);

        int updated = jdbcTemplate.update(
            "update ai_budget_ledger set reserved_tokens = reserved_tokens + ?, reserved_calls = reserved_calls + ?,"
                + " updated_at = ? where scope_key = ? and window_day = ?"
                + " and reserved_tokens + ? <= limit_tokens and reserved_calls + ? <= limit_calls",
            tokens, callCount, java.sql.Timestamp.from(Instant.now()), scopeKey, java.sql.Date.valueOf(windowDay),
            tokens, callCount);
        if (updated == 0) {
            throw new BudgetExceededException(
                "budget_exceeded: " + scopeKey + " " + windowDay + " 需要 tokens=" + tokens + " calls=" + callCount
                    + "，预算不足（已预留 " + reservedOrZero(scopeKey, windowDay, "reserved_tokens") + "/" + limit.limitTokens()
                    + " tokens）");
        }
        String reservationId = "budget-" + UUID.randomUUID();
        jdbcTemplate.update("""
            insert into ai_budget_reservations(
              reservation_id, scope_key, window_day, reserved_tokens, reserved_calls,
              usage_known, pricing_mode, state, attempt_ref, created_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            reservationId, scopeKey, java.sql.Date.valueOf(windowDay), tokens, callCount,
            false, PricingMode.UNKNOWN_PRICE_CONSERVATIVE.name(), "RESERVED",
            attemptRef == null ? "" : attemptRef, java.sql.Timestamp.from(Instant.now()));
        return new Reservation(reservationId, scopeKey, windowDay, tokens, callCount);
    }

    /**
     * 结算。usageKnown=false 或 pricingMode=UNKNOWN_PRICE_CONSERVATIVE 时按预留额全额结算
     * （未知绝不等于免费）；已知则按实际结算并把差额退回。
     */
    public SettleOutcome settle(String reservationId, long actualTokens, int actualCalls,
                                boolean usageKnown, PricingMode pricingMode) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "select * from ai_budget_reservations where reservation_id = ?", reservationId);
        if (rows.isEmpty()) {
            return SettleOutcome.NOT_FOUND;
        }
        Map<String, Object> row = rows.get(0);
        if ("SETTLED".equals(String.valueOf(row.get("state")))) {
            return SettleOutcome.ALREADY_SETTLED;
        }
        long reservedTokens = ((Number) row.get("reserved_tokens")).longValue();
        int reservedCalls = ((Number) row.get("reserved_calls")).intValue();
        boolean conservative = !usageKnown || pricingMode == PricingMode.UNKNOWN_PRICE_CONSERVATIVE;
        long settledTokens = conservative ? reservedTokens : Math.max(actualTokens, 0);
        int settledCalls = conservative ? reservedCalls : Math.max(actualCalls, 0);
        // 保守结算再多也不超过预留（避免把未知放大成二次计费）。
        settledTokens = Math.min(settledTokens, reservedTokens);
        settledCalls = Math.min(settledCalls, reservedCalls);

        int updated = jdbcTemplate.update(
            "update ai_budget_reservations set state = 'SETTLED', settled_tokens = ?, settled_calls = ?,"
                + " usage_known = ?, pricing_mode = ?, settled_at = ? where reservation_id = ? and state = 'RESERVED'",
            settledTokens, settledCalls, usageKnown && !conservative, pricingMode.name(),
            java.sql.Timestamp.from(Instant.now()), reservationId);
        if (updated == 0) {
            return SettleOutcome.ALREADY_SETTLED;
        }
        jdbcTemplate.update(
            "update ai_budget_ledger set reserved_tokens = reserved_tokens - ?, reserved_calls = reserved_calls - ?,"
                + " settled_tokens = settled_tokens + ?, settled_calls = settled_calls + ?, updated_at = ?"
                + " where scope_key = ? and window_day = ?",
            reservedTokens, reservedCalls, settledTokens, settledCalls, java.sql.Timestamp.from(Instant.now()),
            String.valueOf(row.get("scope_key")), row.get("window_day"));
        return SettleOutcome.SETTLED;
    }

    /**
     * 释放预留：多 scope 预留中后续 scope 被拒时，把先前成功的预留原样退回
     * （不结算、不计费——调用根本没有发生）。
     */
    public boolean release(String reservationId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "select * from ai_budget_reservations where reservation_id = ?", reservationId);
        if (rows.isEmpty() || !"RESERVED".equals(String.valueOf(rows.get(0).get("state")))) {
            return false;
        }
        Map<String, Object> row = rows.get(0);
        jdbcTemplate.update(
            "update ai_budget_reservations set state = 'RELEASED', pricing_mode = pricing_mode,"
                + " settled_tokens = 0, settled_calls = 0, settled_at = ? where reservation_id = ? and state = 'RESERVED'",
            java.sql.Timestamp.from(Instant.now()), reservationId);
        jdbcTemplate.update(
            "update ai_budget_ledger set reserved_tokens = reserved_tokens - ?, reserved_calls = reserved_calls - ?,"
                + " updated_at = ? where scope_key = ? and window_day = ?",
            ((Number) row.get("reserved_tokens")).longValue(), ((Number) row.get("reserved_calls")).intValue(),
            java.sql.Timestamp.from(Instant.now()), String.valueOf(row.get("scope_key")), row.get("window_day"));
        return true;
    }

    /** 只读视图：某 scope/day 的已预留/已结算（供诊断与测试）。 */
    public Map<String, Object> snapshot(String scopeKey, LocalDate windowDay) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "select * from ai_budget_ledger where scope_key = ? and window_day = ?",
            scopeKey, java.sql.Date.valueOf(windowDay));
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private void ensureRow(String scopeKey, LocalDate windowDay, BudgetLimit limit) {
        jdbcTemplate.update(
            "insert into ai_budget_ledger(scope_key, window_day, reserved_tokens, settled_tokens,"
                + " reserved_calls, settled_calls, limit_tokens, limit_calls, updated_at)"
                + " values (?, ?, 0, 0, 0, 0, ?, ?, ?)"
                + " on duplicate key update limit_tokens = values(limit_tokens), limit_calls = values(limit_calls)",
            scopeKey, java.sql.Date.valueOf(windowDay), limit.limitTokens(), limit.limitCalls(),
            java.sql.Timestamp.from(Instant.now()));
    }

    private long reservedOrZero(String scopeKey, LocalDate windowDay, String column) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "select " + column + " from ai_budget_ledger where scope_key = ? and window_day = ?",
            scopeKey, java.sql.Date.valueOf(windowDay));
        return rows.isEmpty() || rows.get(0).get(column) == null
            ? 0L : ((Number) rows.get(0).get(column)).longValue();
    }
}
