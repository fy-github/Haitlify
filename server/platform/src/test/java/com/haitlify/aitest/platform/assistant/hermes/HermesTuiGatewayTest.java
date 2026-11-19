package com.haitlify.aitest.platform.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

/**
 * stdio JSON-RPC 客户端的真实进程测试：用 node 假网关（与 A0 探针同协议），不联网、不开容器。
 * Hermes 本体不参与——这里验的是「客户端是否按回包判定，而不是按写入成功判定」。
 */
class HermesTuiGatewayTest {

    private HermesTuiGateway gateway;
    private Path script;

    @BeforeEach
    void setUp() throws Exception {
        script = Path.of(HermesTuiGatewayTest.class.getResource("/assistant/fake-tui-gateway.mjs").toURI());
        Assumptions.assumeTrue(commandAvailable("node"), "缺少 node，跳过 stdio 网关测试");
        gateway = new HermesTuiGateway("node " + script, System.getProperty("java.io.tmpdir"), 10, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        if (gateway != null) {
            gateway.close();
        }
    }

    private static boolean commandAvailable(String command) {
        try {
            return new ProcessBuilder(command, "--version").start().waitFor() == 0;
        } catch (Exception ex) {
            return false;
        }
    }

    @Test
    void createsSessionAndSubmitsPrompt() throws Exception {
        assertThat(gateway.available()).isTrue();
        HermesGateway.Session session = gateway.createSession(Files.createTempDirectory("hermes-cwd").toString());
        assertThat(session.sessionId()).isEqualTo("stored-abc-123");
        assertThat(session.rawJson()).contains("stored-abc-123");

        HermesGateway.Turn turn = gateway.submitPrompt(session.sessionId(), "请规划这一轮");
        assertThat(turn.turnRef()).isEqualTo("turn-1");
    }

    @Test
    void interruptRequiresReceipt() {
        HermesGateway.Session session = gateway.createSession(".");
        assertThat(gateway.interrupt(session.sessionId())).isTrue();
        // 未知会话：网关返回错误 ⇒ 视为「未受理」，不推断已撤销。
        assertThat(gateway.interrupt("no-such-session")).isFalse();
    }

    @Test
    void unknownMethodIsRejectedNotIgnored() {
        gateway.available();
        assertThatThrownBy(() -> gateway.submitPrompt("no-such-session", "x"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("拒绝");
    }

    @Test
    void unconfiguredGatewayIsUnavailable() {
        HermesTuiGateway unconfigured = new HermesTuiGateway("", null, 5, new ObjectMapper());
        assertThat(unconfigured.available()).isFalse();
        assertThatThrownBy(() -> unconfigured.createSession("."))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("不可用");
    }
}
