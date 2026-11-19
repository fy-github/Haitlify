package com.haitlify.aitest.platform.assistant.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.haitlify.aitest.platform.assistant.execution.ExecutionBindingService;
import com.haitlify.aitest.platform.assistant.mcp.McpLeaseRegistry;
import com.haitlify.aitest.platform.security.SecretCipher;

import tools.jackson.databind.ObjectMapper;

/**
 * <b>对话链路 → 执行单元</b>的端到端隔离：两个不同用户各自拿到自己的单元，且各自的配置文件里
 * <b>只有</b>自己的 lease URL。
 *
 * <p>为什么这条用例必须走真实链路（真实 Spring 上下文、真实租约铸造、真实子进程）：
 * 隔离的判据不是「池的 map 里有两个键」，而是「模型那一轮真的建会话的那一刻，网关进程读到的配置文件
 * 里是什么」。桩在 {@code prompt.submit} 时刻把自己 {@code HERMES_HOME/config.yaml} 的全文抄下来，
 * 于是断言的是<b>会话建立时绑定的条目</b>——这正是 {@code mcp/README.md} 里「绑定而不是查询」的位置。
 *
 * <p>钉住四件事：
 * <ol>
 *   <li>两个 {@code (tenant, project, actor)} 的轮次落在两个不同的单元目录里（不同子进程、不同配置）；</li>
 *   <li>各自派发时刻的条目里只有自己的 {@code /mcp-lease/<id>}，且两个 id 互不相同、互不出现；</li>
 *   <li>同一个键的多轮复用同一个单元（不逐轮新建子进程）；</li>
 *   <li>单元不可用（池满）时本轮 fail-closed：账本 {@code FAILED}、不派发、<b>不</b>回退到共享单元。</li>
 * </ol>
 *
 * <p>基准 profile 是临时目录里造出来的，命令是本仓测试里的 {@code python3} 桩：不启真实 Hermes、
 * 不调模型、不读任何真实 profile。
 */
@SpringBootTest(properties = {
    "app.assistant.conversation.enabled=true",
    "app.assistant.conversation.mcp-lease.enabled=true",
    "app.assistant.conversation.mcp-lease.configure-gateway=true",
    "app.assistant.conversation.mcp-lease.public-base-url=https://assistant.internal",
    "app.assistant.conversation.mcp-lease.ttl-seconds=120",
    "app.assistant.conversation.execution-units.enabled=true",
    "app.assistant.conversation.execution-units.capacity=64",
    "app.assistant.conversation.execution-units.idle-ttl-seconds=600",
    "app.assistant.conversation.execution-units.reap-interval-millis=200",
    "app.assistant.mcp.lease.enabled=true",
    "app.assistant.mcp.lease.ttl-seconds=120",
    "app.assistant.hermes.transport=stdio",
    "app.assistant.hermes.timeout-seconds=5",
    "app.assistant.conversation.reply-timeout-seconds=5",
    "app.security.enabled=false",
    "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
    "spring.datasource.url=jdbc:h2:mem:assistant-conversation-execution-units;MODE=MySQL;DB_CLOSE_DELAY=-1;"
        + "DATABASE_TO_LOWER=TRUE"
})
@ActiveProfiles("test")
class AssistantConversationExecutionUnitTest {

    private static final Pattern LEASE = Pattern.compile("/mcp-lease/([A-Za-z0-9_-]{43})");
    private static final ExecutionUnitHarness HARNESS = harness();

    private static ExecutionUnitHarness harness() {
        try {
            Path root = Files.createTempDirectory("execution-units-conversation-");
            ExecutionUnitHarness created = ExecutionUnitHarness.create(root, ExecutionUnitHarness.defaultProfileConfig());
            Runtime.getRuntime().addShutdownHook(new Thread(created::close, "execution-units-harness-cleanup"));
            return created;
        } catch (IOException failure) {
            throw new IllegalStateException("无法准备执行单元端到端夹具", failure);
        }
    }

    @DynamicPropertySource
    static void executionUnits(DynamicPropertyRegistry registry) {
        registry.add("app.assistant.conversation.execution-units.base-profile", () -> HARNESS.profile().toString());
        registry.add("app.assistant.conversation.execution-units.root", () -> HARNESS.unitsRoot().toString());
        registry.add("app.assistant.hermes.command", () -> "python3 " + HARNESS.script());
    }

    @Autowired AssistantConversationService service;
    @Autowired AssistantConversationRepository repository;
    @Autowired AssistantConversationAccess access;
    @Autowired AssistantConversationLeaseProvisioner leases;
    @Autowired ExecutionUnitPool pool;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired SecretCipher cipher;
    @Autowired ExecutionBindingService execution;
    @Autowired McpLeaseRegistry registry;

    private ConversationTurnFixture.Tenant alice;
    private ConversationTurnFixture.Tenant bob;

    @BeforeEach
    void seed() {
        alice = ConversationTurnFixture.seedTenant(jdbc, "unit-alice");
        bob = ConversationTurnFixture.seedTenant(jdbc, "unit-bob");
    }

    /**
     * 两个用户同时（各自的项目/租户）跑一轮：两个单元、两个子进程、两份配置，各自的条目只有自己的 URL。
     */
    @Test
    void twoUsersEachGetTheirOwnUnitAndOnlyTheirOwnLeaseEntry() throws Exception {
        String aliceConversation = create(alice, "unit-a-conv");
        String bobConversation = create(bob, "unit-b-conv");
        ConversationTurnFixture.seedTurn(jdbc, json, cipher, execution, alice, "unit-a-turn");
        ConversationTurnFixture.seedTurn(jdbc, json, cipher, execution, bob, "unit-b-turn");

        assertThat(asUser(alice.username(), () -> submit(aliceConversation, "unit-a-turn", "A 的需求")).state())
            .isEqualTo(AssistantConversationService.STATE_SUBMITTED);
        assertThat(asUser(bob.username(), () -> submit(bobConversation, "unit-b-turn", "B 的需求")).state())
            .isEqualTo(AssistantConversationService.STATE_SUBMITTED);

        awaitState(aliceConversation, "unit-a-turn", AssistantConversationService.STATE_COMPLETED);
        awaitState(bobConversation, "unit-b-turn", AssistantConversationService.STATE_COMPLETED);

        ExecutionUnitKey aliceKey = ExecutionUnitKey.of(alice.tenantId(), alice.projectId(), alice.actorId());
        ExecutionUnitKey bobKey = ExecutionUnitKey.of(bob.tenantId(), bob.projectId(), bob.actorId());
        ExecutionUnit aliceUnit = pool.unitFor(aliceKey).orElseThrow();
        ExecutionUnit bobUnit = pool.unitFor(bobKey).orElseThrow();
        assertThat(aliceUnit.home()).isNotEqualTo(bobUnit.home());

        String aliceEntry = entryAtSubmit(aliceUnit.home());
        String bobEntry = entryAtSubmit(bobUnit.home());
        String aliceLease = leaseIdOf(aliceEntry);
        String bobLease = leaseIdOf(bobEntry);

        assertThat(aliceLease).isNotEqualTo(bobLease);
        assertThat(aliceEntry).doesNotContain(bobLease);
        assertThat(bobEntry).doesNotContain(aliceLease);

        // 两份配置文件（含终态之后）都不得出现对方的 URL：隔离是文件层面的事实，不只是内存里的键。
        assertThat(ExecutionUnitHarness.read(aliceUnit.configFile())).doesNotContain(bobLease);
        assertThat(ExecutionUnitHarness.read(bobUnit.configFile())).doesNotContain(aliceLease);
        // 两个租约都只在自己的 generation 上有效（不是同一张能力的两个 id）。
        assertThat(registry.resolve(aliceLease).orElseThrow().actorId()).isEqualTo(alice.actorId());
        assertThat(registry.resolve(bobLease).orElseThrow().actorId()).isEqualTo(bob.actorId());
    }

    /**
     * <b>同一租户、同一项目、两个不同用户</b>也必须各自一个单元。
     *
     * <p>这条用例专门钉住键里的 {@code actorId}：只按 {@code (tenant, project)} 建单元时两个用户共用一个
     * 配置文件，先到的那一轮写进去的 URL 就会被后到的那一轮的会话读到（能力错配），而「两个租户」的用例
     * 完全不会变红。
     */
    @Test
    void twoActorsInTheSameProjectGetSeparateUnits() throws Exception {
        ConversationTurnFixture.Tenant carol = actorInSameProject("unit-carol", alice);
        String aliceConversation = create(alice, "unit-shared-alice-conv");
        String carolConversation = asUser(carol.username(), () -> service.create(Map.of(
            "requestId", "unit-shared-carol-conv", "projectId", alice.projectId(), "title", "carol")).conversationId());
        ConversationTurnFixture.seedTurn(jdbc, json, cipher, execution, alice, "unit-shared-alice");
        ConversationTurnFixture.seedTurn(jdbc, json, cipher, execution, carol, "unit-shared-carol");

        assertThat(asUser(alice.username(), () -> submit(aliceConversation, "unit-shared-alice", "A")).state())
            .isEqualTo(AssistantConversationService.STATE_SUBMITTED);
        assertThat(asUser(carol.username(), () -> submit(carolConversation, "unit-shared-carol", "C")).state())
            .isEqualTo(AssistantConversationService.STATE_SUBMITTED);
        awaitState(aliceConversation, "unit-shared-alice", AssistantConversationService.STATE_COMPLETED);
        awaitState(carolConversation, "unit-shared-carol", AssistantConversationService.STATE_COMPLETED);

        ExecutionUnitKey aliceKey = ExecutionUnitKey.of(alice.tenantId(), alice.projectId(), alice.actorId());
        ExecutionUnitKey carolKey = ExecutionUnitKey.of(carol.tenantId(), carol.projectId(), carol.actorId());
        assertThat(carolKey).isNotEqualTo(aliceKey);
        ExecutionUnit aliceUnit = pool.unitFor(aliceKey).orElseThrow();
        ExecutionUnit carolUnit = pool.unitFor(carolKey).orElseThrow();
        assertThat(carolUnit.home()).isNotEqualTo(aliceUnit.home());

        String aliceLease = leaseIdOf(entryAtSubmit(aliceUnit.home()));
        String carolLease = leaseIdOf(entryAtSubmit(carolUnit.home()));
        assertThat(aliceLease).isNotEqualTo(carolLease);
        assertThat(ExecutionUnitHarness.read(aliceUnit.configFile())).doesNotContain(carolLease);
        assertThat(ExecutionUnitHarness.read(carolUnit.configFile())).doesNotContain(aliceLease);
    }

    /** 同一个键的多轮复用同一个单元：不逐轮新建子进程、不换配置文件。 */
    @Test
    void theSameActorReusesOneUnitAcrossTurns() throws Exception {
        String first = create(alice, "unit-reuse-first");
        String second = create(alice, "unit-reuse-second");
        ConversationTurnFixture.seedTurn(jdbc, json, cipher, execution, alice, "unit-reuse-1");
        ConversationTurnFixture.seedTurn(jdbc, json, cipher, execution, alice, "unit-reuse-2");

        AssistantConversationService.SubmitReceipt firstReceipt =
            asUser(alice.username(), () -> submit(first, "unit-reuse-1", "第一轮"));
        assertThat(firstReceipt.state()).as("第一轮受理：%s", firstReceipt.detail())
            .isEqualTo(AssistantConversationService.STATE_SUBMITTED);
        awaitState(first, "unit-reuse-1", AssistantConversationService.STATE_COMPLETED);
        // 账本先落终态、收尾再放许可（两步之间有毫秒级窗口）。同一单元的第二轮若落进那个窗口，会合法地
        // 拿到 MCP_PERMIT_BUSY —— 那与单进程形状里的行为一致（客户端换 requestId 重试），这里等它释放。
        ExecutionUnitHarness.await("本单元的许可已释放", Duration.ofSeconds(10),
            () -> !pool.holdsPermit(firstReceipt.messageId()));
        AssistantConversationService.SubmitReceipt secondReceipt =
            asUser(alice.username(), () -> submit(second, "unit-reuse-2", "第二轮"));
        assertThat(secondReceipt.state()).as("第二轮受理：%s", secondReceipt.detail())
            .isEqualTo(AssistantConversationService.STATE_SUBMITTED);
        awaitState(second, "unit-reuse-2", AssistantConversationService.STATE_COMPLETED);

        ExecutionUnitKey key = ExecutionUnitKey.of(alice.tenantId(), alice.projectId(), alice.actorId());
        Path home = pool.unitFor(key).orElseThrow().home();
        List<String> snapshots = ExecutionUnitHarness.snapshots(home);
        assertThat(snapshots).hasSize(2);
        assertThat(snapshots.get(0)).contains("/mcp-lease/");
        assertThat(snapshots.get(1)).contains("/mcp-lease/");
        // 两轮的 lease id 不同（按轮铸、按轮撤销），但它们的宿主始终是同一个单元目录。
        assertThat(leaseIdOf(snapshots.get(0))).isNotEqualTo(leaseIdOf(snapshots.get(1)));
        assertThat(pool.keys()).filteredOn(key::equals).hasSize(1);
    }

    /** 终态收尾会在<b>本单元内</b>清掉条目：终态之后配置文件里不再挂着那一条 lease URL。 */
    @Test
    void theUnitClearsItsOwnEntryAtTheTerminalState() throws Exception {
        String conversation = create(alice, "unit-clear-conv");
        ConversationTurnFixture.seedTurn(jdbc, json, cipher, execution, alice, "unit-clear-turn");
        assertThat(asUser(alice.username(), () -> submit(conversation, "unit-clear-turn", "清除条目的一轮")).state())
            .isEqualTo(AssistantConversationService.STATE_SUBMITTED);
        awaitState(conversation, "unit-clear-turn", AssistantConversationService.STATE_COMPLETED);

        ExecutionUnitKey key = ExecutionUnitKey.of(alice.tenantId(), alice.projectId(), alice.actorId());
        Path config = pool.unitFor(key).orElseThrow().configFile();
        ExecutionUnitHarness.await("终态后本单元条目被清除", Duration.ofSeconds(10),
            () -> !ExecutionUnitHarness.read(config).contains("/mcp-lease/"));
        assertThat(ExecutionUnitHarness.read(config)).contains("mcp_servers");
    }

    /**
     * 单元不可用时本轮 <b>fail-closed</b>：池被占满（容量 1，另一个键已在册）→ 账本 {@code FAILED}、
     * 未派发、未建会话，而且<b>没有</b>回退到别人的单元（条目里不会出现本轮的 URL）。
     *
     * <p>用独立的池与服务实例：共享 bean 的池不能在这个用例里被关掉/占满。
     */
    @Test
    void aTurnFailsClosedWhenNoUnitCanServeItsKey() throws Exception {
        ExecutionUnitKey squattingKey = ExecutionUnitKey.of(alice.tenantId(), alice.projectId(), alice.actorId());
        ExecutionUnitSettings settings = new ExecutionUnitSettings(HARNESS.profile(), HARNESS.unitsRoot(),
            List.of("python3", HARNESS.script().toString()), 1, Duration.ofMinutes(10), Duration.ofSeconds(30),
            Duration.ofSeconds(10), Duration.ofSeconds(10), "haitlify", 64, Duration.ofSeconds(2), Map.of());
        ExecutionUnitPool bounded = new ExecutionUnitPool(settings, json);
        // 占住容量的那一轮已经结束（只留下「单元在册」这个事实），否则关闭时要白等一个租约宽限。
        bounded.acquire(squattingKey).lease().release();

        String conversation = create(bob, "unit-bounded-conv");
        ConversationTurnFixture.Turn turn =
            ConversationTurnFixture.seedTurn(jdbc, json, cipher, execution, bob, "unit-bounded-turn");
        AssistantConversationService local = new AssistantConversationService(repository, access, leases, "/tmp",
            Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(2), null, bounded, true);
        try {
            AssistantConversationService.SubmitReceipt receipt = asUser(bob.username(),
                () -> local.submit(conversation, Map.of("requestId", "unit-bounded-turn", "text", "池满的时候")));

            assertThat(receipt.state()).isEqualTo(AssistantConversationService.STATE_FAILED);
            assertThat(receipt.reconcileRequired()).as("一帧都没发出去：确定性失败").isFalse();
            assertThat(receipt.detail())
                .as("拒绝理由必须能对账（固定文案，含执行单元原因码）")
                .contains(AssistantConversationService.EXECUTION_UNIT_UNAVAILABLE_DETAIL);
            assertThat(local.mcpTrace(conversation, "unit-bounded-turn"))
                .isEqualTo(AssistantConversationService.MCP_TRACE_UNIT_UNAVAILABLE);
            assertThat(messageState(conversation, "unit-bounded-turn"))
                .isEqualTo(AssistantConversationService.STATE_FAILED);
            // 没有回退到既有单元：它的配置里不会有本轮租约，本轮的 generation 一张 grant 都没签。
            assertThat(bounded.liveUnits()).isEqualTo(1);
            assertThat(leases.endpointFor(conversation))
                .as("被拒绝的轮次连租约都不铸")
                .isEmpty();
            assertThat(jdbc.queryForObject("select count(*) from assistant_execution_grants where binding_id=?",
                Integer.class, turn.bindingId())).isZero();
        } finally {
            local.shutdown();
            bounded.close();
        }
    }

    /**
     * <b>跨用户不互相阻塞</b>（本轮改造的头号目标）：A 的一轮已经受理、仍持有它<b>自己单元</b>的许可
     * （桩被终态门挡住，终态还没到），此时 B 提交必须正常受理；修复前（全局一把许可）B 会拿到
     * {@code MCP_PERMIT_BUSY}/FAILED。
     *
     * <p>用独立的池（带终态门环境变量）与独立服务实例：共享 bean 的池不带这个门。
     */
    @Test
    void aSecondUserIsNotBlockedWhileTheFirstUsersPermitIsHeld() throws Exception {
        Path gate = HARNESS.root().resolve("terminal-gate");
        ExecutionUnitSettings settings = new ExecutionUnitSettings(HARNESS.profile(), HARNESS.unitsRoot(),
            List.of("python3", HARNESS.script().toString()), 8, Duration.ofMinutes(10), Duration.ofSeconds(30),
            Duration.ofSeconds(10), Duration.ofSeconds(10), "haitlify", 64, Duration.ofSeconds(2),
            Map.of(ExecutionUnitHarness.TERMINAL_GATE_ENV, gate.toString()));
        ExecutionUnitPool localPool = new ExecutionUnitPool(settings, json);
        AssistantConversationService local = new AssistantConversationService(repository, access, leases, "/tmp",
            Duration.ofSeconds(15), Duration.ofSeconds(5), Duration.ofSeconds(2), null, localPool, true);
        try {
            String aliceConversation = create(alice, "unit-parallel-alice-conv");
            String bobConversation = create(bob, "unit-parallel-bob-conv");
            ConversationTurnFixture.seedTurn(jdbc, json, cipher, execution, alice, "unit-parallel-alice");
            ConversationTurnFixture.seedTurn(jdbc, json, cipher, execution, bob, "unit-parallel-bob");

            AssistantConversationService.SubmitReceipt aliceReceipt = asUser(alice.username(),
                () -> local.submit(aliceConversation,
                    Map.of("requestId", "unit-parallel-alice", "text", "A 的一轮")));
            assertThat(aliceReceipt.state()).as("A 的轮次受理：%s", aliceReceipt.detail())
                .isEqualTo(AssistantConversationService.STATE_SUBMITTED);
            // A 的一轮已经派发、但还没有终态（桩被终态门挡住）——这正是「许可仍被持有」的窗口。
            Path aliceHome = localPool.unitFor(ExecutionUnitKey.of(alice.tenantId(), alice.projectId(),
                alice.actorId())).orElseThrow().home();
            ExecutionUnitHarness.await("A 的一轮已派发并停在终态门前", Duration.ofSeconds(10),
                () -> !ExecutionUnitHarness.snapshots(aliceHome).isEmpty());

            AssistantConversationService.SubmitReceipt bobReceipt = asUser(bob.username(),
                () -> local.submit(bobConversation,
                    Map.of("requestId", "unit-parallel-bob", "text", "B 的一轮")));
            assertThat(bobReceipt.state())
                .as("B 不得因为 A 持有<b>另一个单元</b>的许可而被拒（%s）", bobReceipt.detail())
                .isEqualTo(AssistantConversationService.STATE_SUBMITTED);
            assertThat(bobReceipt.detail()).isNull();

            Files.writeString(gate, "go");
            awaitState(aliceConversation, "unit-parallel-alice", AssistantConversationService.STATE_COMPLETED);
            awaitState(bobConversation, "unit-parallel-bob", AssistantConversationService.STATE_COMPLETED);
        } finally {
            local.shutdown();
            localPool.close();
        }
    }

    /**
     * <b>默认配置下（{@code configure-gateway=false}）在飞轮次不得被回收</b>——这是「在飞保护寄生于 MCP
     * 许可」那个缺陷的回归位。
     *
     * <p>形状刻意做到最不利：{@code idle-ttl=1ms}、回收节拍 20ms、本轮一次许可都没取（配置关闭），
     * 桩被终态门挡住（终态迟迟不来）。以许可为判据时，回收线程会把单元连同子进程一起摘掉——那一轮只能
     * 落 {@code UNKNOWN}。判据换成<b>单元在飞租约</b>之后，回收必须一只都不碰，直到这一轮真的结束。
     *
     * <p>同时钉住另外两件事：<b>终态后可被回收</b>，以及<b>跨单元并行不受影响</b>（B 的轮次不因 A 持着
     * 自己单元的租约而被拒）。
     */
    @Test
    void anInFlightTurnSurvivesReclaimWhenTheGatewayEntryIsNotConfigured() throws Exception {
        Path gate = HARNESS.root().resolve("lease-gate-" + UUID.randomUUID());
        ExecutionUnitPool localPool = localPool(8, Duration.ofMillis(1), Duration.ofMillis(20),
            Map.of(ExecutionUnitHarness.TERMINAL_GATE_ENV, gate.toString()));
        AssistantConversationService local = new AssistantConversationService(repository, access, leases, "/tmp",
            Duration.ofSeconds(20), Duration.ofSeconds(5), Duration.ofSeconds(2), null, localPool, false);
        try {
            String aliceConversation = create(alice, "unit-lease-alice-conv");
            ConversationTurnFixture.seedTurn(jdbc, json, cipher, execution, alice, "unit-lease-alice");
            AssistantConversationService.SubmitReceipt receipt = asUser(alice.username(),
                () -> local.submit(aliceConversation,
                    Map.of("requestId", "unit-lease-alice", "text", "在飞的一轮")));
            assertThat(receipt.state()).as("A 的轮次受理：%s", receipt.detail())
                .isEqualTo(AssistantConversationService.STATE_SUBMITTED);

            ExecutionUnitKey key = ExecutionUnitKey.of(alice.tenantId(), alice.projectId(), alice.actorId());
            ExecutionUnit unit = localPool.unitFor(key).orElseThrow();
            Path home = unit.home();
            ExecutionUnitHarness.await("A 的一轮已派发并停在终态门前", Duration.ofSeconds(10),
                () -> !ExecutionUnitHarness.snapshots(home).isEmpty());
            long pid = ExecutionUnitHarness.pidOf(home);
            assertThat(local.holdsGatewayMcpPermit(receipt.messageId()))
                .as("configure-gateway=false：本轮一次许可都没取，许可不能充当在飞标记")
                .isFalse();
            assertThat(unit.inFlightLeases()).as("在飞标记来自单元租约").isEqualTo(1);

            // 回收线程自己跑几十拍（TTL 只有 1ms）：在飞租约必须让它一个都不回收。
            Thread.sleep(600L);
            assertThat(localPool.liveUnits()).isEqualTo(1);
            assertThat(localPool.keys()).containsExactly(key);
            assertThat(unit.isClosed()).as("单元不得被回收线程关闭").isFalse();
            assertThat(Files.isDirectory(home)).as("单元目录不得被删除").isTrue();
            assertThat(ExecutionUnitHarness.alive(pid)).as("子进程不得被回收线程杀掉").isTrue();
            assertThat(messageState(aliceConversation, "unit-lease-alice"))
                .as("这一轮仍在等终态，不得被回收成 UNKNOWN")
                .isEqualTo(AssistantConversationService.STATE_SUBMITTED);

            // 跨单元并行不受影响：A 持着自己单元的租约，B 的轮次必须照常受理。
            String bobConversation = create(bob, "unit-lease-bob-conv");
            ConversationTurnFixture.seedTurn(jdbc, json, cipher, execution, bob, "unit-lease-bob");
            AssistantConversationService.SubmitReceipt bobReceipt = asUser(bob.username(),
                () -> local.submit(bobConversation, Map.of("requestId", "unit-lease-bob", "text", "B 的一轮")));
            assertThat(bobReceipt.state()).as("B 不得被 A 的租约挡住（%s）", bobReceipt.detail())
                .isEqualTo(AssistantConversationService.STATE_SUBMITTED);

            // 终态：各自归还租约，单元回到可回收（TTL 1ms + 20ms 节拍，很快就被删掉）。
            Files.writeString(gate, "go");
            awaitState(aliceConversation, "unit-lease-alice", AssistantConversationService.STATE_COMPLETED);
            awaitState(bobConversation, "unit-lease-bob", AssistantConversationService.STATE_COMPLETED);
            ExecutionUnitHarness.await("终态后本单元回到可回收", Duration.ofSeconds(15),
                () -> !Files.exists(home));
            assertThat(unit.inFlightLeases()).as("终态已归还租约").isZero();
            ExecutionUnitHarness.await("两个单元都被回收", Duration.ofSeconds(15),
                () -> localPool.liveUnits() == 0);
        } finally {
            local.shutdown();
            localPool.close();
        }
    }

    /** 终态<b>超时</b>（UNKNOWN）也必须归还租约：租约漏还，这个单元此后永远回收不掉。 */
    @Test
    void aTurnThatTimesOutReleasesItsUnitLeaseAndTheUnitBecomesReclaimable() throws Exception {
        Path gate = HARNESS.root().resolve("lease-timeout-gate-" + UUID.randomUUID());
        ExecutionUnitPool localPool = localPool(8, Duration.ofMillis(1), Duration.ofMillis(20),
            Map.of(ExecutionUnitHarness.TERMINAL_GATE_ENV, gate.toString()));
        AssistantConversationService local = new AssistantConversationService(repository, access, leases, "/tmp",
            Duration.ofMillis(500), Duration.ofSeconds(5), Duration.ofSeconds(2), null, localPool, false);
        try {
            String conversation = create(alice, "unit-lease-timeout-conv");
            ConversationTurnFixture.seedTurn(jdbc, json, cipher, execution, alice, "unit-lease-timeout");
            assertThat(asUser(alice.username(), () -> local.submit(conversation,
                Map.of("requestId", "unit-lease-timeout", "text", "会超时的一轮"))).state())
                .isEqualTo(AssistantConversationService.STATE_SUBMITTED);

            ExecutionUnitKey key = ExecutionUnitKey.of(alice.tenantId(), alice.projectId(), alice.actorId());
            ExecutionUnit unit = localPool.unitFor(key).orElseThrow();
            Path home = unit.home();
            ExecutionUnitHarness.await("终态超时落 UNKNOWN", Duration.ofSeconds(20),
                () -> AssistantConversationService.STATE_UNKNOWN
                    .equals(messageState(conversation, "unit-lease-timeout")));
            ExecutionUnitHarness.await("超时后归还在飞租约", Duration.ofSeconds(10),
                () -> unit.inFlightLeases() == 0);
            ExecutionUnitHarness.await("租约归还后单元可被回收", Duration.ofSeconds(15),
                () -> !Files.exists(home));
        } finally {
            local.shutdown();
            localPool.close();
        }
    }

    /** <b>派发失败</b>（{@code session.create} 回错）同样归还租约：失败不是「留着租约等下一次」的理由。 */
    @Test
    void aDispatchFailureReleasesItsUnitLeaseAndTheUnitBecomesReclaimable() throws Exception {
        ExecutionUnitPool localPool = localPool(8, Duration.ofMillis(1), Duration.ofMillis(20),
            Map.of(ExecutionUnitHarness.FAIL_CREATE_ENV, "1"));
        AssistantConversationService local = new AssistantConversationService(repository, access, leases, "/tmp",
            Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(2), null, localPool, false);
        try {
            String conversation = create(alice, "unit-lease-dispatch-conv");
            ConversationTurnFixture.seedTurn(jdbc, json, cipher, execution, alice, "unit-lease-dispatch");
            AssistantConversationService.SubmitReceipt receipt = asUser(alice.username(),
                () -> local.submit(conversation,
                    Map.of("requestId", "unit-lease-dispatch", "text", "派发会失败的一轮")));
            assertThat(receipt.state()).as("本地派发失败：结果不可判定（%s）", receipt.detail())
                .isEqualTo(AssistantConversationService.STATE_UNKNOWN);
            assertThat(receipt.reconcileRequired()).isTrue();

            ExecutionUnitKey key = ExecutionUnitKey.of(alice.tenantId(), alice.projectId(), alice.actorId());
            ExecutionUnit unit = localPool.unitFor(key).orElseThrow();
            Path home = unit.home();
            assertThat(unit.inFlightLeases()).as("派发失败后租约必须已归还").isZero();
            ExecutionUnitHarness.await("失败后单元可被回收", Duration.ofSeconds(15),
                () -> !Files.exists(home));
        } finally {
            local.shutdown();
            localPool.close();
        }
    }

    /**
     * <b>{@code Error} 出口</b>（受理窗口里抛出致命错误）也必须归还租约：{@code finally} 覆盖 {@code Error}，
     * 而不是靠逐路径清理。回复池在这里「受理即抛 {@link AssertionError}」——正是「已记账、已派发、还没
     * 交接给消费者」的那一刻。
     */
    @Test
    void aFatalErrorInTheAcceptanceWindowReleasesTheUnitLease() throws Exception {
        ExecutionUnitPool localPool = localPool(8, Duration.ofMinutes(10), Duration.ofSeconds(30), Map.of());
        ThreadPoolExecutor exploding = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>()) {
            @Override
            public void execute(Runnable task) {
                throw new AssertionError("受理窗口里的致命错误（用例构造）");
            }
        };
        AssistantConversationService local = new AssistantConversationService(repository, access, leases, "/tmp",
            Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(2), exploding, localPool, false);
        try {
            String conversation = create(alice, "unit-lease-error-conv");
            ConversationTurnFixture.seedTurn(jdbc, json, cipher, execution, alice, "unit-lease-error");
            assertThatThrownBy(() -> asUser(alice.username(), () -> local.submit(conversation,
                Map.of("requestId", "unit-lease-error", "text", "受理窗口会抛 Error 的一轮"))))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("致命错误");

            ExecutionUnitKey key = ExecutionUnitKey.of(alice.tenantId(), alice.projectId(), alice.actorId());
            ExecutionUnit unit = localPool.unitFor(key).orElseThrow();
            assertThat(unit.inFlightLeases()).as("Error 出口也必须归还租约（finally 覆盖 Error）").isZero();
            assertThat(localPool.reapIdle(Instant.now().plusSeconds(900)))
                .as("租约已归还：这个单元必须回到可回收")
                .containsExactly(key);
        } finally {
            implode(exploding);
            localPool.close();
        }
    }

    // ------------------------------------------------------------------ helpers

    /** 独立池：与共享 bean 的池参数无关（TTL / 节拍 / 子进程环境由用例自己定）。 */
    private ExecutionUnitPool localPool(int capacity, Duration idleTtl, Duration reapInterval,
                                        Map<String, String> environment) {
        ExecutionUnitSettings settings = new ExecutionUnitSettings(HARNESS.profile(), HARNESS.unitsRoot(),
            List.of("python3", HARNESS.script().toString()), capacity, idleTtl, reapInterval,
            Duration.ofSeconds(10), Duration.ofSeconds(10), "haitlify", 64, Duration.ofSeconds(2), environment);
        return new ExecutionUnitPool(settings, json);
    }

    /** 关掉一个「执行即抛」的回复池：{@code execute} 抛错，队列里不会有任务。 */
    private static void implode(ThreadPoolExecutor executor) {
        executor.shutdownNow();
    }

    private String create(ConversationTurnFixture.Tenant tenant, String requestId) throws Exception {
        return asUser(tenant.username(), () -> service.create(Map.of("requestId", requestId,
            "projectId", tenant.projectId(), "title", requestId)).conversationId());
    }

    private AssistantConversationService.SubmitReceipt submit(String conversationId, String requestId, String text)
        throws Exception {
        return service.submit(conversationId, Map.of("requestId", requestId, "text", text));
    }

    /** 在既有租户/项目下再造一个用户（ADMIN：{@code requireProject} 跳过成员表校验，项目归属不变）。 */
    private ConversationTurnFixture.Tenant actorInSameProject(String prefix,
                                                              ConversationTurnFixture.Tenant scope) {
        String username = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();
        jdbc.update("insert into platform_users(username,password_hash,role,enabled,created_at,updated_at)"
            + " values (?,'unused','ADMIN',true,?,?)", username, now, now);
        long actorId = jdbc.queryForObject("select id from platform_users where username=?", Long.class, username);
        return new ConversationTurnFixture.Tenant(username, actorId, scope.tenantId(), scope.projectId());
    }

    private <T> T asUser(String username, Callable<T> action) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            username, "unused", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        try {
            return action.call();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void awaitState(String conversationId, String requestId, String expected) {
        ExecutionUnitHarness.await("账本进入 " + expected, Duration.ofSeconds(20),
            () -> expected.equals(messageState(conversationId, requestId)));
    }

    private String messageState(String conversationId, String requestId) {
        return repository.findMessageByRequest(conversationId, requestId).map(AssistantConversationRepository.Message::state)
            .orElse(null);
    }

    /** 桩在 {@code prompt.submit} 时刻抄下来的配置内容（= 会话建立时网关读到的条目所在文件）。 */
    private static String entryAtSubmit(Path home) {
        List<String> snapshots = ExecutionUnitHarness.snapshots(home);
        assertThat(snapshots).as("单元 %s 必须真的派发过一轮", home.getFileName()).isNotEmpty();
        return snapshots.getFirst();
    }

    private static String leaseIdOf(String configText) {
        Matcher matcher = LEASE.matcher(configText);
        assertThat(matcher.find()).as("配置里必须有恰好一条 lease URL：%s", configText).isTrue();
        return matcher.group(1);
    }
}