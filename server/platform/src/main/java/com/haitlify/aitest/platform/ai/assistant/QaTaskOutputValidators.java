package com.haitlify.aitest.platform.ai.assistant;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import tools.jackson.databind.JsonNode;

/**
 * T12：七类 QA 任务输出的证据规则校验（主计划 §5.2 验收要点）。
 *
 * <p>确定性规则，不调用模型：证据不足、越权执行、数值被模型改动、规则结果冒充模型评测
 * 等都会以 violations 返回——调用方必须把它们落到产物状态上，而不是静默丢弃。
 */
public final class QaTaskOutputValidators {

    public record Validation(boolean valid, List<String> violations) {
        static Validation ok() {
            return new Validation(true, List.of());
        }
    }

    private static final Set<String> BUG_ANALYSIS_LOCATED_STATUSES = Set.of("identified", "confirmed", "已定位");

    private QaTaskOutputValidators() {
    }

    public static Validation validate(String taskType, JsonNode output) {
        return validate(taskType, output, null);
    }

    /**
     * @param deterministicFacts 由确定性代码计算的执行事实（如 {"passRate": 87.5}），
     *                           模型输出中的对应数字必须与之一致；null 表示本轮不校验数值。
     */
    public static Validation validate(String taskType, JsonNode output, JsonNode deterministicFacts) {
        if (taskType == null || output == null || output.isNull()) {
            return new Validation(false, List.of("output_missing: 任务输出为空"));
        }
        // T-R5 残余④：任务标识统一走全平台唯一归一化（与外部入口/目录/策略一致），
        // 全角/零宽/组合类变体不再落 unknown_task_type。
        return switch (com.haitlify.aitest.platform.ai.runtime.AiRuntimeSwitches.normalizeIdentifier(taskType)) {
            case "requirement_analysis", "testcase_generation" -> requirementOutputs(output);
            case "bug_analysis" -> bugAnalysis(output);
            case "log_triage" -> logTriage(output);
            case "sql_analysis" -> sqlAnalysis(output);
            case "regression_planning" -> regressionPlanning(output);
            case "report_draft" -> reportDraft(output, deterministicFacts);
            case "prompt_evaluation" -> promptEvaluation(output);
            default -> new Validation(false, List.of("unknown_task_type: " + taskType));
        };
    }

    private static Validation requirementOutputs(JsonNode output) {
        List<String> violations = new ArrayList<>();
        JsonNode points = output.path("draftTestPoints");
        JsonNode cases = output.path("generatedCases");
        if (!points.isArray() && !cases.isArray()) {
            violations.add("产物缺失: 需求/用例任务必须给出 draftTestPoints 或 generatedCases");
        }
        // 没有证据的推断必须标记待确认，不得伪装成已确认。
        for (JsonNode item : iterable(points, cases)) {
            JsonNode sources = item.path("evidenceIds").isArray() ? item.path("evidenceIds") : item.path("sourceIds");
            boolean noEvidence = !sources.isArray() || sources.isEmpty();
            String status = item.path("status").asText("");
            boolean pretendsConfirmed = Set.of("confirmed", "accepted", "已确认").contains(status);
            if (noEvidence && pretendsConfirmed) {
                violations.add("证据不足的条目不得标记为已确认: " + item.path("title").asText("(untitled)"));
            }
        }
        return result(violations);
    }

    private static Validation bugAnalysis(JsonNode output) {
        List<String> violations = new ArrayList<>();
        JsonNode hypotheses = output.path("hypotheses");
        if (!hypotheses.isArray() || hypotheses.isEmpty()) {
            violations.add("bug_analysis 必须给出至少一条假设（hypotheses）");
        }
        for (JsonNode hypothesis : iterable(hypotheses)) {
            JsonNode evidence = hypothesis.path("evidenceIds");
            boolean noEvidence = !evidence.isArray() || evidence.isEmpty();
            String status = hypothesis.path("status").asText("").toLowerCase(Locale.ROOT);
            if (noEvidence && BUG_ANALYSIS_LOCATED_STATUSES.contains(status)) {
                violations.add("没有证据不得给「已定位根因」: " + hypothesis.path("claim").asText("(claim)"));
            }
            if (status.isBlank()) {
                violations.add("假设缺少 status（缺证据时必须为 needs_confirmation）: "
                    + hypothesis.path("claim").asText("(claim)"));
            }
        }
        for (JsonNode check : iterable(output.path("recommendedChecks"))) {
            if (check.path("readOnly").isBoolean() && !check.path("readOnly").asBoolean()) {
                violations.add("建议检查必须为只读: " + check.path("description").asText("(check)"));
            }
        }
        if (output.has("humanReviewRequired") && !output.path("humanReviewRequired").asBoolean()) {
            violations.add("bug_analysis 结论必须保留人工复核（humanReviewRequired=true）");
        }
        return result(violations);
    }

    private static Validation logTriage(JsonNode output) {
        List<String> violations = new ArrayList<>();
        String timezone = output.path("timezone").asText("");
        if (timezone.isBlank()) {
            violations.add("log_triage 必须保留原时区（timezone）");
        }
        for (JsonNode entry : iterable(output.path("timeline"), output.path("anomalies"), output.path("conclusions"))) {
            boolean cited = hasAnyNonEmptyArray(entry, "evidenceIds", "lineIds", "lineRefs");
            if (!cited) {
                violations.add("日志结论必须引用具体行（evidenceIds/lineIds/lineRefs）: "
                    + entry.path("description").asText(entry.path("summary").asText("(entry)")));
            }
        }
        return result(violations);
    }

    private static Validation sqlAnalysis(JsonNode output) {
        List<String> violations = new ArrayList<>();
        JsonNode suggestions = output.path("suggestions");
        if (!suggestions.isArray() || suggestions.isEmpty()) {
            violations.add("sql_analysis 必须给出静态建议（suggestions）");
        }
        // 静态建议永远不连接/执行数据库。
        Set<String> executionKeys = Set.of("executed", "executionResult", "dbConnection", "connectionString", "runOnDatabase");
        if (mentionsAnyKey(output, executionKeys)) {
            violations.add("SQL 分析禁止连接或执行数据库（仅静态建议与已有执行计划分析）");
        }
        for (JsonNode item : iterable(output.path("risks"), output.path("suggestions"))) {
            String text = item.path("description").asText(item.path("detail").asText(""));
            boolean claimsConfirmedPerformance = item.path("confirmedPerformance").asBoolean(false)
                || text.contains("已证实");
            if (claimsConfirmedPerformance && !hasAnyNonEmptyArray(item, "evidenceIds", "statsIds")) {
                violations.add("无统计信息不得给出已证实的性能结论: " + text);
            }
        }
        return result(violations);
    }

    private static Validation regressionPlanning(JsonNode output) {
        List<String> violations = new ArrayList<>();
        JsonNode required = output.path("requiredItems");
        if (!required.isArray() || required.isEmpty()) {
            violations.add("regression_planning 必须给出必测项（requiredItems）");
        }
        Set<String> chosenCaseIds = new LinkedHashSet<>();
        for (JsonNode item : iterable(required, output.path("suggestedItems"))) {
            String caseId = item.path("caseId").asText(item.path("case_id").asText(""));
            if (caseId.isBlank()) {
                violations.add("回归项必须从真实用例资产选择（缺少 caseId）: "
                    + item.path("title").asText(item.path("description").asText("(item)")));
            } else {
                chosenCaseIds.add(caseId);
            }
        }
        for (JsonNode suggestion : iterable(output.path("newSuggestions"))) {
            String caseId = suggestion.path("caseId").asText(suggestion.path("case_id").asText(""));
            if (!caseId.isBlank() && chosenCaseIds.contains(caseId)) {
                violations.add("新增建议与已有用例必须分开（同一 caseId 混用）: " + caseId);
            }
            if (suggestion.path("reason").asText("").isBlank()) {
                violations.add("新增建议必须给出选择原因（reason）");
            }
        }
        return result(violations);
    }

    private static Validation reportDraft(JsonNode output, JsonNode deterministicFacts) {
        List<String> violations = new ArrayList<>();
        JsonNode sourceRunIds = output.path("sourceRunIds");
        if (!sourceRunIds.isArray() || sourceRunIds.isEmpty()) {
            violations.add("report_draft 必须携带 sourceRunIds（报告数值必须可回溯到执行记录）");
        }
        String approval = output.path("approvalStatus").asText("").toLowerCase(Locale.ROOT);
        boolean humanApproved = output.path("humanApproved").asBoolean(false);
        if (Set.of("approved", "已批准", "accepted").contains(approval) && !humanApproved) {
            violations.add("报告上线结论永远 HUMAN_REQUIRED——模型不得自动批准");
        }
        if (deterministicFacts != null && deterministicFacts.has("passRate") && output.has("passRate")) {
            double computed = deterministicFacts.path("passRate").asDouble();
            double claimed = output.path("passRate").asDouble();
            if (Math.abs(computed - claimed) > 0.001) {
                violations.add("pass rate 必须由执行记录确定性计算，模型不得改动数字（期望 "
                    + computed + "，实际 " + claimed + "）");
            }
        }
        return result(violations);
    }

    private static Validation promptEvaluation(JsonNode output) {
        List<String> violations = new ArrayList<>();
        JsonNode samples = output.path("sampleResults");
        if (!samples.isArray() || samples.isEmpty()) {
            violations.add("prompt_evaluation 必须给出样例结果（sampleResults）");
        }
        for (JsonNode sample : iterable(samples)) {
            boolean realRequest = sample.path("realRequest").asBoolean(false);
            boolean labeledRuleOnly = sample.path("ruleOnly").asBoolean(false);
            if (!realRequest && !labeledRuleOnly) {
                violations.add("规则校验不能冒充模型评测（需要 realRequest=true 或显式 ruleOnly=true）: "
                    + sample.path("sampleId").asText("(sample)"));
            }
        }
        return result(violations);
    }

    private static boolean hasAnyNonEmptyArray(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.path(key);
            if (value.isArray() && !value.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean mentionsAnyKey(JsonNode node, Set<String> keys) {
        if (node == null || node.isMissingNode()) {
            return false;
        }
        if (node.isObject()) {
            for (String key : keys) {
                if (node.has(key)) {
                    return true;
                }
            }
            for (JsonNode child : node) {
                if (mentionsAnyKey(child, keys)) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (mentionsAnyKey(child, keys)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Validation result(List<String> violations) {
        return violations.isEmpty() ? Validation.ok() : new Validation(false, List.copyOf(violations));
    }

    private static Iterable<JsonNode> iterable(JsonNode... arrays) {
        List<JsonNode> items = new ArrayList<>();
        for (JsonNode array : arrays) {
            if (array != null && array.isArray()) {
                array.forEach(items::add);
            }
        }
        return items;
    }
}
