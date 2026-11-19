package com.haitlify.aitest.platform.assistant.conversation;

/**
 * 一次<b>成功</b> reload 的回执：网关说它到底加载了哪一版配置。
 *
 * <p>为什么需要它：{@code reload.mcp} 在开启确认策略时也会「成功返回」，但那是
 * {@code {status: "confirm_required"}}，并没有重载任何东西（见 {@link GatewayReloadException.Code#CONFIRM_REQUIRED}）。
 * 唯一的成功证据是 {@code status == "reloaded"} 加上一个非空的 {@code loaded_rev}——它是网关在
 * shutdown + rediscover 之后对 {@code mcp_servers}/{@code mcp}/{@code tools} 求得的内容哈希
 * （{@code tui_gateway/server.py:_compute_mcp_rev}）。有了它，「回复了」和「加载的确实是刚写进去的那一版」
 * 才是两个可以分开陈述的事实。
 *
 * <p>{@code coalesced}：网关把这次请求合并到一次已经完成的 reload 上（同一 rev 的重复请求）。合并是成功，
 * 但调用方不该把它当成「刚刚又重载了一次」——对账时要按「已加载的 rev」判断。
 *
 * <p>回执里不含 URL / lease id / token；{@code loaded_rev} 是配置内容哈希，不是可用凭据。
 *
 * <p>注意与 {@link GatewayMcpReceipt} 的区别：那个端口是「网关视角下条目当前是什么」的回读，
 * 这个是「reload 本身成功了吗、加载了哪一版」的结果。
 *
 * @param loadedRev 网关加载的配置版本（pin 的 {@code loaded_rev}，12 位十六进制），必须非空
 * @param coalesced 是否被合并到一次已完成的 reload
 */
record GatewayMcpReloadReceipt(String loadedRev, boolean coalesced) {

    GatewayMcpReloadReceipt {
        if (loadedRev == null || loadedRev.isBlank()) {
            throw new IllegalArgumentException("loaded revision required");
        }
        if (loadedRev.length() > 64 || !loadedRev.chars().allMatch(TcpGatewayReloader::isRevisionCharacter)) {
            throw new IllegalArgumentException("loaded revision must be a short hex digest");
        }
    }
}
