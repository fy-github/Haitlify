package com.haitlify.aitest.platform.assistant.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link GatewayMcpLeaseConfigurator} 的网关侧时序：<b>先写条目、再 reload</b>。
 *
 * <p>这是协议要求而不是实现口味：Hermes 只在新会话建立时读 MCP 条目。先 reload 再写，重载的是旧配置，
 * 下一个模型会话仍然看到上一轮的（已撤销）URL，而平台会以为配置成功。写失败时不得 reload——
 * 那只把旧配置再加载一遍，把失败掩盖成「已生效」。
 *
 * <p>本用例完全在进程内：{@link FakeGateway} 只记录写过什么、reload 了几次，不接触任何真实网关。
 */
class GatewayMcpLeaseConfiguratorTest {

    private static final String LEASE_URL = "https://assistant.internal/mcp-lease/abc";

    @Test
    void writesTheEntryFirstAndReloadsAfterwards() {
        FakeGateway gateway = new FakeGateway();
        new GatewayMcpLeaseConfigurator(gateway).apply(LEASE_URL);

        assertThat(gateway.events).containsExactly("write:" + LEASE_URL, "reload");
        assertThat(gateway.entry).isEqualTo(LEASE_URL);
    }

    /** 本轮无端点：必须清除条目再 reload（不是「什么都不做」）。 */
    @Test
    void clearsTheEntryWhenTheTurnHasNoEndpoint() {
        FakeGateway gateway = new FakeGateway();
        gateway.entry = "https://assistant.internal/mcp-lease/previous";

        new GatewayMcpLeaseConfigurator(gateway).apply(null);

        assertThat(gateway.events).containsExactly("write:<none>", "reload");
        assertThat(gateway.entry).isNull();
    }

    /** 写失败就不 reload：否则等于把旧配置再加载一遍并假装新条目已生效。 */
    @Test
    void doesNotReloadWhenTheWriteFails() {
        FakeGateway gateway = new FakeGateway();
        gateway.failWrite = true;

        assertThatThrownBy(() -> new GatewayMcpLeaseConfigurator(gateway).apply(LEASE_URL))
            .isInstanceOf(IllegalStateException.class);
        assertThat(gateway.events).containsExactly("write:" + LEASE_URL);
        assertThat(gateway.reloads).isZero();
    }

    /** reload 失败就是失败：异常必须冒出去，由调用方按「本轮无端点」处理。 */
    @Test
    void propagatesAReloadFailure() {
        FakeGateway gateway = new FakeGateway();
        gateway.failReload = true;

        assertThatThrownBy(() -> new GatewayMcpLeaseConfigurator(gateway).apply(LEASE_URL))
            .isInstanceOf(IllegalStateException.class);
        assertThat(gateway.events).containsExactly("write:" + LEASE_URL, "reload");
    }

    private static final class FakeGateway implements GatewayMcpControl {

        private final List<String> events = new ArrayList<>();
        private String entry;
        private int reloads;
        private boolean failWrite;
        private boolean failReload;

        @Override
        public void writeServerEntry(String leaseEndpoint) {
            events.add(leaseEndpoint == null ? "write:<none>" : "write:" + leaseEndpoint);
            if (failWrite) {
                throw new IllegalStateException("write failed");
            }
            entry = leaseEndpoint;
        }

        @Override
        public void reload() {
            events.add("reload");
            reloads++;
            if (failReload) {
                throw new IllegalStateException("reload failed");
            }
        }
    }
}
