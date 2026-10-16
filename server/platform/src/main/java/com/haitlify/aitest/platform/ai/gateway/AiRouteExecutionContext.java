package com.haitlify.aitest.platform.ai.gateway;

/** T05/T15：路由执行上下文；taskType/projectId 供发布开关在真正的发送点裁决（可为空=未知）。 */
public record AiRouteExecutionContext(
    String runId,
    String strategySnapshotId,
    int chunkIndex,
    int chunkTotal,
    String plannedChunkId,
    String taskType,
    Long projectId
) {

    /** 兼容构造器（既有单测/调用方）：无任务/项目上下文。 */
    public AiRouteExecutionContext(String runId, String strategySnapshotId, int chunkIndex,
                                   int chunkTotal, String plannedChunkId) {
        this(runId, strategySnapshotId, chunkIndex, chunkTotal, plannedChunkId, null, null);
    }
}
