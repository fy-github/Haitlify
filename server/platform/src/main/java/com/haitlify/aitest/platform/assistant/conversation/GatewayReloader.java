package com.haitlify.aitest.platform.assistant.conversation;

/**
 * 端口：让网关重新读取配置（真实实现对应网关的 {@code reload.mcp}）。
 *
 * <p>为什么单独一个端口而不是把「怎么 reload」写进 {@link ConfigFileGatewayMcpControl}：写配置文件是
 * 文件系统操作，reload 是网关操作（CLI、HTTP 管理接口或子进程），两者的失败语义、权限和部署形态都不同。
 * 控制面只依赖本接口，测试用桩记录调用顺序与次数，绝不启动真实 Hermes、绝不碰任何真实 profile。
 *
 * <p><b>约定</b>：reload 必须读取「刚刚写入磁盘的」配置；实现方不得缓存上一次的配置内容，也不得把
 * URL/lease id 写进日志或异常（本端口拿不到 URL）。失败直接抛 {@link RuntimeException}，由控制面决定
 * 回滚与对账。
 *
 * <p><b>返回值</b>：成功时给出 {@link GatewayMcpReloadReceipt}（网关加载了哪一版、是否被合并）。
 * 「RPC 返回了」不等于「重载了」——例如 {@code confirm_required} 就是一个成功的 RPC 但没重载，必须抛
 * {@link GatewayReloadException} 而不是返回回执。
 */
interface GatewayReloader {

    /**
     * 触发网关 reload。
     *
     * @return 成功回执（含网关实际的 {@code loaded_rev}）
     * @throws GatewayReloadException 网关明确拒绝或回包不足以证明成功（例如 {@code confirm_required}）
     * @throws RuntimeException 其它失败；控制面按「本轮未生效」回滚
     */
    GatewayMcpReloadReceipt reload();
}
