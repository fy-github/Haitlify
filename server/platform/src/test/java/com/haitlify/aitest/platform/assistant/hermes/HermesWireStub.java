package com.haitlify.aitest.platform.assistant.hermes;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 真实 loopback 上的假 Hermes 端点：脚本化收发换行分隔 JSON-RPC，用来驱动真实的
 * {@link HermesTcpGateway}（不 mock 被测类、不 mock 传输层，也不连真实 Hermes/模型）。
 *
 * <p>pin 的网关对 {@code client.capabilities} 一定会回包（{@code methods_voice.py:442-450}）。
 * 本桩在 {@link #read(BufferedReader)} 里透明应答该 RPC（默认宣告 approval+clarify），
 * 因此脚本拿到的第一帧仍然是它想断言的那个业务请求；宣告帧本身留在 {@link #inbound} 里供断言。
 * 需要模拟旧网关/部分宣告时用 {@link Capability} 指定回包。
 */
public final class HermesWireStub implements AutoCloseable {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    /** pin 的 {@code client.capabilities} 回包形状（{@code contracts.SERVER_REQUESTS} 的真实集合超集）。 */
    public static final String ADVERTISED_SERVER_REQUESTS =
        "{\"server_requests\":[\"approval\",\"clarify\",\"setup_beat\"],\"declines_not_shown\":true}";

    public static final String CAPABILITIES_METHOD = "client.capabilities";

    /** 对 {@code client.capabilities} 的回包：成功结果 JSON，或一个 JSON-RPC 错误码。 */
    record Capability(String resultJson, Integer errorCode) {
        static Capability advertised() {
            return new Capability(ADVERTISED_SERVER_REQUESTS, null);
        }

        static Capability result(String resultJson) {
            return new Capability(resultJson, null);
        }

        static Capability error(int errorCode) {
            return new Capability(null, errorCode);
        }
    }

    @FunctionalInterface
    public interface Script {
        void run(HermesWireStub stub, BufferedReader in, BufferedWriter out) throws Exception;
    }

    private final ServerSocket server;
    private final CompletableFuture<Void> finished = new CompletableFuture<>();
    private final Thread thread;
    private final Capability capability;
    private volatile Socket accepted;
    private BufferedWriter stubWriter;

    /** 被测网关写到这条连接上的每一帧（请求与回包都算），按到达顺序。 */
    public final List<JsonNode> inbound = Collections.synchronizedList(new ArrayList<>());

    public HermesWireStub(Script script) throws IOException {
        this(script, Capability.advertised());
    }

    public HermesWireStub(Script script, Capability capability) throws IOException {
        this.capability = capability == null ? Capability.advertised() : capability;
        this.server = new ServerSocket(0);
        this.thread = new Thread(() -> {
            try (Socket socket = server.accept()) {
                accepted = socket;
                socket.setSoTimeout(5000);
                stubWriter = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                script.run(this,
                    new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)),
                    stubWriter);
                finished.complete(null);
            } catch (Exception ex) {
                finished.completeExceptionally(ex);
            }
        }, "hermes-wire-stub");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    public int port() {
        return server.getLocalPort();
    }

    public HermesTcpGateway gateway(int timeoutSeconds) {
        return new HermesTcpGateway("127.0.0.1", port(), timeoutSeconds, MAPPER);
    }

    public HermesTcpGateway gateway(int timeoutSeconds, HermesServerRequestHandler handler) {
        return new HermesTcpGateway("127.0.0.1", port(), timeoutSeconds, MAPPER, handler);
    }

    /**
     * 读下一帧并记账；对端关闭时返回 {@code null}。透明的 {@code client.capabilities} 握手应答
     * 与脚本无关：脚本永远只会看到自己关心的业务帧。
     */
    public JsonNode read(BufferedReader in) throws IOException {
        while (true) {
            String line = in.readLine();
            if (line == null || line.isBlank()) {
                return null;
            }
            JsonNode node = MAPPER.readTree(line);
            inbound.add(node);
            if (!CAPABILITIES_METHOD.equals(node.path("method").asString(""))) {
                return node;
            }
            answerCapabilities(node);
        }
    }

    private void answerCapabilities(JsonNode request) throws IOException {
        String frame;
        if (capability.errorCode() != null) {
            frame = jsonRpcError(request.path("id"), capability.errorCode(), "method not found");
        } else {
            frame = jsonRpcResult(request.path("id"), capability.resultJson());
        }
        stubWriter.write(frame + "\n");
        stubWriter.flush();
    }

    /** 等某一帧出现在入站记录里（超时即判失败，避免用 sleep 掩盖竞态）。 */
    public JsonNode awaitInbound(Predicate<JsonNode> match) {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            synchronized (inbound) {
                for (JsonNode node : inbound) {
                    if (match.test(node)) {
                        return node;
                    }
                }
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待入站帧被中断", ex);
            }
        }
        throw new AssertionError("3s 内没有等到期望的入站帧：" + inbound);
    }

    public void awaitFinished() {
        try {
            finished.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new AssertionError("stub 脚本未正常结束", ex);
        }
    }

    public static void send(BufferedWriter out, String frame) throws IOException {
        out.write(frame + "\n");
        out.flush();
    }

    public static String jsonRpcResult(JsonNode id, String resultJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id.toString() + ",\"result\":" + resultJson + "}";
    }

    public static String jsonRpcError(JsonNode id, int code, String message) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id.toString()
            + ",\"error\":{\"code\":" + code + ",\"message\":\"" + message + "\"}}";
    }

    @Override
    public void close() throws Exception {
        server.close();
        Socket socket = accepted;
        if (socket != null) {
            socket.close();
        }
        thread.join(2000);
    }
}
