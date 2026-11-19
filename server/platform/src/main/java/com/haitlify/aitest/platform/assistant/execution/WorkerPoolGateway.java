package com.haitlify.aitest.platform.assistant.execution;

/** Trusted host-owner boundary, deliberately not a launcher or isolation proof implementation. */
public interface WorkerPoolGateway {
    enum Outcome { SUCCESS, UNKNOWN }
    record Lease(ExecutionBindingService.Binding binding, String ownerId, String incarnation) {}
    /** Roles are positional and distinct: model proxy and tool proxy must both attest the expected identity. */
    record PairReadback(Lease lease, Outcome modelProxy, Outcome toolProxy) {}
    default PairReadback configure(Lease lease) { return new PairReadback(lease, Outcome.UNKNOWN, Outcome.UNKNOWN); }
    default PairReadback readback(Lease lease) { return new PairReadback(lease, Outcome.UNKNOWN, Outcome.UNKNOWN); }
    /** One-use in-memory handoff after commit. Implementations must never log or persist the raw token. */
    default Outcome acceptGrant(Lease lease, ExecutionBindingService.RawToken token) { return Outcome.UNKNOWN; }
    /** Positive proxy revoke readback is NOT physical workspace/container cleanup evidence. */
    record CleanupReadback(Lease lease, String epoch, Outcome modelProxyRevoked, Outcome toolProxyRevoked) {}
    default CleanupReadback revoke(Lease lease, String epoch) { return new CleanupReadback(lease, epoch, Outcome.UNKNOWN, Outcome.UNKNOWN); }
    static WorkerPoolGateway disabled() { return new WorkerPoolGateway() {}; }
}
