package com.haitlify.aitest.platform.ai.tool.internal;

import com.haitlify.aitest.platform.ai.tool.AiTool;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

abstract class AbstractInternalAiTool implements AiTool {

    protected final ObjectMapper objectMapper;

    AbstractInternalAiTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("text").put("type", "string");
        schema.putArray("required").add("text");
        return schema;
    }

    protected String inputText(JsonNode input) {
        if (input == null || !input.hasNonNull("text")) {
            throw new IllegalArgumentException("Tool input text is required");
        }
        return input.path("text").asText("");
    }
}
