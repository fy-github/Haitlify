package com.haitlify.aitest.platform.assistant.conversation;

/**
 * {@link GatewayMcpConfigurator} 的网关侧实现：<b>先写条目、再 reload</b>，顺序不可交换。
 *
 * <p>为什么顺序是硬的：Hermes 只在新会话建立时读取 MCP 服务器条目，而且在那一刻条目就被<b>绑定</b>到
 * 那个会话。先 reload 再写，重载的是旧配置，下一个会话拿到的是<b>别人的</b> URL——那不只是「已撤销会
 * 401」这么轻：会话在条目被读走时就已经拿到了对另一个 generation 的能力（能力错配），平台却以为
 * 「已配置成功」。因此这里的顺序是：
 * <ol>
 *   <li>{@link GatewayMcpControl#writeServerEntry(String)} —— 写入本轮 URL；没有端点时写入 {@code null}
 *       即清除条目（绝不保留旧 URL）；</li>
 *   <li>{@link GatewayMcpControl#reload()} —— 让下一次会话看到它。</li>
 * </ol>
 * 第一步抛错时<b>不</b>执行 reload：条目没写成，reload 只会把旧配置再加载一遍，反而掩盖了失败。
 *
 * <p>本类本身不自带网关实现：它只接受注入的 {@link GatewayMcpControl}。真实控制面
 * （{@link ConfigFileGatewayMcpControl}）只在设置 {@code gateway-config-path} 时装配；默认没有 control，
 * 也就没有 configurator，服务层一次都不调用。
 *
 * <p>这是 {@code configure-gateway=true} 时<b>唯一</b>被接受的实现类型：装配会校验绑定对象是本类
 * （并同时要求 {@link GatewayMcpControl} Bean 存在），因为只有本类保证「先写条目、再 reload」，
 * 而一个手注册的空实现能让网关继续持有旧 URL 却看起来一切正常。
 *
 * <p>不记录 URL 明文日志：lease id 只允许出现在进程内的配置写入里。
 */
final class GatewayMcpLeaseConfigurator implements GatewayMcpConfigurator {

    private final GatewayMcpControl control;

    GatewayMcpLeaseConfigurator(GatewayMcpControl control) {
        if (control == null) {
            throw new IllegalArgumentException("Gateway MCP control required");
        }
        this.control = control;
    }

    @Override
    public void apply(String leaseEndpoint) {
        // 1) 写：null 表示清除条目（fail-closed），不得跳过。
        control.writeServerEntry(leaseEndpoint);
        // 2) reload：只有写成功才重载，否则等于把旧配置再加载一遍并假装成功。
        control.reload();
    }
}
