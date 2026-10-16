package com.haitlify.aitest.platform.ai.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * T12 批次 1：任务目录（七类入口 + 路由候选）与输出证据校验器的固定场景/负例。
 * 规则全部来自主计划 §5.2 验收要点；此处不调用模型，全部为确定性断言。
 */
class AiAssistantTaskFlowTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JsonNode json(String text) {
        return objectMapper.readTree(text);
    }

    // ---------- 任务目录 ----------

    @Test
    void catalogDefinesAllAssistantTaskTypesWithoutBindingAnyModel() {
        // 主计划 §5.2 共七行入口，其中第一行含 requirement_analysis 与 testcase_generation 两个类型。
        assertThat(AiTaskCatalog.all()).hasSize(8);
        assertThat(AiTaskCatalog.all()).extracting(AiTaskCatalog.TaskDefinition::taskType)
            .containsExactlyInAnyOrder(
                "requirement_analysis", "testcase_generation", "bug_analysis", "log_triage",
                "sql_analysis", "regression_planning", "report_draft", "prompt_evaluation");
        for (AiTaskCatalog.TaskDefinition definition : AiTaskCatalog.all()) {
            assertThat(definition.requiredInputs()).isNotEmpty();
            assertThat(definition.outputSchema()).isNotNull();
            assertThat(definition.humanGateRequired()).isTrue();
            assertThat(definition.toolAllowlist()).doesNotContain("execute-sql", "run-shell");
        }
    }

    @Test
    void requestValidationReportsMissingInputsInsteadOfDefaultingThem() {
        AiTaskCatalog.RequestValidation incomplete = AiTaskCatalog.validateRequest(
            "bug_analysis", json("{\"symptom\": \"支付成功后订单状态未同步\"}"));
        assertThat(incomplete.complete()).isTrue();

        AiTaskCatalog.RequestValidation missing = AiTaskCatalog.validateRequest(
            "log_triage", json("{\"logExcerpt\": \"\", \"timeRange\": [\"2026-10-10 10:00\", \"2026-10-10 10:05\"]}"));
        assertThat(missing.complete()).isFalse();
        assertThat(missing.missingInputs()).containsExactly("logExcerpt");
    }

    @Test
    void routingProposesCandidatesWithGapsAndNeverAutoTriggersWrites() {
        List<Map<String, Object>> candidates = AiTaskCatalog.routeCandidates(
            json("{\"symptom\": \"500\", \"logExcerpt\": \"stack...\"}"),
            Set.of("log_excerpt"));
        assertThat(candidates).hasSize(8);
        // 缺项最少的排在前；完全满足的候选在列。
        Map<String, Object> first = candidates.get(0);
        assertThat((List<?>) first.get("missingInputs")).isEmpty();
        // 候选只携带任务信息与缺项——没有任何“执行/触发”字段。
        assertThat(first.keySet()).containsExactlyInAnyOrder(
            "taskType", "displayName", "missingInputs", "evidenceReady", "complete");
    }

    // ---------- 输出校验：每类一个成功场景 + 一条证据不足负例 ----------

    @Test
    void bugAnalysisRejectsLocatedRootCauseWithoutEvidence() {
        JsonNode valid = json("""
            {
              "taskType": "bug_analysis",
              "summary": "支付成功后订单状态未同步",
              "hypotheses": [
                {"claim": "消费链路可能延迟", "evidenceIds": ["ev-log-1"], "status": "needs_confirmation"},
                {"claim": "定时任务未执行", "evidenceIds": [], "status": "needs_confirmation"}
              ],
              "missingInputs": ["同 traceId 消费端日志"],
              "recommendedChecks": [{"description": "核对消费结果", "readOnly": true}],
              "humanReviewRequired": true
            }
            """);
        assertThat(QaTaskOutputValidators.validate("bug_analysis", valid).valid()).isTrue();

        // T-R5 残余④统一：任务标识必须与全平台同一归一化——全角/零宽变体命中原分支而非落 unknown_task_type。
        assertThat(QaTaskOutputValidators.validate("ＢＵＧ＿ＡＮＡＬＹＳＩＳ", valid).valid()).isTrue();
        assertThat(QaTaskOutputValidators.validate("bug_analysis\u200B", valid).valid()).isTrue();

        JsonNode invalid = json("""
            {
              "taskType": "bug_analysis",
              "summary": "支付成功后订单状态未同步",
              "hypotheses": [
                {"claim": "消费链路可能延迟", "evidenceIds": [], "status": "identified"}
              ],
              "humanReviewRequired": true
            }
            """);
        QaTaskOutputValidators.Validation validation = QaTaskOutputValidators.validate("bug_analysis", invalid);
        assertThat(validation.valid()).isFalse();
        assertThat(validation.violations()).anySatisfy(violation ->
            assertThat(violation).contains("没有证据不得给「已定位根因」"));
    }

    @Test
    void logTriageRequiresTimezoneAndPerConclusionLineCitations() {
        JsonNode valid = json("""
            {
              "taskType": "log_triage",
              "timezone": "Asia/Shanghai",
              "timeline": [{"description": "请求进入", "lineIds": ["L102"]}],
              "anomalies": [{"description": "超时", "lineRefs": ["L2041"]}]
            }
            """);
        assertThat(QaTaskOutputValidators.validate("log_triage", valid).valid()).isTrue();

        JsonNode invalid = json("""
            {
              "taskType": "log_triage",
              "timeline": [{"description": "请求进入"}],
              "anomalies": [{"description": "超时", "lineIds": []}]
            }
            """);
        QaTaskOutputValidators.Validation validation = QaTaskOutputValidators.validate("log_triage", invalid);
        assertThat(validation.valid()).isFalse();
        assertThat(validation.violations()).anySatisfy(violation ->
            assertThat(violation).contains("必须保留原时区"));
        assertThat(validation.violations()).anySatisfy(violation ->
            assertThat(violation).contains("必须引用具体行"));
    }

    @Test
    void sqlAnalysisStaysStaticAndDemandsStatsForConfirmedPerformance() {
        JsonNode valid = json("""
            {
              "taskType": "sql_analysis",
              "risks": [{"description": "orders 表缺索引导致回表"}],
              "suggestions": [{"description": "为 (user_id, status) 增加联合索引"}],
              "verificationSteps": ["在测试库用 EXPLAIN 复核"]
            }
            """);
        assertThat(QaTaskOutputValidators.validate("sql_analysis", valid).valid()).isTrue();

        JsonNode executed = json("""
            {"taskType": "sql_analysis", "risks": [], "suggestions": [{"description": "x"}],
             "executionResult": {"rows": 10}}
            """);
        assertThat(QaTaskOutputValidators.validate("sql_analysis", executed).valid()).isFalse();

        JsonNode performanceClaim = json("""
            {"taskType": "sql_analysis", "risks": [{"description": "该语句已证实慢", "confirmedPerformance": true}],
             "suggestions": [{"description": "加索引"}]}
            """);
        QaTaskOutputValidators.Validation validation = QaTaskOutputValidators.validate("sql_analysis", performanceClaim);
        assertThat(validation.valid()).isFalse();
        assertThat(validation.violations()).anySatisfy(violation ->
            assertThat(violation).contains("无统计信息不得给出已证实的性能结论"));
    }

    @Test
    void regressionPlanningSelectsFromRealCasesAndSeparatesNewSuggestions() {
        JsonNode valid = json("""
            {
              "taskType": "regression_planning",
              "requiredItems": [{"caseId": "CASE-101", "title": "登录回归"}],
              "suggestedItems": [{"caseId": "CASE-103", "title": "权限校验"}],
              "newSuggestions": [{"title": "补充并发订单场景", "reason": "本次改动引入并发路径"}]
            }
            """);
        assertThat(QaTaskOutputValidators.validate("regression_planning", valid).valid()).isTrue();

        JsonNode invalid = json("""
            {
              "taskType": "regression_planning",
              "requiredItems": [{"title": "凭印象选择的回归"}],
              "newSuggestions": [{"caseId": "CASE-101", "title": "重复"}]
            }
            """);
        QaTaskOutputValidators.Validation validation = QaTaskOutputValidators.validate("regression_planning", invalid);
        assertThat(validation.valid()).isFalse();
        assertThat(validation.violations()).anySatisfy(violation ->
            assertThat(violation).contains("必须从真实用例资产选择"));
    }

    @Test
    void reportDraftCannotAlterDeterministicNumbersOrSelfApprove() {
        JsonNode facts = json("{\"passRate\": 87.5}");
        JsonNode valid = json("""
            {
              "taskType": "report_draft",
              "sourceRunIds": ["run-1", "run-2"],
              "passRate": 87.5,
              "approvalStatus": "pending_human",
              "sections": [{"title": "结论", "body": "以执行记录为准"}]
            }
            """);
        assertThat(QaTaskOutputValidators.validate("report_draft", valid, facts).valid()).isTrue();

        JsonNode tampered = json("""
            {
              "taskType": "report_draft",
              "sourceRunIds": ["run-1"],
              "passRate": 100,
              "approvalStatus": "approved",
              "sections": []
            }
            """);
        QaTaskOutputValidators.Validation validation = QaTaskOutputValidators.validate("report_draft", tampered, facts);
        assertThat(validation.valid()).isFalse();
        assertThat(validation.violations()).anySatisfy(violation ->
            assertThat(violation).contains("模型不得改动数字"));
        assertThat(validation.violations()).anySatisfy(violation ->
            assertThat(violation).contains("HUMAN_REQUIRED"));
    }

    @Test
    void promptEvaluationDistinguishesRealRequestsFromRuleChecks() {
        JsonNode valid = json("""
            {
              "taskType": "prompt_evaluation",
              "sampleResults": [{"sampleId": "s1", "realRequest": true, "score": 0.9}],
              "qualityDiff": {"baseline": "v1", "candidate": "v2"},
              "costAndLatency": {"costDelta": 0.12, "latencyDeltaMs": 320}
            }
            """);
        assertThat(QaTaskOutputValidators.validate("prompt_evaluation", valid).valid()).isTrue();

        JsonNode invalid = json("""
            {"taskType": "prompt_evaluation", "sampleResults": [{"sampleId": "s1", "score": 0.9}]}
            """);
        QaTaskOutputValidators.Validation validation = QaTaskOutputValidators.validate("prompt_evaluation", invalid);
        assertThat(validation.valid()).isFalse();
        assertThat(validation.violations()).anySatisfy(violation ->
            assertThat(violation).contains("规则校验不能冒充模型评测"));
    }

    @Test
    void requirementOutputsFlagConfirmedItemsWithoutEvidence() {
        JsonNode valid = json("""
            {"draftTestPoints": [{"title": "登录成功", "evidenceIds": ["src-1"], "status": "confirmed"}],
             "unclearQuestions": []}
            """);
        assertThat(QaTaskOutputValidators.validate("requirement_analysis", valid).valid()).isTrue();

        JsonNode invalid = json("""
            {"draftTestPoints": [{"title": "猜测的行为", "evidenceIds": [], "status": "confirmed"}]}
            """);
        QaTaskOutputValidators.Validation validation = QaTaskOutputValidators.validate("requirement_analysis", invalid);
        assertThat(validation.valid()).isFalse();
        assertThat(validation.violations()).anySatisfy(violation ->
            assertThat(violation).contains("不得标记为已确认"));
    }

    @Test
    void unknownTaskTypeFailsClosed() {
        assertThat(QaTaskOutputValidators.validate("mystery_task", json("{}")).valid()).isFalse();
        assertThat(QaTaskOutputValidators.validate("bug_analysis", null).violations())
            .containsExactly("output_missing: 任务输出为空");
    }
}
