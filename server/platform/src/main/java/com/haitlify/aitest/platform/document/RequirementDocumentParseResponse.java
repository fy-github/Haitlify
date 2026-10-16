package com.haitlify.aitest.platform.document;

import java.util.List;

/**
 * T08：需求文件解析结果——逐来源状态（AVAILABLE/PARTIAL/UNSUPPORTED）、warnings 与
 * 表格/图片计数。不支持/部分解析必须显式呈现，绝不静默忽略。
 */
public record RequirementDocumentParseResponse(
    String fileName,
    String documentType,
    String projectName,
    String text,
    int lineCount,
    RequirementVisualSummary visualSummary,
    java.util.List<String> suggestedRequirementPoints,
    String parseStatus,
    List<String> warnings,
    int tableCount,
    int imageCount
) {

    /** 兼容构造器（Figma/旧调用方）：无富状态时按文本可用性给出保守默认。 */
    public RequirementDocumentParseResponse(
        String fileName,
        String documentType,
        String projectName,
        String text,
        int lineCount,
        RequirementVisualSummary visualSummary,
        java.util.List<String> suggestedRequirementPoints
    ) {
        this(fileName, documentType, projectName, text, lineCount, visualSummary,
            suggestedRequirementPoints,
            text != null && !text.isBlank() ? "AVAILABLE" : "PARTIAL",
            text != null && !text.isBlank() ? List.of() : List.of("no extractable text"),
            0, 0);
    }
}
