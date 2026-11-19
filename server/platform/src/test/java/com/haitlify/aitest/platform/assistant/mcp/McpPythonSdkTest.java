package com.haitlify.aitest.platform.assistant.mcp;

import static org.assertj.core.api.Assertions.*;
import com.haitlify.aitest.platform.assistant.execution.ExecutionBindingService;
import com.haitlify.aitest.platform.security.SecretCipher;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Opt-in real SDK gate. Python path is an executable only; all grants travel over stdin. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "app.assistant.mcp.enabled=true", "app.security.enabled=true", "server.address=127.0.0.1",
    "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
    "app.jira.enabled=false", "app.web-automation.runner.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:mcp-python-sdk;MODE=MySQL;DB_CLOSE_DELAY=0;DATABASE_TO_LOWER=TRUE"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfSystemProperty(named = "mcp.sdk.python", matches = ".+")
class McpPythonSdkTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ExecutionBindingService execution;
    @Autowired SecretCipher cipher;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @LocalServerPort int port;
    record Fixture(long actor, long tenant, long project, String username, String generation,
                   String snapshotDigest, ExecutionBindingService.Binding binding, String token) {}

    Fixture seed() throws Exception { return seed(false); }

    Fixture seed(boolean failAfterGrant) throws Exception {
        Instant now = Instant.now();
        String suffix = UUID.randomUUID().toString();
        ExecutionBindingService.Binding binding = null;
        try {
            jdbc.update("insert into platform_users(username,password_hash,role,enabled,created_at,updated_at) values (?,?,'EDITOR',true,?,?)",
                suffix, passwords.encode("SDK-fixture-password-1!"), now, now);
            long actor = jdbc.queryForObject("select id from platform_users where username=?", Long.class, suffix);
            jdbc.update("insert into tenants(code,name,active,created_at,updated_at) values (?,'SDK',true,?,?)", suffix, now, now);
            long tenant = jdbc.queryForObject("select id from tenants where code=?", Long.class, suffix);
            jdbc.update("insert into projects(code,name,description,active,tenant_id,created_at,updated_at) values (?,'SDK','',true,?,?,?)", suffix, tenant, now, now);
            long project = jdbc.queryForObject("select id from projects where code=?", Long.class, suffix);
            jdbc.update("insert into tenant_members(tenant_id,username,created_at) values (?,?,?)", tenant, suffix, now);
            jdbc.update("insert into project_members(project_id,username,created_at) values (?,?,?)", project, suffix, now);
            String generation = UUID.randomUUID().toString();
            String snapshot = json.writeValueAsString(new TreeMap<>(Map.of("requestId", suffix, "projectId", project,
                "requirementRef", "requirement:immutable", "requirementText", "Immutable requirement",
                "targetUrl", "https://example.com/app", "targetRevision", "v1")));
            String digest = hash(snapshot);
            jdbc.update("insert into assistant_generations(id,actor_id,project_id,tenant_id,request_id,payload_digest,snapshot_ciphertext,state,created_at) values (?,?,?,?,?,?,?,'WAITING_WORKER',?)",
                generation, actor, project, tenant, suffix, digest, cipher.encrypt(snapshot), now);
            String unit = "sdk-" + suffix;
            execution.confirmUnitCleaned(execution.registerUnit(new ExecutionBindingService.WorkerUnit(unit, "sha256:" + "a".repeat(64), "sdk-fixture")));
            binding = execution.allocate(new ExecutionBindingService.AllocationRequest(generation, unit, Duration.ofMinutes(5)));
            String token = execution.issueGrant(new ExecutionBindingService.IssueRequest(binding.bindingId(), actor)).rawToken().reveal();
            if (failAfterGrant) throw new IllegalStateException("intentional second fixture seed failure");
            return new Fixture(actor, tenant, project, suffix, generation, digest, binding, token);
        } catch (Throwable failure) {
            if (binding != null) {
                Throwable cleanup = cleanupFixture(new Fixture(0, 0, 0, suffix, "", "", binding, ""));
                if (cleanup != null) failure.addSuppressed(cleanup);
            }
            throwUnchecked(failure);
            return null;
        }
    }

    @Test @Timeout(100)
    void realPythonSdkDraftChainScopeAndRevocation() throws Exception {
        Fixture own = null, other = null;
        Path scratch = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), "mcp-sdk-client-");
        McpPythonProcess child = null;
        Throwable failure = null;
        try {
            own = seed();
            other = seed();
            assertThat(execution.inspectAuthorizedGrant(own.token(), "generation_context").scope()).isEqualTo(
                new ExecutionBindingService.Scope(own.actor(), own.tenant(), own.project(), own.generation(), own.binding().bindingId(), own.binding().leaseGeneration()));
            Path script = Path.of("src/test/resources/assistant/mcp_sdk_probe.py").toAbsolutePath();
            ProcessBuilder pb = new ProcessBuilder(System.getProperty("mcp.sdk.python"), "-I", script.toString());
            pb.directory(scratch.toFile());
            pb.environment().clear();
            pb.environment().putAll(Map.of("HOME", scratch.toString(), "TMPDIR", scratch.toString(), "PATH", "/usr/bin:/bin"));
            // Python emits only bounded structured receipts. Never forward stderr containing request internals.
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            child = McpPythonProcess.start(pb);
            child.writer().write(json.writeValueAsString(Map.of("port", port, "token", own.token(), "otherToken", other.token(), "username", own.username())));
            child.writer().newLine(); child.writer().flush();
            JsonNode receipt = json.readTree(child.readLine(Duration.ofSeconds(40)));
                assertThat(receipt.path("stage").asString()).as("SDK first receipt: %s", receipt).isEqualTo("created");
                JsonNode draft = receipt.path("draft");
                String draftId = draft.path("draftId").asString();
                var row = jdbc.queryForMap("select * from assistant_draft where draft_id=?", draftId);
                assertThat(row.get("created_by")).isEqualTo(own.actor());
                assertThat(row.get("tenant_id")).isEqualTo(own.tenant());
                assertThat(row.get("project_id")).isEqualTo(own.project());
                assertThat(row.get("root_draft_id")).isEqualTo(draftId);
                assertThat(row.get("state")).isEqualTo("DRAFT");
                assertThat(row.get("target_ref")).isEqualTo("project:" + own.project());
                assertThat(row.get("target_revision")).isEqualTo("v1");
                assertThat(json.readValue(row.get("draft_json").toString(), com.haitlify.aitest.platform.assistant.compile.AssistantScriptCompiler.CaseSpec.class))
                    .isEqualTo(json.treeToValue(draft.path("spec"), com.haitlify.aitest.platform.assistant.compile.AssistantScriptCompiler.CaseSpec.class));
                var source = jdbc.queryForMap("select * from assistant_generation_drafts where generation_id=?", own.generation());
                assertThat(source.get("draft_id")).isEqualTo(draftId);
                assertThat(source.get("binding_id")).isEqualTo(own.binding().bindingId());
                assertThat(((Number) source.get("lease_generation")).longValue()).isEqualTo(own.binding().leaseGeneration());
                assertThat(source.get("snapshot_digest")).isEqualTo(own.snapshotDigest());
                assertThat(source.get("spec_digest")).isEqualTo(hash(row.get("draft_json").toString()));
                assertThat(source.get("provenance_digest")).isEqualTo(hash(json.writeValueAsString(List.of(
                    own.generation(), draftId, own.binding().bindingId(), own.binding().leaseGeneration(), own.snapshotDigest(), source.get("spec_digest")))));
                assertCounts(own, other);
                execution.revoke(own.binding().bindingId());
                assertThat(jdbc.queryForObject("select revoked from assistant_execution_grants where binding_id=?", Boolean.class, own.binding().bindingId())).isTrue();
                assertThat(jdbc.queryForObject("select state from assistant_execution_bindings where id=?", String.class, own.binding().bindingId())).isEqualTo("REVOKED");
                child.writer().write("revoked\n"); child.writer().flush();
                JsonNode complete = json.readTree(child.readLine(Duration.ofSeconds(40)));
                assertThat(complete.path("stage").asString()).as("SDK completion: %s", complete).isEqualTo("complete");
                assertThat(complete.path("negative_checks").asInt()).isEqualTo(7);
                assertThat(complete.path("network_blocked").asInt()).isZero();
                assertThat(child.waitFor(Duration.ofSeconds(10))).isTrue();
                assertThat(child.exitValue()).isZero();
                assertCounts(own, other);
                assertThat(jdbc.queryForMap("select * from assistant_draft where draft_id=?", draftId)).isEqualTo(row);
                assertThat(jdbc.queryForMap("select * from assistant_generation_drafts where generation_id=?", own.generation())).isEqualTo(source);
                System.out.println("REAL_MCP_SDK_RECEIPT " + complete);
        } catch (Throwable thrown) {
            failure = thrown;
            throwUnchecked(thrown);
        } finally {
            Throwable cleanup = null;
            if (child != null) cleanup = attempt(cleanup, child::close);
            Fixture finalOwn = own, finalOther = other;
            cleanup = attempt(cleanup, () -> cleanupFixtureOrThrow(finalOwn));
            cleanup = attempt(cleanup, () -> cleanupFixtureOrThrow(finalOther));
            if (child != null && !child.cleanupComplete()) cleanup = attempt(cleanup, () -> {
                throw new IllegalStateException("refusing scratch deletion before owned Python cleanup completes");
            });
            else cleanup = attempt(cleanup, () -> deleteTree(scratch));
            if (failure != null && cleanup != null) failure.addSuppressed(cleanup);
            else if (failure == null && cleanup != null) throwUnchecked(cleanup);
        }
    }

    @Test @Timeout(30)
    void secondFixtureSeedFailureCleansItsGrantWithoutTouchingFirstFixture() throws Exception {
        Fixture first = seed();
        try {
            assertThatThrownBy(() -> seed(true)).isInstanceOf(IllegalStateException.class)
                .hasMessage("intentional second fixture seed failure");
            assertThat(jdbc.queryForObject("select count(*) from assistant_execution_bindings where state='ACTIVE'", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from assistant_execution_grants where revoked=false", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select state from assistant_execution_bindings where id=?", String.class, first.binding().bindingId())).isEqualTo("ACTIVE");
        } finally {
            Throwable cleanup = cleanupFixture(first);
            if (cleanup != null) throwUnchecked(cleanup);
        }
    }

    Throwable cleanupFixture(Fixture fixture) {
        if (fixture == null || fixture.binding() == null) return null;
        Throwable failure = null;
        failure = attempt(failure, () -> execution.revoke(fixture.binding().bindingId()));
        final ExecutionBindingService.CleanupTicket[] ticket = {null};
        failure = attempt(failure, () -> ticket[0] = execution.requestCleanup(fixture.binding().bindingId(), fixture.binding().leaseGeneration()));
        failure = attempt(failure, () -> {
            if (ticket[0] == null) throw new IllegalStateException("cleanup ticket was not issued");
            execution.confirmUnitCleaned(ticket[0]);
        });
        return failure;
    }

    void cleanupFixtureOrThrow(Fixture fixture) {
        Throwable failure = cleanupFixture(fixture);
        if (failure != null) throwUnchecked(failure);
    }

    static Throwable attempt(Throwable primary, ThrowingAction action) {
        try { action.run(); }
        catch (Throwable cleanup) {
            if (primary == null) primary = cleanup;
            else primary.addSuppressed(cleanup);
        }
        return primary;
    }

    static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); }
                catch (IOException e) { throw new UncheckedIOException(e); }
            });
        }
    }

    @FunctionalInterface interface ThrowingAction { void run() throws Throwable; }

    @SuppressWarnings("unchecked")
    static <T extends Throwable> void throwUnchecked(Throwable failure) throws T { throw (T) failure; }
    void assertCounts(Fixture own, Fixture other) {
        assertThat(jdbc.queryForObject("select count(*) from assistant_generation_drafts where generation_id=?", Integer.class, own.generation())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from assistant_draft where project_id=?", Integer.class, own.project())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from assistant_generation_drafts where generation_id=?", Integer.class, other.generation())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from assistant_draft where project_id=?", Integer.class, other.project())).isZero();
    }
    static String hash(String text) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
