package com.haitlify.aitest.platform.ai.runtime;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * T06 job API：幂等提交、审计读取、取消（幂等；已终态返回 ALREADY_FINISHED，
 * 晚到取消不损坏终态）与 afterSeq 事件补拉。actor 绑定沿用平台既有 API 层约定
 * （SSO 认证），此处的 actorId 仅作为审计元数据由调用方提供。
 */
@RestController
@RequestMapping("/api/ai/jobs")
public class AiJobController {

    public record SubmitRequest(String jobType, Long projectId, String actorId, String idempotencyKey,
                                String inputHash, List<String> nodePlan, String legacyRunRef,
                                String deploymentRevision) {}

    public record SubmitResponse(String jobId) {}

    public record CancelResponse(String outcome) {}

    /** 任务不存在（404）；由 {@link AiJobExceptionHandler} 映射。 */
    public static class JobNotFoundException extends RuntimeException {
        JobNotFoundException(String jobId) {
            super("job_not_found: " + jobId);
        }
    }

    private final AiJobService jobs;
    private final AiRunAuditService audit;

    @org.springframework.beans.factory.annotation.Autowired
    public AiJobController(AiJobService jobs, AiRunAuditService audit,
                           com.haitlify.aitest.platform.ai.security.AiInvocationAuthorization authorization) {
        this.jobs = jobs;
        this.audit = audit;
        this.authorization = authorization;
    }

    private final com.haitlify.aitest.platform.ai.security.AiInvocationAuthorization authorization;

    /** T14-R5：job 面读取/取消必须与「不存在」同形并做项目授权——绝不允许跨项目读审计或取消。 */
    private AiJobService.AiJob requireVisibleJob(String jobId) {
        AiJobService.AiJob job = jobs.find(jobId).orElseThrow(
            com.haitlify.aitest.platform.ai.security.AiInvocationAuthorization::notFound);
        if (authorization != null) {
            authorization.requireProjectAccess(job.projectId());
        }
        return job;
    }

    @PostMapping
    public SubmitResponse submit(@RequestBody SubmitRequest request) {
        boolean invalid = request.jobType() == null || request.jobType().isBlank()
            || request.projectId() == null || request.idempotencyKey() == null || request.idempotencyKey().isBlank()
            || request.inputHash() == null || request.inputHash().isBlank()
            || request.nodePlan() == null || request.nodePlan().isEmpty();
        if (invalid) {
            throw new IllegalArgumentException("job submit fields required");
        }
        // T14-R5 轮 2：提交同样做项目授权——任何编辑器不得为他项目创建付费作业（与读取/取消同形）。
        if (authorization != null) {
            authorization.requireProjectAccess(request.projectId());
        }
        // T-R5 轮 3：jobType 归一化后入库——'requirement_analysis\u00a0' 不得成为绕过灰度的键。
        String normalizedJobType = com.haitlify.aitest.platform.ai.runtime.AiRuntimeSwitches
            .normalizeIdentifier(request.jobType());
        if (normalizedJobType.isEmpty()) {
            throw new IllegalArgumentException("job submit fields required");
        }
        // T-R5 轮 4/5：jobType 必须是目录已知任务类型，并以目录 canonical 值落库
        // （杜绝 'bug_Analysis' 等非规范值入库引发的下游集合失配）。
        String canonicalJobType = com.haitlify.aitest.platform.ai.assistant.AiTaskCatalog
            .find(normalizedJobType)
            .orElseThrow(() -> new IllegalArgumentException("unknown_task_type: " + normalizedJobType))
            .taskType();
        String jobId = jobs.submit(canonicalJobType, request.projectId(),
            request.actorId() == null || request.actorId().isBlank() ? "api" : request.actorId(),
            request.idempotencyKey(), request.inputHash(), request.nodePlan(),
            request.deploymentRevision() == null ? "" : request.deploymentRevision());
        if (request.legacyRunRef() != null && !request.legacyRunRef().isBlank()) {
            jobs.linkLegacyRun(jobId, request.legacyRunRef());
        }
        return new SubmitResponse(jobId);
    }

    @GetMapping("/{jobId}")
    public AiRunAuditService.JobAudit job(@PathVariable String jobId,
                                          @RequestParam(value = "afterSeq", required = false) Long afterSeq) {
        requireVisibleJob(jobId);
        return audit.jobAudit(jobId, afterSeq);
    }

    @PostMapping("/{jobId}/cancel")
    public CancelResponse cancel(@PathVariable String jobId,
                                 @RequestParam(value = "requestedBy", required = false) String requestedBy) {
        requireVisibleJob(jobId);
        AiJobService.CancelOutcome outcome = jobs.requestCancel(jobId,
            requestedBy == null || requestedBy.isBlank() ? "api" : requestedBy);
        if (outcome == AiJobService.CancelOutcome.NOT_FOUND) {
            throw com.haitlify.aitest.platform.ai.security.AiInvocationAuthorization.notFound();
        }
        return new CancelResponse(outcome.name());
    }

    @GetMapping("/{jobId}/events")
    public List<com.haitlify.aitest.platform.ai.event.AiRunEvent> events(
            @PathVariable String jobId,
            @RequestParam(value = "afterSeq", required = false) Long afterSeq) {
        requireVisibleJob(jobId);
        return audit.jobAudit(jobId, afterSeq).events();
    }
}
