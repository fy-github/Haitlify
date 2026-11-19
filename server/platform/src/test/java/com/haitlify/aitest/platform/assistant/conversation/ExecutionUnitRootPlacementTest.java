package com.haitlify.aitest.platform.assistant.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.ObjectMapper;

/**
 * <b>单元目录根位置</b>的启动校验。
 *
 * <p>被钉住的事实（子网关只在默认 Hermes 根树之下复用已装好的运行时，落在外面会每单元重装一次，
 * 约 1.3G / 分钟级）：
 * <ol>
 *   <li>根内 → 一条 WARN 都没有，启动照旧；</li>
 *   <li>根外 → <b>WARN</b>（文案必须说清「每建一个单元重装一次运行时，秒级变分钟级」）且<b>启动成功</b>；</li>
 *   <li>根外 + {@code require-root-under-hermes-root=true} → <b>启动失败</b>（且在碰任何目录之前）；</li>
 *   <li>推导不出 Hermes 根 → 只 WARN 一次，不猜路径、不默认操作者配置目录。</li>
 * </ol>
 *
 * <p>路径比较必须是<b>路径段</b>比较：{@code <home>/.hermesX} 不在 {@code <home>/.hermes} 之下，
 * 字符串前缀会把它误判成「在里面」（那正好是唯一一个看起来通过、实际每单元重装的假绿）。
 *
 * <p>全部用例只用 {@link TempDir}：不读任何真实 profile、不启真实 Hermes、不调模型。{@code HERMES_HOME}
 * 一律显式传参（进程环境里可能有真的值，绝不让用例结果取决于它）。
 */
class ExecutionUnitRootPlacementTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path temp;

    private Logger placementLogger;
    private Level originalLevel;
    private ListAppender<ILoggingEvent> events;

    @BeforeEach
    void captureWarnings() {
        placementLogger = (Logger) LoggerFactory.getLogger(ExecutionUnitRootPlacement.class);
        originalLevel = placementLogger.getLevel();
        // 用例断言的是「这个类有没有告警」，因此不受 application-test.yml 里的级别影响。
        placementLogger.setLevel(Level.DEBUG);
        events = new ListAppender<>();
        events.start();
        placementLogger.addAppender(events);
    }

    @AfterEach
    void releaseWarnings() {
        placementLogger.detachAppender(events);
        placementLogger.setLevel(originalLevel);
        events.stop();
    }

    // (1) 根内：一条 WARN 都没有，启动照旧。

    @Test
    void aUnitsRootUnderHermesRootStartsSilently() throws Exception {
        Path hermesRoot = temp.resolve("hermes");
        Path baseProfile = profile(hermesRoot.resolve("profiles").resolve("default"));
        Path unitsRoot = hermesRoot.resolve("execution-units");

        ExecutionUnitPool pool = startPool(baseProfile, unitsRoot, "", false);
        try {
            assertThat(warnings()).as("根内不该有任何位置告警").isEmpty();
            assertThat(Files.isDirectory(unitsRoot)).as("根内：池照旧建目录并启动").isTrue();
        } finally {
            pool.close();
        }
    }

    /** 单元目录根 == Hermes 根本身也算「在之下」：单元目录 `<root>/unit-XXXXXXXX` 仍在根树里。 */
    @Test
    void theHermesRootItselfCountsAsInside() throws Exception {
        Path hermesRoot = temp.resolve("hermes");
        Path baseProfile = profile(hermesRoot.resolve("profiles").resolve("default"));

        ExecutionUnitPool pool = startPool(baseProfile, hermesRoot, "", false);
        try {
            assertThat(warnings()).isEmpty();
        } finally {
            pool.close();
        }
    }

    // (2) 根外：WARN 且启动成功。

    @Test
    void aUnitsRootOutsideHermesRootWarnsButStillStarts() throws Exception {
        Path hermesRoot = temp.resolve("hermes");
        Path baseProfile = profile(hermesRoot.resolve("profiles").resolve("default"));
        Path unitsRoot = temp.resolve("somewhere-else").resolve("execution-units");

        ExecutionUnitPool pool = startPool(baseProfile, unitsRoot, "", false);
        try {
            List<String> warned = warnings();
            assertThat(warned).as("根外必须告警，且只告警一次").hasSize(1);
            assertThat(warned.get(0))
                .as("文案要说清后果：每建一个单元重装一次运行时，秒级变分钟级")
                .contains("不在 Hermes 根树之下")
                .contains("uv sync")
                .contains("秒级变成分钟级");
            assertThat(Files.isDirectory(unitsRoot)).as("WARN 不是失败：池仍然起来了").isTrue();
        } finally {
            pool.close();
        }
    }

    // (3) 根外 + require=true：启动失败，并且在碰任何目录之前。

    @Test
    void aUnitsRootOutsideHermesRootFailsStartupWhenRequired() throws Exception {
        Path hermesRoot = temp.resolve("hermes");
        Path baseProfile = profile(hermesRoot.resolve("profiles").resolve("default"));
        Path unitsRoot = temp.resolve("somewhere-else").resolve("execution-units");

        assertThatThrownBy(() -> startPool(baseProfile, unitsRoot, "", true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("拒绝启动")
            .hasMessageContaining("不在 Hermes 根树之下")
            .hasMessageContaining("require-root-under-hermes-root");
        assertThat(Files.exists(unitsRoot)).as("拒绝必须先于建目录：一个目录都不留").isFalse();
    }

    // (4) 推导不出 Hermes 根：只 WARN 一次，不猜路径。

    @Test
    void anUnrecognisableBaseProfileWithoutHermesHomeOnlyWarns() throws Exception {
        Path baseProfile = profile(temp.resolve("profile"));
        Path unitsRoot = temp.resolve("units");

        ExecutionUnitPool pool = startPool(baseProfile, unitsRoot, "", false);
        try {
            List<String> warned = warnings();
            assertThat(warned).as("无法判定时只 WARN 一次").hasSize(1);
            assertThat(warned.get(0))
                .contains("无法判定 Hermes 根")
                .contains("不猜");
            assertThat(Files.isDirectory(unitsRoot)).isTrue();
        } finally {
            pool.close();
        }
    }

    /**
     * 无法判定时<b>不</b>因为 {@code require=true} 而拒绝启动：开关约束的是「已知根外」这一个可判定的事实，
     * 拿「不知道」当「根外」会让没有 {@code HERMES_HOME} 的合法部署启动不了。
     */
    @Test
    void anUnknownHermesRootWarnsEvenWhenTheSwitchIsOn() throws Exception {
        Path baseProfile = profile(temp.resolve("profile"));
        Path unitsRoot = temp.resolve("units");

        assertThatCode(() -> {
            ExecutionUnitPool pool = startPool(baseProfile, unitsRoot, "", true);
            pool.close();
        }).doesNotThrowAnyException();
        assertThat(warnings()).hasSize(1);
    }

    @Test
    void aBlankHermesHomeIsTreatedAsAbsent() throws Exception {
        Path baseProfile = profile(temp.resolve("profile"));

        assertThat(ExecutionUnitRootPlacement.inspect(baseProfile, temp.resolve("units"), "   ").verdict())
            .isEqualTo(ExecutionUnitRootPlacement.Verdict.UNKNOWN);
    }

    // 推导顺序：base-profile 形状优先，其次 HERMES_HOME。

    @Test
    void theProfileShapeWinsOverHermesHome() throws Exception {
        Path hermesRoot = temp.resolve("hermes");
        Path baseProfile = profile(hermesRoot.resolve("profiles").resolve("default"));
        Path otherHome = temp.resolve("other-hermes");

        assertThat(ExecutionUnitRootPlacement.inspect(baseProfile, hermesRoot.resolve("units"), otherHome.toString())
            .verdict()).isEqualTo(ExecutionUnitRootPlacement.Verdict.INSIDE);
        assertThat(ExecutionUnitRootPlacement.inspect(baseProfile, otherHome.resolve("units"), otherHome.toString())
            .verdict())
            .as("形状推导出的根才是权威：环境变量指向的目录不算")
            .isEqualTo(ExecutionUnitRootPlacement.Verdict.OUTSIDE);
    }

    @Test
    void hermesHomeIsTheFallbackWhenTheProfileShapeIsUnrecognisable() throws Exception {
        Path baseProfile = profile(temp.resolve("deploy-profile"));
        Path hermesRoot = temp.resolve("hermes");

        ExecutionUnitPool pool = startPool(baseProfile, hermesRoot.resolve("units"), hermesRoot.toString(), false);
        try {
            assertThat(warnings()).isEmpty();
        } finally {
            pool.close();
        }
    }

    // 路径段比较，不是字符串前缀。

    /** `<home>/.hermesX` 与 `<home>/.hermes` 共享字符串前缀，却是两个目录：必须判成根外。 */
    @Test
    void aSiblingSharingTheStringPrefixIsStillOutside() throws Exception {
        Path hermesRoot = temp.resolve(".hermes");
        Path baseProfile = profile(hermesRoot.resolve("profiles").resolve("default"));
        Path unitsRoot = temp.resolve(".hermesX").resolve("execution-units");

        ExecutionUnitPool pool = startPool(baseProfile, unitsRoot, "", false);
        try {
            List<String> warned = warnings();
            assertThat(warned).as("字符串前缀相等不等于路径在之下").hasSize(1);
            assertThat(warned.get(0)).contains("不在 Hermes 根树之下");
        } finally {
            pool.close();
        }
    }

    /** `..` 与相对路径都先归一化成绝对路径再比较：`<root>/units/../outside` 不在根内。 */
    @Test
    void traversalSegmentsCannotSmuggleARootInside() throws Exception {
        Path hermesRoot = temp.resolve("hermes");
        Path baseProfile = profile(hermesRoot.resolve("profiles").resolve("default"));

        assertThat(ExecutionUnitRootPlacement.inspect(baseProfile,
            hermesRoot.resolve("units").resolve("..").resolve("..").resolve("outside"), "").verdict())
            .isEqualTo(ExecutionUnitRootPlacement.Verdict.OUTSIDE);
    }

    private ExecutionUnitPool startPool(Path baseProfile, Path unitsRoot, String hermesHome, boolean requireRoot) {
        return new AssistantConversationConfiguration().executionUnitPool(MAPPER, baseProfile.toString(),
            unitsRoot.toString(), 4, 600, 60_000, "stdio", "python3 -c pass", 10, 10, "haitlify", 64, 2,
            hermesHome, requireRoot);
    }

    /** 临时「基准 profile」：形状与真实 profile 一致（`config.yaml` + `.env`），内容不含任何真实凭据。 */
    private static Path profile(Path directory) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("config.yaml"), "mcp_servers: {}\n", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(".env"), "STUB_GATEWAY=1\n", StandardCharsets.UTF_8);
        return directory;
    }

    private List<String> warnings() {
        return events.list.stream()
            .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
    }
}
