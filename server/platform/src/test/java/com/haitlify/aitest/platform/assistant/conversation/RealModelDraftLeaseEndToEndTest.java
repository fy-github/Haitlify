package com.haitlify.aitest.platform.assistant.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

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
import com.haitlify.aitest.platform.security.SecretCipher;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.ObjectMapper;

/**
 * 真实业务纵链的端到端验收：<b>真实模型 → 真实 stdio 网关 → 平台签发的 MCP 租约 → 真实 draft_create →
 * 草稿落库</b>。
 *
 * <p>与 {@code HermesModelEndToEndTest} 的分工：那个用例只证明「平台上能向真实模型提交一轮」；本用例证明
 * <b>整条能力链</b>：平台在派发前武装租约、把 {@code <base>/mcp-lease/<leaseId>} 写进网关配置并 reload，
 * 网关里的模型通过这条 MCP 端点调用 {@code generation_context} / {@code draft_create}，平台用真实 grant
 * 授权并把草稿连同 provenance 一起落库；终态之后网关条目被清除、网关级许可被释放。
 *
 * <p>为什么必须默认跳过（CI 不跑）：它需要一次真实的模型调用（分钟级、要网络、要模型凭据）和一个真实的
 * Hermes 子进程；把它放进确定性套件既不稳也不该。门控变量：
 * <ul>
 *   <li>{@code HAITLIFY_REAL_DRAFT_E2E=true}——显式打开（同 {@code HermesModelEndToEndTest} 的模式）；</li>
 *   <li>{@code HAITLIFY_REAL_DRAFT_E2E_HERMES_HOME}——<b>临时</b> HERMES_HOME（0600 的 config.yaml 副本）。
 *       它同时是网关配置文件的落点：平台写的条目与子进程读的配置必须是同一个文件，否则「写成功、reload
 *       成功、模型却看不到工具」；</li>
 *   <li>{@code HAITLIFY_REAL_DRAFT_E2E_PYTHON} / {@code ..._AGENT_DIR}——stdio 网关入口
 *       （{@code python -m tui_gateway.entry}）；</li>
 *   <li>{@code HAITLIFY_REAL_DRAFT_E2E_PORT}——固定端口。租约 URL 的 host:port 必须可预测
 *       （{@code public-base-url} 与实际监听端口一致），随机端口做不到。</li>
 * </ul>
 *
 * <p>证据（模型终态文本、条目 before/during/after、draft 行字段、数据库快照）写到
 * 显式环境变量 {@code HAITLIFY_REAL_DRAFT_E2E_EVIDENCE_DIR} 指定的隔离目录，全部脱敏：不写 lease id、token、任何凭据。
 *
 * <p>本类<b>不修改任何被测实现</b>：模型没调工具、参数不合法、终态为空都如实失败；不做「为了让结果通过而
 * 放宽断言」的事。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "HAITLIFY_REAL_DRAFT_E2E", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealModelDraftLeaseEndToEndTest {

    private static final String TURN_REQUEST_ID = "real-draft-e2e-turn-1";
    private static final String TARGET_URL = "https://example.com/haitlify/real-draft-e2e/console";
    private static final String REQUIREMENT_REF = "requirement:real-draft-e2e";
    private static final String REQUIREMENT_TEXT = "打开控制台并确认应用可见（真实模型 E2E 的需求文本）";
    private static final String TARGET_REVISION = "rev-real-e2e";

    /** 与 {@code application-test.yml} 里其余用例一致：测试上下文用固定 32 字节密钥。 */
    private static final String TEST_SECRET_KEY = "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=";

    private static final Duration SUBMIT_TIMEOUT = Duration.ofSeconds(30);
    /** 模型一轮的硬上界：{@code awaitFinalText} 自己也只接受 ≤5 分钟。 */
    private static final Duration REPLY_TIMEOUT = Duration.ofMinutes(5);
    /** 终态之后「条目被清掉 + 许可被释放」的观测窗口。 */
    private static final Duration SETTLE_TIMEOUT = Duration.ofSeconds(60);

    /** 由 {@link #wire} 在上下文刷新前填好（类加载时不读环境，避免「被跳过也要读环境」的假失败）。 */
    private static Path hermesHome;
    private static Path configFile;
    private static Path workspace;
    private static Path evidenceDir;
    /** 本次真正用到的数据源（证据里要写清是 H2 还是 MySQL）；凭据永不写进任何文件。 */
    private static String datasourceUrl;

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        hermesHome = Path.of(env("HAITLIFY_REAL_DRAFT_E2E_HERMES_HOME"));
        configFile = hermesHome.resolve("config.yaml");
        workspace = hermesHome.resolve("workspace");
        evidenceDir = Path.of(env("HAITLIFY_REAL_DRAFT_E2E_EVIDENCE_DIR"));
        String python = env("HAITLIFY_REAL_DRAFT_E2E_PYTHON");
        String agentDir = env("HAITLIFY_REAL_DRAFT_E2E_AGENT_DIR");
        int port = Integer.parseInt(envOrDefault("HAITLIFY_REAL_DRAFT_E2E_PORT", "18080"));

        // 前置事实先断言一次：缺文件/缺解释器时明确失败，而不是等到 submit 才看到「网关不可用」。
        if (!Files.isRegularFile(configFile) || !Files.isRegularFile(Path.of(python)) || !Files.isExecutable(Path.of(python))
            || !Files.isDirectory(Path.of(agentDir))) {
            throw new IllegalStateException("真实 E2E 前置缺失：需要 " + configFile + " 与 " + python);
        }
        try {
            Files.createDirectories(workspace);
            Files.createDirectories(evidenceDir);
        } catch (Exception ex) {
            throw new IllegalStateException("无法准备临时目录", ex);
        }

        // 数据源可切换：默认仍是 H2（CI 默认行为不变）；给出 HAITLIFY_REAL_DRAFT_E2E_DB_URL 才走真库。
        // 凭据只从环境读，绝不写进代码/测试资源。
        datasourceUrl = envOrDefault("HAITLIFY_REAL_DRAFT_E2E_DB_URL",
            "jdbc:h2:mem:real-draft-e2e;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        boolean mysql = datasourceUrl.startsWith("jdbc:mysql:");

        registry.add("server.port", () -> port);
        registry.add("spring.datasource.url", () -> datasourceUrl);
        registry.add("spring.datasource.username",
            () -> envOrDefault("HAITLIFY_REAL_DRAFT_E2E_DB_USERNAME", mysql ? "haitlify_app" : "sa"));
        registry.add("spring.datasource.password",
            () -> envOrDefault("HAITLIFY_REAL_DRAFT_E2E_DB_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name",
            () -> mysql ? "com.mysql.cj.jdbc.Driver" : "org.h2.Driver");
        registry.add("APP_SECRET_KEY", () -> TEST_SECRET_KEY);
        registry.add("app.security.enabled", () -> "false");

        // 三道开关全开：对话入口、租约铸造、网关配置（外加 MCP 端点本身）。
        registry.add("app.assistant.conversation.enabled", () -> "true");
        registry.add("app.assistant.conversation.mcp-lease.enabled", () -> "true");
        registry.add("app.assistant.conversation.mcp-lease.configure-gateway", () -> "true");
        registry.add("app.assistant.conversation.mcp-lease.public-base-url",
            () -> "http://127.0.0.1:" + port);
        registry.add("app.assistant.conversation.mcp-lease.ttl-seconds", () -> "300");
        registry.add("app.assistant.conversation.mcp-lease.gateway-config-path", configFile::toString);
        registry.add("app.assistant.conversation.mcp-lease.gateway-reload-timeout-seconds", () -> "240");
        registry.add("app.assistant.mcp.enabled", () -> "true");
        registry.add("app.assistant.mcp.lease.enabled", () -> "true");
        registry.add("app.assistant.mcp.lease.ttl-seconds", () -> "300");

        // stdio 真实子进程：命令由空格分隔（HermesTuiGateway 的契约），cwd 让 `-m tui_gateway.entry` 可导入。
        registry.add("app.assistant.hermes.transport", () -> "stdio");
        registry.add("app.assistant.hermes.command", () -> python + " -m tui_gateway.entry");
        registry.add("app.assistant.hermes.cwd", () -> agentDir);
        registry.add("app.assistant.hermes.timeout-seconds", () -> "120");
        registry.add("app.assistant.conversation.reply-timeout-seconds", () -> "300");
        registry.add("app.assistant.conversation.resume-idle-wait-seconds", () -> "20");
        registry.add("app.assistant.conversation.workspace", workspace::toString);

        // 证据需要网关 stderr（子进程的 stderr 由平台按 debug 落日志）：只调这一个 logger，不动全局级别。
        registry.add("logging.level.com.haitlify.aitest.platform.assistant.hermes.HermesTuiGateway",
            () -> "DEBUG");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired SecretCipher cipher;
    @Autowired ExecutionBindingService execution;
    @Autowired AssistantConversationService service;
    @Autowired HermesTuiGateway stdioGateway;

    private ConversationTurnFixture.Tenant tenant;
    private String generationId;
    private String bindingId;
    private String unitId;
    private String conversationId;
    private String draftId;
    /** 本轮开始前的行数快照（清理证据要能对比，而不是只说「删了」）。 */
    private Map<String, String> countsBeforeSeed;
    private static final List<String> CLEANUP_TABLES = List.of(
        "assistant_generation_drafts", "assistant_generation_executions", "assistant_generations",
        "assistant_draft",
        "assistant_execution_grants", "assistant_execution_bindings", "assistant_execution_worker_units",
        "assistant_conversations", "assistant_conversation_messages",
        "tenants", "projects", "platform_users");
    /** 网关子进程 stderr 的采集器（平台按 debug 落这份日志；证据不能只留在 mvn 输出里）。 */
    private ListAppender<ILoggingEvent> gatewayStderr;

    @BeforeAll
    void seedOnce() throws Exception {
        countsBeforeSeed = tableCounts();
        // 1) 真实仓储/SQL：租户 / 项目 / 操作者（ADMIN，免成员表），以及本轮 generation 与 ACTIVE binding。
        tenant = ConversationTurnFixture.seedTenant(jdbc, "real-draft-e2e");
        generationId = UUID.randomUUID().toString();
        String snapshot = json.writeValueAsString(new TreeMap<>(Map.of(
            "requestId", TURN_REQUEST_ID,
            "projectId", tenant.projectId(),
            "requirementRef", REQUIREMENT_REF,
            "requirementText", REQUIREMENT_TEXT,
            "targetUrl", TARGET_URL,
            "targetRevision", TARGET_REVISION)));
        jdbc.update("insert into assistant_generations(id,actor_id,project_id,tenant_id,request_id,payload_digest,"
                + "snapshot_ciphertext,state,created_at) values (?,?,?,?,?,?,?,'WAITING_WORKER',?)",
            generationId, tenant.actorId(), tenant.projectId(), tenant.tenantId(), TURN_REQUEST_ID,
            sha256(snapshot), cipher.encrypt(snapshot), Instant.now());
        String unit = "unit-" + UUID.randomUUID();
        unitId = unit;
        execution.confirmUnitCleaned(execution.registerUnit(
            new ExecutionBindingService.WorkerUnit(unit, "sha256:" + "c".repeat(64), "policy-real-e2e")));
        bindingId = execution.allocate(new ExecutionBindingService.AllocationRequest(
            generationId, unit, Duration.ofMinutes(5))).bindingId();

        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(tenant.username(), "unused",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterAll
    void clearPrincipalAndCleanUpThisRun() {
        // 早失败的路径也要留下 stderr：那条 [gateway-exit]/[gateway-crash] 往往就是唯一线索。
        writeGatewayStderrEvidence();
        if (gatewayStderr != null) {
            ((Logger) LoggerFactory.getLogger(HermesTuiGateway.class)).detachAppender(gatewayStderr);
            gatewayStderr = null;
        }
        cleanUpThisRun();
        SecurityContextHolder.clearContext();
    }

    /**
     * 一轮真实提交 → 模型经租约调用 {@code draft_create} → 草稿落库 → 终态收尾。
     *
     * <p>断言分四组，对应验收要求的四条：草稿行（含 provenance 重算）、本轮终态文本、终态后网关条目消失、
     * 网关级许可已释放。
     */
    @Test
    void realModelCreatesADraftThroughTheLeaseAndThePlatformPersistsIt() throws Exception {
        // 0) 前置事实：租约 HTTP 面真的在监听（否则后面所有的「模型没调工具」都只是这一个缺口的症状），
        //    并且 stdio 网关子进程已经冷启动完毕（首次 state.db 初始化十几秒，不能算进本轮预算）。
        gatewayStderr = attachGatewayStderrCapture();
        assertLeaseSurfaceIsMounted();
        long warmUpMillis = warmUpGateway();
        System.out.println("[real-draft-e2e] 网关预热完成：" + warmUpMillis + "ms");
        writeGatewayStderrEvidence();

        Path config = configFile;
        assertThat(haitlifyUrl(config))
            .as("前置：临时 HERMES_HOME 的 mcp_servers 里本来没有 haitlify 条目（否则无法证明是本轮写的）")
            .isNull();
        writeEvidence("02-mcp-entry-before.txt", describeEntry(config, "before"));

        String conversationId = createConversation();
        String prompt = prompt();

        long startedAt = System.nanoTime();
        AssistantConversationService.SubmitReceipt receipt =
            service.submit(conversationId, Map.of("requestId", TURN_REQUEST_ID, "text", prompt));
        assertThat(receipt.state())
            .as("生产入口必须受理这一轮（否则链路根本没跑起来）")
            .isEqualTo(AssistantConversationService.STATE_SUBMITTED);
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(SUBMIT_TIMEOUT);

        // 派发前后的事实：条目 = 本轮租约 URL（模型会话建立时读到的正是它）。
        String armedUrl = haitlifyUrl(config);
        assertThat(armedUrl)
            .as("submit 返回时网关配置里必须已经是本轮的租约 URL")
            .isNotNull();
        assertThat(armedUrl).startsWith("http://127.0.0.1:" + port() + "/mcp-lease/");
        assertThat(service.mcpTrace(conversationId, TURN_REQUEST_ID))
            .isEqualTo(AssistantConversationService.MCP_TRACE_CONFIGURED);
        writeEvidence("03-mcp-entry-during.txt", describeEntry(config, "during"));

        String userState = awaitTerminalUserState(conversationId);
        String finalText = assistantText(conversationId);
        writeGatewayStderrEvidence();

        // ---- 1) 草稿行（真实 draft_create 的唯一产物）
        List<Map<String, Object>> draftRows = jdbc.queryForList(
            "select * from assistant_generation_drafts where generation_id=?", generationId);
        assertThat(draftRows)
            .as("模型没有经租约调用 draft_create（或调用被拒）：assistant_generation_drafts 里没有本轮的草稿行。"
                + " 用户消息终态=%s，终态文本=%s，MCP 痕迹=%s（网关 stderr：%s）",
                userState, abbreviate(finalText),
                service.mcpTrace(conversationId, TURN_REQUEST_ID), stderrTail())
            .hasSize(1);
        Map<String, Object> draftRow = draftRows.get(0);
        draftId = (String) draftRow.get("draft_id");
        long leaseGeneration = ((Number) draftRow.get("lease_generation")).longValue();
        String snapshotDigest = (String) draftRow.get("snapshot_digest");
        String specDigest = (String) draftRow.get("spec_digest");
        String provenanceDigest = (String) draftRow.get("provenance_digest");

        assertThat(draftRow.get("binding_id")).as("草稿必须挂在种子 binding 上").isEqualTo(bindingId);
        String payloadDigest = jdbc.queryForObject(
            "select payload_digest from assistant_generations where id=?", String.class, generationId);
        assertThat(snapshotDigest).as("snapshot_digest 必须等于本轮 generation 的 payload_digest")
            .isEqualTo(payloadDigest);

        Map<String, Object> draft = jdbc.queryForMap("select * from assistant_draft where draft_id=?", draftId);
        assertThat(sha256((String) draft.get("draft_json")))
            .as("spec_digest 必须是落库草稿 JSON 的摘要（同一份 spec，不是另写一份）")
            .isEqualTo(specDigest);
        assertThat(draft.get("state")).isEqualTo("DRAFT");
        assertThat(((Number) draft.get("tenant_id")).longValue()).isEqualTo(tenant.tenantId());
        assertThat(((Number) draft.get("project_id")).longValue()).isEqualTo(tenant.projectId());
        assertThat(((Number) draft.get("created_by")).longValue()).isEqualTo(tenant.actorId());
        assertThat(draft.get("action_code")).isEqualTo("web.run");
        assertThat(draft.get("target_ref")).isEqualTo("project:" + tenant.projectId());
        assertThat(draft.get("target_revision")).isEqualTo(TARGET_REVISION);
        assertThat(draft.get("requirement_ref")).isEqualTo(REQUIREMENT_REF);

        String recomputed = sha256(json.writeValueAsString(List.of(generationId, draftId, bindingId,
            leaseGeneration, snapshotDigest, specDigest)));
        assertThat(provenanceDigest)
            .as("provenance_digest 必须能由行数据逐字段重算出来（否则「来源可核」只是一句注释）")
            .isEqualTo(recomputed);

        // 模型写出的 spec 必须真的可解析且与授权 URL 同源（GenerationSpecPolicy 已拦过一遍，这里复核落库内容）。
        var spec = json.readTree((String) draft.get("draft_json"));
        assertThat(spec.path("startUrl").asString()).isEqualTo(TARGET_URL);
        assertThat(spec.path("steps").isArray()).isTrue();
        assertThat(spec.path("steps").size()).isGreaterThan(0);

        // ---- 2) 本轮终态文本
        assertThat(userState)
            .as("本轮必须走到 COMPLETED（SUBMITTED 表示 %ss 内模型仍未跑完；UNKNOWN/FAILED 表示已明确失败，"
                + " 详见网关 stderr：%s）", REPLY_TIMEOUT.plusSeconds(60).toSeconds(), stderrTail())
            .isEqualTo(AssistantConversationService.STATE_COMPLETED);
        assertThat(finalText).as("终态回复不得为空（空文本只会落 FAILED）").isNotBlank();

        // ---- 3) 终态之后网关条目必须消失
        awaitEntryCleared(config);
        assertThat(haitlifyUrl(config)).as("终态之后条目不得再指向本轮的（即将到 TTL 的）租约").isNull();
        writeEvidence("04-mcp-entry-after.txt", describeEntry(config, "after"));

        // ---- 4) 网关级许可必须已释放
        String messageId = jdbc.queryForObject("select id from assistant_conversation_messages"
            + " where conversation_id=? and request_id=? and role='USER'", String.class,
            conversationId, TURN_REQUEST_ID);
        long settleDeadline = System.nanoTime() + SETTLE_TIMEOUT.toNanos();
        while (System.nanoTime() < settleDeadline && service.holdsGatewayMcpPermit(messageId)) {
            Thread.sleep(100);
        }
        assertThat(service.holdsGatewayMcpPermit(messageId))
            .as("终态之后网关级单一许可必须已经归还（否则后续所有对话都会 MCP_PERMIT_BUSY）")
            .isFalse();

        // ---- 证据（脱敏）
        writeEvidence("01-model-terminal-text.txt", finalText);
        writeEvidence("05-draft-row.json", json.writeValueAsString(new LinkedHashMap<>(Map.of(
            "generationId", generationId,
            "draftId", draftId,
            "bindingId", bindingId,
            "leaseGeneration", leaseGeneration,
            "snapshotDigest", snapshotDigest,
            "specDigest", specDigest,
            "provenanceDigest", provenanceDigest,
            "provenanceDigestRecomputed", recomputed,
            "spec", json.readTree((String) draft.get("draft_json"))))));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("conversationId", conversationId);
        summary.put("generationId", generationId);
        summary.put("draftId", draftId);
        summary.put("userFinalState", userState);
        summary.put("assistantFinalTextLength", finalText.length());
        summary.put("mcpTrace", service.mcpTrace(conversationId, TURN_REQUEST_ID));
        summary.put("gatewayEntryDuring", "http://127.0.0.1:" + port() + "/mcp-lease/<redacted-43>");
        summary.put("gatewayEntryAfter", null);
        summary.put("permitHeldAfterTerminal", false);
        summary.put("draftRowCount", draftRows.size());
        writeEvidence("06-db-snapshot.json", json.writeValueAsString(summary));

        // 数据源原始字段（真库跑时这就是 MySQL 的真实输出；清理发生在 @AfterAll 之后）。
        writeEvidence("13-draft-row-raw.txt", rawDraftRow());
    }

    // ------------------------------------------------------------------ 生产入口

    /**
     * <b>前置断言：MCP 租约面真的被挂上了</b>。
     *
     * <p>为什么要专门证明这一点：启动日志里那条「{@code Filter mcpEndpointServletRegistration was not
     * registered (disabled)}」<b>不是</b>缺失——{@code McpEndpoint} 是个 {@code Filter} Bean，Spring Boot 会
     * 把它自动注册到 {@code /*}（在安全链之外），所以 {@code McpSecurityConfiguration} 用一个禁用状态的
     * {@code FilterRegistrationBean} 专门压掉那次自动注册；真正的挂载点是两条 {@code SecurityFilterChain}
     * （{@code /mcp} 与 {@code /mcp-lease/**}，order -100/-101）里的 {@code addFilterBefore}。这两件事在日志里
     * 长得一模一样（都只有一行 "not registered"），所以只能从<b>行为</b>上证明：格式合法但未知的 lease id
     * 必须得到 401（走到了过滤器 + 注册表），而不是 404/连接被拒（没挂上）。
     */
    private void assertLeaseSurfaceIsMounted() throws Exception {
        String origin = "http://127.0.0.1:" + port();
        HttpResponse<String> unknownLease = postJson(origin + "/mcp-lease/" + "A".repeat(43),
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
        assertThat(unknownLease.statusCode())
            .as("租约端点没有挂上：未知但格式合法的 lease id 必须是 401（404/连接失败说明 /mcp-lease/** 根本没监听）")
            .isEqualTo(401);

        HttpResponse<String> noToken = postJson(origin + "/mcp",
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
        assertThat(noToken.statusCode())
            .as("/mcp 面也必须挂上且拒绝无凭据请求（否则模型即使拿到条目也没有端点可打）")
            .isEqualTo(401);
        writeEvidence("00-mcp-surface-probe.txt", "POST /mcp-lease/<43 fakes> -> " + unknownLease.statusCode()
            + "\nPOST /mcp (no bearer) -> " + noToken.statusCode() + "\n");
    }

    /**
     * 预热网关子进程：真实 Hermes 首次启动要建 state.db / 载入工具与配置（十几秒）。
     *
     * <p>不预热的话，这段冷启动时间会落在 {@code prompt.submit} 与 {@code reply-timeout} 的预算里，让一
     * 个本来能跑完的轮次看起来像「模型没反应」。
     *
     * <p>选 {@code client.capabilities} 而不是 {@code config.get}：后者只接受白名单 key，空参数会<b>正常
     * 回一个 error 帧</b>（实测），用它做预热必然失败；前者在 pin 上已实测可用，回包里带全部
     * {@code server_requests}，正好能证明「子进程真的应答了协议，而不是只有进程活着」。
     *
     * <p>错误帧必须显式区分：{@code error.code/message} 写进证据与断言文案，绝不只说「不是 true」。
     */
    private long warmUpGateway() {
        assertThat(stdioGateway.available())
            .as("stdio 网关必须可用（否则本用例只会验证到「子进程没起来」）")
            .isTrue();
        var params = json.createObjectNode();
        params.put("server_requests", true);
        long startedAt = System.nanoTime();
        var response = stdioGateway.rpc("client.capabilities", params, Duration.ofSeconds(180));
        long millis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        var error = response.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            writeEvidence("08-warmup.json", "client.capabilities 回包=error frame\n"
                + "error.code=" + error.path("code").asString("") + "\n"
                + "error.message=" + error.path("message").asString("") + "\n");
            throw new AssertionError("预热 RPC 被网关拒绝（error frame）：code="
                + error.path("code").asString("") + " message=" + error.path("message").asString(""));
        }
        var result = response.path("result");
        var serverRequests = result.path("server_requests");
        writeEvidence("08-warmup.json", "client.capabilities 回包=result\n"
            + "warmUpMillis=" + millis + "\n"
            + "result.keys=" + result.propertyNames() + "\n"
            + "server_requests.count=" + serverRequests.size() + "\n"
            + "server_requests=" + serverRequests + "\n");
        assertThat(serverRequests.isArray())
            .as("预热必须拿到真实内容（server_requests 数组），而不是空 result：%s", result)
            .isTrue();
        assertThat(serverRequests.size())
            .as("server_requests 不能为空（空数组说明网关没按契约应答）")
            .isGreaterThan(0);
        assertThat(serverRequests.valueStream().map(node -> node.asString()).toList())
            .as("pin 的宣告里必须含 approval 与 clarify")
            .contains("approval", "clarify");
        return millis;
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

    /** 采集平台按 debug 记录的 {@code [hermes stderr]} 行：证据要落到文件，不能只留在 mvn 控制台。 */
    private ListAppender<ILoggingEvent> attachGatewayStderrCapture() {
        Logger logger = (Logger) LoggerFactory.getLogger(HermesTuiGateway.class);
        // 级别在代码里定死：属性没生效时 debug() 会被 logger 过滤掉，appender 只会收到空列表，
        // 于是「stderr 没内容」和「没配 level」看起来一模一样（第一次跑就是这样丢了证据）。
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void writeGatewayStderrEvidence() {
        if (gatewayStderr == null) {
            return;
        }
        StringBuilder text = new StringBuilder();
        gatewayStderr.list.forEach(event -> text.append(redact(event.getFormattedMessage())).append('\n'));
        writeEvidence("07-gateway-stderr.txt", text.toString());
    }

    private String stderrTail() {
        if (gatewayStderr == null || gatewayStderr.list.isEmpty()) {
            return "(空)";
        }
        int from = Math.max(0, gatewayStderr.list.size() - 6);
        StringBuilder text = new StringBuilder();
        for (int index = from; index < gatewayStderr.list.size(); index++) {
            text.append(redact(gatewayStderr.list.get(index).getFormattedMessage())).append(" | ");
        }
        return text.toString();
    }

    /** 脱敏：任何 {@code /mcp-lease/<43 字符>} 都替换成占位符（stderr 里可能出现被拒的 URL）。 */
    private static String redact(String line) {
        return line == null ? "" : line.replaceAll("/mcp-lease/[A-Za-z0-9_-]{43}", "/mcp-lease/<redacted>");
    }

    private String createConversation() {
        AssistantConversationService.ConversationView created = service.create(Map.of(
            "requestId", "conv-" + UUID.randomUUID(),
            "projectId", tenant.projectId(),
            "title", "real-draft-e2e"));
        assertThat(created.conversationId()).isNotBlank();
        conversationId = created.conversationId();
        return created.conversationId();
    }

    /**
     * 要求模型「先读上下文、再建草稿」，并把授权 URL 与 spec 形状写死在 prompt 里。
     *
     * <p>为什么把 URL 写进 prompt 而不是让模型自己猜：{@code GenerationSpecPolicy} 要求 {@code startUrl}
     * 与授权 targetUrl 同源，猜出来的域名只会得到 400；而「同源」这件事本身仍由平台校验，prompt 不参与授权。
     * 这里的 prompt 是唯一可以反复调整的东西（若模型第一次没按格式调用，最多重试 3 次并逐次留证）。
     */
    private String prompt() {
        return """
            你是 Haitlify 平台的用例生成助手。请严格按步骤执行，不要提问、不要解释、不要输出计划，也不要使用 \
            除下面列出的工具以外的任何工具。

            第 1 步：调用工具 mcp__haitlify__generation_context（无参数），读取本轮授权的生成上下文，\
            确认其中 targetUrl 的值。

            第 2 步：调用工具 mcp__haitlify__draft_create 创建草稿。它的参数只有一个 spec 对象，形状严格如下\
            （把尖括号换成你自己的内容，其余键名与结构一字不改）：

            {"spec":{"id":"real-e2e-console-check","name":"控制台加载校验","startUrl":"%s","steps":[\
            {"id":"s1","action":"goto","target":"%s"},{"id":"s2","action":"assertVisible","target":"#app"}]}}

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
            """.formatted(TARGET_URL, TARGET_URL);
    }

    // ------------------------------------------------------------------ 轮询 / 读取

    /** 等本轮 USER 行离开 SUBMITTED；超时即以它当时的状态返回（由调用方断言时暴露真实原因）。 */
    private String awaitTerminalUserState(String conversationId) throws Exception {
        long deadline = System.nanoTime() + REPLY_TIMEOUT.plusSeconds(60).toNanos();
        String state = AssistantConversationService.STATE_SUBMITTED;
        while (System.nanoTime() < deadline) {
            state = jdbc.queryForObject("select state from assistant_conversation_messages"
                + " where conversation_id=? and request_id=? and role='USER'", String.class,
                conversationId, TURN_REQUEST_ID);
            if (!AssistantConversationService.STATE_SUBMITTED.equals(state)) {
                return state;
            }
            Thread.sleep(500);
        }
        return state;
    }

    /** 终态文本只从 ASSISTANT 行读：它是平台接受的唯一权威回复。 */
    private String assistantText(String conversationId) {
        List<String> texts = jdbc.queryForList("select body_text from assistant_conversation_messages"
            + " where conversation_id=? and role='ASSISTANT' order by created_at desc", String.class,
            conversationId);
        return texts.isEmpty() ? "" : texts.get(0);
    }

    private void awaitEntryCleared(Path config) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline && haitlifyUrl(config) != null) {
            Thread.sleep(200);
        }
    }

    // ------------------------------------------------------------------ 网关配置文件（脱敏读取）

    private static String haitlifyUrl(Path config) {
        Map<String, Object> servers = mcpServers(config);
        Object entry = servers.get("haitlify");
        if (!(entry instanceof Map<?, ?> map)) {
            return null;
        }
        Object url = map.get("url");
        return url instanceof String text ? text : null;
    }

    private static Map<String, Object> mcpServers(Path config) {
        try {
            Object loaded = new Yaml(new SafeConstructor(new LoaderOptions()))
                .load(Files.readString(config, StandardCharsets.UTF_8));
            if (loaded instanceof Map<?, ?> root && root.get("mcp_servers") instanceof Map<?, ?> servers) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) servers;
                return cast;
            }
            return Map.of();
        } catch (Exception ex) {
            throw new IllegalStateException("读不了网关配置（不打印内容，避免带出 URL）", ex);
        }
    }

    /** 条目快照：只记录「有没有、host、路径前缀、lease id 长度」，绝不落 URL 原文。 */
    private static String describeEntry(Path config, String phase) {
        Map<String, Object> servers = mcpServers(config);
        String url = haitlifyUrl(config);
        StringBuilder text = new StringBuilder();
        text.append("phase=").append(phase).append('\n');
        text.append("mcp_servers.keys=").append(new ArrayList<>(servers.keySet())).append('\n');
        text.append("haitlify.present=").append(url != null).append('\n');
        if (url != null) {
            int slash = url.lastIndexOf('/');
            String leaseId = slash < 0 ? "" : url.substring(slash + 1);
            text.append("haitlify.url.origin=").append(url.substring(0, url.indexOf("/mcp-lease")))
                .append('\n');
            text.append("haitlify.url.path=/mcp-lease/<redacted>\n");
            text.append("haitlify.url.leaseIdLength=").append(leaseId.length()).append('\n');
            text.append("haitlify.url.leaseIdRedacted=true\n");
        }
        return text.toString();
    }

    private int port() {
        return Integer.parseInt(envOrDefault("HAITLIFY_REAL_DRAFT_E2E_PORT", "18080"));
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 数据源里的草稿行原文（问的是<b>库</b>，不是内存里的对象）。
     *
     * <p>先按验收单里给的那条单表 SQL 原样执行一次——{@code state}/{@code created_by} 并不在
     * {@code assistant_generation_drafts} 上（它们在 {@code assistant_draft}），所以那条 SQL 在真实 schema 上
     * 会因列不存在而失败，失败本身就是证据；随后用等价的 join 版本取到全部字段。
     */
    private String rawDraftRow() {
        StringBuilder text = new StringBuilder("datasource=").append(datasourceUrl).append("\n\n");
        text.append("-- 1) 验收单原文（单表；state/created_by 不在该表上）\n");
        try {
            jdbc.queryForList("select draft_id,generation_id,binding_id,lease_generation,spec_digest,"
                + "snapshot_digest,provenance_digest,state,created_by,created_at from assistant_generation_drafts"
                + " where generation_id=?", generationId).forEach(row -> text.append(row).append('\n'));
        } catch (RuntimeException failed) {
            text.append("ERROR: ").append(failed.getClass().getSimpleName()).append(": ")
                .append(firstLine(failed.getMessage())).append('\n');
        }
        text.append("\n-- 2) 等价 join 版本（同一行的全部字段）\n");
        List<Map<String, Object>> rows = jdbc.queryForList(
            "select d.draft_id, d.generation_id, d.binding_id, d.lease_generation, d.spec_digest,"
                + " d.snapshot_digest, d.provenance_digest, d.created_at,"
                + " a.state, a.created_by, a.project_id, a.tenant_id, a.action_code, a.target_ref, a.target_revision"
                + " from assistant_generation_drafts d join assistant_draft a on a.draft_id = d.draft_id"
                + " where d.generation_id=?", generationId);
        rows.forEach(row -> row.forEach((column, value) ->
            text.append(column).append('=').append(value).append('\n')));
        text.append("rows=").append(rows.size()).append('\n');

        text.append("\n-- 3) 会话终态 / mcpTrace / 条目 / 许可\n");
        text.append(jdbc.queryForList("select role, state, length(body_text) as body_len, failure_detail"
            + " from assistant_conversation_messages where conversation_id=? order by created_at",
            conversationId)).append('\n');
        text.append("mcpTrace=").append(service.mcpTrace(conversationId, TURN_REQUEST_ID)).append('\n');
        text.append("gatewayEntryAfterTerminal.present=").append(haitlifyUrl(configFile) != null).append('\n');
        return text.toString();
    }

    /**
     * 清理本轮种下的全部行，并把「清理前 / 清理后 / 本轮开始前」三份行数写进证据。
     *
     * <p>为什么放在 {@code @AfterAll}（而不是断言之后紧接着）：失败路径同样会留下租户/项目/generation，
     * 一条失败的真实 E2E 不该把测试数据留在开发库里。删除是逐表 try/catch 的：被外键挡住时<b>如实记录</b>
     * 并列出剩余行，绝不假装删干净。
     */
    private void cleanUpThisRun() {
        if (tenant == null) {
            return;
        }
        Map<String, String> before = tableCounts();
        StringBuilder log = new StringBuilder();
        log.append("datasource=").append(datasourceUrl).append('\n');
        log.append("credentials=<from env only; never logged>\n\n");
        log.append(flywayState());

        delete(log, "assistant_generation_drafts", "generation_id = ?", generationId);
        delete(log, "assistant_generation_executions", "generation_id = ?", generationId);
        delete(log, "assistant_generations", "id = ?", generationId);
        delete(log, "assistant_draft", "draft_id = ?", draftId);
        delete(log, "assistant_execution_grants", "binding_id = ?", bindingId);
        delete(log, "assistant_execution_bindings", "id = ?", bindingId);
        delete(log, "assistant_execution_worker_units", "unit_id = ?", unitId);
        delete(log, "assistant_conversation_messages", "conversation_id = ?", conversationId);
        delete(log, "assistant_conversations", "id = ?", conversationId);
        delete(log, "platform_users", "id = ?", tenant.actorId());
        delete(log, "projects", "id = ?", tenant.projectId());
        delete(log, "tenants", "id = ?", tenant.tenantId());

        Map<String, String> after = tableCounts();
        log.append("\n--- row counts (before seeding / before cleanup / after cleanup) ---\n");
        for (String table : CLEANUP_TABLES) {
            log.append(table).append(": ")
                .append(countsBeforeSeed == null ? "?" : countsBeforeSeed.get(table)).append(" / ")
                .append(before.get(table)).append(" / ").append(after.get(table)).append('\n');
        }
        log.append("\n--- leftovers of THIS run ---\n");
        log.append(leftover("assistant_generation_drafts", "generation_id = ?", generationId));
        log.append(leftover("assistant_generations", "id = ?", generationId));
        log.append(leftover("assistant_draft", "draft_id = ?", draftId));
        log.append(leftover("assistant_execution_grants", "binding_id = ?", bindingId));
        log.append(leftover("assistant_execution_bindings", "id = ?", bindingId));
        log.append(leftover("assistant_execution_worker_units", "unit_id = ?", unitId));
        log.append(leftover("assistant_conversation_messages", "conversation_id = ?", conversationId));
        log.append(leftover("assistant_conversations", "id = ?", conversationId));
        log.append(leftover("platform_users", "id = ?", tenant.actorId()));
        log.append(leftover("projects", "id = ?", tenant.projectId()));
        log.append(leftover("tenants", "id = ?", tenant.tenantId()));
        writeEvidence("11-db-cleanup.txt", log.toString());
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
            // 如实记录原因（可能被外键/权限挡住），由调用方在报告里说明。
            log.append("FAILED ").append(table).append(": ")
                .append(blocked.getClass().getSimpleName()).append(": ")
                .append(firstLine(blocked.getMessage())).append('\n');
        }
    }

    private String leftover(String table, String where, Object id) {
        if (id == null) {
            return table + ": (no id)\n";
        }
        try {
            Long remaining = jdbc.queryForObject("select count(*) from " + table + " where " + where,
                Long.class, id);
            return table + ": " + remaining + " row(s)\n";
        } catch (RuntimeException unsupported) {
            return table + ": <n/a: " + firstLine(unsupported.getMessage()) + ">\n";
        }
    }

    private Map<String, String> tableCounts() {
        Map<String, String> counts = new LinkedHashMap<>();
        for (String table : CLEANUP_TABLES) {
            try {
                counts.put(table, String.valueOf(jdbc.queryForObject("select count(*) from " + table, Long.class)));
            } catch (RuntimeException missing) {
                counts.put(table, "<n/a>");
            }
        }
        return counts;
    }

    /**
     * Flyway 状态（同一数据源上的真实证据）：最后应用到的版本、成功条数、最近 12 条。
     *
     * <p>对真库跑这条用例会<b>真的执行未应用的迁移</b>，所以「到底跑了哪些版本」必须是证据而不是回忆。
     */
    private String flywayState() {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                "select version, description, success from flyway_schema_history order by installed_rank desc limit 12");
            Long total = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success = true", Long.class);
            StringBuilder text = new StringBuilder();
            text.append("flyway.applied_success_count=").append(total).append('\n');
            text.append("flyway.recent (newest first):\n");
            rows.forEach(row -> text.append("  V").append(row.get("version")).append(" ")
                .append(row.get("description")).append(" success=").append(row.get("success")).append('\n'));
            return text.toString();
        } catch (RuntimeException unsupported) {
            return "flyway.history=<n/a: " + firstLine(unsupported.getMessage()) + ">\n";
        }
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        int newline = message.indexOf('\n');
        String line = newline < 0 ? message : message.substring(0, newline);
        return line.length() > 200 ? line.substring(0, 200) : line;
    }

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
