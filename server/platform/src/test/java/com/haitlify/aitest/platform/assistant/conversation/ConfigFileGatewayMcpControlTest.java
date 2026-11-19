package com.haitlify.aitest.platform.assistant.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import com.haitlify.aitest.platform.assistant.conversation.GatewayMcpControlException.Reason;

/**
 * 真实 {@link ConfigFileGatewayMcpControl} 的行为：<b>原子替换单条</b> → reload → 回读 → 失败回滚。
 *
 * <p>全部用临时目录里的<b>真实文件</b>与真实文件 IO；网关侧只有一个记录顺序/次数/并发的桩
 * {@link GatewayReloader}。不启动 Hermes，不读写任何真实 profile，也不调用模型。
 *
 * <p>夹具按 pin 的真实配置形状写：MCP 条目在<b>顶层</b> {@code mcp_servers} 映射下，每个条目是
 * {@code {url: ...}}（http）或 {@code {command/args/env}}（stdio）。因此每条用例同时钉住两件事：
 * 写对了位置（顶层 {@code mcp_servers.<name>}，不是 {@code mcp.servers}），以及只动这一条。
 *
 * <p>每条用例对应一条会被生产事故验证的性质：
 * <ul>
 *   <li>写的是<b>一条</b>条目，无关键、注释与<b>别的 MCP server</b>（mcp-atlassian/figma）原样保留；</li>
 *   <li>条目被<b>整体</b>替换成 {@code {url: ...}}：旧的 {@code auth}/{@code headers}/{@code command}
 *       必须消失，否则旧凭据会随新会话继续生效；</li>
 *   <li>先落盘再 reload：reload 时读到磁盘上必须已经是本轮 URL；</li>
 *   <li>写失败绝不 reload（把旧配置再加载一遍会伪装成成功）；</li>
 *   <li>reload 失败恢复上一条目并再次 reload；恢复也失败时保留现状并抛可对账异常；</li>
 *   <li>reload 成功后回读文件与回执，不一致即回滚——「reload 返回了」不等于「条目是本轮 URL」；</li>
 *   <li>同实例并发写入串行、reload 不交叉；</li>
 *   <li>文件缺失/不可写/格式非法 → 明确失败，绝不自动创建或整文件覆盖；</li>
 *   <li>异常、{@code toString()} 与标准输出都不含 URL / lease id。</li>
 * </ul>
 */
class ConfigFileGatewayMcpControlTest {

    private static final String SERVER = "haitlify";
    private static final String PREVIOUS = "https://assistant.internal/mcp-lease/previousLease01";
    private static final String CURRENT = "https://assistant.internal/mcp-lease/currentLease02";
    private static final String OTHER = "https://assistant.internal/mcp-lease/otherLease03";
    private static final String COMMENT = "# 网关配置（测试夹具）：无关键与注释都必须原样保留";

    /** 本轮条目带着上一轮残留的凭据：整体替换后它们必须消失。 */
    private static final String BASE_CONFIG = COMMENT + "\n"
        + "model: deepseek-v4\n"
        + "mcp_servers:\n"
        + "  mcp-atlassian:\n"
        + "    command: uvx\n"
        + "    args: ['mcp-atlassian']\n"
        + "    env:\n"
        + "      JIRA_TOKEN: 'stale-shared-token'\n"
        + "  haitlify:\n"
        + "    url: '" + PREVIOUS + "'\n"
        + "    headers:\n"
        + "      Authorization: 'Bearer stale-lease-token'\n"
        + "    auth:\n"
        + "      token: 'stale-entry-token'\n"
        + "  figma:\n"
        + "    url: 'https://figma.internal/mcp'\n"
        + "memory:\n"
        + "  enabled: true\n";

    @TempDir
    Path dir;

    private Path config;

    @BeforeEach
    void setUp() {
        config = dir.resolve("config.yaml");
    }

    @AfterEach
    void restoreDirectoryPermissions() throws IOException {
        // 回滚失败用例会把目录改成只读，恢复后才能让 @TempDir 清理。
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
    }

    // ---------------------------------------------------------------- 写入

    /**
     * 写进去的是本轮 URL，其它顶层键、注释以及<b>别的 MCP server</b> 条目都原样保留。
     *
     * <p>整文件重写会清掉别人的 server；写 {@code mcp.servers} 则根本改不到网关读取的那一段。
     */
    @Test
    void replacesOnlyTheSingleEntryUnderTopLevelMcpServersAndKeepsEverythingElse() throws IOException {
        writeConfig(BASE_CONFIG);

        control(new StubReloader(), null).writeServerEntry(CURRENT);

        assertThat(entryOf(config)).isEqualTo(CURRENT);
        Map<String, Object> servers = serversOf(config);
        assertThat(servers).as("顶层 mcp_servers 必须同时保留其它 server 条目")
            .containsKeys(SERVER, "mcp-atlassian", "figma");
        assertThat(urlOf(servers, "figma")).isEqualTo("https://figma.internal/mcp");
        assertThat(atlassianOf(servers)).containsEntry("command", "uvx").containsKey("env");
        String text = readConfig();
        assertThat(text)
            .as("注释与无关键（模型、内存段、别的 server）必须原样保留")
            .contains(COMMENT)
            .contains("model: deepseek-v4")
            .contains("mcp-atlassian:")
            .contains("stale-shared-token")
            .contains("figma:")
            .contains("memory:")
            .contains("enabled: true");
        assertThat(text.lines().filter(line -> line.contains(SERVER + ":"))).hasSize(1);
        assertNoTemporaryFiles();
    }

    /** 条目被整体替换成 {@code {url: ...}}：旧的 headers/auth/command 必须消失。 */
    @Test
    void replacesTheWholeEntryAndDropsStaleCredentialKeys() throws IOException {
        writeConfig(BASE_CONFIG.replace(
            "    auth:\n      token: 'stale-entry-token'\n",
            "    auth:\n      token: 'stale-entry-token'\n"
                + "    command: /bin/stale-bridge\n"
                + "    args: ['--stale']\n"));

        control(new StubReloader(), null).writeServerEntry(CURRENT);

        Map<String, Object> entry = entryMapOf(config);
        assertThat(entry)
            .as("整条写成 {url: ...}：残留的 auth/headers/command 会让旧凭据继续生效")
            .containsOnlyKeys("url");
        assertThat(entry).containsEntry("url", CURRENT);
        assertThat(readConfig())
            .doesNotContain("stale-lease-token")
            .doesNotContain("stale-entry-token")
            .doesNotContain("stale-bridge")
            .as("别的 server 自己的凭据当然要保留")
            .contains("stale-shared-token");
    }

    /** 位置必须是顶层 {@code mcp_servers}：旧的 {@code mcp.servers} 布局拒绝改写（fail-closed）。 */
    @Test
    void refusesTheLegacyNestedMcpServersLayout() throws IOException {
        String legacy = "model: deepseek-v4\n"
            + "mcp:\n"
            + "  servers:\n"
            + "    haitlify:\n"
            + "      url: '" + PREVIOUS + "'\n";
        writeConfig(legacy);
        StubReloader reloader = new StubReloader();

        assertReason(() -> control(reloader, null).writeServerEntry(CURRENT), Reason.CONFIG_INVALID);

        assertThat(readConfig())
            .as("网关读的是顶层 mcp_servers；改错位置等于什么都没配置，绝不能假装成功")
            .isEqualTo(legacy);
        assertThat(reloader.calls()).isZero();
    }

    /** write 自身不 reload：reload 是第二步，调用方（租约配置器）按顺序执行。 */
    @Test
    void writeDoesNotReloadByItself() throws IOException {
        writeConfig(BASE_CONFIG);
        StubReloader reloader = new StubReloader();

        control(reloader, null).writeServerEntry(CURRENT);

        assertThat(reloader.calls()).isZero();
    }

    /** reload 执行时磁盘上必须已经是本轮 URL（先写、后 reload，顺序不可交换）。 */
    @Test
    void theRoundEntryIsAlreadyOnDiskWhenReloadRuns() throws IOException {
        writeConfig(BASE_CONFIG);
        StubReloader reloader = new StubReloader();
        reloader.onCall(call -> reloader.entriesAtReload.add(readConfigQuietly()));
        GatewayMcpControl control = control(reloader, null);

        control.writeServerEntry(CURRENT);
        control.reload();

        assertThat(reloader.entriesAtReload).hasSize(1);
        assertThat(reloader.entriesAtReload.get(0))
            .as("reload 时磁盘上必须已是本轮 URL，而不是上一轮")
            .contains(CURRENT)
            .doesNotContain(PREVIOUS);
        assertThat(reloader.calls()).isEqualTo(1);
    }

    /** null = 清除条目（fail-closed），别的 server 与无关键保留，然后 reload 成功。 */
    @Test
    void clearsTheEntryForANullEndpointAndReloads() throws IOException {
        writeConfig(BASE_CONFIG);
        StubReloader reloader = new StubReloader();
        GatewayMcpControl control = control(reloader, null);

        control.writeServerEntry(null);
        assertThat(entryOf(config)).isNull();
        assertThat(readConfig()).contains("mcp-atlassian:").contains("figma:").contains("memory:");
        assertThat(serversOf(config)).containsKeys("mcp-atlassian", "figma");

        control.reload();
        assertThat(reloader.calls()).isEqualTo(1);
        assertThat(entryOf(config)).isNull();
    }

    /** 唯一条目被清除后父段留成空映射（不是 null），之后还能再写回来。 */
    @Test
    void clearsTheOnlyEntryIntoAnEmptyParentAndCanWriteAgain() throws IOException {
        writeConfig("mcp_servers:\n  haitlify:\n    url: '" + PREVIOUS + "'\nmemory:\n  enabled: true\n");
        GatewayMcpControl control = control(new StubReloader(), null);

        control.writeServerEntry(null);
        control.reload();
        assertThat(readConfig()).contains("mcp_servers: {}\n").contains("memory:");
        assertThat(entryOf(config)).isNull();

        control.writeServerEntry(CURRENT);
        control.reload();
        assertThat(entryOf(config)).isEqualTo(CURRENT);
        assertThat(readConfig()).contains("memory:");
    }

    /** 空的 flow 风格父段也能写进去，且不影响兄弟键。 */
    @Test
    void writesIntoAnEmptyFlowStyleParent() throws IOException {
        writeConfig("mcp_servers: {}\nmodel: deepseek-v4\n");
        GatewayMcpControl control = control(new StubReloader(), null);

        control.writeServerEntry(CURRENT);
        control.reload();

        assertThat(entryOf(config)).isEqualTo(CURRENT);
        assertThat(readConfig()).contains("model: deepseek-v4");
    }

    /** 条目已经是本轮的规范写法：不重写文件（避免无谓的 inode 抖动），reload 照常。 */
    @Test
    void doesNotRewriteAFileWhoseEntryIsAlreadyTheCanonicalRoundValue() throws IOException {
        writeConfig("mcp_servers:\n  haitlify:\n    url: '" + CURRENT + "'\n");
        Object before = Files.readAttributes(config, BasicFileAttributes.class).fileKey();
        StubReloader reloader = new StubReloader();
        GatewayMcpControl control = control(reloader, null);

        control.writeServerEntry(CURRENT);
        control.reload();

        assertThat(Files.readAttributes(config, BasicFileAttributes.class).fileKey()).isEqualTo(before);
        assertThat(entryOf(config)).isEqualTo(CURRENT);
        assertThat(reloader.calls()).isEqualTo(1);
    }

    /** 符号链接：拒绝（rename 会把链接换成普通文件，等于悄悄换掉目标），且目标一字不动。 */
    @Test
    void refusesToFollowASymlink() throws IOException {
        Path target = dir.resolve("real-config.yaml");
        Files.writeString(target, BASE_CONFIG, StandardCharsets.UTF_8);
        Files.createSymbolicLink(config, target.getFileName());

        assertReason(() -> control(new StubReloader(), null).writeServerEntry(CURRENT),
            Reason.CONFIG_UNAVAILABLE);

        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo(BASE_CONFIG);
    }

    /**
     * 原子替换：写入通过「同目录临时文件 + fsync + rename」完成，inode 必然变化；权限 0600。
     *
     * <p>直接写目标文件（非原子）会保留 inode，因此这条用例同时钉住原子性与权限。
     */
    @Test
    void replacesTheFileAtomicallyWithOwnerOnlyPermissions() throws IOException {
        writeConfig(BASE_CONFIG);
        Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rw-r--r--"));
        Object before = Files.readAttributes(config, BasicFileAttributes.class).fileKey();
        assertThat(before).as("文件系统必须提供 fileKey 才能验证 rename").isNotNull();

        control(new StubReloader(), null).writeServerEntry(CURRENT);

        Object after = Files.readAttributes(config, BasicFileAttributes.class).fileKey();
        assertThat(after)
            .as("原子 rename 会换成新 inode；原地覆盖不会")
            .isNotEqualTo(before);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(config)))
            .isEqualTo("rw-------");
        assertNoTemporaryFiles();
    }

    /** 目标文件不可写：明确失败，内容不动，也不 reload。 */
    @Test
    void failsWithoutReloadingWhenTheFileIsNotWritable() throws IOException {
        writeConfig(BASE_CONFIG);
        Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("r--------"));
        try {
            StubReloader reloader = new StubReloader();

            assertReason(() -> control(reloader, null).writeServerEntry(CURRENT), Reason.WRITE_FAILED);

            assertThat(reloader.calls()).isZero();
            assertThat(readConfig()).isEqualTo(BASE_CONFIG);
        } finally {
            Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rw-------"));
        }
    }

    /** 文件不存在：明确失败，绝不自动创建。 */
    @Test
    void failsWithoutCreatingAMissingFile() {
        StubReloader reloader = new StubReloader();

        assertReason(() -> control(reloader, null).writeServerEntry(CURRENT), Reason.CONFIG_UNAVAILABLE);

        assertThat(Files.exists(config)).isFalse();
        assertThat(reloader.calls()).isZero();
    }

    /** 非法 YAML：明确失败，原文件一字不动。 */
    @Test
    void failsOnInvalidYamlWithoutTouchingTheFile() throws IOException {
        writeConfig("mcp_servers: [unclosed\n");
        StubReloader reloader = new StubReloader();

        assertReason(() -> control(reloader, null).writeServerEntry(CURRENT), Reason.CONFIG_INVALID);

        assertThat(readConfig()).isEqualTo("mcp_servers: [unclosed\n");
        assertThat(reloader.calls()).isZero();
    }

    /** 缺少顶层 mcp_servers 段：不自动创建结构，明确失败。 */
    @Test
    void failsWhenTheParentSectionIsMissing() throws IOException {
        writeConfig("model: deepseek-v4\n");

        assertReason(() -> control(new StubReloader(), null).writeServerEntry(CURRENT),
            Reason.CONFIG_INVALID);

        assertThat(readConfig()).isEqualTo("model: deepseek-v4\n");
    }

    /** 现有条目结构不符合预期（没有 url）：无法可靠快照/回滚，明确失败。 */
    @Test
    void failsWhenTheExistingEntryHasNoUrl() throws IOException {
        String text = "mcp_servers:\n  haitlify:\n    transport: http\n";
        writeConfig(text);

        assertReason(() -> control(new StubReloader(), null).writeServerEntry(CURRENT),
            Reason.CONFIG_INVALID);

        assertThat(readConfig()).isEqualTo(text);
    }

    /** 换行/控制字符不得进入 YAML（引号逃逸不是唯一防线）。 */
    @Test
    void rejectsAnEndpointThatWouldBreakTheConfiguration() throws IOException {
        writeConfig(BASE_CONFIG);

        assertThatThrownBy(() -> control(new StubReloader(), null)
            .writeServerEntry("https://assistant.internal/mcp-lease/x\nmodel: evil"))
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(readConfig()).isEqualTo(BASE_CONFIG);
    }

    // ---------------------------------------------------------------- 失败与回滚

    /** reload 失败：恢复上一条目，并再次 reload；异常脱敏。 */
    @Test
    void restoresThePreviousEntryAndReloadsAgainWhenReloadFails() throws IOException {
        writeConfig(BASE_CONFIG);
        StubReloader reloader = new StubReloader().failOn(call -> call == 1);
        GatewayMcpControl control = control(reloader, null);
        control.writeServerEntry(CURRENT);

        assertReason(control::reload, Reason.RELOAD_FAILED);

        assertThat(entryOf(config)).as("必须恢复上一条目").isEqualTo(PREVIOUS);
        assertThat(entryMapOf(config)).as("回滚也要回到整条 {url} 的形态").containsOnlyKeys("url");
        assertThat(reloader.calls()).as("失败一次 + 恢复后一次").isEqualTo(2);
        assertNoTemporaryFiles();
    }

    /** reload 成功后必须回读；文件被改成别的 URL → 回滚（「reload 成功」不是「条目是本轮 URL」）。 */
    @Test
    void rollsBackWhenTheFileReadbackDisagrees() throws IOException {
        writeConfig(BASE_CONFIG);
        StubReloader reloader = new StubReloader()
            .onCall(call -> {
                if (call == 1) {
                    // 模拟网关/其它进程在 reload 期间把条目改成了别的 URL。
                    writeConfigQuietly(BASE_CONFIG.replace(PREVIOUS, OTHER));
                }
            });
        GatewayMcpControl control = control(reloader, null);
        control.writeServerEntry(CURRENT);

        assertReason(control::reload, Reason.READBACK_MISMATCH);

        assertThat(entryOf(config)).isEqualTo(PREVIOUS);
        assertThat(reloader.calls()).isEqualTo(2);
    }

    /** 回执可用时也要校验：文件对了、网关回执不是本轮 URL → 回滚。 */
    @Test
    void rollsBackWhenTheGatewayReceiptDisagrees() throws IOException {
        writeConfig(BASE_CONFIG);
        List<String> receipt = Collections.synchronizedList(new ArrayList<>());
        receipt.add(PREVIOUS);
        StubReloader reloader = new StubReloader()
            .onCall(call -> receipt.set(0, call == 1 ? OTHER : PREVIOUS));
        GatewayMcpControl control = control(reloader, () -> receipt.get(0));
        control.writeServerEntry(CURRENT);

        assertReason(control::reload, Reason.READBACK_MISMATCH);

        assertThat(entryOf(config)).isEqualTo(PREVIOUS);
        assertThat(reloader.calls()).isEqualTo(2);
    }

    /** 回滚写不进去：保留现状（新条目），抛可对账异常；不再有第二次 reload。 */
    @Test
    void keepsTheNewEntryAndAsksForReconciliationWhenTheRollbackCannotBeWritten() throws IOException {
        writeConfig(BASE_CONFIG);
        StubReloader reloader = new StubReloader()
            .failOn(call -> call == 1)
            .onCall(call -> {
                if (call == 1) {
                    setDirectoryPermissions("r-x------");
                }
            });
        GatewayMcpControl control = control(reloader, null);
        control.writeServerEntry(CURRENT);

        assertReason(control::reload, Reason.ROLLBACK_FAILED);

        assertThat(entryOf(config)).as("恢复失败时保留现状，不假装已回滚").isEqualTo(CURRENT);
        assertThat(reloader.calls()).as("恢复失败后不能再 reload").isEqualTo(1);
    }

    /** 恢复写入成功但第二次 reload 失败：仍是对账异常（配置已回滚、未生效）。 */
    @Test
    void asksForReconciliationWhenTheSecondReloadAlsoFails() throws IOException {
        writeConfig(BASE_CONFIG);
        StubReloader reloader = new StubReloader().failOn(call -> call <= 2);
        GatewayMcpControl control = control(reloader, null);
        control.writeServerEntry(CURRENT);

        assertReason(control::reload, Reason.ROLLBACK_FAILED);

        assertThat(entryOf(config)).isEqualTo(PREVIOUS);
        assertThat(reloader.calls()).isEqualTo(2);
    }

    /** 没有先写入就 reload：顺序协议被破坏，拒绝且不触达网关。 */
    @Test
    void rejectsReloadWithoutAPrecedingWrite() throws IOException {
        writeConfig(BASE_CONFIG);
        StubReloader reloader = new StubReloader();
        GatewayMcpControl control = control(reloader, null);

        assertReason(control::reload, Reason.NO_WRITE_PENDING);
        assertThat(reloader.calls()).isZero();

        control.writeServerEntry(CURRENT);
        control.reload();
        assertReason(control::reload, Reason.NO_WRITE_PENDING);
        assertThat(reloader.calls()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- 并发与脱敏

    /** 两个线程同时 apply：reload 绝不交叉，文件不被写坏，无关键保留。 */
    @Test
    void serialisesConcurrentAppliesAndNeverInterleavesReloads() throws Exception {
        writeConfig(BASE_CONFIG);
        StubReloader reloader = new StubReloader().onCall(call -> sleepQuietly(60));
        GatewayMcpConfigurator configurator = new GatewayMcpLeaseConfigurator(control(reloader, null));
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);

        Thread first = new Thread(() -> applyAfter(start, configurator, CURRENT, failures), "mcp-a");
        Thread second = new Thread(() -> applyAfter(start, configurator, OTHER, failures), "mcp-b");
        first.start();
        second.start();
        start.countDown();
        first.join(TimeUnit.SECONDS.toMillis(10));
        second.join(TimeUnit.SECONDS.toMillis(10));

        assertThat(first.isAlive() || second.isAlive()).isFalse();
        assertThat(reloader.maxInFlight())
            .as("同一控制面内 reload 不得交叉（交叉 reload 会加载别人写的条目）")
            .isEqualTo(1);
        assertThat(entryOf(config)).isIn(CURRENT, OTHER);
        assertThat(readConfig()).contains("memory:").contains("mcp-atlassian:").contains(COMMENT);
        // 被后一轮写入作废的序列只能以「交叉」或「已无待 reload 写入」被拒绝；两者都表示本轮未生效，
        // 由调用方撤销租约，绝不会去 reload / 回滚别人写的条目。
        assertThat(failures).allSatisfy(failure -> assertThat(failure)
            .isInstanceOfSatisfying(GatewayMcpControlException.class,
                error -> assertThat(error.reason()).isIn(Reason.CROSSED_RELOAD, Reason.NO_WRITE_PENDING)));
        assertNoTemporaryFiles();
    }

    /** 异常、toString 与标准输出都不得出现 URL / lease id，即使 reloader 自己把它们写进消息。 */
    @Test
    void neverLeaksTheEndpointThroughExceptionsOrOutput() throws IOException {
        writeConfig(BASE_CONFIG);
        StubReloader reloader = new StubReloader()
            .failOn(call -> true)
            .failWith(new IllegalStateException("reload failed for " + CURRENT + " (lease currentLease02)"));
        GatewayMcpControl control = control(reloader, null);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        Throwable thrown;
        try {
            // 捕获整条序列（写 + reload + 回滚）的输出：写入路径也可能打印 URL。
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            control.writeServerEntry(CURRENT);
            thrown = catchThrowable(control::reload);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        assertThat(thrown).isInstanceOf(GatewayMcpControlException.class);
        String visible = flatten(thrown) + control.toString()
            + out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
        assertThat(visible)
            .as("租约 URL / lease id 是仍然有效的凭据，绝不能进异常、toString 或日志")
            .doesNotContain(CURRENT)
            .doesNotContain("currentLease02");
    }

    // ---------------------------------------------------------------- 夹具

    private ConfigFileGatewayMcpControl control(GatewayReloader reloader, GatewayMcpReceipt receipt) {
        return new ConfigFileGatewayMcpControl(config, SERVER, ConfigFileGatewayMcpControl.DEFAULT_PARENT_PATH,
            reloader, receipt);
    }

    private void writeConfig(String text) throws IOException {
        Files.writeString(config, text, StandardCharsets.UTF_8);
    }

    private String readConfig() throws IOException {
        return Files.readString(config, StandardCharsets.UTF_8);
    }

    private void writeConfigQuietly(String text) {
        try {
            writeConfig(text);
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private void setDirectoryPermissions(String permissions) {
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString(permissions));
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private String readConfigQuietly() {
        try {
            return readConfig();
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private static Map<Object, Object> parse(Path file) throws IOException {
        Object root = new Yaml().load(Files.readString(file, StandardCharsets.UTF_8));
        @SuppressWarnings("unchecked")
        Map<Object, Object> model = (Map<Object, Object>) root;
        return model;
    }

    /** 顶层 {@code mcp_servers} 段；缺失即断言失败（写错位置会在这里直接暴露）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> serversOf(Path file) throws IOException {
        Object servers = parse(file).get("mcp_servers");
        assertThat(servers)
            .as("MCP 条目必须在顶层 mcp_servers 下（pin 的真实配置形状）")
            .isInstanceOf(Map.class);
        return (Map<String, Object>) servers;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> entryMapOf(Path file) throws IOException {
        return (Map<String, Object>) serversOf(file).get(SERVER);
    }

    private static String entryOf(Path file) throws IOException {
        Map<String, Object> entry = entryMapOf(file);
        return entry == null ? null : (String) entry.get("url");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> atlassianOf(Map<String, Object> servers) {
        return (Map<String, Object>) servers.get("mcp-atlassian");
    }

    @SuppressWarnings("unchecked")
    private static String urlOf(Map<String, Object> servers, String name) {
        Object entry = servers.get(name);
        return entry == null ? null : (String) ((Map<String, Object>) entry).get("url");
    }

    private void assertNoTemporaryFiles() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files.map(path -> path.getFileName().toString())
                .filter(name -> name.startsWith(".haitlify-mcp-")))
                .as("原子写入的临时文件必须被清理或 rename 掉")
                .isEmpty();
        }
    }

    private static void applyAfter(CountDownLatch start, GatewayMcpConfigurator configurator, String endpoint,
                                   List<Throwable> failures) {
        try {
            start.await(5, TimeUnit.SECONDS);
            configurator.apply(endpoint);
        } catch (Throwable failure) {
            failures.add(failure);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String flatten(Throwable thrown) {
        StringBuilder text = new StringBuilder();
        Throwable current = thrown;
        while (current != null) {
            text.append(current).append('\n');
            for (Throwable suppressed : current.getSuppressed()) {
                text.append(flatten(suppressed));
            }
            current = current.getCause();
        }
        return text.toString();
    }

    private static void assertReason(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable,
                                     Reason reason) {
        assertThatThrownBy(callable)
            .isInstanceOfSatisfying(GatewayMcpControlException.class,
                error -> assertThat(error.reason()).isEqualTo(reason));
    }

    private static final class StubReloader implements GatewayReloader {

        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger maxInFlight = new AtomicInteger();
        private final List<String> entriesAtReload = Collections.synchronizedList(new ArrayList<>());
        private volatile IntPredicate failureOn = call -> false;
        private volatile RuntimeException failure;
        private volatile Consumer<Integer> behaviour = call -> { };

        @Override
        public GatewayMcpReloadReceipt reload() {
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            int call = calls.incrementAndGet();
            try {
                behaviour.accept(call);
                if (failureOn.test(call)) {
                    throw failure != null ? failure : new IllegalStateException("reload failed");
                }
                return new GatewayMcpReloadReceipt("0a1b2c3d4e5f", false);
            } finally {
                inFlight.decrementAndGet();
            }
        }

        StubReloader failOn(IntPredicate failingCalls) {
            this.failureOn = Objects.requireNonNull(failingCalls);
            return this;
        }

        StubReloader failWith(RuntimeException error) {
            this.failure = error;
            return this;
        }

        /** 注册观察动作；返回自身便于链式配置。 */
        StubReloader onCall(Consumer<Integer> action) {
            Consumer<Integer> previous = this.behaviour;
            this.behaviour = call -> {
                previous.accept(call);
                action.accept(call);
            };
            return this;
        }

        int calls() {
            return calls.get();
        }

        int maxInFlight() {
            return maxInFlight.get();
        }
    }
}
