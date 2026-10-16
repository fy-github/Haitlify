package com.haitlify.aitest.platform.ai.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class AiProviderCompatibilityMatrix {
    private AiProviderCompatibilityMatrix() {}

    public record Entry(
        String protocol,
        String profileId,
        int profileVersion,
        String modelId,
        String capability,
        String inputHash,
        Instant observedAt,
        String configVersion,
        String providerRequestId,
        Integer httpStatus,
        String finishStatus,
        boolean usageKnown,
        Integer inputTokens,
        Integer outputTokens,
        String schemaOutcome,
        String evidenceOutcome,
        long latencyMs,
        String conclusion,
        String blockingReason,
        String runId
    ) {
        public boolean isLiveBusinessPass() {
            return "LIVE_BUSINESS_PASS".equals(conclusion)
                && providerRequestId != null && !providerRequestId.isBlank()
                && httpStatus != null && httpStatus >= 200 && httpStatus < 300
                && "stop".equalsIgnoreCase(finishStatus)
                && usageKnown
                && "PASS".equals(schemaOutcome)
                && "PASS".equals(evidenceOutcome)
                && inputHash != null && !inputHash.isBlank()
                && runId != null && !runId.isBlank();
        }
    }

    public record Comparison(
        List<Entry> entries,
        boolean sameInput,
        boolean onlyProfileChanged,
        int liveBusinessPassCount
    ) {}

    public static Comparison compare(List<Entry> entries) {
        if (entries == null || entries.size() < 2) {
            throw new IllegalArgumentException("at least two profile observations are required");
        }
        Entry first = entries.getFirst();
        boolean sameInput = entries.stream().allMatch(entry -> Objects.equals(first.inputHash(), entry.inputHash()));
        if (!sameInput) throw new IllegalArgumentException("model comparison requires the same input hash");
        boolean sameExecutionContract = entries.stream().allMatch(entry ->
            Objects.equals(first.protocol(), entry.protocol())
                && Objects.equals(first.capability(), entry.capability())
                && Objects.equals(first.configVersion(), entry.configVersion())
                && entry.profileId() != null && !entry.profileId().isBlank()
        );
        if (!sameExecutionContract) {
            throw new IllegalArgumentException("model comparison requires the same protocol, capability, and config version");
        }
        boolean distinctProfiles = entries.stream().map(Entry::profileId).distinct().count() > 1;
        if (!distinctProfiles) throw new IllegalArgumentException("model comparison requires distinct profile ids");
        int livePasses = (int) entries.stream().filter(Entry::isLiveBusinessPass).count();
        return new Comparison(List.copyOf(entries), true, true, livePasses);
    }
}
