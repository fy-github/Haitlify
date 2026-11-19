package com.haitlify.aitest.platform.assistant.report;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 报告修订：只追加；同一 operation 的 revision 递增且唯一。 */
@Repository
public class AssistantReportRepository {

    public record ReportRow(String reportId, String operationId, int revision, String bodyJson, Instant createdAt,
                            Long generatedBy,
                            String decision, Long decidedBy, Instant decidedAt) {}

    private final JdbcTemplate jdbcTemplate;

    public AssistantReportRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int nextRevision(String operationId) {
        Integer max = jdbcTemplate.queryForObject(
            "select coalesce(max(revision), 0) from assistant_report where operation_id = ?",
            Integer.class, operationId);
        return (max == null ? 0 : max) + 1;
    }

    /**
     * 追加修订。取号与插入放在同一条语句里（{@code insert ... select max+1}），避免 select-then-insert 的竞态；
     * 万一仍撞唯一键（极少数并发），退避重试。
     */
    public ReportRow append(String operationId, String bodyJson, Long generatedBy) {
        for (int attempt = 1; attempt <= 3; attempt += 1) {
            String reportId = UUID.randomUUID().toString();
            try {
                jdbcTemplate.update(
                    "insert into assistant_report(report_id, operation_id, revision, body_json, generated_by, created_at)"
                        + " select ?, ?, coalesce(max(revision), 0) + 1, ?, ?, ? from assistant_report"
                        + " where operation_id = ?",
                    reportId, operationId, bodyJson, generatedBy, Timestamp.from(Instant.now()), operationId);
                return find(reportId).orElseThrow();
            } catch (DuplicateKeyException | org.springframework.dao.DeadlockLoserDataAccessException ex) {
                // 唯一键冲突与 MySQL gap-lock 死锁都按并发取号冲突处理，退避重试。
                if (attempt == 3) {
                    throw new IllegalStateException("报告修订写入冲突（并发生成）：" + operationId, ex);
                }
                try {
                    Thread.sleep(20L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("报告写入被中断", interrupted);
                }
            }
        }
        throw new IllegalStateException("unreachable");
    }

    public Optional<ReportRow> find(String reportId) {
        List<ReportRow> rows = jdbcTemplate.query(
            "select report_id, operation_id, revision, body_json, created_at, generated_by, decision, decided_by,"
                + " decided_at from assistant_report where report_id = ?",
            (rs, i) -> map(rs), reportId);
        return rows.stream().findFirst();
    }

    public Optional<ReportRow> find(String operationId, int revision) {
        List<ReportRow> rows = jdbcTemplate.query(
            "select report_id, operation_id, revision, body_json, created_at, generated_by, decision, decided_by,"
                + " decided_at from assistant_report where operation_id = ? and revision = ?",
            (rs, i) -> map(rs), operationId, revision);
        return rows.stream().findFirst();
    }

    /** 把决策写到指定修订上：谁、何时、接受还是驳回。 */
    public boolean markDecision(String reportId, String decision, Long decidedBy) {
        return jdbcTemplate.update(
            "update assistant_report set decision = ?, decided_by = ?, decided_at = ?"
                + " where report_id = ? and decision is null",
            decision, decidedBy, Timestamp.from(Instant.now()), reportId) == 1;
    }

    public Optional<ReportRow> latest(String operationId) {
        List<ReportRow> rows = jdbcTemplate.query(
            "select report_id, operation_id, revision, body_json, created_at, generated_by, decision, decided_by,"
                + " decided_at from assistant_report where operation_id = ? order by revision desc limit 1",
            (rs, i) -> map(rs), operationId);
        return rows.stream().findFirst();
    }

    private static ReportRow map(ResultSet rs) throws SQLException {
        long decidedByRaw = rs.getLong("decided_by");
        Long decidedBy = rs.wasNull() ? null : decidedByRaw;
        java.sql.Timestamp decidedAt = rs.getTimestamp("decided_at");
        return new ReportRow(rs.getString("report_id"), rs.getString("operation_id"), rs.getInt("revision"),
            rs.getString("body_json"), rs.getTimestamp("created_at").toInstant(), (Long) rs.getObject("generated_by"),
            rs.getString("decision"),
            decidedBy, decidedAt == null ? null : decidedAt.toInstant());
    }
}
