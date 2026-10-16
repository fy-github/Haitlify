package com.haitlify.aitest.platform.ai.visual;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.ObjectMapper;

class AiVisualTestPointMapper {

    @SuppressWarnings("unused")
    private final ObjectMapper objectMapper;

    AiVisualTestPointMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    List<VisualDraftTestPointCandidate> map(AiVisualAnalysisResponse analysis) {
        List<VisualDraftTestPointCandidate> points = new ArrayList<>();
        addLayoutPoint(analysis, points);
        for (AiVisualRegionResponse region : analysis.regions()) {
            points.add(componentPoint(region));
        }
        for (VisualSuggestedTestPoint suggested : analysis.suggestedTestPoints()) {
            points.add(new VisualDraftTestPointCandidate(
                suggested.title(),
                "视觉需求",
                pageType(analysis),
                "web_automation",
                testTypeForCategory(suggested.category()),
                "验证视觉稿中的交互假设: " + suggested.title(),
                "定位视觉区域并执行对应交互",
                "页面反馈与视觉需求保持一致",
                suggested.sourceRegionId(),
                "interaction".equals(suggested.category())
            ));
        }
        return points;
    }

    private void addLayoutPoint(AiVisualAnalysisResponse analysis, List<VisualDraftTestPointCandidate> points) {
        if (analysis.layout() == null || analysis.layout().isMissingNode() || analysis.layout().isEmpty()) {
            return;
        }
        points.add(new VisualDraftTestPointCandidate(
            pageType(analysis) + " 页面布局稳定",
            "视觉需求",
            pageType(analysis),
            "web_automation",
            "UI布局测试点",
            "验证页面在不同视口下的响应式布局稳定性",
            "切换桌面和移动视口，检查主要区域层级、间距和可见性",
            "布局层级清晰，关键控件无重叠、无遮挡、无异常换行",
            "",
            false
        ));
    }

    private VisualDraftTestPointCandidate componentPoint(AiVisualRegionResponse region) {
        String label = !region.text().isBlank() ? region.text() : region.componentHint();
        String objective = switch (region.regionType()) {
            case "field" -> "验证 " + label + " 输入控件可见、可输入并保留状态";
            case "button" -> "验证 " + label + " 按钮显示、状态和可点击反馈";
            default -> "验证 " + label + " 组件显示和状态符合视觉需求";
        };
        return new VisualDraftTestPointCandidate(
            label + " 组件状态正确",
            "视觉需求",
            region.regionType(),
            "web_automation",
            "组件显示/状态测试点",
            objective,
            "打开页面并检查视觉区域 " + region.regionId(),
            "组件文案、位置、状态和样式符合视觉稿",
            region.regionId(),
            false
        );
    }

    private String testTypeForCategory(String category) {
        return switch (category) {
            case "layout" -> "UI布局测试点";
            case "style" -> "视觉样式测试点";
            case "interaction" -> "交互流程测试点";
            case "state" -> "空态/错误态/成功态测试点";
            default -> "组件显示/状态测试点";
        };
    }

    private String pageType(AiVisualAnalysisResponse analysis) {
        String pageType = analysis.layout().path("pageType").asText("");
        return pageType.isBlank() ? "视觉页面" : pageType;
    }
}

record VisualDraftTestPointCandidate(
    String title,
    String module,
    String pageOrFeature,
    String caseKind,
    String testType,
    String objective,
    String steps,
    String expectedResult,
    String sourceRegionId,
    boolean inferred
) {
}
