package com.haitlify.aitest.platform.ai.event;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends normalized run events to {@code ai_run_events} and reads them back ordered for the live
 * panel. Append-only; no foreign keys that could cascade. Each event carries the strategy snapshot
 * id so a visible model name is explainable via the call-record audit, without leaking secrets.
 */
@Service
@Transactional
public class AiRunEventService {

    private final JdbcTemplate jdbcTemplate;
    private final org.springframework.transaction.support.TransactionTemplate freshTransaction;
    /** 每个 run 的进程内发射监视器：单 JVM 内串行化 seq 分配；跨进程由新事务重取 seq + 唯一索引收口。 */
    private final java.util.concurrent.ConcurrentHashMap<String, Object> emitMonitors =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MONITOR_CAP = 4096;

    public AiRunEventService(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AiRunEventService(JdbcTemplate jdbcTemplate,
                             org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.freshTransaction = transactionManager == null ? null : freshTransactionTemplate(transactionManager);
    }

    private static org.springframework.transaction.support.TransactionTemplate freshTransactionTemplate(
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        var template = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        template.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }

    public void emit(AiRunEvent event) {
        Instant occurredAt = event.occurredAt() == null ? Instant.now() : event.occurredAt();
        Instant createdAt = Instant.now();
        String runId = blankToDefault(event.runId(), "");
        // T06：event_seq 是 run 内单调序列（afterSeq 补拉的最小单位）。MySQL 禁止
        // INSERT ... VALUES 里自引用目标表的子查询（error 1093），因此先独立取
        // 下一个 seq 再插入；并发写入由唯一索引 (run_id, event_seq) 收口，冲突重算，
        // 有界重试。
        org.springframework.dao.DataAccessException lastConflict = null;
        if (emitMonitors.size() > MONITOR_CAP) {
            // C-9：监视器上限——清空重建（清空瞬间的并发会让同 run 出现两个监视器，
            // 由唯一索引 + 新事务重试兜底；监视器只是减少冲突的加速器，非正确性依赖）。
            emitMonitors.clear();
        }
        Object monitor = emitMonitors.computeIfAbsent(runId, key -> new Object());
        synchronized (monitor) {
            for (int attempt = 0; attempt < 12; attempt++) {
                try {
                    // C-1：每次尝试都必须在全新事务里重取 seq——同一事务内 RE-SELECT
                    // 在 MySQL REPEATABLE READ 下命中固定快照，恒冲突（复核探针证实）。
                    Runnable attemptBody = () -> {
                        Integer nextSeq = jdbcTemplate.queryForObject(
                            "select coalesce(max(event_seq), 0) + 1 from ai_run_events where run_id = ?",
                            Integer.class, runId);
                        jdbcTemplate.update("""
                            insert into ai_run_events(
                              event_id, run_id, occurred_at, stage, severity, summary, chunk_index, chunk_total,
                              attempt_index, provider_id, provider_type, model, duration_ms, retry_reason,
                              strategy_snapshot_id, event_seq, created_at
                            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                            "evt-" + UUID.randomUUID(),
                            runId,
                            Timestamp.from(occurredAt),
                            blankToDefault(event.stage(), "info"),
                            blankToDefault(event.severity(), "info"),
                            clampSummary(blankToDefault(event.summary(), "")),
                            event.chunkIndex(),
                            event.chunkTotal(),
                            event.attemptIndex(),
                            blankToDefault(event.providerId(), ""),
                            blankToDefault(event.providerType(), ""),
                            blankToDefault(event.model(), ""),
                            event.durationMs(),
                            blankToDefault(event.retryReason(), ""),
                            blankToDefault(event.strategySnapshotId(), ""),
                            nextSeq,
                            Timestamp.from(createdAt)
                        );
                    };
                    if (freshTransaction != null) {
                        freshTransaction.executeWithoutResult(status -> attemptBody.run());
                    } else {
                        attemptBody.run();
                    }
                    return;
                } catch (org.springframework.dao.DataAccessException conflict) {
                    if (!(conflict instanceof org.springframework.dao.DuplicateKeyException)
                            && !(conflict instanceof org.springframework.dao.CannotAcquireLockException)
                            && !(conflict instanceof org.springframework.dao.DeadlockLoserDataAccessException)) {
                        throw conflict;
                    }
                    lastConflict = conflict;
                    try {
                        Thread.sleep(Math.min(10L * (attempt + 1), 200L));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw lastConflict;
    }

    public AiRunEventListResponse listByRun(String runId) {
        return listByRun(runId, null);
    }

    /** afterSeq 补拉：只取 event_seq 严格更大的事件（最终事件可重复获取）。 */
    public AiRunEventListResponse listByRun(String runId, Long afterSeq) {
        String sql = afterSeq == null
            ? "select * from ai_run_events where run_id = ? order by event_seq asc, id asc"
            : "select * from ai_run_events where run_id = ? and event_seq > ? order by event_seq asc, id asc";
        List<AiRunEvent> items = jdbcTemplate.query(
            sql,
            (rs, rowNum) -> new AiRunEvent(
                rs.getString("run_id"),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("stage"),
                rs.getString("severity"),
                rs.getString("summary"),
                (Integer) rs.getObject("chunk_index"),
                (Integer) rs.getObject("chunk_total"),
                (Integer) rs.getObject("attempt_index"),
                rs.getString("provider_id"),
                rs.getString("provider_type"),
                rs.getString("model"),
                rs.getLong("duration_ms"),
                rs.getString("retry_reason"),
                rs.getString("strategy_snapshot_id"),
                rs.getLong("event_seq")
            ),
            afterSeq == null ? new Object[] {runId} : new Object[] {runId, afterSeq});
        return new AiRunEventListResponse(items);
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** 兜底钳位：summary 列为 varchar(1024)；任何发射方传入超长诊断文本都不得让事件写入自爆。 */
    private static String clampSummary(String text) {
        if (text == null || text.length() <= 1000) return text;
        return text.substring(0, 1000) + "…(truncated)";
    }
}
