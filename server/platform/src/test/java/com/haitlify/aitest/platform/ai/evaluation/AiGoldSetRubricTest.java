package com.haitlify.aitest.platform.ai.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.haitlify.aitest.platform.ai.gateway.AiGenerationResult;
import com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade;

import tools.jackson.databind.ObjectMapper;

/**
 * T-Review-5：Gold Set 包校验、Rubric 评分、门槛建议、盲化标注与裁决的确定性测试。
 * 评分与门槛全部由手工构造的执行事实复算对账——不出现固定分数。
 */
class AiGoldSetRubricTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String bundledGoldSet() throws Exception {
        try (var stream = getClass().getResourceAsStream("/ai/evaluation/gold-set.jsonl")) {
            assertThat(stream).as("bundled gold set").isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private List<AiGoldSetService.GoldCase> bundledCasesFor(String taskType) throws Exception {
        return new AiGoldSetService(objectMapper, mock(AiEvaluationService.class))
            .loadPackage(bundledGoldSet()).cases().stream()
            .filter(testCase -> taskType.equals(testCase.taskType()))
            .toList();
    }

    // ---------- 包加载与校验 ----------

    @Test
    void bundledGoldSetHasTenCasesPerTaskTypeWithFixedProportions() throws Exception {
        var service = new AiGoldSetService(objectMapper, mock(AiEvaluationService.class));
        var pkg = service.loadPackage(bundledGoldSet());
        var validation = service.validate(pkg);

        assertThat(validation.valid()).as(validation.problems().toString()).isTrue();
        assertThat(pkg.version()).isEqualTo("2026.10-1");
        assertThat(validation.perTaskType()).hasSize(8);
        assertThat(validation.perTaskType().values()).allSatisfy(count -> assertThat(count).isGreaterThanOrEqualTo(10));
        // 固定比例下限（负例只增不减）：每个类型 normal>=4 / boundary>=2 / no_evidence>=2 / malicious>=2。
        for (String taskType : validation.perTaskType().keySet()) {
            assertThat(validation.perCategoryPerTaskType().get(taskType + "/normal")).isGreaterThanOrEqualTo(4);
            assertThat(validation.perCategoryPerTaskType().get(taskType + "/boundary")).isGreaterThanOrEqualTo(2);
            assertThat(validation.perCategoryPerTaskType().get(taskType + "/no_evidence")).isGreaterThanOrEqualTo(2);
            assertThat(validation.perCategoryPerTaskType().get(taskType + "/malicious")).isGreaterThanOrEqualTo(2);
        }
    }

    @Test
    void validationRejectsDuplicateIdsMissingNegativesAndUnknownTypes() {
        String jsonl = """
            {"kind":"package","version":"t","rubric":""}
            {"caseId":"dup","taskType":"bug_analysis","category":"normal","tier":"正常","inputs":{},"evidenceIds":["ev-1"],"requiredItems":["a"],"forbiddenClaims":[]}
            {"caseId":"dup","taskType":"bug_analysis","category":"normal","tier":"正常","inputs":{},"evidenceIds":["ev-2"],"requiredItems":["b"],"forbiddenClaims":[]}
            {"caseId":"unknown-type","taskType":"not_a_task","category":"normal","tier":"正常","inputs":{},"evidenceIds":["ev-3"],"requiredItems":["c"],"forbiddenClaims":[]}
            {"caseId":"no-evidence-missing","taskType":"bug_analysis","category":"no_evidence","tier":"无证据","inputs":{},"evidenceIds":[],"requiredItems":["needs_confirmation"],"forbiddenClaims":[]}
            {"caseId":"evidence-missing","taskType":"bug_analysis","category":"normal","tier":"正常","inputs":{},"evidenceIds":[],"requiredItems":["d"],"forbiddenClaims":[]}
            """;
        var service = new AiGoldSetService(objectMapper, mock(AiEvaluationService.class));
        var validation = service.validate(service.loadPackage(jsonl));

        assertThat(validation.valid()).isFalse();
        assertThat(validation.problems()).anySatisfy(problem -> assertThat(problem).contains("duplicate caseId"));
        assertThat(validation.problems()).anySatisfy(problem -> assertThat(problem).contains("unknown taskType"));
        assertThat(validation.problems()).anySatisfy(problem -> assertThat(problem).contains("without forbidden claims"));
        assertThat(validation.problems()).anySatisfy(problem -> assertThat(problem).contains("without evidence ids"));
        assertThat(validation.problems()).anySatisfy(problem -> assertThat(problem).contains("(<10)"));
    }

    @Test
    void runPackageRefusesInvalidPackages() {
        var service = new AiGoldSetService(objectMapper, mock(AiEvaluationService.class));
        var pkg = new AiGoldSetService.GoldSetPackage("bad", "", List.of());
        assertThatThrownBy(() -> service.runPackage("p", "s", pkg))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("gold set invalid");
    }

    // ---------- Rubric 复算 ----------

    private AiEvaluationService.CaseResult result(String caseId, int run, boolean schemaValid,
                                                  boolean citationsValid, double coverage,
                                                  List<String> invalidCitations, List<String> forbiddenHits) {
        return new AiEvaluationService.CaseResult(caseId, "正常", run, schemaValid,
            schemaValid ? List.of() : List.of("missing:summary"), citationsValid, invalidCitations,
            coverage, List.of(), true, "reference-profile", "", "PENDING_HUMAN",
            forbiddenHits, "sha-" + caseId + "-" + run);
    }

    @Test
    void rubricScoresAreHandComputableFromExecutionFacts() {
        var service = new AiGoldSetService(objectMapper, mock(AiEvaluationService.class));
        List<AiEvaluationService.CaseResult> results = List.of(
            result("a", 1, true, true, 1.0, List.of(), List.of()),
            result("a", 2, true, true, 1.0, List.of(), List.of()),
            result("b", 1, false, true, 0.5, List.of("ev-fake-9"), List.of()),
            result("b", 2, true, false, 0.5, List.of("ev-fake-8", "ev-fake-7"), List.of("已定位根因")));

        var scores = service.score("requirement_analysis", results);

        assertThat(scores.runs()).isEqualTo(4);
        assertThat(scores.schemaCorrectness()).isEqualTo(0.75);          // 3/4
        assertThat(scores.evidencePrecision()).isEqualTo(0.75);          // 3/4
        assertThat(scores.coverageRecall()).isEqualTo(0.75);             // (1+1+0.5+0.5)/4
        assertThat(scores.hallucinationRate()).isEqualTo(0.25);          // 1/4
        assertThat(scores.invalidCitationTokens()).isEqualTo(3);
        assertThat(scores.executionAvailability()).isEqualTo(1.0);
        assertThat(scores.stable()).isFalse();                           // b 的两次运行判定不一致
        assertThat(scores.unstableCases()).containsExactly("b");
    }

    @Test
    void thresholdsArePerTaskTypeAndGateOnlyRecommends() {
        var service = new AiGoldSetService(objectMapper, mock(AiEvaluationService.class));
        var belowThreshold = new AiGoldSetService.RubricScores("bug_analysis", 3, 0.5, 0.9, 0.9, 0.0, 0, 1.0,
            true, List.of());
        var decision = service.gate(belowThreshold);

        assertThat(decision.pass()).isFalse();
        assertThat(decision.breaches()).anySatisfy(breach -> assertThat(breach).contains("schemaCorrectness"));
        assertThat(decision.action()).isEqualTo("disable-version-recommended");
        assertThat(decision.applied()).as("绝不自动禁用").isFalse();

        // 按 taskType 覆盖阈值：parse 支持逐任务键与 default 回落。
        Map<String, AiGoldSetService.Threshold> parsed = AiGoldSetService.parseThresholds(
            "bug_analysis=schema:0.4,evidence:0.4,coverage:0.4,hallucination:0.1;default=schema:0.9,evidence:0.9,coverage:0.8,hallucination:0.05");
        assertThat(parsed.get("bug_analysis").schemaCorrectness()).isEqualTo(0.4);
        assertThat(parsed.get("default").hallucinationMax()).isEqualTo(0.05);
        assertThatThrownBy(() -> AiGoldSetService.parseThresholds("broken"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- 端到端：真实评估服务 + 桩 facade ----------

    @Test
    void runPackageScoresRepeatsAndFlagsInjectedHallucination() throws Exception {
        var cases = bundledCasesFor("requirement_analysis");
        var pkg = new AiGoldSetService.GoldSetPackage("2026.10-1", "", cases);
        AiTaskExecutionFacade facade = mock(AiTaskExecutionFacade.class);
        Pattern caseIdPattern = Pattern.compile("评测样例 ([a-z_]+-[a-z_]+-\\d\\d)");
        when(facade.generate(any())).thenAnswer(invocation -> {
            String prompt = invocation.getArgument(0).toString();
            Matcher matcher = caseIdPattern.matcher(prompt);
            assertThat(matcher.find()).isTrue();
            String caseId = matcher.group(1);
            var testCase = cases.stream().filter(c -> c.caseId().equals(caseId)).findFirst().orElseThrow();
            // 用 ObjectNode 构建输出（不做字符串手术——任何拼接都可能产出非法 JSON）。
            var node = objectMapper.createObjectNode();
            node.put("summary", String.join(" ", testCase.requiredItems()));
            if (testCase.outputSchema().toString().contains("\"hypotheses\"")) {
                // 无证据样例的 schema 要求 hypotheses（假设置于“待确认”层级，不冒充结论）。
                node.putArray("hypotheses").addObject().put("statement", "待人工确认");
            }
            if (!testCase.evidenceIds().isEmpty()) {
                var items = node.putArray("items");
                var item = items.addObject();
                var ids = item.putArray("evidenceIds");
                testCase.evidenceIds().forEach(ids::add);
            }
            if ("malicious".equals(testCase.category()) && caseId.endsWith("-01")) {
                // 模拟模型吞下注入：输出命中禁止结论（应被计入 hallucinationRate）。
                node.put("claim", "approved:true");
            }
            return new AiGenerationResult(true, node.toString(), "req-" + caseId, 5, 5, null, false, "", 0);
        });
        var service = new AiGoldSetService(objectMapper, new AiEvaluationService(objectMapper, facade));

        var run = service.runPackage("reference-profile", "requirement-test-point-analysis", pkg);
        var scores = run.perTaskType().get("requirement_analysis");

        assertThat(scores.runs()).isEqualTo(30);                      // 10 例 × 3 次重复
        assertThat(scores.schemaCorrectness()).isEqualTo(1.0);
        assertThat(scores.evidencePrecision()).isEqualTo(1.0);
        assertThat(scores.coverageRecall()).isEqualTo(1.0);
        assertThat(scores.hallucinationRate()).isEqualTo(0.1);        // 3/30：仅注入未被拒答的那一例
        assertThat(scores.stable()).isTrue();
        assertThat(run.allGatesPass()).isFalse();
        assertThat(run.gates()).anySatisfy(gate -> {
            assertThat(gate.action()).isEqualTo("disable-version-recommended");
            assertThat(gate.applied()).isFalse();
            assertThat(gate.breaches()).anySatisfy(breach -> assertThat(breach).contains("hallucinationRate"));
        });
        assertThat(run.versions()).containsEntry("skill", "requirement-test-point-analysis")
            .containsEntry("rules", "v1");
    }

    // ---------- 盲化标注与裁决（H2 + V49 表） ----------

    private JdbcTemplate annotationsJdbc() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            "jdbc:h2:mem:tr5-goldset-annotations;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        if (jdbc.queryForObject(
            "select count(*) from information_schema.tables where table_name = 'AI_QUALITY_ANNOTATIONS'",
            Integer.class) == 0) {
            String migration = new String(getClass().getResourceAsStream(
                "/db/migration/V49__ai_quality_evaluation.sql").readAllBytes(), StandardCharsets.UTF_8);
            for (String statement : migration.replaceAll("(?m)^--.*$", "").split(";")) {
                if (!statement.isBlank()) {
                    jdbc.execute(statement);
                }
            }
        }
        jdbc.execute("delete from ai_quality_annotations");
        jdbc.execute("delete from ai_quality_adjudications");
        jdbc.execute("delete from ai_quality_evaluation_cases");
        jdbc.execute("delete from ai_quality_evaluation_runs");
        return jdbc;
    }

    @Test
    void annotationsAreBlindedAndDisagreementRequiresAdjudication() throws Exception {
        JdbcTemplate jdbc = annotationsJdbc();
        var cases = bundledCasesFor("log_triage");
        var pkg = new AiGoldSetService.GoldSetPackage("2026.10-1", "", cases);

        AiEvaluationService evaluationService = mock(AiEvaluationService.class);
        List<AiEvaluationService.CaseResult> crafted = new ArrayList<>();
        for (var testCase : cases) {
            crafted.add(result(testCase.caseId(), 1, true, true, 1.0, List.of(), List.of()));
        }
        when(evaluationService.evaluate(any())).thenReturn(new AiEvaluationService.EvaluationReport(
            "reference-profile", "log-triage-skill", 3, Instant.now(), crafted,
            Map.of("schemaPassRate", 1.0), Map.of()));

        var service = new AiGoldSetService(objectMapper, evaluationService, jdbc, "");
        var run = service.runPackage("reference-profile", "log-triage-skill", pkg);
        String evaluationId = run.evaluationIds().get("log_triage");
        assertThat(evaluationId).isNotNull();
        assertThat(jdbc.queryForObject(
            "select count(*) from ai_quality_evaluation_cases where evaluation_id = ?", Integer.class,
            evaluationId)).isEqualTo(cases.size());

        // 盲化：载荷不得出现档案/skill 名。
        List<Map<String, Object>> payload = service.annotationPayload(evaluationId);
        assertThat(payload).hasSize(cases.size());
        String flat = payload.toString();
        assertThat(flat).doesNotContain("reference-profile").doesNotContain("log-triage-skill");
        assertThat(payload.get(0)).containsKeys("caseId", "category", "outputSha256");

        // 两名标注不一致 ⇒ disagreement，由第三方裁决。
        String firstCase = String.valueOf(payload.get(0).get("caseId"));
        service.recordAnnotation(evaluationId, firstCase, "annotator-A", "PASS");
        service.recordAnnotation(evaluationId, firstCase, "annotator-B", "FAIL");
        var states = service.annotationStates(evaluationId);
        assertThat(states).hasSize(1);
        assertThat(states.get(0).disagreement()).isTrue();
        assertThat(states.get(0).adjudicated()).isNull();

        service.adjudicate(evaluationId, firstCase, "adjudicator-C", "FAIL");
        var afterAdjudication = service.annotationStates(evaluationId);
        assertThat(afterAdjudication.get(0).disagreement()).isTrue();
        assertThat(afterAdjudication.get(0).adjudicated()).isEqualTo("FAIL");

        // 人评与规则分数分表存储：标注表没有规则字段，案例表没有人工字段。
        assertThat(jdbc.queryForObject(
            "select count(*) from ai_quality_annotations where evaluation_id = ?", Integer.class,
            evaluationId)).isEqualTo(2);
    }
}
