package com.haitlify.aitest.platform.assistant.hermes;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * TUI Gateway 的 stdio JSON-RPC 客户端（A0 §5.4 实测：换行分隔、按 id 回包、未知方法精确拒绝）。
 *
 * <p>三条纪律：
 * <ol>
 *   <li><b>不猜字段</b>：返回原始 JSON，由调用方按版本契约取 session_id / stored_session_id；</li>
 *   <li><b>不静默连接</b>：未配置命令时 {@link #available()} 为 false，调用方必须拒绝派发；</li>
 *   <li><b>超时即失败</b>：请求超时抛异常，绝不返回「大概成功」。</li>
 * </ol>
 *
 * <p>一个进程只服务一个租户（A0 §5.3 拓扑结论：session id 不是沙箱），因此本类不做多租户复用。
 */
@Component
@ConditionalOnProperty(name = "app.assistant.hermes.transport", havingValue = "stdio", matchIfMissing = true)
public class HermesTuiGateway implements HermesGateway {

    private static final Logger log = LoggerFactory.getLogger(HermesTuiGateway.class);

    private final List<String> command;
    private final String cwd;
    private final Duration timeout;
    private final ObjectMapper objectMapper;
    /** 追加到白名单环境之后的变量（执行单元用它注入 {@code HERMES_HOME}）。 */
    private final Map<String, String> environment;
    /** 是否为本实例注册 JVM 关闭钩子（单元实例由池关闭，不挂钩子）。 */
    private final boolean registerShutdownHook;
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    /**
     * 事件缓冲的硬边界（与 {@link HermesTcpGateway} 同阶）：按会话、按条数、按字节。
     *
     * <p>为什么要缓冲：会话类 RPC 的回包是「按 id 应答」，而模型终态走的是<b>通知帧</b>
     * （{@code {"method":"event","params":{"type":"message.complete",...}}}，没有 id）。没有这张表，
     * stdio 传输就只能把终态当噪声丢掉，于是 {@code awaitFinalText} 永远拿不到终态——平台会把一轮
     * 正常跑完的对话写成 UNKNOWN（这正是真实纵链跑不通的根因）。
     */
    static final int MAX_SESSIONS = 64;
    static final int MAX_EVENTS = 256;
    static final int MAX_EVENT_BYTES = 1024 * 1024;

    private final Object eventLock = new Object();
    private final Map<String, List<JsonNode>> events = new HashMap<>();
    private final Map<String, Integer> eventBytes = new HashMap<>();
    /** 事件流已不可信（进程退出 / 缓冲越界）：后续空 drain 必须显式报错，绝不冒充「没有事件」。 */
    private volatile boolean eventStreamLost;

    private Process process;
    private BufferedWriter stdin;
    private boolean unavailable;

    @Autowired
    public HermesTuiGateway(@Value("${app.assistant.hermes.command:}") String command,
                            @Value("${app.assistant.hermes.cwd:}") String cwd,
                            @Value("${app.assistant.hermes.timeout-seconds:60}") int timeoutSeconds,
                            ObjectMapper objectMapper) {
        this(command == null || command.isBlank() ? List.of() : List.of(command.trim().split("\\s+")),
            cwd, timeoutSeconds, objectMapper, Map.of(), true);
    }

    /**
     * 显式命令表 + 附加环境 + 是否挂 JVM 关闭钩子。
     *
     * <p>为什么需要这个构造重载（而不是在子类里改环境）：<b>执行单元</b>必须让网关子进程读它自己的
     * {@code HERMES_HOME}（单元目录里的 profile 副本），否则一个进程内的 MCP 条目仍然是共享的。
     * 单元也不该在每次新建时往 JVM 上挂一个新的关闭钩子——钩子数量会随单元数增长，而那些单元本来
     * 由池确定性地关闭（{@code ExecutionUnit.close()}）。
     *
     * @param command              命令与参数（逐项，不做空白切分）
     * @param cwd                  子进程工作目录；{@code null} = 继承
     * @param timeoutSeconds       普通会话 RPC 的默认预算
     * @param environment          <b>追加</b>到白名单环境之后的变量（同名覆盖）
     * @param registerShutdownHook 是否为本实例注册 JVM 关闭钩子（单元传 {@code false}，由池负责关闭）
     */
    public HermesTuiGateway(List<String> command, String cwd, int timeoutSeconds, ObjectMapper objectMapper,
                            Map<String, String> environment, boolean registerShutdownHook) {
        this.command = command == null ? List.of() : List.copyOf(command);
        this.cwd = cwd == null || cwd.isBlank() ? null : cwd;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.objectMapper = objectMapper;
        this.environment = environment == null ? Map.of() : Map.copyOf(environment);
        this.registerShutdownHook = registerShutdownHook;
    }

    @Override
    public synchronized boolean available() {
        if (command.isEmpty() || unavailable) {
            return false;
        }
        try {
            return ensureStarted();
        } catch (IOException ex) {
            unavailable = true;
            log.warn("Hermes 执行单元无法启动，按不可用处理：{}", ex.toString());
            return false;
        }
    }

    @Override
    public Session createSession(String workingDirectory) {
        JsonNode result = request("session.create", Map.of("cwd", workingDirectory == null ? "" : workingDirectory));
        String sessionId = text(result, "session_id", "sessionId", "id");
        if (sessionId.isBlank()) {
            throw new IllegalStateException("Hermes 未返回 session_id，拒绝把空会话写进账本：" + result);
        }
        return new Session(sessionId, result.toString());
    }

    @Override
    public Turn submitPrompt(String sessionId, String prompt) {
        JsonNode result = request("prompt.submit", Map.of("session_id", sessionId, "text", prompt));
        String turnRef = text(result, "turn_id");
        return new Turn(turnRef.isBlank() ? null : turnRef, result.toString());
    }

    @Override
    public boolean interrupt(String sessionId) {
        try {
            JsonNode result = request("session.interrupt", Map.of("session_id", sessionId));
            return "interrupted".equalsIgnoreCase(text(result, "status")) || result.path("interrupted").asBoolean(false);
        } catch (RuntimeException ex) {
            log.warn("中断未获回执（session={}）：{}", sessionId, ex.toString());
            return false;
        }
    }

    /**
     * 管理面 RPC（例如 {@code reload.mcp}）：返回<b>原始</b>回包，不把 JSON-RPC {@code error} 当异常——
     * 调用方需要区分「明确拒绝」与「传输坏了」。
     *
     * <p>契约与 {@code HermesTcpGateway::rpc} 相同（两者都能直接当 {@code GatewayRpc} 用）：<b>复用</b>
     * 本类已有的子进程、换行分隔帧与 pending/id 映射，不新写一套协议；唯一的额外能力是<b>每次调用自己的
     * 预算</b>——一次 {@code reload.mcp} 会全局 shutdown + rediscover，比普通会话 RPC 慢得多。
     *
     * <p>超时只结束<b>这一次</b>调用并清掉它的 pending 条目，<b>不</b>像 TCP 版那样把连接/进程打成不可用：
     * 子进程可能完全健康，只是这一条太慢；真正死了会由读线程的 EOF 把在途请求全部失败掉。
     *
     * @param method JSON-RPC 方法名
     * @param params 参数对象（{@code null} = 空对象；非对象直接拒绝，绝不静默转成空参数）
     * @param timeout 本次调用的预算（必须为正）
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
            // 静默转换成空对象会让调用方以为参数发出去了（例如丢掉 confirm=true）。
            throw new IllegalArgumentException("rpc params must be a JSON object");
        }
        return exchange(method, object, timeout);
    }

    /**
     * 在途请求数（管理面/测试可见）：超时、EOF、错误帧处理完之后必须归零，
     * 否则一次慢调用会把 pending 表永久占住，后续请求再也不会被唤醒。
     */
    public int inFlightRequests() {
        return pending.size();
    }

    /**
     * 按运行时 session id <b>原子地取走并清空</b>已缓冲的事件信封（契约见
     * {@link HermesGateway#drainEvents(String)}）。
     *
     * <p>语义与 TCP 版逐条对齐：返回原始信封（调用方按 {@code method=="event"} /
     * {@code params.type} 自己判定终态）；空列表只表示「此刻没有缓冲事件」，<b>不</b>表示完成；
     * 进程已退出或缓冲越界之后，空 drain 必须抛 {@link IllegalStateException}，让调用方按「事件流已丢」
     * 处理（落 UNKNOWN 并对账），而不是把「丢了终态」伪装成「这一轮还没跑完」。
     */
    @Override
    public List<JsonNode> drainEvents(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("live session id required");
        }
        synchronized (eventLock) {
            List<JsonNode> buffered = events.remove(sessionId);
            eventBytes.remove(sessionId);
            if (buffered != null) {
                return List.copyOf(buffered);
            }
            if (eventStreamLost) {
                throw new IllegalStateException("Hermes 事件流已不可用（子进程退出或缓冲越界）；需对账会话历史");
            }
            return List.of();
        }
    }

    /**
     * 把一帧通知收进缓冲：只认 {@code method=="event"} 且带 {@code params.session_id} 的信封。
     *
     * <p>压力下丢的只能是进度帧（{@code message.delta} 之类）；{@code message.complete}/{@code error}
     * 这种终态帧绝不静默淘汰——塞不进来就整条流标记为不可信，因为「终态丢了」与「这一轮还在跑」
     * 在调用方看来是同一件事，而其中一个是假等待。
     */
    private void ingestEvent(JsonNode message) {
        if (!"event".equals(message.path("method").asString(""))) {
            return;
        }
        JsonNode params = message.path("params");
        String sessionId = params.path("session_id").asString("");
        if (sessionId.isBlank()) {
            return;
        }
        int size = message.toString().getBytes(StandardCharsets.UTF_8).length;
        synchronized (eventLock) {
            if (size > MAX_EVENT_BYTES) {
                markEventStreamLost("单帧超过上限");
                return;
            }
            if (!events.containsKey(sessionId) && events.size() >= MAX_SESSIONS) {
                markEventStreamLost("事件会话数超过上限");
                return;
            }
            List<JsonNode> queue = events.computeIfAbsent(sessionId, key -> new ArrayList<>());
            int bytes = eventBytes.getOrDefault(sessionId, 0);
            while (queue.size() >= MAX_EVENTS || bytes + size > MAX_EVENT_BYTES) {
                JsonNode discard = queue.stream().filter(event -> !terminalEvent(event)).findFirst().orElse(null);
                if (discard == null) {
                    if (!terminalEvent(message)) {
                        return; // 只丢增量，终态优先保留
                    }
                    markEventStreamLost("终态帧积压");
                    return;
                }
                queue.remove(discard);
                bytes -= discard.toString().getBytes(StandardCharsets.UTF_8).length;
            }
            queue.add(message);
            eventBytes.put(sessionId, bytes + size);
        }
    }

    private static boolean terminalEvent(JsonNode envelope) {
        String type = envelope.path("params").path("type").asString("");
        return "message.complete".equals(type) || "error".equals(type);
    }

    /**
     * 把事件流标记为不可信：此后<b>空 drain 必须抛错</b>（见 {@link #drainEvents}）。
     *
     * <p>刻意不把 {@code unavailable} 也置上：RPC 仍可能可用（例如只是某一帧超限），而会话类调用失败
     * 会由传输层自己按超时/EOF 报错。这里只回答一个问题——「事件流还能不能代表真相」，答案是「不能」。
     *
     * <p>标记是<b>按进程</b>的：{@code ensureStarted} 起新子进程时会复位，因为新进程就是一条新的事件流。
     */
    private void markEventStreamLost(String reason) {
        eventStreamLost = true;
        log.warn("Hermes stdio 事件流已标记不可信（{}）：后续终态只能靠对账", reason);
    }

    /** 会话类 RPC：解包 {@code result}，JSON-RPC {@code error} 直接失败（既有行为的唯一入口）。 */
    private JsonNode request(String method, Map<String, Object> params) {
        ObjectNode object = objectMapper.createObjectNode();
        params.forEach((key, value) -> object.set(key, objectMapper.valueToTree(value)));
        JsonNode response = exchange(method, object, timeout);
        JsonNode error = response.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            throw new IllegalStateException("Hermes 拒绝 " + method + "：" + error.toString());
        }
        JsonNode result = response.get("result");
        return result == null ? objectMapper.createObjectNode() : result;
    }

    /**
     * 发一帧并等回执，返回<b>原始</b>回包。
     *
     * <p>锁只覆盖「启动 + 写管道 + 登记 pending」，等待在锁外——一次慢 reload 不会挡住其它在途调用。
     * {@code budget} 是这次调用的硬上界；超时/中断/EOF 都必须精确失败，并在离开时清掉自己的 pending 条目。
     */
    private JsonNode exchange(String method, ObjectNode params, Duration budget) {
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        long id;
        synchronized (this) {
            try {
                if (!ensureStarted()) {
                    throw new IllegalStateException("Hermes 执行单元不可用（未配置或启动失败）");
                }
            } catch (IOException ex) {
                throw new IllegalStateException("Hermes 执行单元启动失败", ex);
            }
            id = ids.incrementAndGet();
            pending.put(id, future);
            try {
                Map<String, Object> message = new LinkedHashMap<>();
                message.put("jsonrpc", "2.0");
                message.put("id", id);
                message.put("method", method);
                message.put("params", params);
                stdin.write(objectMapper.writeValueAsString(message));
                stdin.write("\n");
                stdin.flush();
            } catch (IOException ex) {
                pending.remove(id);
                throw new IllegalStateException("写入 Hermes 失败：" + method, ex);
            }
        }
        try {
            return future.get(budget.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            throw new IllegalStateException("Hermes 在 " + budgetText(budget) + " 内未回包：" + method);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 Hermes 回包被中断：" + method);
        } catch (ExecutionException ex) {
            throw new IllegalStateException("Hermes 回包异常：" + method, ex.getCause());
        } finally {
            // 成功时读线程已经摘掉；超时/中断/EOF 时由这里兜底——残留条目永远不会被唤醒。
            pending.remove(id);
        }
    }

    /** 预算的可读写法：亚秒预算按毫秒打印，否则「0s 内未回包」会掩盖真实上界。 */
    private static String budgetText(Duration budget) {
        return budget.toMillis() < 1000 ? budget.toMillis() + "ms" : budget.toSeconds() + "s";
    }

    private boolean ensureStarted() throws IOException {
        if (process != null && process.isAlive()) {
            return true;
        }
        if (command.isEmpty() || unavailable) {
            return false;
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        if (cwd != null) {
            builder.directory(new java.io.File(cwd));
        }
        // 白名单而不是黑名单：只交出助手自身运行需要的最小环境。
        // （黑名单会漏 DATABASE_URL/MYSQL_PWD/GOOGLE_APPLICATION_CREDENTIALS，又会误删助手自己的 API key。）
        Set<String> keep = Set.of("PATH", "HOME", "LANG", "LC_ALL", "TMPDIR", "TERM", "SHELL", "USER", "LOGNAME");
        Map<String, String> env = builder.environment();
        env.keySet().removeIf(key -> !keep.contains(key) && !key.startsWith("HERMES_"));
        if (!environment.isEmpty()) {
            // 显式变量留在白名单之后：执行单元靠它把 HERMES_HOME 指到自己的 profile 副本上。
            env.putAll(environment);
        }
        process = builder.start();
        // 新子进程 = 新的事件流：上一次的「事件流已丢」与残留缓冲都不再适用于它（否则新会话的
        // 空 drain 会永远抛错，一条健康的新进程被旧标记拖成不可用）。
        synchronized (eventLock) {
            events.clear();
            eventBytes.clear();
            eventStreamLost = false;
        }
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        BufferedReader stderr = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));
        Thread reader = new Thread(() -> readLoop(stdout), "hermes-stdout");
        reader.setDaemon(true);
        reader.start();
        Thread errReader = new Thread(() -> {
            try (stderr) {
                String line;
                while ((line = stderr.readLine()) != null) {
                    log.debug("[hermes stderr] {}", line);
                }
            } catch (IOException ignored) {
                // 进程退出时读流关闭属正常。
            }
        }, "hermes-stderr");
        errReader.setDaemon(true);
        errReader.start();
        if (registerShutdownHook) {
            Runtime.getRuntime().addShutdownHook(new Thread(this::close, "hermes-shutdown"));
        }
        return true;
    }

    private void readLoop(BufferedReader reader) {
        try (reader) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode message;
                try {
                    message = objectMapper.readTree(line);
                } catch (RuntimeException ex) {
                    log.debug("忽略非 JSON 行：{}", line);
                    continue;
                }
                JsonNode idNode = message.get("id");
                if (idNode == null || idNode.isNull()) {
                    // 通知帧：终态（message.complete）/ 错误 / 进度都从这里进来，必须收进按会话的缓冲，
                    // 否则 awaitFinalText 永远等不到终态（见 drainEvents 的注释）。
                    ingestEvent(message);
                    continue;
                }
                long id = idNode.asLong();
                CompletableFuture<JsonNode> future = pending.remove(id);
                if (future != null) {
                    future.complete(message);
                }
            }
        } catch (IOException ex) {
            log.debug("Hermes stdout 关闭：{}", ex.toString());
        } finally {
            // 缓冲里的终态仍可先被取走；之后的空 drain 必须显式报「事件流已丢」。
            eventStreamLost = true;
            failAllPending(new IllegalStateException("Hermes 进程已退出"));
        }
    }

    private void failAllPending(RuntimeException cause) {
        pending.forEach((id, future) -> future.completeExceptionally(cause));
        pending.clear();
    }

    public synchronized void close() {
        eventStreamLost = true;
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        process = null;
        stdin = null;
    }

    private static String text(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && !value.isNull() && !value.asString("").isBlank()) {
                return value.asString();
            }
        }
        return "";
    }
}
