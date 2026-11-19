package com.haitlify.aitest.platform.assistant.execution;

import com.haitlify.aitest.platform.assistant.security.capability.CapabilityTokenCodec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.function.Function;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Internal authorization foundation for a future trusted worker/MCP adapter.
 * It is deliberately not an HTTP endpoint, model interface, isolation verifier, or runtime launcher.
 * Allocation is the admission point for immutable worker metadata; a caller must not invent a verified
 * isolation claim from these fields.
 *
 * Lock order: unit -> generation -> binding -> grant -> tenant membership -> project membership
 * -> actor -> tenant -> project. Membership-first agrees with the existing user-deletion writer.
 * Discovery reads are not authorization. Every authority row is re-read with FOR UPDATE and held
 * through callback commit. A committed revocation before these reads must deny; a later writer waits.
 * All public service entries reject ambient transactions (including callback reentry).
 * Callbacks MUST only perform DB work on this transaction's datasource, without starting another
 * transaction or acquiring earlier-order execution/authority rows. External I/O is forbidden;
 * future dispatch must write a transactional outbox, not call a worker/network from this callback.
 */
@Service
public final class ExecutionBindingService {
    public static final String AUDIENCE = "assistant-tool-worker";
    private static final Set<String> ACTIONS = Set.of("generation_context", "draft_create", "draft_get");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}");
    private final JdbcTemplate jdbc;
    private final CapabilityTokenCodec codec;
    private final TransactionTemplate transaction;
    private final Clock clock;

    @Autowired
    public ExecutionBindingService(JdbcTemplate jdbc, CapabilityTokenCodec codec,
                                   PlatformTransactionManager manager, ObjectProvider<Clock> clocks) {
        this(jdbc, codec, manager, clocks.getIfAvailable(Clock::systemUTC));
    }

    /** Package-visible constructor is useful for deterministic integration tests without a global clock. */
    ExecutionBindingService(JdbcTemplate jdbc, CapabilityTokenCodec codec,
                            PlatformTransactionManager manager, Clock clock) {
        this.jdbc = jdbc;
        this.codec = codec;
        this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public record WorkerUnit(String unitId, String imageDigest, String policyVersion) {
        public WorkerUnit {
            requireId(unitId, "unit id");
            if (imageDigest == null || !imageDigest.matches("sha256:[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid image digest");
            requireId(policyVersion, "policy version");
        }
    }
    public record AllocationRequest(String generationId, String workerUnitId, Duration ttl) {
        public AllocationRequest {
            requireId(generationId, "generation id"); requireId(workerUnitId, "worker unit id");
            if (ttl == null || ttl.compareTo(Duration.ofSeconds(1)) < 0 || ttl.compareTo(Duration.ofMinutes(5)) > 0) throw new IllegalArgumentException("TTL must be between 1 second and 5 minutes");
        }
    }
    public record Binding(String bindingId, String generationId, String workerUnitId, long leaseGeneration,
                          String imageDigest, String policyVersion, Instant expiresAt) {}
    public record IssueRequest(String bindingId, long actorId) {}
    /** Sent only to the trusted cleanup adapter. Not proof of physical cleanup by itself. */
    public record CleanupTicket(String workerUnitId, String bindingId, long leaseGeneration, String epoch) {}
    public enum Action { GENERATION_CONTEXT("generation_context"), DRAFT_CREATE("draft_create"), DRAFT_GET("draft_get");
        private final String value; Action(String value) { this.value = value; }
        String value() { return value; }
    }
    public record Scope(long actorId, long tenantId, long projectId, String generationId,
                        String bindingId, long leaseGeneration) {}
    /** Current authorization snapshot, not authority to skip subsequent request validation. */
    public record AuthorizedGrant(Scope scope, Instant expiresAt) {}
    public record IssuedGrant(RawToken rawToken, Scope scope, Instant expiresAt) {}
    public static final class RawToken {
        private String value;
        private RawToken(String value) { this.value = value; }
        public synchronized String reveal() {
            if (value == null) throw new IllegalStateException("Capability token already handed off");
            String result = value; value = null; return result;
        }
        @Override public String toString() { return "CapabilityToken[REDACTED]"; }
    }
    public static class AuthorizationException extends RuntimeException {
        public AuthorizationException(String message) { super(message); }
    }
    /** The service clock says cleanup is not due; callers must not mutate authorization state. */
    public static final class CleanupNotDueException extends AuthorizationException {
        public CleanupNotDueException(String message) { super(message); }
    }
    public static class AllocationConflictException extends RuntimeException {
        public AllocationConflictException(String message) { super(message); }
    }

    public CleanupTicket registerUnit(WorkerUnit unit) {
        rejectAmbientTransaction();
        return transaction.execute(status -> {
            String epoch = UUID.randomUUID().toString();
            jdbc.update("insert into assistant_execution_worker_units(unit_id,image_digest,policy_version,active,cleanup_confirmed,current_lease_generation,cleanup_epoch,updated_at) values (?,?,?,true,false,0,?,?)",
                unit.unitId(), unit.imageDigest(), unit.policyVersion(), epoch, clock.instant());
            return new CleanupTicket(unit.unitId(), null, 0, epoch);
        });
    }

    public Binding allocate(AllocationRequest request) {
        return allocate(request, binding -> binding);
    }

    /** Package-only DB callback; commit claim and allocation together, never external I/O. */
    <T> T allocate(AllocationRequest request, Function<Binding,T> persistClaim) {
        rejectAmbientTransaction();
        return transaction.execute(status -> {
            UnitRow unit = jdbc.query("select * from assistant_execution_worker_units where unit_id=? for update", this::unit, request.workerUnitId())
                .stream().findFirst().orElseThrow(() -> new AllocationConflictException("worker unit is not allocator-registered"));
            if (!unit.active() || !unit.cleanupConfirmed() || unit.currentBindingId()!=null || unit.cleanupEpoch()!=null) throw new AllocationConflictException("worker unit is inactive or cleanup is unconfirmed");
            GenerationRow generation = jdbc.query("select * from assistant_generations where id=? for update", this::generation, request.generationId())
                .stream().findFirst().orElseThrow(() -> new AllocationConflictException("generation not found"));
            if (!generation.state().equals("WAITING_WORKER")) throw new AllocationConflictException("generation is not allocatable");
            Instant now = clock.instant();
            // Generation lock serializes all writers of this unique active key; no cross-unit binding locks.
            var activeForGeneration = jdbc.query("select * from assistant_execution_bindings where generation_id=? and active_generation_key is not null", this::bindingMinimal, request.generationId());
            if (!activeForGeneration.isEmpty()) throw new AllocationConflictException("generation already has an active binding");
            var activeForUnit = jdbc.query("select * from assistant_execution_bindings where worker_unit_id=? and active_worker_unit_key is not null", this::bindingMinimal, request.workerUnitId());
            if (!activeForUnit.isEmpty()) throw new AllocationConflictException("worker unit is already bound");
            long lease = Math.addExact(unit.currentLease(), 1);
            String id = UUID.randomUUID().toString();
            Instant expiry = now.plus(request.ttl());
            try {
                jdbc.update("insert into assistant_execution_bindings(id,generation_id,worker_unit_id,lease_generation,state,active_generation_key,active_worker_unit_key,expires_at,created_at) values (?,?,?,?,?,?,?,?,?)",
                    id, request.generationId(), request.workerUnitId(), lease, "ACTIVE", request.generationId(), request.workerUnitId(), expiry, now);
            } catch (DataIntegrityViolationException ex) {
                throw new AllocationConflictException("binding allocation conflict");
            }
            // New binding insert is locked before authority rows. Allocation consumes the cleaned epoch.
            authorizeActor(generation.actorId(), new BindingRow(id, request.generationId(), unit.id(), lease, "ACTIVE", expiry, generation.actorId(), generation.tenantId(), generation.projectId()));
            int changed = jdbc.update("update assistant_execution_worker_units set current_binding_id=?,current_lease_generation=?,cleanup_confirmed=false,updated_at=? where unit_id=? and current_binding_id is null and current_lease_generation=? and cleanup_confirmed=true and cleanup_epoch is null",
                id, lease, now, unit.id(), unit.currentLease());
            if (changed!=1) throw new AllocationConflictException("worker lease changed");
            return persistClaim.apply(new Binding(id, request.generationId(), unit.id(), lease, unit.imageDigest(), unit.policyVersion(), expiry));
        });
    }

    /** Preflight only; the host must fence the same lease again at external use. */
    void requireConfigurable(Binding expected) {
        rejectAmbientTransaction();
        transaction.executeWithoutResult(s -> {
            LockedBinding locked = lockBinding(expected.bindingId());
            BindingRow b = locked.binding();
            if (!currentOwner(locked) || !locked.unit().active() || locked.unit().cleanupEpoch() != null
                || !b.state().equals("ACTIVE") || !b.expiresAt().isAfter(clock.instant())
                || !b.expiresAt().equals(expected.expiresAt()) || b.leaseGeneration() != expected.leaseGeneration()
                || !locked.generation().state().equals("CONFIGURING")) throw new AuthorizationException("Stale configure lease");
            authorizeActor(b.actorId(), b);
        });
    }

    public IssuedGrant issueGrant(IssueRequest request) {
        return issueGrant(request, () -> {});
    }

    /** DB-only coordinator transition under the canonical binding locks, committed with the grant. */
    IssuedGrant issueGrant(IssueRequest request, Runnable beforeIssue) {
        rejectAmbientTransaction();
        return transaction.execute(status -> {
            LockedBinding locked = lockBinding(request.bindingId());
            beforeIssue.run();
            // Callback may transition generation from CONFIGURING to RUNNING under this lock.
            GenerationRow current = jdbc.query("select * from assistant_generations where id=?", this::generation, locked.binding().generationId()).getFirst();
            locked = new LockedBinding(locked.unit(), current, locked.binding());
            BindingRow binding = locked.binding();
            requireUsable(locked);
            lockGrants(binding.id());
            if (!binding.state().equals("ACTIVE") || !binding.expiresAt().isAfter(clock.instant())) throw new AuthorizationException("binding is expired or inactive");
            CapabilityTokenCodec.IssuedToken token = codec.issue();
            Instant expiry = binding.expiresAt();
            if (request.actorId() != binding.actorId()) throw new AuthorizationException("actor does not own generation");
            jdbc.update("insert into assistant_execution_grants(id,binding_id,actor_id,token_hash,audience,actions,expires_at,revoked,created_at) values (?,?,?,?,?,?,?,false,?)",
                UUID.randomUUID().toString(), binding.id(), request.actorId(), token.hash(), AUDIENCE, String.join(",", ACTIONS), expiry, clock.instant());
            Scope scope = authorizeActor(request.actorId(), binding);
            // TransactionTemplate commits before returning this value to the public caller.
            return new IssuedGrant(new RawToken(token.rawToken().reveal()), scope, expiry);
        });
    }

    public <T> T withAuthorizedGrant(RawToken raw, Action action, Function<Scope, T> callback) {
        rejectAmbientTransaction();
        if (raw == null || action == null || callback == null) throw new IllegalArgumentException("grant/action/callback required");
        return withAuthorizedGrantInternal(raw.reveal(), action.value(), grant -> callback.apply(grant.scope()));
    }
    public <T> T withAuthorizedGrant(RawToken raw, String action, Function<Scope, T> callback) {
        rejectAmbientTransaction();
        if (raw == null || action == null || callback == null) throw new IllegalArgumentException("grant/action/callback required");
        if (!ACTIONS.contains(action)) throw new IllegalArgumentException("unsupported tool action");
        return withAuthorizedGrantInternal(raw.reveal(), action, grant -> callback.apply(grant.scope()));
    }
    public <T> T withAuthorizedGrant(String raw, String action, Function<Scope, T> callback) {
        rejectAmbientTransaction();
        if (raw == null || action == null || callback == null) throw new IllegalArgumentException("grant/action/callback required");
        if (!ACTIONS.contains(action)) throw new IllegalArgumentException("unsupported tool action");
        return withAuthorizedGrantInternal(raw, action, grant -> callback.apply(grant.scope()));
    }

    public AuthorizedGrant inspectAuthorizedGrant(String raw, String action) {
        rejectAmbientTransaction();
        if (raw == null || action == null) throw new IllegalArgumentException("grant/action required");
        if (!ACTIONS.contains(action)) throw new IllegalArgumentException("unsupported tool action");
        return withAuthorizedGrantInternal(raw, action, grant -> grant);
    }

    private <T> T withAuthorizedGrantInternal(String raw, String action, Function<AuthorizedGrant, T> callback) {
        final String hash;
        try { hash = codec.hashPresented(raw); } catch (RuntimeException ex) { throw new AuthorizationException("invalid capability token"); }
        return transaction.execute(status -> {
            GrantRow located = jdbc.query("select * from assistant_execution_grants where token_hash=?", this::grant, hash)
                .stream().findFirst().orElseThrow(() -> new AuthorizationException("grant not found"));
            LockedBinding locked = lockBinding(located.bindingId());
            BindingRow binding = locked.binding();
            requireUsable(locked);
            // Unit -> generation -> binding -> grant, followed by current authority rows.
            GrantRow grant = jdbc.query("select * from assistant_execution_grants where token_hash=? and binding_id=? for update", this::grant, hash, binding.id())
                .stream().findFirst().orElseThrow(() -> new AuthorizationException("grant not found"));
            Scope scope = authorizeActor(grant.actorId(), binding);
            if (grant.actorId() != binding.actorId()) throw new AuthorizationException("grant actor does not own generation");
            if (grant.revoked() || !grant.audience().equals(AUDIENCE) || !Arrays.asList(grant.actions().split(",", -1)).contains(action)
                || !binding.state().equals("ACTIVE") || !grant.expiresAt().isAfter(clock.instant()) || !binding.expiresAt().isAfter(clock.instant())
                ) throw new AuthorizationException("grant is expired, revoked, stale, or out of scope");
            Instant expiry = grant.expiresAt().isBefore(binding.expiresAt()) ? grant.expiresAt() : binding.expiresAt();
            return callback.apply(new AuthorizedGrant(scope, expiry));
        });
    }

    public void revoke(String bindingId) {
        rejectAmbientTransaction();
        transaction.executeWithoutResult(status -> {
            LockedBinding locked = lockBinding(bindingId);
            // An old callback has no right to mutate any part of the current lease.
            if (!currentOwner(locked) || !locked.binding().state().equals("ACTIVE")) return;
            revokeLocked(locked);
        });
    }

    private void revokeLocked(LockedBinding locked) {
        BindingRow b = locked.binding();
        lockGrants(b.id());
        jdbc.update("update assistant_execution_grants set revoked=true where binding_id=?", b.id());
        jdbc.update("update assistant_execution_bindings set state='REVOKED',active_generation_key=null,active_worker_unit_key=null,revoked_at=? where id=? and state='ACTIVE'", clock.instant(), b.id());
        if (jdbc.update("update assistant_execution_worker_units set cleanup_confirmed=false,updated_at=? where unit_id=? and current_binding_id=? and current_lease_generation=?",
            clock.instant(), b.workerUnitId(), b.id(), b.leaseGeneration())!=1) throw new AuthorizationException("stale lease");
    }

    /** Start/retrieve a durable cleanup operation; expiry alone never makes a unit reusable. */
    public CleanupTicket requestCleanup(String bindingId, long expectedLease) {
        rejectAmbientTransaction();
        return transaction.execute(status -> {
            LockedBinding locked = lockBinding(bindingId);
            BindingRow b = locked.binding();
            if (!currentOwner(locked) || expectedLease!=b.leaseGeneration() || b.state().equals("CLEANED")) throw new AuthorizationException("stale cleanup request");
            if (b.state().equals("ACTIVE")) {
                if (b.expiresAt().isAfter(clock.instant())) throw new CleanupNotDueException("active lease cannot be cleaned");
                revokeLocked(locked);
            } else lockGrants(b.id());
            String epoch = locked.unit().cleanupEpoch();
            if (epoch==null) {
                epoch = UUID.randomUUID().toString();
                if (jdbc.update("update assistant_execution_worker_units set cleanup_epoch=?,updated_at=? where unit_id=? and current_binding_id=? and current_lease_generation=? and cleanup_epoch is null",
                    epoch, clock.instant(), b.workerUnitId(), b.id(), expectedLease)!=1) throw new AuthorizationException("cleanup epoch changed");
            }
            return new CleanupTicket(b.workerUnitId(), b.id(), expectedLease, epoch);
        });
    }

    /** Fail closed for legacy callbacks: a unit ID cannot identify which lease was cleaned. */
    @Deprecated
    public void confirmUnitCleaned(String workerUnitId) {
        rejectAmbientTransaction();
        throw new AuthorizationException("binding, lease and cleanup epoch are required");
    }

    /** Only a trusted adapter may acknowledge actual cleanup, using the exact dispatched ticket. */
    public void confirmUnitCleaned(CleanupTicket ticket) {
        confirmUnitCleaned(ticket, () -> {});
    }

    /** DB-only audit callback, committed atomically with cleanup in this service's own transaction. */
    void confirmUnitCleaned(CleanupTicket ticket, Runnable persistReceipt) {
        rejectAmbientTransaction();
        if (ticket==null || ticket.epoch()==null) throw new AuthorizationException("cleanup ticket required");
        transaction.executeWithoutResult(status -> {
            UnitRow unit;
            if (ticket.bindingId()==null) {
                unit = lockUnit(ticket.workerUnitId());
                if (ticket.leaseGeneration()!=0 || unit.currentLease()!=0 || unit.currentBindingId()!=null) throw new AuthorizationException("stale initial cleanup");
            } else {
                LockedBinding locked = lockBinding(ticket.bindingId());
                unit = locked.unit();
                if (!currentOwner(locked) || !locked.binding().state().equals("REVOKED") || ticket.leaseGeneration()!=locked.binding().leaseGeneration()) throw new AuthorizationException("stale cleanup");
                lockGrants(ticket.bindingId());
            }
            if (!unit.id().equals(ticket.workerUnitId()) || !Objects.equals(unit.cleanupEpoch(), ticket.epoch()) || unit.cleanupConfirmed()) throw new AuthorizationException("stale cleanup epoch");
            int changed = jdbc.update("update assistant_execution_worker_units set cleanup_confirmed=true,current_binding_id=null,cleanup_epoch=null,updated_at=? where unit_id=? and current_lease_generation=? and cleanup_epoch=? and cleanup_confirmed=false and (current_binding_id=? or (current_binding_id is null and ? is null))",
                clock.instant(), unit.id(), ticket.leaseGeneration(), ticket.epoch(), ticket.bindingId(), ticket.bindingId());
            if (changed!=1) throw new AuthorizationException("cleanup ownership changed");
            if (ticket.bindingId()!=null) {
                jdbc.update("update assistant_execution_grants set revoked=true where binding_id=?", ticket.bindingId());
                jdbc.update("update assistant_execution_bindings set state='CLEANED',active_generation_key=null,active_worker_unit_key=null where id=? and lease_generation=? and state='REVOKED'", ticket.bindingId(), ticket.leaseGeneration());
            }
            persistReceipt.run();
        });
    }

    private static void rejectAmbientTransaction() {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new AuthorizationException("ambient transactions and callback reentry are forbidden");
    }

    private UnitRow lockUnit(String id) {
        return jdbc.query("select * from assistant_execution_worker_units where unit_id=? for update", this::unit, id)
            .stream().findFirst().orElseThrow(() -> new AuthorizationException("unit not found"));
    }
    private LockedBinding lockBinding(String id) {
        // Discovery only. These immutable IDs are revalidated after explicit canonical locks; no join locks.
        BindingRow found = jdbc.query("select * from assistant_execution_bindings where id=?", this::bindingMinimal, id)
            .stream().findFirst().orElseThrow(() -> new AuthorizationException("binding not found"));
        UnitRow unit = lockUnit(found.workerUnitId());
        GenerationRow g = jdbc.query("select * from assistant_generations where id=? for update", this::generation, found.generationId())
            .stream().findFirst().orElseThrow(() -> new AuthorizationException("generation not found"));
        BindingRow b = jdbc.query("select * from assistant_execution_bindings where id=? for update", this::bindingMinimal, id)
            .stream().findFirst().orElseThrow(() -> new AuthorizationException("binding not found"));
        if (!b.workerUnitId().equals(found.workerUnitId()) || !b.generationId().equals(found.generationId()) || b.leaseGeneration()!=found.leaseGeneration()) throw new AuthorizationException("binding identity changed");
        return new LockedBinding(unit, g, new BindingRow(b.id(), b.generationId(), b.workerUnitId(), b.leaseGeneration(), b.state(), b.expiresAt(), g.actorId(), g.tenantId(), g.projectId()));
    }
    private void lockGrants(String bindingId) {
        jdbc.query("select * from assistant_execution_grants where binding_id=? for update", this::grant, bindingId);
    }
    private boolean currentOwner(LockedBinding b) {
        return b.binding().id().equals(b.unit().currentBindingId()) && b.binding().leaseGeneration()==b.unit().currentLease();
    }
    private void requireUsable(LockedBinding b) {
        if (!currentOwner(b) || !b.unit().active() || b.unit().cleanupConfirmed() || b.unit().cleanupEpoch()!=null
            || !b.binding().state().equals("ACTIVE") || !b.binding().expiresAt().isAfter(clock.instant())
            || !Set.of("WAITING_WORKER", "RUNNING", "READY").contains(b.generation().state())) throw new AuthorizationException("inactive or stale execution lease");
    }
    private Scope authorizeActor(long actorId, BindingRow b) {
        // Membership-first matches UserManagementService.delete: tenant membership -> project membership -> actor.
        // The username discovery is not authority: revalidate it against the subsequently locked actor.
        String username = jdbc.query("select username from platform_users where id=?", (rs,n) -> rs.getString(1), actorId)
            .stream().findFirst().orElseThrow(() -> new AuthorizationException("actor missing"));
        var tenantMember = jdbc.query("select username from tenant_members where tenant_id=? and username=? for update", (rs,n) -> rs.getString(1), b.tenantId(), username);
        var projectMember = jdbc.query("select username from project_members where project_id=? and username=? for update", (rs,n) -> rs.getString(1), b.projectId(), username);
        ActorRow actor = jdbc.query("select id,username,role from platform_users where id=? and enabled=true for update", (rs, n) -> new ActorRow(rs.getLong("id"), rs.getString("username"), rs.getString("role")), actorId)
            .stream().findFirst().orElseThrow(() -> new AuthorizationException("actor is disabled or missing"));
        if (!username.equals(actor.username()) || !Set.of("EDITOR", "ADMIN").contains(actor.role())) throw new AuthorizationException("actor identity or role changed");
        var tenant = jdbc.query("select active from tenants where id=? for update", (rs,n) -> rs.getBoolean(1), b.tenantId());
        var project = jdbc.query("select active,tenant_id from projects where id=? for update", (rs,n) -> rs.getBoolean(1) && rs.getLong(2)==b.tenantId(), b.projectId());
        if (tenant.size()!=1 || !tenant.getFirst() || project.size()!=1 || !project.getFirst()) throw new AuthorizationException("tenant/project is inactive or mismatched");
        if (!actor.role().equals("ADMIN") && (tenantMember.size()!=1 || projectMember.size()!=1)) throw new AuthorizationException("actor is not a member of tenant and project");
        return new Scope(actor.id(), b.tenantId(), b.projectId(), b.generationId(), b.id(), b.leaseGeneration());
    }

    private UnitRow unit(ResultSet rs, int n) throws SQLException { return new UnitRow(rs.getString("unit_id"), rs.getString("image_digest"), rs.getString("policy_version"), rs.getBoolean("active"), rs.getBoolean("cleanup_confirmed"), rs.getString("current_binding_id"), rs.getLong("current_lease_generation"), rs.getString("cleanup_epoch")); }
    private GenerationRow generation(ResultSet rs, int n) throws SQLException { return new GenerationRow(rs.getString("state"), rs.getLong("actor_id"), rs.getLong("tenant_id"), rs.getLong("project_id")); }
    private BindingRow bindingMinimal(ResultSet rs, int n) throws SQLException { return new BindingRow(rs.getString("id"), rs.getString("generation_id"), rs.getString("worker_unit_id"), rs.getLong("lease_generation"), rs.getString("state"), rs.getTimestamp("expires_at").toInstant(), 0, 0, 0); }
    private GrantRow grant(ResultSet rs, int n) throws SQLException { return new GrantRow(rs.getString("binding_id"), rs.getLong("actor_id"), rs.getString("audience"), rs.getString("actions"), rs.getTimestamp("expires_at").toInstant(), rs.getBoolean("revoked")); }
    private record UnitRow(String id, String imageDigest, String policyVersion, boolean active, boolean cleanupConfirmed, String currentBindingId, long currentLease, String cleanupEpoch) {}
    private record LockedBinding(UnitRow unit, GenerationRow generation, BindingRow binding) {}
    private record GenerationRow(String state, long actorId, long tenantId, long projectId) {}
    private record ActorRow(long id, String username, String role) {}
    private record BindingRow(String id, String generationId, String workerUnitId, long leaseGeneration, String state, Instant expiresAt, long actorId, long tenantId, long projectId) {}
    private record GrantRow(String bindingId, long actorId, String audience, String actions, Instant expiresAt, boolean revoked) {}
    private static void requireId(String value, String name) { if (value == null || !ID.matcher(value).matches()) throw new IllegalArgumentException("Invalid " + name); }
}
