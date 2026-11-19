package com.haitlify.aitest.platform.assistant.security.capability;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;

/**
 * Immutable snapshot loaded/created only by trusted server authorization code, never from tool arguments.
 * This is not an authentication entry point. The caller must enforce a short issuance TTL, load fresh
 * revocation/binding/lease state, and make validation plus use atomic if concurrent revocation matters.
 * No persistence, replay prevention, locks, tenant lookup or HTTP/header handling is provided here.
 */
public record CapabilityGrant(String tokenHash, Scope scope, String audience, Set<String> actions,
                              Instant expiresAt, boolean revoked) {
    public CapabilityGrant {
        if (!validHash(tokenHash) || scope == null || !validIdentifier(audience) || expiresAt == null
                || actions == null || actions.isEmpty() || actions.size() > 64
                || actions.stream().anyMatch(action -> !validIdentifier(action))) {
            throw new IllegalArgumentException("Invalid capability grant");
        }
        actions = Set.copyOf(actions);
    }

    /** Positive numeric IDs/lease; opaque ASCII generation/binding IDs, 1..128 chars, no normalization. */
    public record Scope(long actorId, long tenantId, long projectId, String generationId,
                        String bindingId, long leaseGeneration) {
        public Scope {
            if (actorId <= 0 || tenantId <= 0 || projectId <= 0 || leaseGeneration <= 0
                    || !validIdentifier(generationId) || !validIdentifier(bindingId)) {
                throw new IllegalArgumentException("Invalid capability scope");
            }
        }
    }

    private static boolean validIdentifier(String value) {
        return value != null && value.length() <= 128 && value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}");
    }

    private static boolean validHash(String value) {
        return value != null && value.length() == 64 && value.matches("[0-9a-f]{64}");
    }
    /**
     * presentedHash must come from hashPresented on the received secret, never a client-supplied hash.
     * All other arguments are trusted server context: current scope, configured audience/action, clock.
     * Exact action membership only (no wildcard), and expiry is exclusive: now == expiry is denied.
     */
    public boolean allows(String presentedHash, Scope trustedCurrentScope, String expectedAudience,
                          String requiredAction, Clock trustedClock) {
        if (!validHash(presentedHash) || trustedCurrentScope == null || trustedClock == null
                || !validIdentifier(expectedAudience) || !validIdentifier(requiredAction)) {
            return false;
        }
        return !revoked && trustedClock.instant().isBefore(expiresAt)
                && java.security.MessageDigest.isEqual(tokenHash.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                presentedHash.getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                && scope.equals(trustedCurrentScope) && audience.equals(expectedAudience)
                && actions.contains(requiredAction);
    }
}
