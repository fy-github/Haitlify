package com.haitlify.aitest.platform.tenant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

/**
 * 租户授权必须真的拦住跨租户：这里打开 security（其余用例默认关闭），
 * 用真实 platform_users / tenants / tenant_members 行验证四种结果。
 */
@SpringBootTest(properties = "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=")
@ActiveProfiles("test")
@TestPropertySource(properties = "app.security.enabled=true")
class TenantAccessServiceTest {

    @Autowired
    private TenantAccessService tenants;
    @Autowired
    private JdbcTemplate jdbc;

    private long tenantA;
    private long tenantB;

    @BeforeEach
    void seed() {
        cleanup();
        Instant now = Instant.now();
        jdbc.update("insert into platform_users(username, password_hash, role, enabled, created_at, updated_at)"
            + " values ('alice', 'x', 'USER', true, ?, ?)", now, now);
        jdbc.update("insert into platform_users(username, password_hash, role, enabled, created_at, updated_at)"
            + " values ('root', 'x', 'ADMIN', true, ?, ?)", now, now);
        jdbc.update("insert into tenants(code, name, active, created_at, updated_at) values ('ta', 'A', true, ?, ?)", now, now);
        jdbc.update("insert into tenants(code, name, active, created_at, updated_at) values ('tb', 'B', true, ?, ?)", now, now);
        jdbc.update("insert into tenants(code, name, active, created_at, updated_at) values ('off', '停用', false, ?, ?)", now, now);
        tenantA = jdbc.queryForObject("select id from tenants where code = 'ta'", Long.class);
        tenantB = jdbc.queryForObject("select id from tenants where code = 'tb'", Long.class);
        jdbc.update("insert into tenant_members(tenant_id, username, created_at) values (?, 'alice', ?)", tenantA, now);
        login("alice");
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        jdbc.update("delete from tenant_members where username in ('alice', 'root')");
        jdbc.update("delete from project_members where username in ('alice', 'root')");
        jdbc.update("delete from projects where code like 'tenant-test-%'");
        jdbc.update("delete from tenants where code in ('ta', 'tb', 'off')");
        jdbc.update("delete from platform_users where username in ('alice', 'root')");
    }

    private void login(String username) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            username, "n/a", AuthorityUtils.createAuthorityList("ROLE_USER")));
    }

    @Test
    void memberOfTenantPassesAndOutsiderIsRejected() {
        assertThatCode(() -> tenants.requireTenant(tenantA)).doesNotThrowAnyException();

        assertThatThrownBy(() -> tenants.requireTenant(tenantB))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> org.assertj.core.api.Assertions
                .assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN))
            .hasMessageContaining("无权访问该租户");
    }

    @Test
    void unknownOrDisabledTenantIsRejectedEvenForAdmin() {
        long disabled = jdbc.queryForObject("select id from tenants where code = 'off'", Long.class);
        assertThatThrownBy(() -> tenants.requireTenant(disabled))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("租户不存在或已停用");

        login("root");
        assertThatThrownBy(() -> tenants.requireTenant(999_999_999L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("租户不存在或已停用");
        // ADMIN 可以跨租户，但仍受「租户存在且启用」约束。
        assertThatCode(() -> tenants.requireTenant(tenantB)).doesNotThrowAnyException();
    }

    @Test
    void projectMustBelongToTheClaimedTenant() {
        Instant now = Instant.now();
        jdbc.update("insert into projects(code, name, description, active, tenant_id, created_at, updated_at)"
            + " values ('tenant-test-p1', 'P1', '', true, ?, ?, ?)", tenantA, now, now);
        long project = jdbc.queryForObject("select id from projects where code = 'tenant-test-p1'", Long.class);

        // alice 是 A 的成员但不是 B 的成员：用 B 声称操作 A 的项目必须被拒（先撞租户成员校验）。
        assertThatThrownBy(() -> tenants.requireTenantProject(tenantB, project))
            .isInstanceOf(ResponseStatusException.class);

        // 用 A 声称操作 A 的项目：租户成员 + 项目归属都对，但仍需项目成员关系（未加成员 ⇒ 403）。
        assertThatThrownBy(() -> tenants.requireTenantProject(tenantA, project))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("无权访问项目");

        jdbc.update("insert into project_members(project_id, username, created_at) values (?, 'alice', ?)",
            project, now);
        assertThatCode(() -> tenants.requireTenantProject(tenantA, project)).doesNotThrowAnyException();
    }
}
