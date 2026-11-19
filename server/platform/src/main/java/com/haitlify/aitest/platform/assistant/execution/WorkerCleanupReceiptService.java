package com.haitlify.aitest.platform.assistant.execution;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Explicit non-bean contract, not a physical cleaner. No HTTP/Docker/scheduler caller is provided.
 * The injected verifier must independently verify resource inventory completeness, exact owner,
 * binding/lease/incarnation labels and deletion readback against the evidence digest. Caller
 * booleans or a verifier name are not proof. The default adapter never verifies anything.
 * Verification runs outside transactions. Cleanup plus receipt/coordinator status commits in a
 * separate ExecutionBindingService transaction; its DB-only callback cannot perform external I/O.
 */
public final class WorkerCleanupReceiptService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ExecutionBindingService execution;
    private final GenerationExecutionRepository repository;
    private final CleanupVerifier verifier;
    private final ObjectMapper json = new ObjectMapper();

    public WorkerCleanupReceiptService(JdbcTemplate jdbc, PlatformTransactionManager manager,
            ExecutionBindingService execution, GenerationExecutionRepository repository) {
        this(jdbc, manager, execution, repository, CleanupVerifier.disabled());
    }
    public WorkerCleanupReceiptService(JdbcTemplate jdbc, PlatformTransactionManager manager,
            ExecutionBindingService execution, GenerationExecutionRepository repository, CleanupVerifier verifier) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.execution = Objects.requireNonNull(execution);
        this.repository = Objects.requireNonNull(repository);
        this.verifier = Objects.requireNonNull(verifier);
    }
    public enum ReceiptStatus { QUARANTINED, UNKNOWN, VERIFIED, CLEANED }
    public enum VerificationResult { VERIFIED, UNKNOWN, FAILED }
    /** Extensible immutable inventory labels; completeness is an obligation of the trusted verifier. */
    public record ResourceIdentities(String containerId, String networkId, Map<String,String> labels) {
        public ResourceIdentities {
            id(containerId, 255); id(networkId, 255);
            if (labels == null || labels.isEmpty()) throw new IllegalArgumentException("Resource labels required");
            labels.forEach((k,v) -> { id(k,255); id(v,255); });
            labels = Map.copyOf(labels);
        }
    }
    public record DeletionReadback(boolean containerAbsent, boolean networkAbsent) {}
    /** Opaque non-secret evidence locator and SHA-256 digest; no raw evidence body is accepted. */
    public record Evidence(String reference, String digest) {}
    public record CleanupReceipt(String receiptId, ExecutionBindingService.CleanupTicket ticket,
            String ownerId, String incarnation, ResourceIdentities resources, DeletionReadback deletion, Evidence evidence) {
        public CleanupReceipt {
            id(receiptId,36); id(ownerId,128); id(incarnation,36);
            if (ticket == null || resources == null || deletion == null || evidence == null) throw new IllegalArgumentException("Complete receipt required");
            id(ticket.bindingId(),36); id(ticket.workerUnitId(),128); id(ticket.epoch(),36);
            if (ticket.leaseGeneration() < 1) throw new IllegalArgumentException("Lease required");
        }
    }
    /** Epoch may be null only for a crash before cleanup was requested. */
    public record CleanupIdentity(String generationId, String bindingId, String workerUnitId, long leaseGeneration,
            String cleanupEpoch, String ownerId, String incarnation) {
        public CleanupIdentity {
            id(generationId,36); id(bindingId,36); id(workerUnitId,128); id(ownerId,128); id(incarnation,36);
            if (cleanupEpoch != null) id(cleanupEpoch,36);
            if (leaseGeneration < 1) throw new IllegalArgumentException("Lease required");
        }
    }
    public record ReceiptResult(String receiptId, ReceiptStatus status) {}
    public record CrashLeftIdentity(CleanupIdentity identity, ResourceIdentities resources) {
        public CrashLeftIdentity { Objects.requireNonNull(identity); Objects.requireNonNull(resources); }
    }
    @FunctionalInterface public interface CleanupVerifier {
        VerificationResult verify(CleanupReceipt receipt);
        static CleanupVerifier disabled() { return receipt -> VerificationResult.UNKNOWN; }
    }
    /** Trusted external adapter, not a caller-supplied owner string. No takeover is authorized here. */
    @FunctionalInterface public interface ExternalOwnerAttestation { boolean attest(CrashLeftIdentity identity); }

    public ReceiptResult submit(CleanupReceipt receipt) {
        GenerationExecutionCoordinator.rejectAmbient();
        validateReceipt(receipt);
        var prior = existing(receipt);
        if (prior != null && prior.status() == ReceiptStatus.CLEANED) return prior;
        CleanupIdentity identity;
        try {
            identity = transaction.execute(s -> {
                var i = discover(receipt);
                lockCurrent(i, true);
                if (existing(receipt) == null) insert(receipt, i);
                repository.markUnknown(i);
                return i;
            });
        } catch (RuntimeException conflict) {
            // An identical duplicate may have committed CLEANED after this submit's precheck: the unit
            // binding is then released and the stale snapshot cannot fence. Recognize only the durable
            // exact receipt (full identity/evidence/owner comparison); never a different receipt.
            var completed = durableCompleted(receipt);
            if (completed != null) return completed;
            throw conflict;
        }
        VerificationResult result;
        try { result = verifier.verify(receipt); }
        catch (RuntimeException failure) { result = VerificationResult.FAILED; }
        if (result == null) result = VerificationResult.UNKNOWN;
        if (result != VerificationResult.VERIFIED) {
            ReceiptStatus state = result == VerificationResult.FAILED ? ReceiptStatus.QUARANTINED : ReceiptStatus.UNKNOWN;
            String verdict = result.name();
            try {
                return transaction.execute(s -> {
                    lockCurrent(identity, true);
                    if (state == ReceiptStatus.QUARANTINED) repository.markQuarantined(identity);
                    else repository.markUnknown(identity);
                    update(receipt, state, verdict);
                    return new ReceiptResult(receipt.receiptId(), state);
                });
            } catch (RuntimeException conflict) {
                // Same duplicate race after verification: the winner is already durably CLEANED.
                var completed = durableCompleted(receipt);
                if (completed != null) return completed;
                throw conflict;
            }
        }
        try {
            execution.confirmUnitCleaned(receipt.ticket(), () -> {
                // Unit/generation/binding/grants are already locked in canonical order by execution.
                requireAttempt(identity, repository.findForUpdate(identity.generationId()));
                repository.markCleaned(identity);
                update(receipt, ReceiptStatus.CLEANED, "VERIFIED");
            });
        } catch (RuntimeException failure) {
            // A concurrent identical successful confirmation is idempotent, never re-confirm a later lease.
            var completed = existing(receipt);
            if (completed != null && completed.status() == ReceiptStatus.CLEANED) return completed;
            try {
                transaction.executeWithoutResult(s -> {
                    lockCurrent(identity, false);
                    repository.markUnknown(identity);
                    update(receipt, ReceiptStatus.UNKNOWN, "FAILED");
                });
            } catch (RuntimeException compensation) {
                throw new ExecutionBindingService.AuthorizationException("Cleanup confirmation and uncertainty persistence failed; inspect durable state");
            }
            throw new ExecutionBindingService.AuthorizationException("Cleanup confirmation failed; inspect durable state");
        }
        return new ReceiptResult(receipt.receiptId(), ReceiptStatus.CLEANED);
    }

    /** Fences only; even attested owners cannot revoke, reuse, or replay configure through this API. */
    public ReceiptResult reconcileCrashLeft(CrashLeftIdentity crashLeft, ExternalOwnerAttestation attestation) {
        GenerationExecutionCoordinator.rejectAmbient();
        Objects.requireNonNull(crashLeft);
        CleanupIdentity i = crashLeft.identity();
        transaction.executeWithoutResult(s -> { lockCurrent(i, false); repository.markUnknown(i); });
        boolean attested = false;
        try { attested = attestation != null && attestation.attest(crashLeft); }
        catch (RuntimeException failure) { /* Unknown owner; keep durable fence, no exception payload. */ }
        if (attested) transaction.executeWithoutResult(s -> { lockCurrent(i, false); repository.markQuarantined(i); });
        return new ReceiptResult("crash-left:" + i.incarnation(), attested ? ReceiptStatus.QUARANTINED : ReceiptStatus.UNKNOWN);
    }

    private CleanupIdentity discover(CleanupReceipt receipt) {
        var generations = jdbc.queryForList("select generation_id from assistant_generation_executions where binding_id=?", String.class, receipt.ticket().bindingId());
        if (generations.size()!=1) throw denied();
        var t = receipt.ticket();
        return new CleanupIdentity(generations.getFirst(), t.bindingId(), t.workerUnitId(), t.leaseGeneration(), t.epoch(), receipt.ownerId(), receipt.incarnation());
    }
    private void lockCurrent(CleanupIdentity i, boolean cleanup) {
        var units = jdbc.queryForList("select * from assistant_execution_worker_units where unit_id=? for update", i.workerUnitId());
        if (units.size()!=1) throw denied();
        var u = units.getFirst();
        if (!i.bindingId().equals(u.get("current_binding_id")) || ((Number)u.get("current_lease_generation")).longValue()!=i.leaseGeneration()) throw denied();
        if (cleanup && !Objects.equals(i.cleanupEpoch(),u.get("cleanup_epoch"))) throw denied();
        if (jdbc.queryForList("select id from assistant_generations where id=? for update", i.generationId()).size()!=1) throw denied();
        var bindings = jdbc.queryForList("select * from assistant_execution_bindings where id=? for update", i.bindingId());
        if (bindings.size()!=1) throw denied();
        var b = bindings.getFirst();
        if (!i.generationId().equals(b.get("generation_id")) || !i.workerUnitId().equals(b.get("worker_unit_id"))
                || ((Number)b.get("lease_generation")).longValue()!=i.leaseGeneration() || (cleanup && !"REVOKED".equals(b.get("state")))) throw denied();
        var a = repository.findForUpdate(i.generationId());
        requireAttempt(i,a);
        if (cleanup && !java.util.Set.of("QUARANTINED","UNKNOWN","REVOKING").contains(a.status())) throw denied();
    }
    private void requireAttempt(CleanupIdentity i, GenerationExecutionRepository.Attempt a) {
        if (a == null || !i.bindingId().equals(a.binding().bindingId()) || !i.workerUnitId().equals(a.binding().workerUnitId())
                || i.leaseGeneration()!=a.binding().leaseGeneration() || !i.ownerId().equals(a.ownerId()) || !i.incarnation().equals(a.incarnation())
                || !Objects.equals(i.cleanupEpoch(),a.cleanupEpoch()) || "CLEANED".equals(a.status())) throw denied();
    }
    private void validateReceipt(CleanupReceipt r) {
        Objects.requireNonNull(r);
        if (!r.deletion().containerAbsent() || !r.deletion().networkAbsent()) throw new IllegalArgumentException("All resources must be absent");
        if (r.evidence().reference()==null || !r.evidence().reference().matches("evidence://[A-Za-z0-9][A-Za-z0-9_./:-]{0,500}")
                || r.evidence().digest()==null || !r.evidence().digest().matches("sha256:[0-9a-f]{64}")) throw new IllegalArgumentException("Non-secret evidence reference and digest required");
    }
    private String labels(CleanupReceipt r) { return json.writeValueAsString(new TreeMap<>(r.resources().labels())); }
    private ReceiptResult existing(CleanupReceipt r) {
        var t = r.ticket();
        var rows = jdbc.queryForList("select * from assistant_worker_cleanup_receipts where receipt_id=? or (binding_id=? and lease_generation=? and cleanup_epoch=?)",
            r.receiptId(),t.bindingId(),t.leaseGeneration(),t.epoch());
        if (rows.isEmpty()) return null;
        if (rows.size()!=1) throw denied();
        var v = rows.getFirst();
        if (!r.receiptId().equals(v.get("receipt_id")) || !t.bindingId().equals(v.get("binding_id")) || !t.workerUnitId().equals(v.get("worker_unit_id"))
                || t.leaseGeneration()!=((Number)v.get("lease_generation")).longValue() || !t.epoch().equals(v.get("cleanup_epoch"))
                || !r.ownerId().equals(v.get("owner_id")) || !r.incarnation().equals(v.get("incarnation"))
                || !r.resources().containerId().equals(v.get("container_id")) || !r.resources().networkId().equals(v.get("network_id"))
                || !labels(r).equals(v.get("resource_labels")) || !r.evidence().reference().equals(v.get("evidence_ref")) || !r.evidence().digest().equals(v.get("evidence_digest"))) throw denied();
        return new ReceiptResult(r.receiptId(),ReceiptStatus.valueOf((String)v.get("status")));
    }
    /** Durable CLEANED for this exact receipt only; a changed owner/lease/resource/evidence row rethrows. */
    private ReceiptResult durableCompleted(CleanupReceipt r) {
        ReceiptResult durable;
        try { durable = existing(r); } catch (RuntimeException conflict) { return null; }
        return durable != null && durable.status() == ReceiptStatus.CLEANED ? durable : null;
    }
    private void insert(CleanupReceipt r,CleanupIdentity i) {
        jdbc.update("insert into assistant_worker_cleanup_receipts(receipt_id,generation_id,binding_id,worker_unit_id,lease_generation,cleanup_epoch,owner_id,incarnation,container_id,network_id,resource_labels,container_absent,network_absent,evidence_ref,evidence_digest,status,verifier_result,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'UNKNOWN','UNKNOWN',?,?)",
            r.receiptId(),i.generationId(),i.bindingId(),i.workerUnitId(),i.leaseGeneration(),i.cleanupEpoch(),i.ownerId(),i.incarnation(),r.resources().containerId(),r.resources().networkId(),labels(r),true,true,r.evidence().reference(),r.evidence().digest(),Instant.now(),Instant.now());
    }
    private void update(CleanupReceipt r,ReceiptStatus status,String verdict) {
        if (existing(r)==null) throw denied();
        if (jdbc.update("update assistant_worker_cleanup_receipts set status=?,verifier_result=?,updated_at=? where receipt_id=? and status<>'CLEANED'",status.name(),verdict,Instant.now(),r.receiptId())!=1) throw denied();
    }
    private static ExecutionBindingService.AuthorizationException denied() { return new ExecutionBindingService.AuthorizationException("Stale or conflicting cleanup identity/evidence"); }
    private static void id(String value,int length) {
        if (value==null || value.length()>length || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:/-]*")) throw new IllegalArgumentException("Invalid cleanup identifier");
    }
}
