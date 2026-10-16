package com.haitlify.aitest.platform.ai.tool.internal;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
class ExtractFieldRulesTool extends AbstractInternalAiTool {

    private static final Pattern FIELD_PATTERN = Pattern.compile("([\\u4e00-\\u9fa5A-Za-z0-9_]+)(?:字段|名称|编号)?(?:必须|不能为空|长度|格式|唯一)");

    ExtractFieldRulesTool(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public String toolId() {
        return "extract-field-rules";
    }

    @Override
    public String name() {
        return "Extract Field Rules";
    }

    @Override
    public String description() {
        return "Extracts simple field validation rules from requirement text.";
    }

    @Override
    public JsonNode outputSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("fieldRules").put("type", "array");
        return schema;
    }

    @Override
    public JsonNode dryRun(JsonNode input) {
        ArrayNode rules = objectMapper.createArrayNode();
        Matcher matcher = FIELD_PATTERN.matcher(inputText(input));
        while (matcher.find()) {
            rules.addObject()
                .put("field", matcher.group(1))
                .put("rule", matcher.group(0));
        }
        return objectMapper.createObjectNode().set("fieldRules", rules);
    }
}
