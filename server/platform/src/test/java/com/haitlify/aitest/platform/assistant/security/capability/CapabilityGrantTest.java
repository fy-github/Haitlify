package com.haitlify.aitest.platform.assistant.security.capability;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class CapabilityGrantTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String HASH = "a".repeat(64);
    private static final CapabilityGrant.Scope SCOPE = new CapabilityGrant.Scope(1, 2, 3, "generation-4", "binding-5", 6);

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("changedScopes")
    void rejectsEveryScopeMismatch(CapabilityGrant.Scope changed) {
        assertFalse(grant().allows(HASH, changed, "tool-worker", "draft:read", CLOCK));
    }

    static java.util.stream.Stream<CapabilityGrant.Scope> changedScopes() {
        return java.util.stream.Stream.of(
                new CapabilityGrant.Scope(9, 2, 3, "generation-4", "binding-5", 6),
                new CapabilityGrant.Scope(1, 9, 3, "generation-4", "binding-5", 6),
                new CapabilityGrant.Scope(1, 2, 9, "generation-4", "binding-5", 6),
                new CapabilityGrant.Scope(1, 2, 3, "generation-9", "binding-5", 6),
                new CapabilityGrant.Scope(1, 2, 3, "generation-4", "binding-9", 6),
                new CapabilityGrant.Scope(1, 2, 3, "generation-4", "binding-5", 9));
    }

    @Test
    void rejectsDifferentTokenAudienceAndAction() {
        assertFalse(grant().allows("b".repeat(64), SCOPE, "tool-worker", "draft:read", CLOCK));
        assertFalse(grant().allows(HASH, SCOPE, "other-worker", "draft:read", CLOCK));
        assertFalse(grant().allows(HASH, SCOPE, "tool-worker", "draft:write", CLOCK));
        assertFalse(grant().allows(HASH, SCOPE, "tool-worker", "*", CLOCK));
    }

    @Test
    void rejectsRevocationAndExpiryAtExactBoundary() {
        assertFalse(new CapabilityGrant(HASH, SCOPE, "tool-worker", Set.of("draft:read"), NOW.plusSeconds(60), true)
                .allows(HASH, SCOPE, "tool-worker", "draft:read", CLOCK));
        for (Instant expiry : new Instant[] {NOW, NOW.minusNanos(1)}) {
            assertFalse(new CapabilityGrant(HASH, SCOPE, "tool-worker", Set.of("draft:read"), expiry, false)
                    .allows(HASH, SCOPE, "tool-worker", "draft:read", CLOCK));
        }
        assertTrue(new CapabilityGrant(HASH, SCOPE, "tool-worker", Set.of("draft:read"), NOW.plusNanos(1), false)
                .allows(HASH, SCOPE, "tool-worker", "draft:read", CLOCK));
    }

    @Test
    void failsClosedForMissingVerificationContext() {
        assertFalse(grant().allows(null, SCOPE, "tool-worker", "draft:read", CLOCK));
        assertFalse(grant().allows(HASH, null, "tool-worker", "draft:read", CLOCK));
        assertFalse(grant().allows(HASH, SCOPE, null, "draft:read", CLOCK));
        assertFalse(grant().allows(HASH, SCOPE, "tool-worker", null, CLOCK));
        assertFalse(grant().allows(HASH, SCOPE, "tool-worker", "draft:read", null));
        assertFalse(grant().allows("a".repeat(100000), SCOPE, "tool-worker", "draft:read", CLOCK));
        assertFalse(grant().allows(HASH, SCOPE, "a".repeat(100000), "draft:read", CLOCK));
        assertFalse(grant().allows(HASH, SCOPE, "tool-worker", "a".repeat(100000), CLOCK));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("invalidScopes")
    void rejectsInvalidScopeIdsAtConstruction(Object[] values) {
        assertEquals("Invalid capability scope", assertThrows(IllegalArgumentException.class, () ->
                new CapabilityGrant.Scope((long) values[0], (long) values[1], (long) values[2],
                        (String) values[3], (String) values[4], (long) values[5])).getMessage());
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> invalidScopes() {
        var cases = new java.util.ArrayList<Object[]>();
        for (int index : new int[] {0, 1, 2, 5}) {
            for (long invalid : new long[] {0, -1, Long.MIN_VALUE}) {
                Object[] row = {1L, 2L, 3L, "generation-4", "binding-5", 6L};
                row[index] = invalid;
                cases.add(row);
            }
        }
        for (int index : new int[] {3, 4}) {
            for (String invalid : new String[] {null, "", " x", "x ", "x\n", "*", "a/b", "é", "a".repeat(129)}) {
                Object[] row = {1L, 2L, 3L, "generation-4", "binding-5", 6L};
                row[index] = invalid;
                cases.add(row);
            }
        }
        return cases.stream().map(row -> org.junit.jupiter.params.provider.Arguments.of((Object) row));
    }

    @Test
    void rejectsMalformedGrantState() {
        for (String invalid : new String[] {null, "", "A".repeat(64), "x".repeat(64), "a".repeat(63), "a".repeat(100000)}) {
            assertThrows(IllegalArgumentException.class, () ->
                    new CapabilityGrant(invalid, SCOPE, "tool-worker", Set.of("draft:read"), NOW, false));
        }
        assertThrows(IllegalArgumentException.class, () ->
                new CapabilityGrant(HASH, null, "tool-worker", Set.of("draft:read"), NOW, false));
        assertThrows(IllegalArgumentException.class, () ->
                new CapabilityGrant(HASH, SCOPE, "tool-worker", Set.of("draft:read"), null, false));
        for (String invalid : new String[] {null, "", "*", " x", "x ", "x\n", "é", "a".repeat(129)}) {
            assertThrows(IllegalArgumentException.class, () ->
                    new CapabilityGrant(HASH, SCOPE, invalid, Set.of("draft:read"), NOW, false));
            var actions = new java.util.HashSet<String>();
            actions.add(invalid);
            assertThrows(IllegalArgumentException.class, () ->
                    new CapabilityGrant(HASH, SCOPE, "tool-worker", actions, NOW, false));
        }
        assertThrows(IllegalArgumentException.class, () ->
                new CapabilityGrant(HASH, SCOPE, "tool-worker", null, NOW, false));
        assertThrows(IllegalArgumentException.class, () ->
                new CapabilityGrant(HASH, SCOPE, "tool-worker", Set.of(), NOW, false));
        var excessive = java.util.stream.IntStream.range(0, 65).mapToObj(i -> "action-" + i).collect(java.util.stream.Collectors.toSet());
        assertThrows(IllegalArgumentException.class, () ->
                new CapabilityGrant(HASH, SCOPE, "tool-worker", excessive, NOW, false));
    }

    @Test
    void snapshotsActionsSoCallersCannotEscalateGrant() {
        var mutable = new java.util.HashSet<>(Set.of("draft:read"));
        var grant = new CapabilityGrant(HASH, SCOPE, "tool-worker", mutable, NOW.plusSeconds(60), false);
        mutable.add("draft:write");
        mutable.remove("draft:read");
        assertFalse(grant.allows(HASH, SCOPE, "tool-worker", "draft:write", CLOCK));
        assertTrue(grant.allows(HASH, SCOPE, "tool-worker", "draft:read", CLOCK));
        assertThrows(UnsupportedOperationException.class, () -> grant.actions().add("draft:write"));
    }

    private static CapabilityGrant grant() {
        return new CapabilityGrant(HASH, SCOPE, "tool-worker", Set.of("draft:read"), NOW.plusSeconds(60), false);
    }

    @Test
    void acceptsMatchingTrustedScopeBeforeExpiry() {
        var grant = new CapabilityGrant(HASH, SCOPE, "tool-worker", Set.of("draft:read"), NOW.plusSeconds(60), false);
        assertTrue(grant.allows(HASH, SCOPE, "tool-worker", "draft:read", CLOCK));
    }
}
