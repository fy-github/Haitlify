package com.haitlify.aitest.platform.ai.runtime.artifact;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.haitlify.aitest.platform.ai.runtime.AiRuntimeInvocationService.AiRuntimeInvocationResult;
import tools.jackson.databind.ObjectMapper;

class AiArtifactAcceptanceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AiArtifactAcceptanceService service = new AiArtifactAcceptanceService();

    @Test
    void acceptsModelArtifactOnlyWithRuntimeEvidenceAndHumanPendingState() throws Exception {
        var runtime = new AiRuntimeInvocationResult(
            "MODEL", "MODEL_GENERATED", "PENDING_HUMAN_CONFIRMATION", mapper.readTree("{\"items\":[{\"title\":\"A\"}]}"),
            "req-1", new com.haitlify.aitest.platform.ai.runtime.AiProtocolResponse.Usage(1, 2), null, null, null
        );
        var result = service.accept(new AiArtifactAcceptanceRequest(
            AiArtifactOrigin.MODEL_GENERATED, AiArtifactAcceptanceState.PENDING_HUMAN_CONFIRMATION,
            runtime.output(), 2L, 2L, List.of("ev-1"), List.of("ev-1"), null, 1, "idem-1", runtime
        ));
        assertTrue(result.accepted());
        assertEquals(AiArtifactAcceptanceState.PENDING_HUMAN_CONFIRMATION, result.acceptanceState());
    }

    @Test
    void blocksPlaceholderModelArtifactWithoutRuntimeOrCrossProjectEvidence() throws Exception {
        var result = service.accept(new AiArtifactAcceptanceRequest(
            AiArtifactOrigin.MODEL_GENERATED, AiArtifactAcceptanceState.PENDING_HUMAN_CONFIRMATION,
            mapper.readTree("{\"items\":[]}"), 2L, 3L, List.of("ev-other"), List.of("ev-1"), null, 1, "idem-1", null
        ));
        assertFalse(result.accepted());
        assertEquals(AiArtifactAcceptanceState.BLOCKED, result.acceptanceState());
        assertTrue(result.errors().contains("project_scope_mismatch"));
        assertTrue(result.errors().contains("runtime_attempt_missing"));
        assertTrue(result.errors().contains("evidence_scope_mismatch:ev-other"));
    }

    @Test
    void allowsOnlyHumanReviewAndExecutionTransitions() {
        assertEquals(AiArtifactAcceptanceState.ACCEPTED, service.transition(AiArtifactAcceptanceState.PENDING_HUMAN_CONFIRMATION, AiArtifactAcceptanceState.ACCEPTED));
        assertEquals(AiArtifactAcceptanceState.EXECUTED, service.transition(AiArtifactAcceptanceState.ACCEPTED, AiArtifactAcceptanceState.EXECUTED));
        assertThrows(IllegalArgumentException.class, () -> service.transition(AiArtifactAcceptanceState.PENDING_HUMAN_CONFIRMATION, AiArtifactAcceptanceState.EXECUTED));
    }

    @Test
    void ruleAndToolOriginsDoNotClaimModelGenerated() throws Exception {
        var rule = service.accept(new AiArtifactAcceptanceRequest(
            AiArtifactOrigin.RULE_EXTRACTED, AiArtifactAcceptanceState.PENDING_HUMAN_CONFIRMATION,
            mapper.readTree("{\"facts\":[\"observed\"]}"), 2L, 2L, List.of(), List.of(), null, 1, "idem-rule", null
        ));
        var tool = service.accept(new AiArtifactAcceptanceRequest(
            AiArtifactOrigin.TOOL_OBSERVED, AiArtifactAcceptanceState.PENDING_HUMAN_CONFIRMATION,
            mapper.readTree("{\"nonce\":\"n1\"}"), 2L, 2L, List.of(), List.of(), null, 1, "idem-tool", null
        ));
        assertEquals(AiArtifactOrigin.RULE_EXTRACTED, rule.origin());
        assertEquals(AiArtifactOrigin.TOOL_OBSERVED, tool.origin());
        assertNotEquals(AiArtifactOrigin.MODEL_GENERATED, rule.origin());
        assertNotEquals(AiArtifactOrigin.MODEL_GENERATED, tool.origin());
    }
}
