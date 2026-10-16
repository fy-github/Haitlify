package com.haitlify.aitest.platform.ai.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T06：两阶段 attempt 仓库——请求前 {@link #begin} 落库（status=pending），返回后
 * {@link #finalize} 独立收尾。两次写入都在 REQUIRES_NEW 事务里执行：业务事务回滚
 * <b>不得</b>抹掉 attempt 的错误记录（审计优先）。调用内容只存允许摘要（脱敏后）与
 * 请求 hash；密钥、Authorization 头与请求原文绝不落库。
 */
@Service
public class AiAttemptRepository {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_SUCCEEDED = "succeeded";
    public static final String STATUS_FAILED = "failed";

    /** 长 token（API key 形态，含 ./+/= 等常见分隔字符）在允许摘要里一律打码（C-10 边界外扩）。 */
    private static final Pattern SECRET_SHAPED = Pattern.compile("[A-Za-z0-9_\\-./+=]{24,}");

    public record Attempt(String attemptId, String jobId, String nodeKey, String runId, String requestHash,
                          String status, Integer httpStatus, String providerRequestId, String errorCategory,
                          String retryReason, boolean usageKnown, Integer inputTokens, Integer outputTokens,
                          Long ttfbMs, Long durationMs, String deploymentRevision, Instant startedAt,
                          Instant finishedAt) {}

    public record AttemptOutcome(String status, Integer httpStatus, String providerRequestId, String errorCategory,
                                 String retryReason, boolean usageKnown, Integer inputTokens, Integer outputTokens,
                                 Long ttfbMs, Long durationMs) {}

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate requiresNew;

    @org.springframework.beans.factory.annotation.Autowired
    public AiAttemptRepository(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 请求前保存：pending 行先于传输落库；摘要先行脱敏，原文/密钥绝不入库。 */
    public String begin(String jobId, String nodeKey, String runId, String requestSummary,
                        String routeSummary, String deploymentRevision) {
        Instant now = Instant.now();
        String attemptId = "attempt-" + UUID.randomUUID();
        requiresNew.executeWithoutResult(status -> jdbcTemplate.update(
            "insert into ai_runtime_attempts(attempt_id, job_id, node_key, run_id, request_hash,"
                + " request_summary_json, route_summary_json, status, http_status, provider_request_id,"
                + " error_category, retry_reason, usage_known, input_tokens, output_tokens, ttfb_ms, duration_ms,"
                + " deployment_revision, started_at, finished_at, created_at, updated_at)"
                + " values (?, ?, ?, ?, ?, ?, ?, ?, null, '', '', '', true, null, null, null, null, ?, ?, null, ?, ?)",
            attemptId, jobId == null ? "" : jobId, nodeKey == null ? "" : nodeKey, runId == null ? "" : runId,
            sha256(requestSummary == null ? "" : requestSummary),
            maskAllowedSummary(requestSummary), maskAllowedSummary(routeSummary),
            STATUS_PENDING, deploymentRevision == null ? "" : deploymentRevision,
            Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)));
        return attemptId;
    }

    /** 返回后独立收尾：只允许 pending → 终态一次，重复 finalize 被拒（返回 false）。 */
    public boolean finalize(String attemptId, AttemptOutcome outcome) {
        Instant now = Instant.now();
        Boolean updated = requiresNew.execute(status -> jdbcTemplate.update(
            "update ai_runtime_attempts set status = ?, http_status = ?, provider_request_id = ?,"
                + " error_category = ?, retry_reason = ?, usage_known = ?, input_tokens = ?, output_tokens = ?,"
                + " ttfb_ms = ?, duration_ms = ?, finished_at = ?, updated_at = ?"
                + " where attempt_id = ? and status = ?",
            outcome.status(), outcome.httpStatus(), outcome.providerRequestId() == null ? "" : outcome.providerRequestId(),
            outcome.errorCategory() == null ? "" : outcome.errorCategory(),
            outcome.retryReason() == null ? "" : outcome.retryReason(), outcome.usageKnown(),
            outcome.inputTokens(), outcome.outputTokens(), outcome.ttfbMs(), outcome.durationMs(),
            Timestamp.from(now), Timestamp.from(now), attemptId, STATUS_PENDING) == 1);
        return Boolean.TRUE.equals(updated);
    }

    public Optional<Attempt> find(String attemptId) {
        List<Attempt> attempts = jdbcTemplate.query(
            "select attempt_id, job_id, node_key, run_id, request_hash, status, http_status, provider_request_id,"
                + " error_category, retry_reason, usage_known, input_tokens, output_tokens, ttfb_ms, duration_ms,"
                + " deployment_revision, started_at, finished_at from ai_runtime_attempts where attempt_id = ?",
            (rs, rowNum) -> new Attempt(
                rs.getString("attempt_id"), rs.getString("job_id"), rs.getString("node_key"), rs.getString("run_id"),
                rs.getString("request_hash"), rs.getString("status"), (Integer) rs.getObject("http_status"),
                rs.getString("provider_request_id"), rs.getString("error_category"), rs.getString("retry_reason"),
                rs.getBoolean("usage_known"), (Integer) rs.getObject("input_tokens"),
                (Integer) rs.getObject("output_tokens"), (Long) rs.getObject("ttfb_ms"),
                (Long) rs.getObject("duration_ms"), rs.getString("deployment_revision"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant()),
            attemptId);
        return attempts.isEmpty() ? Optional.empty() : Optional.of(attempts.get(0));
    }

    public List<Attempt> listByJob(String jobId) {
        return jdbcTemplate.query(
            "select attempt_id, job_id, node_key, run_id, request_hash, status, http_status, provider_request_id,"
                + " error_category, retry_reason, usage_known, input_tokens, output_tokens, ttfb_ms, duration_ms,"
                + " deployment_revision, started_at, finished_at from ai_runtime_attempts"
                + " where job_id = ? order by id asc",
            (rs, rowNum) -> new Attempt(
                rs.getString("attempt_id"), rs.getString("job_id"), rs.getString("node_key"), rs.getString("run_id"),
                rs.getString("request_hash"), rs.getString("status"), (Integer) rs.getObject("http_status"),
                rs.getString("provider_request_id"), rs.getString("error_category"), rs.getString("retry_reason"),
                rs.getBoolean("usage_known"), (Integer) rs.getObject("input_tokens"),
                (Integer) rs.getObject("output_tokens"), (Long) rs.getObject("ttfb_ms"),
                (Long) rs.getObject("duration_ms"), rs.getString("deployment_revision"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant()),
            jobId);
    }

    // ------------------------------------------------------------------ helpers

    /** 允许摘要：截断到 1000 字符并把「像密钥的长 token」打码；空值存 null。 */
    static String maskAllowedSummary(String value) {
        if (value == null) {
            return null;
        }
        String truncated = value.length() > 1000 ? value.substring(0, 1000) : value;
        return SECRET_SHAPED.matcher(truncated).replaceAll("***");
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hashed) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
