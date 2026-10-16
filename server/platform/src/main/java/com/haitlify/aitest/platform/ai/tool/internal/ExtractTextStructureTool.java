package com.haitlify.aitest.platform.ai.tool.internal;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
class ExtractTextStructureTool extends AbstractInternalAiTool {

    ExtractTextStructureTool(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public String toolId() {
        return "extract-text-structure";
    }

    @Override
    public String name() {
        return "Extract Text Structure";
    }

    @Override
    public String description() {
        return "Summarizes line and section structure for a text chunk.";
    }

    @Override
    public JsonNode outputSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("lineCount").put("type", "integer");
        return schema;
    }

    @Override
    public JsonNode dryRun(JsonNode input) {
        String text = inputText(input);
        ObjectNode result = objectMapper.createObjectNode();
        result.put("lineCount", text.isBlank() ? 0 : text.split("\\R", -1).length);
        result.put("characterCount", text.length());
        return result;
    }
}
