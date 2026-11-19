package com.haitlify.aitest.platform.assistant.generation.tool;

import static org.assertj.core.api.Assertions.*;
import com.haitlify.aitest.platform.assistant.execution.ExecutionBindingService;
import com.haitlify.aitest.platform.security.SecretCipher;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
    "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
    "spring.datasource.url=jdbc:h2:mem:generation-tools;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@ActiveProfiles("test")
class GenerationDraftToolsTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ExecutionBindingService execution;
    @Autowired SecretCipher cipher;
    @Autowired ObjectMapper json;
    @Autowired GenerationDraftTools tools;
    long actor, tenant, project;
    String generation, unit, token, snapshot;
    ExecutionBindingService.Binding binding;

    @BeforeEach void seed() throws Exception {
        var now = Instant.now();
        String suffix = UUID.randomUUID().toString();
        jdbc.update("insert into platform_users(username,password_hash,role,enabled,created_at,updated_at) values (?,'unused','EDITOR',true,?,?)", suffix, now, now);
        actor = jdbc.queryForObject("select id from platform_users where username=?", Long.class, suffix);
        jdbc.update("insert into tenants(code,name,active,created_at,updated_at) values (?,'Tools',true,?,?)", suffix, now, now);
        tenant = jdbc.queryForObject("select id from tenants where code=?", Long.class, suffix);
        jdbc.update("insert into projects(code,name,description,active,tenant_id,created_at,updated_at) values (?,'Tools','',true,?,?,?)", suffix, tenant, now, now);
        project = jdbc.queryForObject("select id from projects where code=?", Long.class, suffix);
        jdbc.update("insert into tenant_members(tenant_id,username,created_at) values (?,?,?)", tenant, suffix, now);
        jdbc.update("insert into project_members(project_id,username,created_at) values (?,?,?)", project, suffix, now);
        generation = UUID.randomUUID().toString();
        snapshot = json.writeValueAsString(new TreeMap<>(Map.of("requestId", suffix, "projectId", project,
            "requirementRef", "requirement:immutable", "requirementText", "Immutable requirement\n| A | B |\n| --- | --- |\n| x | y |",
            "targetUrl", "https://example.com/app", "targetRevision", "v1")));
        jdbc.update("insert into assistant_generations(id,actor_id,project_id,tenant_id,request_id,payload_digest,snapshot_ciphertext,state,created_at) values (?,?,?,?,?,?,?,'WAITING_WORKER',?)",
            generation, actor, project, tenant, suffix, hash(snapshot), cipher.encrypt(snapshot), now);
        unit = "unit-" + suffix;
        execution.confirmUnitCleaned(execution.registerUnit(new ExecutionBindingService.WorkerUnit(unit, "sha256:" + "a".repeat(64), "policy-v1")));
        binding = execution.allocate(new ExecutionBindingService.AllocationRequest(generation, unit, Duration.ofMinutes(5)));
        token = execution.issueGrant(new ExecutionBindingService.IssueRequest(binding.bindingId(), actor)).rawToken().reveal();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test void contextReadsEncryptedImmutableSnapshotWithoutAmbientIdentityOrToken() {
        var context = tools.context(token);
        assertThat(context.requirementText()).startsWith("Immutable requirement");
        assertThat(context.requirementRef()).isEqualTo("requirement:immutable");
        assertThat(context.targetUrl()).isEqualTo("https://example.com/app");
        assertThat(context.targetRevision()).isEqualTo("v1");
        assertThat(json.writeValueAsString(context)).doesNotContain(token, "enc:v1:", "password");
        assertThat(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    String spec() {
        return """
            {"id":"case-1","name":"Visible content","startUrl":"https://example.com/app","steps":[
              {"id":"check","action":"assertVisible","target":"#content","value":null}]}
            """;
    }

    @Test void createPersistsDraftRootSummaryAndProvenanceInAuthorizedScope() throws Exception {
        var draft = tools.create(token, spec());
        assertThat(draft.state()).isEqualTo("DRAFT");
        assertThat(draft.confirmedBy()).isNull();
        assertThat(draft.inputs()).isEmpty();
        assertThat(draft.targetRef()).isEqualTo("project:" + project);
        assertThat(draft.targetRevision()).isEqualTo("v1");
        var row = jdbc.queryForMap("select * from assistant_draft where draft_id=?", draft.draftId());
        assertThat(row.get("created_by")).isEqualTo(actor);
        assertThat(row.get("tenant_id")).isEqualTo(tenant);
        assertThat(row.get("project_id")).isEqualTo(project);
        assertThat(row.get("root_draft_id")).isEqualTo(draft.draftId());
        assertThat(json.readTree(row.get("requirement_summary_json").toString()).path("tableCount").asInt()).isEqualTo(1);
        var source = jdbc.queryForMap("select * from assistant_generation_drafts where generation_id=?", generation);
        assertThat(source.get("binding_id")).isEqualTo(binding.bindingId());
        assertThat(((Number) source.get("lease_generation")).longValue()).isEqualTo(binding.leaseGeneration());
        assertThat(source.get("snapshot_digest")).isEqualTo(hash(snapshot));
        assertThat(source.get("draft_id")).isEqualTo(draft.draftId());
        assertThat(tools.get(token)).isEqualTo(draft);
        assertThat(tools.create(token, spec())).isEqualTo(draft);
        assertThat(jdbc.queryForObject("select state from assistant_generations where id=?", String.class, generation)).isEqualTo("WAITING_WORKER");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "https://evil.example/app", "http://example.com/app", "https://example.com:444/app",
        "https://user@example.com/app", "https://example.com.evil/app", "//evil.example/app"
    })
    void rejectsStartUrlOutsideExactAuthorizedOrigin(String url) {
        assertBad(spec().replace("https://example.com/app", url));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "https://evil.example/path", "http://example.com/path", "https://example.com:444/path",
        "https://user@example.com/path", "//evil.example/path", "/\\\\evil.example/path"
    })
    void rejectsEveryGotoOutsideOrigin(String url) {
        var tree = (tools.jackson.databind.node.ObjectNode) json.readTree(spec());
        ((tools.jackson.databind.node.ArrayNode) tree.path("steps")).addObject()
            .put("id", "nav").put("action", "goto").put("target", url);
        assertBad(json.writeValueAsString(tree));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "actorId", "tenantId", "projectId", "generationId", "bindingId", "draftId", "inputs", "secretRef", "script"
    })
    void rejectsAdditionalModelAuthorityOrInputFields(String field) {
        var tree = (tools.jackson.databind.node.ObjectNode) json.readTree(spec());
        tree.put(field, "not-authorized");
        assertBad(json.writeValueAsString(tree));
    }

    @Test void rejectsOversizeDuplicateTrailingAndNestedUnknownFields() {
        assertBad(spec().replace("Visible content", "x".repeat(33000)));
        assertBad(spec().replace("\"id\":\"case-1\"", "\"id\":\"case-1\",\"id\":\"case-2\""));
        assertBad(spec() + "{}");
        assertBad(spec().replace("\"value\":null", "\"value\":null,\"code\":\"alert(1)\""));
        assertBad(spec().replace("\"name\":\"Visible content\"", "\"name\":7"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"input", "fill", "select", "evaluate", "upload", "download"})
    void noInputOrArbitraryActionsInThisSlice(String action) {
        var tree = (tools.jackson.databind.node.ObjectNode) json.readTree(spec());
        ((tools.jackson.databind.node.ArrayNode) tree.path("steps")).addObject()
            .put("id", "unsafe").put("action", action).put("target", "#password").put("value", "literal-secret");
        assertBad(json.writeValueAsString(tree));
    }

    @Test void rejectsSecretShapedValuesEvenInAssertions() {
        assertBad(spec().replace("\"value\":null", "\"value\":\"" + "a".repeat(40) + "\""));
    }

    private void assertBad(String spec) {
        assertThatThrownBy(() -> tools.create(token, spec)).isInstanceOfSatisfying(
            org.springframework.web.server.ResponseStatusException.class,
            ex -> { assertThat(ex.getStatusCode().value()).isEqualTo(400); assertThat(ex.getCause()).isNull();
                assertThat(ex.getReason()).isEqualTo("Invalid generation draft specification"); });
        assertThat(jdbc.queryForObject("select count(*) from assistant_draft where project_id=?", Integer.class, project)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from assistant_generation_drafts where generation_id=?", Integer.class, generation)).isZero();
    }

    @Test void getWithoutDraftIsNotFoundAndHasNoSideEffects() {
        assertThatThrownBy(() -> tools.get(token)).isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
            ex -> assertThat(ex.getStatusCode().value()).isEqualTo(404));
        assertNoDraft();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"revoked", "expired", "role", "membership", "invalid", "action", "binding"})
    void everyEntryRevalidatesGrantAndLiveAuthority(String change) {
        switch (change) {
            case "revoked" -> execution.revoke(binding.bindingId());
            case "expired" -> jdbc.update("update assistant_execution_grants set expires_at=? where binding_id=?", Instant.now().minusSeconds(1), binding.bindingId());
            case "role" -> jdbc.update("update platform_users set role='VIEWER' where id=?", actor);
            case "membership" -> jdbc.update("delete from project_members where project_id=?", project);
            case "invalid" -> token = "unissued-token";
            case "action" -> jdbc.update("update assistant_execution_grants set actions='unsupported' where binding_id=?", binding.bindingId());
            case "binding" -> jdbc.update("update assistant_execution_bindings set state='REVOKED' where id=?", binding.bindingId());
        }
        assertThatThrownBy(() -> tools.context(token)).isInstanceOf(ExecutionBindingService.AuthorizationException.class);
        assertThatThrownBy(() -> tools.create(token, spec())).isInstanceOf(ExecutionBindingService.AuthorizationException.class);
        assertThatThrownBy(() -> tools.get(token)).isInstanceOf(ExecutionBindingService.AuthorizationException.class);
        assertNoDraft();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"plaintext", "ciphertext", "digest", "project", "request"})
    void snapshotMustBeEncryptedIntactAndMatchScope(String change) throws Exception {
        switch (change) {
            case "plaintext" -> jdbc.update("update assistant_generations set snapshot_ciphertext=? where id=?", snapshot, generation);
            case "ciphertext" -> jdbc.update("update assistant_generations set snapshot_ciphertext='enc:v1:bad.secret' where id=?", generation);
            case "digest" -> jdbc.update("update assistant_generations set payload_digest=? where id=?", "0".repeat(64), generation);
            default -> {
                var body = (tools.jackson.databind.node.ObjectNode) json.readTree(snapshot);
                if (change.equals("project")) body.put("projectId", project + 1); else body.put("requestId", "other");
                String changed = json.writeValueAsString(body);
                jdbc.update("update assistant_generations set snapshot_ciphertext=?,payload_digest=? where id=?", cipher.encrypt(changed), hash(changed), generation);
            }
        }
        assertConflict(() -> tools.context(token));
        assertConflict(() -> tools.create(token, spec()));
        assertConflict(() -> tools.get(token));
        assertNoDraft();
    }

    @Test void transactionRollsBackDraftRootWhenProvenanceInsertFailsAndErrorIsOpaque() {
        String constraint = "fail_" + generation.replace("-", "");
        jdbc.execute("alter table assistant_generation_drafts add constraint " + constraint + " check (generation_id <> '" + generation + "')");
        try {
            assertThatThrownBy(() -> tools.create(token, spec())).isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                ex -> { assertThat(ex.getStatusCode().value()).isEqualTo(503); assertThat(ex.getCause()).isNull();
                    assertThat(ex.getMessage()).doesNotContain("Immutable", "Visible content", token, "insert into"); });
            assertNoDraft();
        } finally { jdbc.execute("alter table assistant_generation_drafts drop constraint " + constraint); }
        assertThat(tools.create(token, spec()).state()).isEqualTo("DRAFT");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"binding", "lease", "source", "requirement", "inputs", "spec"})
    void storedDraftCannotBeReadOrReplayedWithMismatchedProvenance(String change) {
        var draft = tools.create(token, spec());
        switch (change) {
            case "binding" -> jdbc.update("update assistant_generation_drafts set binding_id='wrong' where generation_id=?", generation);
            case "lease" -> jdbc.update("update assistant_generation_drafts set lease_generation=999 where generation_id=?", generation);
            case "source" -> jdbc.update("update assistant_generation_drafts set snapshot_digest=? where generation_id=?", "0".repeat(64), generation);
            case "requirement" -> jdbc.update("update assistant_draft set requirement_digest='wrong' where draft_id=?", draft.draftId());
            case "inputs" -> jdbc.update("update assistant_draft set inputs_json=? where draft_id=?", cipher.encrypt("{\"password\":\"secret\"}"), draft.draftId());
            case "spec" -> jdbc.update("update assistant_draft set draft_json=? where draft_id=?", spec().replace("Visible content", "Tampered"), draft.draftId());
        }
        assertConflict(() -> tools.get(token));
        assertConflict(() -> tools.create(token, spec()));
    }

    @Test void samePayloadAcrossBindingRetryReturnsOriginalAndConfirmedDraftNeverOverwritten() {
        var first = tools.create(token, spec());
        assertThat(jdbc.update("update assistant_draft set state='CONFIRMED',confirmed_by=?,confirmed_at=? where draft_id=?", actor, Instant.now(), first.draftId())).isEqualTo(1);
        execution.revoke(binding.bindingId());
        execution.confirmUnitCleaned(execution.requestCleanup(binding.bindingId(), binding.leaseGeneration()));
        var next = execution.allocate(new ExecutionBindingService.AllocationRequest(generation, unit, Duration.ofMinutes(5)));
        String nextToken = execution.issueGrant(new ExecutionBindingService.IssueRequest(next.bindingId(), actor)).rawToken().reveal();
        var replay = tools.create(nextToken, spec());
        assertThat(replay.draftId()).isEqualTo(first.draftId());
        assertThat(replay.state()).isEqualTo("CONFIRMED");
        assertThat(replay.confirmedBy()).isEqualTo(actor);
        assertThat(tools.get(nextToken)).isEqualTo(replay);
        assertConflict(() -> tools.create(nextToken, spec().replace("Visible content", "Changed")));
        assertThat(jdbc.queryForObject("select binding_id from assistant_generation_drafts where generation_id=?", String.class, generation)).isEqualTo(binding.bindingId());
        assertThat(jdbc.queryForObject("select count(*) from assistant_draft where project_id=?", Integer.class, project)).isEqualTo(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void concurrentCreateHasExactlyOneDraft(boolean samePayload) throws Exception {
        var latch = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { latch.await(); return tools.create(token, spec()); });
            var b = pool.submit(() -> { latch.await(); return tools.create(token, samePayload ? spec() : spec().replace("Visible content", "Changed")); });
            latch.countDown();
            var successes = new ArrayList<com.haitlify.aitest.platform.assistant.draft.AssistantDraftService.DraftView>();
            int conflicts = 0;
            for (var future : List.of(a, b)) {
                try { successes.add(future.get(10, java.util.concurrent.TimeUnit.SECONDS)); }
                catch (java.util.concurrent.ExecutionException ex) {
                    assertThat(ex.getCause()).isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                        status -> assertThat(status.getStatusCode().value()).isEqualTo(409));
                    conflicts++;
                }
            }
            assertThat(successes).hasSize(samePayload ? 2 : 1);
            assertThat(conflicts).isEqualTo(samePayload ? 0 : 1);
            assertThat(successes.stream().map(d -> d.draftId()).distinct().count()).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("select count(*) from assistant_draft where project_id=?", Integer.class, project)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from assistant_generation_drafts where generation_id=?", Integer.class, generation)).isEqualTo(1);
    }

    @Test void relativeGotoAndExplicitDefaultPortRemainOnAuthorizedOrigin() {
        var body = (tools.jackson.databind.node.ObjectNode) json.readTree(spec().replace("example.com/app", "example.com:443/other"));
        ((tools.jackson.databind.node.ArrayNode) body.path("steps")).addObject().put("id", "nav").put("action", "goto").put("target", "/next");
        assertThat(tools.create(token, json.writeValueAsString(body)).spec().steps()).hasSize(2);
    }

    private void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
            ex -> { assertThat(ex.getStatusCode().value()).isEqualTo(409); assertThat(ex.getCause()).isNull();
                assertThat(ex.getMessage()).doesNotContain(token, "Immutable requirement"); });
    }
    private void assertNoDraft() {
        assertThat(jdbc.queryForObject("select count(*) from assistant_draft where project_id=?", Integer.class, project)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from assistant_generation_drafts where generation_id=?", Integer.class, generation)).isZero();
    }

    static String hash(String text) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
