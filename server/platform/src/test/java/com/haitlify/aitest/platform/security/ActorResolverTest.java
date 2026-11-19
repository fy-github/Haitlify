package com.haitlify.aitest.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * T14：actor 解析——已登录写路径必须携带真实用户；会话缺失的后台/自动化上下文
 * 落到显式的 system，且不存在可冒充的默认开发身份（system-dev 已删除）。
 */
class ActorResolverTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatedCallersCarryTheirRealIdentity() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "qa-user", "n/a", List.of(new SimpleGrantedAuthority("ROLE_EDITOR"))));
        assertThat(ActorResolver.currentOrSystem()).isEqualTo("qa-user");
    }

    @Test
    void sessionlessContextsFallBackToAnExplicitSystemActorNotADefaultPersona() {
        SecurityContextHolder.clearContext();
        assertThat(ActorResolver.currentOrSystem()).isEqualTo("system");

        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
            "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        assertThat(ActorResolver.currentOrSystem()).isEqualTo("system");
        assertThat(ActorResolver.currentOrSystem()).isNotEqualTo("system-dev");
    }
}
