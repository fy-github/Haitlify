package com.haitlify.aitest.platform.assistant.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * <b>网关级单一 MCP 许可的原语语义</b>：单持有者、同一轮重试、<b>只放自己的</b>、释放幂等。
 *
 * <p>为什么这些要有独立用例（而不是只靠服务层的并发用例）：服务层的每条收尾路径都只释放<b>自己</b>
 * 那一轮，所以「释放时忘了比较持有者」在那里看不出差别——它只在「别人的轮次走到释放」时才毁事：
 * 非持有者的清理把持有者的许可放掉，两轮于是可以同时改写同一个网关条目（能力错配）。去掉
 * {@code release} 里的 {@code messageId.equals(owner)} 比较，本类第一、二条用例立刻变红
 * （第三条用例的「非持有者释放之后再取」也会变红）。
 *
 * <p>纯单元用例：不启 Spring、不碰数据库、不碰网关。
 */
class GatewayMcpPermitTest {

    @Test
    void onlyOneTurnHoldsThePermitAtATime() {
        GatewayMcpPermit permit = new GatewayMcpPermit();

        assertThat(permit.acquire("turn-a")).as("空闲时第一个调用者取得许可").isTrue();
        assertThat(permit.heldBy("turn-a")).isTrue();
        assertThat(permit.acquire("turn-b")).as("别人持有期间第二个轮次被拒绝").isFalse();
        assertThat(permit.heldBy("turn-a")).as("被拒的轮次不得顶掉持有者").isTrue();
        assertThat(permit.heldBy("turn-b")).isFalse();
    }

    @Test
    void aRetryFromTheSameTurnKeepsThePermitItAlreadyHolds() {
        GatewayMcpPermit permit = new GatewayMcpPermit();

        assertThat(permit.acquire("turn-a")).isTrue();
        assertThat(permit.acquire("turn-a")).as("同一轮重复获取（重放/重试）视为成功").isTrue();
        assertThat(permit.acquire("turn-b")).as("重试不改变「只有一轮持有」").isFalse();
        assertThat(permit.heldBy("turn-a")).isTrue();
    }

    @Test
    void aNonHolderCannotReleaseSomebodyElsesPermit() {
        GatewayMcpPermit permit = new GatewayMcpPermit();
        assertThat(permit.acquire("turn-a")).isTrue();

        // 这正是「忘了比较持有者」会造成的破坏：一次属于 B 的清理放掉了 A 的许可。
        permit.release("turn-b");

        assertThat(permit.heldBy("turn-a")).as("非持有者的释放是空操作").isTrue();
        assertThat(permit.acquire("turn-c")).as("A 仍然持有，第三个轮次依然进不来").isFalse();
    }

    @Test
    void releaseIsIdempotentAndOnlyFreesTheHolder() {
        GatewayMcpPermit permit = new GatewayMcpPermit();
        assertThat(permit.acquire("turn-a")).isTrue();

        permit.release("turn-a");
        permit.release("turn-a");

        assertThat(permit.heldBy("turn-a")).isFalse();
        assertThat(permit.acquire("turn-b")).as("重复释放无害：许可已经空闲").isTrue();
        permit.release("turn-a");
        assertThat(permit.heldBy("turn-b")).as("持有者换成 B 之后，A 的迟到释放不得放掉 B 的许可").isTrue();
    }

    @Test
    void aMissingTurnIdentityIsRejectedInsteadOfBecomingASharedHolder() {
        GatewayMcpPermit permit = new GatewayMcpPermit();

        assertThatThrownBy(() -> permit.acquire(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(permit.acquire("turn-a")).as("失败的 acquire 不占用许可").isTrue();
    }
}
