package com.haitlify.aitest.platform.ai.agentic.memory;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.haitlify.aitest.platform.ai.agentic.WebCaseAgentModels.AgentMemoryResponse;
import org.springframework.web.bind.annotation.PutMapping;

@RestController
@Profile("!jira-readonly")
@RequestMapping("/api/ai/agent-assets/memories")
class AgentMemoryController {
    private final AgentMemoryService service;
    AgentMemoryController(AgentMemoryService service) { this.service = service; }
    @GetMapping List<AgentMemoryResponse> list(@RequestParam Long projectId, @RequestParam(defaultValue = "false") boolean pendingOnly) { return service.list(projectId, pendingOnly); }
    @PostMapping AgentMemoryResponse create(@RequestBody CreateMemoryRequest request) { return service.createCandidate(request); }
    @PutMapping("/{memoryId}") AgentMemoryResponse update(@PathVariable String memoryId, @RequestBody UpdateMemoryRequest request) { return service.update(memoryId, request); }
    @PostMapping("/{memoryId}/confirm") AgentMemoryResponse confirm(@PathVariable String memoryId, @RequestBody ConfirmMemoryRequest request) { return service.confirm(memoryId, request); }
    @PostMapping("/{memoryId}/disable") void disable(@PathVariable String memoryId) { service.disable(memoryId); }
    @DeleteMapping("/{memoryId}") void delete(@PathVariable String memoryId) { service.delete(memoryId); }
}
