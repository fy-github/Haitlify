package com.haitlify.aitest.platform.document;

import java.util.List;

public record RequirementVisualSummary(
    int width,
    int height,
    double aspectRatio,
    String viewportHint,
    List<String> layoutHints,
    List<String> styleHints,
    List<String> componentHints,
    String ocrStatus
) {
}
