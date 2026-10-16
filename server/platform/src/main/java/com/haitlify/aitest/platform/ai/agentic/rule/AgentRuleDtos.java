package com.haitlify.aitest.platform.ai.agentic.rule;

import java.time.Instant;
import java.util.Map;

import com.haitlify.aitest.platform.ai.agentic.WebCaseAgentModels.AgentRuleSnapshot;

record SaveAgentRuleRequest(String contentText, Map<String, String> phaseRules) {
}

record PublishAgentRuleRequest(int version) {
}

record RollbackAgentRuleRequest(int version) {
}

record AgentRuleVersionResponse(
    String ruleSetId,
    String scope,
    Long projectId,
    int version,
    String status,
    String contentText,
    Map<String, String> phaseRules,
    String contentHash,
    Instant publishedAt,
    Instant updatedAt
) {
    AgentRuleSnapshot snapshot() {
        return new AgentRuleSnapshot(ruleSetId, scope, projectId, version, contentHash, phaseRules);
    }
}

record AgentRuleDiffResponse(
    String ruleSetId,
    int fromVersion,
    int toVersion,
    String fromContent,
    String toContent
) {
}
