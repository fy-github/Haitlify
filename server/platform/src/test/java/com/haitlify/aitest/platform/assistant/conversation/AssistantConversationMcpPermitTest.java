package com.haitlify.aitest.platform.assistant.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.haitlify.aitest.platform.assistant.execution.ExecutionBindingService;
import com.haitlify.aitest.platform.assistant.hermes.HermesGateway;
import com.haitlify.aitest.platform.assistant.mcp.McpLeaseRegistry;
import com.haitlify.aitest.platform.assistant.runtime.HermesConversationService;
import com.haitlify.aitest.platform.security.SecretCipher;

import tools.jackson.databind.ObjectMapper;

/**
 * <b>网关级单一 MCP 许可</b>的端到端行为：条目全局唯一，而且在会话创建的那一刻被<b>绑定</b>，所以
 * 「谁可以武装/配置/派发」不能按轮各自决定。
 *
 * <p>钉住五件事，缺一条都会把跨会话能力错配（或全局砖死）放回去：
 * <ol>
 *   <li><b>许可被占时本轮被显式拒绝</b>：不 arm、不 configure、<b>不派发</b>（因此不建立任何会话），
 *       账本落 {@code FAILED}、痕迹 {@code MCP_PERMIT_BUSY}。「以无工具继续派发」是假的降级——那个
 *       新建会话在创建时读到的是<b>持有者</b>的 URL；</li>
 *   <li><b>许可持有到本轮终态</b>：持有者还在等终态时，别人进不来（配置完成后就提前释放会在这里变红）；</li>
 *   <li><b>终态时先在许可内清掉网关条目，再释放许可</b>：清条目时另一个轮次仍被拒绝，条目在下一次
 *       提交之前就是 {@code null}（不留下一条指向已死租约的条目）；</li>
 *   <li><b>持有者的会话看到的必须是自己那一轮的租约</b>：唯一的写入就是持有者的 URL，非持有者一次都没写；</li>
 *   <li><b>终态之后释放</b>：超时（UNKNOWN）、派发失败（UNKNOWN）、受理窗口抛 {@code Error} 之后，
 *       另一个对话的轮次都必须能拿到自己的端点。不释放就是全局单点砖死。</li>
 * </ol>
 *
 * <p>直接用服务入口而不是 MockMvc：并发用例需要两个线程各自带登录主体（HTTP 状态码由
 * {@code AssistantConversationHttpTest} / {@code AssistantConversationMcpConfigureTest} 覆盖）。
 * 假 Hermes 仍是 loopback 上的脚本化 TCP 端点，控制面是 {@link RecordingGatewayMcpControl}，
 * 配置器是真实的 {@link GatewayMcpLeaseConfigurator}（装配只接受这一类实现）。
 * 许可原语本身的性质（只放自己的、幂等）由 {@link GatewayMcpPermitTest} 直接钉住。
 */
@SpringBootTest(properties = {
    "app.assistant.conversation.enabled=true",
    "app.assistant.conversation.mcp-lease.enabled=true",
    "app.assistant.conversation.mcp-lease.configure-gateway=true",
    "app.assistant.conversation.mcp-lease.public-base-url=https://assistant.internal",
    "app.assistant.conversation.mcp-lease.ttl-seconds=120",
    "app.assistant.mcp.lease.enabled=true",
    "app.assistant.mcp.lease.ttl-seconds=120",
    "app.assistant.hermes.transport=tcp",
    "app.assistant.hermes.timeout-seconds=5",
    "app.assistant.conversation.reply-timeout-seconds=3",
    "app.security.enabled=false",
    "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
    "spring.datasource.url=jdbc:h2:mem:assistant-conversation-mcp-permit;MODE=MySQL;DB_CLOSE_DELAY=-1;"
        + "DATABASE_TO_LOWER=TRUE"
})
@ActiveProfiles("test")
class AssistantConversationMcpPermitTest {

    private static final ConversationHermesStub STUB = ConversationHermesStub.start();
    private static final RecordingGatewayMcpControl CONTROL = new RecordingGatewayMcpControl();

    @TestConfiguration
    static class Wiring {
        @Bean
        GatewayMcpControl testGatewayMcpControl() {
            return CONTROL;
        }

        @Bean
        GatewayMcpConfigurator testGatewayMcpConfigurator(GatewayMcpControl control) {
            return new GatewayMcpLeaseConfigurator(control);
        }
    }

    @DynamicPropertySource
    static void hermesEndpoint(DynamicPropertyRegistry registry) {
        registry.add("app.assistant.hermes.host", () -> "127.0.0.1");
        registry.add("app.assistant.hermes.port", STUB::port);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired SecretCipher cipher;
    @Autowired ExecutionBindingService execution;
    @Autowired McpLeaseRegistry registry;
    @Autowired AssistantConversationLeaseProvisioner provisioner;
    @Autowired AssistantConversationService service;
    @Autowired AssistantConversationRepository repository;
    @Autowired AssistantConversationAccess access;
    @Autowired HermesGateway hermesGateway;

    private ConversationTurnFixture.Tenant tenant;
    /**
     * 本用例真正受理（202 语义）过的轮次。网关级许可在同一个 Spring 上下文里跨用例共享，用例结束时
     * 必须等它被放掉，否则下一个用例的首个提交会合法地拿到 {@code MCP_PERMIT_BUSY}（假红）。
     */
    private final List<String[]> startedTurns = new ArrayList<>();

    @BeforeEach
    void seed() {
        STUB.reset();
        CONTROL.reset();
        tenant = ConversationTurnFixture.seedTenant(jdbc, "mcp-permit");
    }

    @AfterEach
    void releaseEveryTurnThisTestStarted() throws Exception {
        for (String[] started : startedTurns) {
            awaitPermitReleased(started[0], started[1]);
        }
        startedTurns.clear();
    }

    // ------------------------------------- 1. 许可被占 → 显式拒绝（不是「无工具运行」）

    /**
     * 修复前会「无工具继续跑」的那个交错：「A 已配置、尚未派发」的窗口里插入一整轮 B。
     *
     * <p>现在 B 必须被<b>显式拒绝</b>，理由不是保守而是事实：条目在会话创建时被绑定，所以 B 的
     * {@code prompt.submit} 一旦到达网关，它的会话读到的就是 A 的 URL（能力错配）。因此这里钉住
     * 「一帧都没发出去」：{@code STUB.submits()} 仍是 1、条目里只有 A 的 URL、B 从未武装。
     */
    @Test
    void aTurnSubmittedWhileThePermitIsHeldIsRefusedAndNeverReachesTheGateway() throws Exception {
        ConversationTurnFixture.Turn turnA =
            ConversationTurnFixture.seedTurn(jdbc, json, cipher, execution, tenant, "permit-a");
        ConversationTurnFixture.seedTurn(jdbc, json, cipher, execution, tenant, "permit-b");
        String conversationA = newConversation("permit-conv-a");
        String conversationB = newConversationWithDistinctSubmitLock(conversationA, "permit-conv-b");

        // prompt.submit 时刻网关条目里是什么：这就是「会话建立时绑定到的 URL」的代理观测点。
        List<String> entryAtSubmit = Collections.synchronizedList(new ArrayList<>());
        STUB.onPromptSubmit(() -> entryAtSubmit.add(CONTROL.currentEntry()));

        AtomicReference<AssistantConversationService.SubmitReceipt> bReceipt = new AtomicReference<>();
        AtomicReference<Throwable> bFailure = new AtomicReference<>();
        AtomicReference<Integer> bWrites = new AtomicReference<>();

        // A 写条目之后、reload/派发之前：B 的整轮在这个窗口里尝试提交（正是修复前会拿到 A 的租约的位置）。
        CONTROL.onNextWrite(() -> {
            try {
                bReceipt.set(asUser(tenant.username(), () -> submitting(conversationB,
                    message("permit-b", "B 这一轮，许可在 A 手里"))));
                bWrites.set(CONTROL.writeCount());
            } catch (Throwable failure) {
                bFailure.set(failure);
            }
        });

        AssistantConversationService.SubmitReceipt receiptA = asUser(tenant.username(),
            () -> submitting(conversationA, message("permit-a", "A 这一轮，持有许可")));

        assertThat(bFailure.get()).as("许可被占是显式失败，不是异常").isNull();
        assertThat(bReceipt.get().state())
            .as("非持有者必须被拒绝，而不是以「无工具」被受理")
            .isEqualTo(AssistantConversationService.STATE_FAILED);
        assertThat(bReceipt.get().reconcileRequired())
            .as("一帧都没发出去：这是已知失败，不需要对账推断")
            .isFalse();
        assertThat(bReceipt.get().detail())
            .as("拒绝理由要能对账（固定文案，含 MCP_PERMIT_BUSY）")
            .contains(AssistantConversationService.MCP_TRACE_PERMIT_BUSY);
        assertThat(receiptA.state()).isEqualTo(AssistantConversationService.STATE_SUBMITTED);

        assertThat(STUB.submits()).as("只有 A 真的派发了：B 从未建会话、也就没人读到 A 的 URL").isEqualTo(1);
        assertThat(bWrites.get()).as("B 一次都不许写条目（含 apply(null)）").isEqualTo(1);

        String endpointA = provisioner.endpointFor(conversationA).orElseThrow();
        assertThat(CONTROL.publishedUrls()).as("唯一的写入就是持有者自己的 URL").containsExactly(endpointA);
        assertThat(registry.resolve(leaseIdOf(endpointA)).orElseThrow().generationId())
            .as("唯一发布的 URL 属于 A 那一轮")
            .isEqualTo(turnA.generationId());
        assertThat(provisioner.endpointFor(conversationB))
            .as("B 从未武装：被拒的轮次连租约都不铸")
            .isEmpty();
        assertThat(service.mcpTrace(conversationB, "permit-b"))
            .as("非持有者留可对账痕迹")
            .isEqualTo(AssistantConversationService.MCP_TRACE_PERMIT_BUSY);
        assertThat(messageState(conversationB, "permit-b"))
            .as("账本：被拒的轮次落 FAILED（确定性失败）")
            .isEqualTo(AssistantConversationService.STATE_FAILED);

        // 采样顺序固定：只有 A 派发过，所以只有一条采样，而且必须是 A 自己的 URL。
        assertThat(entryAtSubmit).as("只有持有者的会话被建立").hasSize(1);
        assertThat(entryAtSubmit.getFirst())
            .as("A 的会话在建立时读到的必须是 A 自己的租约")
            .isEqualTo(endpointA);

        // 许可在终态之后释放：A 那一轮已经 COMPLETED，B 的<b>新</b>一轮现在可以拿到自己的端点。
        awaitState(conversationA, "permit-a", AssistantConversationService.STATE_COMPLETED);
        awaitTerminalSettled(conversationA, "permit-a");
        ConversationTurnFixture.Turn turnB2 =
            ConversationTurnFixture.seedTurn(jdbc, json, cipher, execution, tenant, "permit-b2");
        assertThat(asUser(tenant.username(), () -> submitting(conversationB,
            message("permit-b2", "B 的第二轮，这时许可已经释放"))).state())
            .isEqualTo(AssistantConversationService.STATE_SUBMITTED);
        assertThat(service.mcpTrace(conversationB, "permit-b2"))
            .isEqualTo(AssistantConversationService.MCP_TRACE_CONFIGURED);
        assertThat(CONTROL.publishedUrls()).as("释放之后 B 才发布了第二条").hasSize(2);
        assertThat(registry.resolve(leaseIdOf(CONTROL.publishedUrls().get(1))).orElseThrow().generationId())
            .as("第二条 URL 属于 B 的第二轮：许可按轮释放，不是永久锁")
            .isEqualTo(turnB2.generationId());
    }

    // ------------------------------------- 2. 许可必须持到终态

    /**
     * <b>许可覆盖到本轮终态</b>：持有者已经配置完、还在等终态时，别的对话必须仍然被拒绝。
     *
     * <p>这条用例就是「配置之后就把许可放掉」的探针：那样 B 会被正常受理并派发（账本 SUBMITTED、
     * 痕迹 {@code MCP_CONFIGURED}、{@code STUB.submits()} 变 2），本用例的三处断言同时变红。
     * 为什么不能提前放：条目在本轮会话<b>创建时</b>被绑定，而会话的创建发生在派发那一刻；终态之前的任何
     * 改写都可能落到本轮的在建会话上。
     */
    @Test
    void thePermitIsHeldUntilTheTurnReachesATerminalState() throws Exception {
        STUB.mode(ConversationHermesStub.Mode.HANG);
        ConversationTurnFixture.seedTurn(jdbc, json, cipher, execution, tenant, "permit-hold-a");
        ConversationTurnFixture.seedTurn(jdbc, json, cipher, execution, tenant, "permit-hold-b");
        String conversationA = newConversation("permit-hold-conv-a");
        String conversationB = newConversation("permit-hold-conv-b");

        assertThat(asUser(tenant.username(), () -> submitting(conversationA,
            message("permit-hold-a", "持有许可、等着超时的一轮"))).state())
            .isEqualTo(AssistantConversationService.STATE_SUBMITTED);
        assertThat(STUB.submits()).isEqualTo(1);
        String endpointA = provisioner.endpointFor(conversationA).orElseThrow();

        // 这一刻 A 已经配置完、还在等终态（HANG → 3s 后超时）。
        AssistantConversationService.SubmitReceipt receiptB = asUser(tenant.username(),
            () -> submitting(conversationB, message("permit-hold-b", "许可还被 A 持有时的一轮")));

        assertThat(receiptB.state())
            .as("持有者仍在等终态：别人的轮次不得被受理")
            .isEqualTo(AssistantConversationService.STATE_FAILED);
        assertThat(service.mcpTrace(conversationB, "permit-hold-b"))
            .as("被拒的轮次留 PERMIT_BUSY，而不是留下一条「已配置」的假象")
            .isEqualTo(AssistantConversationService.MCP_TRACE_PERMIT_BUSY);
        assertThat(STUB.submits()).as("被拒的轮次不得派发（因而不建会话）").isEqualTo(1);
        assertThat(CONTROL.publishedUrls()).as("条目仍只有持有者那一条").containsExactly(endpointA);
        assertThat(CONTROL.currentEntry()).isEqualTo(endpointA);
        assertThat(provisioner.endpointFor(conversationB)).as("被拒的轮次不得武装").isEmpty();

        // 终态（超时 → UNKNOWN）之后才放开许可，而且条目必须先被清掉。
        awaitState(conversationA, "permit-hold-a", AssistantConversationService.STATE_UNKNOWN);
        awaitTerminalSettled(conversationA, "permit-hold-a");

        ConversationTurnFixture.Turn turnAfter =
            ConversationTurnFixture.seedTurn(jdbc, json, cipher, execution, tenant, "permit-hold-c");
        assertThat(asUser(tenant.username(), () -> submitting(conversationB,
            message("permit-hold-c", "终态之后的一轮"))).state())
            .isEqualTo(AssistantConversationService.STATE_SUBMITTED);
        assertThat(service.mcpTrace(conversationB, "permit-hold-c"))
            .as("超时释放了许可：新一轮拿到自己的端点")
            .isEqualTo(AssistantConversationService.MCP_TRACE_CONFIGURED);
        assertThat(registry.resolve(leaseIdOf(CONTROL.publishedUrls().getLast())).orElseThrow().generationId())
            .isEqualTo(turnAfter.generationId());
    }

    // ------------------------------------- 3. 终态：先清条目，再放许可

    /**
     * <b>终态的收尾顺序</b>：在<b>仍持有许可</b>时把网关条目清成 {@code null}，然后才释放许可。
     *
     * <p>观察点放在「清除那一次写入」的回调里：那一刻另起一轮提交——如果许可已经放掉（先放后清），
     * 它会顺利被受理并写下自己的 URL；正确实现下它必须仍然被拒绝。用例同时钉住「终态之后条目是
     * {@code null}」，否则条目会继续指向一条已死租约，直到下一轮或 TTL。
     */
    @Test
    void theGatewayEntryIsClearedWhileThePermitIsStillHeldAndOnlyThenIsThePermitReleased() throws Exception {
        ConversationTurnFixture.seedTurn(jdbc, json, cipher, execution, tenant, "permit-order-a");
        ConversationTurnFixture.seedTurn(jdbc, json, cipher, execution, tenant, "permit-order-b");
        String conversationA = newConversation("permit-order-conv-a");
        String conversationB = newConversationWithDistinctSubmitLock(conversationA, "permit-order-conv-b");

        AtomicReference<String> entryDuringTerminalClear = new AtomicReference<>("hook-never-ran");
        AtomicReference<AssistantConversationService.SubmitReceipt> competing = new AtomicReference<>();
        AtomicReference<Throwable> competingFailure = new AtomicReference<>();
        CONTROL.onNextClear(() -> {
            entryDuringTerminalClear.set(CONTROL.currentEntry());
            try {
                competing.set(asUser(tenant.username(), () -> submitting(conversationB,
                    message("permit-order-b", "清条目那一刻的另一轮"))));
            } catch (Throwable failure) {
                competingFailure.set(failure);
            }
        });

        assertThat(asUser(tenant.username(), () -> submitting(conversationA,
            message("permit-order-a", "这一轮会正常终结"))).state())
            .isEqualTo(AssistantConversationService.STATE_SUBMITTED);

        awaitState(conversationA, "permit-order-a", AssistantConversationService.STATE_COMPLETED);
        awaitEntryCleared();

        assertThat(entryDuringTerminalClear.get())
            .as("终态必须清一次网关条目（不清就是漏掉那一步，条目会继续指向已死租约）")
            .isNull();
        assertThat(competingFailure.get()).isNull();
        assertThat(competing.get()).as("清条目那一刻必须还在许可窗口内").isNotNull();
        assertThat(competing.get().state())
            .as("先清条目、后放许可：清条目时另一轮仍被拒绝；先放许可再清会在这里变成 SUBMITTED")
            .isEqualTo(AssistantConversationService.STATE_FAILED);
        assertThat(service.mcpTrace(conversationB, "permit-order-b"))
            .isEqualTo(AssistantConversationService.MCP_TRACE_PERMIT_BUSY);
        assertThat(STUB.submits()).as("清条目窗口里的那一轮也不得派发").isEqualTo(1);

        assertThat(CONTROL.writes()).as("本条链路只有两次写入：本轮 URL + 终态清除").hasSize(2);
        assertThat(CONTROL.writes().get(1)).as("第二次写入必须是清除").isNull();
        assertThat(CONTROL.currentEntry()).as("终态之后条目不再指向本轮的租约").isNull();
        assertThat(CONTROL.publishedUrls()).as("只有本轮那一条 URL 被发布过").hasSize(1);

        // 顺序的第二半：清除完成之后许可确实放开了——下一轮能拿到自己的端点。
        awaitPermitReleased(conversationA, "permit-order-a");
        ConversationTurnFixture.Turn turnAfter =
            ConversationTurnFixture.seedTurn(jdbc, json, cipher, execution, tenant, "permit-order-c");
        assertThat(asUser(tenant.username(), () -> submitting(conversationB,
            message("permit-order-c", "终态之后的一轮"))).state())
            .isEqualTo(AssistantConversationService.STATE_SUBMITTED);
        assertThat(service.mcpTrace(conversationB, "permit-order-c"))
            .isEqualTo(AssistantConversationService.MCP_TRACE_CONFIGURED);
        assertThat(registry.resolve(leaseIdOf(CONTROL.publishedUrls().getLast())).orElseThrow().generationId())
            .isEqualTo(turnAfter.generationId());
    }

    // ------------------------------------- 4. 许可在超时后释放

    /** 超时（HANG → UNKNOWN）也必须释放：否则整个网关的 MCP 从此只剩一个永远占用者。 */
    @Test
    void thePermitIsReleasedAfterATerminalTimeout() throws Exception {
        STUB.mode(ConversationHermesStub.Mode.HANG);
        ConversationTurnFixture.Turn first =
            ConversationTurnFixture.seedTurn(jdbc, json, cipher, execution, tenant, "permit-timeout");
        String conversation = newConversation("permit-conv-timeout");

        assertThat(asUser(tenant.username(), () -> submitting(conversation,
            message("permit-timeout", "这一轮不会终结"))).state())
            .isEqualTo(AssistantConversationService.STATE_SUBMITTED);
        awaitState(conversation, "permit-timeout", AssistantConversationService.STATE_UNKNOWN);
        awaitTerminalSettled(conversation, "permit-timeout");
        assertThat(registry.resolve(leaseIdOf(CONTROL.publishedUrls().getFirst())).orElseThrow().generationId())
            .isEqualTo(first.generationId());

        STUB.mode(ConversationHermesStub.Mode.NORMAL);
        ConversationTurnFixture.Turn second =
            ConversationTurnFixture.seedTurn(jdbc, json, cipher, execution, tenant, "permit-after-timeout");
        String next = newConversation("permit-conv-after-timeout");
        assertThat(asUser(tenant.username(), () -> submitting(next,
            message("permit-after-timeout", "超时之后的新一轮"))).state())
            .isEqualTo(AssistantConversationService.STATE_SUBMITTED);

        assertThat(service.mcpTrace(next, "permit-after-timeout"))
            .as("超时释放了许可：新一轮拿到的是自己的端点，而不是 PERMIT_BUSY")
            .isEqualTo(AssistantConversationService.MCP_TRACE_CONFIGURED);
        assertThat(registry.resolve(leaseIdOf(CONTROL.publishedUrls().get(1))).orElseThrow().generationId())
            .isEqualTo(second.generationId());
    }

    // ------------------------------------- 5. 许可在派发失败后释放

    /** 派发失败（queued 不是受理）走的是同步窗口的 finally：许可同样必须放掉。 */
    @Test
    void thePermitIsReleasedAfterAFailedDispatch() throws Exception {
        STUB.mode(ConversationHermesStub.Mode.QUEUED);
        ConversationTurnFixture.seedTurn(jdbc, json, cipher, execution, tenant, "permit-unqueued");
        String conversation = newConversation("permit-conv-queued");

        assertThat(asUser(tenant.username(), () -> submitting(conversation,
            message("permit-unqueued", "这一轮会被排队"))).state())
            .isEqualTo(AssistantConversationService.STATE_UNKNOWN);
        assertThat(CONTROL.writeCount()).as("先武装后派发：失败前已经配置过一次").isEqualTo(1);

        STUB.mode(ConversationHermesStub.Mode.NORMAL);
        ConversationTurnFixture.Turn second =
            ConversationTurnFixture.seedTurn(jdbc, json, cipher, execution, tenant, "permit-after-failure");
        String next = newConversation("permit-conv-after-failure");
        assertThat(asUser(tenant.username(), () -> submitting(next,
            message("permit-after-failure", "失败之后的新一轮"))).state())
            .isEqualTo(AssistantConversationService.STATE_SUBMITTED);

        assertThat(service.mcpTrace(next, "permit-after-failure"))
            .as("派发失败释放了许可")
            .isEqualTo(AssistantConversationService.MCP_TRACE_CONFIGURED);
        assertThat(registry.resolve(leaseIdOf(CONTROL.publishedUrls().get(1))).orElseThrow().generationId())
            .isEqualTo(second.generationId());
    }

    // ------------------------------------- 6. 许可在 Error 之后释放

    /**
     * 受理窗口里的致命 {@code Error}（写网关配置时 OOM 一类）不是 {@link RuntimeException}，
     * 走的是 {@code submitAccepted} 的 finally：许可必须跟着放，否则这个进程的 MCP 永远停摆。
     */
    @Test
    void thePermitIsReleasedWhenTheConfigureStepThrowsAnError() throws Exception {
        ConversationTurnFixture.seedTurn(jdbc, json, cipher, execution, tenant, "permit-error");
        String conversation = newConversation("permit-conv-error");
        CONTROL.failWithErrorOnWrite(true);

        Throwable failure = catchThrowable(() -> asUser(tenant.username(), () -> submitting(conversation,
            message("permit-error", "写配置时抛 Error 的一轮"))));
        assertThat(failure).as("Error 必须冒出去（不吞、不伪装成成功）").isNotNull();
        assertThat(causeChain(failure)).contains(RecordingGatewayMcpControl.WriteError.class);
        assertThat(STUB.submits()).as("配置阶段就死了：一轮都没派发").isZero();

        CONTROL.failWithErrorOnWrite(false);
        ConversationTurnFixture.Turn second =
            ConversationTurnFixture.seedTurn(jdbc, json, cipher, execution, tenant, "permit-after-error");
        String next = newConversation("permit-conv-after-error");
        assertThat(asUser(tenant.username(), () -> submitting(next,
            message("permit-after-error", "Error 之后的新一轮"))).state())
            .isEqualTo(AssistantConversationService.STATE_SUBMITTED);

        assertThat(service.mcpTrace(next, "permit-after-error"))
            .as("Error 释放在 finally 里：新一轮能拿到自己的端点")
            .isEqualTo(AssistantConversationService.MCP_TRACE_CONFIGURED);
        assertThat(registry.resolve(leaseIdOf(CONTROL.publishedUrls().getLast())).orElseThrow().generationId())
            .isEqualTo(second.generationId());
    }

    // ------------------------------------- 7. 终态清条目抛 Error 时释放许可

    /**
     * <b>终态清条目抛 {@code Error} 也必须释放许可</b>（OOM / 栈溢出一类，不是 {@link RuntimeException}）。
     *
     * <p>释放点若只是「清条目之后的下一句」（而不是 {@code finally}），{@code Error} 会把它整段跳过：
     * 这一轮账本已经 COMPLETED，可全局许可却永久留在它名下——此后<b>所有对话</b>的提交都是
     * {@code MCP_PERMIT_BUSY}/503，直到进程重启。这正是类注释里「终态释放」必须覆盖
     * {@code Error}/{@code Throwable} 出口的原因（javadoc 已写明，这里把它钉住）。
     *
     * <p>三条断言各钉一件事：① 许可确实被释放（否则下一轮仍被拒）；② 上抛的 {@code Error} 不得把已经
     * 落库的完成态改写成失败（账本先落终态、收尾再抛，两者互不影响）；③ 清条目这一步真的被尝试过
     * （{@code writes} 的第二条是 {@code null}）——不是靠「跳过清条目」把许可换来。
     */
    @Test
    void thePermitIsReleasedWhenTheTerminalClearThrowsAnError() throws Exception {
        ConversationTurnFixture.seedTurn(jdbc, json, cipher, execution, tenant, "permit-clear-error");
        ConversationTurnFixture.seedTurn(jdbc, json, cipher, execution, tenant, "permit-after-clear-error");
        String conversation = newConversation("permit-clear-error-conv");
        String next = newConversation("permit-after-clear-error-conv");

        // 配置（第一条写入）照常成功，只让终态那一次清除抛 Error。
        CONTROL.failWithErrorOnClear(true);

        assertThat(asUser(tenant.username(), () -> submitting(conversation,
            message("permit-clear-error", "清条目时会抛 Error 的一轮"))).state())
            .isEqualTo(AssistantConversationService.STATE_SUBMITTED);

        awaitState(conversation, "permit-clear-error", AssistantConversationService.STATE_COMPLETED);
        // 修复前：Error 跳过了释放，这里 5s 后仍是 true —— 全局砖死的探针。
        awaitPermitReleased(conversation, "permit-clear-error");

        assertThat(CONTROL.writes()).as("本轮 URL + 终态清除：清除那一次确实被尝试过").hasSize(2);
        assertThat(CONTROL.writes().get(1))
            .as("不得靠「不调 apply(null)」来换一条活路")
            .isNull();

        CONTROL.failWithErrorOnClear(false);
        assertThat(messageState(conversation, "permit-clear-error"))
            .as("终态先落库：上抛的 Error 不得把完成态改写成失败")
            .isEqualTo(AssistantConversationService.STATE_COMPLETED);

        assertThat(asUser(tenant.username(), () -> submitting(next,
            message("permit-after-clear-error", "清条目抛 Error 之后的新一轮"))).state())
            .as("许可已经释放：别的对话能正常受理，而不是一路 503 到重启")
            .isEqualTo(AssistantConversationService.STATE_SUBMITTED);
        assertThat(service.mcpTrace(next, "permit-after-clear-error"))
            .isEqualTo(AssistantConversationService.MCP_TRACE_CONFIGURED);
    }

    // ------------------------------------- 8. 关闭清扫：先清条目，再放许可

    /**
     * <b>关闭清扫的顺序</b>：把仍在等终态的轮次落成 UNKNOWN 之后，必须在<b>仍持有许可</b>时用
     * {@code apply(null)} 清掉网关条目，然后才释放许可。
     *
     * <p>这条用例补的是「清扫调用点没有咬人测试」：一个「只释放、不清条目」的变体不会让任何旧用例变红
     * （许可照样归还、别的轮次照样能提交），于是「关闭后条目仍指向一条随进程消失的租约」这条缺陷可以
     * 一路绿灯。这里同时钉住顺序（清条目那一刻许可仍在本轮手里）与结果（条目为 {@code null}、许可已归还、
     * 账本先落 UNKNOWN）。
     *
     * <p>用<b>独立实例</b>而不是共享的 Spring bean：{@code shutdown()} 会关掉回复池。它的回复池受理但
     * 永不执行任务——正是「本轮已受理、消费者还没取终态」的关闭时刻，这样清扫就是唯一可能的收尾路径；
     * 否则 {@code awaitReply} 的 finally 会替清扫把条目清掉，用例就测不到清扫本身。
     */
    @Test
    void theShutdownSweepClearsTheGatewayEntryBeforeReleasingThePermit() throws Exception {
        ConversationTurnFixture.seedTurn(jdbc, json, cipher, execution, tenant, "permit-shutdown");
        String conversation = newConversation("permit-shutdown-conv");

        ThreadPoolExecutor neverRuns = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>()) {
            @Override
            public void execute(Runnable task) {
                // 只「受理」不执行：把这一轮固定在「已受理、消费者还没跑」的关闭时刻。
            }
        };
        AssistantConversationService dedicated = new AssistantConversationService(repository, access,
            new HermesConversationService(hermesGateway, json, 32, Duration.ofSeconds(10)), hermesGateway,
            "/tmp/assistant-shutdown", Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofSeconds(10),
            neverRuns, provisioner, new GatewayMcpLeaseConfigurator(CONTROL));
        try {
            assertThat(asUser(tenant.username(), () -> dedicated.submit(conversation,
                message("permit-shutdown", "关闭时还在等终态的一轮"))).state())
                .isEqualTo(AssistantConversationService.STATE_SUBMITTED);
            String messageId = userMessageId(conversation, "permit-shutdown");
            String endpoint = provisioner.endpointFor(conversation).orElseThrow();
            assertThat(dedicated.holdsGatewayMcpPermit(messageId)).as("受理之后许可在本轮手里").isTrue();
            assertThat(CONTROL.writes()).containsExactly(endpoint);

            AtomicBoolean permitHeldDuringClear = new AtomicBoolean();
            AtomicReference<String> stateDuringClear = new AtomicReference<>();
            CONTROL.onNextClear(() -> {
                permitHeldDuringClear.set(dedicated.holdsGatewayMcpPermit(messageId));
                stateDuringClear.set(messageState(conversation, "permit-shutdown"));
            });

            dedicated.shutdown();

            assertThat(stateDuringClear.get())
                .as("清扫必须真的调 apply(null)（只释放不清条目会让这里停在 null）")
                .isNotNull();
            assertThat(permitHeldDuringClear.get())
                .as("清条目那一刻许可必须仍在本轮手里：先放再清，下一轮会读到空条目")
                .isTrue();
            assertThat(stateDuringClear.get())
                .as("顺序：账本先落 UNKNOWN，再清条目")
                .isEqualTo(AssistantConversationService.STATE_UNKNOWN);
            assertThat(CONTROL.writes()).as("两次写入：本轮 URL + 关闭时的清除").containsExactly(endpoint, null);
            assertThat(CONTROL.currentEntry()).as("关闭之后条目不得继续指向一条随进程消失的租约").isNull();
            assertThat(dedicated.holdsGatewayMcpPermit(messageId)).as("清理完成之后许可必须归还").isFalse();
            assertThat(messageState(conversation, "permit-shutdown"))
                .as("关闭也是终态：这一行落 UNKNOWN（可对账），不是让它留在 SUBMITTED")
                .isEqualTo(AssistantConversationService.STATE_UNKNOWN);
        } finally {
            neverRuns.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- helpers

    private static List<Class<?>> causeChain(Throwable failure) {
        List<Class<?>> types = new ArrayList<>();
        for (Throwable cursor = failure; cursor != null; cursor = cursor.getCause()) {
            types.add(cursor.getClass());
            if (cursor.getCause() == cursor) {
                break;
            }
        }
        return types;
    }

    private static Map<String, Object> message(String requestId, String text) {
        return Map.of("requestId", requestId, "text", text);
    }

    /** 服务入口 + 许可收尾登记：受理成功的轮次必须在用例结束时等到它放出许可。 */
    private AssistantConversationService.SubmitReceipt submitting(String conversationId, Map<String, Object> payload) {
        AssistantConversationService.SubmitReceipt receipt =
            asUser(tenant.username(), () -> service.submit(conversationId, payload));
        if (AssistantConversationService.STATE_SUBMITTED.equals(receipt.state())) {
            startedTurns.add(new String[] {conversationId, String.valueOf(payload.get("requestId"))});
        }
        return receipt;
    }

    private String newConversation(String requestId) {
        return asUser(tenant.username(), () -> service.create(Map.of("requestId", requestId,
            "projectId", tenant.projectId(), "title", "permit")).conversationId());
    }

    /**
     * 提交锁是 64 段按会话哈希的：两个并发用例若落在同一段，B 会等在 A 的锁上，那个「A 已配置、
     * 尚未派发」的窗口就造不出来（测试会变成假绿）。所以显式再建一个落在不同段的会话。
     */
    private String newConversationWithDistinctSubmitLock(String otherConversationId, String prefix) {
        for (int attempt = 0; attempt < 64; attempt++) {
            String candidate = newConversation(prefix + "-" + UUID.randomUUID().toString().substring(0, 8));
            if (Math.floorMod(candidate.hashCode(), 64) != Math.floorMod(otherConversationId.hashCode(), 64)) {
                return candidate;
            }
        }
        throw new IllegalStateException("64 个会话都落在同一段提交锁上：测试前提不成立");
    }

    /** 账本里这一轮（用户消息）的 id；用来对许可做只读观察。 */
    private String userMessageId(String conversationId, String requestId) {
        return jdbc.queryForObject("select id from assistant_conversation_messages"
            + " where conversation_id=? and request_id=? and role='USER'", String.class, conversationId, requestId);
    }

    private String messageState(String conversationId, String requestId) {
        return jdbc.queryForObject("select state from assistant_conversation_messages"
            + " where conversation_id=? and request_id=? and role='USER'", String.class, conversationId, requestId);
    }

    /**
     * 终态收尾的两个证据：网关条目已被清除、网关级许可已释放。账本落终态发生在它们之前，所以读到终态
     * 不等于收尾完成；这里等两个证据都到位，避免「账本说结束了、条目/许可还没收」的假红。
     *
     * <p>两者的<b>先后</b>由 {@code theGatewayEntryIsClearedWhileThePermitIsStillHeldAndOnlyThenIsThePermitReleased}
     * 钉住，这里只要求最终都成立。
     */
    private void awaitTerminalSettled(String conversationId, String requestId) throws Exception {
        String messageId = userMessageId(conversationId, requestId);
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline
                && (service.holdsGatewayMcpPermit(messageId) || CONTROL.currentEntry() != null)) {
            Thread.sleep(10);
        }
        assertThat(service.holdsGatewayMcpPermit(messageId)).as("终态之后许可必须已经释放").isFalse();
        assertThat(CONTROL.currentEntry()).as("终态之后网关条目必须被清除").isNull();
    }

    /** 只等条目被清除（顺序用例用它先把「清除已发生」固定下来）。 */
    private void awaitEntryCleared() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline && CONTROL.currentEntry() != null) {
            Thread.sleep(10);
        }
        assertThat(CONTROL.currentEntry()).as("终态之后网关条目必须被清除").isNull();
    }

    /** 只等许可被释放（释放紧跟在清条目之后；再提交之前必须等到它，否则会合法地拿到一次 503）。 */
    private void awaitPermitReleased(String conversationId, String requestId) throws Exception {
        String messageId = userMessageId(conversationId, requestId);
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline && service.holdsGatewayMcpPermit(messageId)) {
            Thread.sleep(10);
        }
        assertThat(service.holdsGatewayMcpPermit(messageId)).as("终态之后许可必须已经释放").isFalse();
    }

    private void awaitState(String conversationId, String requestId, String expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        String state = null;
        while (System.nanoTime() < deadline) {
            List<String> states = jdbc.queryForList("select state from assistant_conversation_messages"
                + " where conversation_id=? and request_id=? and role='USER'", String.class,
                conversationId, requestId);
            state = states.isEmpty() ? null : states.getFirst();
            if (expected.equals(state)) {
                return;
            }
            Thread.sleep(25);
        }
        assertThat(state).as("15s 内账本没有落到 %s", expected).isEqualTo(expected);
    }

    private static String leaseIdOf(String endpoint) {
        return endpoint.substring(endpoint.lastIndexOf('/') + 1);
    }

    /** 服务入口从 {@code SecurityContextHolder} 取主体，所以每个线程各自设一次（并恢复原值）。 */
    private static <T> T asUser(String username, Callable<T> action) {
        Authentication previous = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            username, "not-a-credential", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        try {
            return action.call();
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Exception checked) {
            throw new IllegalStateException(checked);
        } finally {
            if (previous == null) {
                SecurityContextHolder.clearContext();
            } else {
                SecurityContextHolder.getContext().setAuthentication(previous);
            }
        }
    }
}
