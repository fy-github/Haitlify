package com.haitlify.aitest.platform.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * stdio 网关的管理面 RPC（{@code rpc(method, params, timeout)}）：契约与
 * {@code HermesTcpGateway::rpc} 相同——返回<b>原始</b>回包（JSON-RPC {@code error} 不抛出）、传输失败抛异常。
 *
 * <p>用 node 假网关（与 {@link HermesTuiGatewayTest} 同一个桩，命令行开关切换 reload.mcp 的回包形状），
 * <b>不启动真实 Hermes</b>、不碰任何真实 profile、不调模型。这里钉的是 stdio 版独有的风险：
 * 每次调用自己的预算、超时/EOF 精确失败并清空 pending、慢 reload 不阻塞其它在途调用。
 */
class HermesTuiGatewayRpcTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String KNOWN_SESSION = "stored-abc-123";

    private Path script;
    private Path cwd;
    private HermesTuiGateway gateway;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(commandAvailable("node"), "缺少 node，跳过 stdio 网关 RPC 测试");
        script = Path.of(HermesTuiGatewayRpcTest.class.getResource("/assistant/fake-tui-gateway.mjs").toURI());
        cwd = temporaryDirectory();
    }

    @AfterEach
    void tearDown() {
        if (gateway != null) {
            gateway.close();
        }
    }

    /** reload.mcp 走 stdio：拿回原始回包，{@code status=reloaded} 与 {@code loaded_rev} 都在 result 里。 */
    @Test
    void rpcReturnsTheRawReloadReceipt() {
        gateway = stdio("reloaded");

        JsonNode response = gateway.rpc("reload.mcp", confirmParams(), Duration.ofSeconds(10));

        assertThat(response.path("error").isMissingNode() || response.path("error").isNull())
            .as("成功回包不该带 error").isTrue();
        assertThat(response.path("result").path("status").asString()).isEqualTo("reloaded");
        assertThat(response.path("result").path("loaded_rev").asString()).isEqualTo("0123456789ab");
        assertThat(gateway.inFlightRequests()).isZero();
    }

    /** JSON-RPC error 是<b>原始回包</b>，不是异常：只有传输坏了才抛。 */
    @Test
    void rpcErrorFrameIsReturnedNotThrown() {
        gateway = stdio("error");

        JsonNode response = gateway.rpc("reload.mcp", confirmParams(), Duration.ofSeconds(10));

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32603);
        assertThat(response.path("result").isMissingNode()).isTrue();
    }

    /** 超时：精确失败、pending 清空，且健康子进程不被一次慢 reload 打成不可用。 */
    @Test
    void timeoutFailsTheCallAndClearsPending() {
        gateway = stdio("silent");

        assertThatThrownBy(() -> gateway.rpc("reload.mcp", confirmParams(), Duration.ofMillis(300)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("未回包")
            .hasMessageContaining("reload.mcp");

        assertThat(gateway.inFlightRequests()).as("超时后 pending 必须清空，否则慢请求会永久占表").isZero();
        // 超时只是「这次没等到回执」，不等于子进程坏了：后续调用必须照常可用。
        assertThat(gateway.available()).isTrue();
        assertThat(gateway.createSession(".").sessionId()).isEqualTo(KNOWN_SESSION);
        assertThat(gateway.inFlightRequests()).isZero();
    }

    /** 子进程退出（EOF）：在途调用精确失败，pending 清空。 */
    @Test
    void processExitFailsTheCallAndClearsPending() {
        gateway = stdio("exit");

        assertThatThrownBy(() -> gateway.rpc("reload.mcp", confirmParams(), Duration.ofSeconds(10)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("reload.mcp");

        assertThat(gateway.inFlightRequests()).isZero();
    }

    /** 慢 reload（2s）在途时，另一条 prompt.submit 风格调用必须立刻拿到回执，不被锁或 pending 挡住。 */
    @Test
    void slowReloadDoesNotBlockOtherCalls() throws Exception {
        gateway = stdio("reloaded", 2000);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<JsonNode> reload = worker.submit(
                () -> gateway.rpc("reload.mcp", confirmParams(), Duration.ofSeconds(15)));
            // 等 reload 真的发出去（在途数 1），避免误测成「顺序执行」。
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (gateway.inFlightRequests() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(gateway.inFlightRequests()).as("reload 应仍在途").isEqualTo(1);

            long startedAt = System.nanoTime();
            HermesGateway.Turn turn = gateway.submitPrompt(KNOWN_SESSION, "请规划这一轮");
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

            assertThat(turn.turnRef()).isEqualTo("turn-1");
            assertThat(elapsedMillis)
                .as("慢 reload 在途时其它调用不得被阻塞（实测 %d ms）", elapsedMillis)
                .isLessThan(1200);
            assertThat(gateway.inFlightRequests()).as("慢 reload 仍在途").isEqualTo(1);

            assertThat(reload.get().path("result").path("status").asString()).isEqualTo("reloaded");
        } finally {
            worker.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- 夹具

    private HermesTuiGateway stdio(String reloadMode) {
        return stdio(reloadMode, 0);
    }

    private HermesTuiGateway stdio(String reloadMode, int slowMillis) {
        String command = "node " + script + " --reload=" + reloadMode
            + (slowMillis > 0 ? " --slow-ms=" + slowMillis : "");
        return new HermesTuiGateway(command, cwd.toString(), 10, MAPPER);
    }

    private static ObjectNode confirmParams() {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("confirm", true);
        return params;
    }

    private static boolean commandAvailable(String command) {
        try {
            return new ProcessBuilder(command, "--version").start().waitFor() == 0;
        } catch (Exception ex) {
            return false;
        }
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("hermes-stdio-rpc-cwd");
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
