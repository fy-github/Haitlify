package com.haitlify.aitest.platform.ai.tool;

import tools.jackson.databind.JsonNode;

public interface AiTool {

    String toolId();

    String name();

    String description();

    boolean readOnly();

    JsonNode inputSchema();

    JsonNode outputSchema();

    JsonNode dryRun(JsonNode input);
}
