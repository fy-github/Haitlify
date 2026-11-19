package com.haitlify.aitest.platform.assistant.execution;

import static org.assertj.core.api.Assertions.*;
import com.haitlify.aitest.platform.assistant.security.capability.CapabilityTokenCodec;
import com.zaxxer.hikari.HikariDataSource;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Actual populated Flyway chain, isolated H2 only; never starts a worker or calls a model. */
class V77LegacyBindingRetirementMigrationTest {
    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    static final String DIGEST = "sha256:" + "a".repeat(64);

    @Test void populatedV75UpgradeReleasesGenerationWithoutCleaningOrReplaying() {
        try (Fixture f = new Fixture()) {
            Legacy old = f.legacy("legacy", "WAITING_WORKER", NOW.plusSeconds(30));
            f.migrate("76");
            var unit = f.unit("legacy");
            var generation = f.generation(old.generation());
            var s = f.service(NOW.plusSeconds(31));
            assertThatThrownBy(() -> s.withAuthorizedGrant(old.raw(), "draft_get", scope -> {
                throw new AssertionError("old callback ran");
            })).isInstanceOf(ExecutionBindingService.AuthorizationException.class);
            s.revoke(old.binding());
            assertThat(f.binding(old.binding())).containsEntry("state", "ACTIVE")
                .containsEntry("active_generation_key", old.generation());
            assertThatThrownBy(() -> s.requestCleanup(old.binding(), 7))
                .isInstanceOf(ExecutionBindingService.AuthorizationException.class);
            s.confirmUnitCleaned(s.registerUnit(new ExecutionBindingService.WorkerUnit("replacement", DIGEST, "p1")));
            f.migrate("latest");
            assertThatCode(() -> s.allocate(new ExecutionBindingService.AllocationRequest(old.generation(), "replacement", Duration.ofSeconds(30))))
                .as("V76 quarantined binding must no longer reserve the generation").doesNotThrowAnyException();
            assertThat(f.unit("legacy")).isEqualTo(unit);
            assertThat(f.generation(old.generation())).isEqualTo(generation);
            assertThat(f.binding(old.binding())).containsEntry("state", "REVOKED")
                .containsEntry("active_generation_key", null).containsEntry("active_worker_unit_key", null);
            assertThat(f.j.queryForObject("select revoked from assistant_execution_grants where binding_id=?", Boolean.class, old.binding())).isTrue();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {-30, 300})
    void expiredAndUnexpiredLegacyLeasesAreRevokedButNeverCleaned(long expirySeconds) {
        try (Fixture f = new Fixture()) {
            Legacy old = f.legacy("old", "WAITING_WORKER", NOW.plusSeconds(expirySeconds));
            var bindingBefore = f.binding(old.binding());
            var grantBefore = f.j.queryForMap("select * from assistant_execution_grants where binding_id=?", old.binding());
            f.migrate("76"); var unitBefore = f.unit("old"); var generationBefore = f.generation(old.generation());
            f.migrate("77");
            var audit = f.j.queryForMap("select * from assistant_execution_legacy_retirements where binding_id=?", old.binding());
            assertThat(audit).containsEntry("original_state", "ACTIVE")
                .containsEntry("original_active_generation_key", old.generation())
                .containsEntry("original_active_worker_unit_key", "old")
                .containsEntry("original_generation_state", "WAITING_WORKER")
                .containsEntry("lease_generation", 7L).containsEntry("original_revoked_at", null);
            var expectedBinding = new HashMap<>(bindingBefore);
            expectedBinding.put("state", "REVOKED"); expectedBinding.put("active_generation_key", null);
            expectedBinding.put("active_worker_unit_key", null); expectedBinding.put("revoked_at", audit.get("retired_at"));
            assertThat(f.binding(old.binding())).isEqualTo(expectedBinding);
            var expectedGrant = new HashMap<>(grantBefore); expectedGrant.put("revoked", true);
            assertThat(f.j.queryForMap("select * from assistant_execution_grants where binding_id=?", old.binding())).isEqualTo(expectedGrant);
            assertThat(f.unit("old")).isEqualTo(unitBefore);
            assertThat(f.generation(old.generation())).isEqualTo(generationBefore);
            assertThat(f.j.queryForObject("select count(*) from assistant_execution_bindings", Integer.class)).isEqualTo(1);
            var s = f.service(NOW);
            assertThatThrownBy(() -> s.withAuthorizedGrant(old.raw(), "draft_get", scope -> { throw new AssertionError("callback ran"); }))
                .isInstanceOf(ExecutionBindingService.AuthorizationException.class);
            assertThatThrownBy(() -> s.issueGrant(new ExecutionBindingService.IssueRequest(old.binding(), f.actor)))
                .isInstanceOf(ExecutionBindingService.AuthorizationException.class);
            assertThatThrownBy(() -> s.requestCleanup(old.binding(), 7)).isInstanceOf(ExecutionBindingService.AuthorizationException.class);
            assertThatThrownBy(() -> s.confirmUnitCleaned(new ExecutionBindingService.CleanupTicket("old", null, 0, "invented")))
                .isInstanceOf(ExecutionBindingService.AuthorizationException.class);
            assertThatThrownBy(() -> s.registerUnit(new ExecutionBindingService.WorkerUnit("old", DIGEST, "p1")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(() -> s.allocate(new ExecutionBindingService.AllocationRequest(old.generation(), "old", Duration.ofSeconds(30))))
                .isInstanceOf(ExecutionBindingService.AllocationConflictException.class);
            s.revoke(old.binding());
            assertThat(f.unit("old")).isEqualTo(unitBefore);
            // Replaying the SQL itself, not only Flyway's already-applied version skip, is idempotent.
            var beforeReplay = f.executionSnapshot();
            f.replayV77(); f.migrate("77");
            assertThat(f.executionSnapshot()).isEqualTo(beforeReplay);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"STOPPED", "RUNNING", "READY"})
    void retirementDoesNotInventOutcomeOrMakeNonWaitingGenerationAllocatable(String state) {
        try (Fixture f = new Fixture()) {
            Legacy old = f.legacy("uncertain-side-effects", state, NOW.plusSeconds(300));
            var generation = f.generation(old.generation());
            f.migrate("76"); f.migrate("77");
            var s = f.service(NOW);
            s.confirmUnitCleaned(s.registerUnit(new ExecutionBindingService.WorkerUnit("new-unit", DIGEST, "p1")));
            assertThatThrownBy(() -> s.allocate(new ExecutionBindingService.AllocationRequest(old.generation(), "new-unit", Duration.ofSeconds(30))))
                .isInstanceOf(ExecutionBindingService.AllocationConflictException.class).hasMessage("generation is not allocatable");
            assertThat(f.generation(old.generation())).isEqualTo(generation);
            assertThat(f.binding(old.binding())).containsEntry("state", "REVOKED").containsEntry("active_generation_key", null);
            assertThat(f.unit("uncertain-side-effects")).containsEntry("active", false).containsEntry("cleanup_confirmed", false);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "update assistant_execution_worker_units set active=true",
        "update assistant_execution_worker_units set cleanup_confirmed=true",
        "update assistant_execution_worker_units set current_binding_id='unknown'",
        "update assistant_execution_worker_units set current_lease_generation=7",
        "update assistant_execution_worker_units set cleanup_epoch='pending'",
        "update assistant_execution_bindings set lease_generation=0",
        "update assistant_execution_bindings set active_generation_key='mismatched'",
        "update assistant_execution_bindings set active_worker_unit_key='mismatched'",
        "delete from assistant_generations"
    })
    void insufficientOrInconsistentLegacyEvidenceFailsClosed(String mutation) {
        try (Fixture f = new Fixture()) {
            f.legacy("ambiguous", "WAITING_WORKER", NOW.plusSeconds(300)); f.migrate("76");
            assertThat(f.j.update(mutation)).isEqualTo(1);
            var before = f.executionSnapshot(); f.migrate("77");
            // New audit table is intentionally excluded from this old-row comparison.
            assertThat(f.executionSnapshot().subList(0, 4)).isEqualTo(before.subList(0, 4));
            assertThat(f.j.queryForObject("select count(*) from assistant_execution_legacy_retirements", Integer.class)).isZero();
        }
    }

    @Test void existingV76DatabaseKeepsFreshAndNormallyDisabledUnitsUnchanged() {
        try (Fixture f = new Fixture()) {
            Legacy old = f.legacy("legacy", "WAITING_WORKER", NOW.plusSeconds(300)); f.migrate("76");
            var s = f.service(NOW);
            s.registerUnit(new ExecutionBindingService.WorkerUnit("pending", DIGEST, "p1"));
            s.confirmUnitCleaned(s.registerUnit(new ExecutionBindingService.WorkerUnit("never-bound-disabled", DIGEST, "p1")));
            s.confirmUnitCleaned(s.registerUnit(new ExecutionBindingService.WorkerUnit("bound-disabled", DIGEST, "p1")));
            var binding = s.allocate(new ExecutionBindingService.AllocationRequest(f.newGeneration("WAITING_WORKER"), "bound-disabled", Duration.ofSeconds(30)));
            s.issueGrant(new ExecutionBindingService.IssueRequest(binding.bindingId(), f.actor));
            s.confirmUnitCleaned(s.registerUnit(new ExecutionBindingService.WorkerUnit("cleaned-disabled", DIGEST, "p1")));
            var cleaned = s.allocate(new ExecutionBindingService.AllocationRequest(f.newGeneration("WAITING_WORKER"), "cleaned-disabled", Duration.ofSeconds(30)));
            s.revoke(cleaned.bindingId()); s.confirmUnitCleaned(s.requestCleanup(cleaned.bindingId(), cleaned.leaseGeneration()));
            f.j.update("update assistant_execution_worker_units set active=false where unit_id<>'legacy'");
            var units = f.j.queryForList("select * from assistant_execution_worker_units order by unit_id");
            var bindings = f.j.queryForList("select * from assistant_execution_bindings where id<>? order by id", old.binding());
            var grants = f.j.queryForList("select * from assistant_execution_grants where binding_id<>? order by id", old.binding());
            f.migrate("77");
            assertThat(f.j.queryForList("select * from assistant_execution_worker_units order by unit_id")).isEqualTo(units);
            assertThat(f.j.queryForList("select * from assistant_execution_bindings where id<>? order by id", old.binding())).isEqualTo(bindings);
            assertThat(f.j.queryForList("select * from assistant_execution_grants where binding_id<>? order by id", old.binding())).isEqualTo(grants);
            assertThat(f.j.queryForList("select binding_id from assistant_execution_legacy_retirements", String.class)).containsExactly(old.binding());
        }
    }

    @Test void populatedV75CanApplyBothPendingMigrationsInOneFlywayUpgrade() {
        try (Fixture f = new Fixture()) {
            Legacy old = f.legacy("old", "WAITING_WORKER", NOW.plusSeconds(300));
            f.migrate("77");
            assertThat(f.binding(old.binding())).containsEntry("state", "REVOKED")
                .containsEntry("active_generation_key", null).containsEntry("active_worker_unit_key", null);
            assertThat(f.unit("old")).containsEntry("active", false).containsEntry("cleanup_confirmed", false);
            var s = f.service(NOW);
            s.confirmUnitCleaned(s.registerUnit(new ExecutionBindingService.WorkerUnit("replacement", DIGEST, "p1")));
            assertThat(s.allocate(new ExecutionBindingService.AllocationRequest(old.generation(), "replacement", Duration.ofSeconds(30))).generationId())
                .isEqualTo(old.generation());
        }
    }

    @Test void historicalRevocationAndCleanupEvidenceIsNotRewritten() {
        try (Fixture f = new Fixture()) {
            Legacy revoked = f.legacy("revoked", "WAITING_WORKER", NOW);
            Legacy cleaned = f.legacy("cleaned", "WAITING_WORKER", NOW);
            f.j.update("update assistant_execution_bindings set state='REVOKED',active_generation_key=null,active_worker_unit_key=null,revoked_at=? where id=?", NOW.minusSeconds(10), revoked.binding());
            f.j.update("update assistant_execution_bindings set state='CLEANED',active_generation_key=null,active_worker_unit_key=null where id=?", cleaned.binding());
            var revokedBefore = f.binding(revoked.binding()); var cleanedBefore = f.binding(cleaned.binding());
            f.migrate("76"); f.migrate("77");
            assertThat(f.binding(revoked.binding())).isEqualTo(revokedBefore);
            assertThat(f.binding(cleaned.binding())).as("historical cleanup is not a newly inferred revocation").isEqualTo(cleanedBefore);
            assertThat(f.j.queryForList("select revoked from assistant_execution_grants", Boolean.class)).containsOnly(true).hasSize(2);
        }
    }

    @Test void freshEmptyDatabaseMigratesWithoutFabricatingExecutionWork() {
        try (var ds = new HikariDataSource()) {
            ds.setJdbcUrl("jdbc:h2:mem:v77-empty-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
            ds.setUsername("sa"); ds.setPassword("");
            var flyway = Flyway.configure().dataSource(ds).target("77").load(); flyway.migrate();
            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("77");
            var j = new JdbcTemplate(ds);
            for (String table : List.of("assistant_generations", "assistant_execution_worker_units", "assistant_execution_bindings", "assistant_execution_grants", "assistant_execution_legacy_retirements"))
                assertThat(j.queryForObject("select count(*) from " + table, Integer.class)).as(table).isZero();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
        }
    }

    record Legacy(String generation, String binding, String raw) {}
    static final class Fixture implements AutoCloseable {
        final HikariDataSource ds = new HikariDataSource();
        final JdbcTemplate j;
        final CapabilityTokenCodec codec = new CapabilityTokenCodec();
        final long actor, tenant, project;
        Fixture() {
            ds.setJdbcUrl("jdbc:h2:mem:v77-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
            ds.setUsername("sa"); ds.setPassword(""); ds.setMaximumPoolSize(2); ds.setMinimumIdle(1);
            j = new JdbcTemplate(ds); migrate("75");
            j.update("insert into platform_users(username,password_hash,role,enabled,created_at,updated_at) values ('v77-actor','unused','EDITOR',true,?,?)", NOW, NOW);
            actor = j.queryForObject("select id from platform_users where username='v77-actor'", Long.class);
            j.update("insert into tenants(code,name,active,created_at,updated_at) values ('v77-test','Test',true,?,?)", NOW, NOW);
            tenant = j.queryForObject("select id from tenants where code='v77-test'", Long.class);
            j.update("insert into projects(code,name,description,active,tenant_id,created_at,updated_at) values ('v77-test','Test','',true,?,?,?)", tenant, NOW, NOW);
            project = j.queryForObject("select id from projects where code='v77-test'", Long.class);
            j.update("insert into tenant_members(tenant_id,username,created_at) values (?,'v77-actor',?)", tenant, NOW);
            j.update("insert into project_members(project_id,username,created_at) values (?,'v77-actor',?)", project, NOW);
        }
        void migrate(String target) { Flyway.configure().dataSource(ds).target(target).load().migrate(); }
        String newGeneration(String state) {
            String id = UUID.randomUUID().toString();
            j.update("insert into assistant_generations(id,actor_id,project_id,tenant_id,request_id,payload_digest,snapshot_ciphertext,state,created_at) values (?,?,?,?,?,'digest','enc:v1:snapshot',?,?)", id, actor, project, tenant, id, state, NOW);
            return id;
        }
        Legacy legacy(String unit, String state, Instant expiry) {
            String g = newGeneration(state), b = UUID.randomUUID().toString();
            j.update("insert into assistant_execution_worker_units(unit_id,image_digest,policy_version,active,cleanup_confirmed,updated_at) values (?,?, 'p1',true,true,?)", unit, DIGEST, NOW);
            j.update("insert into assistant_execution_bindings(id,generation_id,worker_unit_id,lease_generation,state,active_generation_key,active_worker_unit_key,expires_at,created_at) values(?,?,?,7,'ACTIVE',?,?,?,?)", b, g, unit, g, unit, expiry, NOW);
            var token = codec.issue(); String raw = token.rawToken().reveal();
            j.update("insert into assistant_execution_grants(id,binding_id,actor_id,token_hash,audience,actions,expires_at,revoked,created_at) values(?,?,?,?,?,'draft_get',?,false,?)", UUID.randomUUID().toString(), b, actor, token.hash(), ExecutionBindingService.AUDIENCE, expiry, NOW);
            return new Legacy(g, b, raw);
        }
        ExecutionBindingService service(Instant now) { return new ExecutionBindingService(j, codec, new DataSourceTransactionManager(ds), Clock.fixed(now, ZoneOffset.UTC)); }
        Map<String,Object> unit(String id) { return j.queryForMap("select * from assistant_execution_worker_units where unit_id=?", id); }
        Map<String,Object> binding(String id) { return j.queryForMap("select * from assistant_execution_bindings where id=?", id); }
        Map<String,Object> generation(String id) { return j.queryForMap("select * from assistant_generations where id=?", id); }
        List<List<Map<String,Object>>> executionSnapshot() {
            var result = new ArrayList<List<Map<String,Object>>>();
            for (String table : List.of("assistant_execution_worker_units", "assistant_execution_bindings", "assistant_execution_grants", "assistant_generations"))
                result.add(j.queryForList("select * from " + table + " order by 1"));
            if (j.queryForObject("select count(*) from information_schema.tables where table_name='assistant_execution_legacy_retirements'", Integer.class) > 0)
                result.add(j.queryForList("select * from assistant_execution_legacy_retirements order by binding_id"));
            return result;
        }
        void replayV77() {
            try (var connection = ds.getConnection()) {
                org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.ClassPathResource("db/migration/V77__retire_quarantined_legacy_execution_bindings.sql"));
            } catch (java.sql.SQLException ex) { throw new AssertionError(ex); }
        }
        public void close() { j.execute("SHUTDOWN"); ds.close(); }
    }
}
