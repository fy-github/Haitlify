package com.haitlify.aitest.platform.ai.automation;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!jira-readonly")
@RequestMapping("/api/ai/multi-agent-runs")
class AiAutomationSuggestionController {

    private final AiAutomationSuggestionService suggestionService;

    AiAutomationSuggestionController(AiAutomationSuggestionService suggestionService) {
        this.suggestionService = suggestionService;
    }

    @GetMapping("/{runId}/automation-suggestions")
    AiAutomationSuggestionListResponse listSuggestions(@PathVariable String runId) {
        return suggestionService.listSuggestions(runId);
    }
}
