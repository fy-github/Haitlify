package com.haitlify.aitest.platform.ai.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.JsonNode;
import com.haitlify.aitest.platform.ai.agentic.locator.LocatorKnowledgeService;
import com.haitlify.aitest.platform.ai.gateway.ResolvedAiGatewaySettings;
import com.haitlify.aitest.platform.ai.gateway.AiGateway;
import com.haitlify.aitest.platform.ai.gateway.AiGatewaySettingsService;
import com.haitlify.aitest.platform.ai.rule.AiRuleService;
import com.haitlify.aitest.platform.ai.skill.AiSkillPromptContextService;
import com.haitlify.aitest.platform.webautomation.WebAutomationLocatorDiscoveryService;
import com.haitlify.aitest.platform.webautomation.WebAutomationLocatorDiscoveryService.ExplorationReport;
import com.haitlify.aitest.platform.webautomation.WebAutomationLocatorDiscoveryService.ExplorationScores;
import com.haitlify.aitest.platform.webautomation.WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class AiDraftTestPointServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate = mock(org.springframework.jdbc.core.JdbcTemplate.class);
    private final AiGateway aiGateway = mock(AiGateway.class);
    private final AiGatewaySettingsService aiGatewaySettingsService = mock(AiGatewaySettingsService.class);
    /**
     * facade 以「直通 mock gateway」方式注入：生产服务经 facade 调用，测试的既有
     * gateway stub（any config, any prompt）逐字保留生效，零行为差异。
     */
    private final com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade taskFacade =
        mock(com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade.class);
    {
        when(taskFacade.generate(any())).thenAnswer(invocation -> {
            var task = (com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade.TaskExecution) invocation.getArgument(0);
            return aiGateway.structuredGenerate(
                new com.haitlify.aitest.platform.ai.gateway.AiGatewayConfig(
                    "test-provider", "http://test.local", "test-model", "test-key", 30_000),
                task.prompt());
        });
    }
    private final AiRuleService aiRuleService = mock(AiRuleService.class);
    private final AiSkillPromptContextService aiSkillPromptContextService = mock(AiSkillPromptContextService.class);
    private final WebAutomationCaseValidator webAutomationCaseValidator = mock(WebAutomationCaseValidator.class);
    private final WebAutomationLocatorDiscoveryService webAutomationLocatorDiscoveryService = mock(WebAutomationLocatorDiscoveryService.class);
    private final LocatorKnowledgeService locatorKnowledgeService = mock(LocatorKnowledgeService.class);
    private final WebExplorationSemanticExpansionService webExplorationSemanticExpansionService = mock(WebExplorationSemanticExpansionService.class);
    private final WebCaseCompilationService webCaseCompilationService = mock(WebCaseCompilationService.class);
    private final AiDraftTestPointService service = new AiDraftTestPointService(
        jdbcTemplate,
        objectMapper,
        taskFacade,
        aiGatewaySettingsService,
        aiRuleService,
        aiSkillPromptContextService,
        webAutomationCaseValidator,
        webAutomationLocatorDiscoveryService,
        locatorKnowledgeService,
        webExplorationSemanticExpansionService,
        webCaseCompilationService
    );

    @Test
    void buildNaturalPlanBatchAcceptsWrappedAndBareArrayInputs() {
        var wrapped = objectMapper.createObjectNode().set("items", objectMapper.createArrayNode()
            .add(naturalPlan("point-1"))
            .add(naturalPlan("point-2")));
        var bare = objectMapper.createArrayNode()
            .add(naturalPlan("point-1"))
            .add(naturalPlan("point-2"));

        var wrappedBatch = service.buildNaturalPlanBatch(wrapped, List.of("point-1", "point-2"));
        var bareBatch = service.buildNaturalPlanBatch(bare, List.of("point-1", "point-2"));

        assertThat(wrappedBatch.plans()).extracting(AgentWebCaseGenerationFacade.NaturalWebCasePlan::sourcePointId)
            .containsExactly("point-1", "point-2");
        assertThat(bareBatch.plans()).extracting(AgentWebCaseGenerationFacade.NaturalWebCasePlan::sourcePointId)
            .containsExactly("point-1", "point-2");
    }

    @Test
    void normalizesCommonProviderCaseEnvelopesBeforeCoverageValidation() throws Exception {
        JsonNode nested = objectMapper.readTree("{\"data\":{\"testCases\":\"[{\\\"sourcePointId\\\":\\\"point-1\\\",\\\"title\\\":\\\"用户列表\\\",\\\"naturalSteps\\\":[\\\"进入页面\\\"],\\\"naturalExpectedResult\\\":\\\"列表可见\\\"}]\"}}");
        JsonNode fenced = objectMapper.readTree("{\"items\":[{\"sourcePointId\":\"point-1\",\"title\":\"用户列表\",\"naturalSteps\":[\"进入页面\"],\"naturalExpectedResult\":\"列表可见\"}]}");

        JsonNode normalizedNested = service.normalizeCaseEnvelope(nested);
        JsonNode normalizedFenced = service.normalizeCaseEnvelope(fenced);

        assertThat(normalizedNested.path("cases").isArray()).isTrue();
        assertThat(normalizedNested.path("cases")).hasSize(1);
        assertThat(normalizedNested.path("cases").get(0).path("sourcePointId").asText()).isEqualTo("point-1");
        assertThat(normalizedFenced.path("cases").isArray()).isTrue();
        assertThat(normalizedFenced.path("cases")).hasSize(1);
    }

    @Test
    void buildNaturalPlanBatchRejectsPartialDuplicateOrEmptyPlans() {
        assertThatThrownBy(() -> service.buildNaturalPlanBatch(objectMapper.createArrayNode(), List.of("point-1")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("自然语言计划不能为空");

        assertThatThrownBy(() -> service.buildNaturalPlanBatch(objectMapper.createArrayNode()
            .add(naturalPlan(""))
            .add(naturalPlan("point-1"))
            .add(naturalPlan("point-1")), List.of("point-1")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("缺少 sourcePointId");

        assertThatThrownBy(() -> service.buildNaturalPlanBatch(objectMapper.createArrayNode()
            .add(naturalPlan("point-1")), List.of("point-1", "point-2")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("当前批次测试点未生成自然语言计划：point-2");
    }

    @Test
    void buildNaturalPlanBatchScopesCompileBatchToRequestedPoints() {
        JsonNode output = objectMapper.createArrayNode()
            .add(naturalPlan("point-1"))
            .add(naturalPlan("point-2"));

        AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch scoped =
            service.buildNaturalPlanBatch(output, List.of("point-1"));

        assertThat(scoped.plans()).hasSize(1);
        assertThat(scoped.plans().get(0).sourcePointId()).isEqualTo("point-1");
    }

    @Test
    void compileWebCasesScopesConfirmedBatchToTheSingleCompiledPoint() throws Exception {
        when(jdbcTemplate.queryForList(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.<Object[]>any()))
            .thenReturn(List.of(
                sourcePoint("point-1", "用户管理列表展示", "用户", "用户管理", "/users", "web_automation", "验证用户管理列表", "req.docx", "用户管理标题可见"),
                sourcePoint("point-2", "设备列表展示", "设备", "设备管理", "/devices", "web_automation", "验证设备列表", "req.docx", "设备标题可见")));
        when(jdbcTemplate.queryForObject(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(String.class)))
            .thenReturn("{\"webCaseCompilationEnabled\":true}");
        when(webCaseCompilationService.compileEvidenceBoundCase(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList()))
            .thenReturn(new WebCaseCompilationService.CompilationResult(
                List.of(new AgentWebCaseGenerationFacade.GeneratedWebCase(
                    "point-1",
                    "用户管理列表展示",
                    "用户",
                    "web_automation",
                    "用户管理",
                    "/users",
                    "chromium",
                    "1440x900",
                    "",
                    "验证用户管理列表",
                    "goto /users",
                    "assertText text=用户管理 = 用户管理标题可见",
                    "用户管理标题可见",
                    "进入用户管理页面",
                    "用户管理标题可见",
                    "req.docx",
                    "fp-1"
                )),
                List.of(),
                List.of()
            ));

        JsonNode planningOutput = objectMapper.createArrayNode()
            .add(naturalPlan("point-1"))
            .add(naturalPlan("point-2"));

        GeneratedCaseListResponse response = service.compileWebCases(
            List.of("point-1"),
            planningOutput,
            objectMapper.createArrayNode(),
            null,
            null,
            null
        );

        assertThat(response.items()).hasSize(1);
        org.mockito.ArgumentCaptor<AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch> batchCaptor =
            org.mockito.ArgumentCaptor.forClass(AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch.class);
        verify(webCaseCompilationService).compileEvidenceBoundCase(batchCaptor.capture(), any(), anyList());
        assertThat(batchCaptor.getValue().plans())
            .extracting(AgentWebCaseGenerationFacade.NaturalWebCasePlan::sourcePointId)
            .containsExactly("point-1");
    }

    @Test
    void compileWebCasesDeterministicModeKeepsPageFingerprintAndSourceScope() throws Exception {
        when(jdbcTemplate.queryForList(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.<Object[]>any()))
            .thenReturn(List.of(sourcePoint("point-1", "用户管理列表展示", "用户", "用户管理", "/users", "web_automation", "验证用户管理列表", "req.docx", "用户管理标题可见")));
        when(jdbcTemplate.queryForObject(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(String.class)))
            .thenReturn("{\"webCaseCompilationEnabled\":true}");
        when(webCaseCompilationService.compileEvidenceBoundCase(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList()))
            .thenReturn(new WebCaseCompilationService.CompilationResult(
                List.of(new AgentWebCaseGenerationFacade.GeneratedWebCase(
                    "point-1",
                    "用户管理列表展示",
                    "用户",
                    "web_automation",
                    "用户管理",
                    "/users",
                    "chromium",
                    "1440x900",
                    "",
                    "验证用户管理列表",
                    "goto /users",
                    "assertText text=用户管理 = 用户管理标题可见",
                    "用户管理标题可见",
                    "进入用户管理页面",
                    "用户管理标题可见",
                    "req.docx",
                    "fp-1"
                )),
                List.of(),
                List.of()
            ));

        JsonNode planningOutput = objectMapper.createArrayNode().add(objectMapper.createObjectNode()
            .put("sourcePointId", "point-1")
            .put("title", "用户管理列表展示")
            .put("module", "用户")
            .put("pageName", "用户管理")
            .put("startUrl", "/users")
            .set("naturalSteps", objectMapper.createArrayNode().add("进入用户管理页面"))
            .put("naturalExpectedResult", "用户管理标题可见")
            .put("objective", "验证用户管理列表"));

        GeneratedCaseListResponse response = service.compileWebCases(
            List.of("point-1"),
            planningOutput,
            objectMapper.createArrayNode(),
            null,
            null,
            null
        );

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).sourcePointId()).isEqualTo("point-1");
        assertThat(response.items().get(0).pageName()).isEqualTo("用户管理");
        assertThat(pageFingerprint(response.items().get(0))).isEqualTo("fp-1");
    }

    @Test
    void compileWebCasesDefaultsToDeterministicCompilationWhenConfigIsMissing() throws Exception {
        when(jdbcTemplate.queryForList(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.<Object[]>any()))
            .thenReturn(List.of(sourcePoint("point-1", "用户管理列表展示", "用户", "用户管理", "/users", "web_automation", "验证用户管理列表", "req.docx", "用户管理标题可见")));
        when(jdbcTemplate.queryForObject(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(String.class)))
            .thenReturn(null);
        when(webCaseCompilationService.compileEvidenceBoundCase(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList()))
            .thenReturn(new WebCaseCompilationService.CompilationResult(
                List.of(new AgentWebCaseGenerationFacade.GeneratedWebCase(
                    "point-1",
                    "用户管理列表展示",
                    "用户",
                    "web_automation",
                    "用户管理",
                    "/users",
                    "chromium",
                    "1440x900",
                    "",
                    "验证用户管理列表",
                    "goto /users",
                    "assertText text=用户管理 = 用户管理标题可见",
                    "用户管理标题可见",
                    "进入用户管理页面",
                    "用户管理标题可见",
                    "req.docx",
                    "fp-1"
                )),
                List.of(),
                List.of()
            ));

        GeneratedCaseListResponse response = service.compileWebCases(
            List.of("point-1"),
            objectMapper.createObjectNode().set("cases", objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                .put("sourcePointId", "point-1")
                .put("title", "用户管理列表展示")
                .put("module", "用户")
                .put("pageName", "用户管理")
                .put("startUrl", "/users")
                .set("naturalSteps", objectMapper.createArrayNode().add("进入用户管理页面"))
                .put("naturalExpectedResult", "用户管理标题可见")
                .put("objective", "验证用户管理列表"))),
            objectMapper.createArrayNode(),
            null,
            null,
            null
        );

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).sourcePointId()).isEqualTo("point-1");
        verify(webCaseCompilationService).compileEvidenceBoundCase(any(), any(), anyList());
    }

    @Test
    void compileWebCasesRejectsWhenDeterministicCompilationFlagIsDisabled() {
        when(jdbcTemplate.queryForList(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.<Object[]>any()))
            .thenReturn(List.of(sourcePoint("point-1", "用户管理列表展示", "用户", "用户管理", "/users", "web_automation", "验证用户管理列表", "req.docx", "用户管理标题可见")));
        when(jdbcTemplate.queryForObject(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(String.class)))
            .thenReturn("{\"webCaseCompilationEnabled\":false}");

        assertThatThrownBy(() -> service.compileWebCases(
            List.of("point-1"),
            objectMapper.createObjectNode().set("cases", objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                .put("sourcePointId", "point-1")
                .put("title", "用户管理列表展示")
                .put("module", "用户")
                .put("pageName", "用户管理")
                .put("startUrl", "/users")
                .set("naturalSteps", objectMapper.createArrayNode().add("进入用户管理页面"))
                .put("naturalExpectedResult", "用户管理标题可见")
                .put("objective", "验证用户管理列表"))),
            objectMapper.createArrayNode(),
            null,
            null,
            null
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("webCaseCompilationEnabled 未启用");

        verify(webCaseCompilationService, never()).compileEvidenceBoundCase(any(), any(), anyList());
    }

    @Test
    void compileWebCasesPrefersTask10DeterministicFlagWhenPresent() {
        when(jdbcTemplate.queryForList(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.<Object[]>any()))
            .thenReturn(List.of(sourcePoint("point-1", "用户管理列表展示", "用户", "用户管理", "/users", "web_automation", "验证用户管理列表", "req.docx", "用户管理标题可见")));
        when(jdbcTemplate.queryForObject(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(String.class)))
            .thenReturn("{\"webDeterministicCaseCompilationEnabled\":false,\"webCaseCompilationEnabled\":true}");

        assertThatThrownBy(() -> service.compileWebCases(
            List.of("point-1"),
            objectMapper.createObjectNode().set("cases", objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                .put("sourcePointId", "point-1")
                .put("title", "用户管理列表展示")
                .put("module", "用户")
                .put("pageName", "用户管理")
                .put("startUrl", "/users")
                .set("naturalSteps", objectMapper.createArrayNode().add("进入用户管理页面"))
                .put("naturalExpectedResult", "用户管理标题可见")
                .put("objective", "验证用户管理列表"))),
            objectMapper.createArrayNode(),
            null,
            null,
            null
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("webCaseCompilationEnabled 未启用");

        verify(webCaseCompilationService, never()).compileEvidenceBoundCase(any(), any(), anyList());
    }

    @Test
    void readStandaloneFlagsIncludesTask10KeyAndHonorsExplicitFalse() throws Exception {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        jdbc.flagRows = List.of(
            standaloneFlag("webCaseCompilationEnabled", true),
            standaloneFlag("webDeterministicCaseCompilationEnabled", false)
        );
        AiDraftTestPointService localService = newService(jdbc);

        Method method = AiDraftTestPointService.class.getDeclaredMethod("readStandaloneFlags");
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Boolean> flags = (Map<String, Boolean>) method.invoke(localService);

        assertThat(flags).containsEntry("webCaseCompilationEnabled", true);
        assertThat(flags).containsEntry("webDeterministicCaseCompilationEnabled", false);
        assertThat(jdbc.lastQuery).contains("select config_key, enabled from ai_system_configs where config_key in (?, ?, ?, ?, ?, ?)");
        assertThat(jdbc.lastArgs).containsExactly(
            "webMultiRoundExplorationEnabled",
            "webEvidenceSessionReuseEnabled",
            "webCriticalityAwareNetworkPolicyEnabled",
            "webExplorationSemanticExpansionEnabled",
            "webCaseCompilationEnabled",
            "webDeterministicCaseCompilationEnabled"
        );
    }

    @Test
    void collectBrowserLocatorEvidenceKeepsRawDiscoveryAndBackfillsPageIdentityFromReadiness() throws Exception {
        String pageUrl = "https://example.test/users";
        ObjectNode readiness = objectMapper.createObjectNode()
            .put("pageKey", pageUrl)
            .put("pageFingerprint", "sha256:readiness");
        ExplorationReport exploration = new ExplorationReport(
            "matched",
            List.of(),
            new ExplorationScores(10, 10, 10, 30, true),
            List.of(),
            "",
            42L,
            objectMapper.createObjectNode(),
            1_000L,
            null,
            null,
            readiness,
            null
        );
        PageLocatorDiscoveryResponse snapshot = new PageLocatorDiscoveryResponse(pageUrl, List.of(), exploration);
        when(webAutomationLocatorDiscoveryService.discover(org.mockito.ArgumentMatchers.any())).thenReturn(snapshot);
        when(locatorKnowledgeService.searchCandidates(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.isNull()))
            .thenReturn(List.of());
        when(webExplorationSemanticExpansionService.expandWithDiagnostics(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean()))
            .thenReturn(new WebExplorationSemanticExpansionService.ExpansionResult(
                new WebAutomationLocatorDiscoveryService.TargetIntent(List.of("用户管理"), List.of("/users"), List.of("用户管理"), List.of("点击"), List.of("可见"), List.of("用户管理"), List.of()),
                null,
                ""
            ));

        ArrayNode planningOutput = objectMapper.createArrayNode().add(objectMapper.createObjectNode()
            .put("sourcePointId", "point-1")
            .put("title", "用户管理列表展示")
            .put("module", "用户")
            .put("pageName", "用户管理")
            .put("startUrl", "/users")
            .set("naturalSteps", objectMapper.createArrayNode().add("进入用户管理页面"))
            .put("naturalExpectedResult", "用户管理标题可见")
            .put("objective", "验证用户管理列表"));
        Map<String, Object> sourcePoint = Map.of(
            "POINT_ID", "point-1",
            "PROJECT_ID", 1L,
            "REVIEW_STATUS", "confirmed",
            "SOURCE_TEXT", "用户管理列表展示",
            "SOURCE_FILE", "req.docx",
            "TITLE", "用户管理列表展示",
            "MODULE_NAME", "用户",
            "PAGE_OR_FEATURE", "用户管理",
            "TEST_OBJECTIVE", "验证用户管理列表",
            "EXPECTED_RESULT", "用户管理标题可见"
        );
        WebLocatorDiscoveryContext context = new WebLocatorDiscoveryContext(
            "env-1",
            "https://example.test/",
            "chromium",
            "1440x900",
            true,
            30_000,
            "domcontentloaded",
            null,
            Map.of(),
            List.of(),
            null,
            null,
            null,
            null
        );

        Method method = AiDraftTestPointService.class.getDeclaredMethod(
            "collectBrowserLocatorEvidence",
            String.class,
            WebLocatorDiscoveryContext.class,
            Long.class,
            List.class,
            List.class,
            long.class
        );
        method.setAccessible(true);
        ArrayNode evidence = (ArrayNode) method.invoke(
            service,
            planningOutput.toString(),
            context,
            1L,
            List.of(sourcePoint),
            List.of(),
            1_000L
        );

        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).path("pageUrl").asText()).isEqualTo(pageUrl);
        assertThat(evidence.get(0).path("pageKey").asText()).isEqualTo(pageUrl);
        assertThat(evidence.get(0).path("pageFingerprint").asText()).isEqualTo("sha256:readiness");
        assertThat(evidence.get(0).path("source").asText()).isEqualTo("browserDiscovery");
        assertThat(evidence.get(0).path("elements").isArray()).isTrue();
    }

    @Test
    void parseGeneratedCasesReadsPageFingerprintFromJsonOutput() throws Exception {
        ObjectNode output = objectMapper.createObjectNode().set("cases", objectMapper.createArrayNode().add(objectMapper.createObjectNode()
            .put("sourcePointId", "point-1")
            .put("title", "用户管理列表展示")
            .put("module", "用户")
            .put("caseKind", "web_automation")
            .put("pageName", "用户管理")
            .put("startUrl", "/users")
            .put("browser", "chromium")
            .put("viewport", "1440x900")
            .put("precondition", "")
            .put("objective", "验证用户管理列表")
            .put("steps", "goto /users")
            .put("assertions", "assertText text=用户管理 = 用户管理标题可见")
            .put("expectedResult", "用户管理标题可见")
            .put("naturalSteps", "进入用户管理页面")
            .put("naturalExpectedResult", "用户管理标题可见")
            .put("sourceFile", "req.docx")
            .put("sourceText", "原文")
            .put("pageFingerprint", "fp-1")));
        Method method = AiDraftTestPointService.class.getDeclaredMethod("parseGeneratedCases", JsonNode.class, List.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Object> items = (List<Object>) method.invoke(service, output, List.of(sourcePoint("point-1", "用户管理列表展示", "用户", "用户管理", "/users", "web_automation", "验证用户管理列表", "req.docx", "原文")));

        assertThat(items).hasSize(1);
        assertThat(pageFingerprint(items.get(0))).isEqualTo("fp-1");
    }

    @Test
    void validateGeneratedOutputDoesNotRecoverStartUrlFromAssertUrlOnlySteps() throws Exception {
        when(webAutomationCaseValidator.validateAndNormalize(any(), org.mockito.ArgumentMatchers.anyInt()))
            .thenAnswer(invocation -> new WebAutomationValidationResult(
                ((ObjectNode) invocation.getArgument(0)).deepCopy(),
                List.of()
            ));

        Method method = AiDraftTestPointService.class.getDeclaredMethod("validateGeneratedOutput", String.class);
        method.setAccessible(true);

        ValidatedGeneratedOutput validated = (ValidatedGeneratedOutput) method.invoke(service, """
            {
              "cases": [
                {
                  "sourcePointId": "point-1",
                  "title": "用户管理列表展示",
                  "module": "用户",
                  "caseKind": "web_automation",
                  "pageName": "用户管理",
                  "startUrl": "",
                  "browser": "chromium",
                  "viewport": "1440x900",
                  "precondition": "",
                  "objective": "验证用户管理列表",
                  "naturalSteps": "进入用户管理页面",
                  "naturalExpectedResult": "用户管理标题可见",
                  "steps": "assertUrl /users",
                  "assertions": "assertUrl /users",
                  "expectedResult": "用户管理标题可见",
                  "sourceFile": "req.docx",
                  "sourceText": "原文"
                }
              ]
            }
            """);

        assertThat(validated.output().path("cases").get(0).path("startUrl").asText()).isBlank();
        assertThat(validated.errors()).isEmpty();
    }

    @Test
    void generateCasesFailsOnceWhenWebAutomationOutputStillBreaksValidation() {
        when(jdbcTemplate.queryForList(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.<Object[]>any()))
            .thenReturn(List.of(sourcePoint("point-1", "用户管理列表展示", "用户", "用户管理", "/users", "web_automation", "验证用户管理列表", "req.docx", "用户管理标题可见")));
        when(aiGatewaySettingsService.loadDefaultTextSettings())
            .thenReturn(new ResolvedAiGatewaySettings("openai-compatible", "https://example.test", "gpt-4o-mini", "secret", 120000));
        when(aiGateway.structuredGenerate(any(), any()))
            .thenReturn(new com.haitlify.aitest.platform.ai.gateway.AiGenerationResult(true, """
                {"cases":[{"sourcePointId":"point-1","title":"用户管理列表展示","module":"用户","caseKind":"web_automation","pageName":"用户管理","startUrl":"/users","browser":"chromium","viewport":"1440x900","precondition":"","objective":"验证用户管理列表","naturalSteps":"进入用户管理页面","naturalExpectedResult":"用户管理标题可见","steps":"","assertions":"","expectedResult":"用户管理标题可见","sourceFile":"req.docx","sourceText":"原文"}]}
                """, "case-invalid-once", 10, 20, null));
        when(webAutomationCaseValidator.validateAndNormalize(any(), org.mockito.ArgumentMatchers.anyInt()))
            .thenAnswer(invocation -> new WebAutomationValidationResult(
                ((ObjectNode) invocation.getArgument(0)).deepCopy(),
                List.of(
                    "用例 point-1：缺少必填字段 steps",
                    "用例 point-1：缺少必填字段 assertions"
                )
            ));

        assertThatThrownBy(() -> service.generateCases(new GenerateCasesRequest(List.of("point-1"), null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("AI 生成结果不符合 Web 自动化执行规范")
            .hasMessageContaining("缺少必填字段 steps")
            .hasMessageContaining("缺少必填字段 assertions");

        verify(aiGateway).structuredGenerate(any(), any());
    }

    @Test
    void resolveDiscoveryUrlAdoptsEnvironmentRouteForSiteEntryPlans() {
        assertThat(service.resolveDiscoveryUrl("https://app-dev.example.test/#/home", "/"))
            .isEqualTo("https://app-dev.example.test/#/home");
        assertThat(service.resolveDiscoveryUrl("https://app-dev.example.test/#/home", "/list"))
            .isEqualTo("https://app-dev.example.test/#/list");
        assertThat(service.resolveDiscoveryUrl("https://app-dev.example.test/", "/console/account"))
            .isEqualTo("https://app-dev.example.test/console/account");
    }

    @Test
    void authorWebCaseActionsCarriesCollectedEvidenceIdsIntoThePromptAndAcceptsValidActions() {
        when(jdbcTemplate.queryForList(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.<Object[]>any()))
            .thenReturn(List.of(sourcePoint("point-1", "首页概览", "首页", "首页", "/", "web_automation", "验证首页概览", "req.docx", "首页可见")));
        when(aiGatewaySettingsService.loadDefaultTextSettings())
            .thenReturn(new ResolvedAiGatewaySettings("openai-compatible", "https://example.test", "gpt-4o-mini", "secret", 120000));
        when(aiGateway.structuredGenerate(any(), any()))
            .thenReturn(new com.haitlify.aitest.platform.ai.gateway.AiGenerationResult(true, """
                {"cases":[{"sourcePointId":"point-1","actions":[{"id":"a1","type":"assertVisible","safety":"readonly","target":{"kind":"element","evidenceId":"element-abc"}}]}]}
                """, "req-author-1", 10, 20, null));
        var evidence = objectMapper.createArrayNode().add(objectMapper.createObjectNode()
            .put("sourcePointId", "point-1")
            .put("pageUrl", "https://app-dev.example.test/#/home")
            // 顶层 elements = 探索结束态（授权不应采用）；初始态在 exploration.rounds[0].observation.elements
            .set("elements", objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                .put("id", "element-final").put("evidenceId", "element-final-state").put("locator", "text=结束态元素")))
            .set("exploration", objectMapper.createObjectNode().set("rounds", objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode().set("observation", objectMapper.createObjectNode()
                    .set("elements", objectMapper.createArrayNode()
                        .add(objectMapper.createObjectNode()
                            .put("id", "element-1").put("evidenceId", "element-abc").put("locator", "text=首页")
                            .set("locatorSpec", objectMapper.createObjectNode().put("strategy", "text").put("text", "首页")))
                        .add(objectMapper.createObjectNode()
                            .put("id", "element-2").put("evidenceId", "element-count").put("locator", "text=待处理告警 485"))))))));
        var caseItem = objectMapper.createObjectNode().put("sourcePointId", "point-1").put("title", "首页概览").put("startUrl", "/");

        JsonNode actions = service.authorWebCaseActions("point-1", caseItem, evidence);

        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).path("target").path("evidenceId").asText()).isEqualTo("element-abc");
        var promptCaptor = org.mockito.ArgumentCaptor.forClass(JsonNode.class);
        org.mockito.Mockito.verify(aiGateway).structuredGenerate(any(), promptCaptor.capture());
        assertThat(promptCaptor.getValue().path("pageElements")).hasSize(1);
        assertThat(promptCaptor.getValue().path("pageElements").get(0).path("evidenceId").asText()).isEqualTo("element-abc");
        assertThat(promptCaptor.getValue().path("pageElements").get(0).path("label").asText()).isEqualTo("首页");
        assertThat(promptCaptor.getValue().path("actionIrRules").path("valueAction").asText())
            .contains("禁止输出 fill/input/select")
            .contains("必须 fail-closed")
            .doesNotContain("literal");
    }

    @Test
    void authorWebCaseActionsFailsClosedWhenModelActionsDoNotBindToEvidence() {
        when(jdbcTemplate.queryForList(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.<Object[]>any()))
            .thenReturn(List.of(sourcePoint("point-1", "首页概览", "首页", "首页", "/", "web_automation", "验证首页概览", "req.docx", "首页可见")));
        when(aiGatewaySettingsService.loadDefaultTextSettings())
            .thenReturn(new ResolvedAiGatewaySettings("openai-compatible", "https://example.test", "gpt-4o-mini", "secret", 120000));
        when(aiGateway.structuredGenerate(any(), any()))
            .thenReturn(new com.haitlify.aitest.platform.ai.gateway.AiGenerationResult(true, """
                {"cases":[{"sourcePointId":"point-1","actions":[{"id":"a1","type":"click","safety":"readonly","target":{"value":"首页"}}]}]}
                """, "req-author-2", 10, 20, null));
        var evidence = objectMapper.createArrayNode().add(objectMapper.createObjectNode().put("sourcePointId", "point-1"));

        assertThatThrownBy(() -> service.authorWebCaseActions(
                "point-1", objectMapper.createObjectNode().put("sourcePointId", "point-1"), evidence))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("AI Web 动作授权失败")
            .hasMessageContaining("evidenceId");
    }

    @Test
    void authorWebCaseActionsRejectsModelProducedFillBeforeBrowserDispatch() {
        assertAuthoringRejectsInputAction("fill");
    }

    @Test
    void authorWebCaseActionsRejectsModelProducedInputBeforeBrowserDispatch() {
        assertAuthoringRejectsInputAction("input");
    }

    @Test
    void authorWebCaseActionsRejectsModelProducedSelectBeforeBrowserDispatch() {
        assertAuthoringRejectsInputAction("select");
    }

    private void assertAuthoringRejectsInputAction(String actionType) {
        when(jdbcTemplate.queryForList(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.<Object[]>any()))
            .thenReturn(List.of(sourcePoint("point-1", "首页概览", "首页", "首页", "/", "web_automation", "验证首页概览", "req.docx", "首页可见")));
        when(aiGatewaySettingsService.loadDefaultTextSettings())
            .thenReturn(new ResolvedAiGatewaySettings("openai-compatible", "https://example.test", "gpt-4o-mini", "secret", 120000));
        when(aiGateway.structuredGenerate(any(), any()))
            .thenReturn(new com.haitlify.aitest.platform.ai.gateway.AiGenerationResult(true,
                "{\"cases\":[{\"sourcePointId\":\"point-1\",\"actions\":["
                    + "{\"id\":\"a1\",\"type\":\"" + actionType + "\",\"safety\":\"readonly\","
                    + "\"target\":{\"kind\":\"element\",\"evidenceId\":\"element-abc\"},"
                    + "\"value\":{\"kind\":\"literal\",\"value\":\"模型生成的业务输入\"}}]}]}",
                "req-author-input-" + actionType, 10, 20, null));

        assertThatThrownBy(() -> service.authorWebCaseActions(
                "point-1", objectMapper.createObjectNode().put("sourcePointId", "point-1"),
                objectMapper.createArrayNode().add(objectMapper.createObjectNode().put("sourcePointId", "point-1"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("AI Web 动作授权失败")
            .hasMessageContaining(actionType)
            .hasMessageContaining("新 Web 动作授权路径禁止输入动作");
    }

    private tools.jackson.databind.node.ObjectNode naturalPlan(String pointId) {
        return objectMapper.createObjectNode()
            .put("sourcePointId", pointId)
            .put("title", "标题")
            .put("module", "模块")
            .put("pageName", "页面")
            .put("startUrl", "/")
            .set("naturalSteps", objectMapper.createArrayNode().add("进入页面"))
            .put("naturalExpectedResult", "可见")
            .put("objective", "目标");
    }

    private Map<String, Object> sourcePoint(
        String pointId,
        String title,
        String module,
        String pageOrFeature,
        String startUrl,
        String caseKind,
        String testObjective,
        String sourceFile,
        String sourceText
    ) {
        Map<String, Object> row = new HashMap<>();
        row.put("POINT_ID", pointId);
        row.put("PROJECT_ID", 1L);
        row.put("REVIEW_STATUS", "confirmed");
        row.put("TITLE", title);
        row.put("MODULE_NAME", module);
        row.put("PAGE_OR_FEATURE", pageOrFeature);
        row.put("CASE_KIND", caseKind);
        row.put("TEST_OBJECTIVE", testObjective);
        row.put("SUGGESTED_STEPS", "进入页面");
        row.put("EXPECTED_RESULT", "可见");
        row.put("SOURCE_FILE", sourceFile);
        row.put("SOURCE_TEXT", sourceText);
        row.put("START_URL", startUrl);
        return row;
    }

    private Map<String, Object> standaloneFlag(String key, Boolean enabled) {
        Map<String, Object> row = new HashMap<>();
        row.put("config_key", key);
        row.put("enabled", enabled);
        return row;
    }

    private AiDraftTestPointService newService(JdbcTemplate jdbcTemplate) {
        return new AiDraftTestPointService(
            jdbcTemplate,
            objectMapper,
            taskFacade,
            aiGatewaySettingsService,
            aiRuleService,
            aiSkillPromptContextService,
            webAutomationCaseValidator,
            webAutomationLocatorDiscoveryService,
            locatorKnowledgeService,
            webExplorationSemanticExpansionService,
            webCaseCompilationService
        );
    }

    private static class CapturingJdbcTemplate extends JdbcTemplate {
        String lastQuery = "";
        Object[] lastArgs = new Object[0];
        List<Map<String, Object>> pointRows = List.of();
        List<Map<String, Object>> flagRows = List.of();

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            lastQuery = sql;
            lastArgs = args == null ? new Object[0] : args;
            if (sql.contains("from ai_draft_test_points")) return pointRows;
            if (sql.contains("from ai_system_configs")) return flagRows;
            return List.of();
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType) {
            return null;
        }
    }

    private String pageFingerprint(Object item) throws Exception {
        return (String) item.getClass().getMethod("pageFingerprint").invoke(item);
    }

    @Test
    void discoveryBlocksPlansWhoseValueNeedsARuntimeSourceChannel() throws Exception {
        // 采集请求没有变量通道（PageLocatorDiscoveryRequest 无 variables 字段）：带 runtimevariable/
        // maskedsecret 的动作下发到 runner 只会 422，异常还冒泡成整批运行失败。计划期就指名动作并阻塞该点。
        JsonNode masked = objectMapper.readTree("""
            [{"id":"fill-password","type":"fill","value":{"kind":"maskedsecret","key":"loginPassword"}}]
            """);
        String reason = DiscoveryValueSources.unsupportedDiscoveryValueSource(masked);
        assertThat(reason).contains("fill-password").contains("maskedsecret").contains("loginPassword");

        JsonNode runtimeVariable = objectMapper.readTree("""
            [{"actionId":"fill-token","type":"fill","value":{"kind":"runtime_variable","key":"apiToken"}}]
            """);
        assertThat(DiscoveryValueSources.unsupportedDiscoveryValueSource(runtimeVariable)).contains("fill-token");

        // 内联字面量/无值动作照常下发：闸门只拦「平台没有来源通道」的动作。
        JsonNode literal = objectMapper.readTree("""
            [{"id":"fill-name","type":"fill","value":{"kind":"literal","value":"张三"}},
             {"id":"click-save","type":"click"}]
            """);
        assertThat(DiscoveryValueSources.unsupportedDiscoveryValueSource(literal)).isEmpty();
        assertThat(DiscoveryValueSources.unsupportedDiscoveryValueSource(null)).isEmpty();
    }
}
