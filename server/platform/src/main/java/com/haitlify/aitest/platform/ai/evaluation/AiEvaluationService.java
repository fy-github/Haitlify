package com.haitlify.aitest.platform.ai.evaluation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade;
import com.haitlify.aitest.platform.ai.runtime.output.AiSchemaValidationEngine;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * T13：Gold Set 评测框架。指标全部由确定性代码从执行事实计算——绝不出现固定 0.8/0.9 分数。
 *
 * <ul>
 *   <li>schema pass：输出经 {@link AiSchemaValidationEngine} 对照案例 schema 校验；</li>
 *   <li>引用有效率：输出中的引用必须来自本案例提供的证据集——跨项目/编造引用直接判无效；</li>
 *   <li>业务覆盖：案例要求的必达项逐项核对；</li>
 *   <li>人评单列（PENDING_HUMAN，框架不伪造）；</li>
 *   <li>执行可用率：attempt 成功即计入，模型/规则评分分开列；</li>
 *   <li>发布套件对参考档案重复 3 次（{@code repeats=3}），用于 skill/model/profile 与旧基线对比。</li>
 * </ul>
 */
@Service
public class AiEvaluationService {

    public record EvaluationCase(
        String caseId,
        String tier,
        JsonNode inputs,
        JsonNode outputSchema,
        Set<String> evidenceIds,
        Set<String> requiredItems,
        Set<String> forbiddenClaims
    ) {

        /** T-Review-5：兼容旧签名（无禁止结论清单）。 */
        public EvaluationCase(String caseId, String tier, JsonNode inputs, JsonNode outputSchema,
                              Set<String> evidenceIds, Set<String> requiredItems) {
            this(caseId, tier, inputs, outputSchema, evidenceIds, requiredItems, Set.of());
        }
    }

    public record EvaluationSpec(String profileRef, String skillRef, int repeats, List<EvaluationCase> cases) {
    }

    public record CaseResult(
        String caseId,
        String tier,
        int run,
        boolean schemaValid,
        List<String> schemaErrors,
        boolean citationsValid,
        List<String> invalidCitations,
        double coverage,
        List<String> missingItems,
        boolean attemptSucceeded,
        String modelRef,
        String ruleScoresNote,
        String humanScore,
        List<String> forbiddenHits,
        String outputSha256
    ) {

        /** T-Review-5：兼容旧签名。 */
        public CaseResult(String caseId, String tier, int run, boolean schemaValid,
                          List<String> schemaErrors, boolean citationsValid, List<String> invalidCitations,
                          double coverage, List<String> missingItems, boolean attemptSucceeded,
                          String modelRef, String ruleScoresNote, String humanScore) {
            this(caseId, tier, run, schemaValid, schemaErrors, citationsValid, invalidCitations, coverage,
                missingItems, attemptSucceeded, modelRef, ruleScoresNote, humanScore, List.of(), "");
        }
    }

    public record EvaluationReport(
        String profileRef,
        String skillRef,
        int repeats,
        Instant generatedAt,
        List<CaseResult> cases,
        Map<String, Double> metrics,
        Map<String, String> notes
    ) {
    }

    private final ObjectMapper objectMapper;
    private final AiTaskExecutionFacade facade;

    public AiEvaluationService(ObjectMapper objectMapper, AiTaskExecutionFacade facade) {
        this.objectMapper = objectMapper;
        this.facade = facade;
    }

    /** 运行评测。每个案例 × 重复次数各发出一次真实模型请求（经统一 facade，预算正常计入）。 */
    public EvaluationReport evaluate(EvaluationSpec spec) {
        if (spec == null || spec.cases() == null || spec.cases().isEmpty()) {
            throw new IllegalArgumentException("evaluation cases required");
        }
        int repeats = spec.repeats() <= 0 ? 1 : spec.repeats();
        List<CaseResult> results = new ArrayList<>();
        for (EvaluationCase testCase : spec.cases()) {
            for (int run = 1; run <= repeats; run++) {
                results.add(evaluateCase(spec, testCase, run));
            }
        }
        return new EvaluationReport(spec.profileRef(), spec.skillRef(), repeats, Instant.now(), results,
            computeMetrics(results), Map.of(
                "humanReview", "PENDING_HUMAN：人评由人工在报告中填写，框架不代填",
                "ruleScores", "规则评分与模型评分分列，不作为质量提升的证据",
                "baseline", "与旧基线对比：对相同 spec 运行本方法后调用 compare(baseline, candidate)"
            ));
    }

    /** 与旧基线对比：逐项给出 delta；只根据两份报告的确定性指标计算。 */
    public Map<String, Double> compare(EvaluationReport baseline, EvaluationReport candidate) {
        Map<String, Double> deltas = new LinkedHashMap<>();
        for (String key : candidate.metrics().keySet()) {
            Double before = baseline.metrics().get(key);
            Double after = candidate.metrics().get(key);
            if (before != null && after != null) {
                deltas.put(key, after - before);
            }
        }
        return deltas;
    }

    /** 从 Gold Set JSON 载入（测试样例与发布套件共用同一格式）。 */
    public EvaluationSpec loadSpec(JsonNode goldSet) {
        if (goldSet == null || !goldSet.isObject()) {
            throw new IllegalArgumentException("gold set must be an object");
        }
        List<EvaluationCase> cases = new ArrayList<>();
        JsonNode casesNode = goldSet.path("cases");
        for (JsonNode node : casesNode) {
            Set<String> evidence = new LinkedHashSet<>();
            node.path("evidenceIds").forEach(item -> evidence.add(item.asText()));
            Set<String> required = new LinkedHashSet<>();
            node.path("requiredItems").forEach(item -> required.add(item.asText()));
            Set<String> forbidden = new LinkedHashSet<>();
            node.path("forbiddenClaims").forEach(item -> forbidden.add(item.asText()));
            cases.add(new EvaluationCase(
                node.path("caseId").asText(""),
                node.path("tier").asText(""),
                node.path("inputs"),
                node.path("outputSchema"),
                Set.copyOf(evidence),
                Set.copyOf(required),
                Set.copyOf(forbidden)));
        }
        return new EvaluationSpec(
            goldSet.path("profileRef").asText(""),
            goldSet.path("skillRef").asText(""),
            goldSet.path("repeats").asInt(1),
            List.copyOf(cases));
    }

    // ---------- 内部 ----------

    private CaseResult evaluateCase(EvaluationSpec spec, EvaluationCase testCase, int run) {
        ObjectNode prompt = objectMapper.createObjectNode();
        prompt.put("instruction", "评测样例 " + testCase.caseId() + "（tier=" + testCase.tier() + "）："
            + "仅依据提供的证据作答，引用必须来自证据集，输出必须满足给定 schema。");
        prompt.set("inputs", testCase.inputs());
        prompt.set("outputSchema", testCase.outputSchema());
        // 证据集随请求给出：引用必须来自这里（编造/跨项目引用会在评测中判无效）。
        prompt.set("evidenceIds", objectMapper.valueToTree(testCase.evidenceIds()));

        com.haitlify.aitest.platform.ai.gateway.AiGenerationResult result;
        try {
            result = facade.generate(new AiTaskExecutionFacade.TaskExecution(
                "prompt_evaluation", null, "evaluation-runner", null, prompt, null));
        } catch (RuntimeException failure) {
            return new CaseResult(testCase.caseId(), testCase.tier(), run, false,
                List.of("run_failed: " + safe(failure.getMessage())), false, List.of(), 0d,
                List.copyOf(testCase.requiredItems()), false, "", "", "PENDING_HUMAN", List.of(), "");
        }
        boolean attemptSucceeded = result != null && result.success();
        if (!attemptSucceeded) {
            return new CaseResult(testCase.caseId(), testCase.tier(), run, false,
                List.of("attempt_failed: " + safe(result == null ? "无响应" : result.errorMessage())),
                false, List.of(), 0d, List.copyOf(testCase.requiredItems()), false,
                spec.profileRef(), "", "PENDING_HUMAN", List.of(), "");
        }

        JsonNode output;
        try {
            output = objectMapper.readTree(result.content() == null ? "" : result.content());
        } catch (Exception malformed) {
            return new CaseResult(testCase.caseId(), testCase.tier(), run, false,
                List.of("output_malformed"), false, List.of(), 0d, List.copyOf(testCase.requiredItems()),
                true, spec.profileRef(), "", "PENDING_HUMAN", List.of(),
                sha256(result.content() == null ? "" : result.content()));
        }

        // schema pass：对照案例 schema（T03 校验引擎，与运行时同一语义）。
        List<String> schemaErrors = AiSchemaValidationEngine.validate(output, testCase.outputSchema());
        // 引用有效率：任何不属于本案例证据集的引用都判无效（跨项目/编造）。
        // T-R5 轮 2：收集器自身异常兜底——坏形状记 violation 并判无效，绝不中断整轮评测。
        List<String> invalidCitations = new ArrayList<>();
        try {
            // 比对用大小写不敏感（保守方向：EV-REAL-1 与 ev-real-1 视为同一 id）。
            var knownIds = new java.util.HashSet<String>();
            testCase.evidenceIds().forEach(id -> knownIds.add(id.toLowerCase(java.util.Locale.ROOT)));
            for (String citation : collectCitations(output)) {
                if (!knownIds.contains(citation.toLowerCase(java.util.Locale.ROOT))) {
                    invalidCitations.add(citation);
                }
            }
        } catch (RuntimeException collectorFailure) {
            invalidCitations.add("citation_collector_failure: "
                + safe(collectorFailure.getMessage()));
        }
        // 业务覆盖：必达项逐项核对（按 value/文本包含匹配，确定性）。
        List<String> missing = new ArrayList<>();
        String flat = output.toString();
        for (String required : testCase.requiredItems()) {
            if (!flat.contains(required)) {
                missing.add(required);
            }
        }
        double coverage = testCase.requiredItems().isEmpty() ? 1d
            : (testCase.requiredItems().size() - missing.size()) * 1d / testCase.requiredItems().size();
        // T-Review-5：禁止结论（幻觉面）逐条核对——输出命中即记 forbiddenHit（确定性文本包含）。
        List<String> forbiddenHits = new ArrayList<>();
        for (String claim : testCase.forbiddenClaims()) {
            if (!claim.isBlank() && flat.contains(claim)) {
                forbiddenHits.add(claim);
            }
        }
        return new CaseResult(testCase.caseId(), testCase.tier(), run, schemaErrors.isEmpty(), schemaErrors,
            invalidCitations.isEmpty(), invalidCitations, coverage, missing, true, spec.profileRef(), "",
            "PENDING_HUMAN", List.copyOf(forbiddenHits), sha256(output.toString()));
    }

    /** 输出摘要（sha-256）：失败样例可定位到具体输出而不落盘原文。 */
    private static String sha256(String content) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(Character.forDigit((value >> 4) & 0xF, 16));
                builder.append(Character.forDigit(value & 0xF, 16));
            }
            return builder.toString();
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            return "";
        }
    }

    /**
     * 引用收集（T-R5 轮 2 修订二）：容器安全 + 大小写不敏感 + 家族令牌。
     *  - 语义键（evidence/source/citation 或精确的 lineids/linerefs）：文本值或数组内文本值，
     *    仅当其形如家族令牌才收集（避免 timeline/deadline 等普通字段误报）；
     *  - 家族令牌（ev-/src-/doc-/log-/asset-/chunk-/artifact-，大小写不敏感）：任意位置文本；
     *  - 数组内对象/嵌套数组一律递归（sources:[{url}] 不再抛异常）。
     */
    /** 家族令牌：后缀必须含数字（避免 run-time/job-based 等散文误报）。 */
    private static final java.util.regex.Pattern REFERENCE_TOKEN = java.util.regex.Pattern.compile(
        "(?i)\\b(?:ev|src|doc|log|asset|chunk|artifact|run|vis|img|case|job)-[a-z0-9-]*[0-9][a-z0-9-]*\\b");

    private Set<String> collectCitations(JsonNode output) {
        Set<String> citations = new LinkedHashSet<>();
        collectCitationsInto(output, citations);
        return citations;
    }

    private void collectCitationsInto(JsonNode node, Set<String> citations) {
        if (node == null || node.isMissingNode()) {
            return;
        }
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                String key = entry.getKey().toLowerCase(java.util.Locale.ROOT);
                JsonNode value = entry.getValue();
                // T-R5 轮 3：令牌也可能藏在对象键名里——键名本身先过令牌扫描。
                addTokens(entry.getKey(), citations);
                boolean referenceKey = key.contains("evidence") || key.contains("source")
                    || key.contains("citation") || key.endsWith("lineids") || key.endsWith("linerefs");
                if (referenceKey) {
                    collectReferenceValues(value, citations);
                } else {
                    collectCitationsInto(value, citations);
                }
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectCitationsInto(child, citations));
        } else if (node.isTextual()) {
            addTokens(node.asText(), citations);
        }
    }

    /** 日期形态（log-2024-01-05 等）属普通文本，不算引用令牌。 */
    private static final java.util.regex.Pattern DATE_SHAPED_TOKEN = java.util.regex.Pattern.compile(
        "(?i)^(?:log|job|run|case|doc|src)-\\d{4}-\\d{1,2}(?:-\\d{1,2})?$");

    private void addTokens(String text, Set<String> citations) {
        if (text == null || text.isEmpty()) {
            return;
        }
        var matcher = REFERENCE_TOKEN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group();
            if (DATE_SHAPED_TOKEN.matcher(token).matches()) {
                continue;
            }
            citations.add(token);
        }
    }

    /** 引用键下的值：文本取令牌；数组逐项（含对象）递归——绝不在容器节点上直接取值。 */
    private void collectReferenceValues(JsonNode value, Set<String> citations) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return;
        }
        if (value.isTextual()) {
            // T-R5 轮 6：与自由文本分支同一收集口径（含日期形态豁免）。
            addTokens(value.asText(), citations);
            return;
        }
        if (value.isArray() || value.isObject()) {
            collectCitationsInto(value, citations);
        }
    }

    private Map<String, Double> computeMetrics(List<CaseResult> results) {
        int total = results.size();
        long schemaPass = results.stream().filter(CaseResult::schemaValid).count();
        long citationPass = results.stream().filter(CaseResult::citationsValid).count();
        long availability = results.stream().filter(CaseResult::attemptSucceeded).count();
        double coverageSum = results.stream().mapToDouble(CaseResult::coverage).sum();
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("schemaPassRate", total == 0 ? 0d : schemaPass * 1d / total);
        metrics.put("citationValidRate", total == 0 ? 0d : citationPass * 1d / total);
        metrics.put("coverageRate", total == 0 ? 0d : coverageSum / total);
        metrics.put("executionAvailabilityRate", total == 0 ? 0d : availability * 1d / total);
        return metrics;
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) {
            return "未知原因";
        }
        // T-R5 轮 4：报告内所有上游文本同样过脱敏器（token=/api_key= 等不得原样出现在 200 报告里）。
        String single = com.haitlify.aitest.platform.ai.security.AiPayloadSanitizer
            .mask(value.replaceAll("\\s+", " "));
        return single.length() <= 200 ? single : single.substring(0, 197) + "...";
    }
}
