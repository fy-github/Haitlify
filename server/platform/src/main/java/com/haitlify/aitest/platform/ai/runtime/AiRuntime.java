package com.haitlify.aitest.platform.ai.runtime;

/**
 * T06：统一 Runtime。执行顺序固定为
 * authorize → snapshot → reserve budget → build evidence → resolve compatible route
 * → persist attempt → transport → validate → persist candidate → finalize usage/events。
 * 实现必须按此顺序可审计地接线（{@link DefaultAiRuntime}），任何一步失败都收敛为
 * 终态 {@link RuntimeExecution} 且 attempt 记录保留。
 */
public interface AiRuntime {

    /** 终态：succeeded / failed / blocked（前置条件不满足）/ cancelled。 */
    record RuntimeExecution(String jobId, String nodeKey, String runId, String attemptId, String status,
                            String terminalReason, String outputJson, String providerRequestId,
                            String legacyRunRef) {}

    RuntimeExecution execute(AiJobService.AiJob job, String nodeKey, AiRuntimeModels.AiTaskRequest request);
}
