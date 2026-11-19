package com.haitlify.aitest.platform.assistant.execution;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest(properties = {
    "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
    "spring.datasource.url=jdbc:h2:mem:generation-coordinator;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@ActiveProfiles("test")
class GenerationExecutionCoordinatorTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ExecutionBindingService execution;
    @Autowired PlatformTransactionManager manager;
    GenerationExecutionCoordinator coordinator;
    long actor, tenant, project;
    String generation, unit;

    @BeforeEach void seed() {
        Instant now = Instant.now();
        String suffix = UUID.randomUUID().toString();
        jdbc.update("insert into platform_users(username,password_hash,role,enabled,created_at,updated_at) values (?,'unused','EDITOR',true,?,?)", suffix, now, now);
        actor = jdbc.queryForObject("select id from platform_users where username=?", Long.class, suffix);
        jdbc.update("insert into tenants(code,name,active,created_at,updated_at) values (?,'Coordinator',true,?,?)", suffix, now, now);
        tenant = jdbc.queryForObject("select id from tenants where code=?", Long.class, suffix);
        jdbc.update("insert into projects(code,name,description,active,tenant_id,created_at,updated_at) values (?,'Coordinator','',true,?,?,?)", suffix, tenant, now, now);
        project = jdbc.queryForObject("select id from projects where code=?", Long.class, suffix);
        jdbc.update("insert into tenant_members(tenant_id,username,created_at) values (?,?,?)", tenant, suffix, now);
        jdbc.update("insert into project_members(project_id,username,created_at) values (?,?,?)", project, suffix, now);
        generation = UUID.randomUUID().toString();
        jdbc.update("insert into assistant_generations(id,actor_id,project_id,tenant_id,request_id,payload_digest,snapshot_ciphertext,state,created_at) values (?,?,?,?,?,'digest','enc:v1:snapshot','WAITING_WORKER',?)", generation, actor, project, tenant, suffix, now);
        unit = "unit-" + suffix;
        // Test fixture acknowledges an initial clean unit; not production cleanup evidence.
        execution.confirmUnitCleaned(execution.registerUnit(new ExecutionBindingService.WorkerUnit(unit, "sha256:" + "a".repeat(64), "policy-v1")));
        coordinator = new GenerationExecutionCoordinator(execution, new GenerationExecutionRepository(jdbc, manager), WorkerPoolGateway.disabled());
    }

    @Autowired com.haitlify.aitest.platform.assistant.security.capability.CapabilityTokenCodec codec;

    @Test void quarantinePersistenceFailureStillRevokesHandedOffGrant() {
        var gateway = new RecordingGateway() {
            @Override public Outcome acceptGrant(Lease lease, ExecutionBindingService.RawToken raw) {
                super.acceptGrant(lease, raw);
                throw new IllegalStateException(token);
            }
        };
        var repository = new GenerationExecutionRepository(jdbc, manager);
        coordinator = new GenerationExecutionCoordinator(execution, repository, gateway);
        var claim = coordinator.claim(generation, unit, "host-owner-1", Duration.ofMinutes(5));
        String constraint = "fail_" + generation.replace("-", "");
        jdbc.execute("alter table assistant_generation_executions add constraint " + constraint
            + " check (generation_id <> '" + generation + "' or status <> 'QUARANTINED')");
        try {
            Throwable failure = catchThrowable(() -> coordinator.configure(claim));
            assertThat(failure).isNotNull();
            assertThatThrownBy(() -> execution.inspectAuthorizedGrant(gateway.token, "draft_get"))
                .isInstanceOf(ExecutionBindingService.AuthorizationException.class);
            assertThat(jdbc.queryForObject("select revoked from assistant_execution_grants where binding_id=?", Boolean.class, claim.binding().bindingId())).isTrue();
            assertThat(repository.find(generation).status()).isEqualTo("UNKNOWN");
            assertThat(jdbc.queryForObject("select state from assistant_generations where id=?", String.class, generation)).isEqualTo("UNKNOWN");
            assertThat(failure).hasMessageContaining("configure");
            assertThat(failure.getSuppressed()).isNotEmpty();
            assertThat(stackTrace(failure)).doesNotContain(gateway.token);
        } finally { jdbc.execute("alter table assistant_generation_executions drop constraint " + constraint); }
    }

    @Test void prematureExpireLeavesAuthorizedLeaseUnchangedUsingServiceClock() {
        // Deliberately in the past relative to wall time; only the service clock decides expiry.
        execution = new ExecutionBindingService(jdbc, codec, manager, Clock.fixed(Instant.parse("2000-01-01T00:00:00Z"), ZoneOffset.UTC));
        var gateway = new RecordingGateway();
        var repository = new GenerationExecutionRepository(jdbc, manager);
        coordinator = new GenerationExecutionCoordinator(execution, repository, gateway);
        var claim = coordinator.claim(generation, unit, "host-owner-1", Duration.ofMinutes(5));
        var authorized = coordinator.configure(claim);
        assertThatThrownBy(() -> coordinator.expire(claim)).isInstanceOf(ExecutionBindingService.CleanupNotDueException.class);
        assertThat(repository.find(generation)).isEqualTo(authorized);
        assertThat(jdbc.queryForObject("select state from assistant_generations where id=?", String.class, generation)).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("select state from assistant_execution_bindings where id=?", String.class, claim.binding().bindingId())).isEqualTo("ACTIVE");
        assertThat(execution.inspectAuthorizedGrant(gateway.token, "draft_get").scope().generationId()).isEqualTo(generation);
        assertThat(gateway.calls).containsExactly("configure", "readback", "grant");
    }

    @Test void quarantinedRetryRevokesAfterQuarantineSucceededButUnknownPersistenceFailed() {
        var gateway = new RecordingGateway() {
            @Override public Outcome acceptGrant(Lease lease, ExecutionBindingService.RawToken raw) {
                super.acceptGrant(lease, raw);
                throw new IllegalStateException(token);
            }
        };
        var repository = new GenerationExecutionRepository(jdbc, manager);
        coordinator = new GenerationExecutionCoordinator(execution, repository, gateway);
        var claim = coordinator.claim(generation, unit, "host-owner-1", Duration.ofMinutes(5));
        String revokeConstraint = "fail_revoke_" + generation.replace("-", "");
        String unknownConstraint = "fail_unknown_" + generation.replace("-", "");
        jdbc.execute("alter table assistant_execution_bindings add constraint " + revokeConstraint
            + " check (id <> '" + claim.binding().bindingId() + "' or state <> 'REVOKED')");
        jdbc.execute("alter table assistant_generation_executions add constraint " + unknownConstraint
            + " check (generation_id <> '" + generation + "' or status <> 'UNKNOWN')");
        try {
            Throwable failure = catchThrowable(() -> coordinator.configure(claim));
            assertThat(failure).isNotNull();
            assertThat(repository.find(generation).status()).isEqualTo("QUARANTINED");
            assertThat(jdbc.queryForObject("select revoked from assistant_execution_grants where binding_id=?", Boolean.class, claim.binding().bindingId())).isFalse();

            var forgedBinding = new ExecutionBindingService.Binding(claim.binding().bindingId(), generation, "other-unit",
                claim.binding().leaseGeneration(), claim.binding().imageDigest(), claim.binding().policyVersion(), claim.binding().expiresAt());
            var forged = new GenerationExecutionRepository.Attempt(forgedBinding, claim.ownerId(), claim.incarnation(), "QUARANTINED", null);
            assertThat(repository.beginCleanup(forged)).isFalse();
            assertThat(repository.find(generation).status()).isEqualTo("QUARANTINED");
        } finally {
            jdbc.execute("alter table assistant_execution_bindings drop constraint " + revokeConstraint);
            jdbc.execute("alter table assistant_generation_executions drop constraint " + unknownConstraint);
        }

        assertThat(coordinator.revoke(claim).status()).isEqualTo("QUARANTINED");
        assertThat(jdbc.queryForObject("select revoked from assistant_execution_grants where binding_id=?", Boolean.class, claim.binding().bindingId())).isTrue();
        assertThat(jdbc.queryForObject("select state from assistant_execution_bindings where id=?", String.class, claim.binding().bindingId())).isEqualTo("REVOKED");

        var wrongOwner = new GenerationExecutionRepository.Attempt(claim.binding(), "other-owner", claim.incarnation(), "QUARANTINED", null);
        assertThatThrownBy(() -> coordinator.revoke(wrongOwner)).isInstanceOf(ExecutionBindingService.AuthorizationException.class);
    }

    @Test void cleanupAtServiceClockExpiryBoundaryUsesCleanupPath() {
        Instant serviceNow = Instant.parse("2000-01-01T00:00:00Z");
        execution = new ExecutionBindingService(jdbc, codec, manager, Clock.fixed(serviceNow, ZoneOffset.UTC));
        var gateway = new RecordingGateway();
        var repository = new GenerationExecutionRepository(jdbc, manager);
        coordinator = new GenerationExecutionCoordinator(execution, repository, gateway);
        var claim = coordinator.claim(generation, unit, "host-owner-1", Duration.ofSeconds(1));
        coordinator.configure(claim);
        jdbc.update("update assistant_execution_bindings set expires_at=? where id=?", serviceNow, claim.binding().bindingId());

        assertThat(coordinator.expire(claim).status()).isEqualTo("QUARANTINED");
        assertThat(jdbc.queryForObject("select state from assistant_execution_bindings where id=?", String.class, claim.binding().bindingId())).isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject("select revoked from assistant_execution_grants where binding_id=?", Boolean.class, claim.binding().bindingId())).isTrue();
    }

    @Test void coordinatorStatusVocabularyIsDatabaseEnforced() {
        coordinator.claim(generation, unit, "host-owner-1", Duration.ofMinutes(5));
        for (String status : List.of("CONFIGURING", "CONFIGURE_SENT", "GRANT_ISSUED", "AUTHORIZED", "REVOKING", "QUARANTINED", "CLEANED", "UNKNOWN")) {
            assertThat(jdbc.update("update assistant_generation_executions set status=? where generation_id=?", status, generation)).isEqualTo(1);
        }
        assertThatThrownBy(() -> jdbc.update("update assistant_generation_executions set status='INVALID' where generation_id=?", generation))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select status from assistant_generation_executions where generation_id=?", String.class, generation)).isEqualTo("UNKNOWN");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void failedRevocationIsReportedUnknownAndCanBeRetried(boolean alsoFailQuarantine) {
        var gateway = new RecordingGateway() {
            @Override public Outcome acceptGrant(Lease lease, ExecutionBindingService.RawToken raw) {
                super.acceptGrant(lease, raw);
                throw new IllegalStateException(token);
            }
        };
        var repository = new GenerationExecutionRepository(jdbc, manager);
        coordinator = new GenerationExecutionCoordinator(execution, repository, gateway);
        var claim = coordinator.claim(generation, unit, "host-owner-1", Duration.ofMinutes(5));
        String constraint = "fail_" + generation.replace("-", "");
        jdbc.execute("alter table assistant_execution_bindings add constraint " + constraint
            + " check (id <> '" + claim.binding().bindingId() + "' or state <> 'REVOKED')");
        if (alsoFailQuarantine) jdbc.execute("alter table assistant_generation_executions add constraint q_" + constraint
            + " check (generation_id <> '" + generation + "' or status <> 'QUARANTINED')");
        try {
            Throwable failure = catchThrowable(() -> coordinator.configure(claim));
            assertThat(failure).isNotNull();
            assertThat(repository.find(generation).status()).isEqualTo("UNKNOWN");
            assertThat(jdbc.queryForObject("select state from assistant_generations where id=?", String.class, generation)).isEqualTo("UNKNOWN");
            assertThat(jdbc.queryForObject("select revoked from assistant_execution_grants where binding_id=?", Boolean.class, claim.binding().bindingId())).isFalse();
            assertThat(jdbc.queryForObject("select state from assistant_execution_bindings where id=?", String.class, claim.binding().bindingId())).isEqualTo("ACTIVE");
            assertThatThrownBy(() -> execution.inspectAuthorizedGrant(gateway.token, "draft_get")).isInstanceOf(ExecutionBindingService.AuthorizationException.class);
            assertThat(Arrays.stream(failure.getSuppressed()).map(Throwable::getMessage)).anyMatch(m -> m.contains("revoke"));
            assertThat(stackTrace(failure)).doesNotContain(gateway.token);
        } finally {
            jdbc.execute("alter table assistant_execution_bindings drop constraint " + constraint);
            if (alsoFailQuarantine) jdbc.execute("alter table assistant_generation_executions drop constraint q_" + constraint);
        }
        assertThat(coordinator.revoke(claim).status()).isEqualTo("QUARANTINED");
        assertThat(jdbc.queryForObject("select revoked from assistant_execution_grants where binding_id=?", Boolean.class, claim.binding().bindingId())).isTrue();
        assertThat(jdbc.queryForObject("select state from assistant_execution_bindings where id=?", String.class, claim.binding().bindingId())).isEqualTo("REVOKED");
    }

    @Test void explicitRevokeFailureIsNotSilentlyReportedAsQuarantined() {
        var gateway = new RecordingGateway();
        var repository = new GenerationExecutionRepository(jdbc, manager);
        coordinator = new GenerationExecutionCoordinator(execution, repository, gateway);
        var claim = coordinator.claim(generation, unit, "host-owner-1", Duration.ofMinutes(5));
        coordinator.configure(claim);
        String constraint = "fail_" + generation.replace("-", "");
        jdbc.execute("alter table assistant_execution_bindings add constraint " + constraint
            + " check (id <> '" + claim.binding().bindingId() + "' or state <> 'REVOKED')");
        try {
            assertThatThrownBy(() -> coordinator.revoke(claim)).hasMessageContaining("revoke");
            assertThat(repository.find(generation).status()).isEqualTo("UNKNOWN");
            assertThatThrownBy(() -> execution.inspectAuthorizedGrant(gateway.token, "draft_get")).isInstanceOf(ExecutionBindingService.AuthorizationException.class);
            assertThat(gateway.calls).containsExactly("configure", "readback", "grant");
        } finally { jdbc.execute("alter table assistant_execution_bindings drop constraint " + constraint); }
        assertThat(coordinator.revoke(claim).status()).isEqualTo("QUARANTINED");
        assertThat(jdbc.queryForObject("select revoked from assistant_execution_grants where binding_id=?", Boolean.class, claim.binding().bindingId())).isTrue();
    }

    @Test void expiryDatabaseFailureDoesNotInvokeCancellationOrGateway() {
        var gateway = new RecordingGateway();
        var repository = new GenerationExecutionRepository(jdbc, manager);
        coordinator = new GenerationExecutionCoordinator(execution, repository, gateway);
        var claim = coordinator.claim(generation, unit, "host-owner-1", Duration.ofMinutes(5));
        coordinator.configure(claim);
        jdbc.update("update assistant_execution_bindings set expires_at=? where id=?", Instant.parse("2000-01-01T00:00:00Z"), claim.binding().bindingId());
        String constraint = "fail_" + generation.replace("-", "");
        // requestCleanup revokes first, then this fault forces its whole transaction to roll back.
        jdbc.execute("alter table assistant_execution_worker_units add constraint " + constraint
            + " check (unit_id <> '" + unit + "' or cleanup_epoch is null)");
        try {
            assertThatThrownBy(() -> coordinator.expire(claim)).isInstanceOf(GenerationExecutionCoordinator.OperationFailure.class).hasMessageContaining("expire");
            assertThat(repository.find(generation).status()).isEqualTo("UNKNOWN");
            assertThat(jdbc.queryForObject("select state from assistant_execution_bindings where id=?", String.class, claim.binding().bindingId())).isEqualTo("ACTIVE");
            assertThat(jdbc.queryForObject("select revoked from assistant_execution_grants where binding_id=?", Boolean.class, claim.binding().bindingId())).isFalse();
            assertThat(gateway.calls).containsExactly("configure", "readback", "grant");
        } finally { jdbc.execute("alter table assistant_execution_worker_units drop constraint " + constraint); }
        assertThat(coordinator.expire(claim).status()).isEqualTo("QUARANTINED");
    }

    @Test void failedUnknownPersistenceIsAggregatedWithoutClaimingRevocation() {
        var gateway = new RecordingGateway() {
            @Override public Outcome acceptGrant(Lease lease, ExecutionBindingService.RawToken raw) {
                super.acceptGrant(lease, raw);
                throw new IllegalStateException(token);
            }
        };
        var repository = new GenerationExecutionRepository(jdbc, manager);
        coordinator = new GenerationExecutionCoordinator(execution, repository, gateway);
        var claim = coordinator.claim(generation, unit, "host-owner-1", Duration.ofMinutes(5));
        String constraint = "fail_" + generation.replace("-", "");
        jdbc.execute("alter table assistant_execution_bindings add constraint " + constraint
            + " check (id <> '" + claim.binding().bindingId() + "' or state <> 'REVOKED')");
        jdbc.execute("alter table assistant_generation_executions add constraint q_" + constraint
            + " check (generation_id <> '" + generation + "' or status not in ('QUARANTINED','UNKNOWN'))");
        try {
            Throwable failure = catchThrowable(() -> coordinator.configure(claim));
            assertThat(failure).isInstanceOf(GenerationExecutionCoordinator.OperationFailure.class).hasMessageContaining("configure");
            assertThat(Arrays.stream(failure.getSuppressed()).map(Throwable::getMessage).toList())
                .hasSize(3).anyMatch(m -> m.contains("quarantine")).anyMatch(m -> m.contains("revoke")).anyMatch(m -> m.contains("unknown"));
            assertThat(stackTrace(failure)).doesNotContain(gateway.token);
            // A database that rejects every compensation cannot be claimed safe or revoked.
            assertThat(repository.find(generation).status()).isEqualTo("GRANT_ISSUED");
            assertThat(jdbc.queryForObject("select revoked from assistant_execution_grants where binding_id=?", Boolean.class, claim.binding().bindingId())).isFalse();
        } finally {
            jdbc.execute("alter table assistant_execution_bindings drop constraint " + constraint);
            jdbc.execute("alter table assistant_generation_executions drop constraint q_" + constraint);
        }
        assertThat(coordinator.revoke(claim).status()).isEqualTo("QUARANTINED");
        assertThatThrownBy(() -> execution.inspectAuthorizedGrant(gateway.token, "draft_get")).isInstanceOf(ExecutionBindingService.AuthorizationException.class);
    }

    private String stackTrace(Throwable failure) {
        var text = new java.io.StringWriter();
        failure.printStackTrace(new java.io.PrintWriter(text));
        return text.toString();
    }

    @Test void revokedLeaseBeforeConfigureNeverCallsGateway() {
        var gateway = new RecordingGateway();
        coordinator = new GenerationExecutionCoordinator(execution, new GenerationExecutionRepository(jdbc, manager), gateway);
        var claim = coordinator.claim(generation, unit, "host-owner-1", Duration.ofSeconds(30));
        execution.revoke(claim.binding().bindingId());
        assertThatThrownBy(() -> coordinator.configure(claim)).isInstanceOf(GenerationExecutionCoordinator.OperationFailure.class);
        assertThat(new GenerationExecutionRepository(jdbc, manager).find(generation).status()).isEqualTo("QUARANTINED");
        assertThat(gateway.calls).isEmpty();
    }

    @Test void allCoordinatorEntriesRejectAmbientTransactionsWithoutSideEffects() {
        var claim = coordinator.claim(generation, unit, "host-owner-1", Duration.ofSeconds(30));
        new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(tx -> {
            assertThatThrownBy(() -> coordinator.claim(generation, unit, "host-owner-1", Duration.ofSeconds(30))).isInstanceOf(ExecutionBindingService.AuthorizationException.class);
            assertThatThrownBy(() -> coordinator.configure(claim)).isInstanceOf(ExecutionBindingService.AuthorizationException.class);
            assertThatThrownBy(() -> coordinator.revoke(claim)).isInstanceOf(ExecutionBindingService.AuthorizationException.class);
            assertThatThrownBy(() -> coordinator.expire(claim)).isInstanceOf(ExecutionBindingService.AuthorizationException.class);
        });
        assertThat(jdbc.queryForObject("select status from assistant_generation_executions where generation_id=?", String.class, generation)).isEqualTo("CONFIGURING");
    }

    @Test void failedClaimPersistenceRollsBackBindingAndGeneration() {
        String constraint = "fail_" + generation.replace("-", "");
        jdbc.execute("alter table assistant_generation_executions add constraint " + constraint + " check (generation_id <> '" + generation + "')");
        try {
            assertThatThrownBy(() -> coordinator.claim(generation, unit, "host-owner-1", Duration.ofSeconds(30))).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("select count(*) from assistant_execution_bindings where generation_id=?", Integer.class, generation)).isZero();
            assertThat(jdbc.queryForObject("select state from assistant_generations where id=?", String.class, generation)).isEqualTo("WAITING_WORKER");
            assertThat(jdbc.queryForObject("select cleanup_confirmed from assistant_execution_worker_units where unit_id=?", Boolean.class, unit)).isTrue();
        } finally { jdbc.execute("alter table assistant_generation_executions drop constraint " + constraint); }
    }

    @Test void lostGrantHandoffQuarantinesAndRevokesWithoutLeakingException() {
        var gateway = new RecordingGateway() {
            @Override public Outcome acceptGrant(Lease lease, ExecutionBindingService.RawToken raw) {
                super.acceptGrant(lease, raw);
                throw new IllegalStateException(token);
            }
        };
        coordinator = new GenerationExecutionCoordinator(execution, new GenerationExecutionRepository(jdbc, manager), gateway);
        var claim = coordinator.claim(generation, unit, "host-owner-1", Duration.ofSeconds(30));
        Throwable failure = catchThrowable(() -> coordinator.configure(claim));
        assertThat(failure).isInstanceOf(GenerationExecutionCoordinator.OperationFailure.class);
        assertThat(stackTrace(failure)).doesNotContain(gateway.token);
        var result = new GenerationExecutionRepository(jdbc, manager).find(generation);
        assertThat(result.status()).isEqualTo("QUARANTINED");
        assertThat(result.toString()).doesNotContain(gateway.token);
        assertThat(jdbc.queryForMap("select * from assistant_generation_executions where generation_id=?", generation).toString()).doesNotContain(gateway.token);
        assertThat(jdbc.queryForObject("select revoked from assistant_execution_grants where binding_id=?", Boolean.class, claim.binding().bindingId())).isTrue();
        assertThatThrownBy(() -> execution.inspectAuthorizedGrant(gateway.token, "draft_get")).isInstanceOf(ExecutionBindingService.AuthorizationException.class);
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "invalid revoke readback: {0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"null-readback", "null-lease", "null-binding",
        "generation", "binding", "worker", "lease-generation", "owner", "incarnation", "image", "policy",
        "expiry", "epoch", "null-epoch", "null-model", "null-tool"})
    void invalidRevokeReadbackIsUnknownAndRetryKeepsExactIdentityAndEpoch(String fault) {
        var leases = new ArrayList<WorkerPoolGateway.Lease>();
        var epochs = new ArrayList<String>();
        var gateway = new RecordingGateway() {
            @Override public CleanupReadback revoke(Lease lease, String epoch) {
                outside(); calls.add("revoke"); leases.add(lease); epochs.add(epoch);
                return leases.size() == 1 ? invalidCleanupReadback(lease, epoch, fault)
                    : new CleanupReadback(lease, epoch, Outcome.SUCCESS, Outcome.SUCCESS);
            }
        };
        var repository = new GenerationExecutionRepository(jdbc, manager);
        coordinator = new GenerationExecutionCoordinator(execution, repository, gateway);
        var claim = coordinator.claim(generation, unit, "host-owner-1", Duration.ofMinutes(5));
        assertThat(coordinator.configure(claim).status()).isEqualTo("AUTHORIZED");

        Throwable failure = catchThrowable(() -> coordinator.revoke(claim));
        assertThat(failure).as("invalid cleanup readback must fail closed: %s", fault)
            .isInstanceOf(GenerationExecutionCoordinator.OperationFailure.class).hasMessageContaining("revoke");
        assertThat(failure.getCause()).isNull();
        assertThat(stackTrace(failure)).doesNotContain(gateway.token, "gateway-sensitive-diagnostic");
        var uncertain = repository.find(generation);
        assertThat(uncertain.status()).isEqualTo("UNKNOWN");
        assertThat(uncertain.cleanupEpoch()).isNotBlank();
        assertThat(jdbc.queryForObject("select state from assistant_generations where id=?", String.class, generation)).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("select state from assistant_execution_bindings where id=?", String.class, claim.binding().bindingId())).isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject("select revoked from assistant_execution_grants where binding_id=?", Boolean.class, claim.binding().bindingId())).isTrue();
        assertThatThrownBy(() -> execution.inspectAuthorizedGrant(gateway.token, "draft_get"))
            .isInstanceOf(ExecutionBindingService.AuthorizationException.class);
        assertThat(jdbc.queryForMap("select * from assistant_execution_worker_units where unit_id=?", unit))
            .containsEntry("cleanup_confirmed", false).containsEntry("current_binding_id", claim.binding().bindingId())
            .containsEntry("current_lease_generation", claim.binding().leaseGeneration()).containsEntry("cleanup_epoch", uncertain.cleanupEpoch());
        assertThat(jdbc.queryForObject("select count(*) from assistant_worker_cleanup_receipts where binding_id=?", Integer.class, claim.binding().bindingId())).isZero();

        var retried = coordinator.revoke(claim);
        assertThat(retried.status()).isEqualTo("QUARANTINED");
        assertThat(retried.cleanupEpoch()).isEqualTo(uncertain.cleanupEpoch());
        var expectedLease = new WorkerPoolGateway.Lease(claim.binding(), claim.ownerId(), claim.incarnation());
        assertThat(leases).containsExactly(expectedLease, expectedLease);
        assertThat(epochs).containsExactly(uncertain.cleanupEpoch(), uncertain.cleanupEpoch());
        assertThat(gateway.calls).containsExactly("configure", "readback", "grant", "revoke", "revoke");
        assertThat(jdbc.queryForObject("select cleanup_confirmed from assistant_execution_worker_units where unit_id=?", Boolean.class, unit)).isFalse();
    }

    private WorkerPoolGateway.CleanupReadback invalidCleanupReadback(WorkerPoolGateway.Lease lease, String epoch, String fault) {
        if (fault.equals("null-readback")) return null;
        var b = lease.binding();
        String diagnostic = "gateway-sensitive-diagnostic";
        var binding = new ExecutionBindingService.Binding(
            fault.equals("binding") ? diagnostic : b.bindingId(),
            fault.equals("generation") ? diagnostic : b.generationId(),
            fault.equals("worker") ? diagnostic : b.workerUnitId(),
            fault.equals("lease-generation") ? b.leaseGeneration() + 1 : b.leaseGeneration(),
            fault.equals("image") ? diagnostic : b.imageDigest(),
            fault.equals("policy") ? diagnostic : b.policyVersion(),
            fault.equals("expiry") ? b.expiresAt().plusSeconds(1) : b.expiresAt());
        var returnedLease = new WorkerPoolGateway.Lease(fault.equals("null-binding") ? null : binding,
            fault.equals("owner") ? diagnostic : lease.ownerId(), fault.equals("incarnation") ? diagnostic : lease.incarnation());
        return new WorkerPoolGateway.CleanupReadback(fault.equals("null-lease") ? null : returnedLease,
            fault.equals("null-epoch") ? null : fault.equals("epoch") ? diagnostic : epoch,
            fault.equals("null-model") ? null : WorkerPoolGateway.Outcome.SUCCESS,
            fault.equals("null-tool") ? null : WorkerPoolGateway.Outcome.SUCCESS);
    }

    @Test void revokeGatewayExceptionIsSanitizedAndRetryCannotTouchNewLease() {
        var gateway = new RecordingGateway() {
            boolean fail = true;
            @Override public CleanupReadback revoke(Lease lease, String epoch) {
                outside(); calls.add("revoke");
                if (fail) { fail = false; throw new IllegalStateException("gateway-sensitive-diagnostic:" + token); }
                return new CleanupReadback(lease, epoch, Outcome.SUCCESS, Outcome.SUCCESS);
            }
        };
        var repository = new GenerationExecutionRepository(jdbc, manager);
        coordinator = new GenerationExecutionCoordinator(execution, repository, gateway);
        var old = coordinator.claim(generation, unit, "host-owner-1", Duration.ofMinutes(5));
        coordinator.configure(old);
        Throwable failure = catchThrowable(() -> coordinator.revoke(old));
        assertThat(failure).isInstanceOf(GenerationExecutionCoordinator.OperationFailure.class).hasMessageContaining("revoke");
        assertThat(stackTrace(failure)).doesNotContain(gateway.token, "gateway-sensitive-diagnostic");
        assertThat(failure.getCause()).isNull();
        assertThat(repository.find(generation).status()).isEqualTo("UNKNOWN");
        var epoch = repository.find(generation).cleanupEpoch();
        assertThat(coordinator.revoke(old).cleanupEpoch()).isEqualTo(epoch);
        // Test-only trusted cleanup acknowledgement; not physical cleanup evidence. Leave the old
        // coordinator row quarantined to exercise a delayed retry after a genuinely newer lease.
        execution.confirmUnitCleaned(new ExecutionBindingService.CleanupTicket(unit, old.binding().bindingId(), old.binding().leaseGeneration(), epoch));
        String nextGeneration = UUID.randomUUID().toString();
        assertThat(jdbc.update("insert into assistant_generations(id,actor_id,project_id,tenant_id,request_id,payload_digest,snapshot_ciphertext,state,created_at) values (?,?,?,?,?,'digest','enc:v1:snapshot','WAITING_WORKER',?)",
            nextGeneration, actor, project, tenant, nextGeneration, Instant.now())).isEqualTo(1);
        var next = coordinator.claim(nextGeneration, unit, "host-owner-2", Duration.ofMinutes(5));
        assertThat(next.binding().leaseGeneration()).isEqualTo(old.binding().leaseGeneration() + 1);
        var authorized = coordinator.configure(next);
        var unitBefore = jdbc.queryForMap("select * from assistant_execution_worker_units where unit_id=?", unit);
        var bindingBefore = jdbc.queryForMap("select * from assistant_execution_bindings where id=?", next.binding().bindingId());
        var grantBefore = jdbc.queryForMap("select * from assistant_execution_grants where binding_id=?", next.binding().bindingId());
        var callsBefore = List.copyOf(gateway.calls);
        assertThatThrownBy(() -> coordinator.revoke(old)).isInstanceOf(GenerationExecutionCoordinator.OperationFailure.class);
        assertThat(gateway.calls).isEqualTo(callsBefore);
        assertThat(repository.find(nextGeneration)).isEqualTo(authorized);
        assertThat(jdbc.queryForMap("select * from assistant_execution_worker_units where unit_id=?", unit)).isEqualTo(unitBefore);
        assertThat(jdbc.queryForMap("select * from assistant_execution_bindings where id=?", next.binding().bindingId())).isEqualTo(bindingBefore);
        assertThat(jdbc.queryForMap("select * from assistant_execution_grants where binding_id=?", next.binding().bindingId())).isEqualTo(grantBefore);
        assertThat(execution.inspectAuthorizedGrant(gateway.token, "draft_get").scope().generationId()).isEqualTo(nextGeneration);
    }

    @Test void positiveProxyRevocationNeverPretendsPhysicalCleanup() {
        var gateway = new RecordingGateway() {
            @Override public CleanupReadback revoke(Lease lease, String epoch) {
                outside(); return new CleanupReadback(lease, epoch, Outcome.SUCCESS, Outcome.SUCCESS);
            }
        };
        coordinator = new GenerationExecutionCoordinator(execution, new GenerationExecutionRepository(jdbc, manager), gateway);
        var claim = coordinator.claim(generation, unit, "host-owner-1", Duration.ofSeconds(30));
        coordinator.configure(claim);
        assertThat(coordinator.revoke(claim).status()).isEqualTo("QUARANTINED");
        assertThat(jdbc.queryForObject("select cleanup_confirmed from assistant_execution_worker_units where unit_id=?", Boolean.class, unit)).isFalse();
        assertThat(jdbc.queryForObject("select current_binding_id from assistant_execution_worker_units where unit_id=?", String.class, unit)).isEqualTo(claim.binding().bindingId());
    }

    @Test void staleLeaseOnExpiryStillPersistsQuarantine() {
        var claim = coordinator.claim(generation, unit, "host-owner-1", Duration.ofSeconds(30));
        jdbc.update("update assistant_execution_worker_units set current_lease_generation=current_lease_generation+1 where unit_id=?", unit);
        assertThatThrownBy(() -> coordinator.expire(claim)).isInstanceOf(GenerationExecutionCoordinator.OperationFailure.class);
        assertThat(new GenerationExecutionRepository(jdbc, manager).find(generation).status()).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("select state from assistant_generations where id=?", String.class, generation)).isEqualTo("UNKNOWN");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void revokeOrExpiryPersistsEpochBeforeGatewayAndUnknownCleanupQuarantines(boolean expiry) {
        var gateway = new RecordingGateway() {
            @Override public CleanupReadback revoke(Lease lease, String epoch) {
                outside(); calls.add("revoke");
                assertThat(epoch).isNotBlank();
                assertThat(jdbc.queryForObject("select cleanup_epoch from assistant_generation_executions where generation_id=?", String.class, generation)).isEqualTo(epoch);
                assertThat(jdbc.queryForObject("select state from assistant_execution_bindings where id=?", String.class, lease.binding().bindingId())).isEqualTo("REVOKED");
                assertThatThrownBy(() -> execution.inspectAuthorizedGrant(token, "draft_get")).isInstanceOf(ExecutionBindingService.AuthorizationException.class);
                return new CleanupReadback(lease, epoch, Outcome.SUCCESS, Outcome.UNKNOWN);
            }
        };
        coordinator = new GenerationExecutionCoordinator(execution, new GenerationExecutionRepository(jdbc, manager), gateway);
        var claim = coordinator.claim(generation, unit, "host-owner-1", Duration.ofSeconds(30));
        assertThat(coordinator.configure(claim).status()).isEqualTo("AUTHORIZED");
        if (expiry) jdbc.update("update assistant_execution_bindings set expires_at=? where id=?", Instant.now().minusSeconds(1), claim.binding().bindingId());
        var result = expiry ? coordinator.expire(claim) : coordinator.revoke(claim);
        assertThat(result.status()).isEqualTo("QUARANTINED");
        assertThat(result.cleanupEpoch()).isNotBlank();
        assertThat(jdbc.queryForObject("select cleanup_confirmed from assistant_execution_worker_units where unit_id=?", Boolean.class, unit)).isFalse();
        assertThat(jdbc.queryForObject("select state from assistant_generations where id=?", String.class, generation)).isEqualTo("UNKNOWN");
        assertThat(coordinator.revoke(claim)).isEqualTo(result);
        assertThat(gateway.calls).containsExactly("configure", "readback", "grant", "revoke", "revoke");
        assertThatThrownBy(() -> execution.confirmUnitCleaned(new ExecutionBindingService.CleanupTicket(unit, claim.binding().bindingId(), claim.binding().leaseGeneration()+1, result.cleanupEpoch())))
            .isInstanceOf(ExecutionBindingService.AuthorizationException.class);
    }

    @Test void lateConfigureAfterRevokeCannotIssueGrantOrReplayGateway() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var gateway = new RecordingGateway() {
            @Override public PairReadback readback(Lease lease) {
                super.readback(lease); entered.countDown();
                try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); } catch (InterruptedException ex) { throw new AssertionError(ex); }
                return new PairReadback(lease, Outcome.SUCCESS, Outcome.SUCCESS);
            }
        };
        coordinator = new GenerationExecutionCoordinator(execution, new GenerationExecutionRepository(jdbc, manager), gateway);
        var claim = coordinator.claim(generation, unit, "host-owner-1", Duration.ofSeconds(30));
        try (var pool = Executors.newSingleThreadExecutor()) {
            var call = pool.submit(() -> coordinator.configure(claim));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            try { assertThat(coordinator.revoke(claim).status()).isEqualTo("QUARANTINED"); }
            finally { release.countDown(); }
            assertThatThrownBy(() -> call.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(GenerationExecutionCoordinator.OperationFailure.class);
            assertThat(new GenerationExecutionRepository(jdbc, manager).find(generation).status()).isEqualTo("QUARANTINED");
        }
        assertThat(gateway.token).isNull();
        assertThat(jdbc.queryForObject("select count(*) from assistant_execution_grants where binding_id=?", Integer.class, claim.binding().bindingId())).isZero();
        assertThat(coordinator.configure(claim).status()).isEqualTo("QUARANTINED");
        assertThat(gateway.calls).containsExactly("configure", "readback");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"partial-ack", "partial-readback", "wrong-ack", "wrong-readback", "throw", "null", "expired", "revoked", "membership", "stale-unit"})
    void uncertainOrStaleConfigurationNeverIssuesGrant(String fault) {
        var gateway = new RecordingGateway() {
            @Override public PairReadback configure(Lease l) {
                super.configure(l);
                return switch (fault) {
                    case "partial-ack" -> new PairReadback(l, Outcome.SUCCESS, Outcome.UNKNOWN);
                    case "wrong-ack" -> new PairReadback(new Lease(l.binding(), "other", l.incarnation()), Outcome.SUCCESS, Outcome.SUCCESS);
                    case "throw" -> throw new IllegalStateException("external-secret-never-store");
                    case "null" -> null;
                    default -> new PairReadback(l, Outcome.SUCCESS, Outcome.SUCCESS);
                };
            }
            @Override public PairReadback readback(Lease l) {
                super.readback(l);
                switch (fault) {
                    case "expired" -> jdbc.update("update assistant_execution_bindings set expires_at=? where id=?", Instant.now().minusSeconds(1), l.binding().bindingId());
                    case "revoked" -> execution.revoke(l.binding().bindingId());
                    case "membership" -> jdbc.update("delete from project_members where project_id=?", project);
                    case "stale-unit" -> jdbc.update("update assistant_execution_worker_units set current_lease_generation=current_lease_generation+1 where unit_id=?", unit);
                }
                return switch (fault) {
                    case "partial-readback" -> new PairReadback(l, Outcome.UNKNOWN, Outcome.SUCCESS);
                    case "wrong-readback" -> new PairReadback(new Lease(l.binding(), l.ownerId(), "late-incarnation"), Outcome.SUCCESS, Outcome.SUCCESS);
                    default -> new PairReadback(l, Outcome.SUCCESS, Outcome.SUCCESS);
                };
            }
        };
        coordinator = new GenerationExecutionCoordinator(execution, new GenerationExecutionRepository(jdbc, manager), gateway);
        var claim = coordinator.claim(generation, unit, "host-owner-1", Duration.ofSeconds(30));
        if (Set.of("throw", "expired", "revoked", "membership", "stale-unit").contains(fault)) {
            assertThatThrownBy(() -> coordinator.configure(claim)).isInstanceOf(GenerationExecutionCoordinator.OperationFailure.class);
            assertThat(new GenerationExecutionRepository(jdbc, manager).find(generation).status()).isEqualTo("QUARANTINED");
        } else assertThat(coordinator.configure(claim).status()).isEqualTo("QUARANTINED");
        assertThat(gateway.token).isNull();
        assertThat(jdbc.queryForObject("select count(*) from assistant_execution_grants where binding_id=?", Integer.class, claim.binding().bindingId())).isZero();
        assertThat(jdbc.queryForMap("select * from assistant_generation_executions where generation_id=?", generation).toString()).doesNotContain("external-secret");
        assertThat(jdbc.queryForObject("select state from assistant_generations where id=?", String.class, generation)).isEqualTo("UNKNOWN");
    }

    @Test void forgedCallbackIdentityIsRejectedBeforeGatewayCall() {
        var gateway = new RecordingGateway();
        coordinator = new GenerationExecutionCoordinator(execution, new GenerationExecutionRepository(jdbc, manager), gateway);
        var claim = coordinator.claim(generation, unit, "host-owner-1", Duration.ofSeconds(30));
        var forged = new GenerationExecutionRepository.Attempt(claim.binding(), claim.ownerId(), "late", claim.status(), null);
        assertThatThrownBy(() -> coordinator.configure(forged)).isInstanceOf(ExecutionBindingService.AuthorizationException.class);
        assertThat(gateway.calls).isEmpty();
        assertThat(jdbc.queryForObject("select status from assistant_generation_executions where generation_id=?", String.class, generation)).isEqualTo("CONFIGURING");
    }

    @Test void grantIsIssuedOnlyAfterBothPairReadbacksAndDeliveredOutsideTransaction() {
        var gateway = new RecordingGateway();
        coordinator = new GenerationExecutionCoordinator(execution, new GenerationExecutionRepository(jdbc, manager), gateway);
        var claim = coordinator.claim(generation, unit, "host-owner-1", Duration.ofSeconds(30));
        var result = coordinator.configure(claim);
        assertThat(result.status()).isEqualTo("AUTHORIZED");
        assertThat(gateway.calls).containsExactly("configure", "readback", "grant");
        assertThat(execution.inspectAuthorizedGrant(gateway.token, "draft_get").scope().generationId()).isEqualTo(generation);
        assertThat(jdbc.queryForMap("select * from assistant_generation_executions where generation_id=?", generation).toString()).doesNotContain(gateway.token);
        assertThat(jdbc.queryForMap("select * from assistant_execution_grants where binding_id=?", claim.binding().bindingId()).toString()).doesNotContain(gateway.token);
        assertThat(result.toString()).doesNotContain(gateway.token);
        assertThat(coordinator.configure(claim)).isEqualTo(result);
        assertThat(gateway.calls).hasSize(3);
    }

    class RecordingGateway implements WorkerPoolGateway {
        final List<String> calls = new ArrayList<>();
        String token;
        void outside() { assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); }
        @Override public PairReadback configure(Lease lease) {
            outside(); calls.add("configure"); noGrant(lease);
            return new PairReadback(lease, Outcome.SUCCESS, Outcome.SUCCESS);
        }
        @Override public PairReadback readback(Lease lease) {
            outside(); calls.add("readback"); noGrant(lease);
            return new PairReadback(lease, Outcome.SUCCESS, Outcome.SUCCESS);
        }
        @Override public Outcome acceptGrant(Lease lease, ExecutionBindingService.RawToken raw) {
            outside(); calls.add("grant"); token = raw.reveal();
            assertThat(execution.inspectAuthorizedGrant(token, "draft_get").scope().bindingId()).isEqualTo(lease.binding().bindingId());
            return Outcome.SUCCESS;
        }
        void noGrant(Lease lease) {
            assertThat(jdbc.queryForObject("select count(*) from assistant_execution_grants where binding_id=?", Integer.class, lease.binding().bindingId())).isZero();
        }
    }

    @Test void disabledGatewayQuarantinesWithoutGrantAndNeverRetries() {
        var claim = coordinator.claim(generation, unit, "host-owner-1", Duration.ofSeconds(30));
        var result = coordinator.configure(claim);
        assertThat(result.status()).isEqualTo("QUARANTINED");
        assertThat(coordinator.configure(claim)).isEqualTo(result);
        assertThat(jdbc.queryForObject("select status from assistant_generation_executions where generation_id=?", String.class, generation)).isEqualTo("QUARANTINED");
        assertThat(jdbc.queryForObject("select state from assistant_generations where id=?", String.class, generation)).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("select cleanup_confirmed from assistant_execution_worker_units where unit_id=?", Boolean.class, unit)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from assistant_execution_grants where binding_id=?", Integer.class, claim.binding().bindingId())).isZero();
    }

    @Test void duplicateConcurrentClaimHasOneDurableIdentityAndCannotAllocateAgain() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return coordinator.claim(generation, unit, "host-owner-1", Duration.ofSeconds(30)); });
            var b = pool.submit(() -> { start.await(); return coordinator.claim(generation, unit, "host-owner-1", Duration.ofSeconds(30)); });
            start.countDown();
            var first = a.get(10, TimeUnit.SECONDS);
            assertThat(b.get(10, TimeUnit.SECONDS)).isEqualTo(first);
            assertThat(first.status()).isEqualTo("CONFIGURING");
            assertThat(first.ownerId()).isEqualTo("host-owner-1");
            assertThat(first.incarnation()).isNotBlank();
            assertThat(first.binding().generationId()).isEqualTo(generation);
            assertThat(first.binding().workerUnitId()).isEqualTo(unit);
            assertThat(first.binding().leaseGeneration()).isEqualTo(1);
            assertThat(coordinator.claim(generation, unit, "host-owner-1", Duration.ofSeconds(30))).isEqualTo(first);
            assertThatThrownBy(() -> coordinator.claim(generation, unit, "different-owner", Duration.ofSeconds(30))).isInstanceOf(ExecutionBindingService.AuthorizationException.class);
        }
        assertThat(jdbc.queryForObject("select count(*) from assistant_generation_executions where generation_id=?", Integer.class, generation)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from assistant_execution_bindings where generation_id=?", Integer.class, generation)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select state from assistant_generations where id=?", String.class, generation)).isEqualTo("CONFIGURING");
        assertThat(jdbc.queryForObject("select count(*) from assistant_execution_grants where binding_id in (select id from assistant_execution_bindings where generation_id=?)", Integer.class, generation)).isZero();
    }
}
