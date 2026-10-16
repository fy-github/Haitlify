package com.haitlify.aitest.platform.ai.agent;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!jira-readonly")
@RequestMapping("/api/ai/agents")
class AiAgentController {

    private final AiAgentService aiAgentService;

    AiAgentController(AiAgentService aiAgentService) {
        this.aiAgentService = aiAgentService;
    }

    @GetMapping
    List<AiAgentResponse> listAgents() {
        return aiAgentService.listAgents();
    }

    @PostMapping
    AiAgentResponse createCustomAgent(@RequestBody SaveCustomAiAgentRequest request) {
        return aiAgentService.createCustomAgent(request);
    }

    @GetMapping("/{agentId}")
    AiAgentResponse getAgent(@PathVariable String agentId) {
        return aiAgentService.getAgent(agentId);
    }

    @PutMapping("/{agentId}/override")
    AiAgentResponse saveOverride(@PathVariable String agentId, @RequestBody SaveAiAgentOverrideRequest request) {
        return aiAgentService.saveOverride(agentId, request);
    }

    @DeleteMapping("/{agentId}/override")
    AiAgentResponse deleteOverride(@PathVariable String agentId) {
        aiAgentService.deleteOverride(agentId);
        return aiAgentService.getAgent(agentId);
    }
}
