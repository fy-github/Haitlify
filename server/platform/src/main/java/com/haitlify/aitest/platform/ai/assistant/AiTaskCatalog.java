package com.haitlify.aitest.platform.ai.assistant;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * T12：七类 QA 助手入口的任务目录（主计划 §5.2）。
 *
 * <p>目录只声明每个 taskType 的必要输入、输出 schema、证据类型、工具白名单、人工门与产物目标；
 * 不绑定任何具体模型——模型选择属于 profile/policy 层。路由是显式用户选择优先；自动识别只能
 * 提出候选与缺项，用户可修正。
 */
public final class AiTaskCatalog {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public record TaskDefinition(
        String taskType,
        String displayName,
        List<String> requiredInputs,
        List<String> evidenceTypes,
        List<String> toolAllowlist,
        boolean humanGateRequired,
        String artifactTarget,
        JsonNode outputSchema
    ) {
    }

    public record RequestValidation(boolean complete, List<String> missingInputs) {
    }

    private static final List<TaskDefinition> DEFINITIONS = List.of(
        new TaskDefinition(
            "requirement_analysis",
            "需求分析",
            List.of("documentIds", "targetCaseKind"),
            List.of("requirement_document", "figma_frame", "case_template"),
            List.of("extract-text-structure", "extract-requirement-keywords", "extract-field-rules",
                "extract-state-rules", "check-coverage-gaps"),
            true,
            "draft_test_points",
            schema("""
                {
                  "type": "object",
                  "required": ["draftTestPoints"],
                  "properties": {
                    "draftTestPoints": {"type": "array"},
                    "unclearQuestions": {"type": "array"},
                    "coverageSummary": {"type": "object"}
                  }
                }
                """)),
        new TaskDefinition(
            "testcase_generation",
            "用例生成",
            List.of("draftPointIds", "caseKind"),
            List.of("draft_test_point", "case_template", "requirement_document"),
            List.of("match-case-template"),
            true,
            "generated_cases",
            schema("""
                {
                  "type": "object",
                  "required": ["generatedCases"],
                  "properties": {
                    "generatedCases": {"type": "array"},
                    "blockedPoints": {"type": "array"}
                  }
                }
                """)),
        new TaskDefinition(
            "bug_analysis",
            "缺陷分析",
            List.of("symptom"),
            List.of("log_excerpt", "screenshot", "trace", "jira_issue", "execution_record"),
            List.of("extract-text-structure", "check-coverage-gaps"),
            true,
            "analysis_artifact",
            schema("""
                {
                  "type": "object",
                  "required": ["taskType", "summary", "hypotheses"],
                  "properties": {
                    "taskType": {"type": "string", "const": "bug_analysis"},
                    "summary": {"type": "string"},
                    "hypotheses": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "required": ["claim", "status"],
                        "properties": {
                          "claim": {"type": "string"},
                          "evidenceIds": {"type": "array"},
                          "contradictions": {"type": "array"},
                          "status": {"type": "string"}
                        }
                      }
                    },
                    "missingInputs": {"type": "array"},
                    "recommendedChecks": {"type": "array"},
                    "humanReviewRequired": {"type": "boolean"}
                  }
                }
                """)),
        new TaskDefinition(
            "log_triage",
            "日志排查",
            List.of("logExcerpt", "timeRange"),
            List.of("log_excerpt"),
            List.of("extract-text-structure"),
            true,
            "analysis_artifact",
            schema("""
                {
                  "type": "object",
                  "required": ["taskType", "timeline", "timezone"],
                  "properties": {
                    "taskType": {"type": "string", "const": "log_triage"},
                    "timezone": {"type": "string"},
                    "timeline": {"type": "array"},
                    "correlationIds": {"type": "array"},
                    "anomalies": {"type": "array"},
                    "missingLogs": {"type": "array"}
                  }
                }
                """)),
        new TaskDefinition(
            "sql_analysis",
            "SQL 分析",
            List.of("sqlText"),
            List.of("sql_text", "schema_ddl", "explain_plan"),
            List.of(),
            true,
            "analysis_artifact",
            schema("""
                {
                  "type": "object",
                  "required": ["taskType", "risks", "suggestions"],
                  "properties": {
                    "taskType": {"type": "string", "const": "sql_analysis"},
                    "risks": {"type": "array"},
                    "suggestions": {"type": "array"},
                    "verificationSteps": {"type": "array"}
                  }
                }
                """)),
        new TaskDefinition(
            "regression_planning",
            "回归规划",
            List.of("changeSummary"),
            List.of("execution_record", "jira_issue", "case_library"),
            List.of("match-case-template", "check-coverage-gaps"),
            true,
            "regression_plan",
            schema("""
                {
                  "type": "object",
                  "required": ["taskType", "requiredItems"],
                  "properties": {
                    "taskType": {"type": "string", "const": "regression_planning"},
                    "requiredItems": {"type": "array"},
                    "suggestedItems": {"type": "array"},
                    "newSuggestions": {"type": "array"}
                  }
                }
                """)),
        new TaskDefinition(
            "report_draft",
            "报告草稿",
            List.of("sourceRunIds"),
            List.of("execution_record", "jira_issue", "regression_plan"),
            List.of("compute-execution-stats"),
            true,
            "report_draft",
            schema("""
                {
                  "type": "object",
                  "required": ["taskType", "sections", "sourceRunIds"],
                  "properties": {
                    "taskType": {"type": "string", "const": "report_draft"},
                    "sections": {"type": "array"},
                    "sourceRunIds": {"type": "array"},
                    "linkedDefects": {"type": "array"},
                    "approvalStatus": {"type": "string"}
                  }
                }
                """)),
        new TaskDefinition(
            "prompt_evaluation",
            "Prompt 评测",
            List.of("promptVersion", "samples"),
            List.of("prompt_version", "sample_case", "execution_record"),
            List.of(),
            true,
            "evaluation_artifact",
            schema("""
                {
                  "type": "object",
                  "required": ["taskType", "sampleResults"],
                  "properties": {
                    "taskType": {"type": "string", "const": "prompt_evaluation"},
                    "sampleResults": {"type": "array"},
                    "qualityDiff": {"type": "object"},
                    "costAndLatency": {"type": "object"}
                  }
                }
                """))
    );

    private AiTaskCatalog() {
    }

    public static List<TaskDefinition> all() {
        return DEFINITIONS;
    }

    public static Optional<TaskDefinition> find(String taskType) {
        if (taskType == null || taskType.isBlank()) {
            return Optional.empty();
        }
        // T-R5 轮 5：与灰度开关共用同一归一化（含不动点迭代），杜绝两处口径分叉。
        String normalized = com.haitlify.aitest.platform.ai.runtime.AiRuntimeSwitches
            .normalizeIdentifier(taskType);
        return DEFINITIONS.stream().filter(definition -> definition.taskType().equals(normalized)).findFirst();
    }

    /**
     * 必要输入校验：返回缺项清单（显式路由校验与自动识别候选共用）。
     * 输入里出现 null/空字符串/空数组都视为缺失——缺项不得被默认值掩盖。
     */
    public static RequestValidation validateRequest(String taskType, JsonNode inputs) {
        TaskDefinition definition = find(taskType).orElseThrow(
            () -> new IllegalArgumentException("unknown_task_type: " + taskType));
        List<String> missing = new ArrayList<>();
        for (String required : definition.requiredInputs()) {
            JsonNode value = inputs == null ? null : inputs.get(required);
            boolean absent = value == null || value.isNull()
                || (value.isTextual() && value.asText().isBlank())
                || (value.isArray() && value.isEmpty());
            if (absent) {
                missing.add(required);
            }
        }
        return new RequestValidation(missing.isEmpty(), List.copyOf(missing));
    }

    /**
     * 路由候选（自动识别）：只根据当前已有输入给出候选任务与各自缺项，绝不自动触发外部写操作。
     * 排序确定性：缺项少的优先，其次按目录顺序。
     */
    public static List<Map<String, Object>> routeCandidates(JsonNode inputs, Set<String> availableEvidenceTypes) {
        Set<String> evidence = availableEvidenceTypes == null ? Set.of() : new LinkedHashSet<>(availableEvidenceTypes);
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (TaskDefinition definition : DEFINITIONS) {
            RequestValidation validation = validateRequest(definition.taskType(), inputs);
            boolean evidenceReady = definition.evidenceTypes().isEmpty() || evidence.isEmpty()
                || definition.evidenceTypes().stream().anyMatch(evidence::contains);
            candidates.add(Map.of(
                "taskType", definition.taskType(),
                "displayName", definition.displayName(),
                "missingInputs", validation.missingInputs(),
                "evidenceReady", evidenceReady,
                "complete", validation.complete()
            ));
        }
        candidates.sort((left, right) -> {
            int byMissing = Integer.compare(
                ((List<?>) left.get("missingInputs")).size(), ((List<?>) right.get("missingInputs")).size());
            return byMissing != 0 ? byMissing : Integer.compare(
                indexOf((String) left.get("taskType")), indexOf((String) right.get("taskType")));
        });
        return candidates;
    }

    private static int indexOf(String taskType) {
        for (int index = 0; index < DEFINITIONS.size(); index++) {
            if (DEFINITIONS.get(index).taskType().equals(taskType)) {
                return index;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static JsonNode schema(String json) {
        return OBJECT_MAPPER.readTree(json);
    }
}
