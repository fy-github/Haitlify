package com.haitlify.aitest.platform.ai.agentic.rule;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!jira-readonly")
@RequestMapping("/api/ai/agent-assets/rules")
class AgentRuleController {

    private final AgentRuleService service;

    AgentRuleController(AgentRuleService service) { this.service = service; }

    @GetMapping
    AgentRuleVersionResponse current(@RequestParam(required = false) Long projectId) { return service.current(projectId); }

    @GetMapping("/{ruleSetId}/versions")
    List<AgentRuleVersionResponse> versions(@PathVariable String ruleSetId, @RequestParam(required = false) Long projectId) { return service.versions(projectId); }

    @GetMapping("/{ruleSetId}/diff")
    AgentRuleDiffResponse diff(@PathVariable String ruleSetId, @RequestParam int from, @RequestParam int to) { return service.diff(from, to); }

    @PostMapping("/{ruleSetId}/draft")
    AgentRuleVersionResponse draft(@PathVariable String ruleSetId, @RequestParam(required = false) Long projectId, @RequestBody SaveAgentRuleRequest request) { return service.saveDraft(projectId, request); }

    @PostMapping("/{ruleSetId}/publish")
    AgentRuleVersionResponse publish(@PathVariable String ruleSetId, @RequestParam(required = false) Long projectId, @RequestBody PublishAgentRuleRequest request) { return service.publish(projectId, request); }

    @PostMapping("/{ruleSetId}/rollback")
    AgentRuleVersionResponse rollback(@PathVariable String ruleSetId, @RequestParam(required = false) Long projectId, @RequestBody RollbackAgentRuleRequest request) { return service.rollback(projectId, request); }
}
