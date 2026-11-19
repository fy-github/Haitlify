package com.haitlify.aitest.platform.auth;

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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.haitlify.aitest.platform.support.TestDataReset;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.security.enabled=true")
class UserManagementControllerTest {

    private static final String ADMIN_PASSWORD = "admin-password-123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void seedUsers() {
        TestDataReset.clearPlatformUsers(jdbcTemplate);
        Timestamp now = Timestamp.from(Instant.now());
        seed("admin", "管理员", "ADMIN", ADMIN_PASSWORD, true);
        seed("editor", "编辑者", "EDITOR", "editor-password-123", true);
        seed("viewer", "观察者", "VIEWER", "viewer-password-123", true);
        // V71 之后「建项目」需要租户成员关系：给 editor 加到 default 租户，
        // 否则这条角色用例测的就不是权限而是「没租户」。
        jdbcTemplate.update("delete from tenant_members where username in ('admin', 'editor', 'viewer')");
        jdbcTemplate.update("insert into tenant_members(tenant_id, username, created_at)"
            + " select id, 'editor', ? from tenants where code = 'default'", now);
    }

    private void seed(String username, String displayName, String role, String password, boolean enabled) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
            "insert into platform_users(username, password_hash, display_name, role, enabled, must_change_password, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?)",
            username, passwordEncoder.encode(password), displayName, role, enabled, false, now, now
        );
    }

    private int enabledAdminCount() {
        return jdbcTemplate.queryForObject(
            "select count(*) from platform_users where role = 'ADMIN' and enabled = true", Integer.class);
    }

    // ---- 角色鉴权:VIEWER 只读,EDITOR/ADMIN 可写,用户管理仅 ADMIN ----

    @Test
    void viewerCanReadButCannotMutate() throws Exception {
        mockMvc.perform(get("/api/projects").with(user("viewer").roles("VIEWER")))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/projects")
                .with(user("viewer").roles("VIEWER"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"ROLE_PROBE\",\"name\":\"角色探针\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void editorCanMutateBusinessData() throws Exception {
        mockMvc.perform(post("/api/projects")
                .with(user("editor").roles("EDITOR"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"ROLE_PROBE_EDITOR\",\"name\":\"角色探针\"}"))
            .andExpect(status().isCreated());
    }

    @Test
    void deletingTenantMemberIsNotBlockedByForeignKey() throws Exception {
        // V71 之后把用户加进租户是常规操作；删用户必须先解绑成员关系，否则外键直接 500。
        mockMvc.perform(post("/api/users")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"tenant-member\",\"password\":\"password-123\",\"role\":\"EDITOR\"}"))
            .andExpect(status().isOk());
        jdbcTemplate.update("insert into tenant_members(tenant_id, username, created_at)"
            + " select id, 'tenant-member', ? from tenants where code = 'default'", Timestamp.from(Instant.now()));
        Timestamp memberAt = Timestamp.from(Instant.now());
        jdbcTemplate.update("merge into projects (code, name, description, active, created_at, updated_at)"
            + " key(code) values ('MEMBER_DELETE_PROBE', '成员解绑探针', '', true, ?, ?)", memberAt, memberAt);
        jdbcTemplate.update("insert into project_members(project_id, username, created_at)"
            + " select id, 'tenant-member', ? from projects where code = 'MEMBER_DELETE_PROBE'", memberAt);

        mockMvc.perform(delete("/api/users/tenant-member")
                .with(user("admin").roles("ADMIN")).with(csrf()))
            .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from platform_users where username = 'tenant-member'", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from tenant_members where username = 'tenant-member'", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from project_members where username = 'tenant-member'", Integer.class)).isZero();
    }

    @Test
    void userManagementIsAdminOnly() throws Exception {
        mockMvc.perform(get("/api/users").with(user("viewer").roles("VIEWER")))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/users").with(user("editor").roles("EDITOR")))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/users")
                .with(user("editor").roles("EDITOR"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"hacker\",\"password\":\"12345678\",\"role\":\"VIEWER\"}"))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/users").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void viewerCanStillLogoutAndChangeOwnPassword() throws Exception {
        mockMvc.perform(post("/api/auth/logout").with(user("viewer").roles("VIEWER")).with(csrf()))
            .andExpect(status().isOk());
        mockMvc.perform(put("/api/auth/password")
                .with(user("viewer").roles("VIEWER"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"viewer-password-123\",\"newPassword\":\"viewer-new-123456\"}"))
            .andExpect(status().isOk());
    }

    // ---- 用户管理:创建 / 校验 / 更新 / 删除 ----

    @Test
    void adminCreatesUserWithPendingPasswordChange() throws Exception {
        mockMvc.perform(post("/api/users")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"qa.li\",\"password\":\"initial-pass-123\",\"displayName\":\"小李\",\"role\":\"EDITOR\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("qa.li"))
            .andExpect(jsonPath("$.role").value("EDITOR"))
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.mustChangePassword").value(true));

        assertThat(enabledAdminCount()).isEqualTo(1);
    }

    @Test
    void createRejectsDuplicateUsernameAndInvalidInput() throws Exception {
        mockMvc.perform(post("/api/users")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"initial-pass-123\",\"role\":\"EDITOR\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("用户名已存在"));

        mockMvc.perform(post("/api/users")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"qa.li\",\"password\":\"short\",\"role\":\"EDITOR\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("初始密码长度至少 8 位"));

        mockMvc.perform(post("/api/users")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"qa.li\",\"password\":\"initial-pass-123\",\"role\":\"ROOT\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("角色必须是 ADMIN / EDITOR / VIEWER 之一"));
    }

    @Test
    void adminUpdatesRoleAndResetPassword() throws Exception {
        mockMvc.perform(put("/api/users/viewer")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"EDITOR\",\"displayName\":\"观察者升级\",\"password\":\"brand-new-pass-123\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("EDITOR"))
            .andExpect(jsonPath("$.displayName").value("观察者升级"))
            .andExpect(jsonPath("$.mustChangePassword").value(true));
    }

    @Test
    void cannotDisableOrDemoteTheLastEnabledAdmin() throws Exception {
        mockMvc.perform(put("/api/users/admin")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"VIEWER\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("平台必须保留至少一个可用的管理员"));

        mockMvc.perform(put("/api/users/admin")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("平台必须保留至少一个可用的管理员"));

        assertThat(enabledAdminCount()).isEqualTo(1);
    }

    @Test
    void cannotDeleteTheLastEnabledAdminOrOwnAccount() throws Exception {
        mockMvc.perform(delete("/api/users/admin").with(user("admin").roles("ADMIN")).with(csrf()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("不能删除当前登录的账号"));

        assertThat(enabledAdminCount()).isEqualTo(1);
    }

    @Test
    void adminCanDisableAndDeleteAViewer() throws Exception {
        mockMvc.perform(put("/api/users/viewer")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(false));

        mockMvc.perform(delete("/api/users/viewer")
                .with(user("admin").roles("ADMIN"))
                .with(csrf()))
            .andExpect(status().isOk());

        Integer remaining = jdbcTemplate.queryForObject(
            "select count(*) from platform_users where username = 'viewer'", Integer.class);
        assertThat(remaining).isZero();
    }
}
