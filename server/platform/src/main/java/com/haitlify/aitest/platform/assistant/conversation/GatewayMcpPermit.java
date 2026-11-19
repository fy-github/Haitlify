package com.haitlify.aitest.platform.assistant.conversation;

/**
 * <b>网关级单一 MCP 许可</b>：整个 JVM 里同一时刻最多只有一轮持有它。
 *
 * <p>为什么需要它：网关只有一个 MCP 服务器条目，而条目在<b>会话创建</b>的那一刻被绑定到那个会话。
 * 两次改写交错（A 配置 → B 配置 → A 派发）会让 A 的会话拿到为 B 那一轮铸的 URL——那不是「降级成
 * 401」，而是把对 B 的 generation 的能力交给了 A 的会话。因此「武装 + 配置 + 派发」这一段必须跨会话
 * 互斥，并且一直持有到本轮终态。
 *
 * <p>三条性质就是本类的全部语义，缺一条都会把能力错配放回来：
 * <ol>
 *   <li><b>单持有者</b>：空闲时第一个 {@link #acquire(String)} 成功并登记持有者，其余一律失败；</li>
 *   <li><b>同一轮重试视为成功</b>：重放/重试走的是同一个消息 id，不能因此把自己挡在门外；</li>
 *   <li><b>只放自己的 + 幂等</b>：{@link #release(String)} 只在调用者<b>就是</b>当前持有者时才清空，
 *       且重复调用无害。非持有者的清理释放掉别人的许可，等于让两轮同时改同一个条目。</li>
 * </ol>
 *
 * <p>刻意不是静态单例、也不复用提交锁：提交锁是按会话分段的（{@code AssistantConversationService}
 * 的 64 段），而这里必须<b>跨会话</b>互斥；把它做成实例也让「许可只在持有它的那个服务实例内有效」
 * 这件事在类型上可见。{@code acquire} 失败的处理是调用方的事（服务层据此显式拒绝本轮），
 * 本类不含任何等待/排队语义。
 */
final class GatewayMcpPermit {

    private final Object lock = new Object();
    /** 当前持有者的轮次身份（消息 id）；只在 {@link #lock} 内读写。{@code null} = 空闲。 */
    private String owner;

    /**
     * 尝试取得许可。
     *
     * @param messageId 本轮消息 id（轮次身份，不是会话 id：同一会话的两轮也不得同时持有）
     * @return {@code true} = 调用者现在持有许可（空闲时新取得，或它本来就是持有者）；
     *         {@code false} = 别人持有，调用者<b>不得</b>武装/配置/派发
     */
    boolean acquire(String messageId) {
        if (messageId == null) {
            throw new IllegalArgumentException("许可的持有者必须是本轮消息 id");
        }
        synchronized (lock) {
            if (owner == null) {
                owner = messageId;
                return true;
            }
            return owner.equals(messageId);
        }
    }

    /**
     * 释放许可（<b>只放自己的</b>，幂等）。非持有者调用是空操作——这不是宽容，而是必须：
     * 任何一轮的收尾路径都会走到这里，若它们能清掉持有者的许可，网关条目就会被两轮同时改写。
     *
     * @param messageId 调用者认为自己持有的轮次身份；{@code null} 时不做任何事
     */
    void release(String messageId) {
        if (messageId == null) {
            return;
        }
        synchronized (lock) {
            if (messageId.equals(owner)) {
                owner = null;
            }
        }
    }

    /** @return 该轮次当前是否持有许可（只读观察；终态清条目的前置判断用它）。 */
    boolean heldBy(String messageId) {
        if (messageId == null) {
            return false;
        }
        synchronized (lock) {
            return messageId.equals(owner);
        }
    }

    /**
     * 当前是否有任意一轮持有许可（只读观察）。空闲回收用它：一个仍有轮次在等终态的单元不得被回收，
     * 否则那段等待会连同进程一起消失（账本只能落 UNKNOWN）。
     */
    boolean held() {
        synchronized (lock) {
            return owner != null;
        }
    }

    /**
     * <b>单元销毁时的唯一出口</b>：无论谁持有，都清空。
     *
     * <p>为什么需要它：池回收/关闭单元时，单元进程、配置文件与许可一起消失；若许可留在某个已不存在
     * 单元的轮次名下，那只是内存里的一个孤儿，但它会让「回收后许可已释放」无法被证明。本方法<b>只</b>在
     * {@link ExecutionUnit#close()} 一类「单元整个消失」的路径上调用——正常轮次仍然只能
     * {@link #release(String)} 自己的许可。
     */
    void releaseAll() {
        synchronized (lock) {
            owner = null;
        }
    }
}
