package com.haitlify.aitest.platform.assistant.conversation;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 执行单元池的不可变参数。
 *
 * <p>参数分成三类，各有各的失败纪律：
 * <ol>
 *   <li><b>基准 profile</b>（{@link #baseProfile()}）与 <b>单元目录根</b>（{@link #unitsRoot()}）：只从配置里
 *       读，<b>绝不</b>回退到操作者默认 Hermes 配置文件——那既是别人的真实配置，也是
 *       「部署方以为在用隔离目录」的静默错误。缺文件在<b>构造时</b>就失败（见 {@link #requireBaseProfile()}），
 *       而不是等第一个用户提交时才失败；</li>
 *   <li><b>池参数</b>（容量、空闲 TTL、回收节拍）：只影响「能不能服务下一轮」，不允许影响正确性；
 *       容量耗尽必须显式失败，不得驱逐在跑的单元、不得共享；</li>
 *   <li><b>子进程参数</b>（命令、RPC 预算、reload 预算、会话工作目录、每个单元的会话表上限、
 *       额外环境变量）：逐单元独立装配，单元之间不共享任何可变状态。</li>
 * </ol>
 *
 * <p>{@link #environment()} 只用于<b>补充</b>子进程环境（测试桩用它传延迟/会合目录）。{@code HERMES_HOME}
 * 永远由单元自己注入并覆盖同名变量：它指向该单元私有的 profile 目录，是隔离的物理边界。
 */
record ExecutionUnitSettings(Path baseProfile, Path unitsRoot, List<String> command,
                             int capacity, Duration idleTtl, Duration reapInterval, Duration rpcTimeout,
                             Duration reloadTimeout, String serverName, int maxConversations,
                             Duration resumeIdleWait, Map<String, String> environment) {

    /** 单个 profile 文件的上限：配置文件与 .env 都只应该很小；超过它一律拒绝复制（不流式拷贝巨物）。 */
    static final long MAX_PROFILE_FILE_BYTES = 1L << 20;

    ExecutionUnitSettings {
        if (baseProfile == null) {
            throw new IllegalArgumentException("执行单元必须由配置指定基准 profile 路径");
        }
        if (unitsRoot == null) {
            throw new IllegalArgumentException("执行单元必须由配置指定单元目录根");
        }
        if (command == null || command.isEmpty() || command.stream().anyMatch(part -> part == null || part.isBlank())) {
            throw new IllegalArgumentException("执行单元必须给出网关子进程命令（app.assistant.hermes.command）");
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("执行单元池容量必须为正数");
        }
        if (idleTtl == null || idleTtl.isZero() || idleTtl.isNegative()) {
            throw new IllegalArgumentException("执行单元空闲回收期限必须为正");
        }
        if (reapInterval == null || reapInterval.isZero() || reapInterval.isNegative()) {
            throw new IllegalArgumentException("执行单元回收节拍必须为正");
        }
        if (rpcTimeout == null || rpcTimeout.isZero() || rpcTimeout.isNegative()) {
            throw new IllegalArgumentException("网关 RPC 预算必须为正");
        }
        if (reloadTimeout == null || reloadTimeout.isZero() || reloadTimeout.isNegative()) {
            throw new IllegalArgumentException("reload 预算必须为正");
        }
        if (serverName == null || serverName.isBlank()) {
            throw new IllegalArgumentException("网关 MCP 条目名必填");
        }
        if (maxConversations <= 0) {
            throw new IllegalArgumentException("每单元会话表上限必须为正");
        }
        if (resumeIdleWait == null || resumeIdleWait.isZero() || resumeIdleWait.isNegative()) {
            throw new IllegalArgumentException("恢复后等待会话空闲的期限必须为正");
        }
        command = List.copyOf(command);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
    }

    /**
     * 证明基准 profile 真的可以用来建单元：{@code config.yaml} 与 {@code .env} 都必须是<b>常规文件</b>
     * （不跟随符号链接）、可读、且不超过 {@link #MAX_PROFILE_FILE_BYTES}。
     *
     * <p>三条都是 fail-closed 的理由：
     * <ul>
     *   <li>缺文件时「建单元」会得到一个没有配置的网关。那会退化成「没有 MCP 条目」，
     *       而平台会以为隔离已生效；</li>
     *   <li><b>符号链接</b>可以指向任何地方（包括真实用户的默认 Hermes 配置文件）。复制链接
     *       目标等于把别人的真实配置搬进单元目录，是本功能明确禁止的那件事；</li>
     *   <li>超大文件只可能是「指错了目录」，与其复制不如拒绝。</li>
     * </ul>
     *
     * <p>本方法由池的<b>构造器</b>调用一次（启动即发现），由单元创建时再调用一次（运行期 profile 被挪走
     * 也要 fail-closed）。
     */
    void requireBaseProfile() {
        Path config = baseProfile.resolve("config.yaml");
        Path dotEnv = baseProfile.resolve(".env");
        requireRegularFile(config, "config.yaml");
        requireRegularFile(dotEnv, ".env");
    }

    private static void requireRegularFile(Path file, String name) {
        boolean usable = !Files.isSymbolicLink(file)
            && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
            && Files.isReadable(file);
        if (usable) {
            try {
                usable = Files.size(file) <= MAX_PROFILE_FILE_BYTES;
            } catch (java.io.IOException unreadable) {
                usable = false;
            }
        }
        if (!usable) {
            throw new ExecutionUnitUnavailableException(
                ExecutionUnitUnavailableException.Reason.BASE_PROFILE_UNAVAILABLE,
                "基准 profile 缺少可读的常规文件 " + name + "（" + file + "）：拒绝创建执行单元，"
                    + "不回退到任何默认 profile");
        }
    }
}
