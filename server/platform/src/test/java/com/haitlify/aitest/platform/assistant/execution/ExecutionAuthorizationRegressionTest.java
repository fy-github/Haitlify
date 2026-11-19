package com.haitlify.aitest.platform.assistant.execution;

import static org.assertj.core.api.Assertions.*;
import com.haitlify.aitest.platform.assistant.security.capability.CapabilityTokenCodec;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Real independent JDBC transactions; no model, containers or business database. */
class ExecutionAuthorizationRegressionTest {
    DriverManagerDataSource ds;
    HookJdbc j;
    DataSourceTransactionManager tm;
    ExecutionBindingService s;
    java.sql.Connection schema;
    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    static class HookJdbc extends JdbcTemplate {
        volatile Consumer<String> beforeQuery = sql -> {};
        volatile Consumer<String> afterQuery = sql -> {};
        HookJdbc(javax.sql.DataSource ds) { super(ds); }
        @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            beforeQuery.accept(sql); var rows = super.query(sql, mapper, args); afterQuery.accept(sql); return rows;
        }
    }
    @BeforeEach void seed() throws Exception {
        ds = new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=4000", "sa", "");
        j = new HookJdbc(ds); tm = new DataSourceTransactionManager(ds); schema = ds.getConnection();
        for (String sql : List.of(
            "create table platform_users(id bigint primary key,username varchar(128),role varchar(16),enabled boolean)",
            "create table tenants(id bigint primary key,active boolean)",
            "create table projects(id bigint primary key,tenant_id bigint,active boolean)",
            "create table tenant_members(tenant_id bigint,username varchar(128),primary key(tenant_id,username))",
            "create table project_members(project_id bigint,username varchar(128),primary key(project_id,username))",
            "create table assistant_generations(id varchar(36) primary key,actor_id bigint,tenant_id bigint,project_id bigint,state varchar(32))",
            "create table callback_effects(id int primary key)")) j.execute(sql);
        for (String file : List.of("V75__assistant_execution_grants.sql", "V76__assistant_execution_lease_fencing.sql")) {
            try (var in = getClass().getResourceAsStream("/db/migration/"+file)) {
                if (in != null) for (String sql : new String(in.readAllBytes(), StandardCharsets.UTF_8).split(";"))
                    if (!sql.isBlank()) try (var st=schema.createStatement()) { st.execute(sql); }
            }
        }
        j.update("insert into platform_users values(1,'alice','EDITOR',true)");
        j.update("insert into tenants values(2,true)"); j.update("insert into projects values(3,2,true)");
        j.update("insert into tenant_members values(2,'alice')"); j.update("insert into project_members values(3,'alice')");
        for(String g:List.of("g1","g2","g3")) j.update("insert into assistant_generations values(?,1,2,3,'WAITING_WORKER')",g);
        Clock c=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId z){return this;} public Instant instant(){return now.get();}};
        s=new ExecutionBindingService(j,new CapabilityTokenCodec(),tm,c);
        s.confirmUnitCleaned(s.registerUnit(new ExecutionBindingService.WorkerUnit("u1","sha256:"+"a".repeat(64),"p1")));
    }
    @AfterEach void close() throws Exception {schema.close();}
    ExecutionBindingService.Binding allocate(String g){return s.allocate(new ExecutionBindingService.AllocationRequest(g,"u1",Duration.ofSeconds(30)));}
    String issue(ExecutionBindingService.Binding b){return s.issueGrant(new ExecutionBindingService.IssueRequest(b.bindingId(),1)).rawToken().reveal();}
    void denied(Runnable r){assertThatThrownBy(r::run).isInstanceOf(ExecutionBindingService.AuthorizationException.class);}
    static void await(CountDownLatch l){try{assertThat(l.await(5,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    static void blocked(Future<?> f) {assertThatThrownBy(()->f.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);}

    @ParameterizedTest @ValueSource(strings={"update platform_users set enabled=false", "update platform_users set role='VIEWER'", "delete from tenant_members", "delete from project_members", "update tenants set active=false", "update projects set active=false", "update projects set tenant_id=999"})
    void allocationRequiresPersistedOwnersCurrentAuthority(String change){
        j.update(change); denied(()->allocate("g1"));
        assertThat(j.queryForObject("select count(*) from assistant_execution_bindings",Integer.class)).isZero();
    }
    @Test void registrationMetadataIsNotCleanupEvidence() {
        s.registerUnit(new ExecutionBindingService.WorkerUnit("fresh","sha256:"+"a".repeat(64),"p1"));
        assertThat(j.queryForObject("select cleanup_confirmed from assistant_execution_worker_units where unit_id='fresh'",Boolean.class)).isFalse();
    }
    @Test void stoppedGenerationCannotIssue(){var b=allocate("g1");j.update("update assistant_generations set state='STOPPED'");denied(()->issue(b));assertThat(j.queryForObject("select count(*) from assistant_execution_grants",Integer.class)).isZero();}
    @Test void unitOnlyCleanupIsAlwaysRejected(){var b=allocate("g1");s.revoke(b.bindingId());denied(()->s.confirmUnitCleaned("u1"));}

    ExecutionBindingService.CleanupTicket cleanupTicket(ExecutionBindingService.Binding b) {
        return s.requestCleanup(b.bindingId(),b.leaseGeneration());
    }
    void confirm(ExecutionBindingService.CleanupTicket ticket) { s.confirmUnitCleaned(ticket); }
    @Test void staleCleanupCannotCleanLaterLease() throws Exception {
        var first=allocate("g1");s.revoke(first.bindingId());var old=cleanupTicket(first);confirm(old);
        var second=allocate("g2");s.revoke(second.bindingId());
        denied(()->confirm(old));
        assertThatThrownBy(()->allocate("g3")).isInstanceOf(ExecutionBindingService.AllocationConflictException.class);
        confirm(cleanupTicket(second));assertThat(allocate("g3").leaseGeneration()).isEqualTo(3);
    }
    @Test void staleRevokeCannotMutateCurrentUnitOrBinding() throws Exception {
        var first=allocate("g1");s.revoke(first.bindingId());confirm(cleanupTicket(first));
        var second=allocate("g2");String token=issue(second);
        var unitBefore=j.queryForMap("select * from assistant_execution_worker_units where unit_id='u1'");
        var bindingBefore=j.queryForMap("select * from assistant_execution_bindings where id=?",second.bindingId());
        s.revoke(first.bindingId());
        assertThat(j.queryForMap("select * from assistant_execution_worker_units where unit_id='u1'")).isEqualTo(unitBefore);
        assertThat(j.queryForMap("select * from assistant_execution_bindings where id=?",second.bindingId())).isEqualTo(bindingBefore);
        assertThat(s.<String>withAuthorizedGrant(token,"draft_get",scope->scope.bindingId())).isEqualTo(second.bindingId());
    }
    @Test void allocateAndRevokeContendOnUnitBeforeEitherLocksBinding() throws Exception {
        var b=allocate("g1");issue(b);
        var held=new CountDownLatch(1);var release=new CountDownLatch(1);var attempted=new CountDownLatch(1);
        j.afterQuery=sql->{if(Thread.currentThread().getName().equals("allocator")&&sql.contains("assistant_execution_worker_units")&&sql.endsWith("for update")){held.countDown();await(release);}};
        var firstLock=new AtomicBoolean(true);
        j.beforeQuery=sql->{if(Thread.currentThread().getName().equals("revoker")&&sql.endsWith("for update")&&firstLock.getAndSet(false)){attempted.countDown();assertThat(sql).contains("assistant_execution_worker_units");}};
        try(var pool=Executors.newFixedThreadPool(2)){
            var allocation=pool.submit(()->{Thread.currentThread().setName("allocator");assertThatThrownBy(()->allocate("g2")).isInstanceOf(ExecutionBindingService.AllocationConflictException.class);});await(held);
            var revoke=pool.submit(()->{Thread.currentThread().setName("revoker");s.revoke(b.bindingId());});
            try{await(attempted);blocked(revoke);}finally{release.countDown();}
            allocation.get(5,TimeUnit.SECONDS);revoke.get(5,TimeUnit.SECONDS);
            assertThat(j.queryForObject("select state from assistant_execution_bindings where id=?",String.class,b.bindingId())).isEqualTo("REVOKED");
        }finally{release.countDown();}
    }

    @ParameterizedTest @ValueSource(strings={"assistant_execution_grants", "assistant_execution_bindings"})
    void inspectionReturnsMinimumExpiryFromLockedRowsNotDiscovery(String table) {
        var binding=allocate("g1");String token=issue(binding);
        Instant expiry=now.get().plusSeconds(10);
        var changed=new AtomicBoolean();
        j.afterQuery=sql->{
            if(sql.contains("from assistant_execution_grants") && !sql.endsWith("for update") && changed.compareAndSet(false,true)) {
                String key=table.equals("assistant_execution_grants") ? "binding_id" : "id";
                assertThat(j.update("update "+table+" set expires_at=? where "+key+"=?",expiry,binding.bindingId())).isEqualTo(1);
            }
        };
        var inspected=s.inspectAuthorizedGrant(token,"draft_get");
        assertThat(changed).isTrue();
        assertThat(inspected.expiresAt()).isEqualTo(expiry);
        assertThat(inspected.scope()).isEqualTo(new ExecutionBindingService.Scope(1,2,3,"g1",binding.bindingId(),binding.leaseGeneration()));
        now.set(expiry);
        denied(()->s.inspectAuthorizedGrant(token,"draft_get"));
    }
    @Test void inspectionRejectsAmbientTransactionAndCallbackReentry() {
        String token=issue(allocate("g1"));
        new TransactionTemplate(tm).executeWithoutResult(status->denied(()->s.inspectAuthorizedGrant(token,"draft_get")));
        s.withAuthorizedGrant(token,"draft_get",scope->{denied(()->s.inspectAuthorizedGrant(token,"draft_get"));return true;});
        assertThatThrownBy(()->s.inspectAuthorizedGrant(token,"unsupported")).isInstanceOf(IllegalArgumentException.class);
        denied(()->s.inspectAuthorizedGrant("invalid","draft_get"));
    }

    @Test void everyPathUsesExplicitMonotoneLocksWithoutJoinLocks() {
        var order=List.of("assistant_execution_worker_units","assistant_generations","assistant_execution_bindings","assistant_execution_grants","tenant_members","project_members","platform_users","tenants","projects");
        var seen=new ArrayList<Integer>();
        j.beforeQuery=sql->{if(sql.endsWith("for update")){assertThat(sql).doesNotContain(" join ");int rank=-1;for(int i=0;i<order.size();i++)if(sql.contains("from "+order.get(i)+" "))rank=i;assertThat(rank).isGreaterThanOrEqualTo(0);if(!seen.isEmpty())assertThat(rank).isGreaterThanOrEqualTo(seen.getLast());seen.add(rank);}};
        var b=allocate("g1");assertThat(seen).contains(0,1,4,5,6,7,8);seen.clear();
        String token=issue(b);assertThat(seen).containsExactly(0,1,2,3,4,5,6,7,8);seen.clear();
        s.withAuthorizedGrant(token,"draft_get",scope->"ok");assertThat(seen).containsExactly(0,1,2,3,4,5,6,7,8);seen.clear();
        s.inspectAuthorizedGrant(token,"draft_get");assertThat(seen).containsExactly(0,1,2,3,4,5,6,7,8);seen.clear();
        s.revoke(b.bindingId());assertThat(seen).containsExactly(0,1,2,3);seen.clear();
        var ticket=cleanupTicket(b);assertThat(seen).containsExactly(0,1,2,3);seen.clear();
        confirm(ticket);assertThat(seen).containsExactly(0,1,2,3);
    }
    @Test void existingUserDeletionOrderDoesNotCycleWithAuthorization() throws Exception {
        String token=issue(allocate("g1"));var deleted=new CountDownLatch(1);var release=new CountDownLatch(1);var waiting=new CountDownLatch(1);
        j.beforeQuery=sql->{if(Thread.currentThread().getName().equals("use")&&sql.contains("from tenant_members")&&sql.endsWith("for update"))waiting.countDown();};
        try(var pool=Executors.newFixedThreadPool(2)){
            var deletion=pool.submit(()->new TransactionTemplate(tm).execute(status->{
                j.update("delete from tenant_members where username='alice'");deleted.countDown();await(release);
                j.update("delete from project_members where username='alice'");j.update("delete from platform_users where username='alice'");return true;
            }));await(deleted);
            var use=pool.submit(()->{Thread.currentThread().setName("use");denied(()->s.withAuthorizedGrant(token,"draft_get",scope->{throw new AssertionError("deleted actor");}));});await(waiting);
            try{blocked(use);}finally{release.countDown();}deletion.get(5,TimeUnit.SECONDS);use.get(5,TimeUnit.SECONDS);
            assertThat(j.queryForObject("select count(*) from platform_users",Integer.class)).isZero();
        }finally{release.countDown();}
    }

    @Test void issueAndUseRejectMismatchedCurrentLease() {
        var b=allocate("g1");
        j.update("update assistant_execution_worker_units set current_lease_generation=99 where unit_id='u1'");
        denied(()->issue(b));
        j.update("update assistant_execution_worker_units set current_lease_generation=? where unit_id='u1'",b.leaseGeneration());
        String token=issue(b);
        j.update("update assistant_execution_worker_units set current_binding_id='different-owner' where unit_id='u1'");
        denied(()->s.withAuthorizedGrant(token,"draft_get",scope->{throw new AssertionError("stale owner callback");}));
    }
    @Test void expiryCannotAutomaticallyReuseAndCleanupEpochCannotBeForgedOrReplayed() {
        var b=allocate("g1");String token=issue(b);now.set(now.get().plusSeconds(31));
        denied(()->issue(b));denied(()->s.withAuthorizedGrant(token,"draft_get",scope->{throw new AssertionError("expired callback");}));
        assertThatThrownBy(()->allocate("g2")).isInstanceOf(ExecutionBindingService.AllocationConflictException.class);
        var ticket=cleanupTicket(b);
        assertThat(cleanupTicket(b)).isEqualTo(ticket);
        denied(()->s.confirmUnitCleaned(new ExecutionBindingService.CleanupTicket("u1",b.bindingId(),b.leaseGeneration(),UUID.randomUUID().toString())));
        denied(()->s.confirmUnitCleaned(new ExecutionBindingService.CleanupTicket("u1",b.bindingId(),b.leaseGeneration()+1,ticket.epoch())));
        confirm(ticket);denied(()->confirm(ticket));
        var next=allocate("g2");assertThat(next.leaseGeneration()).isEqualTo(2);
        assertThat(j.queryForObject("select cleanup_confirmed from assistant_execution_worker_units where unit_id='u1'",Boolean.class)).isFalse();
    }
    @Test void tokenIsNotDeliveredWhileActualCommitIsBlockedOrAfterCommitFailure() throws Exception {
        var b=allocate("g1");var committing=new CountDownLatch(1);var release=new CountDownLatch(1);
        var handedOff=new AtomicReference<ExecutionBindingService.IssuedGrant>();
        var controlled=new DataSourceTransactionManager(ds){
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status){committing.countDown();await(release);super.doCommit(status);}
        };
        var issuer=new ExecutionBindingService(j,new CapabilityTokenCodec(),controlled,Clock.fixed(now.get(),ZoneOffset.UTC));
        try(var pool=Executors.newSingleThreadExecutor()){
            var call=pool.submit(()->handedOff.set(issuer.issueGrant(new ExecutionBindingService.IssueRequest(b.bindingId(),1))));await(committing);
            try{blocked(call);assertThat(handedOff.get()).isNull();assertThat(j.queryForObject("select count(*) from assistant_execution_grants",Integer.class)).isZero();}finally{release.countDown();}
            call.get(5,TimeUnit.SECONDS);
        }finally{release.countDown();}
        assertThat(handedOff.get()).isNotNull();assertThat(j.queryForObject("select count(*) from assistant_execution_grants",Integer.class)).isEqualTo(1);
        s.revoke(b.bindingId());confirm(cleanupTicket(b));var next=allocate("g2");
        var failing=new DataSourceTransactionManager(ds){@Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status){throw new org.springframework.transaction.TransactionSystemException("forced commit failure");}};
        failing.setRollbackOnCommitFailure(true);
        var failedIssuer=new ExecutionBindingService(j,new CapabilityTokenCodec(),failing,Clock.fixed(now.get(),ZoneOffset.UTC));
        handedOff.set(null);
        assertThatThrownBy(()->handedOff.set(failedIssuer.issueGrant(new ExecutionBindingService.IssueRequest(next.bindingId(),1)))).isInstanceOf(org.springframework.transaction.TransactionSystemException.class);
        assertThat(handedOff.get()).isNull();assertThat(j.queryForObject("select count(*) from assistant_execution_grants where binding_id=?",Integer.class,next.bindingId())).isZero();
    }
    @Test void callbackBlocksRevokeAndLaterUseIsDenied() throws Exception {
        var b=allocate("g1");String token=issue(b);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var attempting=new CountDownLatch(1);
        j.beforeQuery=sql->{if(Thread.currentThread().getName().equals("revoke")&&sql.contains("worker_units")&&sql.endsWith("for update"))attempting.countDown();};
        try(var pool=Executors.newFixedThreadPool(2)){
            var use=pool.submit(()->s.withAuthorizedGrant(token,"draft_get",scope->{entered.countDown();await(release);return "ok";}));await(entered);
            var revoke=pool.submit(()->{Thread.currentThread().setName("revoke");s.revoke(b.bindingId());});await(attempting);
            try{blocked(revoke);}finally{release.countDown();}assertThat(use.get(5,TimeUnit.SECONDS)).isEqualTo("ok");revoke.get(5,TimeUnit.SECONDS);
            denied(()->s.withAuthorizedGrant(token,"draft_get",scope->{throw new AssertionError("post-revoke callback");}));
        }finally{release.countDown();}
    }
    @Test void pendingBindingRevokeBlocksUseThenDenies() throws Exception {
        var b=allocate("g1");String token=issue(b);var held=new CountDownLatch(1);var release=new CountDownLatch(1);var attempting=new CountDownLatch(1);var first=new AtomicBoolean(true);
        j.afterQuery=sql->{if(Thread.currentThread().getName().equals("revoke")&&sql.contains("worker_units")&&sql.endsWith("for update")&&first.getAndSet(false)){held.countDown();await(release);}};
        j.beforeQuery=sql->{if(Thread.currentThread().getName().equals("use")&&sql.contains("worker_units")&&sql.endsWith("for update"))attempting.countDown();};
        try(var pool=Executors.newFixedThreadPool(2)){
            var revoke=pool.submit(()->{Thread.currentThread().setName("revoke");s.revoke(b.bindingId());});await(held);
            var use=pool.submit(()->{Thread.currentThread().setName("use");denied(()->s.withAuthorizedGrant(token,"draft_get",scope->{throw new AssertionError("post-revoke callback");}));});await(attempting);
            try{blocked(use);}finally{release.countDown();}revoke.get(5,TimeUnit.SECONDS);use.get(5,TimeUnit.SECONDS);
        }finally{release.countDown();}
    }
    @Test void cleanupAndAllocationSerializeOnUnitWithoutBindingCycle() throws Exception {
        var b=allocate("g1");s.revoke(b.bindingId());var ticket=cleanupTicket(b);
        var held=new CountDownLatch(1);var release=new CountDownLatch(1);var attempting=new CountDownLatch(1);var first=new AtomicBoolean(true);
        j.afterQuery=sql->{if(Thread.currentThread().getName().equals("cleanup")&&sql.contains("worker_units")&&sql.endsWith("for update")){held.countDown();await(release);}};
        j.beforeQuery=sql->{if(Thread.currentThread().getName().equals("allocate")&&sql.endsWith("for update")&&first.getAndSet(false)){attempting.countDown();assertThat(sql).contains("worker_units");}};
        try(var pool=Executors.newFixedThreadPool(2)){
            var clean=pool.submit(()->{Thread.currentThread().setName("cleanup");confirm(ticket);});await(held);
            var allocation=pool.submit(()->{Thread.currentThread().setName("allocate");return allocate("g2");});await(attempting);
            try{blocked(allocation);}finally{release.countDown();}clean.get(5,TimeUnit.SECONDS);assertThat(allocation.get(5,TimeUnit.SECONDS).leaseGeneration()).isEqualTo(2);
        }finally{release.countDown();}
    }
    @Test void stoppedGenerationRejectsUseWithoutDatabaseEffect() {
        String token=issue(allocate("g1"));j.update("update assistant_generations set state='STOPPED' where id='g1'");
        denied(()->s.withAuthorizedGrant(token,"draft_get",scope->{j.update("insert into callback_effects values(1)");return null;}));
        assertThat(j.queryForObject("select count(*) from callback_effects",Integer.class)).isZero();
    }
    @Test void allPublicEntriesFailClosedInAmbientTransactionWithoutConsumingToken() {
        var b=allocate("g1");var raw=s.issueGrant(new ExecutionBindingService.IssueRequest(b.bindingId(),1)).rawToken();
        new TransactionTemplate(tm).executeWithoutResult(status->{
            denied(()->s.registerUnit(new ExecutionBindingService.WorkerUnit("u2","sha256:"+"a".repeat(64),"p1")));
            denied(()->allocate("g2"));denied(()->issue(b));denied(()->s.revoke(b.bindingId()));
            denied(()->s.requestCleanup(b.bindingId(),b.leaseGeneration()));
            denied(()->s.confirmUnitCleaned("u1"));
            denied(()->s.confirmUnitCleaned(new ExecutionBindingService.CleanupTicket("u1",b.bindingId(),1,"invalid")));
            denied(()->s.withAuthorizedGrant(raw,ExecutionBindingService.Action.DRAFT_GET,scope->{throw new AssertionError("ambient callback");}));
            denied(()->s.withAuthorizedGrant(raw,"draft_get",scope->{throw new AssertionError("ambient callback");}));
        });
        assertThat(s.<String>withAuthorizedGrant(raw,"draft_get",scope->"ok")).isEqualTo("ok");
    }

    @ParameterizedTest @ValueSource(strings={"delete from tenant_members", "delete from project_members", "update platform_users set enabled=false", "update tenants set active=false", "update projects set active=false"})
    void callbackHoldsEveryAuthorityRowUntilCommit(String change) throws Exception {
        String token=issue(allocate("g1"));var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var writing=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var use=pool.submit(()->s.withAuthorizedGrant(token,"draft_get",scope->{j.update("insert into callback_effects values(1)");entered.countDown();await(release);return "ok";}));
            await(entered);
            var writer=pool.submit(()->{writing.countDown();return j.update(change);});await(writing);
            try{blocked(writer);}finally{release.countDown();}
            assertThat(use.get(5,TimeUnit.SECONDS)).isEqualTo("ok");assertThat(writer.get(5,TimeUnit.SECONDS)).isEqualTo(1);
            denied(()->s.withAuthorizedGrant(token,"draft_get",scope->{throw new AssertionError("revoked callback");}));
            assertThat(j.queryForObject("select count(*) from callback_effects",Integer.class)).isEqualTo(1);
        }finally{release.countDown();}
    }
    @ParameterizedTest @ValueSource(strings={"delete from tenant_members", "delete from project_members", "update platform_users set enabled=false", "update tenants set active=false", "update projects set active=false"})
    void pendingRevocationCommitsBeforeUseAndUseMustReject(String change) throws Exception {
        String token=issue(allocate("g1"));var written=new CountDownLatch(1);var release=new CountDownLatch(1);var started=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var writer=pool.submit(()->new TransactionTemplate(tm).execute(status->{j.update(change);written.countDown();await(release);return true;}));await(written);
            var use=pool.submit(()->{started.countDown();denied(()->s.withAuthorizedGrant(token,"draft_get",scope->{throw new AssertionError("stale callback");}));});await(started);
            try{blocked(use);}finally{release.countDown();}
            writer.get(5,TimeUnit.SECONDS);use.get(5,TimeUnit.SECONDS);
        }finally{release.countDown();}
    }
}
