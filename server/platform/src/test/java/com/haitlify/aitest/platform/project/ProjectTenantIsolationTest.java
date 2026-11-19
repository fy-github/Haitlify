package com.haitlify.aitest.platform.project;

import static org.assertj.core.api.Assertions.assertThat;
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
 * 项目注册表也是租户数据：非 ADMIN 只能看/建自己租户的项目。
 * （之前 GET /api/projects 会返回所有租户的项目，POST 也能用别人的 tenantId 建。）
 */
@SpringBootTest(properties = "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=")
@ActiveProfiles("test")
@TestPropertySource(properties = "app.security.enabled=true")
class ProjectTenantIsolationTest {

    @Autowired
    private ProjectService projects;
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
        jdbc.update("insert into tenants(code, name, active, created_at, updated_at) values ('pa', 'A', true, ?, ?)", now, now);
        jdbc.update("insert into tenants(code, name, active, created_at, updated_at) values ('pb', 'B', true, ?, ?)", now, now);
        tenantA = jdbc.queryForObject("select id from tenants where code = 'pa'", Long.class);
        tenantB = jdbc.queryForObject("select id from tenants where code = 'pb'", Long.class);
        jdbc.update("insert into tenant_members(tenant_id, username, created_at) values (?, 'alice', ?)", tenantA, now);
        jdbc.update("insert into projects(code, name, description, active, tenant_id, created_at, updated_at)"
            + " values ('iso-a', 'A 项目', '', true, ?, ?, ?)", tenantA, now, now);
        jdbc.update("insert into projects(code, name, description, active, tenant_id, created_at, updated_at)"
            + " values ('iso-b', 'B 项目', '', true, ?, ?, ?)", tenantB, now, now);
        login("alice");
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        jdbc.update("delete from project_members where username in ('alice', 'root')");
        jdbc.update("delete from projects where code like 'iso-%'");
        jdbc.update("delete from tenant_members where username in ('alice', 'root')");
        jdbc.update("delete from tenants where code in ('pa', 'pb')");
        jdbc.update("delete from platform_users where username in ('alice', 'root')");
    }

    private void login(String username) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            username, "n/a", AuthorityUtils.createAuthorityList("ROLE_USER")));
    }

    @Test
    void memberOnlySeesOwnTenantProjectsAndAdminSeesAll() {
        var visible = projects.list().items().stream().map(item -> item.code()).toList();
        assertThat(visible).contains("iso-a").doesNotContain("iso-b");

        login("root");
        var all = projects.list().items().stream().map(item -> item.code()).toList();
        assertThat(all).contains("iso-a", "iso-b");
    }

    @Test
    void memberCannotCreateProjectInAnotherTenant() {
        var request = new CreateProjectRequest("iso-new-b", "别人的租户", "", tenantB);
        assertThatThrownBy(() -> projects.create(request))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void createWithoutTenantIdLandsInDefaultAndRequiresDefaultMembership() {
        // 不传 tenantId ⇒ default 租户；alice 不是 default 成员，必须 403，
        // 否则会建出一个她自己列表里看不到的项目（复核 P2-C）。
        var request = new CreateProjectRequest("iso-no-tenant", "默认租户", "", null);
        assertThatThrownBy(() -> projects.create(request))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));

        // 明确落在自己所属租户则允许。
        var allowed = new CreateProjectRequest("iso-in-a", "A 新项目", "", tenantA);
        assertThat(projects.create(allowed).code()).isEqualTo("iso-in-a");
    }

    @Test
    void multiTenantMemberSeesEveryTenantTheyBelongTo() {
        Instant now = Instant.now();
        jdbc.update("insert into tenant_members(tenant_id, username, created_at) values (?, 'alice', ?)",
            tenantB, now);

        var visible = projects.list().items().stream().map(item -> item.code()).toList();
        assertThat(visible).contains("iso-a", "iso-b");
    }

    @Test
    void codeCollisionAcrossTenantsIsConflictInsteadOfLeakingTheOtherProject() {
        // alice 在自己的 A 租户里用 B 租户已占用的 code：必须 409，且响应里不能出现 B 的项目行。
        var request = new CreateProjectRequest("iso-b", "抢 code", "", tenantA);
        assertThatThrownBy(() -> projects.create(request))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT));
    }
}
