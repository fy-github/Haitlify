package com.haitlify.aitest.platform.ai.runtime.artifact;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AiArtifactGateAuditService {
    private final JdbcTemplate jdbcTemplate;

    public AiArtifactGateAuditService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public GateResult record(GateRecord record) {
        List<String> errors = new ArrayList<>();
        if (record == null || record.artifactId() == null || record.artifactId().isBlank()) errors.add("artifact_id_missing");
        if (record == null || record.runId() == null || record.runId().isBlank()) errors.add("run_id_missing");
        if (record == null || record.projectId() == null) errors.add("project_id_missing");
        if (record != null && record.revision() < 1) errors.add("revision_invalid");
        if (record == null || record.idempotencyKey() == null || record.idempotencyKey().isBlank()) errors.add("idempotency_key_missing");
        if (record != null && record.acceptanceState() == null) errors.add("acceptance_state_missing");
        String decision = errors.isEmpty() ? record.acceptanceState() : "BLOCKED";
        String gateId = "gate-" + UUID.randomUUID();
        if (record != null) {
            Instant now = Instant.now();
            jdbcTemplate.update("insert into ai_artifact_gate_audits(gate_id, artifact_id, run_id, project_id, result_origin, acceptance_state, decision, revision, parent_artifact_id, idempotency_key, evidence_ids_json, errors_json, actor_id, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                gateId, record.artifactId(), record.runId(), record.projectId(), record.resultOrigin(), record.acceptanceState(), decision,
                record.revision(), record.parentArtifactId(), record.idempotencyKey(), toJson(record.evidenceIds()), toJson(record.errors()), record.actorId(), Timestamp.from(now));
        }
        return new GateResult(gateId, decision, List.copyOf(errors));
    }

    private String toJson(List<String> values) {
        if (values == null || values.isEmpty()) return "[]";
        return "[\"" + String.join("\",\"", values).replace("\"", "\\\"") + "\"]";
    }

    public record GateRecord(String artifactId, String runId, Long projectId, String resultOrigin, String acceptanceState,
                             int revision, String parentArtifactId, String idempotencyKey, List<String> evidenceIds,
                             List<String> errors, String actorId) {}
    public record GateResult(String gateId, String decision, List<String> errors) {}
}
