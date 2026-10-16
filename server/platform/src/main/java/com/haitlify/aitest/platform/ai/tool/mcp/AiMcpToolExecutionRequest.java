package com.haitlify.aitest.platform.ai.tool.mcp;

import tools.jackson.databind.JsonNode;

/** T09：MCP 工具执行请求（public 以便跨包接线，例如 QA 助手的只读证据采集）。 */
public record AiMcpToolExecutionRequest(
    String runId,
    Long projectId,
    String serverId,
    String toolName,
    boolean readOnly,
    JsonNode arguments,
    String actorId,
    String nodeId,
    String attemptId
) {

    /** T09 bullet 7 之前的调用方兼容构造器（无 node/attempt 关联 = 显式空值，可追溯审计发现）。 */
    public AiMcpToolExecutionRequest(String runId, Long projectId, String serverId, String toolName,
                                     boolean readOnly, JsonNode arguments, String actorId) {
        this(runId, projectId, serverId, toolName, readOnly, arguments, actorId, "", "");
    }
}
