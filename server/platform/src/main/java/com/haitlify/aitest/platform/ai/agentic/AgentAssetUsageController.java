package com.haitlify.aitest.platform.ai.agentic;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.haitlify.aitest.platform.ai.agentic.locator.LocatorKnowledgeService;
import com.haitlify.aitest.platform.ai.agentic.memory.AgentMemoryService;

@RestController
@Profile("!jira-readonly")
@RequestMapping("/api/ai/agent-assets/usage")
class AgentAssetUsageController {
    private final LocatorKnowledgeService locatorKnowledge;
    private final AgentMemoryService memories;

    AgentAssetUsageController(LocatorKnowledgeService locatorKnowledge, AgentMemoryService memories) {
        this.locatorKnowledge = locatorKnowledge;
        this.memories = memories;
    }

    @PostMapping("/outcome")
    void recordOutcome(@RequestBody AssetExecutionOutcomeRequest request) {
        if (request.runId() == null || request.runId().isBlank() || request.pointId() == null || request.pointId().isBlank()) throw new IllegalArgumentException("任务和测试点不能为空");
        locatorKnowledge.recordExecutionOutcome(request.runId(), request.pointId(), request.succeeded());
        memories.recordExecutionOutcome(request.runId(), request.pointId(), request.succeeded());
    }
}

record AssetExecutionOutcomeRequest(String runId, String pointId, boolean succeeded) {
}
