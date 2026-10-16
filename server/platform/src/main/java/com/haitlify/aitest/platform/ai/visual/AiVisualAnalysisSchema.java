package com.haitlify.aitest.platform.ai.visual;

import java.util.List;

import tools.jackson.databind.JsonNode;

class AiVisualAnalysisSchema {

    private static final List<String> REQUIRED_FIELDS = List.of(
        "layout",
        "components",
        "styles",
        "interactions",
        "states",
        "risks",
        "suggestedTestPoints",
        "unclearQuestions"
    );

    void validate(JsonNode output) {
        if (output == null || !output.isObject()) {
            throw new IllegalArgumentException("Invalid visual analysis output: root must be an object");
        }
        for (String field : REQUIRED_FIELDS) {
            if (!output.has(field) || output.get(field).isNull()) {
                throw new IllegalArgumentException("Invalid visual analysis output: missing " + field);
            }
        }
        if (!output.path("layout").isObject()) {
            throw new IllegalArgumentException("Invalid visual analysis output: layout must be an object");
        }
        if (!output.path("components").isArray()) {
            throw new IllegalArgumentException("Invalid visual analysis output: components must be an array");
        }
        if (!output.path("suggestedTestPoints").isArray()) {
            throw new IllegalArgumentException("Invalid visual analysis output: suggestedTestPoints must be an array");
        }
    }
}
