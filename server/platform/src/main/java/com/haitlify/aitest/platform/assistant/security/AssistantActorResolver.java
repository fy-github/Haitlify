package com.haitlify.aitest.platform.assistant.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 把「谁在确认/接受」绑定到登录主体，而不是请求体里的自报 actorId。
 *
 * <p>规则：
 * <ul>
 *   <li>security 开启时：必须已认证；actorId 由 {@code platform_users.id} 反查得到；请求体若自称别的 id 一律 403；</li>
 *   <li>security 关闭时（本地/测试）：沿用请求体，但只用于开发与测试，不能作为生产审计依据。</li>
 * </ul>
 */
@Component
public class AssistantActorResolver {

    private final JdbcTemplate jdbcTemplate;
    private final boolean securityEnabled;

    public AssistantActorResolver(JdbcTemplate jdbcTemplate,
                                  @Value("${app.security.enabled:true}") boolean securityEnabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.securityEnabled = securityEnabled;
    }

    public Long resolveActor(Long bodyActorId) {
        if (!securityEnabled) {
            return bodyActorId;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
            || authentication instanceof AnonymousAuthenticationToken) {
            throw new ActorResolutionException("actor required: 该操作必须来自已登录用户");
        }
        String username = authentication.getName();
        Long id = jdbcTemplate.query(
            "select id from platform_users where username = ? and enabled = true",
            (rs, rowNum) -> rs.getLong("id"), username).stream().findFirst()
            .orElseThrow(() -> new ActorResolutionException("actor required: 用户不存在或已停用"));
        if (bodyActorId != null && !bodyActorId.equals(id)) {
            throw new ActorResolutionException("actorId 与登录主体不一致，拒绝冒充");
        }
        return id;
    }

    public static class ActorResolutionException extends RuntimeException {
        public ActorResolutionException(String message) {
            super(message);
        }
    }
}
