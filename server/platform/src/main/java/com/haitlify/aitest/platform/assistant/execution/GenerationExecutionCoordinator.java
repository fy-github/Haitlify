package com.haitlify.aitest.platform.assistant.execution;

import java.time.Duration;

/**
 * Explicitly constructed internal contract slice. No bean, scheduler, endpoint or production caller.
 * The gateway is trusted code: it must fence owner/incarnation/lease/expiry at every host operation;
 * DB preflight cannot prevent a revocation racing subsequent external I/O. Pair readback is a
 * typed adapter assertion, not independently verified Docker/network isolation evidence.
 * AUTHORIZED means grant handoff acknowledged, never model execution or generation success.
 * Crash-left CONFIGURE_SENT/GRANT_ISSUED/REVOKING rows must not be replayed: no recovery or takeover
 * path is provided. A future owner-aware reconciler must quarantine them, never infer cleanup.
 * Even positive proxy revocation remains QUARANTINED pending separately proven physical cleanup.
 */
public final class GenerationExecutionCoordinator {
    private final ExecutionBindingService execution;
    private final GenerationExecutionRepository repository;
    private final WorkerPoolGateway gateway;
    public GenerationExecutionCoordinator(ExecutionBindingService execution, GenerationExecutionRepository repository, WorkerPoolGateway gateway) {
        this.execution = execution; this.repository = repository; this.gateway = gateway;
    }
    public GenerationExecutionRepository.Attempt claim(String generation, String unit, String owner, Duration ttl) {
        rejectAmbient();
        if (owner == null || !owner.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")) throw new IllegalArgumentException("Invalid owner");
        var request = new ExecutionBindingService.AllocationRequest(generation, unit, ttl);
        var prior = repository.find(generation);
        if (prior != null) return matching(prior, unit, owner);
        try { return execution.allocate(request, b -> repository.insert(b, owner)); }
        catch (ExecutionBindingService.AllocationConflictException ex) {
            prior = repository.find(generation);
            if (prior != null) return matching(prior, unit, owner);
            throw ex;
        }
    }
    public GenerationExecutionRepository.Attempt configure(GenerationExecutionRepository.Attempt expected) {
        rejectAmbient();
        requireIdentity(expected);
        if (!repository.transition(expected, "CONFIGURING", "CONFIGURE_SENT")) return repository.find(expected.binding().generationId());
        var lease = new WorkerPoolGateway.Lease(expected.binding(), expected.ownerId(), expected.incarnation());
        try {
            execution.requireConfigurable(expected.binding());
            if (confirmed(lease, gateway.configure(lease)) && confirmed(lease, gateway.readback(lease))) {
                var grant = execution.issueGrant(new ExecutionBindingService.IssueRequest(expected.binding().bindingId(), repository.actor(expected)),
                    () -> repository.authorize(expected));
                if (gateway.acceptGrant(lease, grant.rawToken()) == WorkerPoolGateway.Outcome.SUCCESS
                    && repository.transition(expected, "GRANT_ISSUED", "AUTHORIZED")) return repository.find(expected.binding().generationId());
            }
        } catch (RuntimeException failure) {
            return failClosed(expected, failure("configure", failure));
        }
        return failClosed(expected, null);
    }

    /**
     * Persist the fence and revoke independently. Each operation owns a separate transaction;
     * failure of either must not skip the other. UNKNOWN means incomplete compensation and permits
     * an explicit revoke retry, never configure replay. No exception text/cause from a gateway is
     * retained: it may contain the raw token. Phase and exception type preserve the failure signal.
     */
    private GenerationExecutionRepository.Attempt failClosed(GenerationExecutionRepository.Attempt expected, OperationFailure primary) {
        boolean unresolved = false;
        try { repository.quarantine(expected); }
        catch (RuntimeException failure) {
            primary = aggregate(primary, "quarantine", failure);
            unresolved = true;
        }
        try { execution.revoke(expected.binding().bindingId()); }
        catch (RuntimeException failure) {
            primary = aggregate(primary, "revoke", failure);
            unresolved = true;
        }
        if (unresolved) primary = markUnknown(expected, primary);
        if (primary != null) throw primary;
        return repository.find(expected.binding().generationId());
    }
    /** Explicit caller only: requestCleanup enforces expiry against the execution service clock. */
    public GenerationExecutionRepository.Attempt expire(GenerationExecutionRepository.Attempt expected) {
        rejectAmbient(); requireIdentity(expected);
        // This check/revocation commits before any external gateway operation.
        try {
            execution.requestCleanup(expected.binding().bindingId(), expected.binding().leaseGeneration());
        } catch (ExecutionBindingService.CleanupNotDueException notDue) {
            // The service clock and row locks made this decision. Preserve the live lease and
            // do not let diagnostic wording become an authorization-state discriminator.
            throw notDue;
        } catch (ExecutionBindingService.AuthorizationException failure) {
            throw markUnknown(expected, failure("expire", failure));
        } catch (RuntimeException failure) {
            // Expiry was not confirmed. Fence/report uncertainty, but do not cancel a live lease.
            throw markUnknown(expected, failure("expire", failure));
        }
        return revoke(expected);
    }
    public GenerationExecutionRepository.Attempt revoke(GenerationExecutionRepository.Attempt expected) {
        rejectAmbient(); requireIdentity(expected);
        if (!repository.beginCleanup(expected)) return repository.find(expected.binding().generationId());
        try {
            execution.revoke(expected.binding().bindingId());
            var ticket = execution.requestCleanup(expected.binding().bindingId(), expected.binding().leaseGeneration());
            repository.cleanupEpoch(expected, ticket.epoch());
            var lease = new WorkerPoolGateway.Lease(expected.binding(), expected.ownerId(), expected.incarnation());
            var readback = gateway.revoke(lease, ticket.epoch());
            if (readback == null || !lease.equals(readback.lease()) || !ticket.epoch().equals(readback.epoch())
                    || readback.modelProxyRevoked() == null || readback.toolProxyRevoked() == null)
                throw new ExecutionBindingService.AuthorizationException("Invalid revoke readback");
            // A valid UNKNOWN readback is not edge revocation success. Even SUCCESS for both
            // proxies is NOT a physical clean acknowledgement; either remains quarantined.
        } catch (RuntimeException failure) {
            throw markUnknown(expected, failure("revoke", failure));
        }
        try { repository.quarantine(expected); }
        catch (RuntimeException failure) { throw markUnknown(expected, failure("quarantine", failure)); }
        return repository.find(expected.binding().generationId());
    }

    private OperationFailure markUnknown(GenerationExecutionRepository.Attempt expected, OperationFailure primary) {
        try { repository.unknown(expected); }
        catch (RuntimeException failure) { primary = aggregate(primary, "unknown", failure); }
        return primary;
    }
    private static OperationFailure aggregate(OperationFailure primary, String phase, RuntimeException cause) {
        OperationFailure next = failure(phase, cause);
        if (primary == null) return next;
        primary.addSuppressed(next);
        return primary;
    }
    private static OperationFailure failure(String phase, RuntimeException cause) {
        return new OperationFailure(phase, cause.getClass().getName());
    }
    /** Sanitized operational failure; UNKNOWN persistence itself can fail, so readback is required. */
    public static final class OperationFailure extends RuntimeException {
        private OperationFailure(String phase, String failureType) {
            super("Execution " + phase + " failed [" + failureType + "]; inspect durable state before retry");
        }
    }
    private void requireIdentity(GenerationExecutionRepository.Attempt a) {
        var actual = repository.find(a.binding().generationId());
        if (actual == null || !actual.binding().equals(a.binding()) || !actual.ownerId().equals(a.ownerId()) || !actual.incarnation().equals(a.incarnation()))
            throw new ExecutionBindingService.AuthorizationException("Stale execution identity");
    }
    private boolean confirmed(WorkerPoolGateway.Lease lease, WorkerPoolGateway.PairReadback result) {
        return result != null && lease.equals(result.lease()) && result.modelProxy() == WorkerPoolGateway.Outcome.SUCCESS
            && result.toolProxy() == WorkerPoolGateway.Outcome.SUCCESS;
    }
    private GenerationExecutionRepository.Attempt matching(GenerationExecutionRepository.Attempt a, String unit, String owner) {
        if (!a.binding().workerUnitId().equals(unit) || !a.ownerId().equals(owner)) throw new ExecutionBindingService.AuthorizationException("Claim identity mismatch");
        return a;
    }
    static void rejectAmbient() {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new ExecutionBindingService.AuthorizationException("Ambient transaction forbidden");
    }
}
