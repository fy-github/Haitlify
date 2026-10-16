package com.haitlify.aitest.platform.ai.tool.mcp;

import org.springframework.data.repository.ListCrudRepository;

interface AiMcpToolCallRepository extends ListCrudRepository<AiMcpToolCallRecord, Long> {

    long countByRunId(String runId);

    long countByRunIdAndParameterHash(String runId, String parameterHash);
}
