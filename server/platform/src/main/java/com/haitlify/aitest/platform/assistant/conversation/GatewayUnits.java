package com.haitlify.aitest.platform.assistant.conversation;

import java.util.List;

/**
 * 端口：<b>为一轮对话解析它自己的网关执行单元</b>。
 *
 * <p>为什么需要这一层：单元化的目标是把「一个全局 MCP 条目 + 一个全局许可」换成「每个
 * {@code (tenant, project, actor)} 一个独立的网关子进程 + 独立配置文件 + 独立许可」。对话服务不应该知道
 * 那是池、是单进程还是测试桩，它只需要问一句「这一轮的单元是谁」，然后<b>只用这个单元</b>完成
 * 武装 → 配置 → 派发。
 *
 * <p><b>约定（实现方必须遵守）</b>：
 * <ol>
 *   <li>{@link #acquire(ExecutionUnitKey)} <b>要么给出属于该键的单元，要么抛
 *       {@link ExecutionUnitUnavailableException}</b>；绝不返回另一个键的单元，也绝不返回「降级成共享单元」
 *       的东西。拿不到单元的那一轮必须是确定性的失败（零 wire 副作用）；</li>
 *   <li>同一个键在池里复用同一个单元：配置文件、许可、会话绑定表都是该单元私有的；</li>
 *   <li>单元返回的 {@link TurnUnit#conversations()}、{@link TurnUnit#gateway()} 必须属于同一个子进程——
 *       混用两个单元的会话服务与网关会让会话 id 在错误的进程上被解析。</li>
 *   <li><b>在飞租约在 {@link #acquire(ExecutionUnitKey)} 返回之前就已生效</b>（{@link TurnUnit#lease()}），
 *       并且在<b>终态、失败、异常</b>出口由调用方归还。它是「本单元有一轮正在跑」的唯一权威标记：
 *       实现方不得用「MCP 许可是否被持有」代替它——许可是条件性的（{@code configure-gateway=false} 时
 *       根本不取），而以它为准的回收会把在飞单元连同子进程一起拆掉。</li>
 * </ol>
 *
 * <p>单进程装配（{@link SingleGatewayUnits}）是本端口的退化实现：永远返回同一个单元，键只用做校验。
 * 它让既有链路与既有用例保持逐字节相同的行为，同时把「解析单元」这件事提前显式化。
 */
interface GatewayUnits {

    /**
     * 一轮的单元视图。<b>四个句柄必须来自同一个单元</b>。
     *
     * @param key          解析用的键（只读观察）
     * @param gateway      该单元的网关客户端（可用性判定与传输）
     * @param conversations 该单元的会话适配（绑定表是进程内状态，不能跨单元共用）
     * @param configurator 该单元的 MCP 条目配置器；{@code null} = 本部署不写条目（开关关闭）
     * @param permit       该单元的 MCP 许可；{@code null} = 不写条目时没有共享资源可争
     * @param lease        这一轮的<b>在飞租约</b>（单元层引用计数，与许可无关）；在 {@link #acquire} 返回
     *                     <b>之前</b>就已生效，调用方在终态/失败/异常出口必须归还（幂等）。单进程装配下是
     *                     {@link UnitLease#NONE}
     */
    record TurnUnit(ExecutionUnitKey key, com.haitlify.aitest.platform.assistant.hermes.HermesGateway gateway,
                    com.haitlify.aitest.platform.assistant.runtime.HermesConversationService conversations,
                    GatewayMcpConfigurator configurator, GatewayMcpPermit permit, UnitLease lease) {

        public TurnUnit {
            if (key == null || gateway == null || conversations == null) {
                throw new IllegalArgumentException("执行单元视图必须带 key/gateway/conversations");
            }
            if (lease == null) {
                throw new IllegalArgumentException("执行单元视图必须带在飞租约（单进程装配用 UnitLease.NONE）");
            }
        }

        /**
         * 没有单元层保护时的视图（单进程装配与既有用例的形状）：在飞租约为 {@link UnitLease#NONE}。
         *
         * <p>为什么不给一个默认的「空租约实现」而是显式常量：单进程装配确实没有可回收的单元，
         * 而池化装配漏传租约是一个必须被编译期/构造期挡住的事故。
         */
        TurnUnit(ExecutionUnitKey key, com.haitlify.aitest.platform.assistant.hermes.HermesGateway gateway,
                 com.haitlify.aitest.platform.assistant.runtime.HermesConversationService conversations,
                 GatewayMcpConfigurator configurator, GatewayMcpPermit permit) {
            this(key, gateway, conversations, configurator, permit, UnitLease.NONE);
        }
    }

    /**
     * 本部署是否有可用的执行单元能力（进程未关闭、基准 profile 可用）。
     *
     * <p>这是<b>便宜的</b>前置判定，用于「一行账本都不写就拒绝」的那条路径；它不保证
     * {@link #acquire(ExecutionUnitKey)} 一定成功（池可能刚好满），后者才是权威。
     */
    boolean available();

    /**
     * 解析（必要时创建）该键的单元。
     *
     * @throws ExecutionUnitUnavailableException 池满、基准 profile 不可用、子进程启动失败或池已关闭；
     *         调用方必须把本轮记为确定性失败（未触达 wire），不得回退到共享单元或旧 URL
     */
    TurnUnit acquire(ExecutionUnitKey key);

    /**
     * 该轮次当前是否持有<b>某个</b>单元的 MCP 许可（只读观察，用于终态清条目的前置判断与用例断言）。
     *
     * <p>单进程实现直接问那一个许可；池实现扫一遍在册单元。它不改变任何状态。
     */
    boolean holdsPermit(String messageId);

    /** 在册单元数（只读观察：池容量、回收与不泄漏的证据）。 */
    int liveUnits();

    /** 在册单元的键（只读观察；顺序不承诺）。 */
    List<ExecutionUnitKey> keys();
}
