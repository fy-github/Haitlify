package com.haitlify.aitest.platform.webautomation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

/**
 * 阶段 3：runner 采到的步骤级证据必须原样通过平台 DTO，不能在解析层被丢弃。
 *
 * 载荷都来自 server/web-runner/scripts/record-phase3-evidence.mjs 的真实运行输出：
 * - phase3-runner-response.json：iframe + open shadow 页面，验证 evidenceId / locatorSpec / framePath / shadowPath
 * - phase3-runner-action-response.json：跨页 Action IR 运行，验证 stepEvidence 数组
 * - phase3-runner-navigate-response.json：导航型跨页，验证 pageSnapshots 与地址断言步骤
 */
class WebAutomationLocatorDiscoveryEvidencePayloadTest {

    private static final String SCOPED_PAYLOAD = "/ai/evidence/phase3-runner-response.json";
    private static final String ACTION_PAYLOAD = "/ai/evidence/phase3-runner-action-response.json";
    private static final String NAVIGATE_PAYLOAD = "/ai/evidence/phase3-runner-navigate-response.json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void preservesEvidenceIdsLocatorSpecsAndScopesFromTheRunnerPayload() throws Exception {
        WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse response = parse(SCOPED_PAYLOAD);

        var frameElement = response.elements().stream()
            .filter(element -> "#inner".equals(element.framePath()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("iframe 内元素必须保留 framePath"));
        assertThat(frameElement.evidenceId()).startsWith("element-");
        assertThat(frameElement.locator()).startsWith("frame=#inner >> ");
        assertThat(frameElement.locatorSpec().path("strategy").asText()).isEqualTo("role");
        assertThat(frameElement.locatorSpec().path("name").asText()).isEqualTo("帧内按钮");

        var shadowElement = response.elements().stream()
            .filter(element -> "#host".equals(element.shadowPath()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("open shadow 内元素必须保留 shadowPath"));
        assertThat(shadowElement.locator()).startsWith("css=#host >> ");
        assertThat(shadowElement.locatorSpec().path("selector").asText()).isEqualTo("#shadow-btn");

        assertThat(response.elements()).allSatisfy(element -> assertThat(element.evidenceId()).startsWith("element-"));
    }

    @Test
    void preservesStepEvidenceFromTheRunnerPayload() throws Exception {
        WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse response = parse(ACTION_PAYLOAD);

        assertThat(response.stepEvidence()).isNotNull();
        assertThat(response.stepEvidence().isArray()).isTrue();
        assertThat(response.stepEvidence().size()).isEqualTo(2);
        assertThat(response.stepEvidence().path(0).path("actionId").asText()).isEqualTo("open-detail");
        assertThat(response.stepEvidence().path(1).path("actionId").asText()).isEqualTo("assert-detail-title");
        assertThat(response.stepEvidence().path(0).path("resolvedTarget").path("locator").asText()).isNotBlank();
    }

    @Test
    void preservesUnsupportedDiagnosticsAndPageSnapshotsFromTheRunnerPayload() throws Exception {
        WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse response = parse(NAVIGATE_PAYLOAD);

        // 不可观测子树的诊断必须活着到平台侧：否则「某棵子树根本没被采集」在传输中丢失，
        // 平台只能间接报错，无法提示真实原因。
        assertThat(response.unsupported()).isNotNull();
        assertThat(response.unsupported().path("frames").isArray()).isTrue();
        assertThat(response.unsupported().path("closedShadowHosts").isArray()).isTrue();
        assertThat(response.unsupported().path("unresolvedShadowHosts").isArray()).isTrue();

        // 导航型跨页的两页快照都要在：第 1 步没有元素引用，丢掉起始页会让用例编译失败。
        assertThat(response.pageSnapshots()).isNotNull();
        assertThat(response.pageSnapshots().size()).isGreaterThanOrEqualTo(2);
        assertThat(response.pageSnapshots()).allSatisfy(snapshot -> assertThat(snapshot.path("elements").isArray()).isTrue());
        assertThat(response.stepEvidence().size()).isEqualTo(3);
        assertThat(response.stepEvidence().path(1).path("actionId").asText()).isEqualTo("assert-detail-url");
    }

    private WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse parse(String resource) throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(resource)) {
            assertThat(stream).as("阶段 3 真实载荷必须存在：" + resource).isNotNull();
            return objectMapper.readValue(stream, WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse.class);
        }
    }
}
