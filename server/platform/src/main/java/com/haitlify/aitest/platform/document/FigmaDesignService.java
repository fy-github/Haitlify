package com.haitlify.aitest.platform.document;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

@Service
class FigmaDesignService {

    private static final Pattern FILE_KEY_PATTERN = Pattern.compile("figma\\.com/(?:design|file)/([^/?]+)");
    private static final Pattern NODE_ID_PATTERN = Pattern.compile("[?&]node-id=([^&]+)");

    private final FigmaDesignClient figmaDesignClient;

    FigmaDesignService(FigmaDesignClient figmaDesignClient) {
        this.figmaDesignClient = figmaDesignClient;
    }

    RequirementDocumentParseResponse parse(String url) {
        String fileKey = extract(url, FILE_KEY_PATTERN);
        String nodeId = extract(url, NODE_ID_PATTERN).replace('-', ':');

        try {
            FigmaDesignParseResult parsed = figmaDesignClient.fetchDesign(fileKey, nodeId);
            return new RequirementDocumentParseResponse(
                parsed.fileName(),
                "figma",
                parsed.projectName(),
                "Figma 设计链接: " + url + "\n" + parsed.text(),
                2,
                parsed.visualSummary(),
                parsed.suggestedRequirementPoints()
            );
        } catch (FigmaDesignUnavailableException exception) {
            RequirementVisualSummary visualSummary = new RequirementVisualSummary(
                0,
                0,
                0D,
                "unknown",
                List.of("待通过 Figma API 提取 Auto Layout 与 Frame 结构"),
                List.of("待通过 Figma API 提取颜色、字体、圆角、阴影 token"),
                List.of("待通过 Figma API 提取组件树"),
                "unavailable"
            );
            return new RequirementDocumentParseResponse(
                "figma-link",
                "figma",
                "默认项目",
                "Figma 设计链接: " + url + "\nFigma File Key: " + fileKey + "\nFigma Node ID: " + nodeId + "\n当前未配置 Figma Token，已先生成设计解析草稿。",
                4,
                visualSummary,
                List.of(
                    "布局结构、组件层级、间距与对齐符合 Figma 设计稿",
                    "颜色、字体、圆角、阴影等视觉样式符合设计规范",
                    "组件状态与交互入口在设计稿覆盖的关键场景下表现一致"
                )
            );
        }
    }

    private String extract(String url, Pattern pattern) {
        Matcher matcher = pattern.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }
}
