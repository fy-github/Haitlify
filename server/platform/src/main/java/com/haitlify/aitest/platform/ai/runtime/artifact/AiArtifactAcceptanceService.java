package com.haitlify.aitest.platform.ai.runtime.artifact;

import java.util.Set;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

@Service
public final class AiArtifactAcceptanceService {
    public AiArtifactAcceptanceResult accept(AiArtifactAcceptanceRequest request) {
        List<String> errors = new ArrayList<>();
        if (request == null || request.origin() == null) errors.add("origin_missing");
        if (request == null || request.content() == null || request.content().isNull() || request.content().isMissingNode()) errors.add("content_empty");
        if (request != null && (request.projectId() == null || request.sourceProjectId() == null || !request.projectId().equals(request.sourceProjectId()))) errors.add("project_scope_mismatch");
        if (request != null && request.revision() < 1) errors.add("revision_invalid");
        if (request != null && (request.idempotencyKey() == null || request.idempotencyKey().isBlank())) errors.add("idempotency_key_missing");
        if (request != null && request.evidenceIds() != null && request.allowedEvidenceIds() != null) {
            for (String evidenceId : request.evidenceIds()) if (!request.allowedEvidenceIds().contains(evidenceId)) errors.add("evidence_scope_mismatch:" + evidenceId);
        }
        if (request != null && request.origin() == AiArtifactOrigin.MODEL_GENERATED) {
            if (request.runtimeResult() == null) errors.add("runtime_attempt_missing");
            else {
                if (!AiArtifactAcceptanceState.PENDING_HUMAN_CONFIRMATION.name().equals(request.runtimeResult().acceptanceState())) errors.add("model_acceptance_state_invalid");
                if (request.runtimeResult().output() == null) errors.add("validated_output_missing");
                if (request.runtimeResult().providerRequestId() == null || request.runtimeResult().providerRequestId().isBlank()) errors.add("provider_request_id_missing");
            }
        }
        if (!errors.isEmpty()) return AiArtifactAcceptanceResult.blocked(request == null ? null : request.origin(), errors);
        return new AiArtifactAcceptanceResult(true, request.origin(), AiArtifactAcceptanceState.PENDING_HUMAN_CONFIRMATION, request.content(), List.of());
    }

    public AiArtifactAcceptanceState transition(AiArtifactAcceptanceState current, AiArtifactAcceptanceState next) {
        if (current == null || next == null) throw new IllegalArgumentException("state required");
        if (current == AiArtifactAcceptanceState.PENDING_HUMAN_CONFIRMATION && next == AiArtifactAcceptanceState.ACCEPTED) return next;
        if (current == AiArtifactAcceptanceState.ACCEPTED && next == AiArtifactAcceptanceState.EXECUTED) return next;
        if (current == AiArtifactAcceptanceState.DEGRADED && next == AiArtifactAcceptanceState.PENDING_HUMAN_CONFIRMATION) return next;
        if (current == AiArtifactAcceptanceState.PENDING_HUMAN_CONFIRMATION && next == AiArtifactAcceptanceState.BLOCKED) return next;
        if (current == AiArtifactAcceptanceState.DEGRADED && next == AiArtifactAcceptanceState.BLOCKED) return next;
        if (current == next) return current;
        throw new IllegalArgumentException("invalid artifact state transition: " + current + " -> " + next);
    }
}
