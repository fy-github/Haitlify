package com.haitlify.aitest.platform.ai.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.haitlify.aitest.platform.AiTestPlatformApplication;
import com.haitlify.aitest.platform.ai.runtime.AiRuntimeModels.AiTaskRequest;
import tools.jackson.databind.ObjectMapper;

@Tag("live")
@SpringBootTest(classes = AiTestPlatformApplication.class)
class AiVerticalSliceLiveAcceptanceTest {
    @Autowired
    private AiVerticalSliceService verticalSlice;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void completesRealInvocationArtifactAndHumanConfirmationReadback() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("ai.live.gate"), "Set -Dai.live.gate=true to run the paid live gate");
        // Unique per execution: the run id is the idempotency key, so a fixed id makes this gate
        // un-runnable a second time (duplicate key instead of a fresh real invocation).
        String runId = "live-vertical-" + System.currentTimeMillis();
        var result = verticalSlice.execute(new AiTaskRequest(
            "requirement_analysis", 2L, "admin", runId, "skill-requirement-analysis-v1",
            "test-points-v1", "1", List.of("ev-live-1"),
            objectMapper.readTree("{\"requirement\":\"The user can search the device list by name and see the filtered results.\",\"evidenceIds\":[\"ev-live-1\"]}"),
            runId
        ));
        assertEquals("MODEL_GENERATED", result.invocation().resultOrigin(), result.invocation().failureCategory());
        assertNotNull(result.artifactId());
        assertEquals("PENDING_HUMAN_CONFIRMATION", result.artifact().acceptanceState());
        var confirmed = verticalSlice.confirm(result.artifactId(), "admin");
        assertEquals("ACCEPTED", confirmed.acceptanceState());
        assertEquals(result.artifactId(), verticalSlice.readArtifact(result.artifactId()).artifactId());
        System.out.printf("LIVE_VERTICAL runId=%s artifactId=%s requestId=%s origin=%s acceptance=%s%n",
            result.runId(), result.artifactId(), result.invocation().providerRequestId(), result.invocation().resultOrigin(), confirmed.acceptanceState());
    }
}
