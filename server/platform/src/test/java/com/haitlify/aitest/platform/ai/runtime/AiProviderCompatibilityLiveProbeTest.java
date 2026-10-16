package com.haitlify.aitest.platform.ai.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.haitlify.aitest.platform.AiTestPlatformApplication;

@Tag("live")
@SpringBootTest(classes = AiTestPlatformApplication.class)
class AiProviderCompatibilityLiveProbeTest {
    @Autowired
    private AiProviderCompatibilityProbeService probe;

    @Test
    void probesConfiguredProfileWithTheSameRedactedBusinessInput() {
        Assumptions.assumeTrue(Boolean.getBoolean("ai.live.gate"), "Set -Dai.live.gate=true to run the paid live probe");
        var entry = probe.probe(
            "legacy-default-profile", 1, "gpt-5.6-sol",
            "The user can search the device list by name and see the filtered results.",
            List.of("ev-live-1"), "ai-system-config-v2"
        );
        assertTrue(entry.isLiveBusinessPass(), entry.blockingReason());
        System.out.printf("LIVE_MATRIX protocol=%s profile=%s model=%s inputHash=%s requestId=%s http=%s finish=%s usageKnown=%s schema=%s evidence=%s latencyMs=%d conclusion=%s%n",
            entry.protocol(), entry.profileId(), entry.modelId(), entry.inputHash(), entry.providerRequestId(), entry.httpStatus(), entry.finishStatus(),
            entry.usageKnown(), entry.schemaOutcome(), entry.evidenceOutcome(), entry.latencyMs(), entry.conclusion());
    }
}
