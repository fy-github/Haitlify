package com.haitlify.aitest.platform.ai.agentic.memory;

import java.util.List;

import com.haitlify.aitest.platform.ai.agentic.WebCaseAgentModels.AgentMemoryScopeType;

record ConfirmMemoryRequest(AgentMemoryScopeType scopeType, List<Long> projectIds, Integer revision) {
    ConfirmMemoryRequest(AgentMemoryScopeType scopeType, List<Long> projectIds) {
        this(scopeType, projectIds, null);
    }
}

record CreateMemoryRequest(Long projectId, String sourceRunId, String moduleName, String category, String content, int rulesVersion) {
}

record UpdateMemoryRequest(String moduleName, String category, String content, AgentMemoryScopeType scopeType,
    List<Long> projectIds, Integer revision) {
    UpdateMemoryRequest(String moduleName, String category, String content, AgentMemoryScopeType scopeType,
        List<Long> projectIds) {
        this(moduleName, category, content, scopeType, projectIds, null);
    }
}
