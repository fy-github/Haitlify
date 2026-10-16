package com.haitlify.aitest.platform.ai.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.haitlify.aitest.platform.ai.gateway.AiGenerationResult;
import com.haitlify.aitest.platform.ai.knowledge.AiKnowledgeFeedbackService;
import com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * T13 批次 3：Gold Set 评测框架 + 知识反馈——指标全部来自执行事实（无固定 0.8/0.9）、
 * 编造/跨项目引用判无效、人评单列 PENDING_HUMAN、3 次重复、评价集不得发布为生产知识。
 */
class AiEvaluationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AiTaskExecutionFacade facade;
    private AiEvaluationService evaluationService;

    @BeforeEach
    void setUp() {
        facade = mock(AiTaskExecutionFacade.class);
        evaluationService = new AiEvaluationService(objectMapper, facade);
    }

    private JsonNode goldSet() throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream("ai/evaluation/gold-set.sample.json")) {
            assertThat(stream).isNotNull();
            return objectMapper.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /** 依据 prompt 里的 caseId 返回各自的输出：其中一条编造引用、一条 schema 违例、一条失败。 */
    private void stubFacade() {
        when(facade.generate(any())).thenAnswer(invocation -> {
            String prompt = invocation.getArgument(0).toString();
            if (prompt.contains("cross-model")) {
                return new AiGenerationResult(false, null, "", 0, 0, "模型不可用", false, "no_route", 0);
            }
            // 从 prompt 中取出本案例提供的证据 id（“仅依据提供的证据作答”的模拟）。
            var matcher = java.util.regex.Pattern.compile("\"(ev-[a-z0-9-]+)\"").matcher(prompt);
            String citedId = matcher.find() ? matcher.group(1) : "";
            if (prompt.contains("chart-render")) {
                // 编造引用：ev-chart-9 不属于本案例证据集（真实证据是 ev-chart-1）。
                return new AiGenerationResult(true,
                    "{\"summary\":\"axis-labels ok\",\"evidenceIds\":[\"ev-chart-9\"]}",
                    "req-x", 5, 5, null, false, "", 0);
            }
            if (prompt.contains("format-json-strict")) {
                // schema 违例：summary 必须是 string，这里是数组；但必达项仍出现在输出里。
                return new AiGenerationResult(true,
                    "{\"summary\":[\"not-a-string\"],\"note\":\"extra-field-rejected\"}", "req-y", 5, 5,
                    null, false, "", 0);
            }
            if (prompt.contains("no-evidence")) {
                // 无证据案例：schema 要求 hypotheses；推断必须标注 needs_confirmation。
                return new AiGenerationResult(true,
                    "{\"summary\":\"偶发失败\",\"hypotheses\":[{\"claim\":\"待复现\",\"evidenceIds\":[],\"status\":\"needs_confirmation\"}],\"note\":\"needs_confirmation\"}",
                    "req-n", 5, 5, null, false, "", 0);
            }
            String evidence = citedId.isBlank() ? "" : ",\"evidenceIds\":[\"" + citedId + "\"]";
            return new AiGenerationResult(true,
                "{\"summary\":\"ok\"" + evidence
                    + ",\"note\":\"login-redirect pagination role-gate needs_confirmation axis-labels extra-field-rejected truncation-note\"}",
                "req-z", 5, 5, null, false, "", 0);
        });
    }

    @Test
    void metricsAreComputedFromFactsAcrossTiersAndRepeatsWithoutFixedScores() throws Exception {
        stubFacade();
        AiEvaluationService.EvaluationSpec spec = evaluationService.loadSpec(goldSet());
        assertThat(spec.repeats()).isEqualTo(3);
        assertThat(spec.cases()).hasSize(8);

        AiEvaluationService.EvaluationReport report = evaluationService.evaluate(spec);
        // 8 案例 × 3 次重复 = 24 次真实请求，各自的 attempt 结果都在案。
        assertThat(report.cases()).hasSize(24);
        assertThat(report.cases()).extracting(AiEvaluationService.CaseResult::tier)
            .contains("正常", "边界", "权限", "无证据", "图表", "格式", "拒绝/截断", "跨模型");

        Map<String, Double> metrics = report.metrics();
        // 精确分数说明（全部来自事实计数）：24 条中 cross-model 3 条失败 ⇒ 可用率 21/24；
        // 图表 3 条编造引用 + cross 3 条失败 ⇒ 引用有效率 18/24；格式 3 条 schema 违例 + cross ⇒ 18/24；
        // 失败行覆盖记 0 ⇒ 覆盖率 21/24（其余成功行全部覆盖必达项）。
        assertThat(metrics.get("executionAvailabilityRate")).isEqualTo(21d / 24d);
        assertThat(metrics.get("citationValidRate")).isEqualTo(18d / 24d);
        assertThat(metrics.get("schemaPassRate")).isEqualTo(18d / 24d);
        assertThat(metrics.get("coverageRate")).isEqualTo(21d / 24d);
        // 没有任何固定 0.8/0.9 的「质量提升」捏造值。
        assertThat(metrics.values()).allSatisfy(value ->
            assertThat(value).isNotEqualTo(0.8d).isNotEqualTo(0.9d));
    }

    @Test
    void fabricatedAndCrossProjectCitationsAreFlaggedAsInvalid() throws Exception {
        stubFacade();
        AiEvaluationService.EvaluationReport report = evaluationService.evaluate(
            evaluationService.loadSpec(goldSet()));
        List<AiEvaluationService.CaseResult> chart = report.cases().stream()
            .filter(result -> "图表".equals(result.tier())).toList();
        assertThat(chart).isNotEmpty();
        for (AiEvaluationService.CaseResult result : chart) {
            assertThat(result.citationsValid()).isFalse();
            assertThat(result.invalidCitations()).contains("ev-chart-9");
        }
    }

    @Test
    void humanReviewIsAlwaysPendingAndNeverFabricated() throws Exception {
        stubFacade();
        AiEvaluationService.EvaluationReport report = evaluationService.evaluate(
            evaluationService.loadSpec(goldSet()));
        assertThat(report.cases()).allSatisfy(result ->
            assertThat(result.humanScore()).isEqualTo("PENDING_HUMAN"));
        assertThat(report.notes().get("humanReview")).contains("PENDING_HUMAN");
    }

    @Test
    void baselineComparisonUsesOnlyComputedDeltas() throws Exception {
        stubFacade();
        AiEvaluationService.EvaluationSpec spec = evaluationService.loadSpec(goldSet());
        // 同 spec 两次运行：确定性指标的差值应为 0（无随机分数）。
        AiEvaluationService.EvaluationReport baseline = evaluationService.evaluate(spec);
        AiEvaluationService.EvaluationReport candidate = evaluationService.evaluate(spec);
        Map<String, Double> deltas = evaluationService.compare(baseline, candidate);
        assertThat(deltas.get("executionAvailabilityRate")).isZero();
        assertThat(deltas.get("citationValidRate")).isZero();

        // 与「全成功」报告对比：差值必须是可计算的 3/24（可用率）与 6/24（schema pass）。
        AiEvaluationService.EvaluationReport improved = new AiEvaluationService.EvaluationReport(
            baseline.profileRef(), baseline.skillRef(), baseline.repeats(), java.time.Instant.now(),
            baseline.cases(),
            Map.of("executionAvailabilityRate", 1d, "citationValidRate", 1d,
                "schemaPassRate", 1d, "coverageRate", 1d),
            Map.of());
        Map<String, Double> improvedDeltas = evaluationService.compare(baseline, improved);
        assertThat(improvedDeltas.get("executionAvailabilityRate")).isEqualTo(1d - 21d / 24d);
        assertThat(improvedDeltas.get("schemaPassRate")).isEqualTo(1d - 18d / 24d);
    }

    @Test
    void unknownTaskOrMissingCasesFailClosed() {
        assertThatThrownBy(() -> evaluationService.evaluate(null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> evaluationService.evaluate(new AiEvaluationService.EvaluationSpec(
            "p", "s", 1, List.of())))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void alternateCitationShapesCannotSmuggleFabricatedReferences() {
        // T-R5 轮 2（探针 D2–D4）：换键名/单数键/正文提及都必须被判无效。
        when(facade.generate(any())).thenAnswer(invocation -> {
            String prompt = invocation.getArgument(0).toString();
            if (prompt.contains("shape-sources")) {
                return new AiGenerationResult(true,
                    "{\"summary\":\"ok\",\"sources\":[\"ev-fake-9\"]}", "req-s", 5, 5, null, false, "", 0);
            }
            if (prompt.contains("shape-singular")) {
                return new AiGenerationResult(true,
                    "{\"summary\":\"ok\",\"hypotheses\":[{\"evidenceId\":\"ev-fake-9\"}]}",
                    "req-t", 5, 5, null, false, "", 0);
            }
            return new AiGenerationResult(true,
                "{\"summary\":\"ok，参见 ev-fake-9 的日志\"}", "req-u", 5, 5, null, false, "", 0);
        });
        var spec = new AiEvaluationService.EvaluationSpec("profile-x", "skill-x", 1, List.of(
            new AiEvaluationService.EvaluationCase("shape-sources", "格式",
                objectMapper.createObjectNode(), objectMapper.createObjectNode(),
                java.util.Set.of("ev-ok-1"), java.util.Set.of()),
            new AiEvaluationService.EvaluationCase("shape-singular", "格式",
                objectMapper.createObjectNode(), objectMapper.createObjectNode(),
                java.util.Set.of("ev-ok-1"), java.util.Set.of()),
            new AiEvaluationService.EvaluationCase("shape-prose", "格式",
                objectMapper.createObjectNode(), objectMapper.createObjectNode(),
                java.util.Set.of("ev-ok-1"), java.util.Set.of())));

        AiEvaluationService.EvaluationReport report = evaluationService.evaluate(spec);
        assertThat(report.cases()).hasSize(3).allSatisfy(result -> {
            assertThat(result.citationsValid()).isFalse();
            assertThat(result.invalidCitations()).contains("ev-fake-9");
        });
        assertThat(report.metrics().get("citationValidRate")).isEqualTo(0d);
    }

    @Test
    void nestedContainersDoNotCrashTheEvaluationAndOrdinaryFieldsAreNotMisflagged() {
        when(facade.generate(any())).thenAnswer(invocation -> {
            String prompt = invocation.getArgument(0).toString();
            if (prompt.contains("nested-sources")) {
                // 常见形状：引用数组里是对象（改前在此抛 JsonNodeException → 入口 500）。
                return new AiGenerationResult(true,
                    "{\"summary\":\"ok\",\"sources\":[{\"url\":\"https://example/a\"},{\"id\":\"ev-fake-9\"}]}",
                    "req-n", 5, 5, null, false, "", 0);
            }
            if (prompt.contains("ordinary-lines")) {
                // timeline/deadline 等普通字段不得被 'line' 子串误判为引用。
                return new AiGenerationResult(true,
                    "{\"summary\":\"ok\",\"timeline\":[\"请求进入\",\"响应返回\"],\"deadlineMs\":30000}",
                    "req-o", 5, 5, null, false, "", 0);
            }
            if (prompt.contains("upper-case")) {
                return new AiGenerationResult(true, "{\"summary\":\"见 EV-FAKE-9 与 chunk-fake-7\"}",
                    "req-u", 5, 5, null, false, "", 0);
            }
            return new AiGenerationResult(true, "{\"summary\":\"ok\"}", "req-z", 5, 5, null, false, "", 0);
        });
        var spec = new AiEvaluationService.EvaluationSpec("profile-x", "skill-x", 1, List.of(
            new AiEvaluationService.EvaluationCase("nested-sources", "格式",
                objectMapper.createObjectNode(), objectMapper.createObjectNode(),
                java.util.Set.of("ev-ok-1"), java.util.Set.of()),
            new AiEvaluationService.EvaluationCase("ordinary-lines", "格式",
                objectMapper.createObjectNode(), objectMapper.createObjectNode(),
                java.util.Set.of("ev-ok-1"), java.util.Set.of()),
            new AiEvaluationService.EvaluationCase("upper-case", "格式",
                objectMapper.createObjectNode(), objectMapper.createObjectNode(),
                java.util.Set.of("ev-ok-1"), java.util.Set.of())));

        var report = evaluationService.evaluate(spec);
        assertThat(report.cases()).hasSize(3);
        var nested = report.cases().get(0);
        assertThat(nested.citationsValid()).isFalse();
        assertThat(nested.invalidCitations()).contains("ev-fake-9");
        // 普通字段不误报。
        assertThat(report.cases().get(1).citationsValid()).isTrue();
        // 大写与非 ev 家族前缀同样被判无效。
        var upper = report.cases().get(2);
        assertThat(upper.citationsValid()).isFalse();
        assertThat(upper.invalidCitations()).contains("EV-FAKE-9").contains("chunk-fake-7");
    }

    @Test
    void fabricatedReferencesInRunAndVisFamiliesOrHiddenInKeysAreFlagged() {
        when(facade.generate(any())).thenAnswer(invocation -> {
            String prompt = invocation.getArgument(0).toString();
            if (prompt.contains("run-family")) {
                return new AiGenerationResult(true,
                    "{\"summary\":\"见 run-fake-1 与 VIS-FAKE-2\"}", "req-r", 5, 5, null, false, "", 0);
            }
            if (prompt.contains("key-hidden")) {
                return new AiGenerationResult(true,
                    "{\"summary\":\"ok\",\"ev-fake-9\":\"note\"}", "req-k", 5, 5, null, false, "", 0);
            }
            if (prompt.contains("case-job-family")) {
                return new AiGenerationResult(true,
                    "{\"summary\":\"见 case-fake-5 / job-fake-6\"}", "req-cj", 5, 5, null, false, "", 0);
            }
            if (prompt.contains("case-sensitive-ok")) {
                return new AiGenerationResult(true,
                    "{\"summary\":\"ok\",\"evidenceIds\":[\"EV-REAL-1\"]}", "req-c", 5, 5, null, false, "", 0);
            }
            // run-time/job-based 等含家族前缀但不含数字的散文不得误报；日期形态亦不算引用
            // （自由文本与引用语义键下的标量值两个分支必须同口径）。
            return new AiGenerationResult(true,
                "{\"summary\":\"run-time 与 job-based 的普通描述，时间窗 log-2024-01-05\","
                    + "\"sourceLog\":\"log-2024-01-05\"}", "req-p",
                5, 5, null, false, "", 0);
        });
        var spec = new AiEvaluationService.EvaluationSpec("profile-x", "skill-x", 1, List.of(
            new AiEvaluationService.EvaluationCase("case-job-family", "格式",
                objectMapper.createObjectNode(), objectMapper.createObjectNode(),
                java.util.Set.of("ev-ok-1"), java.util.Set.of()),
            new AiEvaluationService.EvaluationCase("run-family", "格式",
                objectMapper.createObjectNode(), objectMapper.createObjectNode(),
                java.util.Set.of("ev-ok-1"), java.util.Set.of()),
            new AiEvaluationService.EvaluationCase("key-hidden", "格式",
                objectMapper.createObjectNode(), objectMapper.createObjectNode(),
                java.util.Set.of("ev-ok-1"), java.util.Set.of()),
            new AiEvaluationService.EvaluationCase("case-sensitive-ok", "格式",
                objectMapper.createObjectNode(), objectMapper.createObjectNode(),
                java.util.Set.of("ev-real-1"), java.util.Set.of()),
            new AiEvaluationService.EvaluationCase("prose-ok", "格式",
                objectMapper.createObjectNode(), objectMapper.createObjectNode(),
                java.util.Set.of("ev-ok-1"), java.util.Set.of())));

        var report = evaluationService.evaluate(spec);
        var caseJob = report.cases().get(0);
        assertThat(caseJob.citationsValid()).isFalse();
        assertThat(caseJob.invalidCitations()).contains("case-fake-5").contains("job-fake-6");
        var runFamily = report.cases().get(1);
        assertThat(runFamily.citationsValid()).isFalse();
        assertThat(runFamily.invalidCitations()).contains("run-fake-1").contains("VIS-FAKE-2");
        var keyHidden = report.cases().get(2);
        assertThat(keyHidden.citationsValid()).isFalse();
        assertThat(keyHidden.invalidCitations()).contains("ev-fake-9");
        // 大小写不敏感比对：EV-REAL-1 与证据集 ev-real-1 视为同一 id。
        assertThat(report.cases().get(3).citationsValid()).isTrue();
        // 无数字的散文词不误报。
        assertThat(report.cases().get(4).citationsValid()).isTrue();
    }

    // ---------- 知识反馈 ----------

    private JdbcTemplate knowledgeJdbc() {
        var dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:knowledge-feedback;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
            create table if not exists ai_knowledge_documents(
              id bigint auto_increment primary key, knowledge_id varchar(128) not null, project_id bigint not null,
              title varchar(512) not null, status varchar(16) not null, version int default 1,
              content clob not null, source_ref varchar(255) default '', published_at timestamp,
              created_at timestamp, updated_at timestamp)
            """);
        jdbcTemplate.execute("delete from ai_knowledge_documents");
        return jdbcTemplate;
    }

    @Test
    void feedbackCandidatesStartAsDraftAndPublishingRequiresAnActor() {
        AiKnowledgeFeedbackService feedbackService = new AiKnowledgeFeedbackService(knowledgeJdbc());
        AiKnowledgeFeedbackService.KnowledgeCandidate candidate = feedbackService.submitCandidate(
            1L, "订单同步排查经验", "先看消费端日志再看定时任务", "artifact-1");
        assertThat(candidate.status()).isEqualTo(AiKnowledgeFeedbackService.STATUS_DRAFT);
        assertThat(candidate.publishedAt()).isEmpty();

        // 未认证 actor 不得发布。
        assertThatThrownBy(() -> feedbackService.publish(candidate.knowledgeId(), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("actor required");
    }

    @Test
    void evaluationMaterialCanNeverBePublishedAsProductionKnowledge() {
        AiKnowledgeFeedbackService feedbackService = new AiKnowledgeFeedbackService(knowledgeJdbc());
        AiKnowledgeFeedbackService.KnowledgeCandidate evaluationCandidate =
            feedbackService.submitEvaluationCandidate(1L, "评测样例结论", "ev-…", "run-eval-1");
        assertThat(evaluationCandidate.status()).isEqualTo(AiKnowledgeFeedbackService.STATUS_DRAFT);

        // 即使有已认证 actor，评测来源也被拒绝发布——否则模型可以背下评价集作弊。
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("qa-user", null, List.of()));
        try {
            assertThatThrownBy(() -> feedbackService.publish(evaluationCandidate.knowledgeId(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("评价集不得作为生产知识发布");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void publishingAHumanConfirmedCandidateMarksItPublished() {
        AiKnowledgeFeedbackService feedbackService = new AiKnowledgeFeedbackService(knowledgeJdbc());
        AiKnowledgeFeedbackService.KnowledgeCandidate candidate = feedbackService.submitCandidate(
            2L, "支付链路排查", "先核对消费延迟", "artifact-2");

        AiKnowledgeFeedbackService.KnowledgeCandidate published = feedbackService.publish(
            candidate.knowledgeId(), "qa-user");
        assertThat(published.status()).isEqualTo(AiKnowledgeFeedbackService.STATUS_PUBLISHED);
        assertThat(published.publishedAt()).isNotEmpty();
        // 再次发布不是草稿 ⇒ 拒绝。
        assertThatThrownBy(() -> feedbackService.publish(candidate.knowledgeId(), "qa-user"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not_a_draft");
        assertThat(feedbackService.listByProject(2L)).hasSize(1);
    }
}
