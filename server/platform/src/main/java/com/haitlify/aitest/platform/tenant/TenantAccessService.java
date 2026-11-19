package com.haitlify.aitest.platform.tenant;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.haitlify.aitest.platform.auth.ProjectAccessService;

/**
 * 租户授权：把 tenantId 从「调用方随便传的数字」变成必须能校验的实体。
 *
 * <p>规则（security 关闭时全部放行，本地/测试语义不变）：
 * <ol>
 *   <li>租户必须存在且 active；</li>
 *   <li>ADMIN 可跨租户（运维需要），但同样要满足「租户存在」；</li>
 *   <li>其他用户必须是该租户成员；</li>
 *   <li>带 projectId 的调用还要满足项目成员关系，且项目归属租户必须与 tenantId 一致（已绑定时）。</li>
 * </ol>
 */
@Service
public class TenantAccessService {

    private final JdbcTemplate jdbcTemplate;
    private final ProjectAccessService projectAccess;
    private final boolean securityEnabled;

    @Autowired
    public TenantAccessService(JdbcTemplate jdbcTemplate, ProjectAccessService projectAccess,
                               @Value("${app.security.enabled:true}") boolean securityEnabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.projectAccess = projectAccess;
        this.securityEnabled = securityEnabled;
    }

    public void requireTenant(long tenantId) {
        if (!securityEnabled) {
            return;
        }
        Integer active = jdbcTemplate.queryForObject(
            "select count(*) from tenants where id = ? and active = true", Integer.class, tenantId);
        if (active == null || active == 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "租户不存在或已停用：" + tenantId);
        }
        if (projectAccess.isAdmin()) {
            return;
        }
        String username = currentUsername();
        Integer member = jdbcTemplate.queryForObject(
            "select count(*) from tenant_members where tenant_id = ? and username = ?",
            Integer.class, tenantId, username);
        if (member == null || member == 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该租户：" + tenantId);
        }
    }

    /** 租户 + 项目双校验；项目已绑定租户时必须一致，防止用 A 租户身份操作 B 租户的项目。 */
    public void requireTenantProject(long tenantId, Long projectId) {
        requireTenant(tenantId);
        if (projectId == null) {
            throw new IllegalArgumentException("projectId 必填：操作必须绑定项目");
        }
        if (securityEnabled) {
            Long bound = jdbcTemplate.query(
                "select tenant_id from projects where id = ?",
                (rs, rowNum) -> (Long) rs.getObject("tenant_id"), projectId
            ).stream().findFirst().orElse(null);
            if (bound != null && bound != tenantId) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "租户与项目归属不一致：tenant=" + tenantId + " project=" + projectId);
            }
        }
        projectAccess.requireAccess(projectId);
    }

    /** 草稿/报告这类只带 tenantId 的读接口：先校验租户，再按项目成员关系由调用方校验。 */
    public void requireTenantOnly(long tenantId) {
        requireTenant(tenantId);
    }

    private String currentUsername() {
        var authentication = org.springframework.security.core.context.SecurityContextHolder
            .getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return authentication.getName();
    }
}
