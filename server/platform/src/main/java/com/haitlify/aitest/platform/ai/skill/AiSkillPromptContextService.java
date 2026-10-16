package com.haitlify.aitest.platform.ai.skill;

import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class AiSkillPromptContextService {

    private final AiSkillService aiSkillService;
    private final ObjectMapper objectMapper;

    AiSkillPromptContextService(AiSkillService aiSkillService, ObjectMapper objectMapper) {
        this.aiSkillService = aiSkillService;
        this.objectMapper = objectMapper;
    }

    public ObjectNode resolve(String skillId) {
        AiSkillResponse skill = aiSkillService.getSkill(skillId);
        // T09 bullet 5：禁用影响新任务——禁用 Skill 不再解析出可用的 prompt/工具上下文（fail-closed）。
        if (!skill.enabled()) {
            throw new IllegalArgumentException("AI skill is disabled: " + skillId);
        }
        ObjectNode context = objectMapper.createObjectNode();
        context.put("skillId", skill.skillId());
        context.put("version", skill.version());
        context.put("name", skill.name());
        context.put("enabled", skill.enabled());
        context.put("promptTemplate", skill.promptTemplate());
        context.set("outputJsonSchema", skill.outputJsonSchema());
        context.set("availableTools", skill.availableTools());
        context.set("examples", skill.examples());
        return context;
    }
}
