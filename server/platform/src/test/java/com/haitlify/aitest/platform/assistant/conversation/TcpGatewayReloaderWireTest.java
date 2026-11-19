package com.haitlify.aitest.platform.assistant.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import com.haitlify.aitest.platform.assistant.conversation.GatewayMcpControlException.Reason;
import com.haitlify.aitest.platform.assistant.hermes.HermesTcpGateway;
import com.haitlify.aitest.platform.assistant.hermes.HermesServerRequestHandler;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link TcpGatewayReloader} 走<b>真实</b> {@link HermesTcpGateway}（真实 loopback socket、换行分隔
 * JSON-RPC、真实 {@code client.capabilities} 宣告）：证明 reload 复用的是网关客户端本身，而不是第二套协议。
 *
 * <p>断言全部落在 wire 上：平台确实发出 {@code reload.mcp} 且 {@code params.confirm == true}；回包被判成
 * 什么（{@code confirm_required} 是失败，不是成功）；以及在整条链路（控制面 + 真实 reloader + 真实文件）上
 * reload 被拒时条目是否回滚、是否再次 reload。不启动 Hermes、不碰任何真实 profile、不调模型。
 */
class TcpGatewayReloaderWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PREVIOUS = "https://assistant.internal/mcp-lease/previousLease01";
    private static final String CURRENT = "https://assistant.internal/mcp-lease/currentLease02";

    private static final String RELOADED =
        "{\"status\":\"reloaded\",\"loaded_rev\":\"0123456789ab\"}";
    private static final String CONFIRM_REQUIRED =
        "{\"status\":\"confirm_required\",\"message\":\"⚠️ /reload-mcp invalidates the prompt cache\"}";

    @TempDir
    Path dir;

    private ReloadGatewayStub stub;
    private HermesTcpGateway gateway;

    @BeforeEach
    void setUp() throws IOException {
        stub = new ReloadGatewayStub();
        gateway = new HermesTcpGateway("127.0.0.1", stub.port(), 5, MAPPER,
            HermesServerRequestHandler.failClosed());
    }

    @AfterEach
    void tearDown() throws IOException {
        stub.close();
    }

    @Test
    void reloadsThroughTheRealGatewayClientAndReturnsTheLoadedRevision() {
        stub.answerReloadWith(call -> RELOADED);

        GatewayMcpReloadReceipt receipt = reloader().reload();

        List<JsonNode> frames = stub.framesOf("reload.mcp");
        assertThat(frames).hasSize(1);
        JsonNode frame = frames.get(0);
        assertThat(frame.path("jsonrpc").asString()).isEqualTo("2.0");
        assertThat(frame.path("id").isIntegralNumber()).isTrue();
        assertThat(frame.path("params").path("confirm").booleanValue())
            .as("确认策略要求调用方显式 confirm=true，否则只会拿到 confirm_required")
            .isTrue();
        assertThat(receipt.loadedRev()).isEqualTo("0123456789ab");
        assertThat(receipt.coalesced()).isFalse();
        assertThat(stub.framesOf("client.capabilities"))
            .as("复用网关客户端：连接建立时照旧先宣告服务端请求能力")
            .hasSize(1);
    }

    @Test
    void confirmRequiredOnTheWireIsAFailureNotAReload() {
        stub.answerReloadWith(call -> CONFIRM_REQUIRED);

        assertThatThrownBy(() -> reloader().reload())
            .isInstanceOfSatisfying(GatewayReloadException.class, error -> assertThat(error.code())
                .isEqualTo(GatewayReloadException.Code.CONFIRM_REQUIRED));
        assertThat(stub.framesOf("reload.mcp")).hasSize(1);
    }

    /** 整条链路：网关要求确认 → 控制面判定失败 → 回滚上一条目 → 再 reload 一次。 */
    @Test
    void theControlRollsBackAndReloadsAgainWhenTheGatewayAsksForConfirmation() throws IOException {
        Path config = configFile();
        stub.answerReloadWith(call -> call == 1 ? CONFIRM_REQUIRED : RELOADED);
        ConfigFileGatewayMcpControl control = control(config);

        control.writeServerEntry(CURRENT);
        assertThat(entryOf(config)).isEqualTo(CURRENT);

        assertThatThrownBy(control::reload)
            .isInstanceOfSatisfying(GatewayMcpControlException.class,
                error -> assertThat(error.reason()).isEqualTo(Reason.RELOAD_FAILED));

        assertThat(entryOf(config)).as("reload 被拒时必须回滚到上一条目").isEqualTo(PREVIOUS);
        assertThat(stub.framesOf("reload.mcp")).as("失败一次 + 回滚后一次").hasSize(2);
        assertThat(stub.lastReloadParams().path("confirm").booleanValue()).isTrue();
    }

    /** 两次 reload 都被要求确认：回滚写成功、第二次 reload 也失败 → 对账异常。 */
    @Test
    void theControlAsksForReconciliationWhenEveryReloadIsRefused() throws IOException {
        Path config = configFile();
        stub.answerReloadWith(call -> CONFIRM_REQUIRED);
        ConfigFileGatewayMcpControl control = control(config);

        control.writeServerEntry(CURRENT);

        assertThatThrownBy(control::reload)
            .isInstanceOfSatisfying(GatewayMcpControlException.class,
                error -> assertThat(error.reason()).isEqualTo(Reason.ROLLBACK_FAILED));

        assertThat(entryOf(config)).isEqualTo(PREVIOUS);
        assertThat(stub.framesOf("reload.mcp")).hasSize(2);
    }

    // ---------------------------------------------------------------- 夹具

    private TcpGatewayReloader reloader() {
        return new TcpGatewayReloader(gateway::rpc, MAPPER, Duration.ofSeconds(60));
    }

    private ConfigFileGatewayMcpControl control(Path config) {
        return new ConfigFileGatewayMcpControl(config, "haitlify",
            ConfigFileGatewayMcpControl.DEFAULT_PARENT_PATH, reloader(), null);
    }

    private Path configFile() throws IOException {
        Path config = dir.resolve("config.yaml");
        Files.writeString(config, "# 夹具\n"
            + "mcp_servers:\n"
            + "  mcp-atlassian:\n"
            + "    command: uvx\n"
            + "    args: ['mcp-atlassian']\n"
            + "  haitlify:\n"
            + "    url: '" + PREVIOUS + "'\n"
            + "    headers:\n"
            + "      Authorization: 'Bearer stale'\n"
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

    /**
     * 真实 loopback 上的假 tui_gateway：换行分隔 JSON-RPC。
     *
     * <p>只实现两条方法：{@code client.capabilities}（连接时的能力宣告）与 {@code reload.mcp}（脚本化回包）。
     * 每条收到的帧都记流水，断言因此看的是「平台上真的发了什么」。
     */
    private static final class ReloadGatewayStub implements AutoCloseable {

        private final ServerSocket server;
        private final List<JsonNode> inbound = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger reloadCalls = new AtomicInteger();
        private volatile IntFunction<String> reloadAnswers = call -> RELOADED;
        private final List<Socket> sockets = Collections.synchronizedList(new ArrayList<>());

        ReloadGatewayStub() throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            Thread accept = new Thread(this::acceptLoop, "reload-wire-stub-accept");
            accept.setDaemon(true);
            accept.start();
        }

        int port() {
            return server.getLocalPort();
        }

        void answerReloadWith(IntFunction<String> answers) {
            this.reloadAnswers = answers;
        }

        List<JsonNode> framesOf(String method) {
            synchronized (inbound) {
                return inbound.stream().filter(frame -> method.equals(frame.path("method").asString()))
                    .toList();
            }
        }

        JsonNode lastReloadParams() {
            List<JsonNode> frames = framesOf("reload.mcp");
            return frames.isEmpty() ? MAPPER.createObjectNode() : frames.get(frames.size() - 1).path("params");
        }

        private void acceptLoop() {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    sockets.add(socket);
                    Thread worker = new Thread(() -> serve(socket), "reload-wire-stub-conn");
                    worker.setDaemon(true);
                    worker.start();
                } catch (IOException closed) {
                    return;
                }
            }
        }

        private void serve(Socket socket) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    JsonNode request = MAPPER.readTree(line);
                    inbound.add(request);
                    String method = request.path("method").asString("");
                    JsonNode id = request.path("id");
                    String result;
                    if ("client.capabilities".equals(method)) {
                        result = "{\"server_requests\":[\"approval\",\"clarify\"],\"declines_not_shown\":true}";
                    } else if ("reload.mcp".equals(method)) {
                        result = reloadAnswers.apply(reloadCalls.incrementAndGet());
                    } else {
                        writer.write("{\"jsonrpc\":\"2.0\",\"id\":" + id
                            + ",\"error\":{\"code\":-32601,\"message\":\"unsupported\"}}\n");
                        writer.flush();
                        continue;
                    }
                    writer.write("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}\n");
                    writer.flush();
                }
            } catch (IOException ignored) {
                // 连接被对端关闭：桩不需要重连。
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
            synchronized (sockets) {
                for (Socket socket : sockets) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                        // best effort
                    }
                }
            }
        }
    }
}
