package com.haitlify.aitest.platform.ai.agentic.locator;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Disables unused entries after a grace period while preserving history for audit and revalidation. */
@Component
class LocatorKnowledgeMaintenanceJob {
    private final LocatorKnowledgeService locatorKnowledge;

    LocatorKnowledgeMaintenanceJob(LocatorKnowledgeService locatorKnowledge) { this.locatorKnowledge = locatorKnowledge; }

    @Scheduled(cron = "${app.ai.agent-assets.locator-cleanup-cron:0 20 3 * * SUN}")
    void retireUnusedEntries() { locatorKnowledge.retireUnusedBefore(Instant.now().minus(7, ChronoUnit.DAYS)); }
}
