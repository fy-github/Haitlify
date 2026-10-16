package com.haitlify.aitest.platform.ai.agent;

import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AiAgentRegistry {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    List<BuiltInAgentDefinition> all() {
        return List.of(
            agent(
                "requirement-understanding",
                "需求理解 Agent",
                "提取需求范围、业务规则、来源证据和待澄清问题",
                "requirement_understanding",
                """
                    你是需求理解 Agent。
                    请基于需求文档、视觉证据和 Figma 证据，输出需求范围、规则、来源证据和待澄清问题。
                    不要生成正式用例；只输出可追溯的需求理解结果。
                    """
            ),
            agent(
                "test-design",
                "测试设计 Agent",
                "基于需求理解输出生成可评审测试点草稿",
                "test_design",
                """
                    你是测试设计 Agent。
                    请基于需求理解输出设计结构化测试点，覆盖正向、异常、边界、权限和数据状态。
                    每个测试点必须保留来源证据，不能绕过人工评审。
                    """
            ),
            agent(
                "quality-review",
                "质量评审 Agent",
                "审查测试设计输出的证据、可测性、重复风险和自动化准备度",
                "quality_review",
                """
                    你是质量评审 Agent。
                    请审查测试设计输出，识别缺少来源证据、期望结果不可验证、重复目标和自动化准备度不足的问题。
                    只输出评审意见和修复建议，不直接入库。
                    """
            ),
            agent(
                "automation-suggestion",
                "自动化建议 Agent",
                "为评审中的测试点补充自动化可行性、数据、环境、步骤和断言建议",
                "automation_suggestion",
                """
                    你是自动化建议 Agent。
                    请为测试点补充 Web/API 自动化准备信息，包括定位器、等待、测试数据、环境和断言建议。
                    建议只能作为评审参考，不能直接创建自动化用例。
                    """
            ),
            agent(
                "merge-coordinator",
                "合并协调 Agent",
                "合并多 Agent 输出，保留冲突和来源证据，生成待评审草稿",
                "merge_coordinator",
                """
                    你是合并协调 Agent。
                    请合并需求理解、测试设计、质量评审和自动化建议，去重并保留冲突。
                    合并结果必须进入待评审草稿，不能绕过人工确认。
                    """
            )
        );
    }

    BuiltInAgentDefinition find(String agentId) {
        return all().stream()
            .filter(agent -> agent.agentId().equals(agentId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown AI agent: " + agentId));
    }

    private BuiltInAgentDefinition agent(String agentId, String name, String description, String role, String prompt) {
        return new BuiltInAgentDefinition(
            agentId,
            name,
            description,
            role,
            prompt.stripIndent().trim(),
            schema(),
            tools(),
            true,
            true,
            1
        );
    }

    private JsonNode schema() {
        return json("""
            {
              "type": "object",
              "required": ["summary", "items", "sourceEvidence"],
              "properties": {
                "summary": { "type": "string" },
                "items": { "type": "array" },
                "sourceEvidence": { "type": "array" },
                "warnings": { "type": "array" }
              }
            }
            """);
    }

    private JsonNode tools() {
        return json("""
            ["extract-text-structure","extract-requirement-keywords","extract-field-rules","extract-state-rules","check-coverage-gaps"]
            """);
    }

    private JsonNode json(String value) {
        try {
            return OBJECT_MAPPER.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid built-in AI agent JSON", exception);
        }
    }
}

record BuiltInAgentDefinition(
    String agentId,
    String name,
    String description,
    String agentRole,
    String promptTemplate,
    JsonNode outputJsonSchema,
    JsonNode allowedTools,
    boolean enabled,
    boolean builtIn,
    int version
) {
}
