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
 * <b>同 tenant + 同 project、两个不同 actor</b> 的真实模型端到端验收。
 *
 * <pre>
 * 租户 T ─ 项目 P ─┬─ actor A ── 单元 ExecutionUnitKey(T,P,A) ── 子进程 A
 *                  └─ actor B ── 单元 ExecutionUnitKey(T,P,B) ── 子进程 B
 * </pre>
 *
 * <p>与 {@code RealModelDualUserUnitPoolEndToEndTest} 的分工：那个用例的两个用户各自有<b>独立的</b>
 * 租户与项目，所以「两个单元」也能由 tenant/project 两个分量解释；<b>本用例把这两个分量固定住</b>，
 * 只让 {@code actorId} 不同。如果实现只按 {@code (tenant, project)} 建单元（或把 actor 忘了），
 * 两个用户就会落进同一个单元：第二条提交被本单元的单一许可拒（{@code MCP_PERMIT_BUSY}），或者
 * 两份草稿里只有一份、甚至相互覆盖。它的 stub 版（{@code AssistantConversationExecutionUnitTest}）
 * 覆盖了键的形状，但没有真实模型回合；本用例把真实模型、真实子进程、真实租约补上。
 *
 * <p>七组断言：
 * <ol>
 *   <li><b>键</b>：池里恰好两个键，tenant/project 相同、actor 不同（等于两个 actor 的键，不多不少）；</li>
 *   <li><b>两个单元</b>：{@code liveUnits()==2}，两个单元的目录不同，且各自的 key 就是本轮那两个键；</li>
 *   <li><b>草稿各归各的 generation</b>：每个 generation 一行草稿，binding / lease_generation /
 *       snapshot_digest 与本轮种子一致，草稿体的 {@code startUrl} 是本轮授权的 targetUrl；</li>
 *   <li><b>在飞采样互不出现对方 lease</b>：每个单元配置里只有一枚租约，resolve 到自己的 generation 与
 *       自己的 actor；两边 lease 摘要不同，交叉泄漏为空；</li>
 *   <li><b>两轮都 COMPLETED</b>，终态后条目被清、许可被归还；</li>
 *   <li><b>模型真的调了工具</b>：按单元自己的 {@code logs/agent.log} 取该轮的 {@code generation_context}
 *       与 {@code draft_create}；终态文本各自报出自己的 draftId、不出现对方的；</li>
 *   <li><b>真的并行</b>：两个回合时间窗重叠，且存在「两个单元同时各持一枚在飞租约」的采样时刻。</li>
 * </ol>
 *
 * <p>门控变量（默认跳过，CI 不跑）：{@code HAITLIFY_REAL_SAME_PROJECT_E2E=true}，以及
 * {@code ..._BASE_PROFILE} / {@code ..._UNITS_ROOT} / {@code ..._PYTHON} / {@code ..._AGENT_DIR} /
 * {@code ..._PORT} / {@code ..._EVIDENCE_DIR} / {@code ..._DB_URL} 等；凭据只从环境读。
 * 本类不修改任何被测实现。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "HAITLIFY_REAL_SAME_PROJECT_E2E", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealModelSameProjectDualActorUnitPoolEndToEndTest {

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

    private static Path baseProfile;
    private static Path unitsRoot;
    private static Path evidenceDir;
    private static Path workspace;
    private static String datasourceUrl;
    private static int port;
    private static String pythonHome;

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        evidenceDir = Path.of(envOrDefault("HAITLIFY_REAL_SAME_PROJECT_E2E_EVIDENCE_DIR",
            "/Users/lify/.hermes/cache/scratch/dual-user-e2e/same-project"));
        baseProfile = Path.of(env("HAITLIFY_REAL_SAME_PROJECT_E2E_BASE_PROFILE"));
        unitsRoot = Path.of(envOrDefault("HAITLIFY_REAL_SAME_PROJECT_E2E_UNITS_ROOT",
            evidenceDir.resolve("units").toString()));
        workspace = evidenceDir.resolve("workspace");
        pythonHome = env("HAITLIFY_REAL_SAME_PROJECT_E2E_PYTHON");
        String agentDir = env("HAITLIFY_REAL_SAME_PROJECT_E2E_AGENT_DIR");
        port = Integer.parseInt(envOrDefault("HAITLIFY_REAL_SAME_PROJECT_E2E_PORT", "18083"));

        if (!Files.isRegularFile(baseProfile.resolve("config.yaml"))
            || !Files.isRegularFile(baseProfile.resolve(".env"))
            || !Files.isRegularFile(Path.of(pythonHome))) {
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

        datasourceUrl = envOrDefault("HAITLIFY_REAL_SAME_PROJECT_E2E_DB_URL",
            "jdbc:h2:mem:real-same-project-e2e;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        boolean mysql = datasourceUrl.startsWith("jdbc:mysql:");

        registry.add("server.port", () -> port);
        registry.add("spring.datasource.url", () -> datasourceUrl);
        registry.add("spring.datasource.username",
            () -> envOrDefault("HAITLIFY_REAL_SAME_PROJECT_E2E_DB_USERNAME", mysql ? "haitlify_app" : "sa"));
        registry.add("spring.datasource.password",
            () -> envOrDefault("HAITLIFY_REAL_SAME_PROJECT_E2E_DB_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name",
            () -> mysql ? "com.mysql.cj.jdbc.Driver" : "org.h2.Driver");
        registry.add("APP_SECRET_KEY", () -> TEST_SECRET_KEY);
        registry.add("app.security.enabled", () -> "false");

        registry.add("app.assistant.conversation.enabled", () -> "true");
        registry.add("app.assistant.conversation.mcp-lease.enabled", () -> "true");
        registry.add("app.assistant.conversation.mcp-lease.configure-gateway", () -> "true");
        registry.add("app.assistant.conversation.mcp-lease.public-base-url", () -> "http://127.0.0.1:" + port);
        registry.add("app.assistant.conversation.mcp-lease.ttl-seconds", () -> "300");
        registry.add("app.assistant.conversation.mcp-lease.gateway-reload-timeout-seconds", () -> "240");
        registry.add("app.assistant.mcp.enabled", () -> "true");
        registry.add("app.assistant.mcp.lease.enabled", () -> "true");
        registry.add("app.assistant.mcp.lease.ttl-seconds", () -> "300");

        registry.add("app.assistant.conversation.execution-units.enabled", () -> "true");
        registry.add("app.assistant.conversation.execution-units.base-profile", baseProfile::toString);
        registry.add("app.assistant.conversation.execution-units.root", unitsRoot::toString);
        registry.add("app.assistant.conversation.execution-units.capacity", () -> "4");
        registry.add("app.assistant.conversation.execution-units.idle-ttl-seconds", () -> "1800");
        registry.add("app.assistant.conversation.execution-units.reap-interval-millis", () -> "1000");
        registry.add("app.assistant.conversation.execution-units.require-root-under-hermes-root", () -> "true");

        registry.add("app.assistant.hermes.transport", () -> "stdio");
        registry.add("app.assistant.hermes.command", () -> pythonHome + " -m tui_gateway.entry");
        registry.add("app.assistant.hermes.cwd", () -> agentDir);
        registry.add("app.assistant.hermes.timeout-seconds", () -> "240");
        registry.add("app.assistant.conversation.reply-timeout-seconds", () -> "300");
        registry.add("app.assistant.conversation.resume-idle-wait-seconds", () -> "20");
        registry.add("app.assistant.conversation.workspace", workspace::toString);

        registry.add("logging.level.com.haitlify.aitest.platform.assistant.hermes.HermesTuiGateway",
            () -> "DEBUG");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired SecretCipher cipher;
    @Autowired ExecutionBindingService execution;
    @Autowired AssistantConversationService service;
    @Autowired ExecutionUnitPool pool;
    @Autowired McpLeaseRegistry leases;

    /** 一个主体的一轮：身份（tenant/project/actor）+ 本轮 generation/binding/授权。 */
    static final class ActorTurn {
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

        ActorTurn(String label, String marker, String username, long actorId, long tenantId, long projectId,
                  String generationId, String bindingId, String workerUnitId, String requestId,
                  String targetUrl, String requirementRef, String requirementText, String specId,
                  String specName, String stepAssert) {
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

        ExecutionUnitKey key() {
            return ExecutionUnitKey.of(tenantId, projectId, actorId);
        }
    }

    record Sample(long atMillis, String label, String homeName, boolean entryPresent, String origin,
                  String leaseRef, String resolvesToGeneration, String resolvesActor) {}

    private ActorTurn first;
    private ActorTurn second;
    private long sharedTenantId;
    private long sharedProjectId;
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
        writeEvidence("s02-units-before.txt", "unitsRoot=" + unitsRoot + "\n"
            + "unitDirsBefore=" + unitDirs() + "\n"
            + "datasource=" + datasourceUrl + "\n");

        // 一个租户、一个项目、两个 actor：先建 A（顺带建出 tenant/project），再往同一个项目里加 B。
        ConversationTurnFixture.Tenant owner = ConversationTurnFixture.seedTenant(jdbc, "same-project");
        ConversationTurnFixture.Tenant peer = ConversationTurnFixture.addActor(jdbc, owner, "same-project-peer");
        sharedTenantId = owner.tenantId();
        sharedProjectId = owner.projectId();
        assertThat(peer.tenantId()).as("前置：两个 actor 必须在同一个租户").isEqualTo(sharedTenantId);
        assertThat(peer.projectId()).as("前置：两个 actor 必须在同一个项目").isEqualTo(sharedProjectId);
        assertThat(peer.actorId()).as("前置：两个 actor 必须是不同的主体").isNotEqualTo(owner.actorId());

        first = seedTurn("A", owner, "https://example.com/haitlify/same-project/a/console",
            "requirement:same-project-a", "A 用户：打开 A 的控制台并确认 #app 可见",
            "same-a-console-check", "A 控制台加载校验", "#app", "same-project-a-turn-1");
        second = seedTurn("B", peer, "https://example.com/haitlify/same-project/b/console",
            "requirement:same-project-b", "B 用户：打开 B 的控制台并确认 #root 可见",
            "same-b-console-check", "B 控制台加载校验", "#root", "same-project-b-turn-1");

        first.conversationId = createConversation(first);
        second.conversationId = createConversation(second);
        Map<String, Object> seed = new LinkedHashMap<>();
        seed.put("datasource", datasourceUrl);
        seed.put("sharedTenantId", sharedTenantId);
        seed.put("sharedProjectId", sharedProjectId);
        seed.put("A", describeSeed(first));
        seed.put("B", describeSeed(second));
        writeEvidence("s02b-seed.json", json.writeValueAsString(seed));
    }

    private Map<String, Object> describeSeed(ActorTurn turn) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("username", turn.username);
        row.put("actorId", turn.actorId);
        row.put("tenantId", turn.tenantId);
        row.put("projectId", turn.projectId);
        row.put("conversationId", turn.conversationId);
        row.put("generationId", turn.generationId);
        row.put("bindingId", turn.bindingId);
        row.put("requestId", turn.requestId);
        row.put("targetUrl", turn.targetUrl);
        row.put("requirementRef", turn.requirementRef);
        row.put("marker", turn.marker);
        return row;
    }

    private ActorTurn seedTurn(String label, ConversationTurnFixture.Tenant tenant, String targetUrl,
                               String requirementRef, String requirementText, String specId, String specName,
                               String stepAssert, String requestId) throws Exception {
        String generationId = UUID.randomUUID().toString();
        String snapshot = json.writeValueAsString(new TreeMap<>(Map.of(
            "requestId", requestId,
            "projectId", tenant.projectId(),
            "requirementRef", requirementRef,
            "requirementText", requirementText,
            "targetUrl", targetUrl,
            "targetRevision", "rev-same-" + label)));
        jdbc.update("insert into assistant_generations(id,actor_id,project_id,tenant_id,request_id,payload_digest,"
                + "snapshot_ciphertext,state,created_at) values (?,?,?,?,?,?,?,'WAITING_WORKER',?)",
            generationId, tenant.actorId(), tenant.projectId(), tenant.tenantId(), requestId,
            sha256(snapshot), cipher.encrypt(snapshot), Instant.now());
        String unit = "unit-" + UUID.randomUUID();
        execution.confirmUnitCleaned(execution.registerUnit(
            new ExecutionBindingService.WorkerUnit(unit, "sha256:" + "e".repeat(64), "policy-same-project")));
        String bindingId = execution.allocate(new ExecutionBindingService.AllocationRequest(
            generationId, unit, Duration.ofMinutes(5))).bindingId();

        String marker = "【P" + label + "-" + UUID.randomUUID().toString().substring(0, 8) + "】";
        return new ActorTurn(label, marker, tenant.username(), tenant.actorId(), tenant.tenantId(),
            tenant.projectId(), generationId, bindingId, unit, requestId, targetUrl, requirementRef,
            requirementText, specId, specName, stepAssert);
    }

    private String createConversation(ActorTurn turn) throws Exception {
        final String[] holder = new String[1];
        asActor(turn, () -> {
            holder[0] = service.create(Map.of("requestId", "conv-" + UUID.randomUUID(),
                "projectId", turn.projectId, "title", "same-project-" + turn.label)).conversationId();
            return null;
        });
        assertThat(holder[0]).as("会话必须建出来").isNotBlank();
        return holder[0];
    }

    private <T> T asActor(ActorTurn turn, java.util.concurrent.Callable<T> action) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            turn.username, "unused", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        try {
            return action.call();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // ------------------------------------------------------------------ 主用例

    @Test
    void twoActorsInTheSameProjectGetTwoUnitsAndWriteOnlyTheirOwnDraft() throws Exception {
        gatewayStderr = attachGatewayStderrCapture();
        assertLeaseSurfaceIsMounted();

        warmUpUnits();
        writeEvidence("s03-units-warmed.txt", "unitDirsAfterWarmUp=" + unitDirs() + "\n"
            + "unitHomes=" + describeHomes() + "\n");

        // 前置：起飞前两个单元都不该有 mcp 条目（否则「配置里是本轮 URL」无法归因本轮）。
        for (ActorTurn turn : List.of(first, second)) {
            assertThat(leaseIdsIn(configOf(turn))).as("%s 单元起飞前不应该有 mcp 条目", turn.label).isEmpty();
        }

        // ================= 断言 1：键里 actorId 真的生效（同一 tenant/project，两个不同单元）
        List<ExecutionUnitKey> keys = new ArrayList<>(pool.keys());
        assertThat(keys).as("池里必须恰好两个单元键（实际 %s）", keys).hasSize(2);
        assertThat(keys).as("两个键必须正好是两个 actor 的键（tenant/project 相同、actor 不同）：%s", keys)
            .containsExactlyInAnyOrder(first.key(), second.key());
        assertThat(first.key().tenantId()).as("两个键的租户必须相同").isEqualTo(second.key().tenantId());
        assertThat(first.key().projectId()).as("两个键的项目必须相同").isEqualTo(second.key().projectId());
        assertThat(first.key().actorId()).as("两个键的 actor 必须不同").isNotEqualTo(second.key().actorId());
        assertThat(sharedTenantId).isEqualTo(first.key().tenantId());
        assertThat(sharedProjectId).isEqualTo(first.key().projectId());

        startSampler();
        submitters = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "same-project-submit");
            thread.setDaemon(true);
            return thread;
        });
        CountDownLatch startTogether = new CountDownLatch(1);
        CountDownLatch submitted = new CountDownLatch(2);
        List<Throwable> submitFailures = new CopyOnWriteArrayList<>();

        try {
            for (ActorTurn turn : List.of(first, second)) {
                submitters.execute(() -> {
                    try {
                        startTogether.await(30, TimeUnit.SECONDS);
                        turn.submittedAt = System.currentTimeMillis();
                        long started = System.nanoTime();
                        AssistantConversationService.SubmitReceipt receipt = asActor(turn, () -> service.submit(
                            turn.conversationId, Map.of("requestId", turn.requestId, "text", prompt(turn))));
                        assertThat(receipt.state())
                            .as("%s 的轮次必须被受理（%s）", turn.label, receipt.detail())
                            .isEqualTo(AssistantConversationService.STATE_SUBMITTED);
                        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(SUBMIT_TIMEOUT);
                        turn.submitReturnedAt = System.currentTimeMillis();
                        turn.messageId = receipt.messageId();
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
            assertThat(submitFailures).as("两个提交都不得失败（同一项目里的两个 actor 不得互相挤占单元许可）")
                .isEmpty();

            awaitBothLeasesArmed();
            awaitTerminal(first);
            awaitTerminal(second);
            writeGatewayStderrEvidence();
        } finally {
            sampling = false;
            if (sampler != null) {
                sampler.join(5000);
            }
            submitters.shutdownNow();
        }

        // ================= 断言 2：两个单元、两个目录，key 与本轮 actor 对齐
        assertThat(pool.liveUnits()).as("两个 actor 必须各有一个单元（同一个单元就说明 actorId 没参与键）")
            .isEqualTo(2);
        for (ActorTurn turn : List.of(first, second)) {
            ExecutionUnit unit = pool.unitFor(turn.key())
                .orElseThrow(() -> new AssertionError(turn.label + " 的键 " + turn.key() + " 在池里没有单元"));
            turn.home = unit.home();
            assertThat(unit.key()).as("%s 的单元键必须是本轮的 (tenant, project, actor)", turn.label)
                .isEqualTo(turn.key());
        }
        assertThat(first.home).as("两个 actor 必须落在两个不同的单元目录").isNotEqualTo(second.home);
        assertThat(first.home.getFileName()).as("两个单元目录名必须不同")
            .isNotEqualTo(second.home.getFileName());

        // ================= 断言 3：两份草稿各归自己的 generation
        List<Map<String, Object>> draftRows = jdbc.queryForList(
            "select * from assistant_generation_drafts where generation_id in (?,?)",
            first.generationId, second.generationId);
        assertThat(draftRows)
            .as("两个 generation 各必须恰好一行草稿（共 2 行）；实际 %s。A 终态=%s，B 终态=%s，A 文本=%s，B 文本=%s",
                draftRows.size(), first.userState, second.userState, abbreviate(first.finalText),
                abbreviate(second.finalText))
            .hasSize(2);
        for (ActorTurn turn : List.of(first, second)) {
            List<Map<String, Object>> mine = draftRows.stream()
                .filter(row -> turn.generationId.equals(row.get("generation_id"))).toList();
            assertThat(mine).as("%s 的草稿行（generation_id=%s）", turn.label, turn.generationId).hasSize(1);
            Map<String, Object> row = mine.get(0);
            turn.draftId = (String) row.get("draft_id");
            assertThat(row.get("binding_id")).as("%s 的草稿必须挂在本轮种子的 binding 上", turn.label)
                .isEqualTo(turn.bindingId);
            assertThat(((Number) row.get("lease_generation")).longValue())
                .as("%s 的 lease_generation 必须是本轮的 1", turn.label).isEqualTo(1L);
            String payloadDigest = jdbc.queryForObject(
                "select payload_digest from assistant_generations where id=?", String.class, turn.generationId);
            assertThat(row.get("snapshot_digest"))
                .as("%s 的 snapshot_digest 必须等于本轮 generation 的载荷摘要", turn.label)
                .isEqualTo(payloadDigest);

            Map<String, Object> draft = jdbc.queryForMap("select * from assistant_draft where draft_id=?",
                turn.draftId);
            assertThat(draft.get("state")).isEqualTo("DRAFT");
            assertThat(((Number) draft.get("tenant_id")).longValue()).as("%s 草稿的租户", turn.label)
                .isEqualTo(sharedTenantId);
            assertThat(((Number) draft.get("project_id")).longValue()).as("%s 草稿的项目", turn.label)
                .isEqualTo(sharedProjectId);
            assertThat(((Number) draft.get("created_by")).longValue())
                .as("%s 草稿的创建者必须是本轮自己的 actor（串到同一项目里的另一个人就是这里失败）", turn.label)
                .isEqualTo(turn.actorId);
            assertThat(draft.get("requirement_ref")).as("%s 草稿的需求引用（串台时会是对方的）", turn.label)
                .isEqualTo(turn.requirementRef);
            assertThat(draft.get("target_ref")).isEqualTo("project:" + sharedProjectId);
            assertThat(draft.get("action_code")).isEqualTo("web.run");
            assertThat(sha256((String) draft.get("draft_json")))
                .as("%s 的 spec_digest 必须能由落库草稿重算", turn.label)
                .isEqualTo((String) row.get("spec_digest"));

            var spec = json.readTree((String) draft.get("draft_json"));
            assertThat(spec.path("startUrl").asString())
                .as("%s 草稿的 startUrl 必须等于本轮授权的 targetUrl", turn.label)
                .isEqualTo(turn.targetUrl);
            assertThat(spec.path("id").asString()).as("%s 草稿的 spec.id", turn.label).isEqualTo(turn.specId);
            assertThat(spec.path("steps").size()).isGreaterThan(0);
        }
        assertThat(first.draftId).as("两份草稿必须是两行不同的草稿").isNotEqualTo(second.draftId);

        // ================= 断言 4：在飞采样里两份配置互不出现对方的 lease
        assertThat(samples).as("必须采到在飞样本").isNotEmpty();
        for (ActorTurn turn : List.of(first, second)) {
            List<Sample> armed = samples.stream()
                .filter(sample -> sample.label().equals(turn.label) && sample.entryPresent()).toList();
            assertThat(armed)
                .as("%s 的单元必须出现过「条目 = 本轮租约」的样本（否则隔离无从观测）", turn.label).isNotEmpty();
            assertThat(armed).allSatisfy(sample -> {
                assertThat(sample.resolvesToGeneration())
                    .as("%s 的单元里那条租约必须解析到%s自己的 generation", turn.label, turn.label)
                    .isEqualTo(turn.generationId);
                assertThat(sample.resolvesActor())
                    .as("%s 的单元里那条租约必须属于%s自己的 actor", turn.label, turn.label)
                    .isEqualTo(String.valueOf(turn.actorId));
            });
            assertThat(armed.stream().map(Sample::leaseRef).distinct().count())
                .as("%s 侧只应看到自己那一枚租约", turn.label).isEqualTo(1L);
        }
        assertThat(crossLeaks)
            .as("采样期间任一单元的 config.yaml 里出现了对方的 lease id：%s", crossLeaks).isEmpty();
        List<String> firstRefs = refsOf(first);
        List<String> secondRefs = refsOf(second);
        assertThat(firstRefs).as("A 的 lease 摘要集合").hasSize(1);
        assertThat(secondRefs).as("B 的 lease 摘要集合").hasSize(1);
        assertThat(firstRefs).as("两个单元的租约必须不同（同一枚就说明两轮共用了条目）")
            .doesNotContainAnyElementsOf(secondRefs);

        // ================= 断言 5：两轮都 COMPLETED；条目被清、许可被归还
        for (ActorTurn turn : List.of(first, second)) {
            assertThat(turn.userState).as("%s 的账本必须走到 COMPLETED", turn.label)
                .isEqualTo(AssistantConversationService.STATE_COMPLETED);
            assertThat(service.mcpTrace(turn.conversationId, turn.requestId))
                .as("%s 的 MCP 痕迹必须是被配置过的配置态", turn.label)
                .isEqualTo(AssistantConversationService.MCP_TRACE_CONFIGURED);
        }
        for (ActorTurn turn : List.of(first, second)) {
            awaitEntryCleared(turn);
            assertThat(leaseIdsIn(configOf(turn))).as("%s 终态后条目必须被清除", turn.label).isEmpty();
            writeEvidence("s05-config-after-" + turn.label + ".txt", describeEntry(turn, "after"));
            long deadline = System.nanoTime() + SETTLE_TIMEOUT.toNanos();
            while (System.nanoTime() < deadline && service.holdsGatewayMcpPermit(turn.messageId)) {
                Thread.sleep(100);
            }
            assertThat(service.holdsGatewayMcpPermit(turn.messageId))
                .as("%s 终态后单元级许可必须归还", turn.label).isFalse();
        }

        // ================= 断言 6：模型真的调了工具，且各报各的草稿
        for (ActorTurn turn : List.of(first, second)) {
            assertThat(turn.toolCalls).as("%s 的模型回合必须真的调了 haitlify 工具（session=%s）",
                turn.label, turn.sessions).isNotEmpty();
            assertThat(turn.toolCalls.stream().filter(tool -> tool.contains("generation_context")).count())
                .as("%s 的模型必须调用 generation_context", turn.label).isGreaterThan(0);
            assertThat(turn.toolCalls.stream().filter(tool -> tool.contains("draft_create")).count())
                .as("%s 的模型必须调用 draft_create", turn.label).isGreaterThan(0);
            assertThat(turn.finalText).as("%s 的终态文本必须提到自己的草稿 id（%s）", turn.label, turn.draftId)
                .contains(turn.draftId);
        }
        assertThat(first.finalText).as("A 的终态文本不得出现 B 的草稿 id").doesNotContain(second.draftId);
        assertThat(second.finalText).as("B 的终态文本不得出现 A 的草稿 id").doesNotContain(first.draftId);

        // ================= 断言 7：真的并行
        long overlapStart = Math.max(first.submittedAt, second.submittedAt);
        long overlapEnd = Math.min(first.terminalAt, second.terminalAt);
        assertThat(overlapEnd - overlapStart)
            .as("两个回合的时间窗必须重叠（A:%d→%d，B:%d→%d）", first.submittedAt, first.terminalAt,
                second.submittedAt, second.terminalAt)
            .isGreaterThan(1000L);
        long bothArmedTicks = samples.stream().filter(Sample::entryPresent)
            .collect(java.util.stream.Collectors.groupingBy(Sample::atMillis))
            .values().stream().filter(bucket -> bucket.stream().map(Sample::label).distinct().count() == 2)
            .count();
        assertThat(bothArmedTicks)
            .as("必须存在「两个单元同时各持一枚在飞租约」的采样时刻（串行实现不可能满足）")
            .isGreaterThan(0L);

        writeEvidence("s01a-model-terminal-text-A.txt", first.finalText);
        writeEvidence("s01b-model-terminal-text-B.txt", second.finalText);
        writeEvidence("s04-config-during-A.txt", first.duringSnapshot == null
            ? "phase=during\nuser=A\n(采样期间从未观察到条目)\n" : first.duringSnapshot);
        writeEvidence("s04-config-during-B.txt", second.duringSnapshot == null
            ? "phase=during\nuser=B\n(采样期间从未观察到条目)\n" : second.duringSnapshot);
        writeEvidence("s06-drafts.json", draftsEvidence(draftRows));
        writeEvidence("s07-config-samples.jsonl", samplesEvidence());
        writeEvidence("s08-concurrency.json", concurrencyEvidence(overlapStart, overlapEnd, bothArmedTicks));
        writeEvidence("s09-tool-calls-A.txt", toolCallEvidence(first));
        writeEvidence("s09-tool-calls-B.txt", toolCallEvidence(second));
        writeEvidence("s10-units-during.txt", String.join("\n", dirsDuring) + "\n");
        writeEvidence("s12-summary.json", summaryEvidence(bothArmedTicks, overlapEnd - overlapStart));
    }

    private List<String> refsOf(ActorTurn turn) {
        return samples.stream().filter(sample -> sample.label().equals(turn.label))
            .map(Sample::leaseRef).filter(ref -> ref != null).distinct().toList();
    }

    // ------------------------------------------------------------------ 采样

    private void startSampler() {
        sampling = true;
        sampler = new Thread(() -> {
            while (sampling) {
                try {
                    Map<String, List<String>> leasesByActor = new LinkedHashMap<>();
                    long now = System.currentTimeMillis();
                    for (ActorTurn turn : List.of(first, second)) {
                        var unit = pool.unitFor(turn.key());
                        turn.home = unit.map(ExecutionUnit::home).orElse(turn.home);
                        if (unit.isEmpty()) {
                            samples.add(new Sample(now, turn.label, "-", false, null, null, null, null));
                            leasesByActor.put(turn.label, List.of());
                            continue;
                        }
                        Path config = unit.get().configFile();
                        String text;
                        try {
                            text = Files.readString(config, StandardCharsets.UTF_8);
                        } catch (Exception unreadable) {
                            samples.add(new Sample(now, turn.label, unit.get().home().getFileName().toString(),
                                false, null, null, null, null));
                            leasesByActor.put(turn.label, List.of());
                            continue;
                        }
                        String entryUrl = haitlifyUrl(config);
                        List<String> ids = entryUrl == null || !LEASE.matcher(entryUrl).find()
                            ? List.of()
                            : List.of(entryUrl.substring(entryUrl.lastIndexOf('/') + 1));
                        leasesByActor.put(turn.label, ids);
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
                        if (!ids.isEmpty() && turn.duringSnapshot == null) {
                            turn.duringSnapshot = "phase=during\nuser=" + turn.label + "\nunit="
                                + unit.get().home().getFileName() + "\n"
                                + "mcp_servers.keys=" + new ArrayList<>(mcpServers(config).keySet()) + "\n"
                                + "haitlify.present=true\n"
                                + "haitlify.url.origin=" + origin + "\n"
                                + "haitlify.url.path=/mcp-lease/<redacted>\n"
                                + "haitlify.url.leaseIdLength=" + ids.get(0).length() + "\n"
                                + "haitlify.url.leaseIdRedacted=true\n"
                                + "haitlify.url.leaseRef=" + ref + "\n"
                                + "resolvesToOwnGeneration=" + (turn.generationId.equals(resolvedGeneration))
                                + "\nresolvesActor=" + resolvedActor + "\n";
                        }
                        samples.add(new Sample(now, turn.label, unit.get().home().getFileName().toString(),
                            !ids.isEmpty(), origin, ref, resolvedGeneration, resolvedActor));
                    }
                    for (ActorTurn turn : List.of(first, second)) {
                        ActorTurn other = turn == first ? second : first;
                        List<String> theirs = leasesByActor.getOrDefault(other.label, List.of());
                        if (theirs.isEmpty()) {
                            continue;
                        }
                        Path config = configOf(turn);
                        if (config == null || !Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS)) {
                            continue;
                        }
                        String text = Files.readString(config, StandardCharsets.UTF_8);
                        for (String foreign : theirs) {
                            if (text.contains(foreign)) {
                                crossLeaks.add(turn.label + " 的配置在 t=" + now + " 出现了 "
                                    + other.label + " 的 lease（已脱敏）");
                            }
                        }
                        if (leaseIdsIn(text).size() > 1) {
                            crossLeaks.add(turn.label + " 的配置在 t=" + now + " 同时挂着 "
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
        }, "same-project-sampler");
        sampler.setDaemon(true);
        sampler.start();
    }

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
            if (AssistantConversationService.STATE_FAILED.equals(messageState(first))
                || AssistantConversationService.STATE_FAILED.equals(messageState(second))) {
                throw new AssertionError("有轮次在派发阶段就 FAILED（A=" + messageState(first)
                    + "，B=" + messageState(second) + "）");
            }
            Thread.sleep(200);
        }
        throw new AssertionError("180s 内没有观察到「两个单元同时持租约」的在飞窗口（A=" + messageState(first)
            + "，B=" + messageState(second) + "）");
    }

    // ------------------------------------------------------------------ 预热 / 终态

    private void warmUpUnits() throws Exception {
        List<Long> millis = new ArrayList<>();
        for (ActorTurn turn : List.of(first, second)) {
            long started = System.nanoTime();
            UnitLease lease = pool.acquire(turn.key()).lease();
            try {
                ExecutionUnit unit = pool.unitFor(turn.key()).orElseThrow();
                var params = json.createObjectNode();
                params.put("server_requests", true);
                var response = unit.gateway().rpc("client.capabilities", params, Duration.ofSeconds(240));
                var error = response.path("error");
                assertThat(error.isMissingNode() || error.isNull())
                    .as("%s 单元预热被网关拒绝：code=%s message=%s", turn.label,
                        error.path("code").asString(""), error.path("message").asString("")).isTrue();
                assertThat(response.path("result").path("server_requests").isArray())
                    .as("%s 单元的预热必须拿到真实回包", turn.label).isTrue();
            } finally {
                lease.release();
            }
            turn.home = pool.unitFor(turn.key()).orElseThrow().home();
            millis.add(Duration.ofNanos(System.nanoTime() - started).toMillis());
        }
        writeEvidence("s03-warmup.json", "warmUpMillis=" + millis + "\n");
        writeGatewayStderrEvidence();
    }

    private void awaitTerminal(ActorTurn turn) throws Exception {
        long deadline = System.nanoTime() + REPLY_TIMEOUT.plusSeconds(60).toNanos();
        String state = AssistantConversationService.STATE_SUBMITTED;
        while (System.nanoTime() < deadline) {
            state = messageState(turn);
            if (!AssistantConversationService.STATE_SUBMITTED.equals(state)) {
                turn.terminalAt = System.currentTimeMillis();
                turn.userState = state;
                turn.finalText = assistantText(turn);
                collectToolCalls(turn);
                return;
            }
            Thread.sleep(300);
        }
        turn.terminalAt = System.currentTimeMillis();
        turn.userState = state;
        turn.finalText = assistantText(turn);
        collectToolCalls(turn);
    }

    private String messageState(ActorTurn turn) {
        try {
            return jdbc.queryForObject("select state from assistant_conversation_messages"
                + " where conversation_id=? and request_id=? and role='USER'", String.class,
                turn.conversationId, turn.requestId);
        } catch (RuntimeException missing) {
            return null;
        }
    }

    private String assistantText(ActorTurn turn) {
        List<String> texts = jdbc.queryForList("select body_text from assistant_conversation_messages"
            + " where conversation_id=? and role='ASSISTANT' order by created_at desc", String.class,
            turn.conversationId);
        return texts.isEmpty() ? "" : texts.get(0);
    }

    private Path configOf(ActorTurn turn) {
        if (turn.home == null) {
            turn.home = pool.unitFor(turn.key()).map(ExecutionUnit::home).orElse(null);
        }
        return turn.home == null ? null : turn.home.resolve("config.yaml");
    }

    private void awaitEntryCleared(ActorTurn turn) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            Path config = configOf(turn);
            if (config == null || !Files.isRegularFile(config)) {
                return;
            }
            if (leaseIdsIn(Files.readString(config, StandardCharsets.UTF_8)).isEmpty()) {
                return;
            }
            Thread.sleep(200);
        }
    }

    private void collectToolCalls(ActorTurn turn) {
        List<Path> candidates = new ArrayList<>();
        List<Boolean> ownLog = new ArrayList<>();
        if (turn.home != null) {
            Path unitLog = turn.home.resolve("logs/agent.log");
            if (Files.isRegularFile(unitLog)) {
                candidates.add(unitLog);
                ownLog.add(true);
            }
        }
        Path global = Path.of(System.getProperty("user.home"), ".hermes", "logs", "agent.log");
        if (Files.isRegularFile(global)) {
            candidates.add(global);
            ownLog.add(false);
        }
        for (int index = 0; index < candidates.size(); index++) {
            Path log = candidates.get(index);
            try (var lines = Files.lines(log, StandardCharsets.UTF_8)) {
                List<String> all = lines.toList();
                List<String> sessions = new ArrayList<>(turn.sessions);
                if (sessions.isEmpty()) {
                    for (String line : all) {
                        if (line.contains(turn.marker)) {
                            Matcher session = SESSION.matcher(line);
                            while (session.find()) {
                                if (!sessions.contains(session.group(1))) {
                                    sessions.add(session.group(1));
                                }
                            }
                        }
                    }
                }
                if (!ownLog.get(index) && sessions.isEmpty()) {
                    continue;
                }
                for (String line : all) {
                    Matcher call = TOOL_CALL.matcher(line);
                    if (!call.find()) {
                        continue;
                    }
                    boolean owned = ownLog.get(index) || sessions.stream().anyMatch(line::contains);
                    if (!owned) {
                        continue;
                    }
                    turn.toolCalls.add(call.group(1) + " " + call.group(2));
                }
                if (!turn.toolCalls.isEmpty()) {
                    turn.toolCallSource = log.startsWith(String.valueOf(turn.home))
                        ? "unit-local:" + log.getFileName() : "host-global:" + log.getFileName()
                            + "（按 prompt 标记定 session 后过滤）";
                    for (String session : sessions) {
                        if (!turn.sessions.contains(session)) {
                            turn.sessions.add(session);
                        }
                    }
                    return;
                }
            } catch (Exception unreadable) {
                // 日志不可读：如实留空，由断言文案暴露。
            }
        }
    }

    private String toolCallEvidence(ActorTurn turn) {
        StringBuilder text = new StringBuilder();
        text.append("user=").append(turn.label).append('\n');
        text.append("unitHome=").append(turn.home == null ? "-" : turn.home.getFileName()).append('\n');
        text.append("unitKey=").append(turn.key()).append('\n');
        text.append("toolCallSource=").append(turn.toolCallSource).append('\n');
        text.append("sessionIds=").append(turn.sessions).append('\n');
        text.append("toolCallCount=").append(turn.toolCalls.size()).append('\n');
        turn.toolCalls.forEach(call -> text.append("  ").append(call).append('\n'));
        return text.toString();
    }

    // ------------------------------------------------------------------ 证据

    private String draftsEvidence(List<Map<String, Object>> rows) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ActorTurn turn : List.of(first, second)) {
            Map<String, Object> row = rows.stream()
                .filter(r -> turn.generationId.equals(r.get("generation_id"))).findFirst().orElseThrow();
            Map<String, Object> draft = jdbc.queryForMap("select * from assistant_draft where draft_id=?",
                row.get("draft_id"));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("user", turn.label);
            entry.put("actorId", turn.actorId);
            entry.put("unitKey", turn.key().toString());
            entry.put("unitDir", turn.home == null ? "-" : turn.home.getFileName().toString());
            entry.put("generationId", turn.generationId);
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
        out.put("A", timing(first));
        out.put("B", timing(second));
        out.put("overlapMillis", overlapEnd - overlapStart);
        out.put("bothUnitsArmedSampleTicks", bothArmed);
        out.put("toolCallsA", first.toolCalls);
        out.put("toolCallsB", second.toolCalls);
        return json.writeValueAsString(out);
    }

    private static Map<String, Object> timing(ActorTurn turn) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("submittedAt", turn.submittedAt);
        row.put("submitReturnedAt", turn.submitReturnedAt);
        row.put("terminalAt", turn.terminalAt);
        row.put("unit", turn.home == null ? "-" : turn.home.getFileName().toString());
        row.put("unitKey", turn.key().toString());
        row.put("state", turn.userState);
        return row;
    }

    private String summaryEvidence(long bothArmed, long overlapMillis) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("datasource", datasourceUrl);
        out.put("baseProfile", baseProfile.toString());
        out.put("unitsRoot", unitsRoot.toString());
        out.put("port", port);
        out.put("sharedTenantId", sharedTenantId);
        out.put("sharedProjectId", sharedProjectId);
        out.put("unitKeys", List.of(first.key().toString(), second.key().toString()));
        out.put("A", Map.of("actorId", first.actorId, "generationId", first.generationId,
            "bindingId", first.bindingId, "draftId", first.draftId, "unit",
            first.home.getFileName().toString(), "state", first.userState,
            "mcpTrace", String.valueOf(service.mcpTrace(first.conversationId, first.requestId))));
        out.put("B", Map.of("actorId", second.actorId, "generationId", second.generationId,
            "bindingId", second.bindingId, "draftId", second.draftId, "unit",
            second.home.getFileName().toString(), "state", second.userState,
            "mcpTrace", String.valueOf(service.mcpTrace(second.conversationId, second.requestId))));
        out.put("overlapMillis", overlapMillis);
        out.put("bothUnitsArmedSampleTicks", bothArmed);
        out.put("crossLeaks", List.copyOf(crossLeaks));
        out.put("sampleCount", samples.size());
        out.put("liveUnitsAtEnd", pool.liveUnits());
        return json.writeValueAsString(out);
    }

    private String describeEntry(ActorTurn turn, String phase) {
        Path config = configOf(turn);
        Map<String, Object> servers = config == null ? Map.of() : mcpServers(config);
        String url = config == null ? null : haitlifyUrl(config);
        StringBuilder text = new StringBuilder();
        text.append("phase=").append(phase).append('\n');
        text.append("user=").append(turn.label).append('\n');
        text.append("unit=").append(turn.home == null ? "-" : turn.home.getFileName()).append('\n');
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
        writeEvidence("s00-mcp-surface-probe.txt", "POST /mcp-lease/<43 fakes> -> " + unknown.statusCode()
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

    private String prompt(ActorTurn turn) {
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
            """.formatted(turn.marker, turn.specId, turn.specName, turn.targetUrl, turn.targetUrl,
            turn.stepAssert);
    }

    // ------------------------------------------------------------------ 收尾

    private void writeGatewayStderrEvidence() {
        if (gatewayStderr == null) {
            return;
        }
        StringBuilder text = new StringBuilder();
        gatewayStderr.list.forEach(event -> text.append(redact(event.getFormattedMessage())).append('\n'));
        writeEvidence("s11-gateway-stderr.txt", text.toString());
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
            writeEvidence("s13-cleanup-units.txt", closeUnitsAndReport());
        } catch (Exception failure) {
            writeEvidence("s13-cleanup-units.txt", "清理证据写入失败：" + failure.getClass().getSimpleName());
        }
        cleanupRows();
        SecurityContextHolder.clearContext();
    }

    private String closeUnitsAndReport() {
        StringBuilder log = new StringBuilder();
        log.append("unitsRoot=").append(unitsRoot).append('\n');
        log.append("unitDirsBeforeClose=").append(unitDirs()).append('\n');
        log.append("liveUnitsBeforeClose=").append(pool.liveUnits()).append('\n');
        try {
            pool.close();
        } catch (RuntimeException failure) {
            log.append("pool.close FAILED: ").append(failure.getClass().getSimpleName()).append('\n');
        }
        log.append("liveUnitsAfterClose=").append(pool.liveUnits()).append('\n');
        log.append("unitDirsAfterClose=").append(unitDirs()).append('\n');
        log.append("unitSubprocessesLeft=").append(unitSubprocesses()).append('\n');
        try {
            for (Path root : List.of(unitsRoot, workspace)) {
                if (Files.isDirectory(root)) {
                    try (var paths = Files.walk(root)) {
                        paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (Exception ignored) {
                                // 尽力而为；下面的存在性核对才是判据。
                            }
                        });
                    }
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

    private void cleanupRows() {
        if (first == null || second == null) {
            return;
        }
        Map<String, String> before = tableCounts();
        StringBuilder log = new StringBuilder();
        log.append("datasource=").append(datasourceUrl).append('\n');
        log.append("credentials=<from env only; never logged>\n\n");
        for (ActorTurn turn : List.of(first, second)) {
            log.append("--- user ").append(turn.label).append(" (actor ").append(turn.actorId).append(") ---\n");
            delete(log, "assistant_generation_drafts", "generation_id = ?", turn.generationId);
            delete(log, "assistant_generation_executions", "generation_id = ?", turn.generationId);
            delete(log, "assistant_generations", "id = ?", turn.generationId);
            delete(log, "assistant_draft", "draft_id = ?", turn.draftId);
            delete(log, "assistant_execution_grants", "binding_id = ?", turn.bindingId);
            delete(log, "assistant_execution_bindings", "id = ?", turn.bindingId);
            delete(log, "assistant_conversation_messages", "conversation_id = ?", turn.conversationId);
            delete(log, "assistant_conversations", "id = ?", turn.conversationId);
            delete(log, "assistant_execution_worker_units", "unit_id = ?", turn.workerUnitId);
            delete(log, "platform_users", "id = ?", turn.actorId);
        }
        // 共享的租户与项目只删一次（两个 actor 都在里面）。
        delete(log, "projects", "id = ?", sharedProjectId);
        delete(log, "tenants", "id = ?", sharedTenantId);
        Map<String, String> after = tableCounts();
        log.append("\n--- row counts (before seeding / before cleanup / after cleanup) ---\n");
        for (String table : CLEANUP_TABLES) {
            log.append(table).append(": ")
                .append(countsBeforeSeed == null ? "?" : countsBeforeSeed.get(table)).append(" / ")
                .append(before.get(table)).append(" / ").append(after.get(table)).append('\n');
        }
        log.append("\n--- leftovers of THIS run ---\n");
        for (ActorTurn turn : List.of(first, second)) {
            log.append(turn.label).append(": ");
            log.append("drafts=").append(leftover("assistant_generation_drafts", "generation_id = ?",
                turn.generationId));
            log.append(" generations=").append(leftover("assistant_generations", "id = ?", turn.generationId));
            log.append(" drafts_body=").append(leftover("assistant_draft", "draft_id = ?", turn.draftId));
            log.append(" grants=").append(leftover("assistant_execution_grants", "binding_id = ?",
                turn.bindingId));
            log.append(" bindings=").append(leftover("assistant_execution_bindings", "id = ?", turn.bindingId));
            log.append(" conversations=").append(leftover("assistant_conversations", "id = ?",
                turn.conversationId));
            log.append(" messages=").append(leftover("assistant_conversation_messages", "conversation_id = ?",
                turn.conversationId));
            log.append(" users=").append(leftover("platform_users", "id = ?", turn.actorId));
            log.append('\n');
        }
        log.append("shared: projects=").append(leftover("projects", "id = ?", sharedProjectId));
        log.append(" tenants=").append(leftover("tenants", "id = ?", sharedTenantId)).append('\n');
        writeEvidence("s14-db-cleanup.txt", log.toString());
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
        return value;
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
