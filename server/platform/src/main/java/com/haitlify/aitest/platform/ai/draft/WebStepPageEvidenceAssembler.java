package com.haitlify.aitest.platform.ai.draft;

import java.util.List;

import com.haitlify.aitest.platform.webautomation.WebAutomationLocatorDiscoveryService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 把一次浏览器取证（{@link WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse}）
 * 落成平台页面证据（browser evidence）。
 *
 * <p>两个曾经的线上缺口都在这里收口：
 * <ul>
 *   <li>页面证据必须带 {@code sourcePointId}：编译器按它分组，缺了会被归到空测试点，真实链路直接编译失败；</li>
 *   <li>跨页动作的 before/after 属于不同页面：只保存最后一次观察到的页面，会让
 *       「点击菜单 → 断言目标页标题」在生产链路里无法编译（过去测试能过只是因为 fixture 是手工写的两页）。</li>
 * </ul>
 */
final class WebStepPageEvidenceAssembler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WebStepPageEvidenceAssembler() { }

    /** 页面证据的唯一标识：pageKey + pageFingerprint。 */
    static String bundleIdentity(JsonNode page) {
        if (page == null || page.isMissingNode() || page.isNull()) return "|";
        return page.path("pageKey").asText("").strip() + "|" + page.path("pageFingerprint").asText("").strip();
    }

    static boolean hasElements(JsonNode elements) {
        return elements != null && elements.isArray() && elements.size() > 0;
    }

    /**
     * 写入当前测试点的页面证据：第一条是本次取证的最终页面（含元素、步骤证据与探索报告），
     * 随后按 pageSnapshots 为跨页步骤补齐其它页面（每条同样带 sourcePointId）。
     *
     * @return 追加的「步骤页面」条数（不含最终页面那条）
     */
    static int appendDiscoveryBundles(
        ArrayNode evidence,
        WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse snapshot,
        String sourcePointId,
        String plannedStartUrl,
        String moduleName,
        String pageName,
        JsonNode exploration
    ) {
        if (evidence == null || snapshot == null) return 0;
        ObjectNode page = evidence.addObject();
        page.put("sourcePointId", blankToDefault(sourcePointId));
        page.put("plannedStartUrl", blankToDefault(plannedStartUrl));
        page.put("pageUrl", blankToDefault(snapshot.pageUrl()));
        applyPageIdentity(page, snapshot);
        page.put("source", "browserDiscovery");
        page.put("moduleName", blankToDefault(moduleName));
        page.put("pageName", blankToDefault(pageName));
        page.set("elements", elementsNode(snapshot.elements()));
        if (snapshot.historicalMatchedIds() != null && !snapshot.historicalMatchedIds().isEmpty()) {
            page.set("historicalMatchedIds", MAPPER.valueToTree(snapshot.historicalMatchedIds()));
        }
        if (snapshot.historicalRejectedIds() != null && !snapshot.historicalRejectedIds().isEmpty()) {
            page.set("historicalRejectedIds", MAPPER.valueToTree(snapshot.historicalRejectedIds()));
        }
        if (snapshot.sessionId() != null && !snapshot.sessionId().isBlank()) page.put("sessionId", snapshot.sessionId());
        page.put("authenticated", Boolean.TRUE.equals(snapshot.authenticated()));
        if (snapshot.traceId() != null && !snapshot.traceId().isBlank()) page.put("traceId", snapshot.traceId());
        if (snapshot.screenshotUrl() != null && !snapshot.screenshotUrl().isBlank()) page.put("screenshotUrl", snapshot.screenshotUrl());
        if (snapshot.stepEvidence() != null && !snapshot.stepEvidence().isNull()) {
            JsonNode stepsForThisPage = stepEvidenceForPage(
                snapshot.stepEvidence(), page.path("pageKey").asText(""), page.path("pageFingerprint").asText(""));
            if (stepsForThisPage.size() > 0) page.set("stepEvidence", stepsForThisPage);
        }
        if (exploration != null && !exploration.isNull()) {
            page.set("exploration", exploration);
        }
        return appendStepDerivedBundles(evidence, page, snapshot.pageSnapshots(), snapshot.stepEvidence(),
            sourcePointId, plannedStartUrl, moduleName, pageName);
    }

    private static ArrayNode elementsNode(List<WebAutomationLocatorDiscoveryService.PageLocatorEvidence> elements) {
        ArrayNode array = MAPPER.createArrayNode();
        if (elements == null) return array;
        for (WebAutomationLocatorDiscoveryService.PageLocatorEvidence element : elements) {
            if (element == null) continue;
            array.add(MAPPER.valueToTree(element));
        }
        return array;
    }

    private static void applyPageIdentity(ObjectNode page, WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse snapshot) {
        if (page == null || snapshot == null || snapshot.exploration() == null) return;
        JsonNode finalObservation = snapshot.exploration().finalObservation();
        JsonNode readiness = snapshot.exploration().readiness();
        String pageKey = firstText(finalObservation, "pageKey");
        if (pageKey.isBlank()) pageKey = firstText(readiness, "pageKey");
        if (!pageKey.isBlank()) page.put("pageKey", pageKey);
        String pageFingerprint = firstText(finalObservation, "pageFingerprint", "fingerprint");
        if (pageFingerprint.isBlank()) pageFingerprint = firstText(readiness, "pageFingerprint", "shellFingerprint");
        if (!pageFingerprint.isBlank()) page.put("pageFingerprint", pageFingerprint);
    }

    /**
     * 除最终页面外的步骤页面（例如跨页点击的起始页）各写一条独立证据；
     * 与最终页面同一 pageKey + pageFingerprint 的快照跳过，避免重复证据导致「页面证据不唯一」。
     */
    static int appendStepDerivedBundles(
        ArrayNode evidence,
        JsonNode finalPage,
        JsonNode pageSnapshots,
        JsonNode stepEvidence,
        String sourcePointId,
        String plannedStartUrl,
        String moduleName,
        String pageName
    ) {
        if (evidence == null || pageSnapshots == null || !pageSnapshots.isArray()) return 0;
        String finalIdentity = bundleIdentity(finalPage);
        int appended = 0;
        for (JsonNode candidate : pageSnapshots) {
            if (candidate == null || !candidate.isObject()) continue;
            String pageKey = candidate.path("pageKey").asText("").strip();
            String pageFingerprint = candidate.path("pageFingerprint").asText("").strip();
            if (pageKey.isBlank() || pageFingerprint.isBlank()) continue;
            if (bundleIdentity(candidate).equals(finalIdentity)) continue;
            JsonNode elements = candidate.path("elements");
            ObjectNode bundle = evidence.addObject();
            bundle.put("sourcePointId", blankToDefault(sourcePointId));
            bundle.put("plannedStartUrl", blankToDefault(plannedStartUrl));
            bundle.put("pageUrl", candidate.path("pageUrl").asText(""));
            bundle.put("pageKey", pageKey);
            bundle.put("pageFingerprint", pageFingerprint);
            bundle.put("source", "browserDiscovery");
            bundle.put("moduleName", blankToDefault(moduleName));
            bundle.put("pageName", blankToDefault(pageName));
            bundle.put("stepDerived", true);
            bundle.set("elements", elements.isArray() ? elements : MAPPER.createArrayNode());
            if (!hasElements(elements)) {
                // 页面确实没有可采集元素（runner 侧带 elementsEmpty 诊断）时仍保留该页证据：
                // 路由型步骤（navigate/assertUrl/waitForUrl）只需要页面身份，元素型步骤会在解析
                // locator 时得到明确错误；直接丢页会让跨页用例以「缺少 step evidence」这种误导性错误失败。
                bundle.put("elementsEmpty", true);
                String reason = candidate.path("elementsEmptyReason").asText("").strip();
                bundle.put("elementsEmptyReason", reason.isBlank() ? "no_collectable_elements" : reason);
            }
            JsonNode stepsForThisPage = stepEvidenceForPage(stepEvidence, pageKey, pageFingerprint);
            if (stepsForThisPage.size() > 0) bundle.set("stepEvidence", stepsForThisPage);
            appended++;
        }
        return appended;
    }

    /**
     * 把步骤证据分配到它所属的页面：
     * 优先按 resolvedTarget 的页面（目标所在页），其次按 before，最后按 after。
     * 编译器要求「步骤目标必须存在于其 before 状态所在页面的证据里」，
     * 因此跨页点击必须绑定到起始页，而不是简单地把所有步骤都堆到最终页。
     */
    static ArrayNode stepEvidenceForPage(JsonNode steps, String pageKey, String pageFingerprint) {
        ArrayNode subset = MAPPER.createArrayNode();
        if (steps == null || !steps.isArray() || pageKey == null || pageKey.isBlank()) return subset;
        for (JsonNode step : steps) {
            if (matchesStepIdentity(step, pageKey.strip(), pageFingerprint == null ? "" : pageFingerprint.strip())) {
                subset.add(step);
            }
        }
        return subset;
    }

    private static boolean matchesStepIdentity(JsonNode step, String pageKey, String pageFingerprint) {
        if (step == null || !step.isObject()) return false;
        // 只用 resolvedTarget / before 定位步骤所属页面：after 属于「动作到达的页面」，
        // 跨页点击的 after 是目标页，用它会让同一个步骤同时落进两个页面。
        for (String anchor : List.of("resolvedTarget", "before")) {
            JsonNode state = step.path(anchor);
            if (state == null || state.isMissingNode() || state.isNull()) continue;
            String candidateKey = state.path("pageKey").asText("").strip();
            if (candidateKey.isBlank() || !candidateKey.equals(pageKey)) continue;
            String candidateFingerprint = state.path("pageFingerprint").asText("").strip();
            if (pageFingerprint.isBlank() || candidateFingerprint.isBlank() || candidateFingerprint.equals(pageFingerprint)) {
                return true;
            }
        }
        return false;
    }

    private static String firstText(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        for (String field : fields) {
            String value = node.path(field).asText("").strip();
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static String blankToDefault(String value) {
        return value == null ? "" : value;
    }
}
