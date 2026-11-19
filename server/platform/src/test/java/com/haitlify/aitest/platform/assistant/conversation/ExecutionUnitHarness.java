package com.haitlify.aitest.platform.assistant.conversation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * 执行单元用例的公共夹具：一个<b>临时</b>基准 profile + 一个<b>真实子进程</b>的网关桩。
 *
 * <p>三条纪律：
 * <ul>
 *   <li><b>只用临时目录</b>：基准 profile、单元目录根、桩脚本都在 {@code @TempDir} 派生出来的目录里，
 *       任何用例都不会读到 {@code ~/.hermes/config.yaml} 或其它真实 profile；</li>
 *   <li><b>子进程是真的</b>：桩是 {@code python3} 脚本，按换行分隔 JSON-RPC 应答，所以「子进程真的被拉起来」
 *       「回收真的杀了它」「两个单元的 reload 真的并行」都是 observable 的事实，而不是 mock 的假设；</li>
 *   <li><b>不调模型、不连网</b>：桩只回答 {@code session.*}/{@code prompt.submit}/{@code reload.mcp}，
 *       终态是固定文本。</li>
 * </ul>
 *
 * <p>桩的观察点全部落在<b>单元自己的 HERMES_HOME</b> 里（这也是被断言的隔离事实）：
 * {@code pid}（进程号，回收断言用）、{@code hermes-home}（子进程实际看到的 HERMES_HOME）、
 * {@code snapshots/}（每次 {@code prompt.submit} 时刻的 {@code config.yaml} 全文）。
 */
final class ExecutionUnitHarness implements AutoCloseable {

    /** 桩脚本里 {@code reload.mcp} 的可选延迟（毫秒），用于并发/慢 reload 用例。 */
    static final String RELOAD_DELAY_ENV = "STUB_RELOAD_DELAY_MS";
    /** 桩脚本里 {@code reload.mcp} 的会合目录：两个单元必须同时在 reload 里才能返回成功。 */
    static final String RENDEZVOUS_ENV = "STUB_RENDEZVOUS_DIR";
    /**
     * 终态门：设置了它时，桩在回 {@code streaming} 之后、发 {@code message.complete} 之前等这个文件出现
     * （上限 10s）。用来把「一轮已受理、仍持有许可、还没终态」这个窗口固定住。
     */
    static final String TERMINAL_GATE_ENV = "STUB_TERMINAL_GATE";
    /**
     * 派发失败门：设置了它（{@code =1}）时，桩对 {@code session.create} 回一个协议错误。
     *
     * <p>用来构造「派发抛 {@link RuntimeException}」这条出口：服务层必须把账本落 UNKNOWN，并且<b>归还
     * 单元的在飞租约</b>——不归还的话那个单元此后永远回收不掉（子进程与目录泄漏到进程退出）。
     */
    static final String FAIL_CREATE_ENV = "STUB_FAIL_CREATE";

    private static final String STUB = """
        import json, os, sys, time, pathlib

        home = os.environ["HERMES_HOME"]
        pathlib.Path(os.path.join(home, "pid")).write_text(str(os.getpid()))
        pathlib.Path(os.path.join(home, "hermes-home")).write_text(home)
        seq = 0

        def send(payload):
            sys.stdout.write(json.dumps(payload) + "\\n")
            sys.stdout.flush()

        def snapshot():
            directory = os.path.join(home, "snapshots")
            os.makedirs(directory, exist_ok=True)
            number = len(os.listdir(directory)) + 1
            with open(os.path.join(home, "config.yaml"), encoding="utf-8") as handle:
                text = handle.read()
            with open(os.path.join(directory, "snapshot-%02d.yaml" % number), "w", encoding="utf-8") as handle:
                handle.write(text)

        def rendezvous():
            directory = os.environ.get("STUB_RENDEZVOUS_DIR")
            if not directory:
                return True
            os.makedirs(directory, exist_ok=True)
            pathlib.Path(os.path.join(directory, "%d-start" % os.getpid())).touch()
            deadline = time.time() + 5.0
            while time.time() < deadline:
                if len(os.listdir(directory)) >= 2:
                    return True
                time.sleep(0.01)
            return False

        for line in sys.stdin:
            line = line.strip()
            if not line:
                continue
            message = json.loads(line)
            method = message.get("method")
            mid = message.get("id")
            params = message.get("params") or {}
            if method == "reload.mcp":
                delay = float(os.environ.get("STUB_RELOAD_DELAY_MS", "0")) / 1000.0
                if delay > 0:
                    time.sleep(delay)
                if not rendezvous():
                    send({"jsonrpc": "2.0", "id": mid,
                          "error": {"code": -32000, "message": "reload did not overlap another unit"}})
                    continue
                send({"jsonrpc": "2.0", "id": mid,
                      "result": {"status": "reloaded", "loaded_rev": "abc123456789"}})
            elif method == "session.create":
                if os.environ.get("STUB_FAIL_CREATE") == "1":
                    send({"jsonrpc": "2.0", "id": mid,
                          "error": {"code": -32000, "message": "session create refused by stub"}})
                    continue
                seq += 1
                send({"jsonrpc": "2.0", "id": mid,
                      "result": {"session_id": "live-%d" % seq, "stored_session_id": "stored-%d" % seq}})
            elif method == "session.resume":
                seq += 1
                send({"jsonrpc": "2.0", "id": mid,
                      "result": {"session_id": "live-%d" % seq, "resumed": params.get("session_id"),
                                 "message_count": 0, "messages": [], "running": False, "status": "idle"}})
            elif method == "prompt.submit":
                snapshot()
                session = params.get("session_id")
                send({"jsonrpc": "2.0", "id": mid, "result": {"status": "streaming"}})
                gate = os.environ.get("STUB_TERMINAL_GATE")
                if gate:
                    deadline = time.time() + 10.0
                    while time.time() < deadline and not os.path.exists(gate):
                        time.sleep(0.01)
                send({"jsonrpc": "2.0", "method": "event",
                      "params": {"session_id": session, "type": "message.complete",
                                 "payload": {"status": "complete", "text": "reply:" + str(params.get("text"))}}})
            else:
                send({"jsonrpc": "2.0", "id": mid,
                      "error": {"code": -32601, "message": "unsupported method"}})
        """;

    private final Path root;
    private final Path profile;
    private final Path unitsRoot;
    private final Path script;
    private final Path rendezvous;

    private ExecutionUnitHarness(Path root) {
        this.root = root;
        this.profile = root.resolve("profile");
        this.unitsRoot = root.resolve("units");
        this.script = root.resolve("stub-gateway.py");
        this.rendezvous = root.resolve("rendezvous");
    }

    /**
     * 建夹具：临时根目录 + 基准 profile（{@code config.yaml} 只含顶层 {@code mcp_servers}，
     * 与真实 profile 的形状一致）+ 桩脚本。
     *
     * @param profileConfigYaml 基准 profile 的 {@code config.yaml} 内容
     */
    static ExecutionUnitHarness create(Path root, String profileConfigYaml) {
        ExecutionUnitHarness harness = new ExecutionUnitHarness(root);
        try {
            Files.createDirectories(harness.profile);
            Files.createDirectories(harness.unitsRoot);
            Files.writeString(harness.profile.resolve("config.yaml"), profileConfigYaml, StandardCharsets.UTF_8);
            Files.writeString(harness.profile.resolve(".env"), "STUB_GATEWAY=1\n", StandardCharsets.UTF_8);
            Files.writeString(harness.script, STUB, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("无法准备执行单元夹具", failure);
        }
        return harness;
    }

    static String defaultProfileConfig() {
        return "mcp_servers: {}\n";
    }

    Path root() {
        return root;
    }

    Path profile() {
        return profile;
    }

    Path unitsRoot() {
        return unitsRoot;
    }

    /** 桩脚本路径（用作 {@code app.assistant.hermes.command} 的第二个参数）。 */
    Path script() {
        return script;
    }

    /**
     * 池参数。默认容量 4、TTL 5 分钟、回收节拍 1 秒；并发/回收用例自己缩小这些值。
     *
     * @param extraEnvironment 追加给单元子进程的环境变量（{@link #RENDEZVOUS_ENV} 等）
     */
    ExecutionUnitSettings settings(int capacity, Duration idleTtl, Duration reapInterval,
                                   Map<String, String> extraEnvironment) {
        return new ExecutionUnitSettings(profile, unitsRoot, List.of("python3", script.toString()),
            capacity, idleTtl, reapInterval, Duration.ofSeconds(10), Duration.ofSeconds(10), "haitlify", 64,
            Duration.ofSeconds(2), extraEnvironment);
    }

    ExecutionUnitSettings settings() {
        return settings(4, Duration.ofMinutes(5), Duration.ofSeconds(1), Map.of());
    }

    /** 单元目录里桩写下的 pid（{@code 0} = 还没有写出来）。 */
    static long pidOf(Path home) {
        Path pid = home.resolve("pid");
        try {
            if (!Files.isRegularFile(pid)) {
                return 0L;
            }
            return Long.parseLong(Files.readString(pid, StandardCharsets.UTF_8).trim());
        } catch (IOException | NumberFormatException unreadable) {
            return 0L;
        }
    }

    static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("无法读取 " + file, failure);
        }
    }

    /** 桩在 {@code prompt.submit} 时刻看到的配置文件快照（按序）。 */
    static List<String> snapshots(Path home) {
        Path directory = home.resolve("snapshots");
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var entries = Files.list(directory)) {
            return entries.sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .map(ExecutionUnitHarness::read).toList();
        } catch (IOException failure) {
            throw new IllegalStateException("无法列出快照目录 " + directory, failure);
        }
    }

    /** 有界轮询：用于「子进程起来了吗 / 死了吗 / 目录删了吗」这类最终一致的事实。 */
    static void await(String what, Duration budget, BooleanSupplier condition) {
        long deadline = System.nanoTime() + budget.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待 " + what + " 时被中断");
            }
        }
        throw new AssertionError("等待超时（" + budget.toMillis() + "ms）：" + what);
    }

    static boolean alive(long pid) {
        return pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    static Map<String, String> environment(String key, String value) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put(key, value);
        return environment;
    }

    static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 收尾尽力而为：真正的清理断言针对的是单元目录本身。
                }
            });
        } catch (IOException ignored) {
            // 同上。
        }
    }

    @Override
    public void close() {
        deleteTree(root);
    }
}
