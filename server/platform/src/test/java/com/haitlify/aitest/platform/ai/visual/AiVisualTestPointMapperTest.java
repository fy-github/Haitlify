package com.haitlify.aitest.platform.ai.visual;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class AiVisualTestPointMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiVisualTestPointMapper mapper = new AiVisualTestPointMapper(objectMapper);

    @Test
    void buttonComponentGeneratesVisualComponentTestPoint() throws Exception {
        List<VisualDraftTestPointCandidate> points = mapper.map(responseWith(
            objectMapper.readTree("{\"pageType\":\"login\"}"),
            List.of(region("button", "提交", "primary button", "region-button-1")),
            List.of(),
            List.of()
        ));

        assertThat(points).anySatisfy(point -> {
            assertThat(point.title()).contains("提交");
            assertThat(point.testType()).isEqualTo("组件显示/状态测试点");
            assertThat(point.sourceRegionId()).isEqualTo("region-button-1");
            assertThat(point.inferred()).isFalse();
        });
    }

    @Test
    void formFieldGeneratesRequiredUiBehaviorTestPoint() throws Exception {
        List<VisualDraftTestPointCandidate> points = mapper.map(responseWith(
            objectMapper.readTree("{\"pageType\":\"login\"}"),
            List.of(region("field", "手机号", "phone input", "region-field-1")),
            List.of(),
            List.of()
        ));

        assertThat(points).anySatisfy(point -> {
            assertThat(point.title()).contains("手机号");
            assertThat(point.testType()).isEqualTo("组件显示/状态测试点");
            assertThat(point.objective()).contains("输入");
            assertThat(point.sourceRegionId()).isEqualTo("region-field-1");
        });
    }

    @Test
    void layoutHierarchyGeneratesResponsiveStabilityTestPoint() throws Exception {
        List<VisualDraftTestPointCandidate> points = mapper.map(responseWith(
            objectMapper.readTree("{\"pageType\":\"dashboard\",\"hierarchy\":[\"navigation\",\"table\"]}"),
            List.of(),
            List.of(),
            List.of()
        ));

        assertThat(points).anySatisfy(point -> {
            assertThat(point.testType()).isEqualTo("UI布局测试点");
            assertThat(point.objective()).contains("响应式");
        });
    }

    @Test
    void inferredInteractionCreatesInferredTestPoint() throws Exception {
        List<VisualDraftTestPointCandidate> points = mapper.map(responseWith(
            objectMapper.readTree("{\"pageType\":\"login\"}"),
            List.of(region("button", "提交", "primary button", "region-button-1")),
            List.of("submit navigates to dashboard"),
            List.of(new VisualSuggestedTestPoint("提交后进入工作台", "interaction", "region-button-1"))
        ));

        assertThat(points).anySatisfy(point -> {
            assertThat(point.title()).contains("提交后进入工作台");
            assertThat(point.testType()).isEqualTo("交互流程测试点");
            assertThat(point.sourceRegionId()).isEqualTo("region-button-1");
            assertThat(point.inferred()).isTrue();
        });
    }

    private AiVisualAnalysisResponse responseWith(
        tools.jackson.databind.JsonNode layout,
        List<AiVisualRegionResponse> regions,
        List<String> interactions,
        List<VisualSuggestedTestPoint> suggested
    ) {
        return new AiVisualAnalysisResponse(
            "analysis-1",
            "vis-1",
            "succeeded",
            layout,
            regions,
            interactions,
            suggested,
            objectMapper.createArrayNode()
        );
    }

    private AiVisualRegionResponse region(String type, String text, String hint, String regionId) {
        return new AiVisualRegionResponse(regionId, "vis-1", type, 0, 0, 120, 40, text, hint, List.of(), 0.9);
    }
}
