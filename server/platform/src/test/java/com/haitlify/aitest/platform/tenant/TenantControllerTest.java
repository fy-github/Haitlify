package com.haitlify.aitest.platform.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.security.enabled=true")
class TenantControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TenantAccessService tenantAccessService;

    @BeforeEach
    void seedUsers() {
        jdbcTemplate.update("delete from tenant_members where username in ('tenant-admin','tenant-editor','tenant-outsider')");
        jdbcTemplate.update("delete from platform_users where username in ('tenant-admin','tenant-editor','tenant-outsider')");
        jdbcTemplate.update("delete from tenants where code like 'api-test-%'");
        for (String username : new String[] {"tenant-admin", "tenant-editor", "tenant-outsider"}) {
            String role = username.equals("tenant-admin") ? "ADMIN" : username.equals("tenant-editor") ? "EDITOR" : "VIEWER";
            jdbcTemplate.update(
                "insert into platform_users (username, password_hash, role, created_at, updated_at)"
                    + " values (?, ?, ?, ?, ?)",
                username, passwordEncoder.encode("password-123"), role,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        }
    }

    @Test
    void adminCanCreateTenantAddAndRemoveMember() throws Exception {
        String body = "{\"code\":\"api-test-a\",\"name\":\"接口测试租户\"}";
        String response = mockMvc.perform(post("/api/tenants").with(user("tenant-admin").roles("ADMIN"))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.code").value("api-test-a"))
            .andExpect(jsonPath("$.active").value(true))
            .andExpect(jsonPath("$.memberCount").value(0))
            .andReturn().getResponse().getContentAsString();
        long tenantId = Long.parseLong(response.replaceAll(".*\"id\":(\\d+).*", "$1"));

        mockMvc.perform(post("/api/tenants/" + tenantId + "/members").with(user("tenant-admin").roles("ADMIN"))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"tenant-editor\"}"))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/api/tenants/" + tenantId + "/members").with(user("tenant-admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].username").value("tenant-editor"))
            .andExpect(jsonPath("$[0].role").value("EDITOR"));

        mockMvc.perform(get("/api/tenants").with(user("tenant-admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.code=='api-test-a')].memberCount").value(1));

        // PUT 也必须回填真实成员数（曾返回 null）。
        mockMvc.perform(put("/api/tenants/" + tenantId).with(user("tenant-admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"接口测试租户-改名\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("接口测试租户-改名"))
            .andExpect(jsonPath("$.memberCount").value(1));

        mockMvc.perform(delete("/api/tenants/" + tenantId + "/members/tenant-editor")
                .with(user("tenant-admin").roles("ADMIN")).with(csrf()))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/tenants/" + tenantId + "/members").with(user("tenant-admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void nonAdminCannotTouchTenantAdminSurface() throws Exception {
        mockMvc.perform(get("/api/tenants").with(user("tenant-editor").roles("EDITOR")))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/tenants").with(user("tenant-editor").roles("EDITOR")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"api-test-b\",\"name\":\"x\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void rejectsBadCodeDuplicateAndUnknownUser() throws Exception {
        mockMvc.perform(post("/api/tenants").with(user("tenant-admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"Bad Code!\",\"name\":\"x\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").exists());

        // 大写不能被静默改写成小写：文案说「只能小写」就必须拒绝。
        mockMvc.perform(post("/api/tenants").with(user("tenant-admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"API-UPPER\",\"name\":\"x\"}"))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/tenants").with(user("tenant-admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"api-test-dup\",\"name\":\"x\"}"))
            .andExpect(status().isCreated());
        mockMvc.perform(post("/api/tenants").with(user("tenant-admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"api-test-dup\",\"name\":\"y\"}"))
            .andExpect(status().isConflict());

        Long dupId = jdbcTemplate.queryForObject("select id from tenants where code = 'api-test-dup'", Long.class);
        mockMvc.perform(post("/api/tenants/" + dupId + "/members").with(user("tenant-admin").roles("ADMIN"))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"nobody-here\"}"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/tenants/99999999/members").with(user("tenant-admin").roles("ADMIN"))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"tenant-editor\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void defaultTenantCannotBeDeactivated() throws Exception {
        Long defaultId = jdbcTemplate.queryForObject("select id from tenants where code = 'default'", Long.class);
        mockMvc.perform(put("/api/tenants/" + defaultId).with(user("tenant-admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
            .andExpect(status().isBadRequest());
        assertThat(jdbcTemplate.queryForObject("select active from tenants where id = ?", Boolean.class, defaultId))
            .isTrue();
    }

    @Test
    void deactivatedTenantIsRejectedByAccessService() throws Exception {
        mockMvc.perform(post("/api/tenants").with(user("tenant-admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"api-test-off\",\"name\":\"停用\"}"))
            .andExpect(status().isCreated());
        Long id = jdbcTemplate.queryForObject("select id from tenants where code = 'api-test-off'", Long.class);
        mockMvc.perform(put("/api/tenants/" + id).with(user("tenant-admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active").value(false));
        assertThat(jdbcTemplate.queryForObject("select active from tenants where id = ?", Boolean.class, id)).isFalse();
        // 名称与断言要对得上：确实过一遍访问控制，而不是只看数据库列。
        assertThatThrownBy(() -> tenantAccessService.requireTenant(id))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("已停用");
    }
}
