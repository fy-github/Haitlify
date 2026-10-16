package com.haitlify.aitest.platform.ai.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.haitlify.aitest.platform.AiTestPlatformApplication;
import com.haitlify.aitest.platform.ai.runtime.AiRuntimeModels.AiTaskRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * Opt-in live gate. Run only against the configured MySQL and authorized provider profile.
 * It must never be included in the deterministic test suite.
 */
@Tag("live")
@SpringBootTest(classes = AiTestPlatformApplication.class)
class AiRuntimeLiveInvocationTest {
    @Autowired
    private AiRuntimeInvocationService runtime;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void invokesConfiguredProviderWithRedactedRequirement() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("ai.live.gate"), "Set -Dai.live.gate=true to run the paid live gate");
        var result = runtime.invoke(new AiTaskRequest(
            "requirement_analysis",
            2L,
            "admin",
            "live-gate-20260918",
            "skill-requirement-analysis-v1",
            "test-points-v1",
            "1",
            java.util.List.of("ev-live-1"),
            objectMapper.readTree("{\"requirement\":\"The user can search the device list by name and see the filtered results.\",\"evidenceIds\":[\"ev-live-1\"]}"),
            "live-gate-20260918"
        ));
        assertEquals("MODEL_GENERATED", result.resultOrigin(), result.failureCategory());
        assertEquals("PENDING_HUMAN_CONFIRMATION", result.acceptanceState());
        assertNotNull(result.providerRequestId());
        assertNotNull(result.output());
        System.out.printf("LIVE_GATE resultOrigin=%s acceptanceState=%s providerRequestId=%s usageKnown=%s output=%s%n",
            result.resultOrigin(), result.acceptanceState(), result.providerRequestId(),
            result.usage() != null && result.usage().inputTokens() != null && result.usage().outputTokens() != null,
            result.output());
    }
}
