package com.haitlify.aitest.platform.ai.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.haitlify.aitest.platform.AiTestPlatformApplication;

@Tag("live")
@SpringBootTest(classes = AiTestPlatformApplication.class)
class AiProviderCompatibilityModelSwitchLiveTest {
    @Autowired
    private AiProviderCompatibilityProbeService probe;

    @Test
    void comparesTwoExplicitProfilesWithTheSameRedactedInput() {
        Assumptions.assumeTrue(Boolean.getBoolean("ai.live.gate"), "Set -Dai.live.gate=true to run the paid live comparison");
        String requirement = "The user can search the device list by name and see the filtered results.";
        List<String> evidenceIds = List.of("ev-live-1");
        var first = probe.probe("legacy-default-profile", 1, "gpt-5.6-sol", requirement, evidenceIds, "ai-system-config-v2");
        var second = probe.probe("legacy-gpt55-profile", 1, "gpt-5.5", requirement, evidenceIds, "ai-system-config-v2");
        var comparison = AiProviderCompatibilityMatrix.compare(List.of(first, second));
        assertEquals(first.inputHash(), second.inputHash());
        assertTrue(first.isLiveBusinessPass(), first.blockingReason());
        assertTrue(second.isLiveBusinessPass(), second.blockingReason());
        assertEquals(2, comparison.liveBusinessPassCount());
        System.out.printf("LIVE_MODEL_SWITCH inputHash=%s firstProfile=%s firstModel=%s firstRequestId=%s firstLatencyMs=%d secondProfile=%s secondModel=%s secondRequestId=%s secondLatencyMs=%d livePasses=%d%n",
            first.inputHash(), first.profileId(), first.modelId(), first.providerRequestId(), first.latencyMs(),
            second.profileId(), second.modelId(), second.providerRequestId(), second.latencyMs(), comparison.liveBusinessPassCount());
    }
}
