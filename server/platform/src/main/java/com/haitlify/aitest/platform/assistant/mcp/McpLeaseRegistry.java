package com.haitlify.aitest.platform.assistant.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory bridge between a caller that may only hold an unguessable lease id and the real grant token
 * that must never leave the platform's heap.
 *
 * <p>Nothing here is persisted, logged or echoed: the map key is the SHA-256 of the lease id (so even a heap
 * dump or an accidental {@code toString()} of the map cannot replay a lease) and the value holds the grant
 * token. A process restart therefore invalidates every lease — a caller then gets 401 and must be
 * re-provisioned, which is the intended blast radius.
 *
 * <p>Bounded by construction: a lease never outlives {@code min(now + ttl, grant expiry)}, the requested ttl
 * is capped by the configured ceiling, expired entries are dropped on every touch, and the table has a hard
 * capacity after which issuance fails closed (the caller must not fall back to any weaker credential).
 */
public final class McpLeaseRegistry {
    /** Machine path owned by this shim; the gateway configuration only ever sees this plus the lease id. */
    public static final String PATH = "/mcp-lease";
    public static final int DEFAULT_CAPACITY = 256;
    public static final Duration MAX_TTL = Duration.ofMinutes(5);
    private static final int LEASE_BYTES = 32;
    private static final int LEASE_CHARS = 43;

    private final int capacity;
    private final Clock clock;
    private final Duration maxTtl;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> leases = new LinkedHashMap<>();

    public McpLeaseRegistry() { this(DEFAULT_CAPACITY, Clock.systemUTC(), MAX_TTL); }

    public McpLeaseRegistry(int capacity, Clock clock, Duration maxTtl) {
        if (capacity < 1) throw new IllegalArgumentException("Lease capacity must be positive");
        if (clock == null) throw new IllegalArgumentException("Clock required");
        if (maxTtl == null || maxTtl.isZero() || maxTtl.isNegative() || maxTtl.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException("Lease TTL ceiling must be between 1 second and 5 minutes");
        }
        this.capacity = capacity;
        this.clock = clock;
        this.maxTtl = maxTtl;
    }

    /**
     * Registers a lease for {@code token}; the effective deadline is {@code min(now + ttl, grantExpiresAt)}.
     *
     * @return the opaque lease id (43 base64url chars from 256 random bits)
     */
    public String issue(String token, Instant grantExpiresAt, Duration ttl,
                        String generationId, String bindingId, long actorId) {
        if (token == null || token.isBlank() || token.length() > 512) {
            throw new IllegalArgumentException("Invalid grant token");
        }
        if (grantExpiresAt == null) throw new IllegalArgumentException("Grant deadline required");
        if (ttl == null || ttl.isZero() || ttl.isNegative()) throw new IllegalArgumentException("Lease TTL must be positive");
        if (generationId == null || bindingId == null) throw new IllegalArgumentException("Lease binding required");
        Instant now = clock.instant();
        Duration effective = ttl.compareTo(maxTtl) > 0 ? maxTtl : ttl;
        Instant deadline = now.plus(effective);
        if (grantExpiresAt.isBefore(deadline)) deadline = grantExpiresAt;
        if (!deadline.isAfter(now)) throw new IllegalArgumentException("Grant is already expired");
        synchronized (this) {
            purgeExpired(now);
            if (leases.size() >= capacity) throw new LeaseUnavailableException();
            String leaseId = newLeaseId();
            leases.put(hash(leaseId), new Entry(token, deadline, generationId, bindingId, actorId));
            return leaseId;
        }
    }

    /** Unknown, expired and revoked leases are indistinguishable: empty. */
    public Optional<Resolved> resolve(String leaseId) {
        if (leaseId == null || leaseId.length() != LEASE_CHARS) return Optional.empty();
        Instant now = clock.instant();
        synchronized (this) {
            purgeExpired(now);
            Entry entry = leases.get(hash(leaseId));
            if (entry == null) return Optional.empty();
            if (!entry.expiresAt.isAfter(now)) { leases.remove(hash(leaseId)); return Optional.empty(); }
            return Optional.of(new Resolved(entry.token, entry.expiresAt, entry.generationId, entry.bindingId, entry.actorId));
        }
    }

    /** Platform-internal revocation (never an HTTP surface). */
    public boolean revoke(String leaseId) {
        if (leaseId == null || leaseId.length() != LEASE_CHARS) return false;
        synchronized (this) {
            return leases.remove(hash(leaseId)) != null;
        }
    }

    public synchronized int size() {
        purgeExpired(clock.instant());
        return leases.size();
    }

    public int capacity() { return capacity; }

    @Override public String toString() { return "McpLeaseRegistry[size=" + size() + ",capacity=" + capacity + "]"; }

    private String newLeaseId() {
        byte[] bytes = new byte[LEASE_BYTES];
        String id;
        do {
            random.nextBytes(bytes);
            id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } while (leases.containsKey(hash(id)));
        return id;
    }

    private void purgeExpired(Instant now) {
        leases.values().removeIf(entry -> !entry.expiresAt.isAfter(now));
    }

    private static String hash(String leaseId) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(leaseId.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private record Entry(String token, Instant expiresAt, String generationId, String bindingId, long actorId) {}

    /** Capacity exhaustion fails closed; the caller must not fall back to any weaker credential. */
    public static final class LeaseUnavailableException extends RuntimeException {
        public LeaseUnavailableException() { super("MCP lease capacity exhausted", null, false, false); }
    }

    /** A resolved lease is still not authority: the token is revalidated by the grant path on every request. */
    public static final class Resolved {
        private final String token;
        private final Instant expiresAt;
        private final String generationId;
        private final String bindingId;
        private final long actorId;

        Resolved(String token, Instant expiresAt, String generationId, String bindingId, long actorId) {
            this.token = token;
            this.expiresAt = expiresAt;
            this.generationId = generationId;
            this.bindingId = bindingId;
            this.actorId = actorId;
        }

        public String token() { return token; }
        public Instant expiresAt() { return expiresAt; }
        public String generationId() { return generationId; }
        public String bindingId() { return bindingId; }
        public long actorId() { return actorId; }

        @Override public String toString() { return "McpLease[REDACTED]"; }
    }
}
