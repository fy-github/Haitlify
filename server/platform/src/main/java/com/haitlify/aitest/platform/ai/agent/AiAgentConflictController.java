package com.haitlify.aitest.platform.ai.agent;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!jira-readonly")
@RequestMapping("/api/ai/agent-conflicts")
class AiAgentConflictController {

    private final AiAgentConflictService conflictService;

    AiAgentConflictController(AiAgentConflictService conflictService) {
        this.conflictService = conflictService;
    }

    @PostMapping("/{conflictId}/resolve")
    AiAgentConflictResponse resolve(@PathVariable String conflictId, @RequestBody ResolveAgentConflictRequest request) {
        return conflictService.resolve(conflictId, request);
    }

    @PostMapping("/{conflictId}/ignore")
    AiAgentConflictResponse ignore(@PathVariable String conflictId, @RequestBody ResolveAgentConflictRequest request) {
        return conflictService.ignore(conflictId, request);
    }
}
