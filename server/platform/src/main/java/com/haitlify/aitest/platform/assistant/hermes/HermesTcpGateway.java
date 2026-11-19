package com.haitlify.aitest.platform.assistant.hermes;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Hermes 执行单元的 TCP 客户端：换行分隔 JSON-RPC，并提供有界会话事件消费。
 *
 * <p>用于「Hermes 在独立容器里」的部署：平台与 Hermes 之间只开一条 TCP 连接，
 * 不共享进程、不挂 docker socket、不挂宿主目录。Hermes 容器内由 bridge.py 把这条
 * 连接原样转给 {@code tui_gateway} 子进程（stdio 透明隧道）。
 *
 * <p>纪律与 stdio 版相同：不猜字段、连不上就 {@link #available()} false（调用方 fail-closed）、
 * 超时即失败。
 *
 * <p>服务端请求（Hermes→宿主）按方法分型处理：{@code approval}/{@code clarify} 交给显式注入的
 * {@link HermesServerRequestHandler}；未注入时使用 {@link HermesServerRequestHandler#failClosed()}
 * （审批拒绝、澄清取消）。其余方法一律 -32601，绝不替用户做决定。
 *
 * <p>能力宣告：pin 的 {@code tui_gateway/server_requests.py} 只对发过
 * {@code client.capabilities {server_requests: true}} 的 transport 下发 approval/clarify；
 * 没宣告的连接会被「快速失败」而不是等人。因此每建一条新连接都要宣告一次，并严格校验回包
 * （必须是对象，{@code server_requests} 是字符串数组且含 {@code approval} 与 {@code clarify}，
 * {@code declines_not_shown} 若出现必须是 true）。旧网关回 -32601 或回包不满足时，
 * 交互能力记为不可用（{@link #interactionAvailable()} false），到达的服务端请求一律按
 * fail-closed 处理——绝不静默当作已宣告。
 */
@Component
@ConditionalOnProperty(name = "app.assistant.hermes.transport", havingValue = "tcp")
public class HermesTcpGateway implements HermesGateway {

    private static final Logger log = LoggerFactory.getLogger(HermesTcpGateway.class);

    private final String host;
    private final int port;
    private final Duration timeout;
    private final ObjectMapper objectMapper;
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    // Bounds apply per gateway, including unknown/unconsumed session ids.
    static final int MAX_SESSIONS = 64;
    static final int MAX_EVENTS = 256;
    static final int MAX_EVENT_BYTES = 1024 * 1024;
    static final int MAX_FRAME_CHARS = 1024 * 1024;
    /**
     * pin 在 session.resume 上只会产出的运行时状态：idle/working/waiting/starting/streaming，
     * 以及 defer_history/lazy 路径的 resuming（tui_gateway/methods_session.py:963）。
     * 少一项就会把合法恢复判成畸形回执，故按 pin 契约全量收录。
     */
    private static final java.util.Set<String> RESUME_STATUSES =
        java.util.Set.of("idle", "working", "waiting", "starting", "streaming", "resuming");
    /** {@code client.capabilities} 回包里必须出现的服务端请求方法（本适配器确实实现的两种）。 */
    private static final java.util.Set<String> REQUIRED_SERVER_REQUESTS =
        java.util.Set.of("approval", "clarify");
    private final Object eventLock = new Object();
    private final Map<String, java.util.List<JsonNode>> events = new java.util.HashMap<>();
    private final Map<String, Integer> eventBytes = new java.util.HashMap<>();

    /**
     * Returns original envelopes in arrival order, destructively and atomically.
     * Progress is lossy under pressure; message.complete/error are never silently evicted.
     * An exhausted terminal/session/frame budget closes the transport, with retained events
     * drainable first and IllegalStateException on subsequent empty drains. Reconcile history;
     * do not replay submissions. This is a bounded in-memory buffer, not durable delivery.
     */
    @Override
    public java.util.List<JsonNode> drainEvents(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("live session id required");
        synchronized (eventLock) {
            var result = events.remove(sessionId);
            eventBytes.remove(sessionId);
            if (result != null) return java.util.List.copyOf(result);
            if (unavailable) throw new IllegalStateException("Hermes event stream unavailable; reconcile session history");
            return java.util.List.of();
        }
    }

    private Socket socket;
    private BufferedWriter writer;
    private volatile boolean unavailable;

    /** 服务端请求能力的宣告状态；只有 {@link #AVAILABLE} 才算「已宣告并校验通过」。 */
    public enum InteractionState { UNVERIFIED, AVAILABLE, UNAVAILABLE }

    private volatile InteractionState interactionState = InteractionState.UNVERIFIED;
    private volatile String interactionDetail = "尚未连接 Hermes，服务端请求能力未宣告";

    /** 审批/澄清的显式处理器；未注入时一律拒绝（fail-closed），绝不自动批准。 */
    private final HermesServerRequestHandler serverRequests;

    public HermesTcpGateway(@Value("${app.assistant.hermes.host:hermes}") String host,
                            @Value("${app.assistant.hermes.port:9420}") int port,
                            @Value("${app.assistant.hermes.timeout-seconds:60}") int timeoutSeconds,
                            ObjectMapper objectMapper) {
        this(host, port, timeoutSeconds, objectMapper, HermesServerRequestHandler.failClosed());
    }

    /**
     * Spring 装配入口：处理器是可选依赖，缺失时使用 {@link HermesServerRequestHandler#failClosed()}
     * （审批拒绝、澄清取消），因此「忘记注入」不会退化成自动批准。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public HermesTcpGateway(@Value("${app.assistant.hermes.host:hermes}") String host,
                            @Value("${app.assistant.hermes.port:9420}") int port,
                            @Value("${app.assistant.hermes.timeout-seconds:60}") int timeoutSeconds,
                            ObjectMapper objectMapper,
                            org.springframework.beans.factory.ObjectProvider<HermesServerRequestHandler> handlers) {
        this(host, port, timeoutSeconds, objectMapper, handlers.getIfAvailable(HermesServerRequestHandler::failClosed));
    }

    public HermesTcpGateway(String host, int port, int timeoutSeconds, ObjectMapper objectMapper,
                            HermesServerRequestHandler serverRequests) {
        this.host = host;
        this.port = port;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.objectMapper = objectMapper;
        this.serverRequests = serverRequests == null ? HermesServerRequestHandler.failClosed() : serverRequests;
    }

    @Override
    public synchronized boolean available() {
        if (unavailable) {
            return false;
        }
        try {
            ensureConnected();
            return true;
        } catch (IOException ex) {
            log.warn("Hermes 执行单元不可达（{}:{}）：{}", host, port, ex.getMessage());
            return false;
        }
    }

    @Override
    public boolean interactionAvailable() {
        return interactionState == InteractionState.AVAILABLE;
    }

    @Override
    public String interactionDetail() {
        return interactionDetail;
    }

    @Override
    public synchronized Session createSession(String cwd) {
        ObjectNode params = objectMapper.createObjectNode();
        if (cwd != null && !cwd.isBlank()) {
            params.put("cwd", cwd);
        }
        JsonNode result = call("session.create", params);
        String sessionId = text(result, "session_id", "sessionId", "id");
        if (sessionId.isBlank()) {
            throw new IllegalStateException("Hermes 未返回 session id：" + result);
        }
        return new Session(sessionId, result.toString());
    }

    @Override
    public synchronized Turn submitPrompt(String sessionId, String prompt) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("session_id", sessionId);
        params.put("text", prompt);
        JsonNode result = call("prompt.submit", params);
        String turnRef = text(result, "turn_id");
        return new Turn(turnRef.isBlank() ? null : turnRef, result.toString());
    }

    @Override
    public synchronized boolean interrupt(String sessionId) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("session_id", sessionId);
        try {
            JsonNode response = call("session.interrupt", params);
            JsonNode result = response.path("result");
            // Pinned TUI protocol: status=interrupted acknowledges the request only.
            // Missing/contradictory status and nested errors must never imply success.
            return result != null && result.isObject() && !result.hasNonNull("error")
                && result.path("status").isTextual()
                && "interrupted".equals(result.path("status").asString())
                && (!result.has("ok") || (result.path("ok").isBoolean()
                    && result.path("ok").booleanValue()));
        } catch (RuntimeException ex) {
            log.warn("Hermes 中断未确认（session={}）：{}", sessionId, ex.getMessage());
            return false;
        }
    }

    /**
     * 通用 JSON-RPC 调用（管理面用，例如 {@code reload.mcp}）：返回<b>原始</b>回包，不把 JSON-RPC
     * {@code error} 当异常——调用方需要区分「明确拒绝」与「传输坏了」。
     *
     * <p>复用同一条连接、同一套换行分隔帧约定与同一个 pending 表：管理面不需要、也不允许再写一套协议。
     * 唯一额外的东西是<b>每次调用自己的预算</b>：一次 {@code reload.mcp} 会全局 shutdown + rediscover，
     * 比普通会话 RPC 慢得多，不能沿用一个为普通调用配置的短超时。
     *
     * <p>传输失败仍按本类的既有纪律处理：连接被标记不可用并关闭，异常抛出，由调用方对账。
     *
     * @param method JSON-RPC 方法名
     * @param params 参数对象（{@code null} = 空对象）
     * @param timeout 本次调用的预算；调用方的 {@code future.get} 与 socket 空闲读超时都按它放宽
     */
    public JsonNode rpc(String method, JsonNode params, Duration timeout) {
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("rpc method required");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("positive rpc timeout required");
        }
        ObjectNode object;
        if (params == null) {
            object = objectMapper.createObjectNode();
        } else if (params instanceof ObjectNode node) {
            object = node;
        } else {
            // 静默转换成空对象会让调用方以为参数发出去了；JSON-RPC 的 params 必须是对象/数组。
            throw new IllegalArgumentException("rpc params must be a JSON object");
        }
        synchronized (this) {
            return exchange(method, object, timeout);
        }
    }

    private static String text(JsonNode node, String... keys) {
        JsonNode source = node.has("result") ? node.get("result") : node;
        for (String key : keys) {
            JsonNode value = source.path(key);
            if (value.isTextual() && !value.asString().isBlank()) {
                return value.asString();
            }
        }
        return "";
    }

    /** 只读字段查找：不像 {@link #text} 那样优先展开嵌套的 {@code result}，供已解包的 result 使用。 */
    private static String field(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.path(key);
            if (value.isTextual() && !value.asString().isBlank()) {
                return value.asString();
            }
        }
        return "";
    }

    /**
     * {@code session.resume}：参数是 stored id；回包同时给出新铸的运行时 {@code session_id} 与
     * 被恢复的持久标识（冷/温路径字段名不同：{@code resumed}/{@code session_key}/{@code stored_session_id}）。
     *
     * <p>任何「看起来成功但说明不了命中了哪条会话」的回包都拒绝：缺字段、未知状态、持久标识与请求不一致
     * （pin 的标题回退与压缩 tip 回退都会造成这种回包）一律抛异常，由调用方重新按回包里的 id 发起，
     * 绝不允许静默替换身份。
     */
    @Override
    public synchronized Resume resumeSession(String storedSessionId) {
        if (storedSessionId == null || storedSessionId.isBlank()) {
            throw new IllegalArgumentException("stored session id required");
        }
        ObjectNode params = objectMapper.createObjectNode();
        params.put("session_id", storedSessionId);
        JsonNode result = call("session.resume", params).path("result");
        if (!result.isObject()) {
            throw new IllegalStateException("Hermes session.resume 未返回结果对象：" + result);
        }
        String liveSessionId = field(result, "session_id");
        if (liveSessionId.isBlank()) {
            throw new IllegalStateException("Hermes session.resume 未回传运行时 session_id：" + result);
        }
        String resumedStoredId = field(result, "resumed", "stored_session_id", "session_key");
        if (resumedStoredId.isBlank()) {
            throw new IllegalStateException("Hermes session.resume 未回传被恢复的会话标识：" + result);
        }
        if (!resumedStoredId.equals(storedSessionId)) {
            throw new IllegalStateException("Hermes session.resume 命中其他会话（请求 " + storedSessionId
                + "，返回 " + resumedStoredId + "），拒绝静默替换身份：" + result);
        }
        String status = field(result, "status");
        if (!RESUME_STATUSES.contains(status)) {
            throw new IllegalStateException("Hermes session.resume 返回未知或缺失的状态：" + result);
        }
        JsonNode running = result.path("running");
        if (!running.isBoolean()) {
            throw new IllegalStateException("Hermes session.resume 缺少布尔 running：" + result);
        }
        JsonNode messageCount = result.path("message_count");
        if (!messageCount.isIntegralNumber() || messageCount.asLong() < 0
                || messageCount.asLong() > Integer.MAX_VALUE) {
            throw new IllegalStateException("Hermes session.resume 缺少有效 message_count：" + result);
        }
        return new Resume(storedSessionId, liveSessionId, resumedStoredId, status,
            running.booleanValue(), messageCount.asInt(), result.toString());
    }

    /**
     * {@code session.history}：pin 的参数是<b>运行时</b> session id（resume/create 返回的那个），
     * 回 {@code {count, messages}}。未知/已回收的运行时 id 由网关回 4001，call() 直接抛异常——
     * 「没有历史」只能写成成功回包里的 {@code count=0, messages=[]}。
     */
    @Override
    public synchronized History history(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("live session id required");
        }
        ObjectNode params = objectMapper.createObjectNode();
        params.put("session_id", sessionId);
        JsonNode result = call("session.history", params).path("result");
        if (!result.isObject()) {
            throw new IllegalStateException("Hermes session.history 未返回结果对象：" + result);
        }
        JsonNode count = result.path("count");
        if (!count.isIntegralNumber() || count.asLong() < 0 || count.asLong() > Integer.MAX_VALUE) {
            throw new IllegalStateException("Hermes session.history 缺少有效 count：" + result);
        }
        JsonNode messages = result.path("messages");
        if (!messages.isArray()) {
            throw new IllegalStateException("Hermes session.history 缺少 messages 数组：" + result);
        }
        java.util.List<JsonNode> copy = new java.util.ArrayList<>();
        messages.forEach(copy::add);
        return new History(sessionId, count.asInt(), java.util.List.copyOf(copy), result.toString());
    }

    private void ensureConnected() throws IOException {
        if (unavailable) throw new IOException("Hermes connection failed; reconciliation required");
        if (socket != null && socket.isConnected() && !socket.isClosed()) {
            return;
        }
        Socket fresh = new Socket();
        fresh.connect(new InetSocketAddress(host, port), (int) Math.min(5000, timeout.toMillis()));
        fresh.setSoTimeout((int) timeout.toMillis());
        this.socket = fresh;
        this.writer = new BufferedWriter(new OutputStreamWriter(fresh.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader reader = new BufferedReader(new InputStreamReader(fresh.getInputStream(), StandardCharsets.UTF_8));
        Thread pump = new Thread(() -> pump(reader), "hermes-tcp-reader");
        pump.setDaemon(true);
        pump.start();
        // 每条新 transport 都必须重新宣告：pin 的 server_requests 是按连接记账的。
        advertiseServerRequests();
    }

    /**
     * 发一次 {@code client.capabilities {server_requests: true}} 并严格校验回包。
     *
     * <p>回 -32601（旧网关）或回包不满足契约时，只把交互能力记为不可用：连接本身仍可做普通 RPC，
     * 但服务端请求按 fail-closed 处理。只有传输层真的坏了（超时/断连）才作为 IOException 抛出，
     * 由调用方按不可用处理。
     */
    private void advertiseServerRequests() throws IOException {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("server_requests", true);
        JsonNode response;
        try {
            response = exchange("client.capabilities", params);
        } catch (IllegalStateException ex) {
            throw new IOException("Hermes 交互能力宣告失败：" + ex.getMessage(), ex);
        }
        String problem = capabilityProblem(response);
        if (problem == null) {
            interactionState = InteractionState.AVAILABLE;
            interactionDetail = "已宣告 client.capabilities：网关确认 approval/clarify";
        } else {
            interactionState = InteractionState.UNAVAILABLE;
            interactionDetail = problem;
            log.warn("Hermes 交互能力不可用（{}:{}）：{}", host, port, problem);
        }
    }

    /** 回包合法时返回 null，否则返回明确的拒绝原因（绝不返回「大概支持」）。 */
    private static String capabilityProblem(JsonNode response) {
        JsonNode error = response.path("error");
        if (error.isObject()) {
            int code = error.path("code").asInt(Integer.MIN_VALUE);
            if (code == -32601) {
                return "网关不支持 client.capabilities（-32601：旧网关），无法接收 approval/clarify";
            }
            return "网关拒绝 client.capabilities：" + error;
        }
        if (!error.isMissingNode() && !error.isNull()) {
            return "client.capabilities 错误字段形状非法：" + error;
        }
        JsonNode result = response.path("result");
        if (!result.isObject()) {
            return "client.capabilities 未返回结果对象：" + result;
        }
        JsonNode advertised = result.path("server_requests");
        if (!advertised.isArray() || advertised.isEmpty()) {
            return "client.capabilities 未返回非空 server_requests 数组：" + result;
        }
        java.util.Set<String> methods = new java.util.HashSet<>();
        for (JsonNode method : advertised) {
            if (!method.isTextual() || method.asString().isBlank()) {
                return "client.capabilities 的 server_requests 含非字符串项：" + advertised;
            }
            methods.add(method.asString());
        }
        if (!methods.containsAll(REQUIRED_SERVER_REQUESTS)) {
            return "client.capabilities 未宣告 " + REQUIRED_SERVER_REQUESTS + "（实际 " + methods + "）";
        }
        if (result.has("declines_not_shown")) {
            JsonNode declines = result.path("declines_not_shown");
            if (!declines.isBoolean() || !declines.booleanValue()) {
                return "client.capabilities 的 declines_not_shown 不是 true：" + declines;
            }
        }
        return null;
    }

    private static boolean terminal(JsonNode event) {
        String type = event.path("params").path("type").asString("");
        return "message.complete".equals(type) || "error".equals(type);
    }

    private void bufferEvent(String sessionId, JsonNode node) throws IOException {
        int size = node.toString().getBytes(StandardCharsets.UTF_8).length;
        synchronized (eventLock) {
            if (size > MAX_EVENT_BYTES) throw new IOException("Hermes event size exceeded");
            if (!events.containsKey(sessionId) && events.size() >= MAX_SESSIONS) {
                throw new IOException("Hermes event session capacity exceeded");
            }
            var queue = events.computeIfAbsent(sessionId, key -> new java.util.ArrayList<>());
            int bytes = eventBytes.getOrDefault(sessionId, 0);
            while (queue.size() >= MAX_EVENTS || bytes + size > MAX_EVENT_BYTES) {
                JsonNode discard = queue.stream().filter(event -> !terminal(event)).findFirst().orElse(null);
                if (discard == null) {
                    eventBytes.put(sessionId, bytes);
                    if (!terminal(node)) return;
                    throw new IOException("Hermes terminal event backlog exceeded");
                }
                queue.remove(discard);
                bytes -= discard.toString().getBytes(StandardCharsets.UTF_8).length;
            }
            queue.add(node);
            eventBytes.put(sessionId, bytes + size);
        }
    }

    // readLine() allocates without a bound, even when the eventual event queue is bounded.
    private String readFrame(BufferedReader reader) throws IOException {
        StringBuilder frame = new StringBuilder();
        int ch;
        while ((ch = reader.read()) != -1) {
            if (ch == '\n') return frame.toString();
            if (frame.length() >= MAX_FRAME_CHARS) throw new IOException("Hermes frame size exceeded");
            frame.append((char) ch);
        }
        if (!frame.isEmpty()) throw new IOException("Hermes truncated frame");
        return null;
    }

    private void pump(BufferedReader reader) {
        try {
            String line;
            while ((line = readFrame(reader)) != null) {
                JsonNode node = objectMapper.readTree(line);
                JsonNode id = node.path("id");
                if (id.isMissingNode() || id.isNull()) {
                    if ("event".equals(node.path("method").asString(""))) {
                        JsonNode sid = node.path("params").path("session_id");
                        if (sid.isTextual() && !sid.asString().isBlank()) {
                            bufferEvent(sid.asString(), node);
                        }
                    }
                    continue;
                }
                if (node.has("method")) {
                    handleServerRequest(node);
                    continue;
                }
                if (!id.isIntegralNumber() || !(node.has("result") || node.has("error"))) continue;
                CompletableFuture<JsonNode> future = pending.remove(id.asLong());
                if (future != null) {
                    future.complete(node);
                }
            }
        } catch (Exception ex) {
            log.warn("Hermes TCP 读线程退出：{}", ex.getMessage());
        } finally {
            // Never wait for the caller's monitor before waking its future.
            unavailable = true;
            pending.values().forEach(future -> future.completeExceptionally(
                new IOException("Hermes TCP connection closed")));
            closeSocket();
        }
    }

    private final Object writeLock = new Object();

    /**
     * 处理一条服务端请求（server→client）。只有审批与澄清会被派发；其余方法仍回 -32601。
     *
     * <p>失败一律 fail-closed：id 类型非法 → -32600（id=null），缺 session_id → -32602（不派发），
     * 处理器抛异常或返回非对象 → -32603。任何错误帧都不会让 Hermes 以为请求已被批准。
     * 本方法在读线程上执行，因此只做「构造 + 写一帧」，不持有调用方 monitor。
     */
    private void handleServerRequest(JsonNode node) throws IOException {
        JsonNode id = node.get("id");
        if (!id.isTextual() && !id.isIntegralNumber()) {
            // 非字符串/整数 id 无法原样合法回带（JSON-RPC 要求按规范回 null）。
            writeErrorFrame(null, -32600, "Server request id must be a string or an integer");
            return;
        }
        String method = node.path("method").asString("");
        HermesServerRequest.Kind kind = HermesServerRequest.kindOf(method);
        if (kind == null) {
            writeErrorFrame(id, -32601, "Server request method is not supported by this host");
            return;
        }
        JsonNode params = node.path("params");
        JsonNode sessionId = params.path("session_id");
        if (!params.isObject() || !sessionId.isTextual() || sessionId.asString().isBlank()) {
            writeErrorFrame(id, -32602, "Server request requires a string params.session_id");
            return;
        }
        HermesServerRequest request =
            new HermesServerRequest(kind, id, method, sessionId.asString(), params, node);
        // 未确认宣告的 transport 上收到服务端请求是矛盾的：退回 fail-closed 处理器，
        // 既不放行审批，也不替用户回答澄清。
        HermesServerRequestHandler handler = serverRequests;
        if (interactionState != InteractionState.AVAILABLE) {
            handler = HermesServerRequestHandler.failClosed();
        }
        JsonNode result;
        try {
            result = handler.answer(request);
        } catch (RuntimeException ex) {
            log.warn("Hermes 服务端请求处理失败（{}，session={}）：{}", method, request.sessionId(), ex.getMessage());
            writeErrorFrame(id, -32603, "Server request handler failed");
            return;
        }
        if (result == null || !result.isObject()) {
            log.warn("Hermes 服务端请求处理器未返回对象结果（{}，session={}）", method, request.sessionId());
            writeErrorFrame(id, -32603, "Server request handler returned no JSON object");
            return;
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        writeFrame(response);
    }

    private void writeErrorFrame(JsonNode id, int code, String message) throws IOException {
        ObjectNode frame = objectMapper.createObjectNode();
        frame.put("jsonrpc", "2.0");
        if (id == null) {
            frame.putNull("id");
        } else {
            frame.set("id", id);
        }
        frame.putObject("error").put("code", code).put("message", message);
        writeFrame(frame);
    }

    private void writeFrame(JsonNode frame) throws IOException {
        synchronized (writeLock) {
            writer.write(objectMapper.writeValueAsString(frame));
            writer.write("\n");
            writer.flush();
        }
    }

    private synchronized JsonNode call(String method, ObjectNode params) {
        JsonNode response = exchange(method, params);
        JsonNode error = response.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            throw new HermesGateway.UpstreamRpcError(method, topLevelErrorCode(error), error.toString());
        }
        return response;
    }

    /**
     * 只读「顶层」JSON-RPC {@code error.code} 的整数提取：error 必须是对象、{@code code} 必须是 int 范围内的
     * 整数；其它形状（嵌套在 {@code data} 里、字符串码、浮点、缺失、超范围）一律 null——不可判定。
     *
     * <p>本方法只按 JSON 结构取值，不做任何文本扫描：错误正文里出现的数字（含嵌套码）没有语义。
     */
    static Integer topLevelErrorCode(JsonNode error) {
        if (error == null || !error.isObject()) {
            return null;
        }
        JsonNode code = error.path("code");
        if (!code.isIntegralNumber() || !code.canConvertToInt()) {
            return null;
        }
        return Integer.valueOf(code.asInt());
    }

    /**
     * 发一条 RPC 并等回包，但<b>不</b>把 JSON-RPC error 当成异常：能力宣告需要看到 -32601
     * 这种「明确的拒绝」才能区分「旧网关」与「传输坏了」。契约错误由调用方判定。
     */
    private JsonNode exchange(String method, ObjectNode params) {
        return exchange(method, params, timeout);
    }

    /**
     * 带<b>本次调用</b>预算的 RPC。socket 的空闲读超时可能会短于这次预算（它按普通会话调用配置）：
     * 读线程一旦在预算内被判超时，会把整条连接打成不可用。所以这里只把空闲读超时<b>抬高</b>到本次预算，
     * 绝不降低——真正的上界始终是 {@code future.get(timeout)}。
     */
    private JsonNode exchange(String method, ObjectNode params, Duration callTimeout) {
        try {
            ensureConnected();
        } catch (IOException ex) {
            unavailable = true;
            throw new IllegalStateException("Hermes 执行单元不可达：" + ex.getMessage(), ex);
        }
        raiseIdleReadTimeout(callTimeout);
        long id = ids.incrementAndGet();
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.set("params", params);
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            writeFrame(request);
            return future.get(callTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (IOException ex) {
            unavailable = true;
            closeSocket();
            throw new IllegalStateException("Hermes 连接中断：" + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Hermes 调用被中断：" + method, ex);
        } catch (ExecutionException | TimeoutException ex) {
            unavailable = true;
            closeSocket();
            throw new IllegalStateException("Hermes 调用超时/失败：" + method, ex);
        } finally {
            pending.remove(id);
        }
    }

    /** 只抬不降：把 socket 的空闲读超时放到本次调用预算之上。 */
    private void raiseIdleReadTimeout(Duration callTimeout) {
        Socket current = socket;
        if (current == null || callTimeout == null) {
            return;
        }
        try {
            int wanted = (int) Math.min(Integer.MAX_VALUE, callTimeout.toMillis());
            if (wanted > current.getSoTimeout()) {
                current.setSoTimeout(wanted);
            }
        } catch (IOException | RuntimeException ignored) {
            // 抬不上去就沿用原超时：调用方仍有 future.get 的硬预算。
        }
    }

    private synchronized void closeSocket() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // Best-effort close; a failed stream is never silently reconnected.
        }
        socket = null;
        writer = null;
        // transport 没了，宣告也随之失效（pin 的 advertise 按连接记账）。
        interactionState = InteractionState.UNVERIFIED;
        interactionDetail = "Hermes 连接已关闭，服务端请求能力未宣告";
    }
}
