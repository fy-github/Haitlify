package com.haitlify.aitest.platform.ai.agent;

import java.time.Instant;

class AiAgentRunNode {

    private final Long id;
    private final String nodeId;
    private final Long graphPk;
    private final String agentId;
    private final String agentRole;
    private final int executionOrder;
    private final String status;
    private final String inputSnapshotJson;
    private final String outputSnapshotJson;
    private final String errorSummary;
    private final Instant startedAt;
    private final Instant finishedAt;
    private final Instant createdAt;
    private final Instant updatedAt;

    AiAgentRunNode(
        Long id,
        String nodeId,
        Long graphPk,
        String agentId,
        String agentRole,
        int executionOrder,
        String status,
        String inputSnapshotJson,
        String outputSnapshotJson,
        String errorSummary,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.id = id;
        this.nodeId = nodeId;
        this.graphPk = graphPk;
        this.agentId = agentId;
        this.agentRole = agentRole;
        this.executionOrder = executionOrder;
        this.status = status;
        this.inputSnapshotJson = inputSnapshotJson;
        this.outputSnapshotJson = outputSnapshotJson;
        this.errorSummary = errorSummary;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    Long id() {
        return id;
    }

    String nodeId() {
        return nodeId;
    }

    Long graphPk() {
        return graphPk;
    }

    String agentId() {
        return agentId;
    }

    String agentRole() {
        return agentRole;
    }

    int executionOrder() {
        return executionOrder;
    }

    String status() {
        return status;
    }

    String inputSnapshotJson() {
        return inputSnapshotJson;
    }

    String outputSnapshotJson() {
        return outputSnapshotJson;
    }

    String errorSummary() {
        return errorSummary;
    }

    Instant startedAt() {
        return startedAt;
    }

    Instant finishedAt() {
        return finishedAt;
    }

    Instant createdAt() {
        return createdAt;
    }

    Instant updatedAt() {
        return updatedAt;
    }
}
