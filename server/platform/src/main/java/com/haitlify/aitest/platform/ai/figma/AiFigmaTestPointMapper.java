package com.haitlify.aitest.platform.ai.figma;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AiFigmaTestPointMapper {

    private final AiFigmaEvidenceService evidenceService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    AiFigmaTestPointMapper(AiFigmaEvidenceService evidenceService) {
        this.evidenceService = evidenceService;
    }

    List<AiFigmaDraftTestPointCandidate> map(
        AiFigmaFile file,
        List<AiFigmaNode> nodes,
        List<AiFigmaPrototypeLink> prototypeLinks,
        List<AiFigmaStyleToken> styleTokens
    ) {
        List<AiFigmaDraftTestPointCandidate> points = new ArrayList<>();
        for (AiFigmaNode node : nodes) {
            addNodePoints(file, node, points);
        }
        for (AiFigmaPrototypeLink link : prototypeLinks) {
            points.add(prototypePoint(file, link));
        }
        for (AiFigmaStyleToken token : styleTokens) {
            points.add(stylePoint(file, token));
        }
        return points;
    }

    private void addNodePoints(AiFigmaFile file, AiFigmaNode node, List<AiFigmaDraftTestPointCandidate> points) {
        if ("FRAME".equals(node.getNodeType())) {
            points.add(new AiFigmaDraftTestPointCandidate(
                node.getNodeName() + " 页面布局符合 Figma 结构",
                "Figma 设计稿",
                node.getNodeName(),
                "web_automation",
                "页面/布局测试点",
                "验证页面层级、区域顺序、间距和响应式布局与 Figma 节点一致",
                "打开页面并对照 Figma 节点 " + node.getNodeId() + " 检查主要区域和子组件层级",
                "页面布局、组件层级和关键区域可见性符合设计稿",
                evidenceService.fromNode(file, node)
            ));
        }
        if (isInput(node)) {
            points.add(new AiFigmaDraftTestPointCandidate(
                node.getNodeName() + " 表单字段可输入",
                "Figma 设计稿",
                featureName(node),
                "web_automation",
                "表单字段测试点",
                "验证 " + node.getNodeName() + " 输入控件可见、可输入并按设计状态反馈",
                "定位 Figma 节点 " + node.getNodeId() + " 对应输入框，输入有效与无效内容",
                "输入内容、校验提示和控件状态符合设计稿",
                evidenceService.fromNode(file, node)
            ));
        } else if (isButton(node)) {
            points.add(new AiFigmaDraftTestPointCandidate(
                node.getNodeName() + " 组件可点击",
                "Figma 设计稿",
                featureName(node),
                "web_automation",
                "组件测试点",
                "验证 " + node.getNodeName() + " 按钮可见、可点击并展示正确反馈",
                "定位 Figma 节点 " + node.getNodeId() + " 对应按钮并执行点击",
                "按钮文案、状态和点击反馈符合设计稿",
                evidenceService.fromNode(file, node)
            ));
        }
        String variantSummary = variantSummary(node.getVariantPropertiesJson());
        if (!variantSummary.isBlank()) {
            points.add(new AiFigmaDraftTestPointCandidate(
                node.getNodeName() + " 状态与 Figma 变体一致",
                "Figma 设计稿",
                featureName(node),
                "web_automation",
                "状态测试点",
                "验证组件变体状态 " + variantSummary + " 在页面中可达且展示一致",
                "切换或触发 Figma 节点 " + node.getNodeId() + " 对应状态",
                "组件状态、样式和交互限制符合 Figma 变体定义",
                evidenceService.fromNode(file, node)
            ));
        }
    }

    private AiFigmaDraftTestPointCandidate prototypePoint(AiFigmaFile file, AiFigmaPrototypeLink link) {
        return new AiFigmaDraftTestPointCandidate(
            "Figma 流程 " + link.sourceNodeId() + " 到 " + link.targetNodeId() + " 可达",
            "Figma 设计稿",
            "原型流程",
            "web_automation",
            "交互流程测试点",
            "验证 " + link.eventType() + " 触发 " + link.actionType() + " 后进入目标节点",
            "从节点 " + link.sourceNodeId() + " 触发交互并观察目标 " + link.targetNodeId(),
            "页面流转、动画和目标状态符合 Figma prototype 定义",
            evidenceService.fromPrototype(file, link)
        );
    }

    private AiFigmaDraftTestPointCandidate stylePoint(AiFigmaFile file, AiFigmaStyleToken token) {
        return new AiFigmaDraftTestPointCandidate(
            token.tokenName() + " 样式符合设计 token",
            "Figma 设计稿",
            "视觉样式",
            "web_automation",
            "视觉样式测试点",
            "验证页面实现中的 " + token.tokenCategory() + " 样式与 Figma token 一致",
            "检查对应组件的颜色、字体、间距或视觉效果",
            "实现样式与 Figma token 保持一致",
            evidenceService.fromStyleToken(file, token)
        );
    }

    private boolean isButton(AiFigmaNode node) {
        String value = (node.getNodeName() + " " + node.getComponentName()).toLowerCase();
        return value.contains("button") || value.contains("按钮");
    }

    private boolean isInput(AiFigmaNode node) {
        String value = (node.getNodeName() + " " + node.getComponentName()).toLowerCase();
        return value.contains("input") || value.contains("field") || value.contains("输入");
    }

    private String featureName(AiFigmaNode node) {
        return node.getComponentName().isBlank() ? node.getNodeName() : node.getComponentName();
    }

    private String variantSummary(String variantPropertiesJson) {
        if (variantPropertiesJson == null || variantPropertiesJson.isBlank() || "{}".equals(variantPropertiesJson)) {
            return "";
        }
        try {
            JsonNode properties = objectMapper.readTree(variantPropertiesJson);
            List<String> values = new ArrayList<>();
            for (Map.Entry<String, JsonNode> fieldEntry : properties.properties()) {
                String field = fieldEntry.getKey();
                String value = fieldEntry.getValue().path("value").asText("");
                if (!value.isBlank()) {
                    values.add(field + "=" + value);
                }
            }
            return String.join(", ", values);
        } catch (Exception exception) {
            return "";
        }
    }
}

record AiFigmaDraftTestPointCandidate(
    String title,
    String module,
    String pageOrFeature,
    String caseKind,
    String testType,
    String objective,
    String steps,
    String expectedResult,
    AiFigmaRequirementEvidence evidence
) {
}
