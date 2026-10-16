package com.haitlify.aitest.platform.ai.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

class AgentWebCaseGenerationFacadeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiDraftTestPointService draftTestPointService = mock(AiDraftTestPointService.class);
    private final AgentWebCaseGenerationFacade facade = new AgentWebCaseGenerationFacade(draftTestPointService, objectMapper);

    @Test
    void compilePassesRawEvidenceThroughUnchanged() {
        JsonNode rawEvidence = objectMapper.createArrayNode().add(objectMapper.createObjectNode()
            .put("sourcePointId", "point-1")
            .put("pageKey", "https://example.test/users"));
        AgentWebCaseGenerationFacade.BrowserEvidenceBundle evidence = new AgentWebCaseGenerationFacade.BrowserEvidenceBundle(
            rawEvidence, List.of(), 1, 1, false
        );
        AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch plan = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(
            List.of(), objectMapper.createObjectNode().set("cases", objectMapper.createArrayNode())
        );
        when(draftTestPointService.compileWebCases(anyList(), any(), any(), isNull(), isNull(), any()))
            .thenReturn(new GeneratedCaseListResponse(List.of()));

        facade.compile(List.of("point-1"), plan, evidence, null, null, List.of());

        var captor = org.mockito.ArgumentCaptor.forClass(JsonNode.class);
        verify(draftTestPointService).compileWebCases(eq(List.of("point-1")), any(), captor.capture(), isNull(), isNull(), any());
        assertThat(captor.getValue()).isSameAs(rawEvidence);
    }

    @Test
    void ensurePlanActionsAuthorsMissingActionIrFromEvidenceAndKeepsProviderEvidence() throws Exception {
        JsonNode raw = objectMapper.createObjectNode().set("cases", objectMapper.createArrayNode()
            .add(objectMapper.createObjectNode().put("sourcePointId", "point-1").put("title", "首页概览")));
        var batch = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(List.of(), raw, "req-plan-1");
        JsonNode authored = objectMapper.readTree(
            "[{\"id\":\"a1\",\"type\":\"assertVisible\",\"safety\":\"readonly\",\"target\":{\"kind\":\"element\",\"evidenceId\":\"element-abc\"}}]");
        JsonNode evidence = objectMapper.createArrayNode().add(objectMapper.createObjectNode()
            .put("sourcePointId", "point-1")
            .set("elements", objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                .put("evidenceId", "element-abc").put("locator", "text=首页"))));
        when(draftTestPointService.authorWebCaseActions(eq("point-1"), any(), any(), any())).thenReturn(authored);

        var ensured = facade.ensurePlanActions(batch, List.of("point-1"), evidence, List.of());

        assertThat(ensured).isNotSameAs(batch);
        assertThat(ensured.providerRequestId()).isEqualTo("req-plan-1");
        assertThat(ensured.rawOutput().path("cases").get(0).path("actions")).isSameAs(authored);
        verify(draftTestPointService).authorWebCaseActions(eq("point-1"), any(), eq(evidence), eq(List.of()));
    }

    @Test
    void ensurePlanActionsSkipsPlansThatAlreadyCarryValidActionIr() throws Exception {
        JsonNode raw = objectMapper.createObjectNode().set("cases", objectMapper.createArrayNode()
            .add(objectMapper.createObjectNode().put("sourcePointId", "point-1")
                .set("actions", objectMapper.readTree(
                    "[{\"id\":\"a1\",\"type\":\"assertVisible\",\"safety\":\"readonly\",\"target\":{\"kind\":\"element\",\"evidenceId\":\"element-abc\"}}]"))));
        var batch = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(List.of(), raw, "req-plan-1");

        var ensured = facade.ensurePlanActions(batch, List.of("point-1"), objectMapper.createArrayNode(), List.of());

        assertThat(ensured).isSameAs(batch);
        verify(draftTestPointService, never()).authorWebCaseActions(any(), any(), any());
    }

    @Test
    void ensurePlanActionsFailsClosedWhenAuthoringRejectsTheModelOutput() {
        JsonNode raw = objectMapper.createObjectNode().set("cases", objectMapper.createArrayNode()
            .add(objectMapper.createObjectNode().put("sourcePointId", "point-1").put("title", "首页概览")));
        var batch = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(List.of(), raw, "req-plan-1");
        when(draftTestPointService.authorWebCaseActions(eq("point-1"), any(), any(), any()))
            .thenThrow(new IllegalArgumentException("AI Web 动作授权失败：point-1 未返回 actions"));

        assertThatThrownBy(() -> facade.ensurePlanActions(batch, List.of("point-1"), objectMapper.createArrayNode(), List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("AI Web 动作授权失败");
    }

    @Test
    void compileReauthorizesWithAccumulatedExclusionsNamedBySecondEvidencePass() throws Exception {
        // 实测 2026-10-14：模型可能绑定到「重新打开页面后不存在」的元素（实时数据/不稳定元素），
        // 二次取证会以 action_evidence_missing 点名该目标；facade 必须排除它并重授权（多轮累积排除），而不是直接判死。
        JsonNode raw = objectMapper.createObjectNode().set("cases", objectMapper.createArrayNode()
            .add(objectMapper.createObjectNode().put("sourcePointId", "point-1").put("title", "首页概览")));
        var batch = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(List.of(), raw, "req-plan-1");
        JsonNode bad = objectMapper.readTree(
            "[{\"id\":\"a1\",\"type\":\"assertVisible\",\"safety\":\"readonly\",\"target\":{\"kind\":\"element\",\"evidenceId\":\"element-bad\"}}]");
        JsonNode bad2 = objectMapper.readTree(
            "[{\"id\":\"a1\",\"type\":\"assertVisible\",\"safety\":\"readonly\",\"target\":{\"kind\":\"element\",\"evidenceId\":\"element-bad2\"}}]");
        JsonNode good = objectMapper.readTree(
            "[{\"id\":\"a1\",\"type\":\"assertVisible\",\"safety\":\"readonly\",\"target\":{\"kind\":\"element\",\"evidenceId\":\"element-good\"}}]");
        when(draftTestPointService.authorWebCaseActions(eq("point-1"), any(), any(), any())).thenReturn(bad, bad2, good);
        when(draftTestPointService.resolveWebCaseEvidence(anyList(), any(), any(), anyList(), anyLong()))
            .thenThrow(new com.haitlify.aitest.platform.webautomation.WebAutomationEvidenceException(
                422, "Action IR target element-bad was not observed in the current page", "action_evidence_missing",
                objectMapper.createObjectNode().put("targetEvidenceId", "element-bad")))
            .thenThrow(new com.haitlify.aitest.platform.webautomation.WebAutomationEvidenceException(
                422, "Action IR target element-bad2 was not observed in the current page", "action_evidence_missing",
                objectMapper.createObjectNode().put("targetEvidenceId", "element-bad2")))
            .thenReturn(objectMapper.createArrayNode());
        when(draftTestPointService.compileWebCases(anyList(), any(), any(), isNull(), isNull(), any()))
            .thenReturn(new GeneratedCaseListResponse(List.of()));
        JsonNode evidence = objectMapper.createArrayNode().add(objectMapper.createObjectNode()
            .put("sourcePointId", "point-1")
            .set("elements", objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                .put("evidenceId", "element-good").put("locator", "text=首页"))));

        facade.compile(List.of("point-1"), batch,
            new AgentWebCaseGenerationFacade.BrowserEvidenceBundle(evidence, List.of(), 1, 1, true, "matched", 1, 70, 10_000L),
            null, null, List.of());

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(draftTestPointService, times(3)).authorWebCaseActions(eq("point-1"), any(), any(), captor.capture());
        assertThat(captor.getAllValues().get(0)).isEmpty();
        assertThat(captor.getAllValues().get(1)).containsExactly("element-bad");
        assertThat(captor.getAllValues().get(2)).containsExactly("element-bad", "element-bad2");
        verify(draftTestPointService).compileWebCases(anyList(), any(), any(), isNull(), isNull(), any());
    }

    @Test
    void generateKeepsPageFingerprintAndSourceScope() throws Exception {
        when(draftTestPointService.generateCases(any(), any(), any()))
            .thenReturn(new GeneratedCaseListResponse(List.of(generatedCaseResponse(
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
                "原文",
                "fp-1"
            ))));

        List<AgentWebCaseGenerationFacade.GeneratedWebCase> cases = facade.generate(List.of("point-1"), null, null, List.of());

        assertThat(cases).hasSize(1);
        assertThat(cases.get(0).sourcePointId()).isEqualTo("point-1");
        assertThat(cases.get(0).pageName()).isEqualTo("用户管理");
        assertThat(cases.get(0).pageFingerprint()).isEqualTo("fp-1");
    }

    @Test
    void compileKeepsPageFingerprintAndSourceScope() throws Exception {
        when(draftTestPointService.compileWebCases(any(), any(), any(), any(), any(), any()))
            .thenReturn(new GeneratedCaseListResponse(List.of(generatedCaseResponse(
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
                "原文",
                "fp-1"
            ))));

        List<AgentWebCaseGenerationFacade.GeneratedWebCase> cases = facade.compile(
            List.of("point-1"),
            new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(List.of(), objectMapper.createObjectNode()),
            new AgentWebCaseGenerationFacade.BrowserEvidenceBundle(objectMapper.createArrayNode(), List.of(), 0, 0, false),
            null,
            null,
            List.of()
        );

        assertThat(cases).hasSize(1);
        assertThat(cases.get(0).sourcePointId()).isEqualTo("point-1");
        assertThat(cases.get(0).pageName()).isEqualTo("用户管理");
        assertThat(cases.get(0).pageFingerprint()).isEqualTo("fp-1");
    }

    @Test
    void planAcceptsRootArrayOutputs() {
        JsonNode rawPlan = objectMapper.createArrayNode().add(objectMapper.createObjectNode()
            .put("sourcePointId", "point-1")
            .put("title", "用户管理列表展示")
            .put("module", "用户")
            .put("pageName", "用户管理")
            .put("startUrl", "/users")
            .set("naturalSteps", objectMapper.createArrayNode().add("进入用户管理页面"))
            .put("naturalExpectedResult", "用户管理标题可见")
            .put("objective", "验证用户管理列表"));
        when(draftTestPointService.planWebCases(anyList(), any(), any()))
            .thenReturn(new com.haitlify.aitest.platform.ai.draft.WebCasePlanOutput(rawPlan, "plan-provider-req-1"));

        AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch batch = facade.plan(List.of("point-1"), null, List.of());

        assertThat(batch.plans()).extracting(AgentWebCaseGenerationFacade.NaturalWebCasePlan::sourcePointId)
            .containsExactly("point-1");
        assertThat(batch.plans()).extracting(AgentWebCaseGenerationFacade.NaturalWebCasePlan::title)
            .containsExactly("用户管理列表展示");
    }

    @Test
    void planCarriesRuntimeCompileContextWithoutReplacingStructuredActions() {
        JsonNode actions = objectMapper.createArrayNode().add(objectMapper.createObjectNode().put("id", "open").put("type", "goto"));
        JsonNode rawPlan = objectMapper.createObjectNode().set("cases", objectMapper.createArrayNode().add(objectMapper.createObjectNode()
            .put("sourcePointId", "point-1").put("title", "列表").put("module", "用户").put("pageName", "用户列表")
            .put("startUrl", "/users").set("naturalSteps", objectMapper.createArrayNode().add("打开列表"))
            .put("naturalExpectedResult", "列表可见").put("objective", "验证列表").set("actions", actions)));
        when(draftTestPointService.planWebCases(anyList(), any(), any()))
            .thenReturn(new com.haitlify.aitest.platform.ai.draft.WebCasePlanOutput(rawPlan, "plan-provider-req-1"));

        var context = new AgentWebCaseGenerationFacade.BrowserDiscoveryOptions(
            "https://runtime.example", "env-runtime", "firefox", "1280x720", true, null, null, null,
            null, null, null, null, null, null, null, null, "environment", null);
        var batch = facade.plan(List.of("point-1"), context, null, List.of());
        var plan = batch.plans().getFirst();

        assertThat(plan.environmentId()).isEqualTo("env-runtime");
        assertThat(plan.baseUrl()).isEqualTo("https://runtime.example");
        assertThat(plan.browser()).isEqualTo("firefox");
        assertThat(plan.actions()).isSameAs(actions);
        // 用户实测：withCompileContext 重建批次时丢过 providerRequestId，产出被错判 BLOCKED。
        // model evidence 必须原样穿过 compile context 注入。
        assertThat(batch.providerRequestId()).isEqualTo("plan-provider-req-1");
    }

    @Test
    void compileContextDoesNotErasePersistedPlanContextWhenRequestFieldsAreBlank() {
        JsonNode actions = objectMapper.createArrayNode().add(objectMapper.createObjectNode().put("id", "open").put("type", "navigate"));
        var persisted = new AgentWebCaseGenerationFacade.NaturalWebCasePlan(
            "point-1", "列表", "用户", "用户列表", "/users", List.of("打开列表"), "列表可见", "验证列表", actions,
            "env-plan", "https://plan.example", "webkit", "390x844", "reuse_authenticated_context");
        var batch = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(
            List.of(persisted), objectMapper.createObjectNode().set("cases", objectMapper.createArrayNode().add(
                objectMapper.createObjectNode().put("sourcePointId", "point-1").set("actions", actions))));

        var blankRequest = new AgentWebCaseGenerationFacade.BrowserDiscoveryOptions(
            "", "", "", "", true, null, null, null, null, null, null, null, null, null, null, null, "", null);

        var effective = facade.withCompileContext(batch, blankRequest).plans().getFirst();

        assertThat(effective.environmentId()).isEqualTo("env-plan");
        assertThat(effective.baseUrl()).isEqualTo("https://plan.example");
        assertThat(effective.browser()).isEqualTo("webkit");
        assertThat(effective.viewport()).isEqualTo("390x844");
        assertThat(effective.authenticationMode()).isEqualTo("reuse_authenticated_context");
        assertThat(effective.actions()).isEqualTo(actions);
    }

    private GeneratedCaseResponse generatedCaseResponse(
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
        String pageFingerprint
    ) throws Exception {
        var ctor = GeneratedCaseResponse.class.getDeclaredConstructor(
            String.class, String.class, String.class, String.class, String.class, String.class, String.class,
            String.class, String.class, String.class, String.class, String.class, String.class, String.class,
            String.class, String.class, String.class, String.class
        );
        ctor.setAccessible(true);
        return (GeneratedCaseResponse) ctor.newInstance(
            sourcePointId, title, module, caseKind, pageName, startUrl, browser, viewport, precondition,
            objective, steps, assertions, expectedResult, naturalSteps, naturalExpectedResult, sourceFile,
            sourceText, pageFingerprint
        );
    }
}
