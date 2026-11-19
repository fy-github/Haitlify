package com.haitlify.aitest.platform.assistant.conversation;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>启动时的「单元目录根位置」校验</b>：证明单元私有的 {@code HERMES_HOME} 落在 Hermes 根树之下。
 *
 * <p><b>为什么这值得一次启动校验</b>：单元的 {@code HERMES_HOME} 就是它的目录
 * （{@code <root>/unit-XXXXXXXX}）。只有当这个目录在<b>默认 Hermes 根树之下</b>（例如
 * {@code ~/.hermes/...}）时，子网关才会借用宿主已经装好的运行时，1~2 秒就绪；一旦落在外面
 * （{@code /tmp}、{@code /var/lib} 等），子网关会把它当成一个全新的数据根，打印
 * {@code preparing dependencies for this data root} 并跑一次完整 {@code uv sync}（约 1.3G、分钟级），
 * 并发建单元还会在同一把安装锁上互抢。也就是说：<b>每建一个单元就重装一次运行时，秒级变分钟级</b>。
 * 这件事不会让任何既有断言变红，只会表现为「第一轮特别慢」——所以要在这里说出来。
 *
 * <p><b>Hermes 根的推导顺序</b>（不写死、不猜）：
 * <ol>
 *   <li>基准 profile 形如 {@code <root>/profiles/<name>} → 取 {@code <root>}。这是部署方已经表达过的意图，
 *       比环境变量更权威；</li>
 *   <li>否则看环境变量 {@code HERMES_HOME}（由宿主给出）；</li>
 *   <li>两者都拿不到 → <b>无法判定</b>：只 WARN 一次，绝不默认成 {@code ~/.hermes}，也绝不替部署方
 *       选一个路径。</li>
 * </ol>
 *
 * <p><b>三种结论的纪律</b>：
 * <ul>
 *   <li>根内 → 什么都不做；</li>
 *   <li>根外 → 默认 <b>WARN</b>（启动照旧：可能是部署方刻意把单元放到大磁盘上，代价要自知），
 *       {@code require-root-under-hermes-root=true} 时才 {@link IllegalStateException} 拒绝启动；</li>
 *   <li>无法判定 → 只 WARN。<b>开关不把「不知道」当成「根外」</b>：没有 {@code HERMES_HOME} 的合法部署
 *       不该因此起不来。</li>
 * </ul>
 *
 * <p><b>比较是路径段比较</b>（{@link Path#startsWith(Path)}，两边先转绝对路径并归一化），不是字符串前缀：
 * {@code <home>/.hermesX} 与 {@code <home>/.hermes} 共享字符串前缀却是两个目录，用
 * {@code String.startsWith} 会把前者误判成「在根树里」——那正好是唯一一个「看起来通过、实际每单元重装」的假绿。
 * 归一化同时吃掉了 {@code ..} 这种「路径里写着在下面、实际指到外面」的写法。
 */
final class ExecutionUnitRootPlacement {

    /** Hermes 根的环境变量名：只在 base-profile 形状推导不出根时才是后备。 */
    static final String HERMES_HOME = "HERMES_HOME";

    /** 「根外是否拒绝启动」的属性名（默认 false）；告警文案带上它，部署方才知道怎么升级成硬失败。 */
    static final String REQUIRE_PROPERTY =
        "app.assistant.conversation.execution-units.require-root-under-hermes-root";

    /** `<root>/profiles/<name>` 里的固定段。 */
    private static final String PROFILES_SEGMENT = "profiles";

    private static final Logger log = LoggerFactory.getLogger(ExecutionUnitRootPlacement.class);

    /** 一次校验的结论。{@code hermesRoot} 只在 {@link Verdict#UNKNOWN} 时为 {@code null}。 */
    enum Verdict {
        /** 单元目录根在推导出的 Hermes 根树之下：复用已装好的运行时，无需动作。 */
        INSIDE,
        /** 在 Hermes 根树之外：每建一个单元都会重装一次运行时。 */
        OUTSIDE,
        /** 推导不出 Hermes 根：不猜路径，只告警。 */
        UNKNOWN
    }

    /** 结论 + 参与比较的两个规范化路径（只用于日志文案与断言，不含任何用户数据）。 */
    record Placement(Verdict verdict, Path unitsRoot, Path hermesRoot) {

        Placement {
            if (verdict == null || unitsRoot == null) {
                throw new IllegalArgumentException("位置校验结论与单元目录根必填");
            }
            if (verdict == Verdict.UNKNOWN && hermesRoot != null) {
                throw new IllegalArgumentException("无法判定时不得伪造一个 Hermes 根（" + hermesRoot + "）");
            }
            if (verdict != Verdict.UNKNOWN && hermesRoot == null) {
                throw new IllegalArgumentException("已判定（" + verdict + "）时必须给出参与比较的 Hermes 根");
            }
        }
    }

    private ExecutionUnitRootPlacement() {
    }

    /**
     * 只判定、不告警、不抛异常（测试与需要自己决定怎么处置的调用方用）。
     *
     * @param baseProfile 基准 profile 路径（只做形状匹配，<b>不</b>读文件）
     * @param unitsRoot   单元目录根的配置值（不要求已存在）
     * @param hermesHome  {@code HERMES_HOME} 的取值，{@code null}/空白 = 没有
     */
    static Placement inspect(Path baseProfile, Path unitsRoot, String hermesHome) {
        if (unitsRoot == null) {
            throw new IllegalArgumentException("校验单元目录根位置时 root 必填");
        }
        Path units = absolute(unitsRoot);
        Optional<Path> hermesRoot = hermesRootOf(baseProfile, hermesHome);
        if (hermesRoot.isEmpty()) {
            return new Placement(Verdict.UNKNOWN, units, null);
        }
        return new Placement(units.startsWith(hermesRoot.get()) ? Verdict.INSIDE : Verdict.OUTSIDE, units,
            hermesRoot.get());
    }

    /**
     * 推导 Hermes 根：base-profile 的 {@code <root>/profiles/<name>} 形状优先，其次 {@code HERMES_HOME}。
     *
     * <p>只做路径运算：不访问文件系统（不读真实 profile、不解析符号链接），因此启动阶段永远不会因为
     * 目录还没建好而误判。
     */
    static Optional<Path> hermesRootOf(Path baseProfile, String hermesHome) {
        Path fromProfile = hermesRootFromBaseProfile(baseProfile);
        if (fromProfile != null) {
            return Optional.of(fromProfile);
        }
        String fromEnvironment = hermesHome == null ? "" : hermesHome.trim();
        if (fromEnvironment.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(absolute(Path.of(fromEnvironment)));
        } catch (InvalidPathException malformed) {
            // 环境变量不是可用路径 = 没给：不猜、也不据此拒绝启动。
            return Optional.empty();
        }
    }

    /**
     * 执行校验：把结论落到「日志」或「启动失败」上。
     *
     * @return 判定结论（{@link Verdict#OUTSIDE} 与 {@link Verdict#UNKNOWN} 都已经记过 WARN）
     * @throws IllegalStateException 根外<b>且</b> {@code requireRootUnderHermesRoot=true}：拒绝启动；
     *                               此时一个目录都还没建（调用点在校验之后才建目录）
     */
    static Placement enforce(Path baseProfile, Path unitsRoot, String hermesHome, boolean requireRootUnderHermesRoot) {
        Placement placement = inspect(baseProfile, unitsRoot, hermesHome);
        switch (placement.verdict()) {
            case INSIDE -> {
                // 根内：子网关复用已装好的运行时，不需要任何动作，也不该刷日志。
            }
            case UNKNOWN -> log.warn(unknownMessage(baseProfile));
            case OUTSIDE -> {
                String message = outsideMessage(placement);
                if (requireRootUnderHermesRoot) {
                    throw new IllegalStateException("拒绝启动：" + message + "（" + REQUIRE_PROPERTY
                        + "=true：要求 root 必须在 Hermes 根树之下）");
                }
                log.warn(message);
            }
        }
        return placement;
    }

    /** 根外的文案：必须说清「每建一个单元重装一次运行时，秒级变分钟级」，否则读日志的人不会去改配置。 */
    static String outsideMessage(Placement placement) {
        return "执行单元目录根不在 Hermes 根树之下（root=" + placement.unitsRoot() + "，推导出的 Hermes 根="
            + placement.hermesRoot() + "）：每建一个执行单元，子网关都会把这个单元目录当成全新的数据根，"
            + "打印 preparing dependencies for this data root 并跑一次完整 uv sync（约 1.3G）；"
            + "单元就绪从秒级变成分钟级，并发建单元还会在同一把安装锁上互抢。"
            + "把 root 放到 Hermes 根树之下（例如 <Hermes 根>/execution-units）即可复用已装好的运行时。";
    }

    /** 无法判定时的文案：说明两个来源都没拿到，并且明确不猜、不使用任何默认路径。 */
    static String unknownMessage(Path baseProfile) {
        return "无法判定 Hermes 根，跳过执行单元目录根的位置校验（base-profile="
            + (baseProfile == null ? "未给出" : absolute(baseProfile)) + " 不是 <root>/profiles/<name> 形状，"
            + "也没有 HERMES_HOME）：不猜路径、不使用任何默认 Hermes 根。"
            + "若 root 不在 Hermes 根树之下，每建一个单元都会重装一次运行时（uv sync，约 1.3G），秒级变分钟级。";
    }

    private static Path hermesRootFromBaseProfile(Path baseProfile) {
        if (baseProfile == null) {
            return null;
        }
        Path profiles = absolute(baseProfile).getParent();
        if (profiles == null || profiles.getFileName() == null
            || !PROFILES_SEGMENT.equals(profiles.getFileName().toString())) {
            return null;
        }
        // `<root>/profiles/<name>` 的 `profiles` 之上就是根；只有 `<root>` 直接是文件系统根时才可能为 null。
        return profiles.getParent();
    }

    private static Path absolute(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
