package com.haitlify.aitest.platform.ai.tool;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

@Component
public class AiToolRegistry {

    private static final List<String> TOOL_ORDER = List.of(
        "extract-text-structure",
        "extract-requirement-keywords",
        "extract-field-rules",
        "extract-state-rules",
        "match-case-template",
        "check-coverage-gaps"
    );

    private final Map<String, AiTool> tools;

    public AiToolRegistry(List<AiTool> tools) {
        this.tools = tools.stream().collect(Collectors.toUnmodifiableMap(AiTool::toolId, Function.identity()));
    }

    public List<AiTool> listTools() {
        return tools.values().stream()
            .sorted(Comparator
                .comparingInt((AiTool tool) -> toolOrder(tool.toolId()))
                .thenComparing(AiTool::toolId))
            .toList();
    }

    public Optional<AiTool> findTool(String toolId) {
        return Optional.ofNullable(tools.get(toolId));
    }

    public AiTool requireTool(String toolId) {
        return findTool(toolId).orElseThrow(() -> new IllegalArgumentException("Unknown AI tool: " + toolId));
    }

    private int toolOrder(String toolId) {
        int index = TOOL_ORDER.indexOf(toolId);
        return index >= 0 ? index : Integer.MAX_VALUE;
    }
}
