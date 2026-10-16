package com.haitlify.aitest.platform.ai.agentic.rule;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
class AgentRuleValidator {

    private static final List<String> REQUIRED_HEADINGS = List.of(
        "Purpose", "Inputs", "Allowed tools", "Forbidden actions", "Pass conditions",
        "Failure state", "Visible event summary"
    );
    private static final List<String> FORBIDDEN_TERMS = List.of(
        "save password", "persist password", "log password", "save cookie", "persist cookie",
        "log cookie", "capture hidden reasoning", "persist hidden reasoning", "guess locator",
        "猜测 locator", "自动执行最终"
    );
    private static final List<String> PHASES = List.of(
        "01-preflight", "02-evidence", "03-planning", "04-generation", "05-validation", "06-delivery"
    );

    void validate(String contentText, Map<String, String> phaseRules) {
        if (contentText == null || contentText.isBlank()) {
            throw new IllegalArgumentException("Agent 全局规则不能为空");
        }
        String lower = contentText.toLowerCase();
        List<String> missing = REQUIRED_HEADINGS.stream()
            .filter(heading -> !lower.contains(heading.toLowerCase()))
            .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Agent 规则缺少必填章节: " + String.join(", ", missing));
        }
        List<String> unsafe = java.util.Arrays.stream(lower.split("\\R"))
            .filter(line -> !line.contains("never") && !line.contains("do not") && !line.contains("禁止"))
            .flatMap(line -> FORBIDDEN_TERMS.stream().filter(line::contains))
            .distinct()
            .toList();
        if (!unsafe.isEmpty()) {
            throw new IllegalArgumentException("Agent 规则包含不可发布的敏感或危险指令: " + String.join(", ", unsafe));
        }
        if (phaseRules == null || phaseRules.size() != PHASES.size()
            || PHASES.stream().anyMatch(phase -> phaseRules.getOrDefault(phase, "").isBlank())) {
            throw new IllegalArgumentException("Agent 阶段规则必须完整包含六个阶段");
        }
    }
}
