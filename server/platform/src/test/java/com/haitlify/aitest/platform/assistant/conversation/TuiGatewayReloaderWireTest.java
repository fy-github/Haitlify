package com.haitlify.aitest.platform.assistant.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import com.haitlify.aitest.platform.assistant.conversation.GatewayMcpControlException.Reason;
import com.haitlify.aitest.platform.assistant.hermes.HermesTuiGateway;

import tools.jackson.databind.ObjectMapper;

/**
 * {@link TcpGatewayReloader} 接 <b>stdio</b> 网关：真实 {@link HermesTuiGateway}（真子进程、真换行分隔帧、
 * 真 pending 表），证明控制面的 reload 在 {@code transport=stdio} 下同样有落点，而不是第二套协议。
 *
 * <p>对应 {@link TcpGatewayReloaderWireTest}（TCP 版）：判定逻辑完全共用，差的只是传输。
 * 桩是 node 假 tui_gateway（{@code src/test/resources/assistant/fake-tui-gateway.mjs}，用命令行开关切换
 * reload.mcp 的回包形状）；不启动真实 Hermes、不碰任何真实 profile、不调模型。
 */
class TuiGatewayReloaderWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PREVIOUS = "https://assistant.internal/mcp-lease/previousLease01";
    private static final String CURRENT = "https://assistant.internal/mcp-lease/currentLease02";

    @TempDir
    Path dir;

    private Path script;
    private HermesTuiGateway gateway;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(commandAvailable("node"), "缺少 node，跳过 stdio reload 用例");
        script = Path.of(TuiGatewayReloaderWireTest.class.getResource("/assistant/fake-tui-gateway.mjs").toURI());
    }

    @AfterEach
    void tearDown() {
        if (gateway != null) {
            gateway.close();
        }
    }

    /** stdio 下 reload.mcp 拿到 status=reloaded 与 loaded_rev：回执照实转述。 */
    @Test
    void reloadsOverStdioAndReturnsTheLoadedRevision() {
        gateway = stdio("reloaded");

        GatewayMcpReloadReceipt receipt = reloader(Duration.ofSeconds(10)).reload();

        assertThat(receipt.loadedRev()).isEqualTo("0123456789ab");
        assertThat(receipt.coalesced()).isFalse();
        assertThat(gateway.inFlightRequests()).isZero();
    }

    /** 成功的 RPC 但没重载：必须是明确失败，不是「已配置」。 */
    @Test
    void confirmRequiredOverStdioIsAFailureNotAReload() {
        gateway = stdio("confirm_required");

        assertThatThrownBy(() -> reloader(Duration.ofSeconds(10)).reload())
            .isInstanceOfSatisfying(GatewayReloadException.class, error -> assertThat(error.code())
                .isEqualTo(GatewayReloadException.Code.CONFIRM_REQUIRED));
    }

    /** JSON-RPC error 帧 → RPC_ERROR（明确拒绝，与传输坏了区分开）。 */
    @Test
    void rpcErrorFrameOverStdioIsAnRpcError() {
        gateway = stdio("error");

        assertThatThrownBy(() -> reloader(Duration.ofSeconds(10)).reload())
            .isInstanceOfSatisfying(GatewayReloadException.class, error -> assertThat(error.code())
                .isEqualTo(GatewayReloadException.Code.RPC_ERROR));
    }

    /** 不回包 → 超时 → TRANSPORT_FAILED（reload 结果未知，不能当成功）。 */
    @Test
    void timeoutOverStdioIsATransportFailure() {
        gateway = stdio("silent");

        assertThatThrownBy(() -> reloader(Duration.ofMillis(400)).reload())
            .isInstanceOfSatisfying(GatewayReloadException.class, error -> assertThat(error.code())
                .isEqualTo(GatewayReloadException.Code.TRANSPORT_FAILED));
        assertThat(gateway.inFlightRequests()).as("超时后 pending 必须清空").isZero();
    }

    /** 整条链路（stdio）：写条目 → reload 成功 → 读回仍是本轮 URL。 */
    @Test
    void theControlWritesAndReloadsOverStdio() throws IOException {
        Path config = configFile();
        gateway = stdio("reloaded");
        ConfigFileGatewayMcpControl control = new ConfigFileGatewayMcpControl(config, "haitlify",
            ConfigFileGatewayMcpControl.DEFAULT_PARENT_PATH, reloader(Duration.ofSeconds(10)), null);

        control.writeServerEntry(CURRENT);
        control.reload();

        assertThat(entryOf(config)).isEqualTo(CURRENT);
        assertThat(gateway.inFlightRequests()).isZero();
    }

    // ---------------------------------------------------------------- 夹具

    private HermesTuiGateway stdio(String reloadMode) {
        return new HermesTuiGateway("node " + script + " --reload=" + reloadMode, dir.toString(), 10, MAPPER);
    }

    private TcpGatewayReloader reloader(Duration timeout) {
        return new TcpGatewayReloader(gateway::rpc, MAPPER, timeout);
    }

    private Path configFile() throws IOException {
        Path config = dir.resolve("config.yaml");
        Files.writeString(config, "# 夹具\n"
            + "mcp_servers:\n"
            + "  mcp-atlassian:\n"
            + "    command: uvx\n"
            + "  haitlify:\n"
            + "    url: '" + PREVIOUS + "'\n"
            + "memory:\n"
            + "  enabled: true\n", StandardCharsets.UTF_8);
        return config;
    }

    private static String entryOf(Path file) throws IOException {
        Object root = new Yaml().load(Files.readString(file, StandardCharsets.UTF_8));
        Object servers = ((Map<?, ?>) root).get("mcp_servers");
        Object entry = ((Map<?, ?>) servers).get("haitlify");
        return entry == null ? null : (String) ((Map<?, ?>) entry).get("url");
    }

    private static boolean commandAvailable(String command) {
        try {
            return new ProcessBuilder(command, "--version").start().waitFor() == 0;
        } catch (IOException | InterruptedException ex) {
            return false;
        }
    }
}
