package com.haitlify.aitest.platform.ai.runtime.artifact;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AiArtifactOriginPolicyTest {
    @Test
    void modelClaimRequiresRuntimeEvidence() {
        assertTrue(AiArtifactOriginPolicy.isModelClaimAllowed(AiArtifactOrigin.MODEL_GENERATED, true));
        assertFalse(AiArtifactOriginPolicy.isModelClaimAllowed(AiArtifactOrigin.MODEL_GENERATED, false));
        assertFalse(AiArtifactOriginPolicy.isModelClaimAllowed(AiArtifactOrigin.RULE_EXTRACTED, true));
    }

    @Test
    void protectedFieldsHaveNonModelOrigins() {
        var origins = AiArtifactOriginPolicy.protectedFieldOrigins();
        assertEquals(AiFieldOrigin.RULE_COMPUTED, origins.get("passRate"));
        assertEquals(AiFieldOrigin.HUMAN_REQUIRED, origins.get("releaseConclusion"));
        assertEquals(AiFieldOrigin.HUMAN_REQUIRED, origins.get("sqlExecuted"));
        assertEquals(AiFieldOrigin.TOOL_OBSERVED, origins.get("webExecutionResult"));
    }
}
