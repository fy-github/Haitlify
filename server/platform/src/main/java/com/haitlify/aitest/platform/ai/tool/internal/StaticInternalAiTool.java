package com.haitlify.aitest.platform.ai.tool.internal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * T09 bullet 4：两个业务工具（match-case-template / check-coverage-gaps）已迁出为
 * 独立实现（MatchCaseTemplateTool / CheckCoverageGapsTool），读取真实模板与
 * requirement→case 映射并以 RULE_COMPUTED 标注。本类仅保留其余静态工具的基类。
 */
abstract class StaticInternalAiTool extends AbstractInternalAiTool {

    private final String toolId;
    private final String name;
    private final String description;

    StaticInternalAiTool(ObjectMapper objectMapper, String toolId, String name, String description) {
        super(objectMapper);
        this.toolId = toolId;
        this.name = name;
        this.description = description;
    }

    @Override
    public String toolId() {
        return toolId;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public JsonNode outputSchema() {
        return objectMapper.createObjectNode()
            .put("type", "object")
            .set("properties", objectMapper.createObjectNode());
    }

    @Override
    public JsonNode dryRun(JsonNode input) {
        return objectMapper.createObjectNode()
            .put("textLength", inputText(input).length());
    }
}
