package com.haitlify.aitest.platform.assistant.conversation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.haitlify.aitest.platform.assistant.hermes.HermesTuiGateway;
import com.haitlify.aitest.platform.assistant.runtime.HermesConversationService;

import tools.jackson.databind.ObjectMapper;

/**
 * <b>一个用户 × 一个项目 × 一个 actor 的执行单元</b>：一个独立网关子进程 + 一份私有 profile。
 *
 * <p>为什么单元必须是<b>进程</b>而不是「共享进程里的一张表」：Hermes 的 MCP 条目在会话创建的那一刻被
 * 读走并绑定到会话，而条目在<b>一个网关进程内全局唯一</b>。共享进程里「换个条目」在物理上就是改写同一个
 * 文件、让所有在跑的会话共享同一个来源，这正是要消除的限制（见 {@code mcp/README.md} 与
 * {@link GatewayMcpPermit}）。把单元做成子进程之后：
 * <ul>
 *   <li>每个单元读<b>自己的</b> {@code <unit>/config.yaml}，别人的 lease URL 无从出现；</li>
 *   <li>许可下沉到单元内（每单元一把），跨单元天然并行，不再有「第二个用户并发就被拒」的终态；</li>
 *   <li>回收单元 = 杀子进程 + 删目录，没有「残留注册表」「残留会话表」这种跨用户的活状态。</li>
 * </ul>
 *
 * <p><b>物理布局</b>（全部在单元目录根之下，随机名）：
 * <pre>
 * &lt;unitsRoot&gt;/unit-XXXXXXXX/          0700   &lt;- HERMES_HOME
 *   config.yaml                        0600   &lt;- 基准 profile 的副本；本单元唯一的 MCP 条目在这里
 *   .env                               0600   &lt;- 基准 profile 的副本
 * </pre>
 * 目录与文件权限是隔离的一部分：单元目录里既有模型凭据（{@code .env}）也有本轮的租约 URL，任何非属主
 * 可读都是泄漏。复制时<b>不跟随符号链接</b>（见 {@link ExecutionUnitSettings#requireBaseProfile()}）。
 *
 * <p><b>生命周期</b>：{@link #close()} 是唯一出口，它按「先杀子进程、再撤销许可、最后删目录」的顺序做
 * 确定性清理；顺序不能反——子进程活着时删掉配置目录，网关会在一半配置上继续跑（并且可能在退出前把
 * lease URL 写进日志）。池在空闲回收与关闭时都只调用它。
 */
final class ExecutionUnit implements AutoCloseable {

    /** 单元目录的权限（只允许属主进入）。 */
    static final java.util.Set<java.nio.file.attribute.PosixFilePermission> HOME_PERMISSIONS =
        java.nio.file.attribute.PosixFilePermissions.fromString("rwx------");
    /** 单元内 profile 文件的权限（只允许属主读写）。 */
    static final java.util.Set<java.nio.file.attribute.PosixFilePermission> FILE_PERMISSIONS =
        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------");

    private final ExecutionUnitKey key;
    private final Path home;
    private final Path configFile;
    private final HermesTuiGateway gateway;
    private final GatewayMcpControl control;
    private final GatewayMcpConfigurator configurator;
    private final GatewayMcpPermit permit;
    private final HermesConversationService conversations;
    private volatile long lastUsedAt;
    private volatile boolean closed;
    /**
     * <b>在飞租约</b>的计数（与 MCP 许可无关，见 {@link UnitLease}）。只在 {@link #leaseLock} 内读写：
     * 计数与「单元是否已关闭」必须在同一个临界区里判定，否则「刚判完没关闭、接着就被关掉」会让一份租约
     * 落在已经死掉的单元上。
     */
    private final Object leaseLock = new Object();
    private int inFlightLeases;

    private ExecutionUnit(ExecutionUnitKey key, Path home, HermesTuiGateway gateway, GatewayMcpControl control,
                          GatewayMcpConfigurator configurator, GatewayMcpPermit permit,
                          HermesConversationService conversations, long lastUsedAt) {
        this.key = key;
        this.home = home;
        this.configFile = home.resolve("config.yaml");
        this.gateway = gateway;
        this.control = control;
        this.configurator = configurator;
        this.permit = permit;
        this.conversations = conversations;
        this.lastUsedAt = lastUsedAt;
    }

    /**
     * 建一个单元：临时目录（0700）→ 复制 {@code config.yaml}/{@code .env}（0600）→ 装网关子进程
     * （{@code HERMES_HOME} 指向本目录）→ 配置面/reloader/会话适配。
     *
     * <p><b>失败必须不留痕</b>：任何一步失败都先杀掉已启动的子进程、删掉目录，再抛
     * {@link ExecutionUnitUnavailableException}。半成品单元既是临时目录泄漏，也是一个「看起来在册」的
     * 空单元。
     *
     * @throws ExecutionUnitUnavailableException 基准 profile 不可用 / 目录或复制失败 / 子进程起不来
     */
    static ExecutionUnit create(ExecutionUnitKey key, ExecutionUnitSettings settings, ObjectMapper mapper) {
        settings.requireBaseProfile();
        Path home = null;
        HermesTuiGateway gateway = null;
        try {
            Files.createDirectories(settings.unitsRoot());
            home = createHome(settings.unitsRoot());
            copyProfileFile(settings.baseProfile().resolve("config.yaml"), home.resolve("config.yaml"));
            copyProfileFile(settings.baseProfile().resolve(".env"), home.resolve(".env"));
            // HERMES_HOME 指向本单元目录：子进程读到的就是这一份 config.yaml（含本轮唯一的 MCP 条目）。
            gateway = new HermesTuiGateway(settings.command(), null, rpcTimeoutSeconds(settings), mapper,
                environment(settings, home), false);
            if (!gateway.available()) {
                throw new IllegalStateException("网关子进程无法启动");
            }
            GatewayReloader reloader = new TcpGatewayReloader(gateway::rpc, mapper, settings.reloadTimeout());
            GatewayMcpControl control = new ConfigFileGatewayMcpControl(home.resolve("config.yaml"),
                settings.serverName(), ConfigFileGatewayMcpControl.DEFAULT_PARENT_PATH, reloader, null);
            GatewayMcpConfigurator configurator = new GatewayMcpLeaseConfigurator(control);
            HermesConversationService conversations = new HermesConversationService(gateway, mapper,
                settings.maxConversations(), settings.resumeIdleWait());
            return new ExecutionUnit(key, home, gateway, control, configurator, new GatewayMcpPermit(), conversations,
                System.currentTimeMillis());
        } catch (ExecutionUnitUnavailableException unavailable) {
            // 基准 profile 不可用：同样要收干净已经建出来的目录（不能留半成品）。
            cleanup(gateway, home);
            throw unavailable;
        } catch (IOException | RuntimeException failure) {
            cleanup(gateway, home);
            // 只报类型名：底层消息可能带路径/URL，这里不外泄。
            throw new ExecutionUnitUnavailableException(ExecutionUnitUnavailableException.Reason.START_FAILED,
                "执行单元无法创建（" + failure.getClass().getSimpleName() + "）：已回收子进程与临时目录");
        }
    }

    private static Path createHome(Path root) throws IOException {
        try {
            return Files.createTempDirectory(root, "unit-",
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(HOME_PERMISSIONS));
        } catch (UnsupportedOperationException nonPosix) {
            return Files.createTempDirectory(root, "unit-");
        }
    }

    /** RPC 预算以秒传进网关（亚秒预算向上取整到 1 秒，不给出一个「立刻超时」的预算）。 */
    private static int rpcTimeoutSeconds(ExecutionUnitSettings settings) {
        return (int) Math.max(1L, settings.rpcTimeout().toSeconds());
    }

    /** 失败收尾：先杀子进程（它可能还握着目录里的文件），再删目录。 */
    private static void cleanup(HermesTuiGateway gateway, Path home) {
        if (gateway != null) {
            try {
                gateway.close();
            } catch (RuntimeException ignored) {
                // 子进程可能已经死了：清理是尽力而为，真正的判据是「目录是否还在」。
            }
        }
        deleteTree(home);
    }

    private static void deleteTree(Path home) {
        if (home == null) {
            return;
        }
        try (var paths = Files.walk(home)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 尽力清理：残留文件仍是 0600/0700，且不含明文 token。
                }
            });
        } catch (IOException | java.io.UncheckedIOException ignored) {
            // UncheckedIOException 来自 Files.walk 迭代中途的 I/O 失败（例如目录在遍历中被外部删除）；
            // 只捕 IOException 会让它逃出并留下目录。
            // 同上。
        }
    }

    ExecutionUnitKey key() {
        return key;
    }

    /** 单元私有的 HERMES_HOME（测试与回收断言用；生产代码只用它做日志脱敏后的标识）。 */
    Path home() {
        return home;
    }

    /** 单元唯一的 MCP 条目所在文件（{@code <home>/config.yaml}）：别人的 lease URL 不允许出现在这里。 */
    Path configFile() {
        return configFile;
    }

    HermesTuiGateway gateway() {
        return gateway;
    }

    GatewayMcpControl control() {
        return control;
    }

    GatewayMcpConfigurator configurator() {
        return configurator;
    }

    GatewayMcpPermit permit() {
        return permit;
    }

    HermesConversationService conversations() {
        return conversations;
    }

    /** 记录一次使用（回收判据是「最久未被使用」）。 */
    void touch() {
        lastUsedAt = System.currentTimeMillis();
    }

    long lastUsedAt() {
        return lastUsedAt;
    }

    boolean isClosed() {
        return closed;
    }

    /**
     * 取一份<b>在飞租约</b>（计数 +1）。池在 {@code acquire} 里、<b>返回之前</b>调用它：从这一刻起该单元
     * 就不可能被空闲回收或关闭摘掉，直到持有者归还。
     *
     * <p>为什么计数属于单元而不是池：回收/关闭的判据是「<b>这个</b>单元还有没有一轮在跑」，把它放在一个
     * 全局集合里就需要在回收时与池锁交叉判断，而单元自己的计数在同一次加锁里就给出了答案。
     *
     * @return 租约句柄；<b>单元已关闭</b>时为 {@code null}（调用方必须换一个新单元或显式失败，绝不能
     *         在已关闭的单元上排一轮）
     */
    UnitLease tryLease() {
        synchronized (leaseLock) {
            if (closed) {
                return null;
            }
            inFlightLeases++;
            return new Lease(this);
        }
    }

    /** 归还一份在飞租约（只由 {@link Lease#release()} 调用，幂等由句柄保证）。 */
    private void releaseLease() {
        synchronized (leaseLock) {
            if (inFlightLeases > 0) {
                inFlightLeases--;
            }
            // 关闭时的「等在飞租约归零」用它唤醒。
            leaseLock.notifyAll();
        }
    }

    /** 当前在飞租约数（只读观察：回收/关闭的跳过判据与用例断言用它）。 */
    int inFlightLeases() {
        synchronized (leaseLock) {
            return inFlightLeases;
        }
    }

    /**
     * 有界等待在飞租约归零（池关闭时用）。
     *
     * @return {@code true} = 已经归零（可以安全关闭）；{@code false} = 宽限内没等到
     */
    boolean awaitLeaseDrain(java.time.Duration budget) {
        long deadline = System.nanoTime() + Math.max(0L, budget.toNanos());
        synchronized (leaseLock) {
            while (inFlightLeases > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    return false;
                }
                try {
                    leaseLock.wait(Math.max(1L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining)));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return inFlightLeases == 0;
                }
            }
            return true;
        }
    }

    /**
     * 一份具体的在飞租约：与单元配对，<b>释放幂等</b>（同一个句柄重复 release 只减一次计数）。
     *
     * <p>幂等不是宽容：终态、派发失败、超时、受理窗口的 {@code Error}、关闭清扫都会走到收尾，若任一出口
     * 重复归还，计数会漂移成负数——那会让后续的回收判定读到「没有一轮在跑」而把在飞单元摘掉。
     */
    private static final class Lease implements UnitLease {

        private final ExecutionUnit unit;
        private final java.util.concurrent.atomic.AtomicBoolean released =
            new java.util.concurrent.atomic.AtomicBoolean();

        private Lease(ExecutionUnit unit) {
            this.unit = unit;
        }

        @Override
        public void release() {
            if (released.compareAndSet(false, true)) {
                unit.releaseLease();
            }
        }

        @Override
        public boolean released() {
            return released.get();
        }

        @Override
        public String toString() {
            return "UnitLease[" + unit.key() + (released.get() ? ", released]" : ", held]");
        }
    }

    /** 本单元的 TurnUnit 视图：四个句柄都指向同一个子进程，外加这一轮的<b>在飞租约</b>。 */
    GatewayUnits.TurnUnit turnUnit(UnitLease lease) {
        return new GatewayUnits.TurnUnit(key, gateway, conversations, configurator, permit, lease);
    }

    /**
     * 确定性回收：<b>先杀子进程、再撤销许可、最后删目录</b>。
     *
     * <p>顺序不能反：子进程活着时把配置目录删掉，网关会在一半配置上继续跑（并且可能在退出前把 URL 写进日志）；
     * 许可若留在某个已消失单元的轮次名下，回收就只是「看起来回收了」。
     *
     * <p><b>唯一合法的调用者是池</b>，而且池必须先让在飞租约归零（见 {@code ExecutionUnitPool#close()} 的
     * 有界等待）：本方法不替调用方判断「还有没有一轮在跑」，它一旦执行就一定把进程拆掉。
     */
    @Override
    public void close() {
        synchronized (leaseLock) {
            if (closed) {
                return;
            }
            // 与 tryLease 在同一个临界区里置位：之后任何 tryLease 都拿到 null，不会再有租约落在死单元上。
            closed = true;
        }
        try {
            gateway.close();
        } finally {
            permit.releaseAll();
            deleteTree(home);
        }
    }

    @Override
    public String toString() {
        return "ExecutionUnit[" + key + ", home=" + home.getFileName() + ", "
            + (closed ? "closed" : "open") + "]";
    }

    /** 供实现内部使用：把 profile 文件复制进单元目录（0600、不跟随符号链接）。 */
    static void copyProfileFile(Path source, Path target) throws java.io.IOException {
        if (Files.isSymbolicLink(source) || !Files.isRegularFile(source, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new java.io.IOException("profile file is not a regular file");
        }
        byte[] content = Files.readAllBytes(source);
        if (content.length > ExecutionUnitSettings.MAX_PROFILE_FILE_BYTES) {
            throw new java.io.IOException("profile file is too large");
        }
        Files.write(target, content, java.nio.file.StandardOpenOption.CREATE_NEW,
            java.nio.file.StandardOpenOption.WRITE);
        try {
            Files.setPosixFilePermissions(target, FILE_PERMISSIONS);
        } catch (UnsupportedOperationException nonPosix) {
            // 非 POSIX 文件系统：保留 createTempDirectory/createFile 的默认权限。
        }
    }

    /** 单元的环境变量：{@code HERMES_HOME} 由单元自己决定，额外变量只能是补充。 */
    static Map<String, String> environment(ExecutionUnitSettings settings, Path home) {
        java.util.Map<String, String> env = new java.util.LinkedHashMap<>(settings.environment());
        env.put("HERMES_HOME", home.toString());
        return env;
    }
}
