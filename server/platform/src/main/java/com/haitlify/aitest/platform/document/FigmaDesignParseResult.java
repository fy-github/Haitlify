package com.haitlify.aitest.platform.document;

import java.util.List;

public record FigmaDesignParseResult(
    String fileName,
    String projectName,
    String text,
    RequirementVisualSummary visualSummary,
    List<String> suggestedRequirementPoints
) {
}
