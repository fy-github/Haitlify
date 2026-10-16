package com.haitlify.aitest.platform.ai.figma;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class AiFigmaTestPointMapperTest {

    private AiFigmaEvidenceService evidenceService;
    private AiFigmaTestPointMapper mapper;
    private AiFigmaFile file;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        evidenceService = new AiFigmaEvidenceService(objectMapper);
        mapper = new AiFigmaTestPointMapper(evidenceService);
        Instant now = Instant.now();
        file = new AiFigmaFile(
            42L,
            "figma-file-42",
            7L,
            "FileKey123",
            "1:1",
            "Login Flow",
            "hash-figma",
            "{}",
            "system-dev",
            "Development User",
            now,
            now
        );
    }

    @Test
    void mapsFrameHierarchyToPageLayoutPoint() {
        AiFigmaNode frame = node("node-frame", "1:1", "FRAME", "Login Page", "", "", "", "{}", "{}");
        AiFigmaNode child = node("node-button", "1:2", "INSTANCE", "Submit Button", "1:1", "button-primary", "Button / Primary", "{}", "{}");

        var points = mapper.map(file, List.of(frame, child), List.of(), List.of());

        assertThat(points).anySatisfy(point -> {
            assertThat(point.testType()).isEqualTo("页面/布局测试点");
            assertThat(point.title()).contains("Login Page");
            assertThat(point.evidence().sourceType()).isEqualTo("figma");
            assertThat(point.evidence().fileKey()).isEqualTo("FileKey123");
            assertThat(point.evidence().nodeId()).isEqualTo("1:1");
            assertThat(point.evidence().nodeType()).isEqualTo("FRAME");
        });
    }

    @Test
    void mapsButtonAndInputComponentsToInteractionAndFormFieldPoints() {
        AiFigmaNode button = node("node-button", "2:1", "INSTANCE", "Submit Button", "", "button-primary", "Button / Primary", "{}", "{}");
        AiFigmaNode input = node("node-input", "2:2", "INSTANCE", "Email Input", "", "input-email", "Input / Text Field", "{}", "{}");

        var points = mapper.map(file, List.of(button, input), List.of(), List.of());

        assertThat(points).anySatisfy(point -> {
            assertThat(point.testType()).isEqualTo("组件测试点");
            assertThat(point.title()).contains("Submit Button");
            assertThat(point.objective()).contains("可点击");
            assertThat(point.evidence().componentName()).isEqualTo("Button / Primary");
        });
        assertThat(points).anySatisfy(point -> {
            assertThat(point.testType()).isEqualTo("表单字段测试点");
            assertThat(point.title()).contains("Email Input");
            assertThat(point.objective()).contains("输入");
            assertThat(point.evidence().nodeId()).isEqualTo("2:2");
        });
    }

    @Test
    void mapsVariantStateToComponentStatePoint() {
        AiFigmaNode variant = node(
            "node-state",
            "3:1",
            "INSTANCE",
            "Primary Button",
            "",
            "button-primary",
            "Button / Primary",
            "{\"State\":{\"value\":\"Disabled\"}}",
            "{}"
        );

        var points = mapper.map(file, List.of(variant), List.of(), List.of());

        assertThat(points).anySatisfy(point -> {
            assertThat(point.testType()).isEqualTo("状态测试点");
            assertThat(point.title()).contains("Primary Button", "状态");
            assertThat(point.objective()).contains("Disabled");
            assertThat(point.evidence().nodeId()).isEqualTo("3:1");
        });
    }

    @Test
    void mapsPrototypeLinkToNavigationFlowPoint() {
        AiFigmaPrototypeLink link = new AiFigmaPrototypeLink(
            "4:1",
            "4:2",
            "ON_CLICK",
            "NODE",
            "SMART_ANIMATE",
            300,
            new ObjectMapper().createObjectNode()
        );

        var points = mapper.map(file, List.of(node("source", "4:1", "INSTANCE", "Next Button", "", "button-next", "Button / Next", "{}", "{}")), List.of(link), List.of());

        assertThat(points).anySatisfy(point -> {
            assertThat(point.testType()).isEqualTo("交互流程测试点");
            assertThat(point.title()).contains("4:1", "4:2");
            assertThat(point.evidence().prototypeLinkId()).contains("4:1", "4:2");
            assertThat(point.evidence().fileKey()).isEqualTo("FileKey123");
        });
    }

    @Test
    void mapsStyleTokenToVisualConsistencyPoint() {
        AiFigmaStyleToken token = new AiFigmaStyleToken("color", "Login Page fill #3366FF", new ObjectMapper().createObjectNode());

        var points = mapper.map(file, List.of(), List.of(), List.of(token));

        assertThat(points).anySatisfy(point -> {
            assertThat(point.testType()).isEqualTo("视觉样式测试点");
            assertThat(point.title()).contains("Login Page fill #3366FF");
            assertThat(point.evidence().styleTokenId()).contains("color", "Login Page fill #3366FF");
            assertThat(point.evidence().sourceType()).isEqualTo("figma");
        });
    }

    private AiFigmaNode node(
        String figmaNodeId,
        String nodeId,
        String nodeType,
        String nodeName,
        String parentNodeId,
        String componentKey,
        String componentName,
        String variantPropertiesJson,
        String styleJson
    ) {
        Instant now = Instant.now();
        return new AiFigmaNode(
            null,
            figmaNodeId,
            42L,
            nodeId,
            nodeType,
            nodeName,
            parentNodeId,
            componentKey,
            componentName,
            variantPropertiesJson,
            styleJson,
            "{}",
            "",
            "{}",
            "{}",
            "{}",
            "hash-figma",
            now,
            now
        );
    }
}
