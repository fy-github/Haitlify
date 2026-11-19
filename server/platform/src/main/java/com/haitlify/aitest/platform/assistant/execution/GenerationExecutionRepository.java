package com.haitlify.aitest.platform.assistant.execution;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable one-attempt-per-generation state; no retry or takeover API. */
public final class GenerationExecutionRepository {
    final JdbcTemplate jdbc;
    final TransactionTemplate transaction;
    public GenerationExecutionRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.transaction = new TransactionTemplate(manager);
        this.transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public record Attempt(ExecutionBindingService.Binding binding, String ownerId, String incarnation, String status, String cleanupEpoch) {}
    public Attempt find(String generation) {
        return jdbc.query("select * from assistant_generation_executions where generation_id=?", (r,n) -> attempt(r), generation).stream().findFirst().orElse(null);
    }
    Attempt findForUpdate(String generation) {
        return jdbc.query("select * from assistant_generation_executions where generation_id=? for update", (r,n) -> attempt(r), generation).stream().findFirst().orElse(null);
    }
    private Attempt attempt(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Attempt(new ExecutionBindingService.Binding(r.getString("binding_id"), r.getString("generation_id"), r.getString("worker_unit_id"),
                r.getLong("lease_generation"), r.getString("image_digest"), r.getString("policy_version"), r.getTimestamp("expires_at").toInstant()),
            r.getString("owner_id"), r.getString("incarnation"), r.getString("status"), r.getString("cleanup_epoch"));
    }
    boolean transition(Attempt a, String from, String to) {
        return jdbc.update("update assistant_generation_executions set status=? where generation_id=? and binding_id=? and lease_generation=? and incarnation=? and owner_id=? and status=?",
            to, a.binding().generationId(), a.binding().bindingId(), a.binding().leaseGeneration(), a.incarnation(), a.ownerId(), from) == 1;
    }
    boolean beginCleanup(Attempt a) {
        return transaction.execute(s -> {
            jdbc.queryForList("select id from assistant_generations where id=? for update", a.binding().generationId());
            int changed = jdbc.update("update assistant_generation_executions set status='REVOKING' where generation_id=? and binding_id=? and worker_unit_id=? and lease_generation=? and incarnation=? and owner_id=? and status in ('CONFIGURING','CONFIGURE_SENT','GRANT_ISSUED','AUTHORIZED','UNKNOWN','QUARANTINED')",
                a.binding().generationId(), a.binding().bindingId(), a.binding().workerUnitId(), a.binding().leaseGeneration(), a.incarnation(), a.ownerId());
            if (changed == 1) jdbc.update("update assistant_generations set state='UNKNOWN' where id=?", a.binding().generationId());
            return changed == 1;
        });
    }
    void cleanupEpoch(Attempt a, String epoch) {
        if (jdbc.update("update assistant_generation_executions set cleanup_epoch=? where generation_id=? and incarnation=? and status='REVOKING'", epoch, a.binding().generationId(), a.incarnation()) != 1)
            throw new ExecutionBindingService.AuthorizationException("Cleanup changed");
    }
    long actor(Attempt a) {
        return jdbc.queryForObject("select actor_id from assistant_generations where id=?", Long.class, a.binding().generationId());
    }
    void authorize(Attempt a) {
        if (!transition(a, "CONFIGURE_SENT", "GRANT_ISSUED")) throw new ExecutionBindingService.AuthorizationException("Stale configure result");
        if (jdbc.update("update assistant_generations set state='RUNNING' where id=? and state='CONFIGURING'", a.binding().generationId()) != 1)
            throw new ExecutionBindingService.AuthorizationException("Generation no longer configuring");
    }
    void quarantine(Attempt a) { fence(a, "QUARANTINED"); }
    /** A fresh transaction can persist uncertainty even when the quarantine write rolled back. */
    void unknown(Attempt a) { fence(a, "UNKNOWN"); }
    void markCleaned(WorkerCleanupReceiptService.CleanupIdentity identity) {
        int changed = jdbc.update("update assistant_generation_executions set status='CLEANED' where generation_id=? and binding_id=? and worker_unit_id=? and lease_generation=? and incarnation=? and owner_id=? and cleanup_epoch=? and status in ('QUARANTINED','UNKNOWN','VERIFIED')",
            identity.generationId(), identity.bindingId(), identity.workerUnitId(), identity.leaseGeneration(), identity.incarnation(), identity.ownerId(), identity.cleanupEpoch());
        if (changed != 1) throw new ExecutionBindingService.AuthorizationException("Execution cleanup identity changed");
    }
    void markUnknown(WorkerCleanupReceiptService.CleanupIdentity identity) {
        transaction.executeWithoutResult(s -> fence(identity, "UNKNOWN"));
    }
    void markQuarantined(WorkerCleanupReceiptService.CleanupIdentity identity) {
        transaction.executeWithoutResult(s -> fence(identity, "QUARANTINED"));
    }
    private void fence(Attempt a, String status) {
        transaction.executeWithoutResult(s -> {
            if (jdbc.update("update assistant_generations set state='UNKNOWN' where id=?", a.binding().generationId()) != 1)
                throw new ExecutionBindingService.AuthorizationException("Generation missing");
            if (jdbc.update("update assistant_generation_executions set status=? where generation_id=? and binding_id=? and lease_generation=? and incarnation=? and owner_id=? and status<>'CLEANED'",
                status, a.binding().generationId(), a.binding().bindingId(), a.binding().leaseGeneration(), a.incarnation(), a.ownerId()) != 1)
                throw new ExecutionBindingService.AuthorizationException("Execution fence changed");
        });
    }
    private void fence(WorkerCleanupReceiptService.CleanupIdentity i, String status) {
        if (jdbc.update("update assistant_generations set state='UNKNOWN' where id=?", i.generationId()) != 1)
            throw new ExecutionBindingService.AuthorizationException("Generation missing");
        if (jdbc.update("update assistant_generation_executions set status=? where generation_id=? and binding_id=? and worker_unit_id=? and lease_generation=? and incarnation=? and owner_id=? and (cleanup_epoch=? or (cleanup_epoch is null and ? is null)) and status<>'CLEANED'",
            status, i.generationId(), i.bindingId(), i.workerUnitId(), i.leaseGeneration(), i.incarnation(), i.ownerId(), i.cleanupEpoch(), i.cleanupEpoch()) != 1)
            throw new ExecutionBindingService.AuthorizationException("Execution fence changed");
    }
    Attempt insert(ExecutionBindingService.Binding b, String owner) {
        String incarnation = UUID.randomUUID().toString();
        jdbc.update("insert into assistant_generation_executions(generation_id,binding_id,worker_unit_id,lease_generation,image_digest,policy_version,expires_at,owner_id,incarnation,status) values (?,?,?,?,?,?,?,?,?,'CONFIGURING')",
            b.generationId(), b.bindingId(), b.workerUnitId(), b.leaseGeneration(), b.imageDigest(), b.policyVersion(), b.expiresAt(), owner, incarnation);
        if (jdbc.update("update assistant_generations set state='CONFIGURING' where id=? and state='WAITING_WORKER'", b.generationId()) != 1)
            throw new ExecutionBindingService.AuthorizationException("Generation claim changed");
        return find(b.generationId());
    }
}
