package com.haitlify.aitest.platform.ai.tool.internal;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
class ExtractStateRulesTool extends AbstractInternalAiTool {

    private static final Pattern STATE_PATTERN = Pattern.compile("状态(?:变为|改为|为)([\\u4e00-\\u9fa5A-Za-z0-9_]+)");

    ExtractStateRulesTool(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public String toolId() {
        return "extract-state-rules";
    }

    @Override
    public String name() {
        return "Extract State Rules";
    }

    @Override
    public String description() {
        return "Extracts state transition hints from requirement text.";
    }

    @Override
    public JsonNode outputSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("stateRules").put("type", "array");
        return schema;
    }

    @Override
    public JsonNode dryRun(JsonNode input) {
        ArrayNode rules = objectMapper.createArrayNode();
        Matcher matcher = STATE_PATTERN.matcher(inputText(input));
        while (matcher.find()) {
            rules.addObject().put("targetState", matcher.group(1));
        }
        return objectMapper.createObjectNode().set("stateRules", rules);
    }
}
