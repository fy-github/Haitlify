package com.haitlify.aitest.platform.ai.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.haitlify.aitest.platform.ai.event.AiRunEvent;
import com.haitlify.aitest.platform.ai.event.AiRunEventService;

import tools.jackson.databind.JsonNode;

/**
 * T06：runtime job worker。领取（CAS + 租约 + 围栏）→ 逐个节点执行（已完成节点
 * 一律跳过 ⇒ 重启恢复不重放）→ 节点边界检查取消请求 → 结果按围栏写回。
 * 失败快速收尾（job 级重试策略由消费方/后续任务定义，本层只保证审计与状态正确）。
 * JVM 重启后的恢复：{@link #recoverExpiredJobs()} 接管过期租约并按节点副作用对账
 * （未知副作用 ⇒ BLOCKED 待人工判断，绝不猜测性重放）。
 */
@Component
public class AiJobWorker {

    /** 节点输出引用（attemptId）之外的输入来源：由消费方按 jobType 提供（T07 接入分析流水线）。 */
    public interface AiJobInputProvider {
        JsonNode load(AiJobService.AiJob job);
    }

    public record WorkerResult(String outcome, String jobId, String detail) {
        static WorkerResult none() {
            return new WorkerResult("none", "", "");
        }
    }

    private final AiJobService jobs;
    private final AiRuntime runtime;
    private final AiRunEventService eventService;
    private final AiJobInputProvider inputProvider;
    private final boolean enabled;
    private final long leaseSeconds;

    @org.springframework.beans.factory.annotation.Autowired
    public AiJobWorker(AiJobService jobs, AiRuntime runtime, AiRunEventService eventService,
                       @Value("${app.ai.runtime.worker-enabled:false}") boolean enabled,
                       @Value("${app.ai.runtime.worker-lease-seconds:120}") long leaseSeconds) {
        this(jobs, runtime, eventService, job -> null, enabled, leaseSeconds);
    }

    AiJobWorker(AiJobService jobs, AiRuntime runtime, AiRunEventService eventService,
                AiJobInputProvider inputProvider, boolean enabled, long leaseSeconds) {
        if (enabled && (leaseSeconds <= 0 || leaseSeconds > 3600)) {
            // 配置 fail-fast：开启 worker 却给了越界租约，绝不在每个 tick 里抛错等被发现。
            throw new IllegalArgumentException("worker lease seconds out of bounds (0 < lease <= 3600)");
        }
        this.jobs = jobs;
        this.runtime = runtime;
        this.eventService = eventService;
        this.inputProvider = inputProvider;
        this.enabled = enabled;
        this.leaseSeconds = leaseSeconds;
    }

    @Scheduled(fixedDelayString = "${app.ai.runtime.worker-interval-ms:5000}")
    public void scheduledTick() {
        if (!enabled) {
            return;
        }
        recoverExpiredJobs();
        processOne();
    }

    /** 只在模型调用无副作用语义的 taskType 才声明 NONE；未知 taskType 保守声明 UNKNOWN
     * （中断后走 BLOCKED 人工判断而非盲重放；C-8：安全网在生产可达）。 */
    private static final java.util.Set<String> PURE_MODEL_TASK_TYPES = java.util.Set.of("requirement_analysis");

    /** 恢复扫描：过期租约接管 + 节点对账；BLOCKED 的单子发告警事件不重放。 */
    public List<String> recoverExpiredJobs() {
        List<String> reclaimed = jobs.reclaimExpired();
        for (String jobId : reclaimed) {
            AiJobService.AiJob job = jobs.find(jobId).orElseThrow();
            if (AiJobService.STATUS_CANCELLED.equals(job.status())) {
                // C-4：被中断前已收到取消请求 ⇒ 恢复尊重取消意图，直接终态。
                emit(jobId, "runtime_job_cancelled", "warn", "恢复时确认取消请求，任务保持取消", "CANCELLED");
                continue;
            }
            List<String> transitions = jobs.reconcileNodesAfterRecovery(jobId);
            if (jobs.find(jobId).orElseThrow().status().equals(AiJobService.STATUS_BLOCKED)) {
                emit(jobId, "runtime_job_blocked", "warn", "恢复时发现未知副作用节点，等待人工判断：" + transitions, "");
            } else {
                emit(jobId, "runtime_job_recovered", "warn", "检测到过期租约，任务已重新排队等待恢复", "LEASE_RECOVERED");
            }
        }
        return reclaimed;
    }

    /** 处理一个可领取任务；返回 none 表示当前没有可执行单。 */
    public WorkerResult processOne() {
        var claimed = jobs.claim("runtime-worker", Duration.ofSeconds(leaseSeconds));
        if (claimed.isEmpty()) {
            return WorkerResult.none();
        }
        AiJobService.AiJob job = claimed.get();
        String runId = job.legacyRunRef() == null || job.legacyRunRef().isBlank() ? job.jobId() : job.legacyRunRef();
        emit(runId, "runtime_job_started", "info", "任务已开始执行（节点计划 " + jobs.nodes(job.jobId()).size() + " 个）", "");

        for (AiJobService.AiJobNode node : jobs.nodes(job.jobId())) {
            // 重启/恢复后：已完成节点直接跳过（checkpoint 语义）。
            if (AiJobService.NODE_SUCCEEDED.equals(node.status()) || AiJobService.NODE_SKIPPED.equals(node.status())) {
                continue;
            }
            // 节点边界：观察到取消请求 ⇒ 围栏写回取消并停机。
            if (jobs.isCancelRequested(job.jobId())) {
                if (!jobs.cancel(job.jobId(), job.fencingToken())) {
                    emit(runId, "runtime_job_ownership_lost", "warn",
                        "取消写回被围栏拒绝，本持有者已过期，停机交还", "LEASE_LOST");
                    return new WorkerResult("lost", job.jobId(), "ownership_lost_at_" + node.nodeKey());
                }
                emit(runId, "runtime_job_cancelled", "warn", "用户取消，任务在节点边界停机", "CANCELLED");
                return new WorkerResult("cancelled", job.jobId(), "cancelled_at_" + node.nodeKey());
            }
            if (!jobs.startNode(job.jobId(), node.nodeKey(), "",
                    PURE_MODEL_TASK_TYPES.contains(job.jobType())
                        ? AiJobService.SIDE_EFFECT_NONE : AiJobService.SIDE_EFFECT_UNKNOWN,
                    job.fencingToken())) {
                continue;
            }
            AiRuntimeModels.AiTaskRequest request = buildRequest(job, node);
            AiRuntime.RuntimeExecution result = runtime.execute(job, node.nodeKey(), request);
            switch (result.status()) {
                case "succeeded" -> {
                    if (!jobs.completeNode(job.jobId(), node.nodeKey(), result.attemptId(), job.fencingToken())) {
                        // F4：围栏写失败 = 本持有者已过期（接管发生）。绝不发幻影成功。
                        emit(runId, "runtime_job_ownership_lost", "warn",
                            "节点写回被围栏拒绝，本持有者已过期，停机交还", "LEASE_LOST");
                        return new WorkerResult("lost", job.jobId(), "ownership_lost_at_" + node.nodeKey());
                    }
                }
                case "cancelled" -> {
                    if (!jobs.cancel(job.jobId(), job.fencingToken())) {
                        emit(runId, "runtime_job_ownership_lost", "warn",
                            "取消写回被围栏拒绝，本持有者已过期，停机交还", "LEASE_LOST");
                        return new WorkerResult("lost", job.jobId(), "ownership_lost_at_" + node.nodeKey());
                    }
                    emit(runId, "runtime_job_cancelled", "warn", "执行中被取消", "CANCELLED");
                    return new WorkerResult("cancelled", job.jobId(), result.terminalReason());
                }
                default -> {
                    boolean nodeFailed = jobs.failNode(job.jobId(), node.nodeKey(),
                        result.terminalReason() == null ? result.status() : result.terminalReason(),
                        job.fencingToken());
                    boolean jobFailed = jobs.fail(job.jobId(), job.fencingToken(),
                        "节点 " + node.nodeKey() + " 失败：" + result.terminalReason());
                    if (!nodeFailed && !jobFailed) {
                        // R3-1：节点与终态写均被围栏拒绝 = 本持有者已过期 ⇒ 绝不广播幻影失败。
                        emit(runId, "runtime_job_ownership_lost", "warn",
                            "失败写回被围栏拒绝，本持有者已过期，停机交还", "LEASE_LOST");
                        return new WorkerResult("lost", job.jobId(), "ownership_lost_at_" + node.nodeKey());
                    }
                    emit(runId, "runtime_job_failed", "warn",
                        "节点 " + node.nodeKey() + " 失败：" + result.terminalReason(),
                        result.terminalReason());
                    return new WorkerResult("failed", job.jobId(), result.terminalReason());
                }
            }
        }
        jobs.complete(job.jobId(), job.fencingToken(), "");
        if (!jobs.find(job.jobId()).orElseThrow().status().equals(AiJobService.STATUS_SUCCEEDED)) {
            // F4：终态写同样受围栏保护——未真正写入本持有者的成功则不得广播成功事件。
            emit(runId, "runtime_job_ownership_lost", "warn", "终态写回被拒（持有者已过期），停机交还", "LEASE_LOST");
            return new WorkerResult("lost", job.jobId(), "ownership_lost_at_finalization");
        }
        emit(runId, "runtime_job_succeeded", "info", "全部节点完成", "");
        return new WorkerResult("succeeded", job.jobId(), "");
    }

    private AiRuntimeModels.AiTaskRequest buildRequest(AiJobService.AiJob job, AiJobService.AiJobNode node) {
        JsonNode input = inputProvider.load(job);
        String runId = job.legacyRunRef() == null || job.legacyRunRef().isBlank() ? job.jobId() : job.legacyRunRef();
        return new AiRuntimeModels.AiTaskRequest(job.jobType(), job.projectId(), job.actorId(), runId,
            null, null, null, List.of(), input, job.idempotencyKey());
    }

    private void emit(String runId, String stage, String severity, String summary, String retryReason) {
        eventService.emit(new AiRunEvent(runId, Instant.now(), stage, severity, summary,
            null, 0, null, "", "", "", 0L, retryReason, "", 0L));
    }
}
