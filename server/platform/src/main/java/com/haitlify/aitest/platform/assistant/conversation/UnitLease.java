package com.haitlify.aitest.platform.assistant.conversation;

/**
 * <b>一轮在飞租约</b>：单元层（{@link ExecutionUnit}）的引用计数句柄，<b>与 MCP 许可无关</b>。
 *
 * <p>为什么不能拿 MCP 许可当「有一轮在飞」的标记：许可是<b>条件性</b>的——{@code configure-gateway=false}
 * （默认值）时服务层一次都不取（{@link GatewayMcpPermit} 根本没有被争用），于是「本单元有一轮正在跑」
 * 这件事在池里<b>没有任何痕迹</b>。空闲回收只看 {@code lastUsedAt}，就会把一个正在等终态的单元连同子进程
 * 一起摘掉，那一轮只能落 {@code UNKNOWN}。租约把这个事实下沉到单元自己身上，与部署开关无关。
 *
 * <p>为什么必须在 {@link GatewayUnits#acquire(ExecutionUnitKey)} <b>返回之前</b>就生效：
 * {@code lastUsedAt} 只是一个时间戳，能不能挡住回收取决于 {@code idle-ttl} 相对于「取到单元 → 取许可 →
 * 武装 → 配置 → 派发」这段耗时的大小。TTL 稍短（或机器稍慢）就会在这段窗口里被摘牌——那不是配置错误，
 * 而是一个真实存在的时序洞。租约让「这一段正在被使用」成为<b>计数事实</b>而不是时间推断。
 *
 * <p><b>释放是幂等的</b>：同一个句柄被 release 多次只减一次计数（终态、异常出口、关闭清扫都会走到收尾，
 * 任何一个漏写或多写都不能让计数漂移）。这也让「异常路径也释放租约」可以用 {@code finally} 表达，
 * 而不需要逐路径记得补一句。
 */
interface UnitLease extends AutoCloseable {

    /**
     * 没有单元层的租约（单进程装配 {@code SingleGatewayUnits} 与既有测试形状）：释放是空操作。
     *
     * <p>它不是「降级」：单进程装配里没有空闲回收线程，也就没有「谁会被摘牌」这个问题。
     */
    UnitLease NONE = new UnitLease() {

        @Override
        public void release() {
            // 单进程装配：无单元可回收，无需计数。
        }

        @Override
        public boolean released() {
            return true;
        }

        @Override
        public String toString() {
            return "UnitLease[none]";
        }
    };

    /** 归还这一份在飞租约（<b>幂等</b>：重复调用只减一次计数）。 */
    void release();

    /** @return 同一个句柄是否已经释放过（只读观察；用例断言「终态后已归还」用它）。 */
    boolean released();

    @Override
    default void close() {
        release();
    }
}
