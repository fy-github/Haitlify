package com.haitlify.aitest.platform.assistant.conversation;

/**
 * 端口：网关侧的两步操作——写 MCP 服务器条目、触发 reload。
 *
 * <p>刻意拆成两个方法而不是一个 {@code configure(url)}：真实网关的「写配置」与「reload 生效」是两次
 * 可分别失败的操作，顺序也是协议的一部分（先写、后 reload）。把它们合成一步会让「写成功但 reload 失败」
 * 无法表达，而那种状态的正确结论正是「本轮端点未生效」，不是「已配置」。
 *
 * <p><b>真实实现只在显式配置时装配</b>：{@link ConfigFileGatewayMcpControl} 需要
 * {@code app.assistant.conversation.mcp-lease.gateway-config-path} 与一个 {@link GatewayReloader} Bean；
 * 该属性没有默认值，所以默认装配里既没有控制面也没有配置器，对话链路一次都不调用、也不触碰任何网关配置文件。
 *
 * <p><b>装配要求</b>：{@code app.assistant.conversation.mcp-lease.configure-gateway=true} 时，上下文里
 * 必须同时存在本接口的 Bean 与 {@link GatewayMcpLeaseConfigurator} 类型的配置器，否则启动即失败——
 * 缺了本接口就没有真正写条目 + reload 的落点，配了别的实现则可能什么都不做而让平台以为「已配置」。
 */
interface GatewayMcpControl {

    /**
     * 写入（或清除）网关的 MCP 服务器条目；<b>不得</b>在这里顺带 reload。
     *
     * @param leaseEndpoint 本轮租约 URL；{@code null} 表示清除条目（本轮没有 MCP 端点）
     * @throws RuntimeException 写入失败
     */
    void writeServerEntry(String leaseEndpoint);

    /**
     * 触发网关 reload，使刚写入的条目对下一个会话生效。
     *
     * @throws RuntimeException reload 失败（此时配置已写但未生效，调用方按「本轮无端点」处理）
     */
    void reload();
}
