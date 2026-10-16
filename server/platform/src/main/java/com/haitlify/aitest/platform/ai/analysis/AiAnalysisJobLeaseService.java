package com.haitlify.aitest.platform.ai.analysis;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.haitlify.aitest.platform.ai.event.AiRunEvent;
import com.haitlify.aitest.platform.ai.event.AiRunEventService;

/**
 * 持久调度契约的执行层（T-Review-4）：在既有 CAS 领取 + fencing token 基础上补齐
 * 恢复扫描、停机停领与可复用的分片续跑查询。所有写路径以 (owner_id, fencing_token) 为围栏，
 * 过期租约可被新 owner 接管（token 递增），旧 owner 的后续写入会被条件更新拒绝。
 */
@Component
public class AiAnalysisJobLeaseService {

    private static final Logger log = LoggerFactory.getLogger(AiAnalysisJobLeaseService.class);

    private static final int CLAIM_CANDIDATE_LIMIT = 10;

    private final JdbcTemplate jdbcTemplate;
    private final AiRunEventService eventService;
    private final AiSchedulerPolicy policy;
    private final Clock clock;
    private final String workerId;
    private final TransactionTemplate transactionTemplate;
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final ScheduledExecutorService heartbeatExecutor = Executors.newScheduledThreadPool(1, runnable -> {
        Thread thread = new Thread(runnable, "ai-analysis-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    @Autowired
    public AiAnalysisJobLeaseService(
        JdbcTemplate jdbcTemplate,
        AiRunEventService eventService,
        AiSchedulerPolicy policy,
        Clock clock
    ) {
        this(jdbcTemplate, eventService, policy, clock, "analysis-worker-" + UUID.randomUUID());
    }

    public AiAnalysisJobLeaseService(
        JdbcTemplate jdbcTemplate,
        AiRunEventService eventService,
        AiSchedulerPolicy policy,
        Clock clock,
        String workerId
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.eventService = eventService;
        this.policy = policy;
        this.clock = clock;
        this.workerId = workerId == null || workerId.isBlank() ? "analysis-worker-" + UUID.randomUUID() : workerId;
        this.transactionTemplate = new TransactionTemplate(new JdbcTransactionManager(jdbcTemplate.getDataSource()));
        // 领取事务用 READ COMMITTED：项目行锁负责串行化，语句级快照保证门查询必见先提交者的租约
        // （MySQL 默认 REPEATABLE READ 下，等锁方的旧快照会看不见先提交的租约，导致门盲判）。
        this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        // 第三十轮补审 2 C4-3：claim() 若被外部事务包裹，默认传播会静默 join（内层 RC 失效 + 行锁持有到外层提交）；
        // REQUIRES_NEW 保证领取始终运行在自己的短事务里（RC 生效、锁只持有毫秒级）。
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public String workerId() {
        return workerId;
    }

    /** 领取下一个可执行任务：FIFO（created_at, id）+ 项目级并发门；停机后拒绝领取。 */
    public ClaimResult claimNextJob() {
        if (stopping.get()) {
            return new ClaimResult(ClaimOutcome.STOPPING, null, null);
        }
        List<Map<String, Object>> candidates = jdbcTemplate.queryForList("""
            select r.* from ai_analysis_runs r
            where r.status = 'queued' and r.worker_status = 'idle'
              and not exists (
                select 1 from ai_analysis_worker_leases held
                where held.run_id = r.run_id and held.owner_id = ?
              )
              and (
                select count(*) from ai_analysis_runs busy
                where busy.project_id = r.project_id and busy.id <> r.id and busy.worker_status = 'running'
              ) < ?
            order by r.created_at asc, r.id asc
            limit %d
            """.formatted(CLAIM_CANDIDATE_LIMIT), workerId, policy.maxActiveRunsPerProject());
        boolean sawContendedCandidate = false;
        for (Map<String, Object> run : candidates) {
            if (stopping.get()) {
                return new ClaimResult(ClaimOutcome.STOPPING, null, null);
            }
            Lease lease = claim((String) run.get("RUN_ID"));
            if (lease != null) {
                return new ClaimResult(ClaimOutcome.CLAIMED, lease, run);
            }
            sawContendedCandidate = true;
        }
        return new ClaimResult(sawContendedCandidate ? ClaimOutcome.RUN_BUSY : ClaimOutcome.IDLE, null, null);
    }

    /** CAS 领取：过期租约或本人持有可被接管（fencing+1）；被他人有效持有时返回 null。
     *  第三十轮修复：停机期间拒绝；`cancelled`/`succeeded` 状态一律拒绝（生产派发与 /resume 都必须走这里）；
     *  项目级并发门在领取时校验（含「已领取未及标记 running」的窗口：以新鲜租约视为占用）。
     *  第三十轮补审 7 C-1：默认按「自动路径」语义拒绝 failed（人工重跑走 claim(runId, true)）。 */
    public Lease claim(String runId) {
        return claim(runId, false);
    }

    public Lease claim(String runId, boolean allowFailed) {
        if (stopping.get()) {
            log.debug("claim refused for run {}: worker is stopping", runId);
            return null;
        }
        List<String> statuses = jdbcTemplate.queryForList(
            "select status from ai_analysis_runs where run_id = ?", String.class, runId);
        if (statuses.isEmpty()) {
            log.debug("claim refused for run {}: run not found", runId);
            return null;
        }
        if ("cancelled".equals(statuses.getFirst()) || "succeeded".equals(statuses.getFirst())) {
            log.debug("claim refused for run {}: terminal status {}", runId, statuses.getFirst());
            return null;
        }
        if (!allowFailed && "failed".equals(statuses.getFirst())) {
            log.debug("claim refused for run {}: failed runs are only retried via resume", runId);
            return null;
        }
        Instant now = clock.instant();
        // 第三十轮补审 C1：门判定与 CAS 必须原子（并发同项目 claim 曾复现双发放）。
        // 方案：在短事务内先 FOR UPDATE 锁定该项目行（跨进程串行化同项目领取），再做门判定 + CAS。
        return transactionTemplate.execute(status -> doClaimUnderProjectLock(runId, now, allowFailed));
    }

    private Lease doClaimUnderProjectLock(String runId, Instant now, boolean allowFailed) {
        // 第三十轮补审 2 C4-2：锁等待/门查询异常（如行锁超时）一律优雅拒绝（返回 null），不得抛出
        // CannotAcquireLockException 让 /resume 变 500、dispatcher 任务被 Future 吞掉。
        try {
            List<Long> projectIds = jdbcTemplate.queryForList(
                "select project_id from ai_analysis_runs where run_id = ?", Long.class, runId);
            if (!projectIds.isEmpty()) {
                jdbcTemplate.queryForList("select id from projects where id = ? for update", projectIds.getFirst());
            }
            // 在锁内复核 failed（第三十轮补审 7 C-1：guard 读之后、领取之前的翻转在此被拦截）。
            List<String> lockedStatuses = jdbcTemplate.queryForList(
                "select status from ai_analysis_runs where run_id = ?", String.class, runId);
            if (!allowFailed && !lockedStatuses.isEmpty() && "failed".equals(lockedStatuses.getFirst())) {
                log.debug("claim refused for run {} inside the lock: run failed during the window", runId);
                return null;
            }
            Instant until = now.plus(policy.leaseTtl());
            if (!projectIds.isEmpty()) {
                Integer busy = jdbcTemplate.queryForObject("""
                    select count(*) from ai_analysis_runs other
                    where other.project_id = ? and other.run_id <> ?
                      and (other.worker_status = 'running'
                           or exists (select 1 from ai_analysis_worker_leases l
                                      where l.run_id = other.run_id and l.lease_until >= ?))
                    """, Integer.class, projectIds.getFirst(), runId, Timestamp.from(now));
                if (busy != null && busy >= policy.maxActiveRunsPerProject()) {
                    log.debug("claim refused for run {}: project gate busy ({} active)", runId, busy);
                    return null;
                }
            }
            int updated = jdbcTemplate.update(
                "update ai_analysis_worker_leases set owner_id = ?, lease_until = ?, fencing_token = fencing_token + 1, updated_at = ? where run_id = ? and (lease_until < ? or owner_id = ?)",
                workerId, Timestamp.from(until), Timestamp.from(now), runId, Timestamp.from(now), workerId);
            if (updated == 0) {
                jdbcTemplate.update(
                    "insert into ai_analysis_worker_leases(run_id, owner_id, lease_until, fencing_token, updated_at) values (?, ?, ?, 1, ?)",
                    runId, workerId, Timestamp.from(until), Timestamp.from(now));
            }
            Map<String, Object> row = jdbcTemplate.queryForMap(
                "select fencing_token from ai_analysis_worker_leases where run_id = ? and owner_id = ?", runId, workerId);
            return new Lease(runId, workerId, ((Number) row.get("FENCING_TOKEN")).longValue());
        } catch (Exception exception) {
            // 第三十轮补审 4 C-3：拒绝原因至少可经日志追踪（/resume 与轮询对外统一输出 busy）。
            log.debug("claim refused for run {}: {}", runId, exception.toString());
            return null;
        }
    }

    /** 心跳续租：仅当 (owner, token, 未过期) 仍成立时成功。 */
    public boolean renew(Lease lease) {
        if (lease == null) {
            return false;
        }
        Instant now = clock.instant();
        Instant until = now.plus(policy.leaseTtl());
        int updated = jdbcTemplate.update(
            "update ai_analysis_worker_leases set lease_until = ?, updated_at = ? where run_id = ? and owner_id = ? and fencing_token = ?",
            Timestamp.from(until), Timestamp.from(now), lease.runId(), lease.ownerId(), lease.fencingToken());
        return updated == 1;
    }

    /** 非抛出式租约检查：写入前调用，防止旧 fencing token 继续落库。 */
    public boolean isLeaseHeld(Lease lease) {
        if (lease == null) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from ai_analysis_worker_leases where run_id = ? and owner_id = ? and fencing_token = ? and lease_until >= ?",
            Integer.class, lease.runId(), lease.ownerId(), lease.fencingToken(), Timestamp.from(clock.instant()));
        return count != null && count == 1;
    }

    /** 正常完成后的释放：删除本人名下的租约行（一次干净释放执行者的最后动作）。 */
    public void release(Lease lease) {
        if (lease == null) {
            return;
        }
        jdbcTemplate.update(
            "delete from ai_analysis_worker_leases where run_id = ? and owner_id = ? and fencing_token = ?",
            lease.runId(), lease.ownerId(), lease.fencingToken());
    }

    /**
     * 恢复扫描（启动时执行一次 + 按策略间隔周期执行）：
     * ① 把「运行中但租约已过期/缺失」的任务重新排队，保留续跑位置并留下可重放事件；
     * ② 把「已重排（存在 lease_recovered 事件）但仍 queued/idle 且无租约」的孤儿重新纳入派发
     *    （第三十轮补审 2 判定级 2：恢复派发被项目门/停机拒绝后的任务此前无人再管，永久 stranded）；
     * 返回的清单供调用方（调度器）逐一派发。持有有效租约的任务不受影响。
     */
    public List<String> recoverExpiredRuns() {
        Instant now = clock.instant();
        if (now == null) {
            // 防御：调度器在 mock/未就绪时钟的上下文中运行时不得误判（测试上下文由 recovery-enabled=false 整体关闭）。
            return List.of();
        }
        List<Map<String, Object>> stale = jdbcTemplate.queryForList("""
            select r.run_id, r.current_chunk_id, r.resume_from_chunk_id from ai_analysis_runs r
            where r.status = 'running' and r.worker_status = 'running'
              and (
                exists (select 1 from ai_analysis_worker_leases l where l.run_id = r.run_id and l.lease_until < ?)
                or not exists (select 1 from ai_analysis_worker_leases l where l.run_id = r.run_id)
              )
            """, Timestamp.from(now));
        List<String> recovered = new ArrayList<>();
        for (Map<String, Object> run : stale) {
            String runId = (String) run.get("RUN_ID");
            String resumeFrom = blankToDefault((String) run.get("RESUME_FROM_CHUNK_ID"), (String) run.get("CURRENT_CHUNK_ID"));
            int updated = jdbcTemplate.update("""
                update ai_analysis_runs set status = 'queued', worker_status = 'idle',
                  resume_from_chunk_id = ?, updated_at = ?
                where run_id = ? and status = 'running' and worker_status = 'running'
                  and (exists (select 1 from ai_analysis_worker_leases l where l.run_id = ai_analysis_runs.run_id and l.lease_until < ?)
                       or not exists (select 1 from ai_analysis_worker_leases l where l.run_id = ai_analysis_runs.run_id))
                """, resumeFrom == null ? "" : resumeFrom, Timestamp.from(now), runId, Timestamp.from(now));
            if (updated == 1) {
                eventService.emit(new AiRunEvent(
                    runId, now, "lease_recovered", "warn", "检测到过期租约，任务已重新排队等待恢复", null, 0, null, "", "", "", 0L, "LEASE_RECOVERED", "", 0L));
                recovered.add(runId);
            }
        }
        // ② 孤儿重派：两类任务——(a) 已重排（有 lease_recovered 事件）但仍无人领取；
        // (b) 无事件、但创建后超过一个恢复周期仍 queued/idle（首派发被项目门/停机静默拒绝；
        //     第三十轮补审 5 C-1）。谓词统一按「无新鲜租约」判定：正常崩溃态残留的过期租约行
        // 同样必须持续回到派发清单（第三十轮补审 4 C-1；claim 的 CAS 会替换过期行）。
        List<String> stranded = jdbcTemplate.queryForList("""
            select r.run_id from ai_analysis_runs r
            where r.status = 'queued' and r.worker_status = 'idle'
              and not exists (select 1 from ai_analysis_worker_leases l where l.run_id = r.run_id and l.lease_until >= ?)
              and (
                exists (select 1 from ai_run_events e where e.run_id = r.run_id and e.stage = 'lease_recovered')
                or r.created_at < ?
              )
            """, String.class, Timestamp.from(now), Timestamp.from(now.minus(policy.recoveryInterval())));
        for (String runId : stranded) {
            if (!recovered.contains(runId)) {
                recovered.add(runId);
            }
        }
        return recovered;
    }

    /** 分片续跑查询（与 Worker 共用同一谓词）：已成功的分片绝不重复处理，失败分片允许重试。 */
    public List<Map<String, Object>> processableChunks(long runPk, String resumeFromChunkId) {
        if (resumeFromChunkId == null || resumeFromChunkId.isBlank()) {
            return jdbcTemplate.queryForList("""
                select * from ai_analysis_chunks
                where run_id = ? and status in ('planned', 'failed')
                order by id asc
                """, runPk);
        }
        Long resumePk = jdbcTemplate.queryForObject(
            "select id from ai_analysis_chunks where run_id = ? and planned_chunk_id = ?",
            Long.class, runPk, resumeFromChunkId);
        return jdbcTemplate.queryForList("""
            select * from ai_analysis_chunks
            where run_id = ? and id >= ? and status in ('planned', 'failed')
            order by id asc
            """, runPk, resumePk);
    }

    /** 停机：立即停止领取新任务；在飞任务自行结束（受围栏保护）。 */
    public void stopClaiming() {
        stopping.set(true);
    }

    public boolean isStopping() {
        return stopping.get();
    }

    /**
     * 心跳：按策略间隔续租并在租约有效时维持 run 的心跳与事件。run 的心跳写入带围栏（owner+token）。
     * 续租连续失败两次才置位 leaseLost（第三十轮 C5：瞬时数据库故障不得立刻判死整条运行）。
     */
    public ScheduledFuture<?> startHeartbeat(
        String runId,
        String strategySnapshotId,
        int chunkTotal,
        Lease lease,
        AtomicBoolean leaseLost
    ) {
        long intervalSeconds = Math.max(1, policy.renewInterval().toSeconds());
        java.util.concurrent.atomic.AtomicInteger consecutiveFailures = new java.util.concurrent.atomic.AtomicInteger();
        return heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                if (!renew(lease)) {
                    if (consecutiveFailures.incrementAndGet() >= 2) {
                        leaseLost.set(true);
                    }
                    return;
                }
                consecutiveFailures.set(0);
                Instant now = clock.instant();
                if (now == null) {
                    return;
                }
                int updated = jdbcTemplate.update("""
                    update ai_analysis_runs set heartbeat_at = ?, updated_at = ?
                    where run_id = ? and status = ?
                      and exists (select 1 from ai_analysis_worker_leases l where l.run_id = ? and l.owner_id = ? and l.fencing_token = ?)
                    """,
                    Timestamp.from(now), Timestamp.from(now), runId, "running",
                    lease.runId(), lease.ownerId(), lease.fencingToken());
                if (updated > 0) {
                    eventService.emit(new AiRunEvent(runId, now, "run_heartbeat", "info", "AI 仍在处理，已等待模型响应", null, chunkTotal, null, "", "", "", 0L, "", strategySnapshotId, 0L));
                }
            } catch (Exception ignored) {
                if (consecutiveFailures.incrementAndGet() >= 2) {
                    leaseLost.set(true);
                }
                // A heartbeat must never interrupt the active analysis worker.
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdownHeartbeatExecutor() {
        heartbeatExecutor.shutdownNow();
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record Lease(String runId, String ownerId, long fencingToken) {}

    public enum ClaimOutcome {
        CLAIMED,
        IDLE,
        RUN_BUSY,
        STOPPING
    }

    public record ClaimResult(ClaimOutcome outcome, Lease lease, Map<String, Object> run) {}
}
