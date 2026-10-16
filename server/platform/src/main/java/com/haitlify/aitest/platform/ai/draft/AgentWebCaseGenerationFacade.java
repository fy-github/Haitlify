package com.haitlify.aitest.platform.ai.draft;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.haitlify.aitest.platform.ai.agentic.WebCaseAgentModels.AgentRuleSnapshot;
import com.haitlify.aitest.platform.webautomation.WebAutomationEvidenceException;
import com.haitlify.aitest.platform.webautomation.WebAutomationLocatorDiscoveryService.ExplorationReport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Deliberately small public boundary around the existing, browser-grounded case generator.
 * The agent runtime owns orchestration; this facade keeps model prompts and Web DSL validation
 * in their existing, well-tested implementation.
 */
@Service
public class AgentWebCaseGenerationFacade {

    private static final Logger log = LoggerFactory.getLogger(AgentWebCaseGenerationFacade.class);

    private final AiDraftTestPointService draftTestPointService;
    private final ObjectMapper objectMapper;

    AgentWebCaseGenerationFacade(AiDraftTestPointService draftTestPointService, ObjectMapper objectMapper) {
        this.draftTestPointService = draftTestPointService;
        this.objectMapper = objectMapper;
    }

    public List<GeneratedWebCase> generate(List<String> pointIds, BrowserDiscoveryOptions discovery, AgentRuleSnapshot rules, List<String> memories) {
        GeneratedCaseListResponse response = draftTestPointService.generateCases(
            new GenerateCasesRequest(pointIds, discovery == null ? null : new WebLocatorDiscoveryContext(
                discovery.environmentId(), discovery.baseUrl(), discovery.browser(), discovery.viewport(), discovery.headless(),
                discovery.timeoutMs(), discovery.waitUntil(), discovery.authToken(), discovery.extraHeaders(),
                discovery.cookieOrigins(), discovery.allowedRequestOrigins(), discovery.sessionId(), discovery.reuseSession(), discovery.login() == null ? null : new WebLocatorDiscoveryLogin(
                    discovery.login().enabled(), discovery.login().url(), discovery.login().usernameLocator(),
                    discovery.login().passwordLocator(), discovery.login().submitLocator(), discovery.login().username(),
                    discovery.login().password(), discovery.login().successType(), discovery.login().successTarget()
                ), discovery.diagnosticMode(), discovery.tracingEnabled(), discovery.networkPolicyMode()
            )), rules == null ? null : objectMapper.valueToTree(rules), objectMapper.valueToTree(memories == null ? List.of() : memories)
        );
        return response.items().stream().map(item -> new GeneratedWebCase(
            item.sourcePointId(), item.title(), item.module(), item.caseKind(), item.pageName(), item.startUrl(),
            item.browser(), item.viewport(), item.precondition(), item.objective(), item.steps(), item.assertions(),
            item.expectedResult(), item.naturalSteps(), item.naturalExpectedResult(), item.sourceFile(), item.pageFingerprint(), item.pageKey(), item.stepTraces(), item.expectedResultEvidenceIds(), item.actions(), item.environmentId(), item.baseUrl(), item.authenticationMode()
        )).toList();
    }

    public NaturalWebCasePlanBatch plan(List<String> pointIds, AgentRuleSnapshot rules, List<String> memories) {
        WebCasePlanOutput planned = draftTestPointService.planWebCases(
            pointIds,
            rules == null ? null : objectMapper.valueToTree(rules),
            objectMapper.valueToTree(memories == null ? List.of() : memories)
        );
        return naturalPlanBatch(planned.output(), planned.providerRequestId());
    }

    /** Attach the request-scoped execution context to a plan without persisting credentials. */
    public NaturalWebCasePlanBatch plan(List<String> pointIds, BrowserDiscoveryOptions discovery, AgentRuleSnapshot rules, List<String> memories) {
        NaturalWebCasePlanBatch batch = plan(pointIds, rules, memories);
        return withCompileContext(batch, discovery);
    }

    public NaturalWebCasePlanBatch withCompileContext(NaturalWebCasePlanBatch batch, BrowserDiscoveryOptions discovery) {
        if (batch == null || discovery == null) return batch;
        List<NaturalWebCasePlan> plans = batch.plans().stream().map(plan -> new NaturalWebCasePlan(
            plan.sourcePointId(), plan.title(), plan.module(), plan.pageName(), plan.startUrl(), plan.naturalSteps(),
            plan.naturalExpectedResult(), plan.objective(), plan.actions(),
            prefer(discovery.environmentId(), plan.environmentId()), prefer(discovery.baseUrl(), plan.baseUrl()),
            prefer(discovery.browser(), plan.browser()), prefer(discovery.viewport(), plan.viewport()),
            prefer(discovery.authenticationMode(), plan.authenticationMode())
        )).toList();
        JsonNode raw = batch.rawOutput() == null ? objectMapper.createObjectNode() : batch.rawOutput().deepCopy();
        JsonNode cases = caseArray(raw);
        if (cases.isArray()) for (JsonNode item : cases) if (item.isObject()) {
            ((tools.jackson.databind.node.ObjectNode) item).put("environmentId", prefer(discovery.environmentId(), item.path("environmentId").asText(""))).put("baseUrl", prefer(discovery.baseUrl(), item.path("baseUrl").asText("")));
            if (!prefer(discovery.browser(), item.path("browser").asText("")).isBlank()) ((tools.jackson.databind.node.ObjectNode) item).put("browser", prefer(discovery.browser(), item.path("browser").asText("")));
            if (!prefer(discovery.viewport(), item.path("viewport").asText("")).isBlank()) ((tools.jackson.databind.node.ObjectNode) item).put("viewport", prefer(discovery.viewport(), item.path("viewport").asText("")));
            ((tools.jackson.databind.node.ObjectNode) item).put("authenticationMode", prefer(discovery.authenticationMode(), item.path("authenticationMode").asText("")));
        }
        // 用户实测（2026-10-13）：重建批次时丢过 providerRequestId，导致真实模型产出的计划被
        // 错判为「无 Runtime 证据」而整体落 BLOCKED、无法进入人工评审。model evidence 必须透传。
        return new NaturalWebCasePlanBatch(plans, raw, batch.providerRequestId());
    }

    private String prefer(String explicit, String persisted) {
        return explicit == null || explicit.isBlank() ? (persisted == null ? "" : persisted) : explicit;
    }

    NaturalWebCasePlanBatch naturalPlanBatch(JsonNode output, String providerRequestId) {
        return new NaturalWebCasePlanBatch(parseNaturalPlans(output), output, providerRequestId);
    }

    public BrowserEvidenceBundle resolveEvidence(List<String> pointIds, NaturalWebCasePlanBatch plan, BrowserDiscoveryOptions discovery) {
        return resolveEvidence(pointIds, plan, discovery, List.of(), 0L);
    }

    public BrowserEvidenceBundle resolveEvidence(List<String> pointIds, NaturalWebCasePlanBatch plan, BrowserDiscoveryOptions discovery, List<String> previousAttempts, long remainingDurationMs) {
        JsonNode evidence = draftTestPointService.resolveWebCaseEvidence(
            pointIds,
            plan.rawOutput(),
            discovery == null ? null : new WebLocatorDiscoveryContext(
                discovery.environmentId(), discovery.baseUrl(), discovery.browser(), discovery.viewport(), discovery.headless(),
                discovery.timeoutMs(), discovery.waitUntil(), discovery.authToken(), discovery.extraHeaders(),
                discovery.cookieOrigins(), discovery.allowedRequestOrigins(), discovery.sessionId(), discovery.reuseSession(), discovery.login() == null ? null : new WebLocatorDiscoveryLogin(
                    discovery.login().enabled(), discovery.login().url(), discovery.login().usernameLocator(),
                    discovery.login().passwordLocator(), discovery.login().submitLocator(), discovery.login().username(),
                    discovery.login().password(), discovery.login().successType(), discovery.login().successTarget()
                ), discovery.diagnosticMode(), discovery.tracingEnabled(), discovery.networkPolicyMode()
            ), previousAttempts, remainingDurationMs
        );
        return summarizeEvidence(evidence);
    }

    public List<GeneratedWebCase> compile(List<String> pointIds, NaturalWebCasePlanBatch plan, BrowserEvidenceBundle evidence, BrowserDiscoveryOptions discovery, AgentRuleSnapshot rules, List<String> memories) {
        List<String> excludedTargetIds = new java.util.ArrayList<>();
        NaturalWebCasePlanBatch currentPlan = plan;
        // 实测 2026-10-14 批产：单次排除重授权仍有 7/10 点因「又绑定了另一个漂移元素」而失败。
        // 每轮排除一个点名失效目标后重授权，最多 3 轮（模型每次重选，直到全部目标在页面可见或轮次耗尽）。
        final int maxAttempts = 4;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            NaturalWebCasePlanBatch ensuredPlan = ensurePlanActions(currentPlan, pointIds, evidence.rawEvidence(), List.copyOf(excludedTargetIds));
            NaturalWebCasePlanBatch effectivePlan = withCompileContext(ensuredPlan, discovery);
            if (ensuredPlan == plan) {
                // 计划原本就带可用 Action IR：保持原行为，不重跑取证。
                return compileWithEvidence(pointIds, effectivePlan, evidence, discovery, rules, memories);
            }
            try {
                // 计划刚被授权出 Action IR：必须带动作重跑取证——Runner 只读动作会被真实执行并产出 step evidence，
                // 而确定性编译只消费「动作 ↔ step evidence」的绑定，第一轮无动作取证不会留下步骤证据。
                BrowserEvidenceBundle refreshed = resolveEvidence(pointIds, effectivePlan, discovery, List.of(), evidence.remainingDurationMs());
                return compileWithEvidence(pointIds, effectivePlan, refreshed, discovery, rules, memories);
            } catch (WebAutomationEvidenceException failure) {
                String failedTarget = failure.details() == null ? "" : failure.details().path("targetEvidenceId").asText("");
                if (attempt >= maxAttempts || failedTarget.isBlank() || !isActionEvidenceFailure(failure)) {
                    throw failure;
                }
                // 目标在重新打开页面后消失（实时数据漂移/每次采集变化的元素）：排除该目标后重授权再取证。
                excludedTargetIds.add(failedTarget);
                log.warn("Web 动作目标在二次取证中不可见，排除后重授权 attempt={} pointIds={} targetEvidenceId={} category={}",
                    attempt, pointIds, failedTarget, failure.category());
            }
        }
        throw new IllegalStateException("Web 用例编译重试循环未收敛");
    }

    private boolean isActionEvidenceFailure(WebAutomationEvidenceException failure) {
        String category = failure.category();
        return "action_evidence_missing".equals(category) || "ambiguous_locator".equals(category) || "element_not_found".equals(category);
    }

    /**
     * 已确认计划缺失（或未通过校验的）Action IR 时，用刚收集的页面证据授权一次动作模型调用，
     * 把 actions 写回原始输出（case 节点）后重建批次；无需授权时原样返回同一实例（调用方据此判断是否要重新取证）。
     * excludedTargetIds：上一轮二次取证中点名失效的目标，候选列表与既有 actions 都按它过滤。
     */
    NaturalWebCasePlanBatch ensurePlanActions(NaturalWebCasePlanBatch batch, List<String> pointIds, JsonNode evidence, List<String> excludedTargetIds) {
        if (batch == null || batch.rawOutput() == null) return batch;
        JsonNode cases = caseArray(batch.rawOutput());
        if (!cases.isArray()) return batch;
        JsonNode patched = batch.rawOutput().deepCopy();
        boolean any = false;
        for (JsonNode item : caseArray(patched)) {
            if (!item.isObject()) continue;
            String pointId = item.path("sourcePointId").asText(item.path("pointId").asText(""));
            if (!pointIds.contains(pointId)) continue;
            JsonNode existing = item.path("actions");
            if (existing.isArray() && !existing.isEmpty() && ActionIrValidator.validate(existing).isEmpty()
                && !containsExcludedTarget(existing, excludedTargetIds)) continue;
            ((tools.jackson.databind.node.ObjectNode) item).set("actions",
                draftTestPointService.authorWebCaseActions(pointId, item, evidence, excludedTargetIds));
            any = true;
        }
        return any ? naturalPlanBatch(patched, batch.providerRequestId()) : batch;
    }

    private boolean containsExcludedTarget(JsonNode actions, List<String> excludedTargetIds) {
        if (excludedTargetIds == null || excludedTargetIds.isEmpty()) return false;
        for (JsonNode action : actions) {
            String target = action.path("target").path("evidenceId").asText("");
            if (!target.isBlank() && excludedTargetIds.contains(target)) return true;
        }
        return false;
    }

    private List<GeneratedWebCase> compileWithEvidence(List<String> pointIds, NaturalWebCasePlanBatch effectivePlan, BrowserEvidenceBundle evidence, BrowserDiscoveryOptions discovery, AgentRuleSnapshot rules, List<String> memories) {
        GeneratedCaseListResponse response = draftTestPointService.compileWebCases(
            pointIds,
            effectivePlan.rawOutput(),
            evidence.rawEvidence(),
            discovery == null ? null : new WebLocatorDiscoveryContext(
                discovery.environmentId(), discovery.baseUrl(), discovery.browser(), discovery.viewport(), discovery.headless(),
                discovery.timeoutMs(), discovery.waitUntil(), discovery.authToken(), discovery.extraHeaders(),
                discovery.cookieOrigins(), discovery.allowedRequestOrigins(), discovery.sessionId(), discovery.reuseSession(), discovery.login() == null ? null : new WebLocatorDiscoveryLogin(
                    discovery.login().enabled(), discovery.login().url(), discovery.login().usernameLocator(),
                    discovery.login().passwordLocator(), discovery.login().submitLocator(), discovery.login().username(),
                    discovery.login().password(), discovery.login().successType(), discovery.login().successTarget()
                ), discovery.diagnosticMode(), discovery.tracingEnabled(), discovery.networkPolicyMode()
            ),
            rules == null ? null : objectMapper.valueToTree(rules),
            objectMapper.valueToTree(memories == null ? List.of() : memories)
        );
        return response.items().stream().map(item -> new GeneratedWebCase(
            item.sourcePointId(), item.title(), item.module(), item.caseKind(), item.pageName(), item.startUrl(),
            item.browser(), item.viewport(), item.precondition(), item.objective(), item.steps(), item.assertions(),
            item.expectedResult(), item.naturalSteps(), item.naturalExpectedResult(), item.sourceFile(), item.pageFingerprint(), item.pageKey(), item.stepTraces(), item.expectedResultEvidenceIds(), item.actions(),
            effectivePlan.plans().stream().filter(planItem -> planItem.sourcePointId().equals(item.sourcePointId())).findFirst().map(NaturalWebCasePlan::environmentId).orElse(""),
            effectivePlan.plans().stream().filter(planItem -> planItem.sourcePointId().equals(item.sourcePointId())).findFirst().map(NaturalWebCasePlan::baseUrl).orElse(""),
            effectivePlan.plans().stream().filter(planItem -> planItem.sourcePointId().equals(item.sourcePointId())).findFirst().map(NaturalWebCasePlan::authenticationMode).orElse("")
        )).toList();
    }

    private List<NaturalWebCasePlan> parseNaturalPlans(JsonNode output) {
        JsonNode cases = caseArray(output);
        if (!cases.isArray()) return List.of();
        return java.util.stream.StreamSupport.stream(cases.spliterator(), false)
            .map(item -> new NaturalWebCasePlan(
                item.path("sourcePointId").asText(""),
                item.path("title").asText(""),
                firstText(item, "module", "moduleName"),
                firstText(item, "pageName", "targetPage", "page", "pageOrFeature"),
                firstText(item, "startUrl", "pageUrl", "url", "path"),
                textList(item.path("naturalSteps").isMissingNode() ? item.path("steps") : item.path("naturalSteps")),
                firstText(item, "naturalExpectedResult", "expectedResult"),
                firstText(item, "objective", "testObjective"),
                item.path("actions").isArray() ? item.path("actions") : null,
                firstText(item, "environmentId"), firstText(item, "baseUrl"), firstText(item, "browser"),
                firstText(item, "viewport"), firstText(item, "authenticationMode")
            ))
            .toList();
    }

    private BrowserEvidenceBundle summarizeEvidence(JsonNode evidence) {
        List<String> knowledgeHitIds = new java.util.ArrayList<>();
        int pageCount = 0;
        int elementCount = 0;
        boolean usedBrowserDiscovery = false;
        String explorationStatus = "legacy";
        int explorationRounds = 0;
        int totalScore = 0;
        long remainingDurationMs = 0L;
        JsonNode explorationReport = null;
        String sessionId = "";
        if (evidence != null && evidence.isArray()) {
            for (JsonNode page : evidence) {
                pageCount++;
                if ("browserDiscovery".equals(page.path("source").asText(""))) {
                    usedBrowserDiscovery = true;
                    for (JsonNode id : page.path("historicalMatchedIds")) {
                        String value = id.asText("").strip();
                        if (!value.isBlank()) knowledgeHitIds.add(value);
                    }
                    sessionId = page.path("sessionId").asText("");
                    JsonNode report = page.path("exploration");
                    if (!report.isMissingNode() && !report.isNull()) {
                        explorationReport = report;
                        explorationStatus = report.path("status").asText("unknown");
                        explorationRounds += report.path("rounds").isArray() ? report.path("rounds").size() : 0;
                        totalScore = report.path("scores").path("totalScore").asInt(0);
                        remainingDurationMs = report.path("remainingDurationMs").asLong(0L);
                    }
                }
                for (JsonNode element : page.path("elements")) {
                    elementCount++;
                    String id = element.path("id").asText(element.path("evidenceId").asText(""));
                    if ("locatorKnowledge".equals(page.path("source").asText("")) && !id.isBlank()) {
                        knowledgeHitIds.add(id);
                    }
                }
            }
        }
        return new BrowserEvidenceBundle(evidence, List.copyOf(knowledgeHitIds), pageCount, elementCount, usedBrowserDiscovery,
            explorationStatus, explorationRounds, totalScore, remainingDurationMs, explorationReport, sessionId);
    }

    private JsonNode caseArray(JsonNode output) {
        if (output == null) return objectMapper.createArrayNode();
        if (output.isArray()) return output;
        JsonNode cases = output.path("cases");
        if (cases.isArray()) return cases;
        cases = output.path("items");
        if (cases.isArray()) return cases;
        cases = output.path("testCases");
        if (cases.isArray()) return cases;
        return objectMapper.createArrayNode();
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("").strip();
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private List<String> textList(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return List.of();
        if (node.isArray()) {
            List<String> items = new java.util.ArrayList<>();
            for (JsonNode item : node) {
                String value = item.asText("").strip();
                if (!value.isBlank()) items.add(value);
            }
            return List.copyOf(items);
        }
        String value = node.asText("").strip();
        if (value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split("\\R")).map(String::strip).filter(item -> !item.isBlank()).toList();
    }

    public record BrowserDiscoveryOptions(
        String baseUrl,
        String environmentId,
        String browser,
        String viewport,
        boolean headless,
        Integer timeoutMs,
        String waitUntil,
        String authToken,
        Map<String, String> extraHeaders,
        List<String> cookieOrigins,
        List<String> allowedRequestOrigins,
        String sessionId,
        Boolean reuseSession,
        BrowserLogin login,
        String diagnosticMode,
        Boolean tracingEnabled,
        String authenticationMode,
        String networkPolicyMode
    ) {
        public BrowserDiscoveryOptions(
            String baseUrl, String environmentId, String browser, String viewport, boolean headless, Integer timeoutMs,
            String waitUntil, String authToken, Map<String, String> extraHeaders, List<String> cookieOrigins,
            List<String> allowedRequestOrigins, String sessionId, Boolean reuseSession, BrowserLogin login,
            String diagnosticMode, Boolean tracingEnabled
        ) {
            this(baseUrl, environmentId, browser, viewport, headless, timeoutMs, waitUntil, authToken, extraHeaders,
                cookieOrigins, allowedRequestOrigins, sessionId, reuseSession, login, diagnosticMode, tracingEnabled, "", null);
        }

        public BrowserDiscoveryOptions(
            String baseUrl,
            String environmentId,
            String browser,
            String viewport,
            boolean headless,
            Integer timeoutMs,
            String waitUntil,
            String authToken,
            Map<String, String> extraHeaders,
            List<String> cookieOrigins,
            BrowserLogin login
        ) {
            this(baseUrl, environmentId, browser, viewport, headless, timeoutMs, waitUntil, authToken,
                extraHeaders, cookieOrigins, null, null, null, login, null, null, "", null);
        }
    }

    /** Request-only credentials: callers must never persist or log this object. */
    public record BrowserLogin(
        boolean enabled,
        String url,
        String usernameLocator,
        String passwordLocator,
        String submitLocator,
        String username,
        String password,
        String successType,
        String successTarget
    ) { }

    public record NaturalWebCasePlanBatch(
        List<NaturalWebCasePlan> plans,
        JsonNode rawOutput,
        String providerRequestId
    ) {
        public NaturalWebCasePlanBatch {
            plans = plans == null ? List.of() : List.copyOf(plans);
            providerRequestId = providerRequestId == null ? "" : providerRequestId;
        }

        /** Compatibility constructor for callers that do not carry model evidence (fixtures, replay). */
        public NaturalWebCasePlanBatch(List<NaturalWebCasePlan> plans, JsonNode rawOutput) {
            this(plans, rawOutput, "");
        }
    }

    public record NaturalWebCasePlan(
        String sourcePointId,
        String title,
        String module,
        String pageName,
        String startUrl,
        List<String> naturalSteps,
        String naturalExpectedResult,
        String objective,
        JsonNode actions,
        String environmentId,
        String baseUrl,
        String browser,
        String viewport,
        String authenticationMode
    ) {
        public JsonNode actions() {
            return actions == null ? null : actions;
        }

        public NaturalWebCasePlan(
            String sourcePointId,
            String title,
            String module,
            String pageName,
            String startUrl,
            List<String> naturalSteps,
            String naturalExpectedResult,
            String objective
        ) {
            this(sourcePointId, title, module, pageName, startUrl, naturalSteps, naturalExpectedResult, objective, null, "", "", "", "", "");
        }
        public NaturalWebCasePlan(
            String sourcePointId, String title, String module, String pageName, String startUrl, List<String> naturalSteps,
            String naturalExpectedResult, String objective, JsonNode actions, String browser, String viewport
        ) {
            this(sourcePointId, title, module, pageName, startUrl, naturalSteps, naturalExpectedResult, objective, actions, "", "", browser, viewport, "");
        }

        public NaturalWebCasePlan {
            naturalSteps = naturalSteps == null ? List.of() : List.copyOf(naturalSteps);
            browser = browser == null ? "" : browser;
            viewport = viewport == null ? "" : viewport;
            environmentId = environmentId == null ? "" : environmentId;
            baseUrl = baseUrl == null ? "" : baseUrl;
            authenticationMode = authenticationMode == null ? "" : authenticationMode;
        }
    }

    public record BrowserEvidenceBundle(
        JsonNode rawEvidence,
        List<String> knowledgeHitIds,
        int pageCount,
        int elementCount,
        boolean usedBrowserDiscovery,
        String explorationStatus,
        int explorationRounds,
        int totalScore,
        long remainingDurationMs,
        JsonNode explorationReport,
        String sessionId
    ) {
        public BrowserEvidenceBundle(JsonNode rawEvidence, List<String> knowledgeHitIds, int pageCount, int elementCount, boolean usedBrowserDiscovery) {
            this(rawEvidence, knowledgeHitIds, pageCount, elementCount, usedBrowserDiscovery, "legacy", 0, 0, 0L, null, "");
        }

        public BrowserEvidenceBundle(JsonNode rawEvidence, List<String> knowledgeHitIds, int pageCount, int elementCount, boolean usedBrowserDiscovery,
                                     String explorationStatus, int explorationRounds, int totalScore, long remainingDurationMs) {
            this(rawEvidence, knowledgeHitIds, pageCount, elementCount, usedBrowserDiscovery, explorationStatus, explorationRounds, totalScore, remainingDurationMs, null, "");
        }

        public BrowserEvidenceBundle(JsonNode rawEvidence, List<String> knowledgeHitIds, int pageCount, int elementCount, boolean usedBrowserDiscovery,
                                     String explorationStatus, int explorationRounds, int totalScore, long remainingDurationMs, JsonNode explorationReport) {
            this(rawEvidence, knowledgeHitIds, pageCount, elementCount, usedBrowserDiscovery, explorationStatus, explorationRounds, totalScore, remainingDurationMs, explorationReport, "");
        }

        public BrowserEvidenceBundle {
            knowledgeHitIds = knowledgeHitIds == null ? List.of() : List.copyOf(knowledgeHitIds);
            explorationStatus = explorationStatus == null ? "legacy" : explorationStatus;
            sessionId = sessionId == null ? "" : sessionId;
        }
    }

    public record GeneratedWebCase(
        String sourcePointId,
        String title,
        String module,
        String caseKind,
        String pageName,
        String startUrl,
        String browser,
        String viewport,
        String precondition,
        String objective,
        String steps,
        String assertions,
        String expectedResult,
        String naturalSteps,
        String naturalExpectedResult,
        String sourceFile,
        String pageFingerprint,
        String pageKey,
        List<StepTrace> stepTraces,
        List<String> expectedResultEvidenceIds,
        JsonNode actions,
        String environmentId,
        String baseUrl,
        String authenticationMode
    ) {
        public GeneratedWebCase {
            stepTraces = stepTraces == null ? List.of() : List.copyOf(stepTraces);
            expectedResultEvidenceIds = expectedResultEvidenceIds == null ? List.of() : List.copyOf(expectedResultEvidenceIds);
            actions = actions == null || actions.isNull() ? null : actions.deepCopy();
            environmentId = environmentId == null ? "" : environmentId;
            baseUrl = baseUrl == null ? "" : baseUrl;
            authenticationMode = authenticationMode == null ? "" : authenticationMode;
        }

        public GeneratedWebCase(
            String sourcePointId, String title, String module, String caseKind, String pageName, String startUrl,
            String browser, String viewport, String precondition, String objective, String steps, String assertions,
            String expectedResult, String naturalSteps, String naturalExpectedResult, String sourceFile,
            String pageFingerprint, String pageKey, List<StepTrace> stepTraces, List<String> expectedResultEvidenceIds
        ) {
            this(sourcePointId, title, module, caseKind, pageName, startUrl, browser, viewport, precondition, objective,
                steps, assertions, expectedResult, naturalSteps, naturalExpectedResult, sourceFile, pageFingerprint,
                pageKey, stepTraces, expectedResultEvidenceIds, null, "", "", "");
        }

        public GeneratedWebCase(
            String sourcePointId, String title, String module, String caseKind, String pageName, String startUrl,
            String browser, String viewport, String precondition, String objective, String steps, String assertions,
            String expectedResult, String naturalSteps, String naturalExpectedResult, String sourceFile,
            String pageFingerprint, String pageKey, List<StepTrace> stepTraces, List<String> expectedResultEvidenceIds,
            JsonNode actions
        ) {
            this(sourcePointId, title, module, caseKind, pageName, startUrl, browser, viewport, precondition, objective,
                steps, assertions, expectedResult, naturalSteps, naturalExpectedResult, sourceFile, pageFingerprint,
                pageKey, stepTraces, expectedResultEvidenceIds, actions, "", "", "");
        }

        public GeneratedWebCase(
            String sourcePointId,
            String title,
            String module,
            String caseKind,
            String pageName,
            String startUrl,
            String browser,
            String viewport,
            String precondition,
            String objective,
            String steps,
            String assertions,
            String expectedResult,
            String naturalSteps,
            String naturalExpectedResult,
            String sourceFile
        ) {
            this(sourcePointId, title, module, caseKind, pageName, startUrl, browser, viewport, precondition, objective, steps, assertions, expectedResult, naturalSteps, naturalExpectedResult, sourceFile, "", "", List.of(), List.of(), null, "", "", "");
        }

        public GeneratedWebCase(
            String sourcePointId, String title, String module, String caseKind, String pageName, String startUrl,
            String browser, String viewport, String precondition, String objective, String steps, String assertions,
            String expectedResult, String naturalSteps, String naturalExpectedResult, String sourceFile, String pageFingerprint
        ) {
            this(sourcePointId, title, module, caseKind, pageName, startUrl, browser, viewport, precondition, objective,
                steps, assertions, expectedResult, naturalSteps, naturalExpectedResult, sourceFile, pageFingerprint, "", List.of(), List.of(), null, "", "", "");
        }
    }

    public record StepTrace(
        int stepIndex,
        String actionId,
        String naturalStep,
        String intent,
        List<String> dsl,
        List<String> evidenceIds,
        String pageFingerprint,
        String sourcePointId
    ) {
        public StepTrace {
            actionId = actionId == null ? "" : actionId;
            naturalStep = naturalStep == null ? "" : naturalStep;
            intent = intent == null ? "" : intent;
            dsl = dsl == null ? List.of() : List.copyOf(dsl);
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
            pageFingerprint = pageFingerprint == null ? "" : pageFingerprint;
            sourcePointId = sourcePointId == null ? "" : sourcePointId;
        }
    }
}
