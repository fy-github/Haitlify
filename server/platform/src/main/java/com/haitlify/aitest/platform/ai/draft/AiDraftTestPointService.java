package com.haitlify.aitest.platform.ai.draft;

import java.sql.Timestamp;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import com.haitlify.aitest.platform.auth.ProjectAccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.haitlify.aitest.platform.ai.gateway.AiGenerationResult;
import com.haitlify.aitest.platform.ai.gateway.ResolvedAiGatewaySettings;
import com.haitlify.aitest.platform.ai.agentic.locator.LocatorKnowledgeService;
import com.haitlify.aitest.platform.ai.call.AiCallRecordPayloads;
import com.haitlify.aitest.platform.ai.rule.AiRuleService;
import com.haitlify.aitest.platform.ai.skill.AiSkillPromptContextService;
import com.haitlify.aitest.platform.webautomation.WebAutomationLocatorDiscoveryService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
@Transactional
class AiDraftTestPointService {

    private static final Logger log = LoggerFactory.getLogger(AiDraftTestPointService.class);

    private static final int MIN_CASE_GENERATION_TIMEOUT_MS = 300_000;
    private static final WebAutomationLocatorDiscoveryService.ExplorationBudget DEFAULT_EXPLORATION_BUDGET =
        new WebAutomationLocatorDiscoveryService.ExplorationBudget(5, 90_000, 2, 1, 1, 3, 70, 25);
    private static final String MULTI_ROUND_EXPLORATION_FLAG = "webMultiRoundExplorationEnabled";
    private static final String EVIDENCE_SESSION_REUSE_FLAG = "webEvidenceSessionReuseEnabled";
    private static final String CRITICALITY_NETWORK_POLICY_FLAG = "webCriticalityAwareNetworkPolicyEnabled";
    private static final String DETERMINISTIC_COMPILATION_FLAG = "webCaseCompilationEnabled";
    private static final int MAX_PROMPT_SOURCE_EVIDENCE_CHARS = 2_400;
    private static final List<String> WEB_AUTOMATION_REQUIRED_FIELDS = List.of(
        "sourcePointId", "title", "module", "caseKind", "pageName", "startUrl", "browser", "viewport",
        "precondition", "objective", "naturalSteps", "naturalExpectedResult", "steps", "assertions",
        "expectedResult", "sourceFile", "sourceText"
    );
    private static final List<String> WEB_CONTENT_INTENT_KEYWORDS = List.of(
        "tab", "标签", "页签", "顺序", "展示", "显示", "可见", "列表", "字段", "按钮", "入口", "菜单",
        "筛选", "搜索", "导出", "状态", "文案", "标题", "列", "排序"
    );
    private static final List<String> WEB_DSL_COMMAND_PREFIXES = List.of(
        "goto ", "click ", "input ", "select ", "hover ", "waitForVisible ", "waitForUrl ",
        "assertVisible ", "assertHidden ", "assertText ", "assertUrl ", "assertEnabled ", "assertDisabled ", "scrollIntoView ", "paginate ", "screenshot "
    );
    private static final Pattern SEMANTIC_TOKEN = Pattern.compile("[\\p{IsHan}A-Za-z0-9_]{2,}");
    private static final List<String> GENERIC_ASSERTION_TERMS = List.of(
        "管理", "项目", "用户", "服务", "设备", "系统", "菜单", "页面", "列表", "范围", "权限", "角色",
        "查看", "展示", "显示", "可见", "进入", "登录", "首页", "普通", "成员"
    );
    private static final List<String> WEB_DSL_ACTIONS = List.of(
        "goto <url>",
        "click <locator>",
        "input <locator> = <literal|{{variable}}>",
        "select <locator> = <value>",
        "hover <locator>",
        "waitForVisible <locator>",
        "waitForUrl <url-pattern>",
        "assertVisible <locator>",
        "assertHidden <locator>",
        "assertText <locator> = <expected-text>",
        "assertUrl <url-pattern>",
        "assertEnabled <locator>",
        "assertDisabled <locator>",
        "screenshot <name>"
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade taskFacade;
    private final com.haitlify.aitest.platform.ai.gateway.AiGatewaySettingsService aiGatewaySettingsService;
    private final AiRuleService aiRuleService;
    private final AiSkillPromptContextService aiSkillPromptContextService;
    private final WebAutomationCaseValidator webAutomationCaseValidator;
    private final WebAutomationLocatorDiscoveryService webAutomationLocatorDiscoveryService;
    private final LocatorKnowledgeService locatorKnowledgeService;
    private final WebExplorationIntentBuilder webExplorationIntentBuilder = new WebExplorationIntentBuilder();
    private final WebExplorationSemanticExpansionService webExplorationSemanticExpansionService;
    private final WebCaseCompilationService webCaseCompilationService;
    private ProjectAccessService projectAccessService;

    AiDraftTestPointService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade taskFacade,
        com.haitlify.aitest.platform.ai.gateway.AiGatewaySettingsService aiGatewaySettingsService,
        AiRuleService aiRuleService,
        AiSkillPromptContextService aiSkillPromptContextService,
        WebAutomationCaseValidator webAutomationCaseValidator,
        WebAutomationLocatorDiscoveryService webAutomationLocatorDiscoveryService,
        LocatorKnowledgeService locatorKnowledgeService,
        WebExplorationSemanticExpansionService webExplorationSemanticExpansionService,
        WebCaseCompilationService webCaseCompilationService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.taskFacade = taskFacade;
        this.aiGatewaySettingsService = aiGatewaySettingsService;
        this.aiRuleService = aiRuleService;
        this.aiSkillPromptContextService = aiSkillPromptContextService;
        this.webAutomationCaseValidator = webAutomationCaseValidator;
        this.webAutomationLocatorDiscoveryService = webAutomationLocatorDiscoveryService;
        this.locatorKnowledgeService = locatorKnowledgeService;
        this.webExplorationSemanticExpansionService = webExplorationSemanticExpansionService;
        this.webCaseCompilationService = webCaseCompilationService;
        this.projectAccessService = null;
    }

    /** 兼容构造（单元测试直构）：项目访问闸为 null 时跳过；HTTP/集成级负例走真实 bean。 */
    @org.springframework.beans.factory.annotation.Autowired
    AiDraftTestPointService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade taskFacade,
        com.haitlify.aitest.platform.ai.gateway.AiGatewaySettingsService aiGatewaySettingsService,
        AiRuleService aiRuleService,
        AiSkillPromptContextService aiSkillPromptContextService,
        WebAutomationCaseValidator webAutomationCaseValidator,
        WebAutomationLocatorDiscoveryService webAutomationLocatorDiscoveryService,
        LocatorKnowledgeService locatorKnowledgeService,
        WebExplorationSemanticExpansionService webExplorationSemanticExpansionService,
        WebCaseCompilationService webCaseCompilationService,
        ProjectAccessService projectAccessService
    ) {
        this(jdbcTemplate, objectMapper, taskFacade, aiGatewaySettingsService, aiRuleService,
            aiSkillPromptContextService, webAutomationCaseValidator, webAutomationLocatorDiscoveryService,
            locatorKnowledgeService, webExplorationSemanticExpansionService, webCaseCompilationService);
        this.projectAccessService = projectAccessService;
    }

    AiDraftTestPointListResponse listByRun(String runId) {
        List<AiDraftTestPointResponse> items = jdbcTemplate.queryForList("""
                select p.*, r.run_id as external_run_id, c.chunk_id, d.document_id as source_document_external_id
                from ai_draft_test_points p
                join ai_analysis_runs r on r.id = p.run_id
                left join ai_source_chunks c on c.id = p.source_chunk_id
                left join ai_source_documents d on d.id = p.source_document_id
                where r.run_id = ?
                  and p.review_status <> 'deleted'
                order by p.created_at asc
                """, runId)
            .stream()
            .map(this::toResponse)
            .toList();
        return new AiDraftTestPointListResponse(items);
    }

    /**
     * 全链路人工确认 Exit Gate（draft test point）：真实认证 actor + 项目权限 + expectedRevision（过期 409）
     * + 允许的前置状态 + CAS 写入（防并发双写，重复确认幂等）+ 审计携带 actor 与来源快照。
     * 不存在与无权同形 404（不得用状态码区分对象存在）。省略 revision 保持兼容（与全平台契约一致）。
     */
    AiDraftTestPointResponse confirm(String pointId, ReviewDraftPointRequest request) {
        String actor = currentHumanActor();
        Map<String, Object> row = findPointOrNotFound(pointId);
        requirePointAccess(row);
        guardRevision(row, request == null ? null : request.revision());
        String status = String.valueOf(row.get("REVIEW_STATUS"));
        if (ACCEPTED_STATUSES.contains(status)) {
            return toResponse(row);
        }
        if (!"ai_generated".equals(status)) {
            throw stateConflict("confirm", status);
        }
        int updated = jdbcTemplate.update(
            "update ai_draft_test_points set review_status = ?, result_origin = ?, acceptance_state = ?,"
                + " review_comment = ?, updated_at = ? where point_id = ? and review_status = ?",
            "confirmed", "HUMAN_EDITED", "ACCEPTED",
            request == null ? "" : blankToDefault(request.comment(), ""),
            Timestamp.from(Instant.now()), pointId, "ai_generated");
        Map<String, Object> after = findPointOrNotFound(pointId);
        if (updated == 0) {
            if (ACCEPTED_STATUSES.contains(String.valueOf(after.get("REVIEW_STATUS")))) {
                return toResponse(after);
            }
            throw stateConflict("confirm", String.valueOf(after.get("REVIEW_STATUS")));
        }
        appendReviewAction(after, "confirm", actor, row, after, request == null ? "" : blankToDefault(request.comment(), ""));
        return toResponse(after);
    }

    AiDraftTestPointResponse editConfirm(String pointId, EditDraftPointRequest request) {
        String actor = currentHumanActor();
        Map<String, Object> before = findPointOrNotFound(pointId);
        requirePointAccess(before);
        guardRevision(before, request == null ? null : request.revision());
        String status = String.valueOf(before.get("REVIEW_STATUS"));
        if ("deleted".equals(status) || "superseded".equals(status)) {
            throw stateConflict("edit", status);
        }
        int nextRevision = revisionOf(before) + 1;
        String nextPointId = pointId + "-r" + nextRevision;
        // 旧行 → superseded（CAS：仅当状态未变，防并发双写）。
        int superseded = jdbcTemplate.update(
            "update ai_draft_test_points set review_status = ?, review_comment = ?, updated_at = ?"
                + " where point_id = ? and review_status = ?",
            "superseded", "由 revision " + nextRevision + " 取代", Timestamp.from(Instant.now()), pointId, status);
        if (superseded == 0) {
            throw new com.haitlify.aitest.platform.ai.agentic.StaleArtifactRevisionException(
                "point review state changed concurrently: " + pointId);
        }
        jdbcTemplate.update("""
            insert into ai_draft_test_points(
              point_id, run_id, project_id, source_document_id, source_chunk_id, title, module_name,
              page_or_feature, case_kind, test_type, priority_level, precondition_text, test_objective,
              suggested_steps, expected_result, source_file, source_section, source_page, source_text,
              source_image_id, extraction_method, confidence_score, inferred, review_status, review_comment,
              template_id, metadata_json, actor_id, actor_name, revision, parent_artifact_id, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'modified',
              ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            nextPointId, before.get("RUN_ID"), before.get("PROJECT_ID"), before.get("SOURCE_DOCUMENT_ID"),
            before.get("SOURCE_CHUNK_ID"),
            blankToDefault(request.title(), (String) before.get("TITLE")),
            before.get("MODULE_NAME"),
            before.get("PAGE_OR_FEATURE"), before.get("CASE_KIND"), before.get("TEST_TYPE"),
            before.get("PRIORITY_LEVEL"), before.get("PRECONDITION_TEXT"),
            blankToDefault(request.objective(), (String) before.get("TEST_OBJECTIVE")),
            blankToDefault(request.steps(), (String) before.get("SUGGESTED_STEPS")),
            blankToDefault(request.expectedResult(), (String) before.get("EXPECTED_RESULT")),
            before.get("SOURCE_FILE"), before.get("SOURCE_SECTION"), before.get("SOURCE_PAGE"),
            before.get("SOURCE_TEXT"), before.get("SOURCE_IMAGE_ID"), before.get("EXTRACTION_METHOD"),
            before.get("CONFIDENCE_SCORE"), before.get("INFERRED"),
            blankToDefault(request.comment(), ""),
            before.get("TEMPLATE_ID"), before.get("METADATA_JSON"),
            actor, com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystemName(),
            nextRevision, pointId,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        Map<String, Object> after = findPointOrNotFound(nextPointId);
        appendReviewAction(before, "supersede", actor, before, before,
            "由 revision " + nextRevision + "（" + nextPointId + "）取代");
        appendReviewAction(after, "edit_confirm", actor, before, after,
            blankToDefault(request.comment(), ""));
        return toResponse(after);
    }

    AiDraftTestPointResponse reject(String pointId, ReviewDraftPointRequest request) {
        String actor = currentHumanActor();
        Map<String, Object> row = findPointOrNotFound(pointId);
        requirePointAccess(row);
        guardRevision(row, request == null ? null : request.revision());
        String status = String.valueOf(row.get("REVIEW_STATUS"));
        if ("rejected".equals(status)) {
            return toResponse(row);
        }
        if (!"ai_generated".equals(status)) {
            throw stateConflict("reject", status);
        }
        int updated = jdbcTemplate.update(
            "update ai_draft_test_points set review_status = ?, result_origin = ?, acceptance_state = ?,"
                + " review_comment = ?, updated_at = ? where point_id = ? and review_status = ?",
            "rejected", "BLOCKED", "BLOCKED",
            request == null ? "" : blankToDefault(request.comment(), ""),
            Timestamp.from(Instant.now()), pointId, "ai_generated");
        Map<String, Object> after = findPointOrNotFound(pointId);
        if (updated == 0) {
            if ("rejected".equals(String.valueOf(after.get("REVIEW_STATUS")))) {
                return toResponse(after);
            }
            throw stateConflict("reject", String.valueOf(after.get("REVIEW_STATUS")));
        }
        appendReviewAction(after, "reject", actor, row, after, request == null ? "" : blankToDefault(request.comment(), ""));
        return toResponse(after);
    }

    void delete(String pointId) {
        String actor = currentHumanActor();
        Map<String, Object> before = findPointOrNotFound(pointId);
        requirePointAccess(before);
        String status = String.valueOf(before.get("REVIEW_STATUS"));
        if ("deleted".equals(status)) {
            return;
        }
        int updated = jdbcTemplate.update(
            "update ai_draft_test_points set review_status = ?, result_origin = ?, acceptance_state = ?,"
                + " review_comment = ?, updated_at = ? where point_id = ? and review_status = ?",
            "deleted", "BLOCKED", "BLOCKED", "用户删除测试点", Timestamp.from(Instant.now()), pointId, status);
        Map<String, Object> after = findPointOrNotFound(pointId);
        if (updated == 0) {
            if ("deleted".equals(String.valueOf(after.get("REVIEW_STATUS")))) {
                return;
            }
            throw stateConflict("delete", String.valueOf(after.get("REVIEW_STATUS")));
        }
        appendReviewAction(after, "delete", actor, before, after, "用户删除测试点");
    }

    GeneratedCaseListResponse generateCases(GenerateCasesRequest request) {
        return generateCases(request, null);
    }

    GeneratedCaseListResponse generateCases(GenerateCasesRequest request, JsonNode agentExecutionRules) {
        return generateCases(request, agentExecutionRules, null);
    }

    GeneratedCaseListResponse generateCases(GenerateCasesRequest request, JsonNode agentExecutionRules, JsonNode agentMemoryContext) {
        List<Map<String, Object>> points = request.pointIds().stream().map(this::findPoint).toList();
        validateGenerationPoints(points);
        ResolvedAiGatewaySettings settings = aiGatewaySettingsService.loadDefaultTextSettings();
        ObjectNode prompt = buildFormalCaseGenerationPrompt(points, request.locatorDiscovery() != null, agentExecutionRules, agentMemoryContext);
        long started = System.currentTimeMillis();
        AiGenerationResult result = taskFacade.generate(
            new com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade.TaskExecution(
                "draft_case_generation", projectId(points), "system", null, prompt,
                (long) Math.max(settings.timeoutMs(), MIN_CASE_GENERATION_TIMEOUT_MS)));
        insertCallRecord(
            projectId(points),
            settings,
            result,
            System.currentTimeMillis() - started,
            prompt,
            result == null ? "AI 用例生成失败" : result.errorMessage()
        );
        if (result == null || !result.success()) {
            throw new IllegalArgumentException(blankToDefault(result == null ? "" : result.errorMessage(), "AI 用例生成失败"));
        }

        ValidatedGeneratedOutput validated = validateGeneratedOutput(result.content(), points, request.locatorDiscovery());
        if (request.locatorDiscovery() != null) {
            WebExplorationFlags flags = loadWebExplorationFlags();
            ArrayNode browserLocatorEvidence = collectBrowserLocatorEvidence(result.content(), request.locatorDiscovery(), projectId(points), points, flags);
            validated = validateGeneratedCaseSemantics(validated, points, browserLocatorEvidence);
            validated = validateGeneratedCaseCoverage(validated, points);
            if (!validated.errors().isEmpty()) {
                throw new IllegalArgumentException("AI 生成结果不符合 Web 自动化执行规范：" + String.join("；", validated.errors()));
            }
            return new GeneratedCaseListResponse(parseGeneratedCases(validated.output(), points));
        }

        validated = validateGeneratedCaseCoverage(validated, points);
        if (!validated.errors().isEmpty()) {
            throw new IllegalArgumentException("AI 生成结果不符合 Web 自动化执行规范：" + String.join("；", validated.errors()));
        }
        return new GeneratedCaseListResponse(parseGeneratedCases(validated.output(), points));
    }

    WebCasePlanOutput planWebCases(List<String> pointIds, JsonNode agentExecutionRules, JsonNode agentMemoryContext) {
        List<Map<String, Object>> points = pointIds.stream().map(this::findPoint).toList();
        validateGenerationPoints(points);
        ResolvedAiGatewaySettings settings = aiGatewaySettingsService.loadDefaultTextSettings();
        ObjectNode prompt = buildFormalCaseGenerationPrompt(points, true, agentExecutionRules, agentMemoryContext);
        long started = System.currentTimeMillis();
        AiGenerationResult result = taskFacade.generate(
            new com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade.TaskExecution(
                "draft_web_case_plan", projectId(points), "system", null, prompt,
                (long) Math.max(settings.timeoutMs(), MIN_CASE_GENERATION_TIMEOUT_MS)));
        insertCallRecord(
            projectId(points),
            settings,
            result,
            System.currentTimeMillis() - started,
            prompt,
            result == null ? "AI Web 测试设计生成失败" : result.errorMessage()
        );
        if (result == null || !result.success()) {
            throw new IllegalArgumentException(blankToDefault(result == null ? "" : result.errorMessage(), "AI Web 测试设计生成失败"));
        }
        JsonNode output = normalizeCaseEnvelope(parseJsonObject(result.content(), "AI Web 测试设计输出不是合法 JSON"));
        List<String> errors = validateNaturalPlanCoverageFromSourcePoints(output, points);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("AI Web 测试设计不完整：" + String.join("；", errors));
        }
        // Provider request id is the only auditable proof that this plan came from a real model call, so
        // it must travel with the output to the artifact writer instead of being discarded here.
        return new WebCasePlanOutput(output, blankToDefault(result.requestId(), ""));
    }

    /**
     * 用刚收集的页面证据（含真实 evidenceId）为单个测试点授权一次 Action IR。
     *
     * <p>编译只接受结构化 actions，而 evidenceId 是页面内容派生的、计划期尚不存在，
     * 因此当已确认计划缺失 actions 时，必须在取证之后、编译之前补一次聚焦的动作授权模型调用；
     * 授权结果必须通过 {@link ActionIrValidator} 的完整校验，否则 fail-closed。</p>
     */
    JsonNode authorWebCaseActions(String sourcePointId, JsonNode caseItem, JsonNode browserEvidence) {
        return authorWebCaseActions(sourcePointId, caseItem, browserEvidence, List.of());
    }

    JsonNode authorWebCaseActions(String sourcePointId, JsonNode caseItem, JsonNode browserEvidence, List<String> excludedTargetIds) {
        List<Map<String, Object>> points = List.of(findPoint(sourcePointId));
        validateGenerationPoints(points);
        ResolvedAiGatewaySettings settings = aiGatewaySettingsService.loadDefaultTextSettings();
        ObjectNode prompt = buildActionIrAuthoringPrompt(sourcePointId, caseItem, browserEvidence, excludedTargetIds);
        long started = System.currentTimeMillis();
        AiGenerationResult result = taskFacade.generate(
            new com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade.TaskExecution(
                "draft_web_case_actions", projectId(points), "system", null, prompt,
                (long) Math.max(settings.timeoutMs(), MIN_CASE_GENERATION_TIMEOUT_MS)));
        insertCallRecord(
            projectId(points),
            settings,
            result,
            System.currentTimeMillis() - started,
            prompt,
            result == null ? "AI Web 动作授权生成失败" : result.errorMessage()
        );
        if (result == null || !result.success()) {
            throw new IllegalArgumentException(blankToDefault(result == null ? "" : result.errorMessage(), "AI Web 动作授权生成失败"));
        }
        JsonNode output = normalizeCaseEnvelope(parseJsonObject(result.content(), "AI Web 动作授权输出不是合法 JSON"));
        JsonNode cases = caseArray(output);
        JsonNode actions = null;
        if (cases != null && cases.isArray()) {
            for (JsonNode item : cases) {
                if (item.isObject() && sourcePointId.equals(firstNonBlank(item.path("sourcePointId").asText(""), item.path("pointId").asText("")))) {
                    actions = item.path("actions");
                    break;
                }
            }
        }
        if (actions == null || !actions.isArray() || actions.isEmpty()) {
            throw new IllegalArgumentException("AI Web 动作授权失败：" + sourcePointId + " 未返回 actions");
        }
        List<String> inputActionErrors = rejectAuthoringInputActions(actions);
        if (!inputActionErrors.isEmpty()) {
            throw new IllegalArgumentException(
                "AI Web 动作授权失败：" + sourcePointId + " 新 Web 动作授权路径禁止输入动作：" + String.join("；", inputActionErrors));
        }
        List<String> errors = ActionIrValidator.validate(actions);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(
                "AI Web 动作授权失败：" + sourcePointId + " actions 不符合 Action IR 规范：" + String.join("；", errors));
        }
        log.info("Web 动作授权完成 pointId={} actionCount={} providerRequestId={}",
            sourcePointId, actions.size(), blankToDefault(result.requestId(), ""));
        return actions;
    }

    private ObjectNode buildActionIrAuthoringPrompt(String sourcePointId, JsonNode caseItem, JsonNode browserEvidence, List<String> excludedTargetIds) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("instruction", "你是 Web 自动化动作授权器。请为给定测试用例产出结构化 actions（Action IR），"
            + "只输出 JSON：{\"cases\":[{\"sourcePointId\":\"" + sourcePointId + "\",\"actions\":[...]}]}，不要输出解释或其它字段。");
        ObjectNode rules = root.putObject("actionIrRules");
        rules.put("elementAction", "元素动作：{\"id\":\"a1\",\"type\":\"click|assertVisible|assertText|assertEnabled|assertDisabled|waitForVisible|scrollIntoView\","
            + "\"safety\":\"readonly\",\"target\":{\"kind\":\"element\",\"evidenceId\":\"<必须原样取自 pageElements 的 evidenceId>\"}}；"
            + "assertText 另需 \"expected\":\"<期望文本>\"。"
            + "元素在初始观察中已存在：用 assertVisible/assertEnabled 直接断言，不要用 waitForVisible 作开头动作。");
        rules.put("routeAction", "路由动作：{\"id\":\"a0\",\"type\":\"navigate|waitForUrl|assertUrl\",\"safety\":\"readonly\","
            + "\"target\":{\"kind\":\"route\",\"value\":\"<以 / 或 # 开头的相对地址，或合法 http(s) URL>\"}}。");
        rules.put("valueAction", "新 Web 动作授权路径禁止输出 fill/input/select（包括其输入动作别名）；如需求包含输入或选择步骤，必须 fail-closed，不得猜测、编造或填入任意业务值。");
        ArrayNode constraints = rules.putArray("constraints");
        constraints.add("元素动作只允许 safety=readonly；禁止 mutating/dangerous");
        constraints.add("每个动作 id 唯一，actions 按执行顺序排列");
        constraints.add("至少包含一个断言动作（assertVisible/assertText/assertUrl/assertEnabled/assertDisabled）");
        constraints.add("evidenceId 只能原样使用 pageElements 中列出的值，禁止编造");
        constraints.add("禁止输出 CSS/XPath/testId 选择器字符串");
        constraints.add("优先选择名称稳定的导航/区域/标题/按钮类元素，避免依赖随实时数据变化的展示内容");
        ArrayNode elements = root.putArray("pageElements");
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode page : evidencePages(browserEvidence)) {
            String pagePointId = page.path("sourcePointId").asText("");
            if (!sourcePointId.isBlank() && !pagePointId.isBlank() && !sourcePointId.equals(pagePointId)) continue;
            for (JsonNode element : initialObservationElements(page)) {
                String evidenceId = firstNonBlank(element.path("evidenceId").asText(""), element.path("id").asText(""));
                if (evidenceId.isBlank() || !seen.add(evidenceId) || elements.size() >= 160) continue;
                // 上一轮二次取证中点名失效的目标不再作为候选：它们在重新打开页面后不存在（实时数据/不稳定元素）。
                if (excludedTargetIds != null && excludedTargetIds.contains(evidenceId)) continue;
                String label = firstNonBlank(
                    element.path("label").asText(""), element.path("text").asText(""), element.path("name").asText(""),
                    element.path("locatorSpec").path("text").asText(""), element.path("locator").asText(""));
                // 名称含数字计数的元素（如「待处理告警 485」「在线 2」）会随实时数据变化，
                // 其内容派生的 evidenceId 在下一轮观察中就不存在（实测 2026-10-14 导致 422 action_evidence_missing）。
                // 这类元素不进候选，避免模型绑定到注定失效的目标。
                if (label.chars().anyMatch(Character::isDigit)) continue;
                ObjectNode item = elements.addObject();
                item.put("evidenceId", evidenceId);
                item.put("label", label);
                item.put("locator", element.path("locator").asText(""));
            }
        }
        ArrayNode cases = root.putArray("cases");
        ObjectNode item = cases.addObject();
        item.put("sourcePointId", sourcePointId);
        for (String field : List.of("title", "pageName", "startUrl", "objective", "naturalSteps", "naturalExpectedResult")) {
            item.put(field, caseItem.path(field).asText(""));
        }
        return root;
    }

    /**
     * ActionIrValidator also serves the confirmed execution path, where value sources
     * have already been approved. New model authoring must not invent business inputs.
     */
    private List<String> rejectAuthoringInputActions(JsonNode actions) {
        List<String> errors = new ArrayList<>();
        for (int index = 0; index < actions.size(); index++) {
            JsonNode action = actions.get(index);
            String type = action.path("type").asText("");
            if (Set.of("fill", "input", "select").contains(type)) {
                errors.add("actions[" + index + "] type=" + type);
            }
        }
        return errors;
    }

    /**
     * 动作授权只应绑定「用例开始时」的页面状态：顶层 elements 是探索结束态（探索器自己的点击/悬停会改变页面），
     * 用它授权会让动作目标在重新打开页面时不存在（实测 2026-10-14 422 action_evidence_missing）。
     * 优先取首轮观察（初始态）；无探索数据时退回顶层 elements。
     */
    private JsonNode initialObservationElements(JsonNode page) {
        JsonNode rounds = page.path("exploration").path("rounds");
        if (rounds.isArray() && !rounds.isEmpty()) {
            JsonNode observation = rounds.get(0).path("observation");
            for (String field : List.of("elements", "allElements")) {
                JsonNode elements = observation.path(field);
                if (elements.isArray() && !elements.isEmpty()) return elements;
            }
        }
        return page.path("elements");
    }

    private List<JsonNode> evidencePages(JsonNode browserEvidence) {
        List<JsonNode> pages = new ArrayList<>();
        if (browserEvidence == null || browserEvidence.isNull()) return pages;
        if (browserEvidence.isArray()) {
            browserEvidence.forEach(pages::add);
        } else if (browserEvidence.path("pages").isArray()) {
            browserEvidence.path("pages").forEach(pages::add);
        } else {
            pages.add(browserEvidence);
        }
        return pages;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    JsonNode resolveWebCaseEvidence(List<String> pointIds, JsonNode planningOutput, WebLocatorDiscoveryContext locatorDiscovery) {
        return resolveWebCaseEvidence(pointIds, planningOutput, locatorDiscovery, List.of(), 0L);
    }

    JsonNode resolveWebCaseEvidence(List<String> pointIds, JsonNode planningOutput, WebLocatorDiscoveryContext locatorDiscovery, List<String> previousAttempts, long remainingDurationMs) {
        List<Map<String, Object>> points = pointIds.stream().map(this::findPoint).toList();
        validateGenerationPoints(points);
        if (locatorDiscovery == null) {
            throw new IllegalArgumentException("请先配置 Web 运行环境，才能执行自动化落地取证");
        }
        WebExplorationFlags flags = loadWebExplorationFlags();
        return collectBrowserLocatorEvidence(planningOutput.toString(), locatorDiscovery, projectId(points), points, previousAttempts, remainingDurationMs, flags);
    }

    GeneratedCaseListResponse compileWebCases(
        List<String> pointIds,
        JsonNode planningOutput,
        JsonNode browserLocatorEvidence,
        WebLocatorDiscoveryContext locatorDiscovery,
        JsonNode agentExecutionRules,
        JsonNode agentMemoryContext
    ) {
        List<Map<String, Object>> points = pointIds.stream().map(this::findPoint).toList();
        validateGenerationPoints(points);
        if (!loadWebExplorationFlags().deterministicCaseCompilationEnabled()) {
            throw new IllegalArgumentException("webCaseCompilationEnabled 未启用，禁止回退到旧的 grounded/repair 编译路径");
        }
        AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch naturalPlanBatch = buildNaturalPlanBatch(planningOutput, pointIds);
        WebCaseCompilationService.CompilationResult compilation = webCaseCompilationService.compileEvidenceBoundCase(
            naturalPlanBatch,
            browserLocatorEvidence,
            points
        );
        if (!compilation.success()) {
            throw new IllegalArgumentException("AI Web 自动化编译失败：" + String.join("；", compilation.errors()));
        }
        return new GeneratedCaseListResponse(compilation.cases().stream().map(item -> new GeneratedCaseResponse(
            item.sourcePointId(), item.title(), item.module(), item.caseKind(), item.pageName(), item.startUrl(),
            item.browser(), item.viewport(), item.precondition(), item.objective(), item.steps(), item.assertions(),
            item.expectedResult(), item.naturalSteps(), item.naturalExpectedResult(), item.sourceFile(), sourceField(points, item.sourcePointId(), "SOURCE_TEXT"),
            item.pageFingerprint(), item.pageKey(), item.stepTraces(), item.expectedResultEvidenceIds(), item.actions(),
            item.environmentId(), item.baseUrl(), item.authenticationMode()
        )).toList());
    }

    AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch buildNaturalPlanBatch(JsonNode output, List<String> pointIds) {
        JsonNode cases = caseArray(output);
        if (cases == null || !cases.isArray()) {
            cases = output != null && output.isArray() ? output : objectMapper.createArrayNode();
        }
        java.util.Set<String> requestedPointIds = pointIds == null
            ? java.util.Set.of()
            : new java.util.LinkedHashSet<>(pointIds);
        List<AgentWebCaseGenerationFacade.NaturalWebCasePlan> plans = new ArrayList<>();
        if (cases.isArray()) {
            for (JsonNode item : cases) {
                plans.add(new AgentWebCaseGenerationFacade.NaturalWebCasePlan(
                    item.path("sourcePointId").asText(""),
                    firstText(item, "title"),
                    firstText(item, "module", "moduleName"),
                    firstText(item, "pageName", "targetPage", "page", "pageOrFeature"),
                    firstText(item, "startUrl", "pageUrl", "url", "path"),
                    naturalStepList(item.path("naturalSteps").isMissingNode() ? item.path("steps") : item.path("naturalSteps")),
                    firstText(item, "naturalExpectedResult", "expectedResult"),
                    firstText(item, "objective", "testObjective"),
                    item.path("actions").isArray() ? item.path("actions") : null,
                    firstText(item, "environmentId"), firstText(item, "baseUrl"), firstText(item, "browser"),
                    firstText(item, "viewport"), firstText(item, "authenticationMode")
                ));
            }
        }
        List<String> errors = validateNaturalPlanCoverage(plans, requestedPointIds);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("AI Web 自然语言计划不完整：" + String.join("；", errors));
        }
        // 编译路径按测试点逐条执行：已确认的计划批次可能覆盖整批测试点，而本次只编译 requestedPointIds。
        // 这里把批次收窄到本次编译的点——否则下游 compileEvidenceBoundCase 会因其它点缺少单点证据
        // 而报 step_evidence_missing，逐点编译永远无法完成。
        List<AgentWebCaseGenerationFacade.NaturalWebCasePlan> scopedPlans = requestedPointIds.isEmpty()
            ? plans
            : plans.stream()
                .filter(plan -> requestedPointIds.contains(plan.sourcePointId() == null ? "" : plan.sourcePointId().strip()))
                .toList();
        return new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(scopedPlans, output);
    }

    private String sourceField(List<Map<String, Object>> points, String pointId, String field) {
        for (Map<String, Object> row : points) {
            if (!pointId.equals(row.get("POINT_ID"))) continue;
            Object value = row.get(field);
            return value == null ? "" : String.valueOf(value);
        }
        return "";
    }

    private JsonNode parseJsonObject(String content, String errorPrefix) {
        try {
            JsonNode output = objectMapper.readTree(stripJsonCodeFence(content));
            if (output != null && output.isObject()) return output;
            if (output != null && output.isArray()) {
                ObjectNode wrapped = objectMapper.createObjectNode();
                wrapped.set("cases", output);
                return wrapped;
            }
        } catch (Exception exception) {
            throw new IllegalArgumentException(errorPrefix + "：" + exception.getMessage());
        }
        throw new IllegalArgumentException(errorPrefix + "：根节点必须是 JSON 对象");
    }

    /**
     * Normalizes common provider envelopes before applying the strict point coverage checks.
     * Providers occasionally return a fenced JSON document, a JSON string in `testCases`, or
     * wrap the actual payload in `data`/`result`; these are transport differences, not a reason
     * to discard an otherwise valid test design.
     */
    JsonNode normalizeCaseEnvelope(JsonNode output) {
        JsonNode cases = findCaseArray(output, 0);
        if (!cases.isArray()) return output;
        if (output != null && output.isObject() && output.path("cases").isArray()) return output;
        ObjectNode normalized = objectMapper.createObjectNode();
        normalized.set("cases", cases);
        return normalized;
    }

    private JsonNode findCaseArray(JsonNode node, int depth) {
        if (node == null || node.isNull() || depth > 5) return objectMapper.createObjectNode();
        if (node.isArray()) return node;
        if (node.isTextual()) {
            String text = node.asText("").strip();
            if (text.isBlank()) return objectMapper.createObjectNode();
            try {
                return findCaseArray(objectMapper.readTree(stripJsonCodeFence(text)), depth + 1);
            } catch (Exception ignored) {
                return objectMapper.createObjectNode();
            }
        }
        if (!node.isObject()) return objectMapper.createObjectNode();
        for (String field : List.of("cases", "items", "testCases", "test_cases", "caseList", "data", "result", "output", "content")) {
            JsonNode candidate = node.path(field);
            if (candidate.isMissingNode() || candidate.isNull()) continue;
            JsonNode found = findCaseArray(candidate, depth + 1);
            if (found.isArray()) return found;
        }
        if (node.has("sourcePointId") && (node.has("title") || node.has("naturalSteps") || node.has("steps"))) {
            ArrayNode single = objectMapper.createArrayNode();
            single.add(node);
            return single;
        }
        return objectMapper.createObjectNode();
    }

    private String stripJsonCodeFence(String content) {
        String value = content == null ? "" : content.strip();
        if (!value.startsWith("```")) return value;
        int firstLineEnd = value.indexOf('\n');
        if (firstLineEnd < 0) return value;
        int closingFence = value.lastIndexOf("```");
        if (closingFence <= firstLineEnd) return value.substring(firstLineEnd + 1).strip();
        return value.substring(firstLineEnd + 1, closingFence).strip();
    }

    private List<String> validateNaturalPlanCoverageFromSourcePoints(JsonNode output, List<Map<String, Object>> sourcePoints) {
        JsonNode cases = caseArray(output);
        if (!cases.isArray()) {
            return List.of("输出字段 cases/items/testCases 必须是数组");
        }
        return validateNaturalPlanCoverageFromJson(cases, sourcePoints.stream()
            .map(point -> (String) point.get("POINT_ID"))
            .toList());
    }

    List<String> validateNaturalPlanCoverageFromJson(JsonNode output, List<String> requestedPointIds) {
        JsonNode cases = caseArray(output);
        if (!cases.isArray()) {
            return List.of("输出字段 cases/items/testCases 必须是数组");
        }
        return validateNaturalPlanCoverage(cases, requestedPointIds);
    }

    private List<String> validateNaturalPlanCoverage(List<AgentWebCaseGenerationFacade.NaturalWebCasePlan> plans, java.util.Set<String> requestedPointIds) {
        List<String> errors = new ArrayList<>();
        if (plans == null || plans.isEmpty()) {
            errors.add("自然语言计划不能为空");
            return errors;
        }
        java.util.Set<String> generatedPointIds = new java.util.LinkedHashSet<>();
        for (AgentWebCaseGenerationFacade.NaturalWebCasePlan plan : plans) {
            String pointId = blankToDefault(plan.sourcePointId(), "").strip();
            if (pointId.isBlank()) {
                errors.add("自然语言计划缺少 sourcePointId");
                continue;
            }
            if (!requestedPointIds.isEmpty() && !requestedPointIds.contains(pointId)) {
                // 编译调用允许「批次大于本次请求」：其它测试点的计划在各自的编译调用中处理，此处跳过，
                // 由 buildNaturalPlanBatch 收窄后交给编译服务。
                continue;
            }
            if (!generatedPointIds.add(pointId)) {
                errors.add("自然语言计划存在重复的 sourcePointId：" + pointId);
                continue;
            }
            if (blankToDefault(plan.title(), "").isBlank()) {
                errors.add("自然语言计划 " + pointId + " 缺少 title");
            }
            if (blankToDefault(plan.pageName(), "").isBlank()) {
                errors.add("自然语言计划 " + pointId + " 缺少 pageName/targetPage");
            }
            if (plan.naturalSteps() == null || plan.naturalSteps().isEmpty()) {
                errors.add("自然语言计划 " + pointId + " 缺少 naturalSteps");
            }
            if (blankToDefault(plan.naturalExpectedResult(), "").isBlank()) {
                errors.add("自然语言计划 " + pointId + " 缺少 naturalExpectedResult");
            }
        }
        if (!requestedPointIds.isEmpty()) {
            requestedPointIds.stream()
                .filter(pointId -> !generatedPointIds.contains(pointId))
                .sorted()
                .forEach(pointId -> errors.add("当前批次测试点未生成自然语言计划：" + pointId));
        }
        return errors;
    }

    private List<String> validateNaturalPlanCoverage(JsonNode cases, List<String> requestedPointIds) {
        java.util.Set<String> requested = requestedPointIds == null
            ? java.util.Set.of()
            : new java.util.LinkedHashSet<>(requestedPointIds);
        List<String> errors = new ArrayList<>();
        if (cases == null || !cases.isArray()) {
            errors.add("输出字段 cases/items/testCases 必须是数组");
            return errors;
        }
        if (cases.isEmpty()) {
            errors.add("自然语言计划不能为空");
            return errors;
        }
        java.util.Set<String> generatedPointIds = new java.util.LinkedHashSet<>();
        for (JsonNode item : cases) {
            String pointId = item.path("sourcePointId").asText("").strip();
            if (pointId.isBlank()) {
                errors.add("自然语言计划缺少 sourcePointId");
                continue;
            }
            if (!requested.isEmpty() && !requested.contains(pointId)) {
                errors.add("自然语言计划引用了当前批次之外的 sourcePointId：" + pointId);
                continue;
            }
            if (!generatedPointIds.add(pointId)) {
                errors.add("自然语言计划存在重复的 sourcePointId：" + pointId);
                continue;
            }
            if (firstPlannedText(item, null, "title").isBlank()) {
                errors.add("自然语言计划 " + pointId + " 缺少 title");
            }
            if (firstPlannedText(item, null, "pageName", "targetPage", "page", "pageOrFeature").isBlank()) {
                errors.add("自然语言计划 " + pointId + " 缺少 pageName/targetPage");
            }
            if (firstPlannedText(item, null, "naturalSteps", "steps").isBlank()) {
                errors.add("自然语言计划 " + pointId + " 缺少 naturalSteps");
            }
            if (firstPlannedText(item, null, "naturalExpectedResult", "expectedResult").isBlank()) {
                errors.add("自然语言计划 " + pointId + " 缺少 naturalExpectedResult");
            }
        }
        requested.stream()
            .filter(pointId -> !generatedPointIds.contains(pointId))
            .sorted()
            .forEach(pointId -> errors.add("当前批次测试点未生成自然语言计划：" + pointId));
        return errors;
    }

    private void validateGenerationPoints(List<Map<String, Object>> points) {
        for (Map<String, Object> row : points) {
            String status = (String) row.get("REVIEW_STATUS");
            if (!"confirmed".equals(status) && !"modified".equals(status)) {
                throw new IllegalArgumentException("Only confirmed/modified draft test points can generate formal cases");
            }
            String sourceText = (String) row.get("SOURCE_TEXT");
            String sourceFile = (String) row.get("SOURCE_FILE");
            if ((sourceText == null || sourceText.isBlank()) && (sourceFile == null || sourceFile.isBlank())) {
                throw new IllegalArgumentException("Source evidence is required before generating formal cases");
            }
        }
    }

    private ObjectNode buildFormalCaseGenerationPrompt(List<Map<String, Object>> points, boolean browserLocatorPlanning, JsonNode agentExecutionRules, JsonNode agentMemoryContext) {
        ObjectNode prompt = objectMapper.createObjectNode();
        prompt.put("skillId", "formal-web-case-generation");
        prompt.set("skill", aiSkillPromptContextService.resolve("formal-web-case-generation"));
        prompt.set("effectiveRules", objectMapper.valueToTree(aiRuleService.resolveEffectiveRules(projectId(points))));
        if (agentExecutionRules != null && !agentExecutionRules.isNull()) {
            prompt.set("agentExecutionRules", agentExecutionRules);
            prompt.put("agentExecutionRulesNote", "必须遵守 agentExecutionRules 中适用于当前阶段的规则；这些规则不允许覆盖浏览器取证、locator 白名单和确定性校验。");
        }
        if (agentMemoryContext != null && agentMemoryContext.isArray() && !agentMemoryContext.isEmpty()) {
            prompt.set("agentMemoryContext", agentMemoryContext);
            prompt.put("agentMemoryContextNote", "agentMemoryContext 是人工确认的历史经验，仅作可复用参考；当前浏览器证据和需求原文优先，不能据此猜测 locator。");
        }
        prompt.put(
            "instruction",
            "根据已确认的需求测试点生成可落表的正式测试用例。输出 JSON 根字段 cases；Web 用例必须包含 sourcePointId,title,module,caseKind,pageName,startUrl,browser,viewport,precondition,objective,naturalSteps,naturalExpectedResult,actions,steps,assertions,expectedResult,sourceFile,sourceText。必须先理解每个测试点的 title、pageOrFeature、objective、expectedResult 和 sourceText 在测试什么目标，再输出结构化 actions。actions 是自动化编译的唯一主输入，必须是数组；每个 action 必须包含 id、type、target 或 assertion，禁止输出 CSS/XPath/testId locator 字符串。支持 navigate、click、fill、select、hover、waitForVisible、assertVisible、assertHidden、assertText、assertUrl、assertEnabled、assertDisabled、scrollIntoView、paginate、screenshot。fill/select 必须包含 value.kind 和 value.key/value，禁止生成无来源的 inputValue/selectValue 占位符。assert 必须拆分 type、target、expected，不能把业务描述整句当作页面文本。每个 action 必须声明 safety；确认、提交、保存、删除、停用、启用、支付、发送、上传默认为 mutating/dangerous。自然语言字段仅用于展示和追溯；steps 由服务端根据 actions 和浏览器 evidence 确定性编译，不得由模型生成 locator DSL。"
        );
        prompt.put("webAutomationStepFormat", "一行一个动作，严格使用固定动作关键字；每个 sourcePointId 的 steps 均须包含至少一个 assertVisible/assertHidden/assertText/assertUrl/assertEnabled/assertDisabled；当测试目标涉及 Tab/顺序/展示/列表/字段/按钮/菜单/文案时，steps 必须包含与目标元素对应的 assertVisible 或 assertText，不得只写 assertUrl；禁止 manual 和编号自然语言步骤；无法自动执行时不要生成该用例。禁止输出自然语言段落。");
        ArrayNode webDslActions = objectMapper.createArrayNode();
        WEB_DSL_ACTIONS.forEach(webDslActions::add);
        prompt.set("webAutomationDslActions", webDslActions);
        prompt.set("webAutomationRequiredFields", objectMapper.valueToTree(WEB_AUTOMATION_REQUIRED_FIELDS));
        ArrayNode webDslExamples = objectMapper.createArrayNode();
        webDslExamples.add("goto /login");
        webDslExamples.add("input css=#username = {{webUsername}}");
        webDslExamples.add("input css=#password = {{webPassword}}");
        webDslExamples.add("click role=button[name=\"登录\"]");
        webDslExamples.add("waitForUrl /user-center");
        webDslExamples.add("assertVisible text=用户管理");
        prompt.set("webAutomationDslExample", webDslExamples);
        ArrayNode artifacts = objectMapper.createArrayNode();
        for (Map<String, Object> row : points) {
            ObjectNode artifact = objectMapper.createObjectNode();
            artifact.put("sourcePointId", (String) row.get("POINT_ID"));
            artifact.put("title", (String) row.get("TITLE"));
            artifact.put("module", (String) row.get("MODULE_NAME"));
            artifact.put("pageOrFeature", (String) row.get("PAGE_OR_FEATURE"));
            artifact.put("caseKind", (String) row.get("CASE_KIND"));
            artifact.put("objective", (String) row.get("TEST_OBJECTIVE"));
            artifact.put("steps", (String) row.get("SUGGESTED_STEPS"));
            artifact.put("expectedResult", (String) row.get("EXPECTED_RESULT"));
            artifact.put("sourceFile", (String) row.get("SOURCE_FILE"));
            artifact.put("sourceText", compactPromptSourceEvidence(row));
            artifacts.add(artifact);
        }
        prompt.put(
            "sourceEvidenceNote",
            "每个 sourceText 仅提供与测试点相关的原文摘录，用于生成判断；服务端会保留完整原始文件与原文作为最终用例的追溯证据。"
        );
        prompt.set("confirmedDraftTestPoints", artifacts);
        if (browserLocatorPlanning) {
            prompt.put("browserLocatorPlanning", true);
            prompt.put(
                "instruction",
                "这是 Web 用例生成的第一阶段：只生成自然语言业务用例，不生成自动化 DSL，不猜测 locator。为每个已确认测试点输出 JSON 根字段 testCases（兼容 cases/items 也可），至少包含 sourcePointId、title、module、pageName 或 targetPage、startUrl、naturalSteps、naturalExpectedResult。必须先理解测试点的标题、目标页面、步骤目标和预期结果。startUrl 能确定时填可访问 URL/路径/hash 路由；不能确定时用 / 作为兜底入口。第二阶段会优先读取定位器素材库，缺失时再由浏览器采集 DOM，并把采集到的元素回写素材库。"
            );
        }
        return prompt;
    }

    private String compactPromptSourceEvidence(Map<String, Object> row) {
        String sourceText = blankToDefault((String) row.get("SOURCE_TEXT"), "").strip();
        if (sourceText.length() <= MAX_PROMPT_SOURCE_EVIDENCE_CHARS) {
            return sourceText;
        }

        int anchor = findEvidenceAnchor(sourceText,
            (String) row.get("TITLE"),
            (String) row.get("TEST_OBJECTIVE"),
            (String) row.get("EXPECTED_RESULT")
        );
        int start = anchor < 0
            ? 0
            : Math.max(0, anchor - MAX_PROMPT_SOURCE_EVIDENCE_CHARS / 3);
        int end = Math.min(sourceText.length(), start + MAX_PROMPT_SOURCE_EVIDENCE_CHARS);
        if (end - start < MAX_PROMPT_SOURCE_EVIDENCE_CHARS) {
            start = Math.max(0, end - MAX_PROMPT_SOURCE_EVIDENCE_CHARS);
        }
        return sourceText.substring(start, end).strip();
    }

    private int findEvidenceAnchor(String sourceText, String... candidates) {
        for (String candidate : candidates) {
            String value = blankToDefault(candidate, "").strip();
            int exactIndex = sourceText.indexOf(value);
            if (value.length() >= 4 && exactIndex >= 0) {
                return exactIndex;
            }
            for (String term : value.split("[\\s,，。；;、:：()（）]+")) {
                String normalized = term.strip();
                int termIndex = sourceText.indexOf(normalized);
                if (normalized.length() >= 4 && termIndex >= 0) {
                    return termIndex;
                }
            }
        }
        return -1;
    }

    private ArrayNode collectRequiredSourcePointIds(ObjectNode prompt) {
        ArrayNode ids = objectMapper.createArrayNode();
        JsonNode points = prompt.path("confirmedDraftTestPoints");
        if (!points.isArray()) return ids;
        for (JsonNode point : points) {
            String pointId = point.path("sourcePointId").asText("").strip();
            if (!pointId.isBlank()) ids.add(pointId);
        }
        return ids;
    }

    private ArrayNode collectMissingSourcePointIds(List<String> errors) {
        ArrayNode ids = objectMapper.createArrayNode();
        String prefix = "当前批次测试点未生成用例：";
        for (String error : errors) {
            String value = blankToDefault(error, "").strip();
            int start = value.indexOf(prefix);
            if (start < 0) continue;
            String pointId = value.substring(start + prefix.length()).strip();
            int separator = firstErrorSeparator(pointId);
            if (separator >= 0) {
                pointId = pointId.substring(0, separator).strip();
            }
            if (!pointId.isBlank()) ids.add(pointId);
        }
        return ids;
    }

    private int firstErrorSeparator(String value) {
        int first = -1;
        for (String separator : List.of("；", ";", "，", ",", "。", "\n", "\r")) {
            int index = value.indexOf(separator);
            if (index >= 0 && (first < 0 || index < first)) {
                first = index;
            }
        }
        return first;
    }

    private ValidatedGeneratedOutput validateGeneratedCaseCoverage(
        ValidatedGeneratedOutput validated,
        List<Map<String, Object>> sourcePoints
    ) {
        JsonNode cases = caseArray(validated.output());
        if (!cases.isArray()) return validated;

        java.util.Set<String> requestedPointIds = sourcePoints.stream()
            .map(point -> (String) point.get("POINT_ID"))
            .collect(java.util.stream.Collectors.toSet());
        java.util.Set<String> generatedPointIds = new java.util.HashSet<>();
        List<String> errors = new ArrayList<>(validated.errors());
        for (JsonNode testCase : cases) {
            String pointId = testCase.path("sourcePointId").asText("").strip();
            if (pointId.isBlank()) continue;
            if (!requestedPointIds.contains(pointId)) {
                errors.add("用例引用了当前批次之外的 sourcePointId：" + pointId);
                continue;
            }
            generatedPointIds.add(pointId);
        }
        requestedPointIds.stream()
            .filter(pointId -> !generatedPointIds.contains(pointId))
            .sorted()
            .forEach(pointId -> errors.add("当前批次测试点未生成用例：" + pointId));
        return new ValidatedGeneratedOutput(validated.output(), errors);
    }

    private ValidatedGeneratedOutput validateGeneratedCaseSemantics(
        ValidatedGeneratedOutput validated,
        List<Map<String, Object>> sourcePoints
    ) {
        return validateGeneratedCaseSemantics(validated, sourcePoints, null);
    }

    private ValidatedGeneratedOutput validateGeneratedCaseSemantics(
        ValidatedGeneratedOutput validated,
        List<Map<String, Object>> sourcePoints,
        ArrayNode browserLocatorEvidence
    ) {
        JsonNode cases = caseArray(validated.output());
        if (!cases.isArray()) return validated;

        Map<String, Map<String, Object>> sourceByPointId = sourcePoints.stream()
            .collect(java.util.stream.Collectors.toMap(row -> (String) row.get("POINT_ID"), row -> row, (left, right) -> left));
        Map<String, String> evidenceLabelsByLocator = collectEvidenceLabelsByLocator(browserLocatorEvidence);
        List<String> errors = new ArrayList<>(validated.errors());
        for (JsonNode testCase : cases) {
            if (!"web_automation".equals(testCase.path("caseKind").asText(""))) continue;
            String pointId = testCase.path("sourcePointId").asText("").strip();
            String caseLabel = pointId.isBlank() ? "用例：" : "用例 " + pointId + "：";
            String steps = testCase.path("steps").asText("").strip();
            String expectedResult = testCase.path("expectedResult").asText("").strip();
            String assertions = testCase.path("assertions").asText("").strip();
            String intentText = semanticIntentText(testCase, sourceByPointId.get(pointId));

            if (isDslOnlyText(expectedResult)) {
                errors.add(caseLabel + "预期结果不能写 DSL 命令，必须描述与测试目标一致的页面/业务结果：" + expectedResult);
            }
            if (isContentOrientedIntent(intentText) && !hasElementAssertion(steps)) {
                errors.add(caseLabel + "测试目标涉及页面元素/展示/顺序，执行步骤不能只使用 assertUrl，必须围绕测试目标使用浏览器采集元素写 assertVisible/assertText 断言");
            }
            if (isContentOrientedIntent(intentText) && !hasGoalAlignedElementAssertion(steps, testCase, sourceByPointId.get(pointId), evidenceLabelsByLocator)) {
                errors.add(caseLabel + "执行步骤中的元素断言未命中自然语言步骤/预期中的目标元素，不能用全局导航按钮替代业务页面断言");
            }
            if (isContentOrientedIntent(intentText) && assertions.equals(expectedResult) && isDslOnlyText(assertions)) {
                errors.add(caseLabel + "断言与预期结果均退化为 DSL，未体现 title/objective/pageOrFeature 的测试目标");
            }
        }
        return new ValidatedGeneratedOutput(validated.output(), errors);
    }

    private String semanticIntentText(JsonNode testCase, Map<String, Object> source) {
        StringBuilder builder = new StringBuilder();
        appendSemanticText(builder, testCase.path("title").asText(""));
        appendSemanticText(builder, testCase.path("pageName").asText(""));
        appendSemanticText(builder, testCase.path("objective").asText(""));
        appendSemanticText(builder, testCase.path("expectedResult").asText(""));
        appendSemanticText(builder, textValue(testCase.path("naturalExpectedResult")));
        appendSemanticText(builder, testCase.path("sourceText").asText(""));
        appendSemanticText(builder, textValue(testCase.path("naturalSteps")));
        if (source != null) {
            appendSemanticText(builder, (String) source.get("TITLE"));
            appendSemanticText(builder, (String) source.get("PAGE_OR_FEATURE"));
            appendSemanticText(builder, (String) source.get("TEST_OBJECTIVE"));
            appendSemanticText(builder, (String) source.get("EXPECTED_RESULT"));
            appendSemanticText(builder, (String) source.get("SOURCE_TEXT"));
        }
        return builder.toString();
    }

    private void appendSemanticText(StringBuilder builder, String value) {
        String normalized = blankToDefault(value, "").strip();
        if (!normalized.isBlank()) {
            builder.append(' ').append(normalized);
        }
    }

    private boolean isContentOrientedIntent(String intentText) {
        String normalized = blankToDefault(intentText, "").toLowerCase(java.util.Locale.ROOT);
        return WEB_CONTENT_INTENT_KEYWORDS.stream().anyMatch(keyword -> normalized.contains(keyword.toLowerCase(java.util.Locale.ROOT)));
    }

    private boolean hasElementAssertion(String steps) {
        for (String rawLine : blankToDefault(steps, "").split("\\R")) {
            String line = rawLine.strip();
            if (line.startsWith("assertVisible ") || line.startsWith("assertHidden ") || line.startsWith("assertText ") || line.startsWith("assertEnabled ") || line.startsWith("assertDisabled ")) {
                return true;
            }
        }
        return false;
    }

    private Map<String, String> collectEvidenceLabelsByLocator(ArrayNode browserLocatorEvidence) {
        Map<String, String> evidenceByLocator = new java.util.LinkedHashMap<>();
        if (browserLocatorEvidence == null) return evidenceByLocator;
        for (JsonNode page : browserLocatorEvidence) {
            for (JsonNode element : page.path("elements")) {
                String locator = element.path("locator").asText("").strip();
                if (locator.isBlank()) continue;
                String label = firstText(element, "label", "text", "name", "innerText", "accessibleName");
                if (!label.isBlank() && !evidenceByLocator.containsKey(locator)) {
                    evidenceByLocator.put(locator, label);
                }
            }
        }
        return evidenceByLocator;
    }

    private boolean hasGoalAlignedElementAssertion(
        String steps,
        JsonNode testCase,
        Map<String, Object> source,
        Map<String, String> evidenceLabelsByLocator
    ) {
        List<String> goalTerms = semanticGoalTerms(testCase, source);
        if (goalTerms.isEmpty()) return hasElementAssertion(steps);
        String intentText = semanticIntentText(testCase, source).toLowerCase(java.util.Locale.ROOT);
        for (String rawLine : blankToDefault(steps, "").split("\\R")) {
            String line = rawLine.strip();
            if (!(line.startsWith("assertVisible ") || line.startsWith("assertHidden ") || line.startsWith("assertText ") || line.startsWith("assertEnabled ") || line.startsWith("assertDisabled "))) continue;
            String assertionTarget = assertionLocatorTarget(line);
            String evidenceText = evidenceLabelsByLocator == null ? "" : evidenceLabelsByLocator.getOrDefault(assertionTarget, "");
            if (!evidenceText.isBlank() && evidenceTextMatchesIntent(evidenceText, intentText)) return true;
            String normalizedTarget = assertionTarget.toLowerCase(java.util.Locale.ROOT);
            for (String term : goalTerms) {
                if (normalizedTarget.contains(term.toLowerCase(java.util.Locale.ROOT))) return true;
            }
        }
        return false;
    }

    private boolean evidenceTextMatchesIntent(String evidenceText, String normalizedIntentText) {
        String text = blankToDefault(evidenceText, "").strip();
        if (text.length() < 2 || GENERIC_ASSERTION_TERMS.contains(text)) return false;
        return normalizedIntentText.contains(text.toLowerCase(java.util.Locale.ROOT));
    }

    private String assertionLocatorTarget(String assertionLine) {
        String target = assertionLine.substring(assertionLine.indexOf(' ') + 1).strip();
        if (assertionLine.startsWith("assertText ")) {
            String[] parts = target.split("\\s+=\\s+", 2);
            return parts.length == 0 ? target : parts[0].strip();
        }
        return target;
    }

    private List<String> semanticGoalTerms(JsonNode testCase, Map<String, Object> source) {
        StringBuilder builder = new StringBuilder();
        appendSemanticText(builder, testCase.path("title").asText(""));
        appendSemanticText(builder, testCase.path("pageName").asText(""));
        appendSemanticText(builder, testCase.path("objective").asText(""));
        appendSemanticText(builder, textValue(testCase.path("naturalSteps")));
        appendSemanticText(builder, textValue(testCase.path("naturalExpectedResult")));
        if (source != null) {
            appendSemanticText(builder, (String) source.get("TITLE"));
            appendSemanticText(builder, (String) source.get("PAGE_OR_FEATURE"));
            appendSemanticText(builder, (String) source.get("TEST_OBJECTIVE"));
            appendSemanticText(builder, (String) source.get("EXPECTED_RESULT"));
        }
        return extractSemanticTerms(builder.toString());
    }

    private List<String> extractSemanticTerms(String value) {
        String normalized = blankToDefault(value, "");
        java.util.LinkedHashSet<String> terms = new java.util.LinkedHashSet<>();
        Matcher matcher = SEMANTIC_TOKEN.matcher(normalized);
        while (matcher.find()) {
            String token = matcher.group().strip();
            if (token.length() < 3) continue;
            addSemanticTerm(terms, token);
            if (containsHan(token)) {
                int maxSize = Math.min(6, token.length());
                for (int size = 3; size <= maxSize; size++) {
                    for (int start = 0; start + size <= token.length(); start++) {
                        addSemanticTerm(terms, token.substring(start, start + size));
                    }
                }
            }
        }
        for (String raw : normalized.split("[\\s,，。；;、:：/\\\\|()（）\\[\\]【】\"'“”]+")) {
            String token = raw.strip();
            if (token.length() < 3) continue;
            addSemanticTerm(terms, token);
        }
        return terms.stream().limit(16).toList();
    }

    private void addSemanticTerm(java.util.LinkedHashSet<String> terms, String token) {
        String normalized = blankToDefault(token, "").strip();
        if (normalized.length() < 3) return;
        if (GENERIC_ASSERTION_TERMS.contains(normalized)) return;
        terms.add(normalized);
    }

    private boolean containsHan(String value) {
        return value.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private boolean isDslOnlyText(String value) {
        String normalized = blankToDefault(value, "").strip();
        if (normalized.isBlank()) return false;
        String[] lines = normalized.split("\\R");
        boolean sawCommand = false;
        for (String rawLine : lines) {
            String line = rawLine.strip();
            if (line.isBlank()) continue;
            boolean command = WEB_DSL_COMMAND_PREFIXES.stream().anyMatch(line::startsWith);
            if (!command) return false;
            sawCommand = true;
        }
        return sawCommand;
    }

    private ArrayNode collectBrowserLocatorEvidence(
        String planningOutput,
        WebLocatorDiscoveryContext context,
        Long projectId,
        List<Map<String, Object>> sourcePoints,
        List<String> previousAttempts,
        long remainingDurationMs
    ) {
        return collectBrowserLocatorEvidence(planningOutput, context, projectId, sourcePoints, previousAttempts, remainingDurationMs, loadWebExplorationFlags());
    }

    private LocatorKnowledgeService.ValidationState browserValidationState(WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse snapshot) {
        if (snapshot == null || snapshot.exploration() == null) return LocatorKnowledgeService.ValidationState.NOT_ELIGIBLE;
        var report = snapshot.exploration();
        boolean authenticated = Boolean.TRUE.equals(snapshot.authenticated());
        boolean ready = report.readiness() == null || report.readiness().path("ready").asBoolean(false);
        boolean exploratory = report.status() != null && !"blocked".equals(report.status());
        JsonNode network = report.network();
        boolean networkClear = network == null || (network.path("criticalBlocked").isEmpty() && network.path("unknownBlocked").isEmpty());
        boolean complete = report.scores() != null && report.scores().matched();
        return authenticated && ready && exploratory && networkClear && complete
            ? LocatorKnowledgeService.ValidationState.CONFIRMED_PAGE_MISS
            : LocatorKnowledgeService.ValidationState.NOT_ELIGIBLE;
    }

    private ArrayNode collectBrowserLocatorEvidence(
        String planningOutput,
        WebLocatorDiscoveryContext context,
        Long projectId,
        List<Map<String, Object>> sourcePoints
    ) {
        return collectBrowserLocatorEvidence(planningOutput, context, projectId, sourcePoints, List.of(), 0L, loadWebExplorationFlags());
    }

    private ArrayNode collectBrowserLocatorEvidence(
        String planningOutput,
        WebLocatorDiscoveryContext context,
        Long projectId,
        List<Map<String, Object>> sourcePoints,
        WebExplorationFlags flags
    ) {
        return collectBrowserLocatorEvidence(planningOutput, context, projectId, sourcePoints, List.of(), 0L, flags);
    }

    private ArrayNode collectBrowserLocatorEvidence(
        String planningOutput,
        WebLocatorDiscoveryContext context,
        Long projectId,
        List<Map<String, Object>> sourcePoints,
        List<String> previousAttempts,
        long remainingDurationMs,
        WebExplorationFlags flags
    ) {
        if (context.baseUrl() == null || context.baseUrl().isBlank()) {
            throw new IllegalArgumentException("请先配置 Web 运行环境的 Base URL，才能由浏览器采集页面定位器");
        }
        JsonNode cases;
        try {
            JsonNode output = parseJsonObject(planningOutput, "AI Web 自然语言计划输出不是合法 JSON");
            cases = caseArray(output);
        } catch (Exception exception) {
            throw new IllegalArgumentException("无法从首轮 AI 规划中读取目标页面，不能采集浏览器定位器");
        }
        if (!cases.isArray()) {
            throw new IllegalArgumentException("首轮 AI 规划未返回 cases/items/testCases，不能采集浏览器定位器");
        }

        Map<String, Map<String, Object>> sourceByPointId = sourcePoints.stream()
            .collect(java.util.stream.Collectors.toMap(row -> (String) row.get("POINT_ID"), row -> row, (left, right) -> left));
        ArrayNode evidence = objectMapper.createArrayNode();
        Map<String, DiscoveryTarget> targets = new LinkedHashMap<>();
        for (JsonNode testCase : cases) {
            String pointId = testCase.path("sourcePointId").asText("").strip();
            Map<String, Object> source = sourceByPointId.get(pointId);
            if (source == null) continue;
            String moduleName = firstPlannedText(testCase, source, "module", "moduleName");
            String pageName = firstPlannedText(testCase, source, "pageName", "targetPage", "page", "pageOrFeature");
            String startUrl = extractPlannedStartUrl(testCase);
            String plannedStartUrl = startUrl.isBlank() ? "/" : startUrl;
            List<LocatorKnowledgeService.LocatorKnowledgeCandidate> historicalCandidates;
            try {
                historicalCandidates = locatorKnowledgeService.searchCandidates(
                    projectId, context.environmentId(), moduleName, pageName, locatorKnowledgeKeyword(testCase, source), null
                );
            } catch (org.springframework.dao.DataAccessException readFailure) {
                historicalCandidates = List.of();
                log.warn("素材库查询失败，降级浏览器取证 projectId={} pointId={} causeType={}",
                    projectId, pointId, readFailure.getClass().getSimpleName(), readFailure);
            }
            var expansion = webExplorationSemanticExpansionService.expandWithDiagnostics(
                webExplorationIntentBuilder.build(testCase, source),
                List.of(testCase.toString(), source.toString()),
                flags.semanticExpansionEnabled()
            );
            var historicalIntent = addHistoricalLocatorHints(expansion.intent(), historicalCandidates);
            String targetKey = pointId + "\u0000" + resolveDiscoveryUrl(context.baseUrl(), plannedStartUrl);
            if (!targets.containsKey(targetKey)) {
                targets.put(targetKey, new DiscoveryTarget(pointId, plannedStartUrl, moduleName, pageName, historicalIntent, expansion.repairSuggestion(), historicalCandidates, testCase.path("actions")));
            }
        }

        for (Map.Entry<String, DiscoveryTarget> target : targets.entrySet()) {
            // 采集请求没有变量通道（DTO 里没有 variables）：带运行时值来源的动作下发到 runner 只会 422，
            // 而异常会冒泡成整批运行失败。这里在计划期就阻塞这个测试点并指名动作，其余点照常执行。
            String unavailableValueSource = DiscoveryValueSources.unsupportedDiscoveryValueSource(target.getValue().actions());
            if (!unavailableValueSource.isBlank()) {
                throw new WebExplorationBlockedException(target.getValue().pointId(), "value_source_unavailable: " + unavailableValueSource, null);
            }
            WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse snapshot = webAutomationLocatorDiscoveryService.discover(
                new WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryRequest(
                    resolveDiscoveryUrl(context.baseUrl(), target.getValue().plannedStartUrl()), context.baseUrl(), context.browser(), context.viewport(), context.headless(), context.timeoutMs(),
                    context.waitUntil(), context.authToken(), context.extraHeaders(), context.cookieOrigins(), context.allowedRequestOrigins(), toBrowserLogin(context.login()),
                    List.of(target.getValue().pageName(), target.getValue().moduleName()),
                    target.getValue().targetIntent(), explorationBudget(flags, remainingDurationMs), previousAttempts == null ? List.of() : previousAttempts,
                    context.sessionId(), Boolean.TRUE.equals(context.reuseSession()) && flags.evidenceSessionReuseEnabled(),
                    context.sessionId() == null || context.sessionId().isBlank()
                        ? projectId + ":" + context.environmentId() + ":" + context.browser()
                        : context.environmentId() + ":" + context.browser(),
                    target.getValue().pointId(),
                    target.getValue().repairSuggestion() == null ? null : new WebAutomationLocatorDiscoveryService.RepairSuggestion(target.getValue().repairSuggestion().category(), target.getValue().repairSuggestion().reason()),
                    flags.criticalityAwareNetworkPolicyEnabled(),
                    target.getValue().historicalCandidates().stream().map(candidate -> new WebAutomationLocatorDiscoveryService.HistoricalLocatorCandidate(
                        candidate.entryId(), candidate.locator(), candidate.label(), candidate.role(), candidate.type(), candidate.confidenceScore(),
                        candidate.pageUrl(), candidate.pageFingerprint(), target.getValue().pointId(), candidate.projectId(), candidate.environmentId()
                    )).toList(),
                    allowedRequestUrls(context), context.diagnosticMode(), context.tracingEnabled(), target.getValue().actions(),
                    context.networkPolicyMode()
                )
            );
            try {
                locatorKnowledgeService.recordBrowserValidation(
                    target.getValue().historicalCandidates().stream().map(LocatorKnowledgeService.LocatorKnowledgeCandidate::entryId).toList(),
                    snapshot.historicalMatchedIds(),
                    browserValidationState(snapshot)
                );
            } catch (org.springframework.dao.DataAccessException validationPersistenceFailure) {
                log.warn("历史素材验证评分写回失败，保留本次浏览器证据 projectId={} pointId={} causeType={}",
                    projectId, target.getValue().pointId(), validationPersistenceFailure.getClass().getSimpleName());
            }
            WebAutomationLocatorDiscoveryService.ExplorationReport exploration = enrichExplorationReport(snapshot);
            if (exploration != null && (exploration.scores() == null || !exploration.scores().matched())) {
                String reason = exploration.failureReason();
                throw new WebExplorationBlockedException(target.getValue().pointId(), reason, exploration);
            }
            locatorKnowledgeService.recordAgentEvidence(
                projectId, context.environmentId(), target.getValue().moduleName(), target.getValue().pageName(), snapshot.pageUrl(), snapshot.elements(),
                exploration == null ? null : new LocatorKnowledgeService.ExplorationKnowledge(
                    exploration.navigationTrail(),
                    exploration.scores() == null ? 0 : exploration.scores().totalScore(),
                    exploration.rounds() == null ? 0 : exploration.rounds().size(),
                    exploration.status(),
                    null,
                    snapshot.pageUrl()
                )
            );
            WebStepPageEvidenceAssembler.appendDiscoveryBundles(
                evidence,
                snapshot,
                target.getValue().pointId(),
                target.getValue().plannedStartUrl(),
                target.getValue().moduleName(),
                target.getValue().pageName(),
                exploration == null ? null : objectMapper.valueToTree(exploration)
            );
        }
        return evidence;
    }

    private WebAutomationLocatorDiscoveryService.ExplorationReport enrichExplorationReport(WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse snapshot) {
        if (snapshot == null || snapshot.exploration() == null) return null;
        return new WebAutomationLocatorDiscoveryService.ExplorationReport(
            snapshot.exploration().status(),
            snapshot.exploration().rounds(),
            snapshot.exploration().scores(),
            snapshot.exploration().navigationTrail(),
            snapshot.exploration().failureReason(),
            snapshot.exploration().durationMs(),
            snapshot.exploration().finalObservation(),
            snapshot.exploration().remainingDurationMs(),
            snapshot.exploration().trailError(),
            snapshot.exploration().network(),
            snapshot.exploration().readiness(),
            snapshot.exploration().repairTrail(),
            snapshot.historicalMatchedIds(),
            snapshot.historicalRejectedIds()
        );
    }

    private WebAutomationLocatorDiscoveryService.ExplorationBudget explorationBudget(WebExplorationFlags flags, long remainingDurationMs) {
        return explorationBudgetFor(flags.multiRoundExplorationEnabled(), remainingDurationMs);
    }

    static WebAutomationLocatorDiscoveryService.ExplorationBudget explorationBudgetFor(boolean multiRoundEnabled, long remainingDurationMs) {
        long duration = remainingDurationMs <= 0
            ? DEFAULT_EXPLORATION_BUDGET.maxDurationMs()
            : Math.min(DEFAULT_EXPLORATION_BUDGET.maxDurationMs(), remainingDurationMs);
        if (!multiRoundEnabled) {
            return new WebAutomationLocatorDiscoveryService.ExplorationBudget(
                1, duration, 0, 0, 0, 0,
                DEFAULT_EXPLORATION_BUDGET.minimumTotalScore(),
                DEFAULT_EXPLORATION_BUDGET.minimumElementScore()
            );
        }
        return new WebAutomationLocatorDiscoveryService.ExplorationBudget(
            DEFAULT_EXPLORATION_BUDGET.maxRounds(), duration,
            DEFAULT_EXPLORATION_BUDGET.maxSameFailureRetries(),
            DEFAULT_EXPLORATION_BUDGET.maxLoginRetries(),
            DEFAULT_EXPLORATION_BUDGET.maxReloads(),
            DEFAULT_EXPLORATION_BUDGET.maxPaginationSteps(),
            DEFAULT_EXPLORATION_BUDGET.minimumTotalScore(),
            DEFAULT_EXPLORATION_BUDGET.minimumElementScore()
        );
    }

    private String firstPlannedText(JsonNode testCase, Map<String, Object> source, String... fields) {
        for (String field : fields) {
            String value = textValue(testCase.path(field));
            if (!value.isBlank()) return value;
        }
        if (source == null) return "";
        for (String field : fields) {
            String value = switch (field) {
                case "module", "moduleName" -> (String) source.get("MODULE_NAME");
                case "pageName", "targetPage", "page", "pageOrFeature" -> (String) source.get("PAGE_OR_FEATURE");
                default -> "";
            };
            if (value != null && !value.isBlank()) return value.strip();
        }
        return "";
    }

    private String locatorKnowledgeKeyword(JsonNode testCase, Map<String, Object> source) {
        StringBuilder builder = new StringBuilder();
        for (String field : List.of("title", "objective", "expectedResult", "naturalExpectedResult", "sourceText")) {
            appendSemanticText(builder, testCase.path(field).asText(""));
        }
        appendSemanticText(builder, textValue(testCase.path("naturalSteps")));
        appendSemanticText(builder, testCase.path("steps").toString());
        if (source != null) {
            appendSemanticText(builder, (String) source.get("TITLE"));
            appendSemanticText(builder, (String) source.get("TEST_OBJECTIVE"));
            appendSemanticText(builder, (String) source.get("EXPECTED_RESULT"));
            appendSemanticText(builder, (String) source.get("SOURCE_TEXT"));
        }
        return builder.toString();
    }

    private WebAutomationLocatorDiscoveryService.TargetIntent addHistoricalLocatorHints(
        WebAutomationLocatorDiscoveryService.TargetIntent intent,
        List<LocatorKnowledgeService.LocatorKnowledgeCandidate> candidates
    ) {
        return intent;
    }

    String resolveDiscoveryUrl(String baseUrl, String startUrl) {
        try {
            String normalizedBaseUrl = baseUrl.strip();
            String normalizedStartUrl = startUrl.strip();
            if (normalizedStartUrl.startsWith("data:")) {
                return normalizedStartUrl;
            }
            java.net.URI rawBase = java.net.URI.create(normalizedBaseUrl);
            String baseWithoutFragment = baseUrlWithoutFragment(rawBase);
            if (normalizedStartUrl.startsWith("#/")) {
                return baseWithoutFragment + normalizedStartUrl;
            }
            if (normalizedStartUrl.startsWith("/") && rawBase.getFragment() != null && rawBase.getFragment().startsWith("/")) {
                // 站点入口语义（计划写 "/"）时，环境 baseUrl 的 hash 路由（如 "#/home"）才是可用入口；
                // 合并成 "#/" 会落到不渲染的根路由（2026-10-14 实测：该页永远停在全局加载态、取证元素 0）。
                if ("/".equals(normalizedStartUrl)) {
                    return normalizedBaseUrl;
                }
                return baseWithoutFragment + "#" + normalizedStartUrl;
            }
            java.net.URI base = java.net.URI.create(normalizedBaseUrl.endsWith("/") ? normalizedBaseUrl : normalizedBaseUrl + "/");
            java.net.URI resolved = base.resolve(startUrl);
            if (!"http".equalsIgnoreCase(resolved.getScheme()) && !"https".equalsIgnoreCase(resolved.getScheme())) {
                throw new IllegalArgumentException();
            }
            return resolved.toString();
        } catch (Exception exception) {
            throw new IllegalArgumentException("Web 运行环境 Base URL 或用例 startUrl 无效，不能采集浏览器定位器");
        }
    }

    private List<String> pageOrigins(WebLocatorDiscoveryContext context) {
        java.util.LinkedHashSet<String> origins = new java.util.LinkedHashSet<>();
        addOrigin(origins, context.baseUrl(), false);
        return List.copyOf(origins);
    }

    private List<String> allowedRequestUrls(WebLocatorDiscoveryContext context) {
        java.util.LinkedHashSet<String> urls = new java.util.LinkedHashSet<>();
        if (context.login() != null) addExactRequestUrl(urls, context.login().url());
        if (context.allowedRequestOrigins() != null) context.allowedRequestOrigins().forEach(value -> addExactRequestUrl(urls, value));
        return List.copyOf(urls);
    }

    private void addExactRequestUrl(java.util.Set<String> urls, String value) {
        if (value == null || value.isBlank()) return;
        try {
            URI uri = URI.create(value.strip());
            if (("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null && uri.getUserInfo() == null
                && uri.getPath() != null && !uri.getPath().isBlank() && !"/".equals(uri.getPath())) {
                String path = uri.getPath().replaceAll("/+$", "");
                urls.add(uri.getScheme().toLowerCase() + "://" + uri.getRawAuthority() + (path.isBlank() ? "/" : path)
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
            }
        } catch (IllegalArgumentException ignored) {
            // Invalid request URLs are ignored and remain blocked by the runner.
        }
    }

    private void addOrigin(java.util.Set<String> origins, String value, boolean strictOrigin) {
        if (value == null || value.isBlank()) return;
        try {
            URI uri = URI.create(value.strip());
            if (("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null && uri.getUserInfo() == null
                && (!strictOrigin || (uri.getQuery() == null && uri.getFragment() == null && (uri.getPath() == null || uri.getPath().isBlank() || "/".equals(uri.getPath()))))) {
                origins.add(uri.getScheme().toLowerCase() + "://" + uri.getRawAuthority());
            }
        } catch (IllegalArgumentException ignored) {
            // Invalid configured origins are ignored; callers must opt in explicitly.
        }
    }

    private String baseUrlWithoutFragment(java.net.URI uri) {
        try {
            return new java.net.URI(
                uri.getScheme(), uri.getRawAuthority(), uri.getRawPath(), uri.getRawQuery(), null
            ).toString();
        } catch (Exception exception) {
            throw new IllegalArgumentException();
        }
    }

    private String extractPlannedStartUrl(JsonNode testCase) {
        for (String field : List.of("startUrl", "pageUrl", "url", "path")) {
            String value = testCase.path(field).asText("").strip();
            if (isExecutableUrlTarget(value)) return value;
        }
        return extractPlannedStartUrlFromStep(testCase.path("steps"));
    }

    private String extractPlannedStartUrlFromStep(JsonNode step) {
        if (step == null || step.isMissingNode() || step.isNull()) return "";
        if (step.isArray()) {
            for (JsonNode item : step) {
                String value = extractPlannedStartUrlFromStep(item);
                if (!value.isBlank()) return value;
            }
            return "";
        }
        if (step.isObject()) {
            String action = firstText(step, "action", "type", "name");
            if (isUrlNavigationAction(action)) {
                String target = firstText(step, "startUrl", "pageUrl", "url", "path", "target", "value");
                if (isExecutableUrlTarget(target)) return target;
            }
            for (String field : List.of("step", "text", "description", "dsl")) {
                String value = extractPlannedStartUrlFromStep(step.path(field));
                if (!value.isBlank()) return value;
            }
            return "";
        }
        for (String rawLine : step.asText("").split("\\R")) {
            String line = rawLine.strip();
            for (String action : List.of("goto", "waitForUrl")) {
                String prefix = action + " ";
                if (!line.startsWith(prefix)) continue;
                String target = line.substring(prefix.length()).strip();
                if (isExecutableUrlTarget(target)) return target;
            }
        }
        return "";
    }

    private boolean isUrlNavigationAction(String action) {
        return List.of("goto", "waitForUrl").contains(blankToDefault(action, "").strip());
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = textValue(node.path(field));
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private String textValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        if (node.isArray()) {
            List<String> items = new ArrayList<>();
            for (JsonNode item : node) {
                String value = item.asText("").strip();
                if (!value.isBlank()) items.add(value);
            }
            return String.join("\n", items);
        }
        return node.asText("").strip();
    }

    private JsonNode caseArray(JsonNode output) {
        return findCaseArray(output, 0);
    }

    private boolean isExecutableUrlTarget(String value) {
        if (value == null || value.isBlank()) return false;
        String target = value.strip();
        return target.startsWith("/")
            || target.startsWith("#/")
            || target.startsWith("data:")
            || target.matches("(?i)^https?://.+");
    }

    private WebAutomationLocatorDiscoveryService.Login toBrowserLogin(WebLocatorDiscoveryLogin login) {
        if (login == null || !login.enabled()) return null;
        return new WebAutomationLocatorDiscoveryService.Login(
            true, login.url(), login.usernameLocator(), login.passwordLocator(), login.submitLocator(),
            login.username(), login.password(), login.successType(), login.successTarget()
        );
    }

    private ValidatedGeneratedOutput validateGeneratedOutput(String content) {
        return validateGeneratedOutput(content, List.of(), null);
    }

    private ValidatedGeneratedOutput validateGeneratedOutput(
        String content,
        List<Map<String, Object>> sourcePoints,
        WebLocatorDiscoveryContext locatorDiscovery
    ) {
        return validateGeneratedOutput(content, sourcePoints, locatorDiscovery, Map.of());
    }

    private ValidatedGeneratedOutput validateGeneratedOutput(
        String content,
        List<Map<String, Object>> sourcePoints,
        WebLocatorDiscoveryContext locatorDiscovery,
        Map<String, NaturalPlanSummary> naturalPlans
    ) {
        JsonNode output;
        try {
            output = objectMapper.readTree(content);
        } catch (Exception exception) {
            return new ValidatedGeneratedOutput(
                objectMapper.createObjectNode(),
                List.of("输出不是合法 JSON: " + exception.getMessage())
            );
        }
        if (output == null || !output.isObject()) {
            return new ValidatedGeneratedOutput(
                objectMapper.createObjectNode(),
                List.of("输出根节点必须是 JSON 对象")
            );
        }

        JsonNode cases = caseArray(output);
        if (!cases.isArray()) {
            return new ValidatedGeneratedOutput(output, List.of("输出字段 cases 必须是数组"));
        }

        hydrateRecoverableWebAutomationFields(cases, sourcePoints, locatorDiscovery);
        hydrateNaturalPlanFields(cases, naturalPlans);
        List<String> errors = new ArrayList<>();
        for (int index = 0; index < cases.size(); index++) {
            JsonNode item = cases.get(index);
            if (!(item instanceof ObjectNode objectCase)) {
                errors.add("用例 " + (index + 1) + " 必须是 JSON 对象");
                continue;
            }
            WebAutomationValidationResult result = webAutomationCaseValidator.validateAndNormalize(objectCase, index + 1);
            errors.addAll(result.errors());
            ((ArrayNode) cases).set(index, result.normalizedCase());
        }
        return new ValidatedGeneratedOutput(output, errors);
    }

    private void hydrateRecoverableWebAutomationFields(
        JsonNode cases,
        List<Map<String, Object>> sourcePoints,
        WebLocatorDiscoveryContext locatorDiscovery
    ) {
        if (!cases.isArray()) return;
        Map<String, Map<String, Object>> sourceByPointId = sourcePoints == null
            ? Map.of()
            : sourcePoints.stream().collect(java.util.stream.Collectors.toMap(
                row -> (String) row.get("POINT_ID"),
                row -> row,
                (left, right) -> left
            ));
        for (JsonNode item : cases) {
            if (!(item instanceof ObjectNode testCase)) continue;
            String sourcePointId = testCase.path("sourcePointId").asText("").strip();
            Map<String, Object> source = sourceByPointId.get(sourcePointId);
            putIfBlank(testCase, "caseKind", source == null ? "web_automation" : (String) source.get("CASE_KIND"));
            if (!"web_automation".equals(testCase.path("caseKind").asText(""))) continue;

            putIfBlank(testCase, "title", source == null ? "" : (String) source.get("TITLE"));
            putIfBlank(testCase, "module", source == null ? "" : (String) source.get("MODULE_NAME"));
            putIfBlank(testCase, "pageName", source == null ? "" : (String) source.get("PAGE_OR_FEATURE"));
            putIfBlank(testCase, "precondition", source == null ? "" : (String) source.get("PRECONDITION_TEXT"));
            putIfBlank(testCase, "objective", source == null ? "" : (String) source.get("TEST_OBJECTIVE"));
            putIfBlank(testCase, "naturalSteps", source == null ? "" : (String) source.get("SUGGESTED_STEPS"));
            putIfBlank(testCase, "naturalExpectedResult", source == null ? "" : (String) source.get("EXPECTED_RESULT"));
            putIfBlank(testCase, "expectedResult", source == null ? "" : (String) source.get("EXPECTED_RESULT"));
            putIfBlank(testCase, "sourceFile", source == null ? "" : (String) source.get("SOURCE_FILE"));
            putIfBlank(testCase, "sourceText", source == null ? "" : (String) source.get("SOURCE_TEXT"));
            putIfBlank(testCase, "browser", locatorDiscovery == null ? "" : locatorDiscovery.browser());
            putIfBlank(testCase, "viewport", locatorDiscovery == null ? "" : locatorDiscovery.viewport());
            putIfBlank(testCase, "startUrl", extractPlannedStartUrl(testCase));
            putIfBlank(testCase, "assertions", extractAssertionLines(testCase.path("steps").asText("")));
        }
    }

    private void hydrateNaturalPlanFields(JsonNode cases, Map<String, NaturalPlanSummary> naturalPlans) {
        if (!cases.isArray() || naturalPlans == null || naturalPlans.isEmpty()) return;
        for (JsonNode item : cases) {
            if (!(item instanceof ObjectNode testCase)) continue;
            String sourcePointId = testCase.path("sourcePointId").asText("").strip();
            NaturalPlanSummary plan = naturalPlans.get(sourcePointId);
            if (plan == null) continue;
            String naturalSteps = String.join("\n", plan.naturalSteps()).strip();
            if (!naturalSteps.isBlank()) testCase.put("naturalSteps", naturalSteps);
            String naturalExpectedResult = plan.naturalExpectedResult().strip();
            if (!naturalExpectedResult.isBlank()) testCase.put("naturalExpectedResult", naturalExpectedResult);
        }
    }

    private void putIfBlankOrDsl(ObjectNode node, String field, String value) {
        String current = textValue(node.path(field));
        if (!current.isBlank() && !isDslOnlyText(current)) return;
        String normalized = blankToDefault(value, "").strip();
        if (normalized.isBlank()) return;
        node.put(field, normalized);
    }

    private void putIfBlank(ObjectNode node, String field, String value) {
        if (!node.path(field).asText("").isBlank()) return;
        String normalized = blankToDefault(value, "").strip();
        if (!normalized.isBlank()) {
            node.put(field, normalized);
        }
    }

    private String extractAssertionLines(String steps) {
        List<String> assertions = new ArrayList<>();
        for (String rawLine : steps.split("\\R")) {
            String line = rawLine.strip();
            if (line.startsWith("assertVisible ") || line.startsWith("assertHidden ")
                || line.startsWith("assertText ") || line.startsWith("assertUrl ")
                || line.startsWith("assertEnabled ") || line.startsWith("assertDisabled ")) {
                assertions.add(line);
            }
        }
        return String.join("\n", assertions);
    }

    private Map<String, NaturalPlanSummary> parseNaturalPlanSummaries(JsonNode planningOutput) {
        JsonNode cases = caseArray(planningOutput);
        if (!cases.isArray()) return Map.of();
        Map<String, NaturalPlanSummary> plans = new LinkedHashMap<>();
        for (JsonNode item : cases) {
            String sourcePointId = item.path("sourcePointId").asText("").strip();
            if (sourcePointId.isBlank()) continue;
            plans.put(sourcePointId, new NaturalPlanSummary(
                naturalStepList(item.path("naturalSteps")),
                firstText(item, "naturalExpectedResult", "expectedResult"),
                firstText(item, "objective", "testObjective")
            ));
        }
        return plans;
    }

    private ArrayNode naturalPlanArray(Map<String, NaturalPlanSummary> plans) {
        ArrayNode array = objectMapper.createArrayNode();
        for (Map.Entry<String, NaturalPlanSummary> entry : plans.entrySet()) {
            ObjectNode item = array.addObject();
            item.put("sourcePointId", entry.getKey());
            item.put("naturalExpectedResult", entry.getValue().naturalExpectedResult());
            item.put("objective", entry.getValue().objective());
            item.set("naturalSteps", objectMapper.valueToTree(entry.getValue().naturalSteps()));
        }
        return array;
    }

    private List<String> splitNaturalSteps(String value) {
        if (value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split("\\R")).map(String::strip).filter(line -> !line.isBlank()).toList();
    }

    private List<String> naturalStepList(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return List.of();
        if (node.isArray()) {
            List<String> steps = new ArrayList<>();
            for (JsonNode item : node) {
                String value = item.asText("").strip();
                if (!value.isBlank()) steps.add(value);
            }
            return List.copyOf(steps);
        }
        return splitNaturalSteps(node.asText(""));
    }

    private List<GeneratedCaseResponse> parseGeneratedCases(JsonNode output, List<Map<String, Object>> sourcePoints) {
        JsonNode cases = caseArray(output);
        if (!cases.isArray()) {
            throw new IllegalArgumentException("Invalid AI output JSON: cases must be an array");
        }
        Map<String, Map<String, Object>> sourceByPointId = sourcePoints.stream()
            .collect(java.util.stream.Collectors.toMap(row -> (String) row.get("POINT_ID"), row -> row, (left, right) -> left));
        List<GeneratedCaseResponse> items = new ArrayList<>();
        for (JsonNode item : cases) {
            String sourcePointId = item.path("sourcePointId").asText("");
            Map<String, Object> source = sourceByPointId.get(sourcePointId);
            items.add(new GeneratedCaseResponse(
                sourcePointId,
                item.path("title").asText(source == null ? "AI生成用例" : (String) source.get("TITLE")),
                item.path("module").asText(source == null ? "" : (String) source.get("MODULE_NAME")),
                item.path("caseKind").asText(source == null ? "web_automation" : (String) source.get("CASE_KIND")),
                item.path("pageName").asText(source == null ? "" : (String) source.get("PAGE_OR_FEATURE")),
                item.path("startUrl").asText(""),
                item.path("browser").asText(""),
                item.path("viewport").asText(""),
                item.path("precondition").asText(source == null ? "" : (String) source.get("PRECONDITION_TEXT")),
                item.path("objective").asText(source == null ? "" : (String) source.get("TEST_OBJECTIVE")),
                item.path("steps").asText(source == null ? "" : (String) source.get("SUGGESTED_STEPS")),
                item.path("assertions").asText(""),
                item.path("expectedResult").asText(source == null ? "" : (String) source.get("EXPECTED_RESULT")),
                textValue(item.path("naturalSteps")),
                textValue(item.path("naturalExpectedResult")),
                source == null ? item.path("sourceFile").asText("") : (String) source.get("SOURCE_FILE"),
                source == null ? item.path("sourceText").asText("") : (String) source.get("SOURCE_TEXT"),
                item.path("pageFingerprint").asText(""),
                item.path("pageKey").asText(""),
                List.of(),
                List.of(),
                item.path("actions").isArray() ? item.path("actions") : null,
                item.path("environmentId").asText(""), item.path("baseUrl").asText(""), item.path("authenticationMode").asText("")
            ));
        }
        return items;
    }

    private Long projectId(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return null;
        return ((Number) rows.get(0).get("PROJECT_ID")).longValue();
    }

    private void insertCallRecord(
        Long projectId,
        ResolvedAiGatewaySettings settings,
        AiGenerationResult result,
        long durationMs,
        JsonNode inputSummary,
        String errorMessage
    ) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
            insert into ai_call_records(
              record_id, run_id, project_id, provider_type, model_name, config_snapshot_id, agent_id,
              skill_ids_json, rule_version, modality, request_id, status, error_code, error_message,
              duration_ms, token_usage_input, token_usage_output, estimated_cost, input_summary_json,
              output_summary_json, raw_payload_json, actor_id, actor_name, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "call-" + UUID.randomUUID(),
            null,
            projectId,
            settings.providerType(),
            settings.model(),
            "default",
            "formal-case-generation",
            "[\"formal-web-case-generation\"]",
            inputSummary.path("effectiveRules").path("effectiveVersion").asText("system-v1"),
            "text",
            result == null ? "" : result.requestId(),
            result != null && result.success() ? "succeeded" : "failed",
            "",
            blankToDefault(errorMessage, ""),
            durationMs,
            result == null ? 0 : result.inputTokens(),
            result == null ? 0 : result.outputTokens(),
            0,
            AiCallRecordPayloads.compactJson(objectMapper, inputSummary),
            result == null ? null : stringify(objectMapper.createObjectNode().put("contentLength", result.content().length())),
            result == null ? null : AiCallRecordPayloads.compactText(objectMapper, result.content()),
            com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystem(),
            com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystemName(),
            Timestamp.from(now),
            Timestamp.from(now)
        );
    }

    private AiDraftTestPointResponse toResponse(Map<String, Object> row) {
        return new AiDraftTestPointResponse(
            (String) row.get("POINT_ID"),
            (String) row.get("EXTERNAL_RUN_ID"),
            ((Number) row.get("PROJECT_ID")).longValue(),
            (String) row.get("SOURCE_DOCUMENT_EXTERNAL_ID"),
            (String) row.get("CHUNK_ID"),
            (String) row.get("TITLE"),
            (String) row.get("MODULE_NAME"),
            (String) row.get("PAGE_OR_FEATURE"),
            (String) row.get("CASE_KIND"),
            (String) row.get("TEST_TYPE"),
            (String) row.get("PRIORITY_LEVEL"),
            (String) row.get("TEST_OBJECTIVE"),
            (String) row.get("SUGGESTED_STEPS"),
            (String) row.get("EXPECTED_RESULT"),
            (String) row.get("SOURCE_FILE"),
            (String) row.get("SOURCE_TEXT"),
            ((Number) row.get("CONFIDENCE_SCORE")).doubleValue(),
            (Boolean) row.get("INFERRED"),
            (String) row.get("REVIEW_STATUS"),
            (String) row.get("RESULT_ORIGIN"),
            (String) row.get("ACCEPTANCE_STATE"),
            revisionOf(row),
            (String) row.get("PARENT_ARTIFACT_ID"),
            (String) row.get("MODEL_PROFILE_VERSION"),
            parse((String) row.get("FIELD_ORIGINS_JSON")),
            parse((String) row.get("METADATA_JSON")),
            toIso(row.get("CREATED_AT")),
            toIso(row.get("UPDATED_AT"))
        );
    }

    /** 终态（ACCEPTED）状态集：重复确认在此幂等返回，不再写审计。 */
    private static final java.util.Set<String> ACCEPTED_STATUSES = java.util.Set.of("confirmed", "modified");

    /** 全链路人工确认契约：必须来自真实认证操作人（匿名/系统态一律拒绝）。 */
    private String currentHumanActor() {
        return com.haitlify.aitest.platform.ai.security.AiActorResolver.requireHumanActor();
    }

    /** 不存在与无权同形 404：猜测 id 无法区分对象存在（T14 契约）。 */
    private Map<String, Object> findPointOrNotFound(String pointId) {
        try {
            return findPoint(pointId);
        } catch (IllegalArgumentException notFound) {
            throw com.haitlify.aitest.platform.ai.security.AiInvocationAuthorization.notFound();
        }
    }

    /** 项目访问闸：真实 bean 下拒绝与不存在同形 404；兼容构造（null）跳过，集成负例覆盖真实路径。 */
    private void requirePointAccess(Map<String, Object> row) {
        if (projectAccessService == null) {
            return;
        }
        try {
            projectAccessService.requireAccess(((Number) row.get("PROJECT_ID")).longValue());
        } catch (org.springframework.web.server.ResponseStatusException denied) {
            throw com.haitlify.aitest.platform.ai.security.AiInvocationAuthorization.notFound();
        }
    }

    /** expectedRevision：提供且过期 ⇒ 409（省略保持兼容，与全平台契约一致）。 */
    private void guardRevision(Map<String, Object> row, Integer expectedRevision) {
        if (expectedRevision == null) {
            return;
        }
        int current = revisionOf(row);
        if (expectedRevision != current) {
            throw new com.haitlify.aitest.platform.ai.agentic.StaleArtifactRevisionException(
                "point revision is stale: expected " + expectedRevision + " but current is " + current);
        }
    }

    private int revisionOf(Map<String, Object> row) {
        Object value = row.get("REVISION");
        return value == null ? 1 : ((Number) value).intValue();
    }

    private IllegalArgumentException stateConflict(String action, String status) {
        return new IllegalArgumentException("review_state_conflict: " + action + " 不允许，当前状态 " + status);
    }

    private Map<String, Object> findPoint(String pointId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select p.*, r.run_id as external_run_id, c.chunk_id, d.document_id as source_document_external_id
            from ai_draft_test_points p
            join ai_analysis_runs r on r.id = p.run_id
            left join ai_source_chunks c on c.id = p.source_chunk_id
            left join ai_source_documents d on d.id = p.source_document_id
            where p.point_id = ?
            """, pointId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("当前批次测试点不存在或已被删除：" + pointId);
        }
        return rows.get(0);
    }

    private void appendReviewAction(
        Map<String, Object> pointRow,
        String actionType,
        String actor,
        Map<String, Object> before,
        Map<String, Object> after,
        String comment
    ) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
            insert into ai_review_actions(
              action_id, point_id, action_type, before_value_json, after_value_json, reason_comment,
              actor_id, actor_name, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "review-" + UUID.randomUUID(),
            ((Number) pointRow.get("ID")).longValue(),
            actionType,
            stringify(toReviewSnapshot(before)),
            stringify(toReviewSnapshot(after)),
            comment,
            actor,
            com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystemName(),
            Timestamp.from(now),
            Timestamp.from(now)
        );
    }

    private JsonNode toReviewSnapshot(Map<String, Object> row) {
        return objectMapper.createObjectNode()
            .put("pointId", (String) row.get("POINT_ID"))
            .put("title", (String) row.get("TITLE"))
            .put("objective", (String) row.get("TEST_OBJECTIVE"))
            .put("steps", (String) row.get("SUGGESTED_STEPS"))
            .put("expectedResult", (String) row.get("EXPECTED_RESULT"))
            .put("reviewStatus", (String) row.get("REVIEW_STATUS"));
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            return objectMapper.createObjectNode();
        }
    }

    private String stringify(JsonNode node) {
        if (node == null) return null;
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid AI draft point payload", exception);
        }
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String toIso(Object value) {
        if (value == null) return null;
        if (value instanceof Timestamp timestamp) return timestamp.toInstant().toString();
        if (value instanceof Instant instant) return instant.toString();
        return String.valueOf(value);
    }

    private WebExplorationFlags loadWebExplorationFlags() {
        JsonNode config = readDefaultAiConfig();
        Map<String, Boolean> standaloneFlags = readStandaloneFlags();
        return new WebExplorationFlags(
            readFlag(config, standaloneFlags, MULTI_ROUND_EXPLORATION_FLAG, true),
            readFlag(config, standaloneFlags, EVIDENCE_SESSION_REUSE_FLAG, true),
            readFlag(config, standaloneFlags, CRITICALITY_NETWORK_POLICY_FLAG, true),
            readFlag(config, standaloneFlags, "webExplorationSemanticExpansionEnabled", false),
            readDeterministicCompilationFlag(config, standaloneFlags)
        );
    }

    private JsonNode readDefaultAiConfig() {
        try {
            String configJson = jdbcTemplate.queryForObject(
                "select provider_config_json from ai_system_configs where config_key = 'default'",
                String.class
            );
            if (configJson == null || configJson.isBlank()) return null;
            return objectMapper.readTree(configJson);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Map<String, Boolean> readStandaloneFlags() {
        Map<String, Boolean> flags = new HashMap<>();
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select config_key, enabled from ai_system_configs where config_key in (?, ?, ?, ?, ?, ?)",
                MULTI_ROUND_EXPLORATION_FLAG,
                EVIDENCE_SESSION_REUSE_FLAG,
                CRITICALITY_NETWORK_POLICY_FLAG,
                "webExplorationSemanticExpansionEnabled",
                DETERMINISTIC_COMPILATION_FLAG,
                "webDeterministicCaseCompilationEnabled"
            );
            for (Map<String, Object> row : rows) {
                String key = row.get("config_key") == null ? "" : String.valueOf(row.get("config_key"));
                Object enabled = row.get("enabled");
                if (!key.isBlank() && enabled instanceof Boolean bool) {
                    flags.put(key, bool);
                }
            }
        } catch (Exception ignored) {
            // Missing compatibility rows must not override safe defaults.
        }
        return flags;
    }

    private boolean readFlag(JsonNode config, Map<String, Boolean> standaloneFlags, String key, boolean defaultValue) {
        if (config != null && config.has(key)) {
            return config.path(key).asBoolean(defaultValue);
        }
        return standaloneFlags.getOrDefault(key, defaultValue);
    }

    private boolean readDeterministicCompilationFlag(JsonNode config, Map<String, Boolean> standaloneFlags) {
        if (config != null && config.has("webDeterministicCaseCompilationEnabled")) {
            return config.path("webDeterministicCaseCompilationEnabled").asBoolean(true);
        }
        if (config != null && config.has(DETERMINISTIC_COMPILATION_FLAG)) {
            return config.path(DETERMINISTIC_COMPILATION_FLAG).asBoolean(true);
        }
        if (standaloneFlags.containsKey("webDeterministicCaseCompilationEnabled")) {
            return standaloneFlags.get("webDeterministicCaseCompilationEnabled");
        }
        if (standaloneFlags.containsKey(DETERMINISTIC_COMPILATION_FLAG)) {
            return standaloneFlags.get(DETERMINISTIC_COMPILATION_FLAG);
        }
        return true;
    }

    private record WebExplorationFlags(
        boolean multiRoundExplorationEnabled,
        boolean evidenceSessionReuseEnabled,
        boolean criticalityAwareNetworkPolicyEnabled,
        boolean semanticExpansionEnabled,
        boolean deterministicCaseCompilationEnabled
    ) {
    }

    private record DiscoveryTarget(
        String pointId,
        String plannedStartUrl,
        String moduleName,
        String pageName,
        WebAutomationLocatorDiscoveryService.TargetIntent targetIntent,
        WebExplorationSemanticExpansionService.RepairSuggestion repairSuggestion,
        List<LocatorKnowledgeService.LocatorKnowledgeCandidate> historicalCandidates,
        JsonNode actions
    ) {
        private DiscoveryTarget {
            historicalCandidates = historicalCandidates == null ? List.of() : List.copyOf(historicalCandidates);
        }
    }
}

record AiDraftTestPointListResponse(
    List<AiDraftTestPointResponse> items
) { }

record AiDraftTestPointResponse(
    String pointId,
    String runId,
    Long projectId,
    String sourceDocumentId,
    String sourceChunkId,
    String title,
    String module,
    String pageOrFeature,
    String caseKind,
    String testType,
    String priority,
    String objective,
    String steps,
    String expectedResult,
    String sourceFile,
    String sourceText,
    double confidenceScore,
    boolean inferred,
    String reviewStatus,
    String resultOrigin,
    String acceptanceState,
    Integer revision,
    String parentPointId,
    String modelProfileVersion,
    JsonNode fieldOrigins,
    JsonNode metadata,
    String createdAt,
    String updatedAt
) {
}

record ReviewDraftPointRequest(
    String comment,
    Integer revision
) {
    ReviewDraftPointRequest(String comment) {
        this(comment, null);
    }
}

record EditDraftPointRequest(
    String title,
    String objective,
    String steps,
    String expectedResult,
    String comment,
    Integer revision
) {
    EditDraftPointRequest(String title, String objective, String steps, String expectedResult, String comment) {
        this(title, objective, steps, expectedResult, comment, null);
    }
}

record GenerateCasesRequest(
    List<String> pointIds,
    WebLocatorDiscoveryContext locatorDiscovery
) {
}

/**
 * 采集请求能携带的值来源只有内联字面量：runtimevariable/maskedsecret 在采集期没有来源通道，
 * 返回第一个不可用动作的描述（供阻塞原因指名），全部可用时返回空串。
 */
final class DiscoveryValueSources {
    private DiscoveryValueSources() {}

    static String unsupportedDiscoveryValueSource(JsonNode actions) {
        if (actions == null || !actions.isArray()) return "";
        for (JsonNode action : actions) {
            JsonNode value = action.path("value");
            if (!value.isObject()) continue;
            String kind = value.path("kind").asText("").toLowerCase(java.util.Locale.ROOT);
            if (!kind.equals("runtimevariable") && !kind.equals("runtime_variable") && !kind.equals("maskedsecret")) continue;
            String key = value.path("key").asText(value.path("value").asText(""));
            String actionId = action.path("actionId").asText(action.path("id").asText("(未命名动作)"));
            return actionId + " 需要 " + kind + " 值来源（" + key + "），而浏览器证据采集请求不携带变量";
        }
        return "";
    }
}

record WebLocatorDiscoveryContext(
    String environmentId,
    String baseUrl,
    String browser,
    String viewport,
    Boolean headless,
    Integer timeoutMs,
    String waitUntil,
    String authToken,
    Map<String, String> extraHeaders,
    List<String> cookieOrigins,
    List<String> allowedRequestOrigins,
    String sessionId,
    Boolean reuseSession,
    WebLocatorDiscoveryLogin login,
    String diagnosticMode,
    Boolean tracingEnabled,
    String networkPolicyMode
) {
    WebLocatorDiscoveryContext(
        String environmentId, String baseUrl, String browser, String viewport, Boolean headless, Integer timeoutMs,
        String waitUntil, String authToken, Map<String, String> extraHeaders, List<String> cookieOrigins,
        List<String> allowedRequestOrigins, String sessionId, Boolean reuseSession, WebLocatorDiscoveryLogin login
    ) {
        this(environmentId, baseUrl, browser, viewport, headless, timeoutMs, waitUntil, authToken, extraHeaders,
            cookieOrigins, allowedRequestOrigins, sessionId, reuseSession, login, null, null, null);
    }

    WebLocatorDiscoveryContext(
        String environmentId,
        String baseUrl,
        String browser,
        String viewport,
        Boolean headless,
        Integer timeoutMs,
        String waitUntil,
        String authToken,
        Map<String, String> extraHeaders,
        List<String> cookieOrigins,
        WebLocatorDiscoveryLogin login
    ) {
        this(environmentId, baseUrl, browser, viewport, headless, timeoutMs, waitUntil, authToken,
            extraHeaders, cookieOrigins, null, null, null, login, null, null, null);
    }
}

record WebLocatorDiscoveryLogin(
    boolean enabled,
    String url,
    String usernameLocator,
    String passwordLocator,
    String submitLocator,
    String username,
    String password,
    String successType,
    String successTarget
) {
}

record GeneratedCaseListResponse(
    List<GeneratedCaseResponse> items
) {
}

/** Natural-language plan output plus the provider request id that proves it came from a real call. */
record WebCasePlanOutput(
    JsonNode output,
    String providerRequestId
) {
    WebCasePlanOutput {
        providerRequestId = providerRequestId == null ? "" : providerRequestId;
    }
}

record GeneratedCaseResponse(
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
    String sourceText,
    String pageFingerprint,
    String pageKey,
    List<AgentWebCaseGenerationFacade.StepTrace> stepTraces,
    List<String> expectedResultEvidenceIds,
    JsonNode actions,
    String environmentId,
    String baseUrl,
    String authenticationMode
) {
    public GeneratedCaseResponse(
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
        String sourceText
    ) {
        this(sourcePointId, title, module, caseKind, pageName, startUrl, browser, viewport, precondition, objective,
            steps, assertions, expectedResult, naturalSteps, naturalExpectedResult, sourceFile, sourceText, "", "", List.of(), List.of(), null, "", "", "");
    }

    public GeneratedCaseResponse(
        String sourcePointId, String title, String module, String caseKind, String pageName, String startUrl,
        String browser, String viewport, String precondition, String objective, String steps, String assertions,
        String expectedResult, String naturalSteps, String naturalExpectedResult, String sourceFile, String sourceText,
        String pageFingerprint
    ) {
        this(sourcePointId, title, module, caseKind, pageName, startUrl, browser, viewport, precondition, objective,
            steps, assertions, expectedResult, naturalSteps, naturalExpectedResult, sourceFile, sourceText,
            pageFingerprint, "", List.of(), List.of(), null, "", "", "");
    }
}

record NaturalPlanSummary(
    List<String> naturalSteps,
    String naturalExpectedResult,
    String objective
) {
    public NaturalPlanSummary {
        naturalSteps = naturalSteps == null ? List.of() : List.copyOf(naturalSteps);
        naturalExpectedResult = naturalExpectedResult == null ? "" : naturalExpectedResult;
        objective = objective == null ? "" : objective;
    }
}

record ValidatedGeneratedOutput(
    JsonNode output,
    List<String> errors
) {
}
