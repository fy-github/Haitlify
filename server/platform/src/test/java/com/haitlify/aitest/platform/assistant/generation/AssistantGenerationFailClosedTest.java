package com.haitlify.aitest.platform.assistant.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
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
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"app.security.enabled=false", "APP_SECRET_KEY=",
    "spring.datasource.url=jdbc:h2:mem:generation-failclosed;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class AssistantGenerationFailClosedTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    String username;
    String body;
    long actor;

    @BeforeEach
    void seed() {
        username = "gen-" + UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("insert into platform_users(username,password_hash,role,enabled,created_at,updated_at) values (?,'unused','ADMIN',true,?,?)", username, now, now);
        actor = jdbc.queryForObject("select id from platform_users where username=?", Long.class, username);
        jdbc.update("insert into tenants(code,name,active,created_at,updated_at) values (?,'Generation',true,?,?)", username, now, now);
        long tenant = jdbc.queryForObject("select id from tenants where code=?", Long.class, username);
        jdbc.update("insert into projects(code,name,description,active,tenant_id,created_at,updated_at) values (?,'Generation','',true,?,?,?)", username, tenant, now, now);
        long project = jdbc.queryForObject("select id from projects where code=?", Long.class, username);
        body = json.writeValueAsString(Map.of("requestId", "one", "projectId", project, "requirementRef", "requirement:one",
            "requirementText", "sensitive text", "targetUrl", "https://example.com", "targetRevision", "rev-1"));
    }

    @Test
    void disabledSecurityNeverSuppliesIdentity() throws Exception {
        mvc.perform(post("/api/assistant/generations").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/assistant/generations").with(user("nonexistent").roles("ADMIN"))
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        String forged = body.substring(0, body.length() - 1) + ",\"actorId\":" + actor + "}";
        mvc.perform(post("/api/assistant/generations").contentType(MediaType.APPLICATION_JSON).content(forged)).andExpect(status().isUnauthorized());
    }

    @Test
    void missingEncryptionKeyFailsClosedWithoutPersistingPlaintext() throws Exception {
        mvc.perform(post("/api/assistant/generations").with(user(username).roles("ADMIN"))
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isServiceUnavailable());
        assertThat(jdbc.queryForObject("select count(*) from assistant_generations where actor_id=?", Integer.class, actor)).isZero();
    }

    @Test
    void disabledSecurityStillRequiresLiveProjectTenantAndMembership() throws Exception {
        long project = json.readTree(body).path("projectId").asLong();
        jdbc.update("update platform_users set role='EDITOR' where id=?", actor);
        mvc.perform(post("/api/assistant/generations").with(user(username).roles("ADMIN"))
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        jdbc.update("update platform_users set role='ADMIN' where id=?", actor);
        jdbc.update("update projects set active=false where id=?", project);
        mvc.perform(post("/api/assistant/generations").with(user(username).roles("ADMIN"))
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        jdbc.update("update projects set active=true, tenant_id=null where id=?", project);
        mvc.perform(post("/api/assistant/generations").with(user(username).roles("ADMIN"))
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
    }

    @Test
    void disabledSecurityStillChecksDatabaseRoleAndEnabledFlag() throws Exception {
        jdbc.update("update platform_users set role='VIEWER' where id=?", actor);
        mvc.perform(post("/api/assistant/generations").with(user(username).roles("ADMIN"))
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        jdbc.update("update platform_users set role='ADMIN', enabled=false where id=?", actor);
        mvc.perform(post("/api/assistant/generations").with(user(username).roles("ADMIN"))
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
    }
}
