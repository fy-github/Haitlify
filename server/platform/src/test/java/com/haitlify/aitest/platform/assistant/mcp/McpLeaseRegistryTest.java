package com.haitlify.aitest.platform.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * 纯内存语义：不可猜、有界、到期即失效、撤销即失效，并且任何 toString 都不带 token。
 * 这里不碰 Spring，也不碰数据库——注册表本身没有持久化面。
 */
class McpLeaseRegistryTest {

    private static final String TOKEN = "grant-token-that-must-never-be-logged";
    private static final Instant GRANT_EXPIRY = Instant.parse("2026-10-26T12:00:00Z");

    @Test
    void idsAre256BitRandomAndRepeatIssuesNeverCollide() {
        var registry = new McpLeaseRegistry(256, new MutableClock(GRANT_EXPIRY.minusSeconds(60)), Duration.ofMinutes(5));
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < 64; index++) {
            String id = registry.issue(TOKEN, GRANT_EXPIRY, Duration.ofMinutes(5), "gen-1", "bind-1", 7L);
            assertThat(id).matches("[A-Za-z0-9_-]{43}").doesNotContain("gen-1", "bind-1");
            ids.add(id);
        }
        assertThat(ids).hasSize(64);
        assertThat(registry.size()).isEqualTo(64);
    }

    @Test
    void deadlineIsTheSmallerOfTtlAndGrantExpiry() {
        var now = GRANT_EXPIRY.minusSeconds(300);
        var registry = new McpLeaseRegistry(4, new MutableClock(now), Duration.ofMinutes(5));
        String grantBound = registry.issue(TOKEN, now.plusSeconds(60), Duration.ofMinutes(5), "gen", "bind", 1L);
        String ttlBound = registry.issue(TOKEN, now.plusSeconds(600), Duration.ofSeconds(30), "gen", "bind", 1L);
        assertThat(registry.resolve(grantBound).orElseThrow().expiresAt()).isEqualTo(now.plusSeconds(60));
        assertThat(registry.resolve(ttlBound).orElseThrow().expiresAt()).isEqualTo(now.plusSeconds(30));
        // 请求的 TTL 超过上限时按上限收窄，不会被调用方放大。
        String capped = registry.issue(TOKEN, now.plusSeconds(3600), Duration.ofHours(4), "gen", "bind", 1L);
        assertThat(registry.resolve(capped).orElseThrow().expiresAt()).isEqualTo(now.plus(Duration.ofMinutes(5)));
    }

    @Test
    void expiredAndRevokedLeasesAreIndistinguishableFromUnknownOnes() {
        var clock = new MutableClock(GRANT_EXPIRY.minusSeconds(300));
        var registry = new McpLeaseRegistry(4, clock, Duration.ofMinutes(5));
        String expiring = registry.issue(TOKEN, GRANT_EXPIRY, Duration.ofSeconds(10), "gen", "bind", 1L);
        String revoked = registry.issue(TOKEN, GRANT_EXPIRY, Duration.ofSeconds(10), "gen", "bind", 1L);
        assertThat(registry.resolve(expiring)).isPresent();
        assertThat(registry.revoke(revoked)).isTrue();
        assertThat(registry.resolve(revoked)).isEmpty();
        assertThat(registry.revoke(revoked)).isFalse();
        clock.now = clock.now.plusSeconds(10);
        assertThat(registry.resolve(expiring)).isEmpty();
        assertThat(registry.resolve("A".repeat(43))).isEmpty();
        assertThat(registry.resolve("not-a-lease")).isEmpty();
        assertThat(registry.resolve(null)).isEmpty();
    }

    @Test
    void capacityFailsClosedAndExpiredEntriesFreeSlots() {
        var clock = new MutableClock(GRANT_EXPIRY.minusSeconds(300));
        var registry = new McpLeaseRegistry(2, clock, Duration.ofMinutes(5));
        registry.issue(TOKEN, GRANT_EXPIRY, Duration.ofSeconds(10), "gen", "bind", 1L);
        registry.issue(TOKEN, GRANT_EXPIRY, Duration.ofSeconds(10), "gen", "bind", 1L);
        assertThatThrownBy(() -> registry.issue(TOKEN, GRANT_EXPIRY, Duration.ofSeconds(10), "gen", "bind", 1L))
            .isInstanceOf(McpLeaseRegistry.LeaseUnavailableException.class)
            .hasMessageNotContaining(TOKEN);
        clock.now = clock.now.plusSeconds(11);
        assertThat(registry.issue(TOKEN, GRANT_EXPIRY, Duration.ofSeconds(10), "gen", "bind", 1L))
            .matches("[A-Za-z0-9_-]{43}");
        assertThat(registry.capacity()).isEqualTo(2);
    }

    @Test
    void nothingPrintableCarriesTheTokenOrTheLeaseId() {
        var registry = new McpLeaseRegistry(4, new MutableClock(GRANT_EXPIRY.minusSeconds(60)), Duration.ofMinutes(5));
        String id = registry.issue(TOKEN, GRANT_EXPIRY, Duration.ofMinutes(5), "gen", "bind", 1L);
        var resolved = registry.resolve(id).orElseThrow();
        assertThat(resolved.token()).isEqualTo(TOKEN);
        assertThat(resolved.toString()).doesNotContain(TOKEN, id);
        assertThat(registry.toString()).doesNotContain(TOKEN, id);
        assertThat(resolved.generationId()).isEqualTo("gen");
        assertThat(resolved.bindingId()).isEqualTo("bind");
        assertThat(resolved.actorId()).isEqualTo(1L);
    }

    @Test
    void invalidRegistrationsAreRejectedWithoutEchoingTheToken() {
        var registry = new McpLeaseRegistry(4, new MutableClock(GRANT_EXPIRY.minusSeconds(60)), Duration.ofMinutes(5));
        for (Duration ttl : new Duration[]{null, Duration.ZERO, Duration.ofSeconds(-1)}) {
            assertThatThrownBy(() -> registry.issue(TOKEN, GRANT_EXPIRY, ttl, "gen", "bind", 1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(TOKEN);
        }
        assertThatThrownBy(() -> registry.issue(" ", GRANT_EXPIRY, Duration.ofSeconds(30), "gen", "bind", 1L))
            .isInstanceOf(IllegalArgumentException.class);
        // 已经过期的 grant 不可能换成租约（时钟在 GRANT_EXPIRY-60s，所以这里要早于它）。
        assertThatThrownBy(() -> registry.issue(TOKEN, GRANT_EXPIRY.minusSeconds(61), Duration.ofSeconds(30), "gen", "bind", 1L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) { this.now = now; }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }

        @Override public Clock withZone(ZoneId zone) { return this; }

        @Override public Instant instant() { return now; }
    }
}
