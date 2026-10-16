package com.haitlify.aitest.platform.ai.runtime.output;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;

/**
 * T03 业务输出验证器：在 schema 校验之后执行跨字段/跨集合的业务规则——
 * 拒绝未知 sourceId（含跨项目引用）、缺步骤/断言的用例、错误 template（caseKind）类型。
 * 规则按任务类注册；未知任务类不静默通过（调用方必须显式注册）。
 */
@Service
public class AiBusinessOutputValidator {

    /** web 用例的断言动作必须至少含一个 runner 支持的断言 DSL。 */
    private static final Pattern ASSERT_ACTION = Pattern.compile("assert(Visible|Hidden|Text|Url|Value|Checked)\\b");

    public record Context(Set<String> knownSourceIds, Set<String> knownPointIds) {
        public static Context empty() {
            return new Context(Set.of(), Set.of());
        }
    }

    public List<String> validate(String taskType, JsonNode output, Context context) {
        List<String> errors = new ArrayList<>();
        switch (taskType) {
            case "requirement-test-points" -> validateTestPoints(output, context, errors);
            case "formal-web-cases" -> validateWebCases(output, context, errors);
            case "test-point-review-result" -> validateReviewResult(output, context, errors);
            case "vision-understanding-result" -> {
                // 视觉结果的全部规则由 schema 承担（枚举/边界）；此处仅保证契约已注册。
            }
            default -> errors.add("unknown output contract taskType: " + taskType);
        }
        return List.copyOf(errors);
    }

    private void validateTestPoints(JsonNode output, Context context, List<String> errors) {
        JsonNode points = output.path("draftTestPoints");
        if (!points.isArray()) {
            return;
        }
        for (int index = 0; index < points.size(); index++) {
            JsonNode point = points.get(index);
            String sourceId = point.path("sourceId").asText("");
            if (!sourceId.isBlank() && !context.knownSourceIds().isEmpty() && !context.knownSourceIds().contains(sourceId)) {
                errors.add("/draftTestPoints/" + index + "/sourceId: unknown or cross-project source reference '" + sourceId + "'");
            }
        }
    }

    private void validateWebCases(JsonNode output, Context context, List<String> errors) {
        JsonNode cases = output.path("cases");
        if (!cases.isArray()) {
            return;
        }
        for (int index = 0; index < cases.size(); index++) {
            JsonNode item = cases.get(index);
            String prefix = "/cases/" + index;
            if (!"web_automation".equals(item.path("caseKind").asText(""))) {
                errors.add(prefix + "/caseKind: template type must be web_automation");
            }
            String steps = item.path("steps").asText("");
            if (steps.isBlank()) {
                errors.add(prefix + "/steps: steps must not be empty");
            }
            String assertions = item.path("assertions").asText("");
            if (assertions.isBlank()) {
                errors.add(prefix + "/assertions: assertions must not be empty");
            } else if (!ASSERT_ACTION.matcher(assertions).find()) {
                errors.add(prefix + "/assertions: must contain at least one runner assertion action (assertVisible/assertHidden/assertText/assertUrl/...)");
            }
            String pointId = item.path("sourcePointId").asText("");
            if (!pointId.isBlank() && !context.knownPointIds().isEmpty() && !context.knownPointIds().contains(pointId)) {
                errors.add(prefix + "/sourcePointId: unknown or cross-project point reference '" + pointId + "'");
            }
        }
    }

    private void validateReviewResult(JsonNode output, Context context, List<String> errors) {
        JsonNode reviews = output.path("reviews");
        if (!reviews.isArray()) {
            return;
        }
        for (int index = 0; index < reviews.size(); index++) {
            String pointId = reviews.get(index).path("pointId").asText("");
            if (!pointId.isBlank() && !context.knownPointIds().isEmpty() && !context.knownPointIds().contains(pointId)) {
                errors.add("/reviews/" + index + "/pointId: unknown or cross-project point reference '" + pointId + "'");
            }
        }
    }
}
