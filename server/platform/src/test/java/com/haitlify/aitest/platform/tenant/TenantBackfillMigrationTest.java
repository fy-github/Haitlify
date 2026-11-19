package com.haitlify.aitest.platform.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * V72 回填的行为测试：Flyway 在空库上跑迁移不会覆盖「历史数据」，
 * 这里把迁移文件按语句重放到带历史数据的 H2 上，验证：
 *   1) 只在草稿里出现的项目也能按 tenant_id 归属；
 *   2) 重复执行幂等（不建重复租户、不报错）。
 */
@SpringBootTest(properties = "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=")
@ActiveProfiles("test")
class TenantBackfillMigrationTest {

    private static final long LEGACY_TENANT = 424242L;

    @Autowired
    private JdbcTemplate jdbc;

    private long projectId;

    @BeforeEach
    void seedLegacyRows() {
        cleanup();
        Instant now = Instant.now();
        jdbc.update("insert into projects(code, name, description, active, created_at, updated_at)"
            + " values ('legacy-draft-only', '只有草稿的项目', '', true, ?, ?)", now, now);
        projectId = jdbc.queryForObject(
            "select id from projects where code = 'legacy-draft-only'", Long.class);
        jdbc.update("insert into assistant_draft(draft_id, tenant_id, project_id, requirement_ref,"
            + " requirement_digest, draft_json, state, created_by, created_at, action_code, target_ref,"
            + " target_revision, inputs_json, inputs_digest, revision)"
            + " values ('draft-legacy-1', ?, ?, 'req://legacy', ?, '{}', 'DRAFT', 1, ?,"
            + " 'web.case.execute', 'project:x/y', 'rev-1', '{}', ?, 1)",
            LEGACY_TENANT, projectId, "a".repeat(64), now, "b".repeat(64));
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from assistant_draft where draft_id like 'draft-legacy-%'");
        jdbc.update("delete from projects where code = 'legacy-draft-only'");
        // 映射/守卫表带外键，必须先清映射再删租户；守卫不清会让同类后续用例的 V73 变成 no-op。
        jdbc.update("delete from tenant_legacy_map");
        jdbc.update("delete from tenant_migration_guard");
        jdbc.update("delete from tenants where code like 'legacy-%'");
    }

    @Test
    void backfillsDraftOnlyProjectsAndIsIdempotent() throws IOException {
        applyV72();
        applyV72();

        Long tenantId = jdbc.queryForObject(
            "select tenant_id from projects where id = ?", Long.class, projectId);
        assertThat(tenantId).isNotNull();
        String code = jdbc.queryForObject("select code from tenants where id = ?", String.class, tenantId);
        assertThat(code).isEqualTo("legacy-" + LEGACY_TENANT);
        Integer duplicates = jdbc.queryForObject(
            "select count(*) from tenants where code = ?", Integer.class, "legacy-" + LEGACY_TENANT);
        assertThat(duplicates).isEqualTo(1);
    }

    @Test
    void alignsHistoricalTenantIdsSoOldLedgerRowsStayReachable() throws IOException {
        // 先按 V72 补出 legacy-*（自增 id），再跑 V73：账本 tenant_id 必须被重映射到真实租户 id。
        applyV72();
        applyV73();
        applyV73();

        Long tenantId = jdbc.queryForObject(
            "select id from tenants where code = ?", Long.class, "legacy-" + LEGACY_TENANT);
        Long draftTenant = jdbc.queryForObject(
            "select tenant_id from assistant_draft where draft_id = 'draft-legacy-1'", Long.class);
        assertThat(draftTenant).isEqualTo(tenantId);

        // 再跑一次 V73 不应改动（幂等）。
        applyV73();
        assertThat(jdbc.queryForObject(
            "select tenant_id from assistant_draft where draft_id = 'draft-legacy-1'", Long.class))
            .isEqualTo(tenantId);
    }

    @Test
    void legacyTenantsUseOffsetIdsSoTheyCannotCollideWithHistoricalIds() throws IOException {
        applyV72();
        applyV73();

        Long draftTenant = jdbc.queryForObject(
            "select tenant_id from assistant_draft where draft_id = 'draft-legacy-1'", Long.class);
        // 偏移 id：与历史 tenant_id（424242）不同，避免「自增 id 正好等于另一个历史 id」导致租户合并。
        assertThat(draftTenant).isNotEqualTo(LEGACY_TENANT);
        assertThat(draftTenant).isGreaterThanOrEqualTo(1_000_000_000L);
        assertThat(jdbc.queryForObject("select code from tenants where id = ?", String.class, draftTenant))
            .isEqualTo("legacy-" + LEGACY_TENANT);
    }

    @Test
    void twoHistoricalTenantsStaySeparate() throws IOException {
        // 历史集合 {424242, 424243}：V72 的自增 id 曾把两者合并（legacy-424243 拿到 2 号 id）。
        Instant now = Instant.now();
        jdbc.update("insert into assistant_draft(draft_id, tenant_id, project_id, requirement_ref,"
            + " requirement_digest, draft_json, state, created_by, created_at, action_code, target_ref,"
            + " target_revision, inputs_json, inputs_digest, revision)"
            + " values ('draft-legacy-2', ?, ?, 'req://legacy2', ?, '{}', 'DRAFT', 1, ?,"
            + " 'web.case.execute', 'project:x/y', 'rev-1', '{}', ?, 1)",
            LEGACY_TENANT + 1, projectId, "a".repeat(64), now, "b".repeat(64));

        applyV72();
        applyV73();

        Long first = jdbc.queryForObject(
            "select tenant_id from assistant_draft where draft_id = 'draft-legacy-1'", Long.class);
        Long second = jdbc.queryForObject(
            "select tenant_id from assistant_draft where draft_id = 'draft-legacy-2'", Long.class);
        assertThat(first).isNotEqualTo(second);
        // 偏移 id 是防合并的前提：自增 id 可能等于另一个历史 id（复核变异实验）。
        assertThat(first).isGreaterThanOrEqualTo(1_000_000_000L);
        assertThat(second).isGreaterThanOrEqualTo(1_000_000_000L);
        assertThat(jdbc.queryForObject("select code from tenants where id = ?", String.class, first))
            .isEqualTo("legacy-" + LEGACY_TENANT);
        assertThat(jdbc.queryForObject("select code from tenants where id = ?", String.class, second))
            .isEqualTo("legacy-" + (LEGACY_TENANT + 1));

        // 由 @AfterEach 统一清理
    }

    private void applyV73() throws IOException {
        applyMigration("db/migration/V73__align_legacy_tenant_ids.sql");
    }

    private void applyV72() throws IOException {
        applyMigration("db/migration/V72__backfill_legacy_tenants.sql");
    }

    /** 按语句重放迁移文件（Flyway 已经跑过一次，这里模拟「历史数据 + DBA 重放」）。 */
    private void applyMigration(String resource) throws IOException {
        String sql = new String(new ClassPathResource(resource)
            .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Arrays.stream(sql.split(";\\s*\\n"))
            // 去掉每段开头的注释行，而不是把整段丢掉（否则「注释 + UPDATE」会被当成注释跳过）。
            .map(chunk -> chunk.replaceAll("(?m)^\\s*--.*$", "").trim())
            .filter(statement -> !statement.isEmpty())
            .forEach(jdbc::execute);
    }
}
