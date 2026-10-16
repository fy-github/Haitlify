package com.haitlify.aitest.platform.ai.agent;

import java.time.Instant;

class AiAgentRunGraph {

    private final Long id;
    private final String graphId;
    private final String runId;
    private final Long projectId;
    private final String status;
    private final String inputSnapshotJson;
    private final String outputSnapshotJson;
    private final String errorSummary;
    private final Instant createdAt;
    private final Instant updatedAt;

    AiAgentRunGraph(
        Long id,
        String graphId,
        String runId,
        Long projectId,
        String status,
        String inputSnapshotJson,
        String outputSnapshotJson,
        String errorSummary,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.id = id;
        this.graphId = graphId;
        this.runId = runId;
        this.projectId = projectId;
        this.status = status;
        this.inputSnapshotJson = inputSnapshotJson;
        this.outputSnapshotJson = outputSnapshotJson;
        this.errorSummary = errorSummary;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    Long id() {
        return id;
    }

    String graphId() {
        return graphId;
    }

    String runId() {
        return runId;
    }

    Long projectId() {
        return projectId;
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

    String acceptanceState() {
        try { return new tools.jackson.databind.ObjectMapper().readTree(outputSnapshotJson).path("acceptanceState").asText("BLOCKED"); }
        catch (Exception ignored) { return "BLOCKED"; }
    }

    String resultOrigin() {
        try { return new tools.jackson.databind.ObjectMapper().readTree(outputSnapshotJson).path("resultOrigin").asText("BLOCKED"); }
        catch (Exception ignored) { return "BLOCKED"; }
    }

    String errorSummary() {
        return errorSummary;
    }

    Instant createdAt() {
        return createdAt;
    }

    Instant updatedAt() {
        return updatedAt;
    }
}
