package com.haitlify.aitest.platform.ai.skill;

import tools.jackson.databind.JsonNode;

record AiSkillDefinition(
    String skillId,
    String name,
    String description,
    JsonNode tags,
    JsonNode applicableCaseKinds,
    JsonNode applicableFileTypes,
    String promptTemplate,
    JsonNode outputJsonSchema,
    JsonNode availableTools,
    JsonNode examples,
    boolean enabled,
    boolean builtIn,
    int version
) {
}
