package com.haitlify.aitest.platform.ai.agentic.locator;

import java.util.List;

import com.haitlify.aitest.platform.webautomation.WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryRequest;

record CollectLocatorKnowledgeRequest(
    Long projectId,
    String environmentId,
    String moduleName,
    String pageName,
    PageLocatorDiscoveryRequest discovery
) {
}

record SaveLocatorKnowledgeRequest(
    Long projectId,
    String environmentId,
    String moduleName,
    String pageName,
    String pageUrl,
    String naturalName,
    String locator,
    String roleName,
    String accessibleName,
    String elementType
) {
}

record LocatorKnowledgeListResponse(List<LocatorKnowledgeView> items) {
}

record LocatorKnowledgeView(
    String entryId,
    Long projectId,
    String environmentId,
    String moduleName,
    String pageName,
    String pageUrl,
    String pageFingerprint,
    String naturalName,
    String locator,
    String roleName,
    String accessibleName,
    String elementType,
    String source,
    String status,
    int evidenceVersion,
    String collectedAt,
    String lastVerifiedAt,
    int hitCount,
    int executionSuccessCount,
    int executionFailureCount,
    int confidenceScore,
    boolean confidenceLocked,
    String lastHitAt
) {
}

record CreateLocatorRepairProposalRequest(
    String entryId,
    String replacementLocator,
    String failureCategory,
    String pageFingerprintBefore,
    String pageFingerprintAfter,
    int confidence,
    String reason,
    String verificationId,
    String sourcePointId,
    String pageKey,
    String environmentId
) {
    /** Compatibility constructor: the legacy boolean is deliberately not trusted. */
    CreateLocatorRepairProposalRequest(String entryId, String replacementLocator, String failureCategory,
        String pageFingerprintBefore, String pageFingerprintAfter, int confidence, String reason,
        boolean candidateVerified) {
        this(entryId, replacementLocator, failureCategory, pageFingerprintBefore, pageFingerprintAfter,
            confidence, reason, null, null, null, null);
    }

    /** Compatibility constructor: the legacy boolean is deliberately not trusted. */
    CreateLocatorRepairProposalRequest(String entryId, String replacementLocator, String failureCategory,
        String pageFingerprintBefore, String pageFingerprintAfter, int confidence, String reason,
        boolean candidateVerified, String verificationId, String sourcePointId, String pageKey, String environmentId) {
        this(entryId, replacementLocator, failureCategory, pageFingerprintBefore, pageFingerprintAfter,
            confidence, reason, verificationId, sourcePointId, pageKey, environmentId);
    }
}

record ReviewLocatorRepairProposalRequest(
    String comment
) {
    /** Compatibility constructor: actor fields are ignored and never trusted. */
    ReviewLocatorRepairProposalRequest(String actorId, String actorName, String comment) {
        this(comment);
    }
}

record VerifyLocatorRepairCandidateRequest(
    String entryId,
    String candidateLocator,
    String sourcePointId,
    String pageKey,
    com.haitlify.aitest.platform.webautomation.WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryRequest discovery
) {
}

record LocatorRepairProposalListResponse(List<LocatorKnowledgeService.LocatorRevisionProposal> items) {
}
