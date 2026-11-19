package com.haitlify.aitest.platform.assistant.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@SpringBootTest(properties = {
    "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
    "spring.datasource.url=jdbc:h2:mem:execution-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@ActiveProfiles("test")
class AssistantExecutionServiceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ExecutionBindingService service;

    @Autowired org.springframework.transaction.PlatformTransactionManager manager;

    @Test
    void ambientRepeatableReadCannotAuthorizeOrDeliverTokenBeforeRollback() throws Exception {
        var binding = service.allocate(new ExecutionBindingService.AllocationRequest(generation, unit, Duration.ofSeconds(30)));
        var outer = new org.springframework.transaction.support.TransactionTemplate(manager);
        outer.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        java.util.concurrent.atomic.AtomicReference<ExecutionBindingService.IssuedGrant> delivered = new java.util.concurrent.atomic.AtomicReference<>();
        outer.executeWithoutResult(status -> {
            assertThatThrownBy(() -> delivered.set(service.issueGrant(new ExecutionBindingService.IssueRequest(binding.bindingId(), actor))))
                .isInstanceOf(ExecutionBindingService.AuthorizationException.class);
            status.setRollbackOnly();
        });
        assertThat(delivered.get()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from assistant_execution_grants where binding_id=?", Integer.class, binding.bindingId())).isZero();
        String token = service.issueGrant(new ExecutionBindingService.IssueRequest(binding.bindingId(), actor)).rawToken().reveal();
        try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            outer.executeWithoutResult(status -> {
                assertThat(jdbc.queryForObject("select count(*) from project_members where project_id=?", Integer.class, project)).isEqualTo(1);
                try { pool.submit(() -> jdbc.update("delete from project_members where project_id=?", project)).get(5, java.util.concurrent.TimeUnit.SECONDS); }
                catch (Exception e) { throw new RuntimeException(e); }
                assertThatThrownBy(() -> service.withAuthorizedGrant(token, "draft_get", scope -> { throw new AssertionError("stale authorization callback ran"); }))
                    .isInstanceOf(ExecutionBindingService.AuthorizationException.class);
            });
        }
    }

    @Test
    void databaseCallbackRollsBackAndCannotReenterPublicEntrypoints() {
        var binding = service.allocate(new ExecutionBindingService.AllocationRequest(generation, unit, Duration.ofSeconds(30)));
        String token = service.issueGrant(new ExecutionBindingService.IssueRequest(binding.bindingId(), actor)).rawToken().reveal();
        assertThatThrownBy(() -> service.withAuthorizedGrant(token, "draft_get", scope -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThatThrownBy(() -> service.revoke(binding.bindingId())).isInstanceOf(ExecutionBindingService.AuthorizationException.class);
            jdbc.update("update projects set description='rollback-me' where id=?", project);
            throw new IllegalStateException("abort callback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("abort callback");
        assertThat(jdbc.queryForObject("select description from projects where id=?", String.class, project)).isEmpty();
    }

    private final MutableClock clock = TestClockConfiguration.CLOCK;
    private long actor;
    private long tenant;
    private long project;
    private String generation;
    private String unit;

    @BeforeEach
    void seed() {
        TestClockConfiguration.CLOCK.reset();
        String suffix = java.util.UUID.randomUUID().toString();
        Instant now = clock.instant();
        jdbc.update("insert into platform_users(username,password_hash,role,enabled,created_at,updated_at) values (?,'unused','EDITOR',true,?,?)", suffix, now, now);
        actor = jdbc.queryForObject("select id from platform_users where username=?", Long.class, suffix);
        jdbc.update("insert into tenants(code,name,active,created_at,updated_at) values (?,'Execution',true,?,?)", suffix, now, now);
        tenant = jdbc.queryForObject("select id from tenants where code=?", Long.class, suffix);
        jdbc.update("insert into projects(code,name,description,active,tenant_id,created_at,updated_at) values (?,'Execution','',true,?,?,?)", suffix, tenant, now, now);
        project = jdbc.queryForObject("select id from projects where code=?", Long.class, suffix);
        jdbc.update("insert into tenant_members(tenant_id,username,created_at) values (?,?,?)", tenant, suffix, now);
        jdbc.update("insert into project_members(project_id,username,created_at) values (?,?,?)", project, suffix, now);
        generation = java.util.UUID.randomUUID().toString();
        jdbc.update("insert into assistant_generations(id,actor_id,project_id,tenant_id,request_id,payload_digest,snapshot_ciphertext,state,created_at) values (?,?,?,?,?,'digest','enc:v1:snapshot','WAITING_WORKER',?)",
            generation, actor, project, tenant, suffix, now);
        unit = "unit-" + suffix;
        service.confirmUnitCleaned(service.registerUnit(new ExecutionBindingService.WorkerUnit(unit, "sha256:" + "a".repeat(64), "policy-v1")));
    }

    @Test
    void allocatesOneBindingAndReturnsRawTokenOnlyOnce() {
        var binding = service.allocate(new ExecutionBindingService.AllocationRequest(generation, unit, Duration.ofSeconds(30)));
        var issued = service.issueGrant(new ExecutionBindingService.IssueRequest(binding.bindingId(), actor));
        assertThat(issued.rawToken().toString()).contains("REDACTED");
        String raw = issued.rawToken().reveal();
        assertThat(raw).hasSize(43);
        assertThatThrownBy(issued.rawToken()::reveal).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select token_hash from assistant_execution_grants where binding_id=?", String.class, binding.bindingId()))
            .doesNotContain(raw);
    }

    @Test
    void callbackUsesPersistedScope() {
        var binding = service.allocate(new ExecutionBindingService.AllocationRequest(generation, unit, Duration.ofSeconds(30)));
        var issued = service.issueGrant(new ExecutionBindingService.IssueRequest(binding.bindingId(), actor));
        AtomicInteger calls = new AtomicInteger();
        var result = service.withAuthorizedGrant(issued.rawToken(), ExecutionBindingService.Action.DRAFT_GET,
            scope -> { calls.incrementAndGet(); assertThat(scope.generationId()).isEqualTo(generation); return "ok"; });
        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(1);
    }

    @Test
    void expiresAndRejectsWithoutCallingCallback() {
        var binding = service.allocate(new ExecutionBindingService.AllocationRequest(generation, unit, Duration.ofSeconds(1)));
        var issued = service.issueGrant(new ExecutionBindingService.IssueRequest(binding.bindingId(), actor));
        clock.advance(Duration.ofSeconds(1));
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> service.withAuthorizedGrant(issued.rawToken(), ExecutionBindingService.Action.DRAFT_GET,
            scope -> { calls.incrementAndGet(); return "bad"; })).isInstanceOf(ExecutionBindingService.AuthorizationException.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    void fixedActionsAndMembershipAreEnforcedOnEveryUse() {
        var binding = service.allocate(new ExecutionBindingService.AllocationRequest(generation, unit, Duration.ofSeconds(30)));
        var issued = service.issueGrant(new ExecutionBindingService.IssueRequest(binding.bindingId(), actor));
        assertThatThrownBy(() -> service.withAuthorizedGrant(issued.rawToken(), "arbitrary", scope -> "bad"))
            .isInstanceOf(IllegalArgumentException.class);
        jdbc.update("delete from project_members where project_id=? and username=(select username from platform_users where id=?)", project, actor);
        assertThatThrownBy(() -> service.withAuthorizedGrant(issued.rawToken(), ExecutionBindingService.Action.DRAFT_GET, scope -> "bad"))
            .isInstanceOf(ExecutionBindingService.AuthorizationException.class);
    }

    @Test
    void revokedBindingHasNoSideEffect() {
        var binding = service.allocate(new ExecutionBindingService.AllocationRequest(generation, unit, Duration.ofSeconds(30)));
        var issued = service.issueGrant(new ExecutionBindingService.IssueRequest(binding.bindingId(), actor));
        service.revoke(binding.bindingId());
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> service.withAuthorizedGrant(issued.rawToken(), ExecutionBindingService.Action.DRAFT_CREATE,
            scope -> { calls.incrementAndGet(); return "bad"; })).isInstanceOf(ExecutionBindingService.AuthorizationException.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    void oldLeaseCannotAuthorizeAfterRebindAndUnitRequiresCleanupConfirmation() {
        var first = service.allocate(new ExecutionBindingService.AllocationRequest(generation, unit, Duration.ofSeconds(30)));
        var token = service.issueGrant(new ExecutionBindingService.IssueRequest(first.bindingId(), actor));
        service.revoke(first.bindingId());
        assertThatThrownBy(() -> service.allocate(new ExecutionBindingService.AllocationRequest(generation, unit, Duration.ofSeconds(30))))
            .isInstanceOf(ExecutionBindingService.AllocationConflictException.class);
        service.confirmUnitCleaned(service.requestCleanup(first.bindingId(), first.leaseGeneration()));
        var second = service.allocate(new ExecutionBindingService.AllocationRequest(generation, unit, Duration.ofSeconds(30)));
        assertThat(second.leaseGeneration()).isGreaterThan(first.leaseGeneration());
        assertThatThrownBy(() -> service.withAuthorizedGrant(token.rawToken(), ExecutionBindingService.Action.DRAFT_GET, scope -> "bad"))
            .isInstanceOf(ExecutionBindingService.AuthorizationException.class);
    }

    @Test
    void concurrentAllocationHasOneWinnerForTheUnit() throws Exception {
        String otherGeneration = java.util.UUID.randomUUID().toString();
        jdbc.update("insert into assistant_generations(id,actor_id,project_id,tenant_id,request_id,payload_digest,snapshot_ciphertext,state,created_at) values (?,?,?,?,?,'digest','enc:v1:snapshot','WAITING_WORKER',?)",
            otherGeneration, actor, project, tenant, "other-" + generation, clock.instant());
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return service.allocate(new ExecutionBindingService.AllocationRequest(generation, unit, Duration.ofSeconds(30))); });
            var second = executor.submit(() -> { start.await(); return service.allocate(new ExecutionBindingService.AllocationRequest(otherGeneration, unit, Duration.ofSeconds(30))); });
            start.countDown();
            int successes = 0;
            for (var task : java.util.List.of(first, second)) {
                try { task.get(); successes++; } catch (java.util.concurrent.ExecutionException expected) {
                    assertThat(expected.getCause()).isInstanceOf(ExecutionBindingService.AllocationConflictException.class);
                }
            }
            assertThat(successes).isEqualTo(1);
        }
    }

    @Test
    void missingAllocatorUnitDoesNotCreateAmazinglyConvenientBinding() {
        assertThatThrownBy(() -> service.allocate(new ExecutionBindingService.AllocationRequest(generation, "not-registered", Duration.ofSeconds(30))))
            .isInstanceOf(ExecutionBindingService.AllocationConflictException.class);
        assertThat(jdbc.queryForObject("select count(*) from assistant_execution_bindings where generation_id=?", Integer.class, generation)).isZero();
    }

    @Test
    void stoppedGenerationRejectsAllocation() {
        jdbc.update("update assistant_generations set state='STOPPED' where id=?", generation);
        assertThatThrownBy(() -> service.allocate(new ExecutionBindingService.AllocationRequest(generation, unit, Duration.ofSeconds(30))))
            .isInstanceOf(ExecutionBindingService.AllocationConflictException.class);
    }

    static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void reset() { now = Instant.parse("2026-01-01T00:00:00Z"); }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @TestConfiguration
    static class TestClockConfiguration {
        static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        @Bean @Primary Clock executionTestClock() { return CLOCK; }
    }
}
