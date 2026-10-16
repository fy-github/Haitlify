package com.haitlify.aitest.platform.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
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
class AuthControllerTest {

    private static final String USERNAME = "admin";
    private static final String PASSWORD = "test-password-123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void seedAdminUser() {
        TestDataReset.clearPlatformUsers(jdbcTemplate);
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
            "insert into platform_users(username, password_hash, display_name, role, enabled, must_change_password, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?)",
            USERNAME, passwordEncoder.encode(PASSWORD), "管理员", "ADMIN", true, false, now, now
        );
    }

    @Test
    void rejectsUnauthenticatedAccessToProtectedEndpoint() throws Exception {
        mockMvc.perform(get("/api/ai/config"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsLoginWithWrongPassword() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    void logsInWithValidCredentialsAndReturnsUser() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("admin"))
            .andExpect(jsonPath("$.displayName").value("管理员"))
            .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void rejectsStateChangingRequestWithoutCsrfToken() throws Exception {
        mockMvc.perform(put("/api/ai/config")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void allowsAuthenticatedGetWithoutCsrfToken() throws Exception {
        mockMvc.perform(get("/api/ai/config")
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk());
    }

    @Test
    void exposesCurrentUserWhenAuthenticated() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("admin"));
    }

    @Test
    void changesPasswordWithCorrectCurrentPassword() throws Exception {
        mockMvc.perform(put("/api/auth/password")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"new-password-456\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("admin"));
    }

    @Test
    void rejectsPasswordChangeWithWrongCurrentPassword() throws Exception {
        mockMvc.perform(put("/api/auth/password")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"wrong\",\"newPassword\":\"new-password-456\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("当前密码不正确"));
    }

    @Test
    void rejectsPasswordChangeWithShortNewPassword() throws Exception {
        mockMvc.perform(put("/api/auth/password")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"short\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("新密码长度至少 8 位"));
    }

    @Test
    void clearsMustChangePasswordFlagAfterPasswordChange() throws Exception {
        jdbcTemplate.update("update platform_users set must_change_password = true where username = ?", USERNAME);

        mockMvc.perform(put("/api/auth/password")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"new-password-456\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mustChangePassword").value(false));
    }
}
