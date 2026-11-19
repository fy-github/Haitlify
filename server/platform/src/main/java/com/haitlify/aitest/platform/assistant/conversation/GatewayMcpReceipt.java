package com.haitlify.aitest.platform.assistant.conversation;

/**
 * 可选端口：网关 reload 之后对「当前 MCP 条目」的回执。
 *
 * <p>为什么需要它：reload 成功只说明网关接受了重载请求，不说明它<b>看到的</b>是本轮条目——网关可能
 * 缓存旧配置、配置文件被别的进程改写，或 reload 命中了另一个实例。回执是第二个独立观测点；没有实现时
 * 控制面只回读配置文件（构造参数为 {@code null}），有实现时两处都必须与本轮 URL 一致。
 *
 * <p>返回 {@code null} 表示网关<b>没有</b>该条目（fail-closed 清除后的正常状态）。实现方不得记录
 * 返回值（含 URL），也不得在异常里带上它。
 */
interface GatewayMcpReceipt {

    /** @return 网关视角下该 MCP 条目的 URL；{@code null} = 条目不存在 */
    String serverEntry();
}
