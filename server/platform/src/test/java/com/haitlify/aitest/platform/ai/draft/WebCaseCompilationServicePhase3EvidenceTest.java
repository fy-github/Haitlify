package com.haitlify.aitest.platform.ai.draft;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.haitlify.aitest.platform.webautomation.WebAutomationLocatorDiscoveryService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 阶段 3 验收 2/3：编译器必须接受「生产链路装配出来的」真实浏览器取证。
 *
 * <p>载荷来自 server/web-runner/scripts/record-phase3-evidence.mjs 的真实 Chromium 运行
 * （点击菜单后再断言目标页面标题），页面证据由生产代码 {@link WebStepPageEvidenceAssembler}
 * 装配 —— 不再手工拼 fixture，避免「测试能过但生产链路缺字段」：
 * <ul>
 *   <li>初始页面没有最终标题（列表页只有菜单）</li>
 *   <li>最终页面没有初始菜单（详情页只有标题）</li>
 *   <li>每条页面证据都必须带 sourcePointId（编译器按它分组，缺了会被归到空测试点）</li>
 *   <li>跨页步骤证据必须落在各自页面（cross-page step evidence）</li>
 * </ul>
 */
class WebCaseCompilationServicePhase3EvidenceTest {

    private static final String ACTION_RESPONSE = "/ai/evidence/phase3-runner-action-response.json";
    private static final String SOURCE_POINT_ID = "point-phase3-record";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WebCaseCompilationService service =
        new WebCaseCompilationService(objectMapper, new WebAutomationCaseValidator());

    @Test
    @SuppressWarnings("unchecked")
    void assemblesProductionPageEvidenceAndCompilesTheCrossPageCase() throws Exception {
        WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse response = loadActionResponse();
        ArrayNode pageEvidence = objectMapper.createArrayNode();
        int stepDerived = WebStepPageEvidenceAssembler.appendDiscoveryBundles(
            pageEvidence, response, SOURCE_POINT_ID, "http://127.0.0.1/list", "设备管理", "设备列表", null);

        assertThat(stepDerived).as("跨页运行必须为起始页补齐独立证据").isEqualTo(1);
        assertThat(pageEvidence.size()).isEqualTo(2);
        assertThat(pageEvidence)
            .allSatisfy(bundle -> assertThat(bundle.path("sourcePointId").asText())
                .as("每条页面证据都必须带 sourcePointId").isEqualTo(SOURCE_POINT_ID));

        // 第 0 条是本次取证的最终页面（详情页），第 1 条是跨页点击补齐的起始页（列表页）。
        JsonNode targetPage = bundleOf(pageEvidence, "/detail");
        JsonNode startPage = bundleOf(pageEvidence, "/list");
        assertThat(targetPage.path("pageFingerprint").asText()).isNotBlank();
        assertThat(startPage.path("pageFingerprint").asText()).isNotBlank();
        assertThat(startPage.path("stepDerived").asBoolean(false)).isTrue();

        JsonNode clickStep = startPage.path("stepEvidence").path(0);
        assertThat(clickStep.path("actionId").asText()).isEqualTo("open-detail");
        String menuLocator = clickStep.path("resolvedTarget").path("locator").asText("");
        String menuEvidenceId = clickStep.path("resolvedTarget").path("evidenceId").asText("");
        JsonNode assertStep = targetPage.path("stepEvidence").path(0);
        assertThat(assertStep.path("actionId").asText()).isEqualTo("assert-detail-title");
        String titleLocator = assertStep.path("resolvedTarget").path("locator").asText("");
        String titleEvidenceId = assertStep.path("resolvedTarget").path("evidenceId").asText("");
        assertThat(menuLocator).as("起始页步骤必须带菜单 locator").isNotBlank();
        assertThat(titleLocator).as("目标页步骤必须带标题 locator").isNotBlank();

        // 验收 2/3 的真实性来自证据本身：起始页没有目标标题元素，最终页没有起始菜单元素。
        assertThat(locatorsOf(startPage)).as("初始页面不能包含最终标题证据").doesNotContain(titleLocator);
        assertThat(locatorsOf(targetPage)).as("最终页面不能包含初始菜单证据").doesNotContain(menuLocator);

        ArrayNode actions = objectMapper.createArrayNode()
            .add(structuredAction("open-detail", "click", menuEvidenceId))
            .add(structuredAction("assert-detail-title", "assertVisible", titleEvidenceId));
        var plan = new AgentWebCaseGenerationFacade.NaturalWebCasePlan(
            SOURCE_POINT_ID, "设备详情页标题", "设备管理", "设备列表", "", List.of("ignored natural step"),
            "详情页标题可见", "验证跨页动作", actions, "chromium", "1440x900");

        var result = service.compileEvidenceBoundCase(
            new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(List.of(plan), objectMapper.createObjectNode()),
            pageEvidence, List.of(Map.of("POINT_ID", SOURCE_POINT_ID)));

        assertThat(result.success()).as(String.join(" | ", result.errors())).isTrue();
        assertThat(result.cases()).hasSize(1);
        // startUrl 为空时自动补的 goto 必须落在「第一个动作 before 所在的页面」= 列表页：
        // 回退到被选中的目标页（详情页）会让产物从详情页开始，再点只存在于列表页的菜单。
        assertThat(result.cases().get(0).steps()).startsWith("goto http://127.0.0.1:41827/list");
        assertThat(result.cases().get(0).steps()).contains("click " + menuLocator, "assertVisible " + titleLocator);
        assertThat(result.cases().get(0).stepTraces())
            .extracting(AgentWebCaseGenerationFacade.StepTrace::pageFingerprint)
            .containsExactly(
                startPage.path("pageFingerprint").asText(""),
                targetPage.path("pageFingerprint").asText(""));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsATargetEvidenceThatBelongsToAnotherPage() throws Exception {
        WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse response = loadActionResponse();
        ArrayNode pageEvidence = objectMapper.createArrayNode();
        WebStepPageEvidenceAssembler.appendDiscoveryBundles(
            pageEvidence, response, SOURCE_POINT_ID, "http://127.0.0.1/list", "设备管理", "设备列表", null);
        JsonNode startPage = bundleOf(pageEvidence, "/list");
        String titleEvidenceId = bundleOf(pageEvidence, "/detail")
            .path("stepEvidence").path(0).path("resolvedTarget").path("evidenceId").asText("");

        // 点击步骤属于列表页（before=/list），却引用详情页标题的证据：必须按页面绑定拒绝，
        // 这是 evidenceId（内容派生、页面无关）与页面身份之间的唯一强制点。
        String titleEvidenceIdForAssert = titleEvidenceId;
        ArrayNode actions = objectMapper.createArrayNode()
            .add(structuredAction("open-detail", "click", titleEvidenceId))
            .add(structuredAction("assert-detail-title", "assertVisible", titleEvidenceIdForAssert));
        var plan = new AgentWebCaseGenerationFacade.NaturalWebCasePlan(
            SOURCE_POINT_ID, "设备详情页标题", "设备管理", "设备列表", "", List.of("ignored natural step"),
            "详情页标题可见", "验证跨页绑定", actions, "chromium", "1440x900");

        var result = service.compileEvidenceBoundCase(
            new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(List.of(plan), objectMapper.createObjectNode()),
            pageEvidence, List.of(Map.of("POINT_ID", SOURCE_POINT_ID)));

        assertThat(result.success()).isFalse();
        assertThat(startPage.path("pageUrl").asText()).endsWith("/list");
        assertThat(result.errors()).anySatisfy(error -> assertThat(error).contains("evidenceId"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void assemblesNavigateCrossPageEvidenceAndCompilesTheGotoToTheActionUrl() throws Exception {
        WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse response = loadResponse("/ai/evidence/phase3-runner-navigate-response.json");
        ArrayNode pageEvidence = objectMapper.createArrayNode();
        WebStepPageEvidenceAssembler.appendDiscoveryBundles(
            pageEvidence, response, SOURCE_POINT_ID, "http://127.0.0.1:41827/list", "设备管理", "设备列表", null);

        // 导航型跨页：第 1 步没有任何元素引用，两个页面证据都必须幸存（空快照曾被丢弃 → 用例编译失败）。
        assertThat(pageEvidence.size()).isEqualTo(2);
        assertThat(pageEvidence).allSatisfy(bundle -> assertThat(bundle.path("sourcePointId").asText()).isEqualTo(SOURCE_POINT_ID));
        JsonNode startPage = bundleOf(pageEvidence, "/list");
        JsonNode targetPage = bundleOf(pageEvidence, "/detail");
        assertThat(WebStepPageEvidenceAssembler.hasElements(startPage.path("elements"))).isTrue();
        JsonNode navigateStep = startPage.path("stepEvidence").path(0);
        assertThat(navigateStep.path("actionId").asText()).isEqualTo("go-detail");
        String detailUrl = "http://127.0.0.1:41827/detail";

        ArrayNode actions = objectMapper.createArrayNode()
            .add(objectMapper.createObjectNode().put("id", "go-detail").put("type", "navigate").put("safety", "readonly")
                .set("target", objectMapper.createObjectNode().put("kind", "route").put("value", detailUrl)))
            .add(objectMapper.createObjectNode().put("id", "assert-detail-url").put("type", "assertUrl").put("safety", "readonly")
                .set("target", objectMapper.createObjectNode().put("kind", "route").put("value", detailUrl)))
            .add(structuredAction("assert-detail-title", "assertVisible",
                stepEvidenceId(targetPage, "assert-detail-title")));
        var plan = new AgentWebCaseGenerationFacade.NaturalWebCasePlan(
            SOURCE_POINT_ID, "设备详情页标题", "设备管理", "设备列表", "", List.of("ignored natural step"),
            "详情页标题可见", "验证导航型跨页", actions, "chromium", "1440x900");

        var result = service.compileEvidenceBoundCase(
            new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(List.of(plan), objectMapper.createObjectNode()),
            pageEvidence, List.of(Map.of("POINT_ID", SOURCE_POINT_ID)));

        assertThat(result.success()).as(String.join(" | ", result.errors())).isTrue();
        // 导航步骤必须跳到动作自己的 URL，而不是执行起点（曾经静默编译成 goto startUrl）。
        assertThat(result.cases().get(0).steps()).contains("goto " + detailUrl);
        assertThat(result.cases().get(0).steps()).doesNotContain("goto http://127.0.0.1:41827/list");
        // 地址断言同样必须用它自己的目标：回退执行起点会把「断言已到详情页」编译成「断言还在列表页」。
        assertThat(result.cases().get(0).steps()).contains("assertUrl " + detailUrl);
        assertThat(result.cases().get(0).steps()).doesNotContain("assertUrl http://127.0.0.1:41827/list");
    }

    @Test
    @SuppressWarnings("unchecked")
    void keepsAnEmptyStartPageBundleSoRouteStepsStillCompile() throws Exception {
        WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse response = loadResponse("/ai/evidence/phase3-runner-plain-start-response.json");
        ArrayNode pageEvidence = objectMapper.createArrayNode();
        WebStepPageEvidenceAssembler.appendDiscoveryBundles(
            pageEvidence, response, "point-phase3-plain", "http://127.0.0.1:41827/plain", "设备管理", "纯文本页", null);

        // 起始页确实没有任何可采集元素：这一页证据必须留下（带显式诊断），
        // 否则跨页用例会在编译期以「缺少 step evidence」这种误导性错误失败。
        assertThat(pageEvidence.size()).isEqualTo(2);
        JsonNode emptyPage = bundleOf(pageEvidence, "/plain");
        assertThat(WebStepPageEvidenceAssembler.hasElements(emptyPage.path("elements"))).isFalse();
        assertThat(emptyPage.path("elementsEmpty").asBoolean(false)).isTrue();
        assertThat(emptyPage.path("elementsEmptyReason").asText()).isEqualTo("no_collectable_elements");
        assertThat(emptyPage.path("stepEvidence").size()).isEqualTo(1);

        String detailUrl = "http://127.0.0.1:41827/detail";
        ArrayNode actions = objectMapper.createArrayNode()
            .add(objectMapper.createObjectNode().put("id", "plain-go-detail").put("type", "navigate").put("safety", "readonly")
                .set("target", objectMapper.createObjectNode().put("kind", "route").put("value", detailUrl)))
            .add(objectMapper.createObjectNode().put("id", "plain-assert-detail-url").put("type", "assertUrl").put("safety", "readonly")
                .set("target", objectMapper.createObjectNode().put("kind", "route").put("value", detailUrl)));
        var plan = new AgentWebCaseGenerationFacade.NaturalWebCasePlan(
            "point-phase3-plain", "纯文本页导航", "设备管理", "纯文本页", "", List.of("ignored natural step"),
            "详情页可访问", "验证空页面证据", actions, "chromium", "1440x900");

        var result = service.compileEvidenceBoundCase(
            new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(List.of(plan), objectMapper.createObjectNode()),
            pageEvidence, List.of(Map.of("POINT_ID", "point-phase3-plain")));

        assertThat(result.success()).as(String.join(" | ", result.errors())).isTrue();
        assertThat(result.cases().get(0).steps()).contains("goto " + detailUrl);
    }

    @Test
    void skipsRedundantOrUnidentifiedPageSnapshots() {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode evidence = mapper.createArrayNode();
        ObjectNode finalPage = evidence.addObject()
            .put("pageKey", "http://127.0.0.1/detail").put("pageFingerprint", "fp-detail");
        ArrayNode snapshots = mapper.createArrayNode();
        // 与最终页面同一身份的快照不能重复成第二条证据（否则「页面证据不唯一」）
        snapshots.add(mapper.createObjectNode()
            .put("pageKey", "http://127.0.0.1/detail").put("pageFingerprint", "fp-detail")
            .set("elements", mapper.createArrayNode().add(mapper.createObjectNode().put("locator", "css=#d"))));
        // 缺 pageFingerprint 的快照无法与步骤状态比对，fail-closed 跳过
        snapshots.add(mapper.createObjectNode()
            .put("pageKey", "http://127.0.0.1/list")
            .set("elements", mapper.createArrayNode().add(mapper.createObjectNode().put("locator", "css=#l"))));
        // 元素为空的快照照样产出页面证据：路由型步骤只需要页面身份，
        // 丢掉这一页反而会让「起始页无元素」的跨页用例以误导性的证据缺失错误失败。
        snapshots.add(mapper.createObjectNode()
            .put("pageKey", "http://127.0.0.1/empty").put("pageFingerprint", "fp-empty")
            .set("elements", mapper.createArrayNode()));

        int appended = WebStepPageEvidenceAssembler.appendStepDerivedBundles(
            evidence, finalPage, snapshots, null, SOURCE_POINT_ID, "http://127.0.0.1/detail", "设备管理", "设备详情");

        assertThat(appended).isEqualTo(1);
        assertThat(evidence.size()).isEqualTo(2);
        JsonNode emptyPage = evidence.get(1);
        assertThat(emptyPage.path("pageKey").asText()).isEqualTo("http://127.0.0.1/empty");
        assertThat(WebStepPageEvidenceAssembler.hasElements(emptyPage.path("elements"))).isFalse();
        assertThat(emptyPage.path("elementsEmpty").asBoolean(false)).isTrue();
        assertThat(emptyPage.path("elementsEmptyReason").asText()).isEqualTo("no_collectable_elements");
    }

    @Test
    void partitionsStepEvidenceByThePageEachStepBelongsTo() {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode steps = mapper.createArrayNode();
        steps.add(mapper.createObjectNode().put("actionId", "open-detail")
            .set("before", mapper.createObjectNode().put("pageKey", "http://127.0.0.1/list").put("pageFingerprint", "fp-list"))
            .set("after", mapper.createObjectNode().put("pageKey", "http://127.0.0.1/detail").put("pageFingerprint", "fp-detail"))
            .set("resolvedTarget", mapper.createObjectNode().put("pageKey", "http://127.0.0.1/list").put("pageFingerprint", "fp-list")));
        steps.add(mapper.createObjectNode().put("actionId", "assert-detail-title")
            .set("before", mapper.createObjectNode().put("pageKey", "http://127.0.0.1/detail").put("pageFingerprint", "fp-detail"))
            .set("after", mapper.createObjectNode().put("pageKey", "http://127.0.0.1/detail").put("pageFingerprint", "fp-detail"))
            .set("resolvedTarget", mapper.createObjectNode().put("pageKey", "http://127.0.0.1/detail").put("pageFingerprint", "fp-detail")));

        ArrayNode listSteps = WebStepPageEvidenceAssembler.stepEvidenceForPage(steps, "http://127.0.0.1/list", "fp-list");
        ArrayNode detailSteps = WebStepPageEvidenceAssembler.stepEvidenceForPage(steps, "http://127.0.0.1/detail", "fp-detail");

        assertThat(listSteps).hasSize(1);
        assertThat(listSteps.get(0).path("actionId").asText()).isEqualTo("open-detail");
        assertThat(detailSteps).hasSize(1);
        assertThat(detailSteps.get(0).path("actionId").asText()).isEqualTo("assert-detail-title");
    }

    @Test
    @SuppressWarnings("unchecked")
    void failsClosedWhenAStructuredRouteActionTargetIsNotARoute() throws Exception {
        // 之前会静默回退执行起点 URL：assertUrl 被编译成「断言还在列表页」、goto 被编译成「打开列表页」，
        // 而用例仍然 success=true、errors=[]——「断言已到详情页」被悄悄改成断言另一个页面。
        WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse response = loadResponse("/ai/evidence/phase3-runner-navigate-response.json");
        ArrayNode pageEvidence = objectMapper.createArrayNode();
        WebStepPageEvidenceAssembler.appendDiscoveryBundles(
            pageEvidence, response, SOURCE_POINT_ID, "http://127.0.0.1:41827/list", "设备管理", "设备列表", null);
        String detailUrl = "http://127.0.0.1:41827/detail";

        ArrayNode actions = objectMapper.createArrayNode()
            .add(objectMapper.createObjectNode().put("id", "go-detail").put("type", "navigate").put("safety", "readonly")
                .set("target", objectMapper.createObjectNode().put("kind", "route").put("value", detailUrl)))
            .add(objectMapper.createObjectNode().put("id", "assert-detail-url").put("type", "assertUrl").put("safety", "readonly")
                .set("target", objectMapper.createObjectNode().put("kind", "route").put("value", "设备详情页")))
            .add(structuredAction("assert-detail-title", "assertVisible",
                stepEvidenceId(bundleOf(pageEvidence, "/detail"), "assert-detail-title")));
        var plan = new AgentWebCaseGenerationFacade.NaturalWebCasePlan(
            SOURCE_POINT_ID, "设备详情页标题", "设备管理", "设备列表", "", List.of("ignored natural step"),
            "详情页标题可见", "验证不可解析地址必须 fail-closed", actions, "chromium", "1440x900");

        var result = service.compileEvidenceBoundCase(
            new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(List.of(plan), objectMapper.createObjectNode()),
            pageEvidence, List.of(Map.of("POINT_ID", SOURCE_POINT_ID)));

        assertThat(result.success()).as(String.join(" | ", result.errors())).isFalse();
        // 计划期即被拦下（ActionIrValidator 与 runner 同口径），错误信息里带上原值方便定位；
        // 编译期的同口径守卫保留为纵深防御（覆盖不走计划校验的入口）。
        assertThat(result.errors()).anyMatch(error -> error.contains("不是可执行的地址") && error.contains("设备详情页"));
        // 关键：不能有任何编译产物把起点地址当成该动作的目标。
        assertThat(result.cases()).allSatisfy(compiled -> assertThat(compiled.steps())
            .doesNotContain("assertUrl http://127.0.0.1:41827/list"));
    }

    private WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse loadActionResponse() throws Exception {
        return loadResponse(ACTION_RESPONSE);
    }

    /** 按 actionId 取步骤证据里的 evidenceId：不能按数组下标取，步骤顺序会随动作集合变化。 */
    private static String stepEvidenceId(JsonNode bundle, String actionId) {
        for (JsonNode step : bundle.path("stepEvidence")) {
            if (actionId.equals(step.path("actionId").asText(""))) {
                return step.path("resolvedTarget").path("evidenceId").asText("");
            }
        }
        throw new AssertionError("页面证据里找不到步骤 " + actionId + "：" + bundle.path("pageKey").asText(""));
    }

    private WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse loadResponse(String resource) throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(resource)) {
            assertThat(stream).as("阶段 3 真实载荷必须存在：" + resource).isNotNull();
            return objectMapper.readValue(stream, WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse.class);
        }
    }

    private JsonNode bundleOf(JsonNode pageEvidence, String urlSuffix) {
        for (JsonNode bundle : pageEvidence) {
            if (bundle.path("pageUrl").asText("").endsWith(urlSuffix)) return bundle;
        }
        throw new AssertionError("页面证据缺少 " + urlSuffix + "：" + pageEvidence);
    }

    private List<String> locatorsOf(JsonNode page) {
        List<String> locators = new ArrayList<>();
        page.path("elements").forEach((element) -> locators.add(element.path("locator").asText("")));
        return locators;
    }

    private ObjectNode structuredAction(String id, String type, String evidenceId) {
        return objectMapper.createObjectNode().put("id", id).put("type", type).put("safety", "readonly")
            .set("target", objectMapper.createObjectNode().put("evidenceId", evidenceId));
    }
}
