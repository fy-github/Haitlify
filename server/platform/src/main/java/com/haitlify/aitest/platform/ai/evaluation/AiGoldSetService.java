package com.haitlify.aitest.platform.ai.evaluation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * T-Review-5：Gold Set 包管理与质量评分 Rubric。
 *
 * <ul>
 *   <li>gold-set.jsonl 逐行加载并校验：每类任务 ≥10 例、固定比例下限（正常≥4/边界≥2/无证据≥2/恶意≥2，负例只增不减）、
 *       任一 taskType 必须在任务目录内、caseId 唯一、无证据与恶意例必须携带禁止结论清单、有证据例必须携带证据集；</li>
 *   <li>评分公式（全部由执行事实确定性计算，绝不出现固定 0.8/0.9 分数）：
 *       schemaCorrectness=通过数/运行数；evidencePrecision=无无效引用数/运行数（附原始无效引用计数）；
 *       coverageRecall=必达项覆盖均值；hallucinationRate=命中禁止结论的运行数/运行数；
 *       executionAvailability=attempt 成功数/运行数；stable=同一 caseId 的多次运行四项判定逐位一致；</li>
 *   <li>阈值按 taskType 可配置（{@code app.ai.evaluation.thresholds}），低于门槛输出
 *       {@code disable-version-recommended} 建议与依据——<b>绝不自动禁用</b>，由独立操作执行；</li>
 *   <li>人评：标注者只拿到盲化载荷（无档案/模型/skill 名），人评与规则分数分表存储；两名标注分歧进入
 *       adjudication，由第三方裁决；</li>
 *   <li>版本写入：gold-set 包版本、profile、skill、模型引用与规则版本随结果持久化（V49）。</li>
 * </ul>
 */
@Service
public class AiGoldSetService {

    /** 阈值默认值：可按 taskType 覆盖（见 {@link #parseThresholds}）。 */
    public static final String DEFAULT_THRESHOLDS =
        "default=schema:0.9,evidence:0.9,coverage:0.8,hallucination:0.05";

    public record GoldCase(
        String caseId,
        String taskType,
        String category,
        String tier,
        String source,
        JsonNode inputs,
        JsonNode outputSchema,
        Set<String> evidenceIds,
        Set<String> requiredItems,
        Set<String> forbiddenClaims,
        Set<String> allowedVariants
    ) {
    }

    public record GoldSetPackage(String version, String rubricRef, List<GoldCase> cases) {
    }

    public record PackageValidation(
        boolean valid,
        String version,
        Map<String, Integer> perTaskType,
        Map<String, Integer> perCategoryPerTaskType,
        List<String> problems
    ) {
    }

    public record RubricScores(
        String taskType,
        int runs,
        double schemaCorrectness,
        double evidencePrecision,
        double coverageRecall,
        double hallucinationRate,
        long invalidCitationTokens,
        double executionAvailability,
        boolean stable,
        List<String> unstableCases
    ) {
    }

    public record GateDecision(String taskType, boolean pass, List<String> breaches, String action, boolean applied) {
    }

    public record PackageRun(
        String packageVersion,
        Instant generatedAt,
        Map<String, RubricScores> perTaskType,
        Map<String, String> evaluationIds,
        List<GateDecision> gates,
        Map<String, String> versions
    ) {
        public boolean allGatesPass() {
            return gates.stream().allMatch(GateDecision::pass);
        }
    }

    public record AnnotationState(String caseId, List<String> annotators, boolean disagreement, String adjudicated) {
    }

    /** 单 taskType 的阈值（taskType 未配置时回落到 default）。 */
    public record Threshold(double schemaCorrectness, double evidencePrecision, double coverageRecall,
                            double hallucinationMax) {
    }

    private static final Set<String> REQUIRED_MINIMUMS = Set.of("normal", "boundary", "no_evidence", "malicious");
    private static final Map<String, Integer> MINIMUM_PER_CATEGORY = Map.of(
        "normal", 4, "boundary", 2, "no_evidence", 2, "malicious", 2);

    private final ObjectMapper objectMapper;
    private final AiEvaluationService evaluationService;
    private final JdbcTemplate jdbcTemplate;
    private final Map<String, Threshold> thresholds;

    @Autowired
    public AiGoldSetService(ObjectMapper objectMapper, AiEvaluationService evaluationService,
                            JdbcTemplate jdbcTemplate,
                            @Value("${app.ai.evaluation.thresholds:}") String thresholdsConfig) {
        this.objectMapper = objectMapper;
        this.evaluationService = evaluationService;
        this.jdbcTemplate = jdbcTemplate;
        this.thresholds = parseThresholds(thresholdsConfig == null || thresholdsConfig.isBlank()
            ? DEFAULT_THRESHOLDS : thresholdsConfig);
    }

    /** 单测便捷构造（无持久化、默认阈值）。 */
    public AiGoldSetService(ObjectMapper objectMapper, AiEvaluationService evaluationService) {
        this(objectMapper, evaluationService, null, "");
    }

    // ---------- 包加载与校验 ----------

    /** 逐行解析 gold-set.jsonl（首行可为 package 头；其余每行一个案例）。 */
    public GoldSetPackage loadPackage(String jsonlContent) {
        if (jsonlContent == null || jsonlContent.isBlank()) {
            throw new IllegalArgumentException("gold set content required");
        }
        String version = "";
        String rubricRef = "";
        List<GoldCase> cases = new ArrayList<>();
        int lineNumber = 0;
        for (String rawLine : jsonlContent.split("\\R")) {
            lineNumber++;
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            JsonNode node;
            try {
                node = objectMapper.readTree(line);
            } catch (RuntimeException malformed) {
                throw new IllegalArgumentException("gold set line " + lineNumber + " malformed: "
                    + safe(malformed.getMessage()));
            }
            if (!node.isObject()) {
                throw new IllegalArgumentException("gold set line " + lineNumber + " must be an object");
            }
            if ("package".equals(node.path("kind").asText(""))) {
                if (!cases.isEmpty()) {
                    throw new IllegalArgumentException("package header must be the first content line");
                }
                version = node.path("version").asText("");
                rubricRef = node.path("rubric").asText("");
                continue;
            }
            Set<String> evidence = new LinkedHashSet<>();
            node.path("evidenceIds").forEach(item -> evidence.add(item.asText()));
            Set<String> required = new LinkedHashSet<>();
            node.path("requiredItems").forEach(item -> required.add(item.asText()));
            Set<String> forbidden = new LinkedHashSet<>();
            node.path("forbiddenClaims").forEach(item -> forbidden.add(item.asText()));
            Set<String> variants = new LinkedHashSet<>();
            node.path("allowedVariants").forEach(item -> variants.add(item.asText()));
            cases.add(new GoldCase(
                node.path("caseId").asText(""),
                node.path("taskType").asText(""),
                node.path("category").asText(""),
                node.path("tier").asText(""),
                node.path("source").asText(""),
                node.path("inputs"),
                node.path("outputSchema"),
                Set.copyOf(evidence),
                Set.copyOf(required),
                Set.copyOf(forbidden),
                Set.copyOf(variants)));
        }
        return new GoldSetPackage(version, rubricRef, List.copyOf(cases));
    }

    public PackageValidation validate(GoldSetPackage pkg) {
        List<String> problems = new ArrayList<>();
        Map<String, Integer> perTaskType = new LinkedHashMap<>();
        Map<String, Integer> perCategory = new LinkedHashMap<>();
        Set<String> seenIds = new LinkedHashSet<>();
        for (GoldCase testCase : pkg.cases()) {
            String label = testCase.caseId().isBlank() ? "<blank>" : testCase.caseId();
            if (!seenIds.add(testCase.caseId())) {
                problems.add("duplicate caseId: " + label);
            }
            if (com.haitlify.aitest.platform.ai.assistant.AiTaskCatalog
                    .find(testCase.taskType()).isEmpty()) {
                problems.add("unknown taskType on " + label + ": " + testCase.taskType());
            }
            if (!REQUIRED_MINIMUMS.contains(testCase.category())) {
                problems.add("unknown category on " + label + ": " + testCase.category());
            }
            perTaskType.merge(testCase.taskType(), 1, Integer::sum);
            perCategory.merge(testCase.taskType() + "/" + testCase.category(), 1, Integer::sum);
            boolean noEvidence = "no_evidence".equals(testCase.category());
            boolean malicious = "malicious".equals(testCase.category());
            if ((noEvidence || malicious) && testCase.forbiddenClaims().isEmpty()) {
                problems.add("negative case without forbidden claims: " + label);
            }
            if (!noEvidence && testCase.evidenceIds().isEmpty()) {
                problems.add("non-negative case without evidence ids: " + label);
            }
            if (testCase.requiredItems().isEmpty()) {
                problems.add("case without required items (coverage): " + label);
            }
        }
        for (Map.Entry<String, Integer> entry : perTaskType.entrySet()) {
            if (entry.getValue() < 10) {
                problems.add("taskType " + entry.getKey() + " has " + entry.getValue()
                    + " cases (<10)");
            }
            for (Map.Entry<String, Integer> minimum : MINIMUM_PER_CATEGORY.entrySet()) {
                int actual = perCategory.getOrDefault(entry.getKey() + "/" + minimum.getKey(), 0);
                if (actual < minimum.getValue()) {
                    problems.add("taskType " + entry.getKey() + " category " + minimum.getKey()
                        + " has " + actual + " cases (<" + minimum.getValue()
                        + ")——负例与固定比例只增不减");
                }
            }
        }
        if (perTaskType.isEmpty()) {
            problems.add("gold set has no cases");
        }
        return new PackageValidation(problems.isEmpty(), pkg.version(), perTaskType, perCategory,
            List.copyOf(problems));
    }

    // ---------- 运行与评分 ----------

    /**
     * 对包内每个 taskType 运行 3 次重复评测，计算 Rubric 分数与门槛判定；结果持久化（如有数据源）。
     * 注意：每次运行都会经统一 facade 发出真实模型请求并计入预算——发布套件按此设计。
     */
    public PackageRun runPackage(String profileRef, String skillRef, GoldSetPackage pkg) {
        PackageValidation validation = validate(pkg);
        if (!validation.valid()) {
            throw new IllegalArgumentException("gold set invalid: " + validation.problems());
        }
        Map<String, List<GoldCase>> byType = new LinkedHashMap<>();
        for (GoldCase testCase : pkg.cases()) {
            byType.computeIfAbsent(testCase.taskType(), key -> new ArrayList<>()).add(testCase);
        }
        Map<String, RubricScores> scores = new LinkedHashMap<>();
        Map<String, String> evaluationIds = new LinkedHashMap<>();
        List<GateDecision> gates = new ArrayList<>();
        for (Map.Entry<String, List<GoldCase>> entry : byType.entrySet()) {
            String taskType = entry.getKey();
            AiEvaluationService.EvaluationSpec spec = new AiEvaluationService.EvaluationSpec(
                profileRef, skillRef, 3,
                entry.getValue().stream().map(this::toEvaluationCase).toList());
            AiEvaluationService.EvaluationReport report = evaluationService.evaluate(spec);
            RubricScores rubric = score(taskType, report.cases());
            scores.put(taskType, rubric);
            GateDecision gate = gate(rubric);
            gates.add(gate);
            String evaluationId = persist(taskType, profileRef, skillRef, pkg, report, rubric, gate);
            evaluationIds.put(taskType, evaluationId);
        }
        return new PackageRun(pkg.version(), Instant.now(), Map.copyOf(scores), Map.copyOf(evaluationIds),
            List.copyOf(gates), versions(profileRef, skillRef, pkg));
    }

    /** 评分（纯函数：可从任意 CaseResult 列表复算——测试与线上同一实现）。 */
    public RubricScores score(String taskType, List<AiEvaluationService.CaseResult> results) {
        int runs = results.size();
        if (runs == 0) {
            return new RubricScores(taskType, 0, 0d, 0d, 0d, 0d, 0, 0d, true, List.of());
        }
        long schemaPass = results.stream().filter(AiEvaluationService.CaseResult::schemaValid).count();
        long evidencePass = results.stream().filter(AiEvaluationService.CaseResult::citationsValid).count();
        long hallucinated = results.stream()
            .filter(result -> !result.forbiddenHits().isEmpty()).count();
        long availability = results.stream().filter(AiEvaluationService.CaseResult::attemptSucceeded).count();
        long invalidTokens = results.stream()
            .mapToLong(result -> result.invalidCitations().size()).sum();
        double coverage = results.stream().mapToDouble(AiEvaluationService.CaseResult::coverage).average()
            .orElse(0d);
        List<String> unstable = new ArrayList<>();
        Map<String, List<AiEvaluationService.CaseResult>> perCase = new LinkedHashMap<>();
        for (AiEvaluationService.CaseResult result : results) {
            perCase.computeIfAbsent(result.caseId(), key -> new ArrayList<>()).add(result);
        }
        for (Map.Entry<String, List<AiEvaluationService.CaseResult>> entry : perCase.entrySet()) {
            List<AiEvaluationService.CaseResult> group = entry.getValue();
            if (group.size() < 2) {
                continue;
            }
            AiEvaluationService.CaseResult first = group.get(0);
            for (AiEvaluationService.CaseResult other : group.subList(1, group.size())) {
                boolean same = first.schemaValid() == other.schemaValid()
                    && first.citationsValid() == other.citationsValid()
                    && Double.compare(first.coverage(), other.coverage()) == 0
                    && first.forbiddenHits().equals(other.forbiddenHits());
                if (!same) {
                    unstable.add(entry.getKey());
                    break;
                }
            }
        }
        return new RubricScores(taskType, runs,
            schemaPass * 1d / runs,
            evidencePass * 1d / runs,
            coverage,
            hallucinated * 1d / runs,
            invalidTokens,
            availability * 1d / runs,
            unstable.isEmpty(),
            List.copyOf(unstable));
    }

    /** 门槛判定：只给建议不执行（applied 恒 false）。 */
    public GateDecision gate(RubricScores scores) {
        Threshold threshold = thresholds.getOrDefault(scores.taskType(),
            thresholds.getOrDefault("default", defaultThreshold()));
        List<String> breaches = new ArrayList<>();
        if (scores.schemaCorrectness() < threshold.schemaCorrectness()) {
            breaches.add("schemaCorrectness " + scores.schemaCorrectness()
                + " < " + threshold.schemaCorrectness());
        }
        if (scores.evidencePrecision() < threshold.evidencePrecision()) {
            breaches.add("evidencePrecision " + scores.evidencePrecision()
                + " < " + threshold.evidencePrecision());
        }
        if (scores.coverageRecall() < threshold.coverageRecall()) {
            breaches.add("coverageRecall " + scores.coverageRecall()
                + " < " + threshold.coverageRecall());
        }
        if (scores.hallucinationRate() > threshold.hallucinationMax()) {
            breaches.add("hallucinationRate " + scores.hallucinationRate()
                + " > " + threshold.hallucinationMax());
        }
        if (!scores.stable()) {
            breaches.add("stability violated on: " + scores.unstableCases());
        }
        boolean pass = breaches.isEmpty();
        return new GateDecision(scores.taskType(), pass, List.copyOf(breaches),
            pass ? "pass" : "disable-version-recommended", false);
    }

    // ---------- 人评与裁决 ----------

    /** 盲化载荷：标注者不得看到档案/模型/skill 名（只含案例判定事实与输出摘要）。 */
    public List<Map<String, Object>> annotationPayload(String evaluationId) {
        requireJdbc();
        return jdbcTemplate.queryForList(
            "select case_id, category, coverage, schema_valid, citations_valid, forbidden_hits_json,"
                + " output_sha256 from ai_quality_evaluation_cases where evaluation_id = ? and run_index = 1"
                + " order by case_id",
            evaluationId).stream().map(row -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("caseId", row.get("case_id"));
                item.put("category", row.get("category"));
                item.put("coverage", row.get("coverage"));
                item.put("schemaValid", row.get("schema_valid"));
                item.put("citationsValid", row.get("citations_valid"));
                item.put("forbiddenHits", row.get("forbidden_hits_json"));
                item.put("outputSha256", row.get("output_sha256"));
                // 有意的遗漏：profile_ref/skill_ref/model——盲化要求。
                return (Map<String, Object>) item;
            }).toList();
    }

    /** 记录一名标注者的人工结论（与规则分数分表存储）。 */
    public void recordAnnotation(String evaluationId, String caseId, String annotatorRef, String humanDecision) {
        requireJdbc();
        jdbcTemplate.update(
            "insert into ai_quality_annotations (evaluation_id, case_id, annotator_ref, human_decision)"
                + " values (?, ?, ?, ?)",
            evaluationId, caseId, annotatorRef, humanDecision);
    }

    /** 分歧裁决：第三方给出最终裁定。 */
    public void adjudicate(String evaluationId, String caseId, String adjudicatorRef, String finalDecision) {
        requireJdbc();
        jdbcTemplate.update(
            "insert into ai_quality_adjudications (evaluation_id, case_id, adjudicator_ref, final_decision)"
                + " values (?, ?, ?, ?)",
            evaluationId, caseId, adjudicatorRef, finalDecision);
    }

    /** 标注状态：两名标注者结论不一致 ⇒ disagreement=true（进入 adjudication）。 */
    public List<AnnotationState> annotationStates(String evaluationId) {
        requireJdbc();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "select case_id, annotator_ref, human_decision from ai_quality_annotations"
                + " where evaluation_id = ? order by case_id, id", evaluationId);
        Map<String, List<Map<String, Object>>> perCase = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            perCase.computeIfAbsent(String.valueOf(row.get("case_id")), key -> new ArrayList<>()).add(row);
        }
        List<AnnotationState> states = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : perCase.entrySet()) {
            Set<String> decisions = new LinkedHashSet<>();
            List<String> annotators = new ArrayList<>();
            for (Map<String, Object> row : entry.getValue()) {
                annotators.add(String.valueOf(row.get("annotator_ref")));
                decisions.add(String.valueOf(row.get("human_decision")));
            }
            String adjudicated = jdbcTemplate.query(
                "select final_decision from ai_quality_adjudications where evaluation_id = ? and case_id = ?"
                    + " order by id desc limit 1",
                resultSet -> resultSet.next() ? resultSet.getString(1) : null,
                evaluationId, entry.getKey());
            states.add(new AnnotationState(entry.getKey(), List.copyOf(annotators),
                decisions.size() > 1, adjudicated));
        }
        return List.copyOf(states);
    }

    // ---------- 内部 ----------

    private AiEvaluationService.EvaluationCase toEvaluationCase(GoldCase testCase) {
        return new AiEvaluationService.EvaluationCase(testCase.caseId(), testCase.tier(), testCase.inputs(),
            testCase.outputSchema(), testCase.evidenceIds(), testCase.requiredItems(),
            testCase.forbiddenClaims());
    }

    private Map<String, String> versions(String profileRef, String skillRef, GoldSetPackage pkg) {
        Map<String, String> versions = new LinkedHashMap<>();
        versions.put("goldSetPackage", pkg.version());
        versions.put("rubric", pkg.rubricRef());
        versions.put("profile", profileRef == null ? "" : profileRef);
        versions.put("skill", skillRef == null ? "" : skillRef);
        versions.put("rules", com.haitlify.aitest.platform.ai.runtime.AiFailureClassifier.RULE_VERSION);
        return Map.copyOf(versions);
    }

    private String persist(String taskType, String profileRef, String skillRef, GoldSetPackage pkg,
                           AiEvaluationService.EvaluationReport report, RubricScores rubric,
                           GateDecision gate) {
        String evaluationId = "qeval-" + taskType + "-" + UUID.randomUUID();
        if (jdbcTemplate == null) {
            return evaluationId;
        }
        jdbcTemplate.update(
            "insert into ai_quality_evaluation_runs (evaluation_id, package_version, profile_ref, skill_ref,"
                + " task_type, repeats, versions_json, metrics_json, stability_json, thresholds_json,"
                + " gate_json) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            evaluationId, pkg.version(), profileRef, skillRef, taskType, report.repeats(),
            json(versions(profileRef, skillRef, pkg)),
            json(Map.of(
                "schemaCorrectness", rubric.schemaCorrectness(),
                "evidencePrecision", rubric.evidencePrecision(),
                "coverageRecall", rubric.coverageRecall(),
                "hallucinationRate", rubric.hallucinationRate(),
                "invalidCitationTokens", rubric.invalidCitationTokens(),
                "executionAvailability", rubric.executionAvailability())),
            json(Map.of("stable", rubric.stable(), "unstableCases", rubric.unstableCases())),
            json(thresholds.getOrDefault(taskType, thresholds.getOrDefault("default", defaultThreshold()))
                .toString()),
            json(Map.of("pass", gate.pass(), "breaches", gate.breaches(), "action", gate.action(),
                "applied", gate.applied())));
        for (AiEvaluationService.CaseResult result : report.cases()) {
            jdbcTemplate.update(
                "insert into ai_quality_evaluation_cases (evaluation_id, case_id, run_index, category,"
                    + " schema_valid, citations_valid, coverage, forbidden_hits_json, attempt_succeeded,"
                    + " output_sha256) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                evaluationId, result.caseId(), result.run(), result.tier(), result.schemaValid(),
                result.citationsValid(), result.coverage(), json(result.forbiddenHits()),
                result.attemptSucceeded(), result.outputSha256());
        }
        return evaluationId;
    }

    private String json(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    private void requireJdbc() {
        if (jdbcTemplate == null) {
            throw new IllegalStateException("annotation storage requires a datasource");
        }
    }

    private Threshold defaultThreshold() {
        return new Threshold(0.9, 0.9, 0.8, 0.05);
    }

    /**
     * 解析阈值配置：{@code "task=schema:0.9,evidence:0.9,coverage:0.8,hallucination:0.05;default=..."}；
     * 空配置回落到 {@link #DEFAULT_THRESHOLDS}。
     */
    public static Map<String, Threshold> parseThresholds(String config) {
        Map<String, Threshold> parsed = new LinkedHashMap<>();
        for (String part : config.split(";")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int equals = trimmed.indexOf('=');
            if (equals <= 0 || equals == trimmed.length() - 1) {
                throw new IllegalArgumentException("invalid threshold entry: " + trimmed);
            }
            String taskType = trimmed.substring(0, equals).trim();
            double schema = 0.9;
            double evidence = 0.9;
            double coverage = 0.8;
            double hallucination = 0.05;
            for (String pair : trimmed.substring(equals + 1).split(",")) {
                String[] keyValue = pair.trim().split(":");
                if (keyValue.length != 2) {
                    throw new IllegalArgumentException("invalid threshold pair: " + pair);
                }
                double value = Double.parseDouble(keyValue[1].trim());
                switch (keyValue[0].trim()) {
                    case "schema" -> schema = value;
                    case "evidence" -> evidence = value;
                    case "coverage" -> coverage = value;
                    case "hallucination" -> hallucination = value;
                    default -> throw new IllegalArgumentException("unknown threshold key: " + keyValue[0]);
                }
            }
            parsed.put(taskType, new Threshold(schema, evidence, coverage, hallucination));
        }
        return Map.copyOf(parsed);
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) {
            return "未知原因";
        }
        String single = com.haitlify.aitest.platform.ai.security.AiPayloadSanitizer
            .mask(value.replaceAll("\\s+", " "));
        return single.length() <= 200 ? single : single.substring(0, 197) + "...";
    }
}
