package com.haitlify.aitest.platform.ai.tool.mcp;

import tools.jackson.databind.JsonNode;

public record AiMcpObservedResult(JsonNode result, int httpStatus, String sessionId, String callId) {}
