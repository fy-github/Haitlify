package com.haitlify.aitest.platform.ai.runtime;

import java.util.List;

import org.springframework.stereotype.Service;

import com.haitlify.aitest.platform.ai.event.AiRunEvent;
import com.haitlify.aitest.platform.ai.event.AiRunEventService;

/**
 * T06 审计读侧：按 job 聚合状态、节点 checkpoint、attempt 轨迹与事件流
 * （afterSeq 补拉）。只暴露允许字段——attempt 仅含 hash/状态/用量，绝无请求原文、
 * 密钥或响应体。
 */
@Service
public class AiRunAuditService {

    public record JobAudit(AiJobService.AiJob job, List<AiJobService.AiJobNode> nodes,
                           List<AiAttemptRepository.Attempt> attempts, List<AiRunEvent> events) {}

    private final AiJobService jobs;
    private final AiAttemptRepository attempts;
    private final AiRunEventService eventService;

    @org.springframework.beans.factory.annotation.Autowired
    public AiRunAuditService(AiJobService jobs, AiAttemptRepository attempts, AiRunEventService eventService) {
        this.jobs = jobs;
        this.attempts = attempts;
        this.eventService = eventService;
    }

    public JobAudit jobAudit(String jobId, Long afterSeq) {
        AiJobService.AiJob job = jobs.find(jobId)
            .orElseThrow(() -> new IllegalArgumentException("job_not_found: " + jobId));
        String runId = job.legacyRunRef() == null || job.legacyRunRef().isBlank() ? job.jobId() : job.legacyRunRef();
        return new JobAudit(
            job,
            jobs.nodes(jobId),
            attempts.listByJob(jobId),
            eventService.listByRun(runId, afterSeq).items());
    }
}
