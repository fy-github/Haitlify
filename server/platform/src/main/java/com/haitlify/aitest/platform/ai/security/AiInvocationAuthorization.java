package com.haitlify.aitest.platform.ai.security;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.haitlify.aitest.platform.auth.ProjectAccessService;

/**
 * T14：AI 调用授权。两个语义要点：
 *
 * <ul>
 *   <li><b>存在性不泄漏</b>：AI 资源（run/artifact/attempt/document）的「不存在」与「无权」
 *       对外一律同一个 {@code 404 not_found} —— 攻击者无法通过状态码/文案区分对象是否存在；</li>
 *   <li><b>后台可复核</b>：后台执行没有 HTTP session，按 run 的 actor 显式复核成员关系——
 *       撤权在下一个节点执行前即生效（{@link #requireProjectAccessAs}）。</li>
 * </ul>
 */
@Service
public class AiInvocationAuthorization {

    private final JdbcTemplate jdbcTemplate;
    private final ProjectAccessService projectAccessService;
    private final boolean securityEnabled;

    public AiInvocationAuthorization(JdbcTemplate jdbcTemplate, ProjectAccessService projectAccessService,
                                     @Value("${app.security.enabled:true}") boolean securityEnabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.projectAccessService = projectAccessService;
        this.securityEnabled = securityEnabled;
    }

    /** HTTP 上下文：当前登录身份访问项目；失败与「不存在」同形。 */
    public void requireProjectAccess(Long projectId) {
        if (!securityEnabled) {
            return;
        }
        try {
            projectAccessService.requireAccess(projectId);
        } catch (ResponseStatusException failure) {
            throw notFound();
        }
    }

    /**
     * 后台上下文：显式 actor（run 创建者）复核项目成员关系。撤权立即生效——节点每次执行前
     * 都必须经过本方法，而不是信任创建时的快照。
     */
    public void requireProjectAccessAs(String actor, Long projectId) {
        if (!securityEnabled) {
            return;
        }
        if (actor == null || actor.isBlank() || projectId == null) {
            throw notFound();
        }
        List<String> roles = jdbcTemplate.query(
            "select role from platform_users where username = ? and enabled = true",
            (rs, rowNum) -> rs.getString("role"), actor);
        if (roles.isEmpty()) {
            throw notFound();
        }
        boolean admin = "ADMIN".equalsIgnoreCase(roles.get(0));
        if (!admin) {
            Integer active = jdbcTemplate.queryForObject(
                "select count(*) from projects where id = ? and active = true", Integer.class, projectId);
            if (active == null || active == 0) {
                throw notFound();
            }
            Integer member = jdbcTemplate.queryForObject(
                "select count(*) from project_members where project_id = ? and username = ?",
                Integer.class, projectId, actor);
            if (member == null || member == 0) {
                throw notFound();
            }
        }
    }

    /** 当前登录 actor（HTTP 上下文）；未登录返回 null（由调用方决定形态）。 */
    public String currentActorOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return authentication.getName();
    }

    /** 资源不存在与无权访问的统一形状：404 not_found，绝不携带对象信息。 */
    public static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "not_found");
    }

    /** 测试/诊断辅助：某 actor 是否仍是某项目成员（后台复核用）。 */
    public boolean isProjectMember(String actor, Long projectId) {
        if (!securityEnabled) {
            return true;
        }
        if (actor == null || actor.isBlank() || projectId == null) {
            return false;
        }
        Integer member = jdbcTemplate.queryForObject(
            "select count(*) from project_members where project_id = ? and username = ?",
            Integer.class, projectId, actor);
        return member != null && member > 0;
    }

    /** 诊断视图（只读，不含任何秘密）。 */
    public Map<String, Object> describe() {
        return Map.of("securityEnabled", securityEnabled,
            "uniformShape", "404 not_found");
    }
}
