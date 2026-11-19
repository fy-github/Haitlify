package com.haitlify.aitest.platform.assistant.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 配置器的装配策略（不启动 Spring 上下文）：
 *
 * <ul>
 *   <li>开关关闭 → <b>绝不注入</b>，即使上下文里有实现（服务层因此一次都不会调用）；</li>
 *   <li>开关打开且有<b>租约实现 + 控制面 Bean</b> → 原样注入；</li>
 *   <li>开关打开却没有实现 → <b>启动即失败</b>：静默跳过会让网关继续挂着上一次的（已撤销）URL，
 *       而平台以为「本轮没有 MCP 端点」这件事已经被处理过了；</li>
 *   <li>开关打开、绑定的却是一个<b>非租约</b>实现（例如手注册的 {@code url -> {}}）→ 启动即失败。
 *       这一条以前没有：旧检查只看 Bean 的<b>类型</b>，所以一个什么都不做的空实现能安静启动，
 *       网关继续持有旧 URL，而平台以为「本轮已配置」；</li>
 *   <li>开关打开、有租约实现但上下文里<b>没有</b> {@link GatewayMcpControl} Bean → 启动即失败：
 *       没有「写条目 + reload」的落点，租约 URL 根本不会到达网关。</li>
 * </ul>
 */
class AssistantConversationMcpConfigureWiringTest {

    private static final RecordingGatewayMcpControl CONTROL = new RecordingGatewayMcpControl();
    private static final GatewayMcpConfigurator LEASE_IMPLEMENTATION = new GatewayMcpLeaseConfigurator(CONTROL);
    /** 手注册的「类型对、实际什么都不做」实现：旧检查放行，这正是要 fail-fast 的那类静默。 */
    private static final GatewayMcpConfigurator NO_OP_IMPLEMENTATION = leaseEndpoint -> { };

    @Test
    void theDisabledSwitchNeverInjectsAnExistingImplementation() {
        assertThat(AssistantConversationConfiguration.gatewayMcp(false, provider(LEASE_IMPLEMENTATION),
            provider(CONTROL))).isNull();
        // 关闭时不校验类型：上下文里放一个非租约实现也不影响启动（它一次都不会被调用）。
        assertThat(AssistantConversationConfiguration.gatewayMcp(false, provider(NO_OP_IMPLEMENTATION),
            provider(null))).isNull();
    }

    @Test
    void theEnabledSwitchInjectsTheLeaseImplementation() {
        assertThat(AssistantConversationConfiguration.gatewayMcp(true, provider(LEASE_IMPLEMENTATION),
            provider(CONTROL))).isSameAs(LEASE_IMPLEMENTATION);
    }

    @Test
    void theEnabledSwitchFailsFastInsteadOfSilentlySkippingWhenNoImplementationExists() {
        assertThatThrownBy(() -> AssistantConversationConfiguration.gatewayMcp(true,
            provider(null), provider(CONTROL)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("configure-gateway=true");
    }

    /** 旧检查的漏洞：类型对但不是租约实现 → 必须启动即失败，而不是「看起来配置成功」。 */
    @Test
    void theEnabledSwitchRejectsANonLeaseImplementation() {
        assertThatThrownBy(() -> AssistantConversationConfiguration.gatewayMcp(true,
            provider(NO_OP_IMPLEMENTATION), provider(CONTROL)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("不是 GatewayMcpLeaseConfigurator");
    }

    /** 有租约实现、但上下文里没有控制面 Bean：写条目 + reload 的落点不存在。 */
    @Test
    void theEnabledSwitchRequiresAGatewayMcpControlBean() {
        assertThatThrownBy(() -> AssistantConversationConfiguration.gatewayMcp(true,
            provider(LEASE_IMPLEMENTATION), provider(null)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("GatewayMcpControl");
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return require(value);
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            private T require(T resolved) {
                if (resolved == null) {
                    throw new IllegalStateException("no bean");
                }
                return resolved;
            }
        };
    }
}
