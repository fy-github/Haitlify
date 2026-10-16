package com.haitlify.aitest.platform.ai.tool.mcp;

import tools.jackson.databind.JsonNode;

/** T09：MCP 工具执行结果（public 以便跨包消费；resultOrigin/acceptanceState 语义保持不变）。 */
public record AiMcpToolExecutionResult(
    String status,
    JsonNode response,
    String errorMessage
) {
    public boolean isToolObserved() { return "TOOL_OBSERVED".equals(resultOrigin()); }

    public String resultOrigin() {
        return response == null ? "BLOCKED" : response.path("resultOrigin").asText("BLOCKED");
    }

    public String acceptanceState() {
        return response == null ? "BLOCKED" : response.path("acceptanceState").asText("BLOCKED");
    }
}
