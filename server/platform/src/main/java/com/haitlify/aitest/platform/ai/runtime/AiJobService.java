package com.haitlify.aitest.platform.ai.runtime;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * T06：持久化 runtime job 层——提交（幂等）、领取（CAS + 租约 + 围栏）、
 * 心跳续租、终态写回（围栏校验）、取消（晚到不损坏终态）、过期租约恢复与
 * 节点 checkpoint。语义与 T-R4 调度器契约一致（先 SELECT 候选再逐条 CAS，
 * 保持 H2/MySQL 双环境可执行），围栏令牌单调递增，过期持有者的写一律被拒。
 */
@Service
public class AiJobService {

    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_BLOCKED = "BLOCKED";

    public static final String NODE_PENDING = "PENDING";
    public static final String NODE_RUNNING = "RUNNING";
    public static final String NODE_SUCCEEDED = "SUCCEEDED";
    public static final String NODE_SKIPPED = "SKIPPED";
    public static final String NODE_BLOCKED = "BLOCKED";
    public static final String NODE_FAILED = "FAILED";

    public static final String SIDE_EFFECT_NONE = "none";
    public static final String SIDE_EFFECT_UNKNOWN = "unknown";

    public enum CancelOutcome { CANCELLED, ALREADY_FINISHED, NOT_FOUND }

    public record AiJob(String jobId, String jobType, Long projectId, String actorId, String idempotencyKey,
                        String inputHash, String status, String leaseOwner, Instant leaseUntil, long fencingToken,
                        int attemptCount, String deploymentRevision, String legacyRunRef, String errorSummary,
                        Instant queuedAt, Instant startedAt, Instant firstTokenAt, Instant finishedAt) {}

    public record AiJobNode(String jobId, String nodeKey, int nodeOrder, String status, String sideEffect,
                            String attemptRef, String outputRef, String errorSummary,
                            Instant startedAt, Instant finishedAt) {}

    private final JdbcTemplate jdbcTemplate;
    private final tools.jackson.databind.ObjectMapper objectMapper;
    private final Clock clock;
    private final org.springframework.transaction.support.TransactionTemplate freshRead;

    @org.springframework.beans.factory.annotation.Autowired
    public AiJobService(JdbcTemplate jdbcTemplate, tools.jackson.databind.ObjectMapper objectMapper,
                        org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this(jdbcTemplate, objectMapper, Clock.systemUTC(), transactionManager);
    }

    AiJobService(JdbcTemplate jdbcTemplate, tools.jackson.databind.ObjectMapper objectMapper, Clock clock) {
        this(jdbcTemplate, objectMapper, clock, null);
    }

    AiJobService(JdbcTemplate jdbcTemplate, tools.jackson.databind.ObjectMapper objectMapper, Clock clock,
                 org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
        if (transactionManager == null) {
            this.freshRead = null;
        } else {
            var template = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
            template.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            template.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
            this.freshRead = template;
        }
    }

    // ------------------------------------------------------------------ submit

    /**
     * 幂等提交：同一 idempotencyKey 重复提交返回既有 jobId；但 key 复用在不同
     * 类型/项目/输入上属调用方错误，fail-closed 抛冲突，绝不静默换单。
     */
    @Transactional
    public String submit(String jobType, Long projectId, String actorId, String idempotencyKey,
                         String inputHash, List<String> nodePlan, String deploymentRevision) {
        if (jobType == null || jobType.isBlank() || projectId == null || idempotencyKey == null
                || idempotencyKey.isBlank() || inputHash == null || inputHash.isBlank()
                || nodePlan == null || nodePlan.isEmpty()) {
            throw new IllegalArgumentException("job submit fields required");
        }
        // C-7：节点计划预校验——空/空白或重复节点键在入口 fail-closed，绝不下沉为
        // 数据库唯一键/非空约束异常。
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (String nodeKey : nodePlan) {
            if (nodeKey == null || nodeKey.isBlank()) {
                throw new IllegalArgumentException("node plan entries must be non-blank");
            }
            if (!seen.add(nodeKey)) {
                throw new IllegalArgumentException("node plan entries must be unique: " + nodeKey);
            }
        }
        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
            "select job_id, job_type, project_id, input_hash, status from ai_runtime_jobs where idempotency_key = ?",
            idempotencyKey);
        if (!existing.isEmpty()) {
            Map<String, Object> row = existing.get(0);
            boolean same = jobType.equals(row.get("job_type"))
                && projectId.equals(((Number) row.get("project_id")).longValue())
                && inputHash.equals(row.get("input_hash"));
            if (!same) {
                throw new IllegalStateException("idempotency_conflict for key " + idempotencyKey);
            }
            return (String) row.get("job_id");
        }
        Instant now = clock.instant();
        String jobId = "job-" + java.util.UUID.randomUUID();
        try {
            jdbcTemplate.update(
                "insert into ai_runtime_jobs(job_id, job_type, project_id, actor_id, idempotency_key, input_hash, status,"
                    + " lease_owner, lease_until, fencing_token, attempt_count, deployment_revision, legacy_run_ref,"
                    + " node_plan_json, error_summary, queued_at, created_at, updated_at)"
                    + " values (?, ?, ?, ?, ?, ?, ?, '', null, 0, 0, ?, null, ?, '', ?, ?, ?)",
                jobId, jobType, projectId, actorId, idempotencyKey, inputHash, STATUS_QUEUED,
                deploymentRevision == null ? "" : deploymentRevision,
                writeJson(nodePlan), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        } catch (org.springframework.dao.DuplicateKeyException concurrentSubmit) {
            // C-2（round-2 F1）：并发同 key 提交竞态——另一线程已插入同 key。重查必须
            // 在「全新事务」里执行：本方法自身 @Transactional，同一 REPEATABLE READ
            // 事务的快照看不到刚提交的赢家行（复核确定性探针证实），会重抛冲突。
            // 新事务 + READ_COMMITTED 才能读到赢家；字段不一致才 fail-closed。
            // R3-2：池饱和导致新事务不可得时 fail-closed 重抛原冲突（快速 409），
            // 绝不阻塞至连接超时后 500。
            List<Map<String, Object>> raced;
            try {
                raced = freshRead != null
                    ? freshRead.execute(status -> jdbcTemplate.queryForList(
                        "select job_id, job_type, project_id, input_hash from ai_runtime_jobs where idempotency_key = ?",
                        idempotencyKey))
                    : jdbcTemplate.queryForList(
                        "select job_id, job_type, project_id, input_hash from ai_runtime_jobs where idempotency_key = ?",
                        idempotencyKey);
            } catch (RuntimeException freshReadUnavailable) {
                if (freshReadUnavailable instanceof org.springframework.transaction.CannotCreateTransactionException) {
                    throw concurrentSubmit;
                }
                throw freshReadUnavailable;
            }
            if (raced != null && !raced.isEmpty()) {
                Map<String, Object> row = raced.get(0);
                boolean same = jobType.equals(row.get("job_type"))
                    && projectId.equals(((Number) row.get("project_id")).longValue())
                    && inputHash.equals(row.get("input_hash"));
                if (same) {
                    return (String) row.get("job_id");
                }
                throw new IllegalStateException("idempotency_conflict for key " + idempotencyKey);
            }
            throw concurrentSubmit;
        }
        int order = 0;
        for (String nodeKey : nodePlan) {
            jdbcTemplate.update(
                "insert into ai_runtime_job_nodes(job_id, node_key, node_order, status, side_effect, attempt_ref,"
                    + " output_ref, error_summary, started_at, finished_at, created_at, updated_at)"
                    + " values (?, ?, ?, ?, ?, '', '', '', null, null, ?, ?)",
                jobId, nodeKey, order++, NODE_PENDING, SIDE_EFFECT_NONE, Timestamp.from(now), Timestamp.from(now));
        }
        return jobId;
    }

    // ------------------------------------------------------------------- claim

    /**
     * 领取一个可执行 job：先按 FIFO 取候选，再逐条 CAS（防止双 worker 双领）。
     * 返回空表示当前没有可执行 job。租约上限由调用方决定；越界由调用方校验。
     */
    public Optional<AiJob> claim(String owner, Duration lease) {
        // C-11：租约边界 fail-closed——越界配置（0/负/超上限）不得放大成「即领即过期」。
        if (owner == null || owner.isBlank() || lease == null || lease.toMillis() <= 0 || lease.toSeconds() > 3600) {
            throw new IllegalArgumentException("lease out of bounds (0 < lease <= 3600s)");
        }
        Instant now = clock.instant();
        long leaseMs = lease.toMillis();
        long scanLimit = 16;
        List<Map<String, Object>> candidates = jdbcTemplate.queryForList(
            "select job_id from ai_runtime_jobs where status = ? and (lease_until is null or lease_until < ?)"
                + " order by queued_at, id limit " + scanLimit,
            STATUS_QUEUED, Timestamp.from(now));
        for (Map<String, Object> candidate : candidates) {
            String jobId = (String) candidate.get("job_id");
            int updated = jdbcTemplate.update(
                "update ai_runtime_jobs set status = ?, lease_owner = ?, lease_until = ?,"
                    + " fencing_token = fencing_token + 1, attempt_count = attempt_count + 1,"
                    + " started_at = coalesce(started_at, ?), updated_at = ?"
                    + " where job_id = ? and status = ? and (lease_until is null or lease_until < ?)",
                STATUS_RUNNING, owner, Timestamp.from(now.plusMillis(leaseMs)),
                Timestamp.from(now), Timestamp.from(now), jobId, STATUS_QUEUED, Timestamp.from(now));
            if (updated == 1) {
                return find(jobId);
            }
        }
        return Optional.empty();
    }

    /** 心跳续租：持有者 + 围栏令牌双双匹配才成功（过期持有者续不上）。 */
    public boolean heartbeat(String jobId, String owner, long fencingToken, Duration lease) {
        Instant now = clock.instant();
        return jdbcTemplate.update(
            "update ai_runtime_jobs set lease_until = ?, updated_at = ?"
                + " where job_id = ? and status = ? and lease_owner = ? and fencing_token = ?",
            Timestamp.from(now.plus(lease)), Timestamp.from(now), jobId, STATUS_RUNNING, owner, fencingToken) == 1;
    }

    // ---------------------------------------------------------------- finalize

    /** 围栏校验的终态写回：过期持有者（旧 fencing token）一律被拒。 */
    public boolean complete(String jobId, long fencingToken, String summary) {
        return finish(jobId, fencingToken, STATUS_SUCCEEDED, summary);
    }

    public boolean fail(String jobId, long fencingToken, String errorSummary) {
        return finish(jobId, fencingToken, STATUS_FAILED, errorSummary);
    }

    private boolean finish(String jobId, long fencingToken, String status, String summary) {
        Instant now = clock.instant();
        return jdbcTemplate.update(
            "update ai_runtime_jobs set status = ?, error_summary = ?, finished_at = ?, updated_at = ?"
                + " where job_id = ? and status = ? and fencing_token = ?",
            status, summary == null ? "" : summary, Timestamp.from(now), Timestamp.from(now),
            jobId, STATUS_RUNNING, fencingToken) == 1;
    }

    /** 记录 TTFB（首个流式 token 时刻；已被别人推进过则不覆盖）。 */
    public boolean markFirstToken(String jobId, long fencingToken) {
        Instant now = clock.instant();
        return jdbcTemplate.update(
            "update ai_runtime_jobs set first_token_at = ?, updated_at = ?"
                + " where job_id = ? and status = ? and fencing_token = ? and first_token_at is null",
            Timestamp.from(now), Timestamp.from(now), jobId, STATUS_RUNNING, fencingToken) == 1;
    }

    public boolean linkLegacyRun(String jobId, String legacyRunRef) {
        return jdbcTemplate.update(
            "update ai_runtime_jobs set legacy_run_ref = ?, updated_at = ? where job_id = ?",
            legacyRunRef, Timestamp.from(clock.instant()), jobId) == 1;
    }

    // ------------------------------------------------------------------ cancel

    /**
     * 用户取消。未开始 ⇒ 直接 CANCELLED；运行中 ⇒ 保持 RUNNING，由持有者观察到
     * 取消后按围栏写回（本方法把取消意图落库为节点级 ERROR_SUMMARY 之外的
     * status 保持，实际停机由 worker 检查 {@link #isCancelRequested} 完成）。
     * 已终态 ⇒ ALREADY_FINISHED（晚到取消不得损坏终态）。
     */
    @Transactional
    public CancelOutcome requestCancel(String jobId, String requestedBy) {
        Instant now = clock.instant();
        int queuedCancelled = jdbcTemplate.update(
            "update ai_runtime_jobs set status = ?, error_summary = ?, finished_at = ?, updated_at = ?"
                + " where job_id = ? and status = ?",
            STATUS_CANCELLED, "用户取消（" + requestedBy + "）", Timestamp.from(now), Timestamp.from(now),
            jobId, STATUS_QUEUED);
        if (queuedCancelled == 1) {
            return CancelOutcome.CANCELLED;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "select status from ai_runtime_jobs where job_id = ?", jobId);
        if (rows.isEmpty()) {
            return CancelOutcome.NOT_FOUND;
        }
        String status = (String) rows.get(0).get("status");
        if (STATUS_RUNNING.equals(status)) {
            // C-5：条件更新——若另一侧已收尾（SUCCEEDED/FAILED/CANCELLED），本写不得
            // 把「取消请求」写进终态行。
            int flagged = jdbcTemplate.update(
                "update ai_runtime_jobs set error_summary = ?, updated_at = ? where job_id = ? and status = ?",
                "取消请求（" + requestedBy + "）", Timestamp.from(now), jobId, STATUS_RUNNING);
            if (flagged == 1) {
                return CancelOutcome.CANCELLED;
            }
            // F3（round-2）：快照读 RUNNING 后、条件写之前被 reclaim 抢回 QUEUED ⇒
            // 标记写落空但任务并未收尾。按「未启动取消」再试一次，绝不误报终态。
            int queuedRetryCancelled = jdbcTemplate.update(
                "update ai_runtime_jobs set status = ?, error_summary = ?, finished_at = ?, updated_at = ?"
                    + " where job_id = ? and status = ?",
                STATUS_CANCELLED, "用户取消（" + requestedBy + "）", Timestamp.from(now), Timestamp.from(now),
                jobId, STATUS_QUEUED);
            return queuedRetryCancelled == 1 ? CancelOutcome.CANCELLED : CancelOutcome.ALREADY_FINISHED;
        }
        return CancelOutcome.ALREADY_FINISHED;
    }

    /** worker 在节点边界检查：运行中且 error_summary 带取消请求 ⇒ 停机并按围栏写回取消。 */
    public boolean isCancelRequested(String jobId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "select status, error_summary from ai_runtime_jobs where job_id = ?", jobId);
        if (rows.isEmpty() || !STATUS_RUNNING.equals(rows.get(0).get("status"))) {
            return false;
        }
        String summary = (String) rows.get(0).get("error_summary");
        return summary != null && summary.startsWith("取消请求");
    }

    // ---------------------------------------------------------------- recovery

    /** 持有者观察到取消请求后的围栏写回（错误围栏拒绝，防止过期持有者覆盖新主）。 */
    public boolean cancel(String jobId, long fencingToken) {
         Instant now = clock.instant();
         return jdbcTemplate.update(
             "update ai_runtime_jobs set status = ?, finished_at = ?, updated_at = ?"
                 + " where job_id = ? and status = ? and fencing_token = ?",
             STATUS_CANCELLED, Timestamp.from(now), Timestamp.from(now),
             jobId, STATUS_RUNNING, fencingToken) == 1;
     }

     /** 过期租约恢复（JVM 重启/进程崩溃后调用）：接管过期 RUNNING job，围栏 +1，
     * 重排队为 QUEUED；但若被中断的持有者已收到取消请求（「取消请求」标记），
     * 恢复必须尊重取消意图——直接终态 CANCELLED，绝不复活（C-4）。
     * 节点级恢复由 {@link #reconcileNodesAfterRecovery} 完成：
     * 已完成节点跳过；未知副作用的 RUNNING 节点由调用方判定为不可重放，进入
     * BLOCKED 待人工判断（绝不猜测性重放）。
     */
    @Transactional
    public List<String> reclaimExpired() {
        Instant now = clock.instant();
        List<Map<String, Object>> expired = jdbcTemplate.queryForList(
            "select job_id from ai_runtime_jobs where status = ? and lease_until is not null and lease_until < ?"
                + " order by queued_at, id",
            STATUS_RUNNING, Timestamp.from(now));
        List<String> reclaimed = new ArrayList<>();
        for (Map<String, Object> row : expired) {
            String jobId = (String) row.get("job_id");
            int cancelled = jdbcTemplate.update(
                "update ai_runtime_jobs set status = ?, finished_at = ?, updated_at = ?"
                    + " where job_id = ? and status = ? and lease_until is not null and lease_until < ?"
                    + " and error_summary like '取消请求%'",
                STATUS_CANCELLED, Timestamp.from(now), Timestamp.from(now),
                jobId, STATUS_RUNNING, Timestamp.from(now));
            if (cancelled == 1) {
                reclaimed.add(jobId);
                continue;
            }
            int updated = jdbcTemplate.update(
                "update ai_runtime_jobs set status = ?, lease_owner = '', lease_until = null,"
                    + " fencing_token = fencing_token + 1, error_summary = '', updated_at = ?"
                    + " where job_id = ? and status = ? and lease_until is not null and lease_until < ?"
                    + " and error_summary not like '取消请求%'",
                STATUS_QUEUED, Timestamp.from(now), jobId, STATUS_RUNNING, Timestamp.from(now));
            if (updated == 1) {
                reclaimed.add(jobId);
            }
        }
        return reclaimed;
    }

    /**
     * 恢复后节点对账：RUNNING 节点被中断——无副作用/已知可重放 ⇒ 回到 PENDING；
     * 未知副作用 ⇒ BLOCKED（job 置 BLOCKED 待人工判断）。返回转移明细供审计。
     */
    @Transactional
    public List<String> reconcileNodesAfterRecovery(String jobId) {
        Instant now = clock.instant();
        List<String> transitions = new ArrayList<>();
        List<Map<String, Object>> runningNodes = jdbcTemplate.queryForList(
            "select node_key, side_effect from ai_runtime_job_nodes where job_id = ? and status = ?",
            jobId, NODE_RUNNING);
        for (Map<String, Object> node : runningNodes) {
            String nodeKey = (String) node.get("node_key");
            String sideEffect = (String) node.get("side_effect");
            if (SIDE_EFFECT_UNKNOWN.equals(sideEffect)) {
                jdbcTemplate.update(
                    "update ai_runtime_job_nodes set status = ?, error_summary = ?, updated_at = ?"
                        + " where job_id = ? and node_key = ? and status = ?",
                    NODE_BLOCKED, "中断时副作用未知，需人工判断", Timestamp.from(now), jobId, nodeKey, NODE_RUNNING);
                jdbcTemplate.update(
                    "update ai_runtime_jobs set status = ?, error_summary = ?, finished_at = ?, updated_at = ?"
                        + " where job_id = ? and status = ?",
                    STATUS_BLOCKED, "节点 " + nodeKey + " 副作用未知，需人工判断",
                    Timestamp.from(now), Timestamp.from(now), jobId, STATUS_QUEUED);
                transitions.add(nodeKey + ":BLOCKED");
            } else {
                jdbcTemplate.update(
                    "update ai_runtime_job_nodes set status = ?, started_at = null, updated_at = ?"
                        + " where job_id = ? and node_key = ? and status = ?",
                    NODE_PENDING, Timestamp.from(now), jobId, nodeKey, NODE_RUNNING);
                transitions.add(nodeKey + ":PENDING");
            }
        }
        return transitions;
    }

    // ------------------------------------------------------------------- nodes

    public List<AiJobNode> nodes(String jobId) {
        return jdbcTemplate.query(
            "select job_id, node_key, node_order, status, side_effect, attempt_ref, output_ref, error_summary,"
                + " started_at, finished_at from ai_runtime_job_nodes where job_id = ? order by node_order, id",
            (rs, rowNum) -> new AiJobNode(
                rs.getString("job_id"), rs.getString("node_key"), rs.getInt("node_order"), rs.getString("status"),
                rs.getString("side_effect"), rs.getString("attempt_ref"), rs.getString("output_ref"),
                rs.getString("error_summary"), toInstant(rs.getTimestamp("started_at")),
                toInstant(rs.getTimestamp("finished_at"))),
            jobId);
    }

    /**
     * C-3：节点写回全部带围栏——只有当前持有者（fencing_token 匹配）能推进节点状态，
     * 过期持有者在接管后既不能 complete 也不能 fail 任何节点。
     */
    public boolean startNode(String jobId, String nodeKey, String attemptRef, String sideEffect, long fencingToken) {
        Instant now = clock.instant();
        return jdbcTemplate.update(
            "update ai_runtime_job_nodes set status = ?, side_effect = ?, attempt_ref = ?, started_at = ?, updated_at = ?"
                + " where job_id = ? and node_key = ? and status = ?"
                + " and exists (select 1 from ai_runtime_jobs j where j.job_id = ? and j.fencing_token = ?)",
            NODE_RUNNING, sideEffect == null ? SIDE_EFFECT_NONE : sideEffect, attemptRef == null ? "" : attemptRef,
            Timestamp.from(now), Timestamp.from(now), jobId, nodeKey, NODE_PENDING, jobId, fencingToken) == 1;
    }

    public boolean completeNode(String jobId, String nodeKey, String outputRef, long fencingToken) {
        Instant now = clock.instant();
        return jdbcTemplate.update(
            "update ai_runtime_job_nodes set status = ?, output_ref = ?, finished_at = ?, updated_at = ?"
                + " where job_id = ? and node_key = ? and status = ?"
                + " and exists (select 1 from ai_runtime_jobs j where j.job_id = ? and j.fencing_token = ?)",
            NODE_SUCCEEDED, outputRef == null ? "" : outputRef, Timestamp.from(now), Timestamp.from(now),
            jobId, nodeKey, NODE_RUNNING, jobId, fencingToken) == 1;
    }

    public boolean failNode(String jobId, String nodeKey, String errorSummary, long fencingToken) {
        Instant now = clock.instant();
        return jdbcTemplate.update(
            "update ai_runtime_job_nodes set status = ?, error_summary = ?, finished_at = ?, updated_at = ?"
                + " where job_id = ? and node_key = ? and status = ?"
                + " and exists (select 1 from ai_runtime_jobs j where j.job_id = ? and j.fencing_token = ?)",
            NODE_FAILED, errorSummary == null ? "" : errorSummary, Timestamp.from(now), Timestamp.from(now),
            jobId, nodeKey, NODE_RUNNING, jobId, fencingToken) == 1;
    }

    public Optional<AiJob> find(String jobId) {
        List<AiJob> jobs = jdbcTemplate.query(
            "select job_id, job_type, project_id, actor_id, idempotency_key, input_hash, status, lease_owner,"
                + " lease_until, fencing_token, attempt_count, deployment_revision, legacy_run_ref, error_summary,"
                + " queued_at, started_at, first_token_at, finished_at from ai_runtime_jobs where job_id = ?",
            (rs, rowNum) -> new AiJob(
                rs.getString("job_id"), rs.getString("job_type"), rs.getLong("project_id"), rs.getString("actor_id"),
                rs.getString("idempotency_key"), rs.getString("input_hash"), rs.getString("status"),
                rs.getString("lease_owner"), toInstant(rs.getTimestamp("lease_until")),
                rs.getLong("fencing_token"), rs.getInt("attempt_count"), rs.getString("deployment_revision"),
                rs.getString("legacy_run_ref"), rs.getString("error_summary"),
                toInstant(rs.getTimestamp("queued_at")), toInstant(rs.getTimestamp("started_at")),
                toInstant(rs.getTimestamp("first_token_at")), toInstant(rs.getTimestamp("finished_at"))),
            jobId);
        return jobs.isEmpty() ? Optional.empty() : Optional.of(jobs.get(0));
    }

    // ------------------------------------------------------------------ helpers

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid job JSON", exception);
        }
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
