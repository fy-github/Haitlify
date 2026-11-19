package com.haitlify.aitest.platform.tenant;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * 租户与成员的最小运维面：此前只能手工 SQL，多用户场景下没人能安全地开租户/加成员。
 *
 * <p>约束：
 * <ul>
 *   <li>code 只允许小写字母/数字/中划线，唯一（冲突 409）；</li>
 *   <li>default 租户不允许停用（启动引导依赖它兜底，停用会让无租户用户全部失联）；</li>
 *   <li>成员必须是已存在的平台用户（外键）；重复添加 409，不静默成功。</li>
 * </ul>
 */
@Service
public class TenantManagementService {

    private static final Pattern CODE = Pattern.compile("^[a-z0-9][a-z0-9-]{1,31}$");

    private final JdbcTemplate jdbcTemplate;

    TenantManagementService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<TenantView> list() {
        return jdbcTemplate.query(
            "select t.id, t.code, t.name, t.active, t.created_at,"
                + " (select count(*) from tenant_members m where m.tenant_id = t.id) as member_count"
                + " from tenants t order by t.id",
            (rs, rowNum) -> new TenantView(
                rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                rs.getBoolean("active"), rs.getTimestamp("created_at").toInstant(),
                rs.getLong("member_count")));
    }

    public TenantView create(String code, String name) {
        String normalizedCode = requireCode(code);
        String normalizedName = requireName(name);
        Instant now = Instant.now();
        try {
            jdbcTemplate.update(
                "insert into tenants (code, name, active, created_at, updated_at) values (?, ?, true, ?, ?)",
                normalizedCode, normalizedName, now, now);
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "租户 code 已存在：" + normalizedCode);
        }
        Long id = jdbcTemplate.queryForObject("select id from tenants where code = ?", Long.class, normalizedCode);
        return new TenantView(id, normalizedCode, normalizedName, true, now, 0L);
    }

    public TenantView update(long tenantId, String name, Boolean active) {
        TenantView current = requireTenant(tenantId);
        String nextName = name == null ? current.name() : requireName(name);
        boolean nextActive = active == null ? current.active() : active;
        if (!nextActive && "default".equals(current.code())) {
            throw new IllegalArgumentException("default 租户不能停用：无租户用户会全部失去兜底归属");
        }
        jdbcTemplate.update("update tenants set name = ?, active = ?, updated_at = ? where id = ?",
            nextName, nextActive, Instant.now(), tenantId);
        Long memberCount = jdbcTemplate.queryForObject(
            "select count(*) from tenant_members where tenant_id = ?", Long.class, tenantId);
        return new TenantView(tenantId, current.code(), nextName, nextActive, current.createdAt(), memberCount);
    }

    public List<MemberView> members(long tenantId) {
        requireTenant(tenantId);
        return jdbcTemplate.query(
            "select m.username, m.created_at, u.role from tenant_members m"
                + " join platform_users u on u.username = m.username"
                + " where m.tenant_id = ? order by m.id",
            (rs, rowNum) -> new MemberView(rs.getString("username"), rs.getString("role"),
                rs.getTimestamp("created_at").toInstant()), tenantId);
    }

    public void addMember(long tenantId, String username) {
        requireTenant(tenantId);
        String normalized = requireUsername(username);
        Integer exists = jdbcTemplate.queryForObject(
            "select count(*) from platform_users where username = ?", Integer.class, normalized);
        if (exists == null || exists == 0) {
            throw new IllegalArgumentException("平台用户不存在：" + normalized);
        }
        try {
            jdbcTemplate.update("insert into tenant_members (tenant_id, username, created_at) values (?, ?, ?)",
                tenantId, normalized, Instant.now());
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该用户已是租户成员：" + normalized);
        }
    }

    public void removeMember(long tenantId, String username) {
        requireTenant(tenantId);
        int removed = jdbcTemplate.update("delete from tenant_members where tenant_id = ? and username = ?",
            tenantId, requireUsername(username));
        if (removed == 0) {
            throw new IllegalArgumentException("该用户不是租户成员：" + username);
        }
    }

    TenantView requireTenant(long tenantId) {
        List<TenantView> found = jdbcTemplate.query(
            "select id, code, name, active, created_at from tenants where id = ?",
            (rs, rowNum) -> new TenantView(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                rs.getBoolean("active"), rs.getTimestamp("created_at").toInstant(), null),
            tenantId);
        if (found.isEmpty()) {
            throw new IllegalArgumentException("租户不存在：" + tenantId);
        }
        return found.get(0);
    }

    private static String requireCode(String code) {
        // 不静默改写大小写：报错文案说「只能小写」，就不能把 ABC 偷偷存成 abc。
        String normalized = code == null ? "" : code.trim();
        if (!CODE.matcher(normalized).matches()) {
            throw new IllegalArgumentException("租户 code 只能是小写字母/数字/中划线，长度 2–32：" + code);
        }
        return normalized;
    }

    private static String requireName(String name) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isEmpty() || normalized.length() > 128) {
            throw new IllegalArgumentException("租户名称不能为空且不超过 128 字符");
        }
        return normalized;
    }

    private static String requireUsername(String username) {
        String normalized = username == null ? "" : username.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("username 必填");
        }
        return normalized;
    }

    public record TenantView(long id, String code, String name, boolean active, Instant createdAt,
                             Long memberCount) {
    }

    public record MemberView(String username, String role, Instant createdAt) {
    }
}
