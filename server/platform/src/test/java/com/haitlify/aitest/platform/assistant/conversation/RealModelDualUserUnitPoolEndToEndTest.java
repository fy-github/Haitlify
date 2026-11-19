package com.haitlify.aitest.platform.assistant.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import com.haitlify.aitest.platform.assistant.execution.ExecutionBindingService;
import com.haitlify.aitest.platform.assistant.hermes.HermesTuiGateway;
import com.haitlify.aitest.platform.assistant.mcp.McpLeaseRegistry;
import com.haitlify.aitest.platform.security.SecretCipher;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.ObjectMapper;

/**
 * <b>执行单元池 + 真实模型</b>的双用户端到端验收：
 *
 * <pre>
 * 用户 A ─┐                        ┌─ 单元 A（独立 HERMES_HOME/subprocess/config.yaml）
 *         ├─ 并发提交（真实模型） ─┤
 * 用户 B ─┘                        └─ 单元 B（独立 HERMES_HOME/subprocess/config.yaml）
 * </pre>
 *
 * <p>与两个既有用例的分工：
 * <ul>
 *   <li>{@code RealModelDraftLeaseEndToEndTest}：单用户、单网关，证明「真实模型 → 租约 → draft_create → 落库」；
 *       但它只有<b>一个</b>进程、一份配置，证明不了隔离；</li>
 *   <li>{@code AssistantConversationExecutionUnitTest}：双用户、双单元，证明配置互不可见；但网关是
 *       {@code python3} 桩，模型是假的，<b>草稿行是桩编出来的</b>；</li>
 *   <li>本用例把两者合起来：<b>池开着 + 两个真实 Hermes 子进程 + 两个真实模型回合</b>，两个用户各自建一份
 *       草稿，断言各自只写自己的 generation。</li>
 * </ul>
 *
 * <p>五组断言（对应验收单）：
 * <ol>
 *   <li><b>草稿不交叉</b>：每个 generation 恰好一行草稿，且该行的 binding / lease_generation / snapshot_digest
 *       与本轮种子一致，草稿体的 {@code startUrl} 等于<b>本轮授权</b>的 targetUrl，需求引用是本轮自己的；</li>
 *   <li><b>租约只被自己那轮用</b>：在飞采样里每个单元配置中的 lease id 经 {@code McpLeaseRegistry.resolve}
 *       必须解析到<b>本单元用户</b>的 generation / binding / actor；两个单元各自的模型回合（按 prompt 标记
 *       对齐 session）确实调了 {@code generation_context} 与 {@code draft_create}；</li>
 *   <li><b>配置互不可见</b>：任何一次采样里，A 的 config.yaml 不出现 B 的 lease id（反之亦然），
 *       两边的 mcp_servers 里从不出现第二个条目；</li>
 *   <li><b>终态收尾</b>：两轮都 COMPLETED；终态后两个单元的条目都被清空、两把单元许可都归还；</li>
 *   <li><b>真的并行</b>：两个回合的时间窗重叠（第二个 submit 早于第一个的终态），且存在某一采样时刻
 *       <b>两个单元同时各持一枚在飞租约</b>——串行实现不可能同时满足。</li>
 * </ol>
 *
 * <p>为什么默认跳过（CI 不跑）：需要两个真实模型回合（分钟级、要网络与模型凭据）与两个真实 Hermes 子进程。
 * 门控变量：
 * <ul>
 *   <li>{@code HAITLIFY_REAL_DUAL_USER_E2E=true}；</li>
 *   <li>{@code ..._BASE_PROFILE}：基准 profile 目录（必须同时有 {@code config.yaml} 与 {@code .env}）；</li>
 *   <li>{@code ..._UNITS_ROOT}：单元目录根，<b>必须落在 Hermes 根树之下</b>（例如
 *       {@code <Hermes root>/cache/scratch/.../units}），否则每个单元会触发一次完整的 {@code uv sync}；</li>
 *   <li>{@code ..._PYTHON} / {@code ..._AGENT_DIR}：stdio 网关入口（{@code python -m tui_gateway.entry}）；</li>
 *   <li>{@code ..._PORT}：固定端口（租约 URL 的 host:port 必须可预测）；</li>
 *   <li>{@code ..._EVIDENCE_DIR}：证据落点；</li>
 *   <li>{@code ..._DB_URL}/{@code ..._DB_USERNAME}/{@code ..._DB_PASSWORD}：不给就退到 H2。</li>
 * </ul>
 * 凭据只从环境读，绝不写进代码、命令行或证据文件。
 *
 * <p>本类<b>不修改任何被测实现</b>：模型没调工具、草稿落错 generation、配置交叉、串行执行都如实失败。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "HAITLIFY_REAL_DUAL_USER_E2E", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealModelDualUserUnitPoolEndToEndTest {

    /** 与其它用例一致：测试上下文用固定 32 字节密钥。 */
    private static final String TEST_SECRET_KEY = "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=";

    private static final Pattern LEASE = Pattern.compile("/mcp-lease/([A-Za-z0-9_-]{43})");
    private static final Pattern SESSION = Pattern.compile("(\\d{8}_\\d{6}_[0-9a-f]{6})");
    private static final Pattern TOOL_CALL = Pattern.compile(
        "^([0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2},[0-9]{3}).*agent\\.tool_executor: tool (\\S+) completed");

    private static final Duration SUBMIT_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration REPLY_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration SETTLE_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration SAMPLE_INTERVAL = Duration.ofMillis(120);

    private static final List<String> CLEANUP_TABLES = List.of(
        "assistant_generation_drafts", "assistant_generation_executions", "assistant_generations",
        "assistant_draft",
        "assistant_execution_grants", "assistant_execution_bindings", "assistant_execution_worker_units",
        "assistant_conversations", "assistant_conversation_messages",
        "tenants", "projects", "platform_users");

    // wire() 在上下文刷新前填好（被跳过时不读环境，避免「被跳过也报缺变量」）。
    private static Path baseProfile;
    private static Path unitsRoot;
    private static Path evidenceDir;
    private static Path workspace;
    private static String datasourceUrl;
    private static int port;
    private static String pythonHome;

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        evidenceDir = Path.of(env("HAITLIFY_REAL_DUAL_USER_E2E_EVIDENCE_DIR"));
        baseProfile = Path.of(env("HAITLIFY_REAL_DUAL_USER_E2E_BASE_PROFILE"));
        unitsRoot = Path.of(env("HAITLIFY_REAL_DUAL_USER_E2E_UNITS_ROOT"));
        workspace = evidenceDir.resolve("workspace");
        pythonHome = env("HAITLIFY_REAL_DUAL_USER_E2E_PYTHON");
        String agentDir = env("HAITLIFY_REAL_DUAL_USER_E2E_AGENT_DIR");
        port = Integer.parseInt(envOrDefault("HAITLIFY_REAL_DUAL_USER_E2E_PORT", "18081"));

        if (!Files.isRegularFile(baseProfile.resolve("config.yaml"))
            || !Files.isRegularFile(baseProfile.resolve(".env"))
            || !Files.isRegularFile(Path.of(pythonHome)) || !Files.isExecutable(Path.of(pythonHome))
            || !Files.isDirectory(Path.of(agentDir))) {
            throw new IllegalStateException("真实 E2E 前置缺失：需要基准 profile(" + baseProfile
                + " 的 config.yaml 与 .env) 与解释器 " + pythonHome);
        }
        try {
            Files.createDirectories(workspace);
            Files.createDirectories(evidenceDir);
            Files.createDirectories(unitsRoot);
        } catch (Exception ex) {
            throw new IllegalStateException("无法准备证据/单元目录", ex);
        }

        datasourceUrl = envOrDefault("HAITLIFY_REAL_DUAL_USER_E2E_DB_URL",
            "jdbc:h2:mem:real-dual-user-e2e;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        boolean mysql = datasourceUrl.startsWith("jdbc:mysql:");

        registry.add("server.port", () -> port);
        registry.add("spring.datasource.url", () -> datasourceUrl);
        registry.add("spring.datasource.username",
            () -> envOrDefault("HAITLIFY_REAL_DUAL_USER_E2E_DB_USERNAME", mysql ? "haitlify_app" : "sa"));
        registry.add("spring.datasource.password", () -> envOrDefault("HAITLIFY_REAL_DUAL_USER_E2E_DB_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name",
            () -> mysql ? "com.mysql.cj.jdbc.Driver" : "org.h2.Driver");
        registry.add("APP_SECRET_KEY", () -> TEST_SECRET_KEY);
        registry.add("app.security.enabled", () -> "false");

        // 三道对话开关 + MCP 端点（与单用户真实用例同一组开关）。
        registry.add("app.assistant.conversation.enabled", () -> "true");
        registry.add("app.assistant.conversation.mcp-lease.enabled", () -> "true");
        registry.add("app.assistant.conversation.mcp-lease.configure-gateway", () -> "true");
        registry.add("app.assistant.conversation.mcp-lease.public-base-url", () -> "http://127.0.0.1:" + port);
        registry.add("app.assistant.conversation.mcp-lease.ttl-seconds", () -> "300");
        registry.add("app.assistant.conversation.mcp-lease.gateway-reload-timeout-seconds", () -> "240");
        registry.add("app.assistant.mcp.enabled", () -> "true");
        registry.add("app.assistant.mcp.lease.enabled", () -> "true");
        registry.add("app.assistant.mcp.lease.ttl-seconds", () -> "300");

        // 单进程（池关掉）形状下写条目需要显式的网关配置文件路径：只有这样上下文才装配得出
        // GatewayMcpControl/GatewayMcpConfigurator。阳性运行不设置它，属性集合因此与之前完全一致；
        // 负向对照把它指向临时 HERMES_HOME 里的 config.yaml 副本（绝不指向操作者默认 Hermes 配置目录中的 config.yaml）。
        String gatewayConfigPath = envOrDefault("HAITLIFY_REAL_DUAL_USER_E2E_GATEWAY_CONFIG_PATH", "");
        if (!gatewayConfigPath.isBlank()) {
            registry.add("app.assistant.conversation.mcp-lease.gateway-config-path", () -> gatewayConfigPath);
        }

        // 本轮主角：执行单元池。容量 ≥2、TTL 给足（在飞判定与 TTL 无关，但别让回收线程在断言途中删目录）。
        // 开关本身可被属性覆盖：「负向对照」要用同一个用例在池关掉时跑一遍（见 src/test/e2e/run.sh）。
        // 默认仍是 true，阳性运行的属性集合逐字不变。
        registry.add("app.assistant.conversation.execution-units.enabled",
            () -> envOrDefault("HAITLIFY_REAL_DUAL_USER_E2E_UNITS_ENABLED", "true"));
        registry.add("app.assistant.conversation.execution-units.base-profile", baseProfile::toString);
        registry.add("app.assistant.conversation.execution-units.root", unitsRoot::toString);
        registry.add("app.assistant.conversation.execution-units.capacity", () -> "4");
        registry.add("app.assistant.conversation.execution-units.idle-ttl-seconds", () -> "1800");
        registry.add("app.assistant.conversation.execution-units.reap-interval-millis", () -> "1000");
        registry.add("app.assistant.conversation.execution-units.require-root-under-hermes-root", () -> "true");

        // 单元里的子进程命令：真实 stdio 网关（每单元一个进程）。
        registry.add("app.assistant.hermes.transport", () -> "stdio");
        registry.add("app.assistant.hermes.command", () -> pythonHome + " -m tui_gateway.entry");
        registry.add("app.assistant.hermes.cwd", () -> agentDir);
        registry.add("app.assistant.hermes.timeout-seconds", () -> "240");
        registry.add("app.assistant.conversation.reply-timeout-seconds", () -> "300");
        registry.add("app.assistant.conversation.resume-idle-wait-seconds", () -> "20");
        registry.add("app.assistant.conversation.workspace", workspace::toString);

        // 网关 stderr（模型回退、进程崩溃的第一现场）。
        registry.add("logging.level.com.haitlify.aitest.platform.assistant.hermes.HermesTuiGateway",
            () -> "DEBUG");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired SecretCipher cipher;
    @Autowired ExecutionBindingService execution;
    @Autowired AssistantConversationService service;
    /**
     * 执行单元池。<b>故意可选</b>：池关掉时这个 Bean 根本不存在（装配退回 {@code SingleGatewayUnits}），
     * 若这里是必需注入，负向对照会在上下文刷新阶段就报「没有这个 Bean」，断言一行都跑不到——那只能证明
     * 「用例依赖池这个 Bean」，证明不了「断言能分辨隔离与否」。可选注入让同一个用例在两种装配下都能跑到
     * 断言，失败点因此落在行为上（提交被拒 / 草稿缺行 / 单元数不对）。池打开时行为逐字不变。
     */
    @Autowired(required = false) ExecutionUnitPool pool;
    @Autowired McpLeaseRegistry leases;

    /** 池是否装配（= 本轮走单元化装配）。 */
    private boolean pooled() {
        return pool != null;
    }

    /** 两个真实用户（各自 tenant / project / actor / generation / binding / conversation）。 */
    static final class User {
        final String label;
        final String marker;
        final String username;
        final long actorId;
        final long tenantId;
        final long projectId;
        final String generationId;
        final String bindingId;
        final String workerUnitId;
        final String requestId;
        final String targetUrl;
        final String requirementRef;
        final String requirementText;
        final String specId;
        final String specName;
        final String stepAssert;

        String conversationId;
        String messageId;
        String draftId;
        String finalText = "";
        String userState = "";
        Path home;
        long submittedAt;
        long terminalAt;
        long submitReturnedAt;
        String duringSnapshot;
        String toolCallSource = "<none>";
        final List<String> sessions = new CopyOnWriteArrayList<>();
        final List<String> toolCalls = new CopyOnWriteArrayList<>();

        User(String label, String marker, String username, long actorId, long tenantId, long projectId,
             String generationId, String bindingId, String workerUnitId, String requestId, String targetUrl,
             String requirementRef, String requirementText, String specId, String specName, String stepAssert) {
            this.label = label;
            this.marker = marker;
            this.username = username;
            this.actorId = actorId;
            this.tenantId = tenantId;
            this.projectId = projectId;
            this.generationId = generationId;
            this.bindingId = bindingId;
            this.workerUnitId = workerUnitId;
            this.requestId = requestId;
            this.targetUrl = targetUrl;
            this.requirementRef = requirementRef;
            this.requirementText = requirementText;
            this.specId = specId;
            this.specName = specName;
            this.stepAssert = stepAssert;
        }
    }

    /** 一次在飞采样：只保留 origin + path 前缀、lease 的短摘要，绝不落 URL 原文/lease id。 */
    record Sample(long atMillis, String label, String homeName, boolean entryPresent, String origin,
                  String leaseRef, String resolvesToGeneration, String resolvesActor) {}

    private User alice;
    private User bob;
    private Map<String, String> countsBeforeSeed;
    private final List<Sample> samples = Collections.synchronizedList(new ArrayList<>());
    private final List<String> crossLeaks = Collections.synchronizedList(new ArrayList<>());
    private final List<String> dirsDuring = Collections.synchronizedList(new ArrayList<>());
    private Thread sampler;
    private volatile boolean sampling;
    private ListAppender<ILoggingEvent> gatewayStderr;
    private ExecutorService submitters;

    // ------------------------------------------------------------------ 种子

    @BeforeAll
    void seedOnce() throws Exception {
        countsBeforeSeed = tableCounts();
        writeEvidence("02-units-before.txt", "unitsRoot=" + unitsRoot + "\n"
            + "unitDirsBefore=" + unitDirs() + "\n"
            + "datasource=" + datasourceUrl + "\n");

        alice = seedUser("A", "https://example.com/haitlify/dual-user/a/console",
            "requirement:dual-user-a", "A 用户：打开 A 的控制台并确认 #app 可见",
            "dual-a-console-check", "A 控制台加载校验", "#app", "dual-a-turn-1");
        bob = seedUser("B", "https://example.com/haitlify/dual-user/b/console",
            "requirement:dual-user-b", "B 用户：打开 B 的控制台并确认 #root 可见",
            "dual-b-console-check", "B 控制台加载校验", "#root", "dual-b-turn-1");

        alice.conversationId = createConversation(alice);
        bob.conversationId = createConversation(bob);
        writeEvidence("02b-seed.json", json.writeValueAsString(Map.of(
            "datasource", datasourceUrl,
            "A", Map.of("username", alice.username, "tenantId", alice.tenantId, "projectId", alice.projectId,
                "actorId", alice.actorId, "generationId", alice.generationId, "bindingId", alice.bindingId,
                "requestId", alice.requestId, "targetUrl", alice.targetUrl, "marker", alice.marker),
            "B", Map.of("username", bob.username, "tenantId", bob.tenantId, "projectId", bob.projectId,
                "actorId", bob.actorId, "generationId", bob.generationId, "bindingId", bob.bindingId,
                "requestId", bob.requestId, "targetUrl", bob.targetUrl, "marker", bob.marker))));
    }

    private User seedUser(String label, String targetUrl, String requirementRef, String requirementText,
                          String specId, String specName, String stepAssert, String requestId) throws Exception {
        ConversationTurnFixture.Tenant tenant = ConversationTurnFixture.seedTenant(jdbc, "dual-" + label);
        String generationId = UUID.randomUUID().toString();
        String snapshot = json.writeValueAsString(new TreeMap<>(Map.of(
            "requestId", requestId,
            "projectId", tenant.projectId(),
            "requirementRef", requirementRef,
            "requirementText", requirementText,
            "targetUrl", targetUrl,
            "targetRevision", "rev-dual-" + label)));
        jdbc.update("insert into assistant_generations(id,actor_id,project_id,tenant_id,request_id,payload_digest,"
                + "snapshot_ciphertext,state,created_at) values (?,?,?,?,?,?,?,'WAITING_WORKER',?)",
            generationId, tenant.actorId(), tenant.projectId(), tenant.tenantId(), requestId,
            sha256(snapshot), cipher.encrypt(snapshot), Instant.now());
        String unit = "unit-" + UUID.randomUUID();
        execution.confirmUnitCleaned(execution.registerUnit(
            new ExecutionBindingService.WorkerUnit(unit, "sha256:" + "d".repeat(64), "policy-dual-user")));
        String bindingId = execution.allocate(new ExecutionBindingService.AllocationRequest(
            generationId, unit, Duration.ofMinutes(5))).bindingId();

        // prompt 首部的标记：把「模型日志里的 session」与「本轮用户」钉在一起（日志里 msg 只显示前 60 字，
        // 所以标记放在最前面）。它只是测试提示词，不参与任何授权。
        String marker = "【" + label + "-" + UUID.randomUUID().toString().substring(0, 8) + "】";
        return new User(label, marker, tenant.username(), tenant.actorId(), tenant.tenantId(),
            tenant.projectId(), generationId, bindingId, unit, requestId, targetUrl, requirementRef,
            requirementText, specId, specName, stepAssert);
    }

    private String createConversation(User user) throws Exception {
        final String[] holder = new String[1];
        asUser(user, () -> {
            holder[0] = service.create(Map.of("requestId", "conv-" + UUID.randomUUID(),
                "projectId", user.projectId, "title", "dual-user-" + user.label)).conversationId();
            return null;
        });
        assertThat(holder[0]).as("会话必须建出来").isNotBlank();
        return holder[0];
    }

    private <T> T asUser(User user, java.util.concurrent.Callable<T> action) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            user.username, "unused", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        try {
            return action.call();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // ------------------------------------------------------------------ 主用例

    @Test
    void twoRealModelTurnsInTwoUnitsEachWriteOnlyTheirOwnGeneration() throws Exception {
        gatewayStderr = attachGatewayStderrCapture();
        assertLeaseSurfaceIsMounted();
        warmUpUnits();
        writeEvidence("03-units-warmed.txt", "unitDirsAfterWarmUp=" + unitDirs() + "\n"
            + "unitHomes=" + describeHomes() + "\n");

        // 前置：两个单元在起飞之前都没有条目（否则「配置里是本轮的 URL」无法归因本轮）。
        for (User user : List.of(alice, bob)) {
            assertThat(leaseIdsIn(configOf(user))).as("%s 单元起飞前不应该有 mcp 条目", user.label).isEmpty();
        }

        startSampler();
        submitters = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "dual-submit");
            thread.setDaemon(true);
            return thread;
        });
        CountDownLatch startTogether = new CountDownLatch(1);
        CountDownLatch submitted = new CountDownLatch(2);
        List<Throwable> submitFailures = new CopyOnWriteArrayList<>();

        try {
            for (User user : List.of(alice, bob)) {
                submitters.execute(() -> {
                    try {
                        startTogether.await(30, TimeUnit.SECONDS);
                        user.submittedAt = System.currentTimeMillis();
                        long started = System.nanoTime();
                        AssistantConversationService.SubmitReceipt receipt = asUser(user, () -> service.submit(
                            user.conversationId, Map.of("requestId", user.requestId, "text", prompt(user))));
                        assertThat(receipt.state())
                            .as("%s 的轮次必须被受理（%s）", user.label, receipt.detail())
                            .isEqualTo(AssistantConversationService.STATE_SUBMITTED);
                        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(SUBMIT_TIMEOUT);
                        user.submitReturnedAt = System.currentTimeMillis();
                        user.messageId = receipt.messageId();
                    } catch (Throwable failure) {
                        submitFailures.add(failure);
                    } finally {
                        submitted.countDown();
                    }
                });
            }
            startTogether.countDown();
            assertThat(submitted.await(SUBMIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                .as("两个提交都必须返回").isTrue();
            assertThat(submitFailures).as("两个提交都不得失败").isEmpty();

            // 在飞：两轮都必须已经派发（各自的单元已武装），这是「两个单元同时各持一枚租约」的窗口。
            awaitBothLeasesArmed();

            awaitTerminal(alice);
            awaitTerminal(bob);
            writeGatewayStderrEvidence();
        } finally {
            sampling = false;
            if (sampler != null) {
                sampler.join(5000);
            }
            submitters.shutdownNow();
        }

        // ================= 断言 1：两份草稿各归各的 generation，不交叉
        List<Map<String, Object>> draftRows = jdbc.queryForList(
            "select * from assistant_generation_drafts where generation_id in (?,?)",
            alice.generationId, bob.generationId);
        assertThat(draftRows)
            .as("两个 generation 各必须恰好一行草稿（共 2 行）；实际 %s。A 终态=%s，B 终态=%s，"
                    + "A 文本=%s，B 文本=%s",
                draftRows.size(), alice.userState, bob.userState, abbreviate(alice.finalText),
                abbreviate(bob.finalText))
            .hasSize(2);
        for (User user : List.of(alice, bob)) {
            List<Map<String, Object>> mine = draftRows.stream()
                .filter(row -> user.generationId.equals(row.get("generation_id"))).toList();
            assertThat(mine).as("%s 的草稿行（generation_id=%s）", user.label, user.generationId).hasSize(1);
            Map<String, Object> row = mine.get(0);
            user.draftId = (String) row.get("draft_id");
            assertThat(row.get("binding_id")).as("%s 的草稿必须挂在本轮种子的 binding 上", user.label)
                .isEqualTo(user.bindingId);
            assertThat(((Number) row.get("lease_generation")).longValue())
                .as("%s 的 lease_generation 必须是本轮的 1", user.label).isEqualTo(1L);
            String payloadDigest = jdbc.queryForObject(
                "select payload_digest from assistant_generations where id=?", String.class, user.generationId);
            assertThat(row.get("snapshot_digest")).as("%s 的 snapshot_digest 必须等于本轮 generation 的载荷摘要",
                user.label).isEqualTo(payloadDigest);

            Map<String, Object> draft = jdbc.queryForMap("select * from assistant_draft where draft_id=?",
                user.draftId);
            assertThat(draft.get("state")).isEqualTo("DRAFT");
            assertThat(((Number) draft.get("tenant_id")).longValue()).as("%s 草稿的租户", user.label)
                .isEqualTo(user.tenantId);
            assertThat(((Number) draft.get("project_id")).longValue()).as("%s 草稿的项目", user.label)
                .isEqualTo(user.projectId);
            assertThat(((Number) draft.get("created_by")).longValue()).as("%s 草稿的创建者", user.label)
                .isEqualTo(user.actorId);
            assertThat(draft.get("requirement_ref")).as("%s 草稿的需求引用（串台时这里会是对方的）", user.label)
                .isEqualTo(user.requirementRef);
            assertThat(draft.get("target_ref")).isEqualTo("project:" + user.projectId);
            assertThat(draft.get("action_code")).isEqualTo("web.run");
            assertThat(sha256((String) draft.get("draft_json"))).as("%s 的 spec_digest 必须能由落库草稿重算",
                user.label).isEqualTo((String) row.get("spec_digest"));

            var spec = json.readTree((String) draft.get("draft_json"));
            assertThat(spec.path("startUrl").asString())
                .as("%s 草稿的 startUrl 必须等于本轮授权的 targetUrl（串台时会是对方那个）", user.label)
                .isEqualTo(user.targetUrl);
            assertThat(spec.path("id").asString()).as("%s 草稿的 spec.id", user.label).isEqualTo(user.specId);
            assertThat(spec.path("steps").isArray()).isTrue();
            assertThat(spec.path("steps").size()).isGreaterThan(0);
        }
        assertThat(alice.draftId).as("两份草稿必须是两行不同的草稿").isNotEqualTo(bob.draftId);

        // ================= 断言 2：两个用户各自的 ACTIVE 租约只被自己那轮的 tool call 使用
        assertThat(samples).as("必须采到在飞样本").isNotEmpty();
        for (User user : List.of(alice, bob)) {
            List<Sample> armed = samples.stream()
                .filter(sample -> sample.label().equals(user.label) && sample.entryPresent()).toList();
            assertThat(armed).as("%s 的单元必须出现过「条目 = 本轮租约」的样本（否则隔离无从观测）", user.label)
                .isNotEmpty();
            assertThat(armed).allSatisfy(sample -> {
                assertThat(sample.resolvesToGeneration())
                    .as("%s 的单元里那条租约必须解析到%s自己的 generation", user.label, user.label)
                    .isEqualTo(user.generationId);
                assertThat(sample.resolvesActor())
                    .as("%s 的单元里那条租约必须属于%s自己的 actor", user.label, user.label)
                    .isEqualTo(String.valueOf(user.actorId));
            });
            assertThat(armed.stream().map(Sample::leaseRef).distinct().count())
                .as("%s 侧只应看到自己那一枚租约", user.label).isEqualTo(1L);
            assertThat(user.toolCalls).as("%s 的模型回合必须真的调了 haitlify 工具（session=%s）",
                user.label, user.sessions).isNotEmpty();
            assertThat(user.toolCalls.stream().filter(tool -> tool.contains("generation_context")).count())
                .as("%s 的模型必须调用 generation_context", user.label).isGreaterThan(0);
            assertThat(user.toolCalls.stream().filter(tool -> tool.contains("draft_create")).count())
                .as("%s 的模型必须调用 draft_create", user.label).isGreaterThan(0);
            assertThat(user.finalText).as("%s 的终态文本必须提到自己的草稿 id（%s）", user.label, user.draftId)
                .contains(user.draftId);
        }
        assertThat(alice.finalText).as("A 的终态文本不得出现 B 的草稿 id").doesNotContain(bob.draftId);
        assertThat(bob.finalText).as("B 的终态文本不得出现 A 的草稿 id").doesNotContain(alice.draftId);

        // ================= 断言 3：在飞期间两份配置互不可见
        assertThat(crossLeaks)
            .as("采样期间任一单元的 config.yaml 里出现了对方的 lease id：%s", crossLeaks).isEmpty();
        for (User user : List.of(alice, bob)) {
            User other = user == alice ? bob : alice;
            List<String> mine = samples.stream().filter(s -> s.label().equals(user.label))
                .map(Sample::leaseRef).filter(ref -> ref != null).distinct().toList();
            assertThat(mine).as("%s 的 lease 摘要集合", user.label).hasSize(1);
            List<String> theirs = samples.stream().filter(s -> s.label().equals(other.label))
                .map(Sample::leaseRef).filter(ref -> ref != null).distinct().toList();
            assertThat(theirs).as("%s 的 lease 摘要集合", other.label).hasSize(1);
            assertThat(mine).as("两个单元的租约必须不同").doesNotContainAnyElementsOf(theirs);
        }

        // ================= 断言 4：两轮都 COMPLETED；终态后条目被清、许可被归还
        for (User user : List.of(alice, bob)) {
            assertThat(user.userState).as("%s 的账本必须走到 COMPLETED（%ss 预算内）", user.label,
                REPLY_TIMEOUT.plusSeconds(60).toSeconds()).isEqualTo(AssistantConversationService.STATE_COMPLETED);
            assertThat(service.mcpTrace(user.conversationId, user.requestId))
                .as("%s 的 MCP 痕迹必须是被配置过的配置态", user.label)
                .isEqualTo(AssistantConversationService.MCP_TRACE_CONFIGURED);
        }
        for (User user : List.of(alice, bob)) {
            awaitEntryCleared(user);
            assertThat(leaseIdsIn(configOf(user))).as("%s 终态后条目必须被清除", user.label).isEmpty();
            writeEvidence("05-config-after-" + user.label + ".txt", describeEntry(user, "after"));
            long deadline = System.nanoTime() + SETTLE_TIMEOUT.toNanos();
            while (System.nanoTime() < deadline && service.holdsGatewayMcpPermit(user.messageId)) {
                Thread.sleep(100);
            }
            assertThat(service.holdsGatewayMcpPermit(user.messageId))
                .as("%s 终态后单元级许可必须归还（否则该单元的下一位用户会 MCP_PERMIT_BUSY）", user.label)
                .isFalse();
        }
        assertThat(pooled() ? pool.liveUnits() : 1).as("两个单元都在册（各自一个子进程）").isEqualTo(2);
        assertThat(pooled() ? pool.keys() : List.of()).hasSize(2);

        // ================= 断言 5：真的并行，不是串行等待
        long overlapStart = Math.max(alice.submittedAt, bob.submittedAt);
        long overlapEnd = Math.min(alice.terminalAt, bob.terminalAt);
        assertThat(overlapEnd - overlapStart)
            .as("两个回合的时间窗必须重叠（A:%d→%d，B:%d→%d）", alice.submittedAt, alice.terminalAt,
                bob.submittedAt, bob.terminalAt)
            .isGreaterThan(1000L);
        long bothArmedTicks = samples.stream().filter(sample -> sample.entryPresent())
            .collect(java.util.stream.Collectors.groupingBy(Sample::atMillis))
            .values().stream().filter(bucket -> bucket.stream().map(Sample::label).distinct().count() == 2)
            .count();
        assertThat(bothArmedTicks)
            .as("必须存在「两个单元同时各持一枚在飞租约」的采样时刻（串行实现不可能满足）")
            .isGreaterThan(0L);
        assertThat(alice.home).as("两个用户必须落在两个不同的单元目录").isNotEqualTo(bob.home);
        assertThat(alice.home.getFileName().toString()).isNotEqualTo(bob.home.getFileName().toString());

        writeEvidence("01a-model-terminal-text-A.txt", alice.finalText);
        writeEvidence("01b-model-terminal-text-B.txt", bob.finalText);
        // 「在飞期间」的条目快照取自采样时刻（终态之后同一文件已被清空）。
        writeEvidence("04-config-during-A.txt", alice.duringSnapshot == null
            ? "phase=during\nuser=A\n(采样期间从未观察到条目)\n" : alice.duringSnapshot);
        writeEvidence("04-config-during-B.txt", bob.duringSnapshot == null
            ? "phase=during\nuser=B\n(采样期间从未观察到条目)\n" : bob.duringSnapshot);
        writeEvidence("06-drafts.json", draftsEvidence(draftRows));
        writeEvidence("07-config-samples.jsonl", samplesEvidence());
        writeEvidence("08-concurrency.json", concurrencyEvidence(overlapStart, overlapEnd, bothArmedTicks));
        writeEvidence("09-tool-calls-A.txt", toolCallEvidence(alice));
        writeEvidence("09-tool-calls-B.txt", toolCallEvidence(bob));
        writeEvidence("10-units-during.txt", String.join("\n", dirsDuring) + "\n");
        writeEvidence("12-summary.json", summaryEvidence(bothArmedTicks, overlapEnd - overlapStart));
    }

    // ------------------------------------------------------------------ 采样

    private void startSampler() {
        sampling = true;
        sampler = new Thread(() -> {
            while (sampling) {
                try {
                    Map<String, List<String>> leasesByUser = new LinkedHashMap<>();
                    long now = System.currentTimeMillis();
                    for (User user : List.of(alice, bob)) {
                        ExecutionUnitKey key = ExecutionUnitKey.of(user.tenantId, user.projectId, user.actorId);
                        var unit = pooled() ? pool.unitFor(key) : java.util.Optional.<ExecutionUnit>empty();
                        user.home = unit.map(ExecutionUnit::home).orElse(user.home);
                        if (unit.isEmpty()) {
                            samples.add(new Sample(now, user.label, "-", false, null, null, null, null));
                            leasesByUser.put(user.label, List.of());
                            continue;
                        }
                        Path config = unit.get().configFile();
                        String text;
                        try {
                            text = Files.readString(config, StandardCharsets.UTF_8);
                        } catch (Exception unreadable) {
                            samples.add(new Sample(now, user.label, unit.get().home().getFileName().toString(),
                                false, null, null, null, null));
                            leasesByUser.put(user.label, List.of());
                            continue;
                        }
                        // 条目必须从 mcp_servers.haitlify.url 精确取：整份 config.yaml 里还有模型 base_url，
                        // 按「第一个 http(s)」抓会把 provider 端点误当成租约的 origin（证据就会撒谎）。
                        String entryUrl = haitlifyUrl(config);
                        List<String> ids = entryUrl == null || !LEASE.matcher(entryUrl).find()
                            ? List.of()
                            : List.of(entryUrl.substring(entryUrl.lastIndexOf('/') + 1));
                        leasesByUser.put(user.label, ids);
                        String origin = entryUrl == null || !entryUrl.contains("/mcp-lease")
                            ? null : entryUrl.substring(0, entryUrl.indexOf("/mcp-lease"));
                        String ref = ids.isEmpty() ? null : leaseRef(ids.get(0));
                        String resolvedGeneration = null;
                        String resolvedActor = null;
                        if (!ids.isEmpty()) {
                            var resolved = leases.resolve(ids.get(0)).orElse(null);
                            if (resolved != null) {
                                resolvedGeneration = resolved.generationId();
                                resolvedActor = String.valueOf(resolved.actorId());
                            }
                        }
                        if (!ids.isEmpty() && user.duringSnapshot == null) {
                            // 在飞时刻的条目快照（只留 origin + path 前缀 + lease 长度）：这是「配置里当时
                            // 挂着什么」的唯一证据，终态之后同一文件会被清空，事后补写只会得到一份空文件。
                            user.duringSnapshot = "phase=during\nuser=" + user.label + "\nunit="
                                + unit.get().home().getFileName() + "\n"
                                + "mcp_servers.keys=" + new ArrayList<>(mcpServers(config).keySet()) + "\n"
                                + "haitlify.present=true\n"
                                + "haitlify.url.origin=" + origin + "\n"
                                + "haitlify.url.path=/mcp-lease/<redacted>\n"
                                + "haitlify.url.leaseIdLength=" + ids.get(0).length() + "\n"
                                + "haitlify.url.leaseIdRedacted=true\n"
                                + "haitlify.url.leaseRef=" + ref + "\n"
                                + "resolvesToOwnGeneration=" + (user.generationId.equals(resolvedGeneration))
                                + "\nresolvesActor=" + resolvedActor + "\n";
                        }
                        samples.add(new Sample(now, user.label, unit.get().home().getFileName().toString(),
                            !ids.isEmpty(), origin, ref, resolvedGeneration, resolvedActor));
                    }
                    // 交叉检查在同一时刻做：A 的文件里有 B 的 lease id（或反之）就是隔离破了。
                    for (User user : List.of(alice, bob)) {
                        User other = user == alice ? bob : alice;
                        List<String> theirs = leasesByUser.getOrDefault(other.label, List.of());
                        if (theirs.isEmpty()) {
                            continue;
                        }
                        Path config = configOf(user);
                        if (config == null || !Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS)) {
                            continue;
                        }
                        String text = Files.readString(config, StandardCharsets.UTF_8);
                        for (String foreign : theirs) {
                            if (text.contains(foreign)) {
                                crossLeaks.add(user.label + " 的配置在 t=" + now + " 出现了 "
                                    + other.label + " 的 lease（已脱敏）");
                            }
                        }
                        if (leaseIdsIn(text).size() > 1) {
                            crossLeaks.add(user.label + " 的配置在 t=" + now + " 同时挂着 "
                                + leaseIdsIn(text).size() + " 条 lease");
                        }
                    }
                    dirsDuring.add(now + " " + unitDirs());
                } catch (Exception failure) {
                    crossLeaks.add("采样异常（" + failure.getClass().getSimpleName() + "）：已如实记录");
                }
                try {
                    Thread.sleep(SAMPLE_INTERVAL.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "dual-unit-sampler");
        sampler.setDaemon(true);
        sampler.start();
    }

    /** 两轮都在飞：各自单元里都已写入本轮租约（= 已派发）。 */
    private void awaitBothLeasesArmed() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(180).toNanos();
        while (System.nanoTime() < deadline) {
            boolean armed = samples.stream()
                .filter(Sample::entryPresent)
                .collect(java.util.stream.Collectors.groupingBy(Sample::atMillis))
                .values().stream().anyMatch(bucket ->
                    bucket.stream().map(Sample::label).distinct().count() == 2);
            if (armed) {
                return;
            }
            if (AssistantConversationService.STATE_FAILED.equals(messageState(alice))
                || AssistantConversationService.STATE_FAILED.equals(messageState(bob))) {
                throw new AssertionError("有轮次在派发阶段就 FAILED（A=" + messageState(alice)
                    + "，B=" + messageState(bob) + "）");
            }
            Thread.sleep(200);
        }
        throw new AssertionError("180s 内没有观察到「两个单元同时持租约」的在飞窗口（A=" + messageState(alice)
            + "，B=" + messageState(bob) + "）");
    }

    // ------------------------------------------------------------------ 轮次 / 预热

    private void warmUpUnits() throws Exception {
        if (!pooled()) {
            // 单进程装配下没有「每单元一个子进程」可预热：预热本身就是单元化才有的步骤。如实留证，
            // 由后续断言（提交 / 草稿 / 单元数）决定这一轮是否成立——不在这里造假通过。
            writeEvidence("03-warmup.json", "warmUp=skipped(execution-units.enabled=false)\n");
            writeEvidence("03-units-warmed.txt", "unitDirsAfterWarmUp=<n/a: 单进程装配，没有单元目录>\n");
            return;
        }
        List<Long> millis = new ArrayList<>();
        for (User user : List.of(alice, bob)) {
            ExecutionUnitKey key = ExecutionUnitKey.of(user.tenantId, user.projectId, user.actorId);
            long started = System.nanoTime();
            // acquire 保证单元在册（并取一份在飞租约挡住回收）；RPC 走单元自己的子进程。
            UnitLease lease = pool.acquire(key).lease();
            try {
                ExecutionUnit unit = pool.unitFor(key).orElseThrow();
                var params = json.createObjectNode();
                params.put("server_requests", true);
                var response = unit.gateway().rpc("client.capabilities", params, Duration.ofSeconds(240));
                var error = response.path("error");
                assertThat(error.isMissingNode() || error.isNull())
                    .as("%s 单元预热被网关拒绝：code=%s message=%s", user.label,
                        error.path("code").asString(""), error.path("message").asString("")).isTrue();
                assertThat(response.path("result").path("server_requests").isArray())
                    .as("%s 单元的预热必须拿到真实回包", user.label).isTrue();
            } finally {
                lease.release();
            }
            user.home = pool.unitFor(key).orElseThrow().home();
            millis.add(Duration.ofNanos(System.nanoTime() - started).toMillis());
        }
        writeEvidence("03-warmup.json", "warmUpMillis=" + millis + "\n");
        writeGatewayStderrEvidence();
    }

    private void awaitTerminal(User user) throws Exception {
        long deadline = System.nanoTime() + REPLY_TIMEOUT.plusSeconds(60).toNanos();
        String state = AssistantConversationService.STATE_SUBMITTED;
        while (System.nanoTime() < deadline) {
            state = messageState(user);
            if (!AssistantConversationService.STATE_SUBMITTED.equals(state)) {
                user.terminalAt = System.currentTimeMillis();
                user.userState = state;
                user.finalText = assistantText(user);
                collectToolCalls(user);
                return;
            }
            Thread.sleep(300);
        }
        user.terminalAt = System.currentTimeMillis();
        user.userState = state;
        user.finalText = assistantText(user);
        collectToolCalls(user);
    }

    private String messageState(User user) {
        try {
            return jdbc.queryForObject("select state from assistant_conversation_messages"
                + " where conversation_id=? and request_id=? and role='USER'", String.class,
                user.conversationId, user.requestId);
        } catch (RuntimeException missing) {
            return null;
        }
    }

    private String assistantText(User user) {
        List<String> texts = jdbc.queryForList("select body_text from assistant_conversation_messages"
            + " where conversation_id=? and role='ASSISTANT' order by created_at desc", String.class,
            user.conversationId);
        return texts.isEmpty() ? "" : texts.get(0);
    }

    /** 条目的原文（配置）——只在内存里用，写证据前一律脱敏。 */
    private Path configOf(User user) {
        if (user.home == null && pooled()) {
            ExecutionUnitKey key = ExecutionUnitKey.of(user.tenantId, user.projectId, user.actorId);
            user.home = pool.unitFor(key).map(ExecutionUnit::home).orElse(null);
        }
        return user.home == null ? null : user.home.resolve("config.yaml");
    }

    private void awaitEntryCleared(User user) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            Path config = configOf(user);
            if (config == null || !Files.isRegularFile(config)) {
                return;
            }
            if (leaseIdsIn(Files.readString(config, StandardCharsets.UTF_8)).isEmpty()) {
                return;
            }
            Thread.sleep(200);
        }
    }

    // ------------------------------------------------------------------ 网关日志 → 模型实际调用链

    /**
     * 从网关日志里取「这一轮真的调了什么工具」。
     *
     * <p>只读取单元自己的 {@code <unit>/logs/agent.log}，不搜索操作者默认 Hermes 配置目录。
     * 单元日志缺失时保持空证据并由原断言失败，绝不把别人的调用算进来。
     */
    private void collectToolCalls(User user) {
        List<Path> candidates = new ArrayList<>();
        if (user.home != null) {
            Path unitLog = user.home.resolve("logs/agent.log");
            if (Files.isRegularFile(unitLog)) {
                candidates.add(unitLog);
            }
        }
        for (int index = 0; index < candidates.size(); index++) {
            Path log = candidates.get(index);
            try {
                if (!log.toRealPath().startsWith(unitsRoot.toRealPath())
                    || !log.toRealPath().startsWith(user.home.toRealPath())) {
                    continue;
                }
            } catch (Exception unowned) {
                continue;
            }
            try (var lines = Files.lines(log, StandardCharsets.UTF_8)) {
                List<String> all = lines.toList();
                List<String> sessions = new ArrayList<>(user.sessions);
                if (sessions.isEmpty()) {
                    for (String line : all) {
                        if (line.contains(user.marker)) {
                            Matcher session = SESSION.matcher(line);
                            while (session.find()) {
                                if (!sessions.contains(session.group(1))) {
                                    sessions.add(session.group(1));
                                }
                            }
                        }
                    }
                }
                for (String line : all) {
                    Matcher call = TOOL_CALL.matcher(line);
                    if (!call.find()) {
                        continue;
                    }
                    user.toolCalls.add(call.group(1) + " " + call.group(2));
                    if (sessions.isEmpty()) {
                        Matcher session = SESSION.matcher(line);
                        while (session.find()) {
                            if (!user.sessions.contains(session.group(1))) {
                                user.sessions.add(session.group(1));
                            }
                        }
                    }
                }
                if (!user.toolCalls.isEmpty()) {
                    user.toolCallSource = "unit-local:" + log.getFileName();
                    for (String session : sessions) {
                        if (!user.sessions.contains(session)) {
                            user.sessions.add(session);
                        }
                    }
                    return;
                }
            } catch (Exception unreadable) {
                // 日志不可读：如实留空，由断言文案暴露（不伪造调用记录）。
            }
        }
    }

    private String toolCallEvidence(User user) {
        StringBuilder text = new StringBuilder();
        text.append("user=").append(user.label).append('\n');
        text.append("unitHome=").append(user.home == null ? "-" : user.home.getFileName()).append('\n');
        text.append("toolCallSource=").append(user.toolCallSource).append('\n');
        text.append("sessionIds=").append(user.sessions).append('\n');
        text.append("toolCallCount=").append(user.toolCalls.size()).append('\n');
        user.toolCalls.forEach(call -> text.append("  ").append(call).append('\n'));
        return text.toString();
    }

    // ------------------------------------------------------------------ 证据

    private String draftsEvidence(List<Map<String, Object>> rows) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (User user : List.of(alice, bob)) {
            Map<String, Object> row = rows.stream()
                .filter(r -> user.generationId.equals(r.get("generation_id"))).findFirst().orElseThrow();
            Map<String, Object> draft = jdbc.queryForMap("select * from assistant_draft where draft_id=?",
                row.get("draft_id"));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("user", user.label);
            entry.put("generationId", user.generationId);
            entry.put("bindingId", row.get("binding_id"));
            entry.put("leaseGeneration", row.get("lease_generation"));
            entry.put("draftId", row.get("draft_id"));
            entry.put("specDigest", row.get("spec_digest"));
            entry.put("snapshotDigest", row.get("snapshot_digest"));
            entry.put("provenanceDigest", row.get("provenance_digest"));
            entry.put("spec", json.readTree((String) draft.get("draft_json")));
            entry.put("requirementRef", draft.get("requirement_ref"));
            entry.put("createdBy", draft.get("created_by"));
            entry.put("tenantId", draft.get("tenant_id"));
            entry.put("projectId", draft.get("project_id"));
            out.add(entry);
        }
        return json.writeValueAsString(out);
    }

    private String samplesEvidence() {
        StringBuilder text = new StringBuilder();
        for (Sample sample : List.copyOf(samples)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("t", sample.atMillis());
            row.put("user", sample.label());
            row.put("unit", sample.homeName());
            row.put("entryPresent", sample.entryPresent());
            row.put("origin", sample.origin() == null ? "" : sample.origin());
            row.put("path", "/mcp-lease/<redacted>");
            row.put("leaseRef", sample.leaseRef() == null ? "" : sample.leaseRef());
            row.put("resolvesToOwnGeneration", sample.resolvesToGeneration() != null);
            row.put("resolvesActor", sample.resolvesActor() == null ? "" : sample.resolvesActor());
            text.append(json.writeValueAsString(row)).append('\n');
        }
        return text.toString();
    }

    private String concurrencyEvidence(long overlapStart, long overlapEnd, long bothArmed) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("A", timing(alice));
        out.put("B", timing(bob));
        out.put("overlapMillis", overlapEnd - overlapStart);
        out.put("bothUnitsArmedSampleTicks", bothArmed);
        out.put("toolCallsA", alice.toolCalls);
        out.put("toolCallsB", bob.toolCalls);
        return json.writeValueAsString(out);
    }

    private static Map<String, Object> timing(User user) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("submittedAt", user.submittedAt);
        row.put("submitReturnedAt", user.submitReturnedAt);
        row.put("terminalAt", user.terminalAt);
        row.put("unit", user.home == null ? "-" : user.home.getFileName().toString());
        row.put("state", user.userState);
        return row;
    }

    private String summaryEvidence(long bothArmed, long overlapMillis) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("datasource", datasourceUrl);
        out.put("baseProfile", baseProfile.toString());
        out.put("unitsRoot", unitsRoot.toString());
        out.put("port", port);
        out.put("A", Map.of("generationId", alice.generationId, "bindingId", alice.bindingId,
            "draftId", alice.draftId, "unit", alice.home.getFileName().toString(), "state", alice.userState,
            "mcpTrace", String.valueOf(service.mcpTrace(alice.conversationId, alice.requestId))));
        out.put("B", Map.of("generationId", bob.generationId, "bindingId", bob.bindingId,
            "draftId", bob.draftId, "unit", bob.home.getFileName().toString(), "state", bob.userState,
            "mcpTrace", String.valueOf(service.mcpTrace(bob.conversationId, bob.requestId))));
        out.put("overlapMillis", overlapMillis);
        out.put("bothUnitsArmedSampleTicks", bothArmed);
        out.put("crossLeaks", List.copyOf(crossLeaks));
        out.put("sampleCount", samples.size());
        out.put("liveUnitsAtEnd", pooled() ? pool.liveUnits() : "<n/a: 单进程装配>");
        out.put("executionUnitsEnabled", pooled());
        return json.writeValueAsString(out);
    }

    private String describeEntry(User user, String phase) {
        Path config = configOf(user);
        Map<String, Object> servers = config == null ? Map.of() : mcpServers(config);
        String url = config == null ? null : haitlifyUrl(config);
        StringBuilder text = new StringBuilder();
        text.append("phase=").append(phase).append('\n');
        text.append("user=").append(user.label).append('\n');
        text.append("unit=").append(user.home == null ? "-" : user.home.getFileName()).append('\n');
        text.append("mcp_servers.keys=").append(new ArrayList<>(servers.keySet())).append('\n');
        text.append("haitlify.present=").append(url != null).append('\n');
        if (url != null) {
            text.append("haitlify.url.origin=").append(originOf(url)).append('\n');
            text.append("haitlify.url.path=/mcp-lease/<redacted>\n");
            text.append("haitlify.url.leaseIdLength=").append(url.substring(url.lastIndexOf('/') + 1).length())
                .append('\n');
            text.append("haitlify.url.leaseIdRedacted=true\n");
        }
        return text.toString();
    }

    private static String originOf(String text) {
        Matcher matcher = Pattern.compile("(https?://[^/\\s\"']+)").matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static List<String> leaseIdsIn(Path config) {
        if (config == null || !Files.isRegularFile(config)) {
            return List.of();
        }
        try {
            return leaseIdsIn(Files.readString(config, StandardCharsets.UTF_8));
        } catch (Exception unreadable) {
            return List.of();
        }
    }

    private static List<String> leaseIdsIn(String text) {
        List<String> ids = new ArrayList<>();
        Matcher matcher = LEASE.matcher(text);
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return ids;
    }

    /** lease id 的短摘要（证据里用；原文绝不落盘）。 */
    private static String leaseRef(String leaseId) {
        return sha256(leaseId).substring(0, 16);
    }

    private static String haitlifyUrl(Path config) {
        Map<String, Object> servers = mcpServers(config);
        Object entry = servers.get("haitlify");
        if (!(entry instanceof Map<?, ?> map)) {
            return null;
        }
        Object url = map.get("url");
        return url instanceof String text ? text : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mcpServers(Path config) {
        try {
            Object loaded = new Yaml(new SafeConstructor(new LoaderOptions()))
                .load(Files.readString(config, StandardCharsets.UTF_8));
            if (loaded instanceof Map<?, ?> root && root.get("mcp_servers") instanceof Map<?, ?> servers) {
                return (Map<String, Object>) servers;
            }
            return Map.of();
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private String describeHomes() {
        List<String> homes = new ArrayList<>();
        if (!pooled()) {
            return "<n/a: 单进程装配，所有轮次共用同一个网关>";
        }
        pool.keys().forEach(key -> pool.unitFor(key).ifPresent(unit ->
            homes.add(unit.key() + " -> " + unit.home().getFileName())));
        return homes.toString();
    }

    private List<String> unitDirs() {
        if (!Files.isDirectory(unitsRoot)) {
            return List.of();
        }
        try (var entries = Files.list(unitsRoot)) {
            return entries.filter(Files::isDirectory).map(path -> path.getFileName().toString()).sorted().toList();
        } catch (Exception unreadable) {
            return List.of("<unreadable>");
        }
    }

    // ------------------------------------------------------------------ MCP 面探针

    private void assertLeaseSurfaceIsMounted() throws Exception {
        String origin = "http://127.0.0.1:" + port;
        HttpResponse<String> unknown = postJson(origin + "/mcp-lease/" + "A".repeat(43),
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
        HttpResponse<String> noToken = postJson(origin + "/mcp",
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
        assertThat(unknown.statusCode()).as("租约端点没挂上（未知 lease 必须 401）").isEqualTo(401);
        assertThat(noToken.statusCode()).as("/mcp 必须挂上且拒绝无凭据请求").isEqualTo(401);
        writeEvidence("00-mcp-surface-probe.txt", "POST /mcp-lease/<43 fakes> -> " + unknown.statusCode()
            + "\nPOST /mcp (no bearer) -> " + noToken.statusCode() + "\n");
    }

    private static HttpResponse<String> postJson(String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
            .send(request, HttpResponse.BodyHandlers.ofString());
    }

    // ------------------------------------------------------------------ 提示词

    /**
     * 两个用户的 prompt 形状相同但内容互相不同：标记、授权 URL、spec id / name / 断言选择器都不一样。
     * 这既让模型产出可区分的草稿，也让「串台」在草稿体上肉眼可见。
     */
    private String prompt(User user) {
        return """
            %s 你是 Haitlify 平台的用例生成助手。请严格按步骤执行，不要提问、不要解释、不要输出计划，\
            也不要使用除下面列出的工具以外的任何工具。

            第 1 步：调用工具 mcp__haitlify__generation_context（无参数），读取本轮授权的生成上下文，\
            确认其中 targetUrl 的值。

            第 2 步：调用工具 mcp__haitlify__draft_create 创建草稿。它的参数只有一个 spec 对象，形状严格如下\
            （把尖括号换成你自己的内容，其余键名与结构一字不改）：

            {"spec":{"id":"%s","name":"%s","startUrl":"%s","steps":[\
            {"id":"s1","action":"goto","target":"%s"},{"id":"s2","action":"assertVisible","target":"%s"}]}}

            约束（违反任意一条都会被平台以 400 拒绝）：
            1) 顶层（spec 内）只允许 id / name / startUrl / steps 四个键，且 startUrl 必须与第 1 步读到的 \
            targetUrl 完全同源（协议、主机、端口都一致）；
            2) steps 至少 1 条、最多 100 条；每个 step 必须有 id / action / target，可选 value；
            3) action 只能取 goto、click、hover、scrollIntoView、assertVisible、assertHidden、assertText、\
            assertUrl、assertEnabled、assertDisabled、waitForVisible、waitForUrl、waitForText、\
            waitForHidden、waitForNetworkIdle 之一；
            4) 不要传 inputs、不要传 secretRef、不要传任何未列出的键。

            第 3 步：draft_create 成功后，调用 mcp__haitlify__draft_get（无参数）复核，然后用一句中文回复：\
            「草稿已创建：<draftId>」。除这三个工具外不要再调用其它工具。
            """.formatted(user.marker, user.specId, user.specName, user.targetUrl, user.targetUrl,
            user.stepAssert);
    }

    // ------------------------------------------------------------------ 收尾

    private void writeGatewayStderrEvidence() {
        if (gatewayStderr == null) {
            return;
        }
        StringBuilder text = new StringBuilder();
        gatewayStderr.list.forEach(event -> text.append(redact(event.getFormattedMessage())).append('\n'));
        writeEvidence("11-gateway-stderr.txt", text.toString());
    }

    private ListAppender<ILoggingEvent> attachGatewayStderrCapture() {
        Logger logger = (Logger) LoggerFactory.getLogger(HermesTuiGateway.class);
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static String redact(String line) {
        return line == null ? "" : line.replaceAll("/mcp-lease/[A-Za-z0-9_-]{43}", "/mcp-lease/<redacted>");
    }

    @AfterAll
    void cleanup() {
        sampling = false;
        if (sampler != null) {
            try {
                sampler.join(3000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        writeGatewayStderrEvidence();
        if (gatewayStderr != null) {
            ((Logger) LoggerFactory.getLogger(HermesTuiGateway.class)).detachAppender(gatewayStderr);
            gatewayStderr = null;
        }
        try {
            writeEvidence("13-cleanup-units.txt", closeUnitsAndReport());
        } catch (Exception failure) {
            writeEvidence("13-cleanup-units.txt", "清理证据写入失败：" + failure.getClass().getSimpleName());
        }
        cleanupRows();
        SecurityContextHolder.clearContext();
    }

    /**
     * 回收执行单元：关池（先杀子进程、再删目录）→ 核对单元目录一个不剩 → 删单元根与工作区。
     * 证据里只留目录名与数量，不留任何文件内容。
     */
    private String closeUnitsAndReport() {
        StringBuilder log = new StringBuilder();
        log.append("unitsRoot=").append(unitsRoot).append('\n');
        log.append("unitDirsBeforeClose=").append(unitDirs()).append('\n');
        log.append("liveUnitsBeforeClose=").append(pooled() ? pool.liveUnits() : "<n/a: 单进程装配>").append('\n');
        if (pooled()) {
            try {
                pool.close();
            } catch (RuntimeException failure) {
                log.append("pool.close FAILED: ").append(failure.getClass().getSimpleName()).append('\n');
            }
            log.append("liveUnitsAfterClose=").append(pool.liveUnits()).append('\n');
        } else {
            log.append("pool.close=skipped(execution-units.enabled=false；单进程装配没有单元可回收)\n");
        }
        log.append("unitDirsAfterClose=").append(unitDirs()).append('\n');
        log.append("unitSubprocessesLeft=").append(unitSubprocesses()).append('\n');
        try {
            if (Files.isDirectory(unitsRoot)) {
                try (var paths = Files.walk(unitsRoot)) {
                    paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                            // 尽力而为；下面的存在性核对才是判据。
                        }
                    });
                }
            }
            if (Files.isDirectory(workspace)) {
                try (var paths = Files.walk(workspace)) {
                    paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                        }
                    });
                }
            }
        } catch (Exception failure) {
            log.append("delete FAILED: ").append(failure.getClass().getSimpleName()).append('\n');
        }
        log.append("unitsRootExistsAfterCleanup=").append(Files.exists(unitsRoot)).append('\n');
        log.append("unitDirsAfterCleanup=").append(unitDirs()).append('\n');
        log.append("unitSubprocessesAfterCleanup=").append(unitSubprocesses()).append('\n');
        return log.toString();
    }

    /** 以单元目录根为过滤条件的进程计数（不打印进程命令行，只计数）。 */
    private String unitSubprocesses() {
        try {
            Process process = new ProcessBuilder("bash", "-c",
                "ps -Ao command | grep -F " + unitsRoot + " | grep -v grep | wc -l").start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            process.waitFor(10, TimeUnit.SECONDS);
            return out;
        } catch (Exception failure) {
            return "<n/a: " + failure.getClass().getSimpleName() + ">";
        }
    }

    /** 清掉本轮种下的行，并把「种之前 / 清之前 / 清之后」三份行数写进证据。 */
    private void cleanupRows() {
        if (alice == null || bob == null) {
            return;
        }
        Map<String, String> before = tableCounts();
        StringBuilder log = new StringBuilder();
        log.append("datasource=").append(datasourceUrl).append('\n');
        log.append("credentials=<from env only; never logged>\n\n");
        for (User user : List.of(alice, bob)) {
            log.append("--- user ").append(user.label).append(" ---\n");
            delete(log, "assistant_generation_drafts", "generation_id = ?", user.generationId);
            delete(log, "assistant_generation_executions", "generation_id = ?", user.generationId);
            delete(log, "assistant_generations", "id = ?", user.generationId);
            delete(log, "assistant_draft", "draft_id = ?", user.draftId);
            delete(log, "assistant_execution_grants", "binding_id = ?", user.bindingId);
            delete(log, "assistant_execution_bindings", "id = ?", user.bindingId);
            delete(log, "assistant_conversation_messages", "conversation_id = ?", user.conversationId);
            delete(log, "assistant_conversations", "id = ?", user.conversationId);
            delete(log, "assistant_execution_worker_units", "unit_id = ?", user.workerUnitId);
            delete(log, "platform_users", "id = ?", user.actorId);
            delete(log, "projects", "id = ?", user.projectId);
            delete(log, "tenants", "id = ?", user.tenantId);
        }
        Map<String, String> after = tableCounts();
        log.append("\n--- row counts (before seeding / before cleanup / after cleanup) ---\n");
        for (String table : CLEANUP_TABLES) {
            log.append(table).append(": ")
                .append(countsBeforeSeed == null ? "?" : countsBeforeSeed.get(table)).append(" / ")
                .append(before.get(table)).append(" / ").append(after.get(table)).append('\n');
        }
        log.append("\n--- leftovers of THIS run ---\n");
        for (User user : List.of(alice, bob)) {
            log.append(user.label).append(": ");
            log.append("drafts=").append(leftover("assistant_generation_drafts", "generation_id = ?", user.generationId));
            log.append(" generations=").append(leftover("assistant_generations", "id = ?", user.generationId));
            log.append(" drafts_body=").append(leftover("assistant_draft", "draft_id = ?", user.draftId));
            log.append(" grants=").append(leftover("assistant_execution_grants", "binding_id = ?", user.bindingId));
            log.append(" bindings=").append(leftover("assistant_execution_bindings", "id = ?", user.bindingId));
            log.append(" conversations=").append(leftover("assistant_conversations", "id = ?", user.conversationId));
            log.append(" messages=").append(leftover("assistant_conversation_messages", "conversation_id = ?",
                user.conversationId));
            log.append(" users=").append(leftover("platform_users", "id = ?", user.actorId));
            log.append(" projects=").append(leftover("projects", "id = ?", user.projectId));
            log.append(" tenants=").append(leftover("tenants", "id = ?", user.tenantId));
            log.append('\n');
        }
        writeEvidence("14-db-cleanup.txt", log.toString());
    }

    private void delete(StringBuilder log, String table, String where, Object id) {
        if (id == null) {
            log.append("skip ").append(table).append(": 本轮没有该行（id 为空）\n");
            return;
        }
        try {
            int deleted = jdbc.update("delete from " + table + " where " + where, id);
            log.append("deleted ").append(table).append(": ").append(deleted).append('\n');
        } catch (RuntimeException blocked) {
            log.append("FAILED ").append(table).append(": ").append(blocked.getClass().getSimpleName())
                .append(": ").append(firstLine(blocked.getMessage())).append('\n');
        }
    }

    private long leftover(String table, String where, Object id) {
        if (id == null) {
            return -1L;
        }
        try {
            Long remaining = jdbc.queryForObject("select count(*) from " + table + " where " + where,
                Long.class, id);
            return remaining == null ? -1L : remaining;
        } catch (RuntimeException unsupported) {
            return -2L;
        }
    }

    private Map<String, String> tableCounts() {
        Map<String, String> counts = new LinkedHashMap<>();
        for (String table : CLEANUP_TABLES) {
            try {
                counts.put(table, String.valueOf(
                    jdbc.queryForObject("select count(*) from " + table, Long.class)));
            } catch (RuntimeException missing) {
                counts.put(table, "<n/a>");
            }
        }
        return counts;
    }

    // ------------------------------------------------------------------ 工具

    private static void writeEvidence(String name, String content) {
        try {
            Files.createDirectories(evidenceDir);
            Files.writeString(evidenceDir.resolve(name), content, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("写证据失败：" + name, ex);
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "null";
        }
        return text.length() <= 400 ? text : text.substring(0, 400) + "…";
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        int newline = message.indexOf('\n');
        String line = newline < 0 ? message : message.substring(0, newline);
        return line.length() > 200 ? line.substring(0, 200) : line;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("真实 E2E 需要环境变量 " + name + "（默认套件不会跑到这里）");
        }
        Path path = Path.of(value);
        if (!path.isAbsolute() || path.normalize().getParent() == null || !path.equals(path.normalize())) {
            throw new IllegalStateException("真实 E2E 需要显式的隔离绝对路径: " + name);
        }
        return value;
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
