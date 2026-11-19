package com.haitlify.aitest.platform.security;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * T14：actor 解析。用户发起的写路径必须用真实登录用户；只有会话缺失的后台/自动化上下文
 * 才落到显式的 {@code system}——不再存在可冒充的默认开发身份（system-dev/Development User）。
 */
public final class ActorResolver {

    public static final String SYSTEM = "system";

    private ActorResolver() {
    }

    /** 展示名：system 上下文用显式 System，登录用户用其用户名（不再有伪造的个人名）。 */
    public static String currentOrSystemName() {
        String actor = currentOrSystem();
        return SYSTEM.equals(actor) ? "System" : actor;
    }

    /**
     * 全链路人工确认 Exit Gate 的规范判定：确认/驳回/修改/导出必须来自真实登录用户。
     * 匿名令牌、未认证上下文、空名与字面 {@code system} 一律拒绝（fail-closed）。
     */
    public static String requireHumanActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new IllegalArgumentException("actor required: 人工确认必须来自已认证操作人");
        }
        String name = authentication.getName();
        if (name == null || name.isBlank() || SYSTEM.equals(name)) {
            throw new IllegalArgumentException("actor required: 人工确认必须来自已认证操作人");
        }
        return name;
    }

    public static String currentOrSystem() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return SYSTEM;
        }
        String name = authentication.getName();
        return name == null || name.isBlank() ? SYSTEM : name;
    }
}
