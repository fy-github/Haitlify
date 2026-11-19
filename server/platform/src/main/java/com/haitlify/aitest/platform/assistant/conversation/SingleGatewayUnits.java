package com.haitlify.aitest.platform.assistant.conversation;

import java.util.List;

import com.haitlify.aitest.platform.assistant.hermes.HermesGateway;
import com.haitlify.aitest.platform.assistant.runtime.HermesConversationService;

/**
 * {@link GatewayUnits} 的<b>单进程</b>实现：整个 JVM 只有一个网关、一个条目、一个许可（即单元化之前的形状）。
 *
 * <p>存在的理由有两个，都不是「过渡代码」：
 * <ol>
 *   <li><b>默认部署不变</b>：没有配置执行单元池时，对话链路的行为、时序与失败语义必须与单元化之前
 *       逐条一致（包括 {@code MCP_PERMIT_BUSY} 的拒绝与终态清条目）。这个实现把那种形状原样包成
 *       「一个键都能解析到同一个单元」；</li>
 *   <li><b>键仍然被校验</b>：即使只有一个单元，也不接受空键——一个没有身份的轮次不得进入派发路径。</li>
 * </ol>
 *
 * <p>它<b>不</b>假装支持隔离：{@link #liveUnits()} 恒为 1，{@link #keys()} 只回报最近一次解析用过的键
 * （没有解析过时为空）。池化装配见 {@link ExecutionUnitPool}。
 */
final class SingleGatewayUnits implements GatewayUnits {

    private final HermesGateway gateway;
    private final HermesConversationService conversations;
    private final GatewayMcpConfigurator configurator;
    private final GatewayMcpPermit permit;
    /** 最近一次解析用的键（只读观察用；单进程装配里它不参与任何判定）。 */
    private volatile ExecutionUnitKey lastKey;

    SingleGatewayUnits(HermesGateway gateway, HermesConversationService conversations,
                       GatewayMcpConfigurator configurator, GatewayMcpPermit permit) {
        if (gateway == null || conversations == null) {
            throw new IllegalArgumentException("单进程装配必须给出 gateway 与 conversations");
        }
        this.gateway = gateway;
        this.conversations = conversations;
        this.configurator = configurator;
        this.permit = permit;
    }

    @Override
    public boolean available() {
        return gateway.available();
    }

    @Override
    public TurnUnit acquire(ExecutionUnitKey key) {
        if (key == null) {
            throw new IllegalArgumentException("执行单元键必填");
        }
        lastKey = key;
        // 单进程装配没有空闲回收线程，也就没有「单元被摘牌」这件事：在飞租约是 UnitLease.NONE（空操作）。
        // 服务层的归还路径照旧执行，因此两条装配的收尾代码逐行相同。
        return new TurnUnit(key, gateway, conversations, configurator, permit, UnitLease.NONE);
    }

    @Override
    public boolean holdsPermit(String messageId) {
        return permit != null && permit.heldBy(messageId);
    }

    @Override
    public int liveUnits() {
        return 1;
    }

    @Override
    public List<ExecutionUnitKey> keys() {
        ExecutionUnitKey key = lastKey;
        return key == null ? List.of() : List.of(key);
    }
}
