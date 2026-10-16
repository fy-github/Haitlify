package com.haitlify.aitest.platform.ai.tool.mcp;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("ai_tool_call_records")
class AiMcpToolCallRecord {

    @Id
    @Column("id")
    private Long id;
    @Column("call_id")
    private String callId;
    @Column("run_id")
    private String runId;
    @Column("project_id")
    private Long projectId;
    @Column("server_id")
    private String serverId;
    @Column("tool_name")
    private String toolName;
    @Column("read_only")
    private boolean readOnly;
    @Column("status")
    private String status;
    @Column("request_summary_json")
    private String requestSummaryJson;
    @Column("response_summary_json")
    private String responseSummaryJson;
    @Column("result_origin")
    private String resultOrigin;
    @Column("acceptance_state")
    private String acceptanceState;
    @Column("provider_call_id")
    private String providerCallId;
    @Column("parameter_hash")
    private String parameterHash;
    @Column("evidence_id")
    private String evidenceId;
    @Column("observed_at")
    private Instant observedAt;
    @Column("error_message")
    private String errorMessage;
    @Column("actor_id")
    private String actorId;

    @Column("node_id")
    private String nodeId;

    @Column("attempt_id")
    private String attemptId;
    @Column("created_at")
    private Instant createdAt;

    AiMcpToolCallRecord() {
    }

    AiMcpToolCallRecord(
        Long id,
        String callId,
        String runId,
        Long projectId,
        String serverId,
        String toolName,
        boolean readOnly,
        String status,
        String requestSummaryJson,
        String responseSummaryJson,
        String resultOrigin,
        String acceptanceState,
        String providerCallId,
        String parameterHash,
        String evidenceId,
        Instant observedAt,
        String errorMessage,
        String actorId,
        String nodeId,
        String attemptId,
        Instant createdAt
    ) {
        this.id = id;
        this.callId = callId;
        this.runId = runId;
        this.projectId = projectId;
        this.serverId = serverId;
        this.toolName = toolName;
        this.readOnly = readOnly;
        this.status = status;
        this.requestSummaryJson = requestSummaryJson;
        this.responseSummaryJson = responseSummaryJson;
        this.resultOrigin = resultOrigin;
        this.acceptanceState = acceptanceState;
        this.providerCallId = providerCallId;
        this.parameterHash = parameterHash;
        this.evidenceId = evidenceId;
        this.observedAt = observedAt;
        this.errorMessage = errorMessage;
        this.actorId = actorId;
        this.nodeId = nodeId;
        this.attemptId = attemptId;
        this.createdAt = createdAt;
    }

    Long getId() {
        return id;
    }

    String getCallId() {
        return callId;
    }

    String getRunId() {
        return runId;
    }

    Long getProjectId() {
        return projectId;
    }

    String getServerId() {
        return serverId;
    }

    String getToolName() {
        return toolName;
    }

    boolean isReadOnly() {
        return readOnly;
    }

    String getStatus() {
        return status;
    }

    String getRequestSummaryJson() {
        return requestSummaryJson;
    }

    String getResponseSummaryJson() {
        return responseSummaryJson;
    }

    String getErrorMessage() {
        return errorMessage;
    }

    String getActorId() {
        return actorId;
    }

    String getNodeId() {
        return nodeId;
    }

    String getAttemptId() {
        return attemptId;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
