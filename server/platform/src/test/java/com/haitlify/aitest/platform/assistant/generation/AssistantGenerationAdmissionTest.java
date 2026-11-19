package com.haitlify.aitest.platform.assistant.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.haitlify.aitest.platform.security.SecretCipher;

@SpringBootTest(properties = {"app.security.enabled=true",
    "spring.datasource.url=jdbc:h2:mem:generation-admission;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
    "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE="})
@AutoConfigureMockMvc(printOnlyOnFailure = false, print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@ActiveProfiles("test")
class AssistantGenerationAdmissionTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired SecretCipher cipher;
    String owner;
    String other;
    long actor;
    long tenant;
    long project;
    MockHttpSession session;

    @BeforeEach
    void seed() throws Exception {
        String suffix = UUID.randomUUID().toString();
        owner = "gen-" + suffix;
        other = "other-" + suffix;
        Instant now = Instant.now();
        for (String name : new String[]{owner, other}) {
            jdbc.update("insert into platform_users(username,password_hash,role,enabled,created_at,updated_at) values (?,?, 'EDITOR',true,?,?)",
                name, passwords.encode("generation-test-only"), now, now);
        }
        actor = jdbc.queryForObject("select id from platform_users where username=?", Long.class, owner);
        jdbc.update("insert into tenants(code,name,active,created_at,updated_at) values (?, 'Generation tenant',true,?,?)", suffix, now, now);
        tenant = jdbc.queryForObject("select id from tenants where code=?", Long.class, suffix);
        jdbc.update("insert into projects(code,name,description,active,tenant_id,created_at,updated_at) values (?, 'Generation project','',true,?,?,?)", suffix, tenant, now, now);
        project = jdbc.queryForObject("select id from projects where code=?", Long.class, suffix);
        for (String name : new String[]{owner, other}) {
            jdbc.update("insert into tenant_members(tenant_id,username,created_at) values (?,?,?)", tenant, name, now);
            jdbc.update("insert into project_members(project_id,username,created_at) values (?,?,?)", project, name, now);
        }
        session = (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("username", owner, "password", "generation-test-only"))))
            .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        assertThat(session).isNotNull();
    }

    Map<String, Object> payload() {
        return new java.util.LinkedHashMap<>(Map.of("requestId", "stable-request-1", "projectId", project,
            "requirementRef", "requirement:checkout", "requirementText", "Sensitive requirement: verify checkout 中文",
            "targetUrl", "https://example.com/checkout", "targetRevision", "revision-1"));
    }

    ResultActions submit(Map<String, Object> payload) throws Exception {
        return mvc.perform(post("/api/assistant/generations").session(session).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload)));
    }

    JsonNode accepted() throws Exception {
        return json.readTree(submit(payload()).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
    }

    void unknownTenantFixture() {
        // Simulate historical corrupt binding without weakening production migrations.
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try { jdbc.update("update projects set tenant_id=999999999 where id=?", project); }
        finally { jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE"); }
    }

    @Test
    void loginAndCsrfAreRequiredAndViewerCannotSubmit() throws Exception {
        String content = json.writeValueAsString(payload());
        mvc.perform(post("/api/assistant/generations").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(content))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/assistant/generations").session(session).contentType(MediaType.APPLICATION_JSON).content(content))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/assistant/generations").with(user(other).roles("VIEWER")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(content)).andExpect(status().isForbidden());
        mvc.perform(get("/api/assistant/generations/unknown")).andExpect(status().isUnauthorized());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"disabled-user", "viewer", "unknown-user", "project-member", "tenant-member", "project-disabled", "tenant-disabled", "tenant-null", "tenant-unknown", "tenant-moved"})
    void staleSessionsCannotPollOrReplayAfterAuthorizationChanges(String change) throws Exception {
        String id = accepted().path("id").asText();
        switch (change) {
            case "disabled-user" -> jdbc.update("update platform_users set enabled=false where id=?", actor);
            case "viewer" -> jdbc.update("update platform_users set role='VIEWER' where id=?", actor);
            case "unknown-user" -> {
                jdbc.update("delete from project_members where username=?", owner);
                jdbc.update("delete from tenant_members where username=?", owner);
                jdbc.update("delete from platform_users where id=?", actor);
            }
            case "project-member" -> jdbc.update("delete from project_members where project_id=? and username=?", project, owner);
            case "tenant-member" -> jdbc.update("delete from tenant_members where tenant_id=? and username=?", tenant, owner);
            case "project-disabled" -> jdbc.update("update projects set active=false where id=?", project);
            case "tenant-disabled" -> jdbc.update("update tenants set active=false where id=?", tenant);
            case "tenant-null" -> jdbc.update("update projects set tenant_id=null where id=?", project);
            case "tenant-unknown" -> unknownTenantFixture();
            case "tenant-moved" -> {
                Instant now = Instant.now();
                String code = UUID.randomUUID().toString();
                jdbc.update("insert into tenants(code,name,active,created_at,updated_at) values (?,'Moved',true,?,?)", code, now, now);
                long moved = jdbc.queryForObject("select id from tenants where code=?", Long.class, code);
                jdbc.update("insert into tenant_members(tenant_id,username,created_at) values (?,?,?)", moved, owner, now);
                jdbc.update("update projects set tenant_id=? where id=?", moved, project);
            }
            default -> throw new AssertionError(change);
        }
        int expected = change.equals("disabled-user") || change.equals("unknown-user") ? 401 : 403;
        mvc.perform(get("/api/assistant/generations/" + id).session(session)).andExpect(status().is(expected));
        submit(payload()).andExpect(status().is(change.equals("tenant-moved") ? 409 : expected));
        assertThat(jdbc.queryForObject("select count(*) from assistant_generations where id=?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void crossProjectAndUnknownTenantDeniedEvenWithStaleAdminAuthority() throws Exception {
        jdbc.update("delete from project_members where project_id=? and username=?", project, owner);
        submit(payload()).andExpect(status().isForbidden());
        mvc.perform(post("/api/assistant/generations").with(user(owner).roles("ADMIN")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload()))).andExpect(status().isForbidden());
        jdbc.update("update platform_users set role='ADMIN' where id=?", actor);
        accepted();
        unknownTenantFixture();
        submit(payload()).andExpect(status().isForbidden());
    }

    @Test
    void differentActorsHaveIndependentIdempotencyScopes() throws Exception {
        String first = accepted().path("id").asText();
        String second = json.readTree(mvc.perform(post("/api/assistant/generations").with(user(other).roles("EDITOR")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload())))
            .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).path("id").asText();
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void sameRequestIdInDifferentAuthorizedProjectCreatesIndependentAdmission() throws Exception {
        String first = accepted().path("id").asText();
        String code = UUID.randomUUID().toString();
        Instant now = Instant.now();
        jdbc.update("insert into projects(code,name,description,active,tenant_id,created_at,updated_at) values (?,'Second','',true,?,?,?)", code, tenant, now, now);
        long secondProject = jdbc.queryForObject("select id from projects where code=?", Long.class, code);
        var secondPayload = payload(); secondPayload.put("projectId", secondProject);
        submit(secondPayload).andExpect(status().isForbidden());
        jdbc.update("insert into project_members(project_id,username,created_at) values (?,?,?)", secondProject, owner, now);
        String second = json.readTree(submit(secondPayload).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).path("id").asText();
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void concurrentDifferentPayloadsProduceOneWinnerAndOneConflict() throws Exception {
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (String text : new String[]{"first payload", "second payload"}) tasks.add(executor.submit(() -> {
                start.await(10, java.util.concurrent.TimeUnit.SECONDS);
                var changed = payload(); changed.put("requirementText", text);
                return submit(changed).andReturn().getResponse().getStatus();
            }));
            start.countDown();
            var statuses = new java.util.ArrayList<Integer>();
            for (var task : tasks) statuses.add(task.get(15, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(statuses).containsExactlyInAnyOrder(202, 409);
        }
        assertThat(jdbc.queryForObject("select count(*) from assistant_generations where actor_id=? and project_id=?", Integer.class, actor, project)).isEqualTo(1);
    }

    @Test
    void concurrentRetriesConvergeOnOneCommittedSnapshot() throws Exception {
        var start = new java.util.concurrent.CountDownLatch(1);
        var ready = new java.util.concurrent.CountDownLatch(8);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 8; i++) tasks.add(executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                return accepted().path("id").asText();
            }));
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var ids = new java.util.HashSet<String>();
            for (var task : tasks) ids.add(task.get(15, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);
        }
        assertThat(jdbc.queryForObject("select count(*) from assistant_generations where actor_id=? and project_id=?", Integer.class, actor, project)).isEqualTo(1);
    }

    @Test
    void deletingHistoricalUserAndProjectDoesNotDeleteAuditSnapshot() throws Exception {
        String id = accepted().path("id").asText();
        jdbc.update("delete from project_members where project_id=?", project);
        jdbc.update("delete from projects where id=?", project);
        jdbc.update("delete from tenant_members where username=?", owner);
        jdbc.update("delete from platform_users where id=?", actor);
        var row = jdbc.queryForMap("select actor_id,project_id from assistant_generations where id=?", id);
        assertThat(((Number) row.get("actor_id")).longValue()).isEqualTo(actor);
        assertThat(((Number) row.get("project_id")).longValue()).isEqualTo(project);
        mvc.perform(get("/api/assistant/generations/" + id).session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void admissionRejectsNonHttpUserinfoAndPrivateLiteralTargetsWithoutNetworkCalls() throws Exception {
        for (String url : new String[]{"file:///etc/passwd", "https://user:pass@example.com/", "https://example.com\\\\@127.0.0.1/",
            "http://127.0.0.1", "http://10.0.0.2", "http://192.168.1.1", "http://172.16.0.1", "http://169.254.169.254",
            "http://localhost", "http://a.internal", "http://2130706433", "http://127.1", "http://0x7f.0.0.1", "http://0177.0.0.1",
            "http://[::1]", "http://[fd12::1]", "http://[::ffff:127.0.0.1]", "http://100.64.0.1", "http://224.0.0.1",
            "https://example.com:0/", "https://example.com:65536/"}) {
            var changed = payload(); changed.put("targetUrl", url);
            submit(changed).andExpect(status().isBadRequest());
        }
    }

    @Test
    void ownerCanPollButEvenOtherProjectMemberCannotRead() throws Exception {
        JsonNode first = accepted();
        String uri = "/api/assistant/generations/" + first.path("id").asText();
        assertThat(json.readTree(mvc.perform(get(uri).session(session)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString())).isEqualTo(first);
        mvc.perform(get(uri).with(user(other).roles("EDITOR"))).andExpect(status().isNotFound());
        mvc.perform(get(uri).with(user(other).roles("ADMIN"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/assistant/generations/" + UUID.randomUUID()).session(session)).andExpect(status().isNotFound());
    }

    @Test
    void validatesRequiredTypesAndBoundsWithoutCoercion() throws Exception {
        for (String field : payload().keySet()) {
            var changed = payload(); changed.remove(field);
            submit(changed).andExpect(status().isBadRequest());
            changed = payload(); changed.put(field, null);
            submit(changed).andExpect(status().isBadRequest());
            changed = payload(); changed.put(field, java.util.List.of("bad"));
            submit(changed).andExpect(status().isBadRequest());
        }
        for (Object value : new Object[]{"1", 1.5, -1, 0}) {
            var changed = payload(); changed.put("projectId", value);
            submit(changed).andExpect(status().isBadRequest());
        }
        for (String field : new String[]{"requestId", "requirementRef", "requirementText", "targetUrl", "targetRevision"}) {
            var changed = payload(); changed.put(field, " ");
            submit(changed).andExpect(status().isBadRequest());
            changed.put(field, "a".repeat(70000));
            submit(changed).andExpect(status().isBadRequest());
        }
    }

    @Test
    void rejectsAllUnknownFieldsRatherThanTrustingCallerIdentityOrSpec() throws Exception {
        for (String field : new String[]{"actorId", "tenantId", "spec", "unexpected"}) {
            var forged = payload();
            forged.put(field, "forged");
            submit(forged).andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("select count(*) from assistant_generations where actor_id=?", Integer.class, actor)).isZero();
    }

    @Test
    void retriesReturnOriginalSnapshotAndChangedPayloadConflicts() throws Exception {
        JsonNode first = accepted();
        var stored = jdbc.queryForMap("select * from assistant_generations where id=?", first.path("id").asText());
        assertThat(accepted()).isEqualTo(first);
        for (String field : new String[]{"requirementRef", "requirementText", "targetUrl", "targetRevision"}) {
            var changed = payload();
            changed.put(field, field.equals("targetUrl") ? "https://example.com/other" : "changed");
            submit(changed).andExpect(status().isConflict());
        }
        assertThat(jdbc.queryForMap("select * from assistant_generations where id=?", first.path("id").asText())).isEqualTo(stored);
        assertThat(jdbc.queryForObject("select count(*) from assistant_generations where actor_id=? and project_id=?", Integer.class, actor, project)).isEqualTo(1);
    }

    @Test
    void loggedInAdmissionPersistsEncryptedSnapshotWithoutPretendingModelSuccess() throws Exception {
        JsonNode response = accepted();
        assertThat(response.path("state").asText()).isEqualTo("WAITING_WORKER");
        assertThat(response.path("id").asText()).isNotBlank();
        var row = jdbc.queryForMap("select * from assistant_generations where id=?", response.path("id").asText());
        assertThat(((Number) row.get("actor_id")).longValue()).isEqualTo(actor);
        assertThat(((Number) row.get("tenant_id")).longValue()).isEqualTo(tenant);
        String encrypted = row.get("snapshot_ciphertext").toString();
        assertThat(encrypted).startsWith("enc:v1:").doesNotContain("Sensitive requirement");
        JsonNode snapshot = json.readTree(cipher.decrypt(encrypted));
        for (var field : payload().entrySet()) {
            assertThat(snapshot.path(field.getKey()).asText()).isEqualTo(field.getValue().toString());
        }
        String expectedDigest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(cipher.decrypt(encrypted).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(row.get("payload_digest").toString()).isEqualTo(expectedDigest);
        assertThat(response.has("requirementText")).isFalse();
        assertThat(response.has("snapshotCiphertext")).isFalse();
    }
}
