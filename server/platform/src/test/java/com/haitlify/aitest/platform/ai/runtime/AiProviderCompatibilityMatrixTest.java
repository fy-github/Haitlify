package com.haitlify.aitest.platform.ai.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.haitlify.aitest.platform.ai.runtime.AiRuntimeModels.Protocol;

class AiProviderCompatibilityMatrixTest {
    @Test
    void acceptsLiveBusinessPassOnlyWithCompleteEvidence() {
        var entry = new AiProviderCompatibilityMatrix.Entry(
            "CHAT_COMPLETIONS", "profile-a", 1, "model-a", "text", "input-hash", Instant.parse("2026-09-18T10:00:00Z"),
            "config-v1", "req-1", 200, "stop", true, 10, 20, "PASS", "PASS", 1200,
            "LIVE_BUSINESS_PASS", "", "run-1"
        );
        assertTrue(entry.isLiveBusinessPass());
        assertEquals(Protocol.CHAT_COMPLETIONS.name(), entry.protocol());
    }

    @Test
    void keepsIncompleteOrUnknownObservationOutOfSupportedConclusion() {
        var entry = new AiProviderCompatibilityMatrix.Entry(
            "RESPONSES", "profile-b", 1, "model-b", "json_schema", "input-hash", Instant.now(),
            "config-v1", "", 200, "incomplete", false, null, null, "UNKNOWN", "UNKNOWN", 5000,
            "LIVE_UNKNOWN", "no live business evidence", ""
        );
        assertFalse(entry.isLiveBusinessPass());
        assertEquals("LIVE_UNKNOWN", entry.conclusion());
    }

    @Test
    void modelSwitchComparisonRequiresSameInputAndOnlyExplicitProfileChange() {
        var first = new AiProviderCompatibilityMatrix.Entry(
            "CHAT_COMPLETIONS", "profile-a", 1, "model-a", "text", "same-input", Instant.now(),
            "config-v1", "req-a", 200, "stop", true, 1, 2, "PASS", "PASS", 100,
            "LIVE_BUSINESS_PASS", "", "run-a"
        );
        var second = new AiProviderCompatibilityMatrix.Entry(
            "CHAT_COMPLETIONS", "profile-b", 1, "model-b", "text", "same-input", Instant.now(),
            "config-v1", "req-b", 200, "stop", true, 2, 3, "PASS", "PASS", 110,
            "LIVE_BUSINESS_PASS", "", "run-b"
        );
        var comparison = AiProviderCompatibilityMatrix.compare(List.of(first, second));
        assertEquals(2, comparison.entries().size());
        assertTrue(comparison.sameInput());
        assertTrue(comparison.onlyProfileChanged());
        assertTrue(comparison.liveBusinessPassCount() == 2);
    }

    @Test
    void comparisonRejectsDifferentInputHashes() {
        var first = new AiProviderCompatibilityMatrix.Entry(
            "CHAT_COMPLETIONS", "profile-a", 1, "model-a", "text", "hash-a", Instant.now(),
            "config-v1", "req-a", 200, "stop", true, 1, 2, "PASS", "PASS", 100,
            "LIVE_BUSINESS_PASS", "", "run-a"
        );
        var second = new AiProviderCompatibilityMatrix.Entry(
            "CHAT_COMPLETIONS", "profile-b", 1, "model-b", "text", "hash-b", Instant.now(),
            "config-v1", "req-b", 200, "stop", true, 2, 3, "PASS", "PASS", 110,
            "LIVE_BUSINESS_PASS", "", "run-b"
        );
        assertThrows(IllegalArgumentException.class, () -> AiProviderCompatibilityMatrix.compare(List.of(first, second)));
    }
}
