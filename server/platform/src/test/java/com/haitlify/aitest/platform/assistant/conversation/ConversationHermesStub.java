package com.haitlify.aitest.platform.assistant.conversation;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 真实 loopback 上的假 Hermes 网关：换行分隔 JSON-RPC，脚本化答复，用来驱动<b>真实</b>
 * {@code HermesTcpGateway} 与真实 Spring 上下文（不 mock 被测服务、不 mock 传输层、不连真实模型）。
 *
 * <p>与 {@code HermesWireStub} 的差别只在用途：那个桩是「一次连接、一段脚本」，本桩固定端口、
 * 常驻连接、按方法答复并记流水，因为 HTTP 层的用例需要在上下文启动<b>之前</b>就知道端口。
 *
 * <p>记账全部落在 wire 上（收到几帧 {@code session.create}/{@code session.resume}/{@code prompt.submit}），
 * 因此断言的是「平台上真的发了什么」，而不是被测类的内部字段。
 */
final class ConversationHermesStub implements AutoCloseable {

    /** pin 的 {@code client.capabilities} 回包形状：必须同时宣告 approval 与 clarify。 */
    static final String ADVERTISED_SERVER_REQUESTS =
        "{\"server_requests\":[\"approval\",\"clarify\"],\"declines_not_shown\":true}";

    static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 答复模式。
     *
     * <p>{@link #NORMAL}：受理 {@code streaming} 并立刻给出终态回复（正文 {@code "reply:" + 提问}）。
     * {@link #QUEUED}：忙会话的排队受理——pin 的语义是「本轮并没有开始」，绝不能被当成成功。
     * {@link #HANG}：受理但永不产生终态——用来证明未终态期间同一会话不会双开轮次。
     */
    enum Mode { NORMAL, QUEUED, HANG }

    private final ServerSocket server;
    private final List<JsonNode> inbound = Collections.synchronizedList(new ArrayList<>());
    private final List<String> resumedStoredIds = Collections.synchronizedList(new ArrayList<>());
    private final List<String> submittedSessionIds = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger creates = new AtomicInteger();
    private final AtomicInteger submits = new AtomicInteger();
    private final AtomicInteger accepts = new AtomicInteger();
    private final AtomicInteger liveSeq = new AtomicInteger();
    private volatile Mode mode = Mode.NORMAL;
    /** 用例观察点：收到 {@code prompt.submit} 之后、回包之前执行（只用于采样平台当时的网关/租约状态）。 */
    private volatile Runnable promptSubmitHook;

    /** 设置/清除 {@code prompt.submit} 采样钩子；钩子里只做采样，不做断言（它在桩线程上跑）。 */
    void onPromptSubmit(Runnable hook) {
        this.promptSubmitHook = hook;
    }

    private void firePromptSubmitHook() {
        Runnable hook = promptSubmitHook;
        if (hook == null) {
            return;
        }
        try {
            hook.run();
        } catch (RuntimeException ignored) {
            // 采样失败不得改变桩的协议行为（否则用例会挂成超时，而不是给出断言信息）。
        }
    }
    /** 最近一次 {@code session.create} 回给平台的持久会话 id（仅供断言「平台确实记住了它」）。 */
    private volatile String lastCreatedStoredId;

    ConversationHermesStub() throws IOException {
        this.server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread accept = new Thread(this::acceptLoop, "conversation-hermes-stub-accept");
        accept.setDaemon(true);
        accept.start();
    }

    static ConversationHermesStub start() {
        try {
            return new ConversationHermesStub();
        } catch (IOException ex) {
            throw new IllegalStateException("无法启动假 Hermes 端点", ex);
        }
    }

    int port() {
        return server.getLocalPort();
    }

    void mode(Mode next) {
        this.mode = next;
    }

    /** 用例之间清空流水（同一连接会跨用例复用）。 */
    void reset() {
        mode = Mode.NORMAL;
        promptSubmitHook = null;
        lastCreatedStoredId = null;
        synchronized (inbound) {
            inbound.clear();
        }
        resumedStoredIds.clear();
        submittedSessionIds.clear();
        creates.set(0);
        submits.set(0);
    }

    String lastCreatedStoredId() {
        return lastCreatedStoredId;
    }

    int creates() {
        return creates.get();
    }

    int submits() {
        return submits.get();
    }

    int accepts() {
        return accepts.get();
    }

    List<String> resumedStoredIds() {
        synchronized (resumedStoredIds) {
            return List.copyOf(resumedStoredIds);
        }
    }

    List<String> submittedSessionIds() {
        synchronized (submittedSessionIds) {
            return List.copyOf(submittedSessionIds);
        }
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try (Socket socket = server.accept()) {
                accepts.incrementAndGet();
                socket.setSoTimeout(30_000);
                serve(socket);
            } catch (IOException ex) {
                return;
            }
        }
    }

    private void serve(Socket socket) throws IOException {
        BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            JsonNode frame = MAPPER.readTree(line);
            if (!frame.hasNonNull("method") || !frame.hasNonNull("id")) continue;
            inbound.add(frame);
            respond(frame, out);
        }
    }

    private void respond(JsonNode request, BufferedWriter out) throws IOException {
        String method = request.path("method").asString("");
        JsonNode id = request.get("id");
        JsonNode params = request.path("params");
        switch (method) {
            case "client.capabilities" -> send(out, result(id, ADVERTISED_SERVER_REQUESTS));
            case "session.create" -> {
                int seq = liveSeq.incrementAndGet();
                creates.incrementAndGet();
                lastCreatedStoredId = "stored-" + seq;
                send(out, result(id, "{\"session_id\":\"live-" + seq + "\",\"stored_session_id\":\"" + lastCreatedStoredId + "\"}"));
            }
            case "session.resume" -> {
                String stored = params.path("session_id").asString("");
                resumedStoredIds.add(stored);
                int seq = liveSeq.incrementAndGet();
                send(out, result(id, "{\"session_id\":\"live-" + seq + "\",\"resumed\":\"" + stored
                    + "\",\"message_count\":0,\"messages\":[],\"running\":false,\"status\":\"idle\"}"));
            }
            case "prompt.submit" -> {
                String sessionId = params.path("session_id").asString("");
                String text = params.path("text").asString("");
                submits.incrementAndGet();
                submittedSessionIds.add(sessionId);
                firePromptSubmitHook();
                if (mode == Mode.QUEUED) {
                    send(out, result(id, "{\"status\":\"queued\"}"));
                } else {
                    send(out, result(id, "{\"status\":\"streaming\"}"));
                    if (mode == Mode.NORMAL) {
                        send(out, event(sessionId, "reply:" + text));
                    }
                }
            }
            default -> send(out, error(id, -32601, "unsupported method"));
        }
    }

    private static String result(JsonNode id, String resultJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id.toString() + ",\"result\":" + resultJson + "}";
    }

    private static String error(JsonNode id, int code, String message) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id.toString() + ",\"error\":{\"code\":" + code
            + ",\"message\":\"" + message + "\"}}";
    }

    private static String event(String sessionId, String text) {
        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        envelope.put("method", "event");
        ObjectNode params = envelope.putObject("params");
        params.put("session_id", sessionId);
        params.put("type", "message.complete");
        ObjectNode payload = params.putObject("payload");
        payload.put("status", "complete");
        payload.put("text", text);
        return envelope.toString();
    }

    private static void send(BufferedWriter out, String frame) throws IOException {
        out.write(frame);
        out.write("\n");
        out.flush();
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
