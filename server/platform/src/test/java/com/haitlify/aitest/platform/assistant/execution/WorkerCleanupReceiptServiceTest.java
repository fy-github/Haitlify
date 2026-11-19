package com.haitlify.aitest.platform.assistant.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest(properties = {
    "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
    "spring.datasource.url=jdbc:h2:mem:cleanup-receipt-contract;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@ActiveProfiles("test")
class WorkerCleanupReceiptServiceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ExecutionBindingService execution;
    @Autowired PlatformTransactionManager manager;
    String generation;
    String unit;
    GenerationExecutionCoordinator coordinator;

    @BeforeEach void seed() {
        Instant now = Instant.now();
        String suffix = UUID.randomUUID().toString();
        jdbc.update("insert into platform_users(username,password_hash,role,enabled,created_at,updated_at) values (?,'unused','EDITOR',true,?,?)", suffix, now, now);
        long actor = jdbc.queryForObject("select id from platform_users where username=?", Long.class, suffix);
        jdbc.update("insert into tenants(code,name,active,created_at,updated_at) values (?,'Receipt',true,?,?)", suffix, now, now);
        long tenant = jdbc.queryForObject("select id from tenants where code=?", Long.class, suffix);
        jdbc.update("insert into projects(code,name,description,active,tenant_id,created_at,updated_at) values (?,'Receipt','',true,?,?,?)", suffix, tenant, now, now);
        long project = jdbc.queryForObject("select id from projects where code=?", Long.class, suffix);
        jdbc.update("insert into tenant_members(tenant_id,username,created_at) values (?,?,?)", tenant, suffix, now);
        jdbc.update("insert into project_members(project_id,username,created_at) values (?,?,?)", project, suffix, now);
        generation = UUID.randomUUID().toString();
        jdbc.update("insert into assistant_generations(id,actor_id,project_id,tenant_id,request_id,payload_digest,snapshot_ciphertext,state,created_at) values (?,?,?,?,?,'digest','enc:v1:snapshot','WAITING_WORKER',?)", generation, actor, project, tenant, suffix, now);
        unit = "unit-" + suffix;
        execution.confirmUnitCleaned(execution.registerUnit(new ExecutionBindingService.WorkerUnit(unit, "sha256:" + "a".repeat(64), "policy-v1")));
        coordinator = new GenerationExecutionCoordinator(execution, new GenerationExecutionRepository(jdbc, manager), WorkerPoolGateway.disabled());
    }

    @Test void disabledVerifierCannotTurnProxyCleanupIntoPhysicalCleanup() {
        var attempt = coordinator.claim(generation, unit, "owner-1", Duration.ofSeconds(30));
        attempt = coordinator.revoke(attempt);
        var receipt = receipt(attempt);
        var service = new WorkerCleanupReceiptService(jdbc, manager, execution,
            new GenerationExecutionRepository(jdbc, manager), WorkerCleanupReceiptService.CleanupVerifier.disabled());

        var result = service.submit(receipt);

        assertThat(result.status()).isEqualTo(WorkerCleanupReceiptService.ReceiptStatus.UNKNOWN);
        assertThat(jdbc.queryForObject("select cleanup_confirmed from assistant_execution_worker_units where unit_id=?", Boolean.class, unit)).isFalse();
        assertThat(jdbc.queryForObject("select status from assistant_generation_executions where generation_id=?", String.class, generation)).isEqualTo("UNKNOWN");
    }

    @Test void verifiedReceiptConfirmsOnlyAfterVerifierAndIsIdempotent() {
        var attempt = coordinator.revoke(coordinator.claim(generation, unit, "owner-1", Duration.ofSeconds(30)));
        var receipt = receipt(attempt);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var service = service(r -> { calls.incrementAndGet(); return WorkerCleanupReceiptService.VerificationResult.VERIFIED; });

        assertThat(service.submit(receipt).status()).isEqualTo(WorkerCleanupReceiptService.ReceiptStatus.CLEANED);
        assertThat(service.submit(receipt).status()).isEqualTo(WorkerCleanupReceiptService.ReceiptStatus.CLEANED);
        assertThat(calls).hasValue(1);
        assertThat(jdbc.queryForObject("select cleanup_confirmed from assistant_execution_worker_units where unit_id=?", Boolean.class, unit)).isTrue();
        assertThat(jdbc.queryForObject("select status from assistant_generation_executions where generation_id=?", String.class, generation)).isEqualTo("CLEANED");
    }

    @Test void identityMismatchAndFalseAbsentReadbackAreRejectedBeforeVerifier() {
        var attempt = coordinator.revoke(coordinator.claim(generation, unit, "owner-1", Duration.ofSeconds(30)));
        var receipt = receipt(attempt);
        var wrongOwner = new WorkerCleanupReceiptService.CleanupReceipt(receipt.receiptId(), receipt.ticket(), "other-owner", receipt.incarnation(), receipt.resources(), receipt.deletion(), receipt.evidence());
        var verifierCalls = new java.util.concurrent.atomic.AtomicInteger();
        var service = service(r -> { verifierCalls.incrementAndGet(); return WorkerCleanupReceiptService.VerificationResult.VERIFIED; });
        assertThatThrownBy(() -> service.submit(wrongOwner)).isInstanceOf(ExecutionBindingService.AuthorizationException.class);
        var falseAbsent = new WorkerCleanupReceiptService.CleanupReceipt(UUID.randomUUID().toString(), receipt.ticket(), receipt.ownerId(), receipt.incarnation(), receipt.resources(), new WorkerCleanupReceiptService.DeletionReadback(false, true), receipt.evidence());
        assertThatThrownBy(() -> service.submit(falseAbsent)).isInstanceOf(IllegalArgumentException.class);
        assertThat(verifierCalls).hasValue(0);
        assertThat(jdbc.queryForObject("select cleanup_confirmed from assistant_execution_worker_units where unit_id=?", Boolean.class, unit)).isFalse();
    }

    @Test void verifierFailureLeavesQuarantinedAndConfirmFailureNeverCleans() {
        var attempt = coordinator.revoke(coordinator.claim(generation, unit, "owner-1", Duration.ofSeconds(30)));
        var receipt = receipt(attempt);
        var service = service(r -> WorkerCleanupReceiptService.VerificationResult.FAILED);
        assertThat(service.submit(receipt).status()).isEqualTo(WorkerCleanupReceiptService.ReceiptStatus.QUARANTINED);
        assertThat(jdbc.queryForObject("select cleanup_confirmed from assistant_execution_worker_units where unit_id=?", Boolean.class, unit)).isFalse();

        var verified = service(r -> {
            jdbc.update("update assistant_execution_worker_units set cleanup_epoch='different-epoch' where unit_id=?", unit);
            return WorkerCleanupReceiptService.VerificationResult.VERIFIED;
        });
        assertThatThrownBy(() -> verified.submit(receipt)).isInstanceOf(ExecutionBindingService.AuthorizationException.class);
        assertThat(jdbc.queryForObject("select status from assistant_generation_executions where generation_id=?", String.class, generation)).isEqualTo("UNKNOWN");
    }

    @Test void crashLeftWithoutOwnerAttestationIsUnknownAndNeverReusesUnit() {
        var attempt = coordinator.revoke(coordinator.claim(generation, unit, "owner-1", Duration.ofSeconds(30)));
        var identity = new WorkerCleanupReceiptService.CleanupIdentity(generation, attempt.binding().bindingId(), unit, attempt.binding().leaseGeneration(), attempt.cleanupEpoch(), attempt.ownerId(), attempt.incarnation());
        var service = service(r -> WorkerCleanupReceiptService.VerificationResult.VERIFIED);
        var result = service.reconcileCrashLeft(new WorkerCleanupReceiptService.CrashLeftIdentity(identity, new WorkerCleanupReceiptService.ResourceIdentities("container-1", "network-1", Map.of("unit", unit))), i -> false);
        assertThat(result.status()).isEqualTo(WorkerCleanupReceiptService.ReceiptStatus.UNKNOWN);
        assertThatThrownBy(() -> execution.allocate(new ExecutionBindingService.AllocationRequest(UUID.randomUUID().toString(), unit, Duration.ofSeconds(30))))
            .isInstanceOf(ExecutionBindingService.AllocationConflictException.class);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"owner", "incarnation", "binding", "epoch", "lease", "labels", "id"})
    void cleanedReceiptCannotBeReplayedWithChangedIdentity(String field) {
        var a = coordinator.revoke(coordinator.claim(generation, unit, "owner-1", Duration.ofSeconds(30)));
        var r = receipt(a);
        var svc = service(x -> WorkerCleanupReceiptService.VerificationResult.VERIFIED);
        svc.submit(r);
        var t = r.ticket();
        var forged = new WorkerCleanupReceiptService.CleanupReceipt(field.equals("id") ? UUID.randomUUID().toString() : r.receiptId(),
            new ExecutionBindingService.CleanupTicket(t.workerUnitId(), field.equals("binding") ? UUID.randomUUID().toString() : t.bindingId(),
                field.equals("lease") ? 99 : t.leaseGeneration(), field.equals("epoch") ? UUID.randomUUID().toString() : t.epoch()),
            field.equals("owner") ? "forged" : r.ownerId(), field.equals("incarnation") ? UUID.randomUUID().toString() : r.incarnation(),
            field.equals("labels") ? new WorkerCleanupReceiptService.ResourceIdentities("container-1", "network-1", Map.of("unit", "other")) : r.resources(), r.deletion(), r.evidence());
        assertThatThrownBy(() -> svc.submit(forged)).isInstanceOf(ExecutionBindingService.AuthorizationException.class);
    }

    @Test void crashLeftBeforeCleanupEpochExistsIsFencedWithoutRevocation() {
        var a = coordinator.claim(generation, unit, "owner-1", Duration.ofSeconds(30));
        jdbc.update("update assistant_generation_executions set status='CONFIGURE_SENT' where generation_id=?", generation);
        var i = new WorkerCleanupReceiptService.CleanupIdentity(generation, a.binding().bindingId(), unit, a.binding().leaseGeneration(), null, a.ownerId(), a.incarnation());
        var result = service(WorkerCleanupReceiptService.CleanupVerifier.disabled()).reconcileCrashLeft(
            new WorkerCleanupReceiptService.CrashLeftIdentity(i, new WorkerCleanupReceiptService.ResourceIdentities("container-1", "network-1", Map.of("unit", unit))), null);
        assertThat(result.status()).isEqualTo(WorkerCleanupReceiptService.ReceiptStatus.UNKNOWN);
        assertThat(jdbc.queryForObject("select status from assistant_generation_executions where generation_id=?", String.class, generation)).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("select state from assistant_execution_bindings where id=?", String.class, a.binding().bindingId())).isEqualTo("ACTIVE");
        assertThat(coordinator.configure(a).status()).isEqualTo("UNKNOWN");
    }

    @Test void staleUnitIsRejectedBeforeVerifier() {
        var a = coordinator.revoke(coordinator.claim(generation, unit, "owner-1", Duration.ofSeconds(30)));
        var r = receipt(a);
        jdbc.update("update assistant_execution_worker_units set current_lease_generation=99 where unit_id=?", unit);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        assertThatThrownBy(() -> service(x -> { calls.incrementAndGet(); return WorkerCleanupReceiptService.VerificationResult.VERIFIED; }).submit(r))
            .isInstanceOf(ExecutionBindingService.AuthorizationException.class);
        assertThat(calls).hasValue(0);
    }

    @Test void nullVerifierResultIsUnknownNotException() {
        var a = coordinator.revoke(coordinator.claim(generation, unit, "owner-1", Duration.ofSeconds(30)));
        assertThat(service(x -> null).submit(receipt(a)).status()).isEqualTo(WorkerCleanupReceiptService.ReceiptStatus.UNKNOWN);
    }

    @Test void finalAuditFailureRollsBackCleanupAndKeepsUnitUnavailable() {
        var a = coordinator.revoke(coordinator.claim(generation, unit, "owner-1", Duration.ofSeconds(30)));
        var r = receipt(a);
        String constraint = "fail_" + generation.replace("-", "");
        jdbc.execute("alter table assistant_worker_cleanup_receipts add constraint " + constraint + " check (receipt_id <> '" + r.receiptId() + "' or status <> 'CLEANED')");
        try {
            assertThatThrownBy(() -> service(x -> WorkerCleanupReceiptService.VerificationResult.VERIFIED).submit(r)).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("select cleanup_confirmed from assistant_execution_worker_units where unit_id=?", Boolean.class, unit)).isFalse();
            assertThat(jdbc.queryForObject("select state from assistant_execution_bindings where id=?", String.class, a.binding().bindingId())).isEqualTo("REVOKED");
            assertThat(jdbc.queryForObject("select status from assistant_generation_executions where generation_id=?", String.class, generation)).isEqualTo("UNKNOWN");
        } finally { jdbc.execute("alter table assistant_worker_cleanup_receipts drop constraint " + constraint); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"owner", "binding", "epoch", "lease", "incarnation", "unit", "container-present", "network-present", "missing-evidence", "secret-evidence", "digest"})
    void invalidReceiptsNeverReachVerifier(String field) {
        var a = coordinator.revoke(coordinator.claim(generation, unit, "owner-1", Duration.ofSeconds(30)));
        var r = receipt(a); var t = r.ticket();
        var changed = new WorkerCleanupReceiptService.CleanupReceipt(r.receiptId(),
            new ExecutionBindingService.CleanupTicket(field.equals("unit") ? "other-unit" : t.workerUnitId(), field.equals("binding") ? UUID.randomUUID().toString() : t.bindingId(),
                field.equals("lease") ? 99 : t.leaseGeneration(), field.equals("epoch") ? UUID.randomUUID().toString() : t.epoch()),
            field.equals("owner") ? "other-owner" : r.ownerId(), field.equals("incarnation") ? UUID.randomUUID().toString() : r.incarnation(), r.resources(),
            new WorkerCleanupReceiptService.DeletionReadback(!field.equals("container-present"), !field.equals("network-present")),
            new WorkerCleanupReceiptService.Evidence(field.equals("missing-evidence") ? "" : field.equals("secret-evidence") ? "https://host?token=secret" : r.evidence().reference(), field.equals("digest") ? "invalid" : r.evidence().digest()));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        assertThatThrownBy(() -> service(x -> { calls.incrementAndGet(); return WorkerCleanupReceiptService.VerificationResult.VERIFIED; }).submit(changed)).isInstanceOf(RuntimeException.class);
        assertThat(calls).hasValue(0);
        assertThat(jdbc.queryForObject("select count(*) from assistant_worker_cleanup_receipts where generation_id=?", Integer.class,generation)).isZero();
        assertThat(jdbc.queryForObject("select cleanup_confirmed from assistant_execution_worker_units where unit_id=?", Boolean.class,unit)).isFalse();
    }

    @Test void oldExactReceiptReadbackDoesNotMutateNewLease() {
        var a = coordinator.revoke(coordinator.claim(generation, unit, "owner-1", Duration.ofSeconds(30)));
        var r = receipt(a);
        var svc = service(x -> WorkerCleanupReceiptService.VerificationResult.VERIFIED);
        svc.submit(r);
        String next = UUID.randomUUID().toString();
        assertThat(jdbc.update("insert into assistant_generations(id,actor_id,project_id,tenant_id,request_id,payload_digest,snapshot_ciphertext,state,created_at) select ?,actor_id,project_id,tenant_id,?,'digest','enc:v1:snapshot','WAITING_WORKER',created_at from assistant_generations where id=?",next,next,generation)).isEqualTo(1);
        var b = coordinator.claim(next,unit,"owner-2",Duration.ofSeconds(30));
        var before = jdbc.queryForMap("select * from assistant_execution_worker_units where unit_id=?",unit);
        assertThat(svc.submit(r).status()).isEqualTo(WorkerCleanupReceiptService.ReceiptStatus.CLEANED);
        assertThat(jdbc.queryForMap("select * from assistant_execution_worker_units where unit_id=?",unit)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select state from assistant_execution_bindings where id=?",String.class,b.binding().bindingId())).isEqualTo("ACTIVE");
        assertThatThrownBy(() -> svc.reconcileCrashLeft(new WorkerCleanupReceiptService.CrashLeftIdentity(
            new WorkerCleanupReceiptService.CleanupIdentity(generation,a.binding().bindingId(),unit,a.binding().leaseGeneration(),a.cleanupEpoch(),a.ownerId(),a.incarnation()),r.resources()), x -> true)).isInstanceOf(ExecutionBindingService.AuthorizationException.class);
        assertThat(jdbc.queryForMap("select * from assistant_execution_worker_units where unit_id=?",unit)).isEqualTo(before);
    }

    @Test void evidenceConflictAndNewReceiptIdForSameTicketAreRejected() {
        var a = coordinator.revoke(coordinator.claim(generation,unit,"owner-1",Duration.ofSeconds(30)));
        var r = receipt(a); var svc = service(WorkerCleanupReceiptService.CleanupVerifier.disabled());
        svc.submit(r);
        assertThatThrownBy(() -> svc.submit(new WorkerCleanupReceiptService.CleanupReceipt(r.receiptId(),r.ticket(),r.ownerId(),r.incarnation(),r.resources(),r.deletion(),
            new WorkerCleanupReceiptService.Evidence("evidence://different","sha256:"+"c".repeat(64))))).isInstanceOf(ExecutionBindingService.AuthorizationException.class);
        assertThatThrownBy(() -> svc.submit(receipt(a))).isInstanceOf(ExecutionBindingService.AuthorizationException.class);
        var row = jdbc.queryForMap("select * from assistant_worker_cleanup_receipts where receipt_id=?",r.receiptId());
        assertThat(row.get("evidence_ref")).isEqualTo(r.evidence().reference());
        assertThat(row.get("evidence_digest")).isEqualTo(r.evidence().digest());
        assertThat(new tools.jackson.databind.ObjectMapper().readTree((String)row.get("resource_labels")).get("unit").asText()).isEqualTo(unit);
    }

    @Test void verifierAndAttestationFailuresDoNotLeakMessagesOrClaimCleanup() {
        var a = coordinator.revoke(coordinator.claim(generation,unit,"owner-1",Duration.ofSeconds(30)));
        var r = receipt(a);
        assertThat(service(x -> { throw new IllegalStateException("external-secret"); }).submit(r).status()).isEqualTo(WorkerCleanupReceiptService.ReceiptStatus.QUARANTINED);
        var i = new WorkerCleanupReceiptService.CleanupIdentity(generation,a.binding().bindingId(),unit,a.binding().leaseGeneration(),a.cleanupEpoch(),a.ownerId(),a.incarnation());
        assertThat(service(x -> null).reconcileCrashLeft(new WorkerCleanupReceiptService.CrashLeftIdentity(i,r.resources()),x -> { throw new IllegalStateException("external-secret"); }).status()).isEqualTo(WorkerCleanupReceiptService.ReceiptStatus.UNKNOWN);
        assertThat(jdbc.queryForMap("select * from assistant_worker_cleanup_receipts where receipt_id=?",r.receiptId()).toString()).doesNotContain("external-secret");
        assertThat(jdbc.queryForObject("select cleanup_confirmed from assistant_execution_worker_units where unit_id=?",Boolean.class,unit)).isFalse();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"CONFIGURE_SENT", "GRANT_ISSUED", "REVOKING"})
    void attestedCrashLeftIsQuarantinedButNotRevokedOrReplayed(String state) {
        var a = coordinator.claim(generation,unit,"owner-1",Duration.ofSeconds(30));
        jdbc.update("update assistant_generation_executions set status=? where generation_id=?",state,generation);
        var i = new WorkerCleanupReceiptService.CleanupIdentity(generation,a.binding().bindingId(),unit,a.binding().leaseGeneration(),null,a.ownerId(),a.incarnation());
        var resources = new WorkerCleanupReceiptService.ResourceIdentities("c1","n1",Map.of("unit",unit));
        assertThat(service(x -> null).reconcileCrashLeft(new WorkerCleanupReceiptService.CrashLeftIdentity(i,resources), x -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(x.identity()).isEqualTo(i); return true;
        }).status()).isEqualTo(WorkerCleanupReceiptService.ReceiptStatus.QUARANTINED);
        assertThat(jdbc.queryForObject("select status from assistant_generation_executions where generation_id=?",String.class,generation)).isEqualTo("QUARANTINED");
        assertThat(jdbc.queryForObject("select state from assistant_execution_bindings where id=?",String.class,a.binding().bindingId())).isEqualTo("ACTIVE");
        assertThat(coordinator.configure(a).status()).isEqualTo("QUARANTINED");
    }

    @Test void defaultConstructorIsFailClosedAndAmbientTransactionsAreRejected() {
        var a = coordinator.revoke(coordinator.claim(generation,unit,"owner-1",Duration.ofSeconds(30)));
        var r = receipt(a);
        var svc = new WorkerCleanupReceiptService(jdbc,manager,execution,new GenerationExecutionRepository(jdbc,manager));
        new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(tx -> {
            assertThatThrownBy(() -> svc.submit(r)).isInstanceOf(ExecutionBindingService.AuthorizationException.class);
            assertThatThrownBy(() -> svc.reconcileCrashLeft(null,null)).isInstanceOf(ExecutionBindingService.AuthorizationException.class);
        });
        assertThat(svc.submit(r).status()).isEqualTo(WorkerCleanupReceiptService.ReceiptStatus.UNKNOWN);
    }

    @Test void receiptSchemaEnforcesVocabularyAndTicketUniqueness() {
        var a = coordinator.revoke(coordinator.claim(generation,unit,"owner-1",Duration.ofSeconds(30)));
        var r = receipt(a); service(x -> null).submit(r);
        assertThatThrownBy(() -> jdbc.update("update assistant_worker_cleanup_receipts set status='INVENTED' where receipt_id=?",r.receiptId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update assistant_worker_cleanup_receipts set verifier_result='caller-name' where receipt_id=?",r.receiptId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update assistant_worker_cleanup_receipts set network_absent=false where receipt_id=?",r.receiptId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into assistant_worker_cleanup_receipts select ?,generation_id,binding_id,worker_unit_id,lease_generation,cleanup_epoch,owner_id,incarnation,container_id,network_id,resource_labels,container_absent,network_absent,evidence_ref,evidence_digest,status,verifier_result,created_at,updated_at from assistant_worker_cleanup_receipts where receipt_id=?",UUID.randomUUID().toString(),r.receiptId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void concurrentExactReceiptReplayAfterPendingPrecheckReturnsDurableCleaned() throws Exception {
        var a = coordinator.revoke(coordinator.claim(generation, unit, "owner-1", Duration.ofSeconds(30)));
        var r = receipt(a);
        // Preload the exact pending receipt so the racing replay's precheck observes UNKNOWN.
        assertThat(service(x -> WorkerCleanupReceiptService.VerificationResult.UNKNOWN).submit(r).status())
            .isEqualTo(WorkerCleanupReceiptService.ReceiptStatus.UNKNOWN);
        var calls = new AtomicInteger();
        var observed = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        // Pause one real replay AFTER it has read the pending receipt, before its fencing transaction.
        // No state or return value is mocked: the other submit commits real unit/binding/receipt cleanup.
        var gatedJdbc = new JdbcTemplate(jdbc.getDataSource()) {
            boolean first = true;
            @Override public List<Map<String, Object>> queryForList(String sql, Object... args) {
                var rows = super.queryForList(sql, args);
                if (first && sql.startsWith("select * from assistant_worker_cleanup_receipts where receipt_id=")) {
                    first = false;
                    observed.countDown();
                    try {
                        if (!resume.await(20, TimeUnit.SECONDS)) throw new AssertionError("replay barrier timeout");
                    } catch (InterruptedException e) { throw new AssertionError(e); }
                }
                return rows;
            }
        };
        var replay = new WorkerCleanupReceiptService(gatedJdbc, manager, execution,
            new GenerationExecutionRepository(jdbc, manager),
            x -> { calls.incrementAndGet(); return WorkerCleanupReceiptService.VerificationResult.VERIFIED; });
        var winner = service(x -> { calls.incrementAndGet(); return WorkerCleanupReceiptService.VerificationResult.VERIFIED; });
        var pool = Executors.newSingleThreadExecutor();
        try {
            var pending = pool.submit(() -> replay.submit(r));
            assertThat(observed.await(20, TimeUnit.SECONDS)).isTrue();
            assertThat(winner.submit(r).status()).isEqualTo(WorkerCleanupReceiptService.ReceiptStatus.CLEANED);
            var committed = snapshot();
            resume.countDown();
            var failure = org.assertj.core.api.Assertions.catchThrowable(() ->
                assertThat(pending.get(20, TimeUnit.SECONDS).status()).isEqualTo(WorkerCleanupReceiptService.ReceiptStatus.CLEANED));
            assertThat(snapshot()).as("losing exact replay must preserve the winner's committed cleanup").isEqualTo(committed);
            assertThat(failure).as("concurrent exact receipt replay must return durable CLEANED after pending precheck race").isNull();
            assertThat(calls).as("an already-CLEANED duplicate must not verify or confirm a second time").hasValue(1);
            assertThat(jdbc.queryForObject("select cleanup_confirmed from assistant_execution_worker_units where unit_id=?", Boolean.class, unit)).isTrue();
            assertThat(jdbc.queryForObject("select state from assistant_execution_bindings where id=?", String.class, a.binding().bindingId())).isEqualTo("CLEANED");
            // Different evidence for the same ticket stays rejected after the race.
            assertThatThrownBy(() -> winner.submit(new WorkerCleanupReceiptService.CleanupReceipt(r.receiptId(), r.ticket(), r.ownerId(), r.incarnation(),
                r.resources(), r.deletion(), new WorkerCleanupReceiptService.Evidence("evidence://other", "sha256:" + "c".repeat(64)))))
                .isInstanceOf(ExecutionBindingService.AuthorizationException.class);
            assertThat(snapshot()).isEqualTo(committed);
        } finally { resume.countDown(); pool.shutdownNow(); }
    }

    @Test void concurrentReplayLosingAfterItsVerifierReturnsDurableCleanedWithoutReconfirming() throws Exception {
        var a = coordinator.revoke(coordinator.claim(generation, unit, "owner-1", Duration.ofSeconds(30)));
        var r = receipt(a);
        assertThat(service(x -> WorkerCleanupReceiptService.VerificationResult.UNKNOWN).submit(r).status())
            .isEqualTo(WorkerCleanupReceiptService.ReceiptStatus.UNKNOWN);
        var calls = new AtomicInteger();
        var verifying = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var replay = new WorkerCleanupReceiptService(jdbc, manager, execution, new GenerationExecutionRepository(jdbc, manager), x -> {
            calls.incrementAndGet();
            verifying.countDown();
            try {
                if (!release.await(20, TimeUnit.SECONDS)) throw new AssertionError("verifier barrier timeout");
            } catch (InterruptedException e) { throw new AssertionError(e); }
            return WorkerCleanupReceiptService.VerificationResult.UNKNOWN;
        });
        var winner = service(x -> { calls.incrementAndGet(); return WorkerCleanupReceiptService.VerificationResult.VERIFIED; });
        var pool = Executors.newSingleThreadExecutor();
        try {
            var pending = pool.submit(() -> replay.submit(r));
            assertThat(verifying.await(20, TimeUnit.SECONDS)).isTrue();
            // Both submissions already verified before either completed cleanup; neither may verify again.
            assertThat(winner.submit(r).status()).isEqualTo(WorkerCleanupReceiptService.ReceiptStatus.CLEANED);
            var committed = snapshot();
            release.countDown();
            var failure = org.assertj.core.api.Assertions.catchThrowable(() ->
                assertThat(pending.get(20, TimeUnit.SECONDS).status()).isEqualTo(WorkerCleanupReceiptService.ReceiptStatus.CLEANED));
            assertThat(snapshot()).as("losing replay must not mutate the winner's committed cleanup").isEqualTo(committed);
            assertThat(failure).as("duplicate replay that lost after verification must return durable CLEANED").isNull();
            assertThat(calls).as("each submission verified once; the duplicate is never re-verified").hasValue(2);
            assertThat(jdbc.queryForObject("select cleanup_confirmed from assistant_execution_worker_units where unit_id=?", Boolean.class, unit)).isTrue();
            assertThat(jdbc.queryForObject("select count(*) from assistant_worker_cleanup_receipts where generation_id=?", Integer.class, generation)).isEqualTo(1);
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    private List<Object> snapshot() {
        return List.of(
            jdbc.queryForMap("select * from assistant_execution_worker_units where unit_id=?", unit),
            jdbc.queryForMap("select * from assistant_generation_executions where generation_id=?", generation),
            jdbc.queryForList("select * from assistant_execution_bindings where generation_id=?", generation),
            jdbc.queryForList("select * from assistant_worker_cleanup_receipts where generation_id=?", generation));
    }

    private WorkerCleanupReceiptService service(WorkerCleanupReceiptService.CleanupVerifier verifier) {
        return new WorkerCleanupReceiptService(jdbc, manager, execution, new GenerationExecutionRepository(jdbc, manager), verifier);
    }

    private WorkerCleanupReceiptService.CleanupReceipt receipt(GenerationExecutionRepository.Attempt attempt) {
        return new WorkerCleanupReceiptService.CleanupReceipt(
            UUID.randomUUID().toString(),
            new ExecutionBindingService.CleanupTicket(unit, attempt.binding().bindingId(), attempt.binding().leaseGeneration(), attempt.cleanupEpoch()),
            attempt.ownerId(), attempt.incarnation(),
            new WorkerCleanupReceiptService.ResourceIdentities("container-1", "network-1", Map.of("unit", unit)),
            new WorkerCleanupReceiptService.DeletionReadback(true, true),
            new WorkerCleanupReceiptService.Evidence("evidence://cleanup/1", "sha256:" + "b".repeat(64)));
    }
}
