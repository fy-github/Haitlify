package com.haitlify.aitest.platform.assistant.conversation;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import com.haitlify.aitest.platform.auth.ProjectAccessService;
import com.haitlify.aitest.platform.tenant.TenantAccessService;

/**
 * 对话入口的身份与授权：<b>身份只从登录主体派生</b>，请求体里的任何 identity 字段都不被读取
 * （控制器只接受白名单键，多一个键就是 400）。
 *
 * <p>刻意与 {@code app.security.enabled} 解耦：即使全局开关关着（本地/测试），
 * 「是否有登录主体」「用户是否启用且是 ADMIN/EDITOR」「租户与项目是否 active、是否成员」
 * 仍然逐条校验。否则关掉全局开关就等于关掉这条入口的隔离。
 *
 * <p>为什么这里不直接复用 {@link TenantAccessService}/{@link ProjectAccessService} 就够：
 * 它们在全局开关关闭时是空操作，而本入口的用例要求隔离始终生效，因此那些调用只作为
 * 「开关打开时的第二道校验」保留。
 */
class AssistantConversationAccess {

    record Actor(long id, String username, String role) {}

    private final JdbcTemplate jdbc;
    private final TenantAccessService tenants;
    private final ProjectAccessService projects;

    AssistantConversationAccess(JdbcTemplate jdbc, TenantAccessService tenants, ProjectAccessService projects) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.projects = projects;
    }

    /** 当前登录主体对应的平台用户；未登录/已停用一律 401，角色不足一律 403。 */
    Actor requireActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
            || authentication instanceof AnonymousAuthenticationToken) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "助手对话需要登录主体");
        }
        Actor actor = jdbc.query("select id, username, role from platform_users where username = ? and enabled = true",
            (rs, rowNum) -> new Actor(rs.getLong("id"), rs.getString("username"), rs.getString("role")),
            authentication.getName()).stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在或已停用"));
        if (!"ADMIN".equals(actor.role()) && !"EDITOR".equals(actor.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权使用助手对话");
        }
        return actor;
    }

    /**
     * actor 对项目的访问权；返回项目归属租户。
     *
     * <p>越权一律 403，不区分「项目不存在」与「无权访问」——把存在性泄露给非成员本身就是越权信息。
     */
    long requireProject(Actor actor, long projectId) {
        record Project(long tenantId) {}
        Project project = jdbc.query(
            "select tenant_id from projects where id = ? and active = true and tenant_id is not null",
            (rs, rowNum) -> new Project(rs.getLong("tenant_id")), projectId).stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该项目"));
        Integer activeTenant = jdbc.queryForObject("select count(*) from tenants where id = ? and active = true",
            Integer.class, project.tenantId());
        if (activeTenant == null || activeTenant == 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "租户不存在或已停用");
        }
        if (!"ADMIN".equals(actor.role())) {
            if (count("select count(*) from tenant_members where tenant_id = ? and username = ?",
                    project.tenantId(), actor.username()) == 0
                || count("select count(*) from project_members where project_id = ? and username = ?",
                    projectId, actor.username()) == 0) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该项目");
            }
        }
        try {
            tenants.requireTenantProject(project.tenantId(), projectId);
            projects.requireAccess(projectId);
        } catch (ResponseStatusException denied) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该项目");
        }
        return project.tenantId();
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}
