package com.haitlify.aitest.platform.ai.rule;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!jira-readonly")
@RequestMapping("/api/ai/rules")
class AiRuleController {

    private final AiRuleService aiRuleService;

    AiRuleController(AiRuleService aiRuleService) {
        this.aiRuleService = aiRuleService;
    }

    @GetMapping
    EffectiveAiRule getRules(@RequestParam(required = false) Long projectId) {
        return aiRuleService.getRules(projectId);
    }

    @PutMapping
    EffectiveAiRule saveRules(
        @RequestParam(required = false) Long projectId,
        @RequestBody SaveAiRuleRequest request
    ) {
        return aiRuleService.saveRules(projectId, request);
    }
}
