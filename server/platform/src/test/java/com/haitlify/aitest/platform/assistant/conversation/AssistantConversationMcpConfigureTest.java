package com.haitlify.aitest.platform.assistant.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.haitlify.aitest.platform.assistant.execution.ExecutionBindingService;
import com.haitlify.aitest.platform.assistant.mcp.McpLeaseRegistry;
import com.haitlify.aitest.platform.security.SecretCipher;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 网关 MCP 配置器的接线与<b>武装时机</b>（{@code configure-gateway=true}）。
 *
 * <p>钉住的是一条顺序，而不是「有没有调用」：
 * <ol>
 *   <li><b>先武装、再配置、最后派发</b>：{@code prompt.submit} 到达网关的那一刻，平台已经在注册表里
 *       能解析本轮 lease，且网关条目里已经是本轮 URL。反过来的话，模型这一轮读到的是上一轮的（已撤销）
 *       URL，或者根本没有 MCP 条目——这正是 README 要求「先武装再配置会话」的原因；</li>
 *   <li><b>派发失败撤销本轮租约</b>：不留下谁也够不着的孤儿端点；</li>
 *   <li><b>无端点 fail-closed</b>：本轮没有 ACTIVE binding 时必须显式清除网关条目，
 *       绝不把上一轮的 URL 留在配置里，也绝不跳过调用；</li>
 *   <li><b>配置器抛错只损本轮 MCP 可用性</b>：不改变账本终态、不冒充成功，并留下可对账痕迹。</li>
 * </ol>
 *
 * <p>另外钉住两条不变量（以前只有注释、没有用例，因此实现里把它们挪到闸门之前也全绿）：
 * <ul>
 *   <li><b>先过账本闸门再武装</b>：一个会被拒绝的提交（上一轮仍在跑，或账本里还有开着的行）
 *       不得改变 {@code writeCount}、不得改变 {@code endpointFor}、也不得撤销注册表里<b>正在用</b>
 *       的租约。武装会作废同一对话上一轮的租约，所以「先武装再判断」等于砍掉别人的端点；</li>
 *   <li><b>痕迹表有界</b>：插入 300 条之后长度仍是 256、最旧被淘汰，且取值只能是固定常量
 *       （URL / lease id / token 都进不来）；</li>
 *   <li><b>许可被占 → 显式拒绝</b>：另一轮持有许可时提交得到 503 + {@code MCP_PERMIT_BUSY}，一帧都
 *       不派发（因此不建会话）。「以无工具继续跑」不是降级——那个会话在创建时会绑定持有者的 URL。</li>
 * </ul>
 *
 * <p>不开任何真实网关、不写任何网关配置文件：{@link GatewayMcpConfigurator} 在这里是
 * 真实的 {@link GatewayMcpLeaseConfigurator} 跑在假控制面 {@link RecordingGatewayMcpControl} 上，
 * 假 Hermes 只是 loopback 上的脚本化 TCP 端点。
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
    "spring.datasource.url=jdbc:h2:mem:assistant-conversation-mcp-configure;MODE=MySQL;DB_CLOSE_DELAY=-1;"
        + "DATABASE_TO_LOWER=TRUE"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class AssistantConversationMcpConfigureTest {

    private static final ConversationHermesStub STUB = ConversationHermesStub.start();
    private static final RecordingGatewayMcpControl RECORDER = new RecordingGatewayMcpControl();

    @TestConfiguration
    static class Wiring {
        /** 控制面（写条目 + reload 的实际落点）是假的；配置器必须是真实的租约实现（装配会校验）。 */
        @Bean
        GatewayMcpControl testGatewayMcpControl() {
            return RECORDER;
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

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired SecretCipher cipher;
    @Autowired ExecutionBindingService execution;
    @Autowired McpLeaseRegistry registry;
    @Autowired AssistantConversationLeaseProvisioner provisioner;
    @Autowired AssistantConversationService service;

    private ConversationTurnFixture.Tenant tenant;
    private ConversationTurnFixture.Turn turn;
    /**
     * 本用例真正受理（202）过的轮次：网关级许可跨用例共享，所以每个用例结束时都要等它把许可放掉，
     * 否则下一个用例的首个提交会合法地拿到 PERMIT_BUSY，变成假红。
     */
    private final List<String[]> startedTurns = new ArrayList<>();

    private static final String TURN_REQUEST_ID = "turn-cfg-1";

    @BeforeEach
    void seed() throws Exception {
        STUB.reset();
        RECORDER.reset();
        tenant = ConversationTurnFixture.seedTenant(jdbc, "mcp-cfg");
        turn = ConversationTurnFixture.seedTurn(jdbc, json, cipher, execution, tenant, TURN_REQUEST_ID);
    }

    @AfterEach
    void releaseEveryTurnThisTestStarted() throws Exception {
        for (String[] started : startedTurns) {
            awaitPermitReleased(started[0], started[1]);
        }
        startedTurns.clear();
    }

    /**
     * 顺序：arm（注册表可解析）→ 配置（网关条目 = 本轮 URL）→ 派发（{@code prompt.submit}）。
     *
     * <p>采样点在假 Hermes 收到 {@code prompt.submit} 的那一刻，因此它看到的状态就是「模型开始跑之前」
     * 平台已经准备好的一切。
     */
    @Test
    void armsAndConfiguresTheTurnBeforeThePromptReachesTheGateway() throws Exception {
        String conversationId = createConversation();

        AtomicReference<String> entryAtSubmit = new AtomicReference<>("never-sampled");
        AtomicReference<Boolean> resolvableAtSubmit = new AtomicReference<>(false);
        STUB.onPromptSubmit(() -> {
            String entry = RECORDER.currentEntry();
            entryAtSubmit.set(entry);
            resolvableAtSubmit.set(entry != null && registry.resolve(leaseIdOf(entry)).isPresent());
        });

        MvcResult submit = submit(conversationId, TURN_REQUEST_ID, "请生成一个用例");
        assertThat(submit.getResponse().getStatus()).isEqualTo(202);
        assertThat(STUB.submits()).isEqualTo(1);

        String endpoint = provisioner.endpointFor(conversationId).orElseThrow();
        assertThat(entryAtSubmit.get())
            .as("网关必须在 prompt.submit 之前就持有本轮的租约 URL")
            .isEqualTo(endpoint);
        assertThat(resolvableAtSubmit.get())
            .as("武装必须在配置之前完成：配置发生时注册表已经能解析这个 lease")
            .isTrue();
        // 终态收尾还会写一次 null（清条目），所以「发布了什么」看 publishedUrls()，「写过几次」看 writes()。
        assertThat(RECORDER.publishedUrls()).as("只发布过本轮这一个 URL").containsExactly(endpoint);
        assertThat(RECORDER.writes()).as("除本轮 URL 之外只允许出现终态清除的 null")
            .allMatch(written -> written == null || endpoint.equals(written));
        assertThat(service.mcpTrace(conversationId, TURN_REQUEST_ID))
            .isEqualTo(AssistantConversationService.MCP_TRACE_CONFIGURED);
        // 租约指向本轮的 generation/binding，配置出去的 URL 不是别处借来的。
        assertThat(registry.resolve(leaseIdOf(endpoint)).orElseThrow().generationId())
            .isEqualTo(turn.generationId());
    }

    /** 派发失败（queued 不是受理）时，本轮租约必须撤销：配置过的 URL 只剩 401，不留孤儿端点。 */
    @Test
    void aFailedDispatchRevokesTheTurnsLease() throws Exception {
        STUB.mode(ConversationHermesStub.Mode.QUEUED);
        String conversationId = createConversation();

        MvcResult submit = submit(conversationId, TURN_REQUEST_ID, "这一轮会被排队");
        assertThat(submit.getResponse().getStatus()).isEqualTo(503);

        // 先武装后派发的证据：即使派发失败，也已经为这一轮武装并配置过一次 URL。
        List<String> applied = RECORDER.writes();
        assertThat(applied).hasSize(1);
        String configured = applied.get(0);
        assertThat(configured).matches("https://assistant\\.internal/mcp-lease/[A-Za-z0-9_-]{43}");

        assertThat(registry.resolve(leaseIdOf(configured))).as("派发失败不得留下孤儿端点").isEmpty();
        assertThat(provisioner.endpointFor(conversationId)).isEmpty();
    }

    /**
     * 第二轮没有自己的 ACTIVE binding：必须<b>显式清除</b>网关条目。
     *
     * <p>三种错误做法都会在这里变红：跳过调用（配置里还挂着上一轮的 URL）、把上一轮 URL 再写一次
     * （回退旧能力）、写入空的 {@code ""}（既不是清除也不是 URL）。
     */
    @Test
    void aTurnWithoutItsOwnBindingClearsTheGatewayEntryInsteadOfReusingTheOlderUrl() throws Exception {
        String conversationId = createConversation();
        assertThat(submitWhenIdle(conversationId, TURN_REQUEST_ID, "第一轮有绑定").getResponse().getStatus())
            .isEqualTo(202);
        String firstUrl = RECORDER.writes().getFirst();
        assertThat(firstUrl).matches("https://assistant\\.internal/mcp-lease/[A-Za-z0-9_-]{43}");

        String secondRequestId = "turn-cfg-no-binding";
        assertThat(submitWhenIdle(conversationId, secondRequestId, "第二轮没有绑定").getResponse().getStatus())
            .isEqualTo(202);

        // 之后每一次写入都只能是「清除」：第一轮的终态清条目、第二轮的「无端点」（可能还有第二轮终态的
        // 又一次清除）。关键是<b>再也不出现 URL</b>——「跳过调用」会少一次 null，「回退旧 URL」会让
        // firstUrl 再出现一次，「写空串」会出现 ""。第二轮确实调用过配置器这条事实由 MCP_NO_ENDPOINT 钉住。
        assertThat(RECORDER.writes().getFirst()).as("第一轮发布的是它自己的 URL").isEqualTo(firstUrl);
        assertThat(RECORDER.writes().subList(1, RECORDER.writeCount()))
            .as("无端点也必须调用配置器（不得跳过），且不得回退上一轮的 URL")
            .allMatch(written -> written == null);
        assertThat(RECORDER.publishedUrls()).as("只发布过第一轮那个 URL").containsExactly(firstUrl);
        assertThat(RECORDER.currentEntry()).as("网关条目必须被清除").isNull();
        assertThat(registry.resolve(leaseIdOf(firstUrl))).as("上一轮的 URL 必须已经失效").isEmpty();
        assertThat(service.mcpTrace(conversationId, secondRequestId))
            .isEqualTo(AssistantConversationService.MCP_TRACE_NO_ENDPOINT);
    }

    /**
     * 配置器抛错：只损失本轮 MCP 端点，不得把本轮写成成功。
     *
     * <p>用 {@code HANG} 让这一轮没有终态，于是「配置失败」如果被错误地当成成功收尾，这里就会看到
     * COMPLETED；正确实现下账本仍是 SUBMITTED（终态只由模型决定），并留下 MCP_CONFIGURE_FAILED 痕迹，
     * 且这一轮武装出来的 URL 立即撤销（fail-closed）。
     */
    @Test
    void aConfiguratorFailureLeavesATraceAndNeverFakesSuccess() throws Exception {
        STUB.mode(ConversationHermesStub.Mode.HANG);
        RECORDER.failOnWrite(true);
        String conversationId = createConversation();

        MvcResult submit = submit(conversationId, TURN_REQUEST_ID, "配置器会抛错的一轮");
        assertThat(submit.getResponse().getStatus()).isEqualTo(202);
        assertThat(STUB.submits()).as("配置失败只损失 MCP，不影响派发").isEqualTo(1);

        assertThat(service.mcpTrace(conversationId, TURN_REQUEST_ID))
            .isEqualTo(AssistantConversationService.MCP_TRACE_CONFIGURE_FAILED);
        assertThat(provisioner.endpointFor(conversationId))
            .as("配置没写成，这个端点谁也够不着，必须撤销")
            .isEmpty();
        assertThat(messageState(conversationId, TURN_REQUEST_ID))
            .as("配置失败不得把本轮置 COMPLETED")
            .isEqualTo(AssistantConversationService.STATE_SUBMITTED);
        assertThat(RECORDER.currentEntry()).as("失败时不得留下任何 URL").isNull();

        // HANG 的这一轮只能等回复超时落成 UNKNOWN：等它落地，网关级许可才会释放给后面的用例
        // （许可覆盖到终态，这是有意的：终态之前的任何改写都可能落在本轮的会话上）。
        awaitState(conversationId, TURN_REQUEST_ID, AssistantConversationService.STATE_UNKNOWN);
    }

    // ------------------------------------- 不变量：许可被占 → 显式拒绝

    /**
     * <b>许可被另一轮持有时，提交是显式失败（503），不是「无工具受理」</b>。
     *
     * <p>为什么必须是失败：网关条目在<b>会话创建</b>那一刻被绑定，所以一条「没有工具」的会话并不存在
     * ——它的会话在创建时读到的正是持有者那一条 URL（把对别人 generation 的能力交给它）。因此这里钉住
     * 「一帧都没发出去」：{@code STUB.submits()} 仍是 1、条目仍是持有者的 URL、本对话没有武装过。
     *
     * <p>账本必须是 {@code FAILED} 而不是 {@code UNKNOWN}：准入闸门把「任何非 FAILED 的历史行」当作
     * 「可能打到过网关」，一行 UNKNOWN 会让这个对话此后每一轮都被「无句柄却有历史」挡住。
     */
    @Test
    void aSubmitWhileAnotherTurnHoldsThePermitIsRefusedWithA503AndNeverDispatched() throws Exception {
        STUB.mode(ConversationHermesStub.Mode.HANG);
        ConversationTurnFixture.seedTurn(jdbc, json, cipher, execution, tenant, "turn-permit-holder");
        String holder = createConversation();
        assertThat(submit(holder, "turn-permit-holder", "持有许可、等着超时的一轮").getResponse().getStatus())
            .isEqualTo(202);
        awaitSubmits(1);
        String holderUrl = provisioner.endpointFor(holder).orElseThrow();
        assertThat(RECORDER.currentEntry()).isEqualTo(holderUrl);

        String other = createConversation();
        MvcResult refused = submit(other, "turn-permit-busy", "许可被占时的一轮");
        assertThat(refused.getResponse().getStatus())
            .as("许可被占是显式失败（503），不是「无工具受理」（202）")
            .isEqualTo(503);
        assertThat(refused.getResponse().getContentAsString())
            .as("响应要能对账：固定文案里带 MCP_PERMIT_BUSY")
            .contains(AssistantConversationService.MCP_TRACE_PERMIT_BUSY);
        assertThat(read(refused).path("state").asString()).isEqualTo(AssistantConversationService.STATE_FAILED);
        assertThat(read(refused).path("reconcileRequired").asBoolean())
            .as("一帧都没发出去：不需要对账推断")
            .isFalse();

        assertThat(STUB.submits()).as("被拒的轮次不得派发（因而不建会话）").isEqualTo(1);
        assertThat(RECORDER.publishedUrls()).as("条目只能是持有者那一条").containsExactly(holderUrl);
        assertThat(RECORDER.currentEntry()).as("被拒的轮次不得改写/清除条目").isEqualTo(holderUrl);
        assertThat(provisioner.endpointFor(other)).as("被拒的轮次不得武装").isEmpty();
        assertThat(service.mcpTrace(other, "turn-permit-busy"))
            .isEqualTo(AssistantConversationService.MCP_TRACE_PERMIT_BUSY);
        assertThat(messageState(other, "turn-permit-busy"))
            .as("账本落 FAILED：重放不会被悄悄派发，也不会毒住这个对话的准入闸门")
            .isEqualTo(AssistantConversationService.STATE_FAILED);

        // 终态（超时 → UNKNOWN）之后：条目被清掉、许可放开，同一个对话可以被正常受理。
        awaitState(holder, "turn-permit-holder", AssistantConversationService.STATE_UNKNOWN);
        awaitTerminalSettled(holder, "turn-permit-holder");
        assertThat(RECORDER.currentEntry()).as("终态之后条目不再指向已死租约").isNull();
        assertThat(RECORDER.publishedUrls()).containsExactly(holderUrl);

        ConversationTurnFixture.seedTurn(jdbc, json, cipher, execution, tenant, "turn-permit-after");
        assertThat(submit(other, "turn-permit-after", "终态之后的一轮").getResponse().getStatus())
            .as("许可释放之后同一个对话可以被受理")
            .isEqualTo(202);
        assertThat(service.mcpTrace(other, "turn-permit-after"))
            .isEqualTo(AssistantConversationService.MCP_TRACE_CONFIGURED);
    }

    // ------------------------------------- 不变量：先过账本闸门再武装

    /**
     * <b>被账本闸门拒绝的提交不得碰租约/网关条目</b>（{@code arm} 挪到闸门之前就会在这里变红）。
     *
     * <p>场景：这个对话上一轮已经武装过，它的租约还在 TTL 内、也可能正被在跑的会话使用；此时账本里
     * 还有一行停在 {@code SUBMITTED}（另一实例/崩溃遗留，仍在陈旧预算内），新一轮必然被拒。
     *
     * <p>三条断言缺一不可，因为三种「顺手做点事」的写法各有各的破坏面：
     * <ul>
     *   <li>{@code writeCount} 变了 → 被拒的轮次改了网关条目（本轮注定没有端点，却改写了共享资源）；</li>
     *   <li>{@code endpointFor} 变了 → 它替这个对话重新武装了，正在用的端点被换掉；</li>
     *   <li>注册表里那条租约解析不到 → 它把仍在用的能力撤销了（{@code arm} 的第一件事就是作废同对话旧租约）。</li>
     * </ul>
     */
    @Test
    void aSubmitRefusedByTheLedgerGateNeverArmsOrRevokesTheHeldLease() throws Exception {
        String conversationId = createConversation();
        // 有持久会话句柄：让「账本还有开着的行」这道闸门成为真正生效的那一道（而不是「无句柄却有历史」）。
        jdbc.update("update assistant_conversations set stored_session_id='sess-held-cfg' where id=?", conversationId);

        ConversationTurnFixture.seedTurn(jdbc, json, cipher, execution, tenant, "turn-held-cfg");
        provisioner.arm(conversationId, tenant.actorId(), tenant.projectId(), "turn-held-cfg");
        String heldUrl = provisioner.endpointFor(conversationId).orElseThrow();
        assertThat(registry.resolve(leaseIdOf(heldUrl))).as("前置：这条租约现在是可解析的").isPresent();

        int writesBefore = RECORDER.writeCount();
        String entryBefore = RECORDER.currentEntry();

        Instant now = Instant.now();
        String openId = UUID.randomUUID().toString();
        jdbc.update("insert into assistant_conversation_messages(id,conversation_id,actor_id,request_id,role,"
                + "body_text,state,turn_id,created_at,updated_at) values (?,?,?,?,'USER','open','SUBMITTED',?,?,?)",
            openId, conversationId, tenant.actorId(), "turn-open-cfg", openId, now, now);

        MvcResult refused = submit(conversationId, "turn-refused-by-gate", "会被账本闸门拒绝");
        assertThat(refused.getResponse().getStatus()).isEqualTo(503);
        assertThat(refused.getResponse().getContentAsString()).contains("账本里仍有未终态");

        assertThat(RECORDER.writeCount())
            .as("被闸门拒绝的提交不得改写网关条目（apply(null) 也不行）")
            .isEqualTo(writesBefore);
        assertThat(RECORDER.currentEntry()).as("网关条目不得被清掉/改写").isEqualTo(entryBefore);
        assertThat(provisioner.endpointFor(conversationId))
            .as("被拒的提交不得替这个对话重新武装")
            .contains(heldUrl);
        assertThat(registry.resolve(leaseIdOf(heldUrl)))
            .as("被拒的提交不得撤销仍在用的租约（arm 会作废同对话旧租约）")
            .isPresent();
        assertThat(service.mcpTrace(conversationId, "turn-refused-by-gate"))
            .as("被拒的轮次根本没走到 MCP：没有配置痕迹")
            .isNull();
    }

    /**
     * <b>上一轮仍在跑时被拒的提交（409）也不得动端点</b>。
     *
     * <p>这一条走的是提交入口的并发闸门（{@code activeTurns}），在账本行落库之前就返回，所以它比
     * 账本闸门更早；用例把「在跑」这个状态真的造出来（{@code HANG}：受理但永不产生终态），而不是假设它。
     * 结束后等这一轮超时落成 UNKNOWN，避免把网关级许可留给后面的用例。
     */
    @Test
    void aSubmitRefusedWhileATurnIsStillRunningLeavesThatTurnsEndpointIntact() throws Exception {
        STUB.mode(ConversationHermesStub.Mode.HANG);
        ConversationTurnFixture.seedTurn(jdbc, json, cipher, execution, tenant, "turn-running-cfg");
        String conversationId = createConversation();

        assertThat(submit(conversationId, "turn-running-cfg", "第一轮在跑").getResponse().getStatus())
            .isEqualTo(202);
        awaitSubmits(1);

        String armedUrl = provisioner.endpointFor(conversationId).orElseThrow();
        int writesAfterFirst = RECORDER.writeCount();
        assertThat(writesAfterFirst).as("在跑的这一轮已经配置过一次").isEqualTo(1);
        assertThat(RECORDER.currentEntry()).isEqualTo(armedUrl);

        MvcResult second = submit(conversationId, "turn-refused-409", "第二轮");
        assertThat(second.getResponse().getStatus()).isEqualTo(409);

        assertThat(RECORDER.writeCount())
            .as("被并发闸门拒绝的提交不得调用网关配置")
            .isEqualTo(writesAfterFirst);
        assertThat(RECORDER.currentEntry()).as("条目仍是在跑那一轮的 URL").isEqualTo(armedUrl);
        assertThat(provisioner.endpointFor(conversationId)).contains(armedUrl);
        assertThat(registry.resolve(leaseIdOf(armedUrl)))
            .as("在跑那一轮的租约必须仍然可解析")
            .isPresent();

        // HANG 的这一轮只能等回复超时落成 UNKNOWN；等它落地，网关级许可才会释放给后面的用例。
        awaitState(conversationId, "turn-running-cfg", AssistantConversationService.STATE_UNKNOWN);
    }

    // ------------------------------------- 不变量：痕迹表有界

    /**
     * 痕迹表有界且只装固定常量：300 次记账之后长度仍是 256，最旧的那条被淘汰。
     *
     * <p>为什么值得钉住：这张表是「网关侧发生了什么」的唯一对账线索，但它不是账本——没有上限就是
     * 一个随对话数无限增长的进程内泄漏；而一旦允许写入 URL/lease id，它又会变成凭据的第二份拷贝。
     */
    @Test
    void theMcpTraceTableStaysBoundedAt256AndOnlyHoldsFixedConstants() {
        for (int index = 0; index < 300; index++) {
            service.recordMcpTrace("trace-conv-" + index, "trace-req-" + index,
                AssistantConversationService.MCP_TRACE_CONFIGURED);
        }

        assertThat(service.mcpTraceCount()).as("上限就是 256").isEqualTo(256);
        assertThat(service.mcpTrace("trace-conv-0", "trace-req-0")).as("最旧的被淘汰").isNull();
        assertThat(service.mcpTrace("trace-conv-299", "trace-req-299")).as("最新的一定在").isNotNull();
        assertThat(service.mcpTraceValues()).as("取值只能是固定常量（不含 URL / lease id / token）")
            .isSubsetOf(AssistantConversationService.MCP_TRACE_CONFIGURED,
                AssistantConversationService.MCP_TRACE_NO_ENDPOINT,
                AssistantConversationService.MCP_TRACE_CONFIGURE_FAILED,
                AssistantConversationService.MCP_TRACE_PERMIT_BUSY,
                AssistantConversationService.MCP_TRACE_REVOKED);
    }

    // ---------------------------------------------------------------- helpers

    private void awaitSubmits(int count) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline && STUB.submits() < count) {
            Thread.sleep(10);
        }
        assertThat(STUB.submits()).as("假 Hermes 没有收到 %s 次 prompt.submit", count).isEqualTo(count);
    }

    private void awaitState(String conversationId, String requestId, String expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        String state = null;
        while (System.nanoTime() < deadline) {
            state = messageState(conversationId, requestId);
            if (expected.equals(state)) {
                return;
            }
            Thread.sleep(25);
        }
        assertThat(state).as("10s 内账本没有落到 %s", expected).isEqualTo(expected);
    }

    /**
     * 终态收尾的两个证据：网关条目已被清除、网关级许可已释放。账本落终态在它们之前，所以要等到两个
     * 证据都到位再断言（两者的<b>先后</b>由 {@link AssistantConversationMcpPermitTest} 钉住）。
     */
    private void awaitTerminalSettled(String conversationId, String requestId) throws Exception {
        String messageId = jdbc.queryForObject("select id from assistant_conversation_messages"
            + " where conversation_id=? and request_id=? and role='USER'", String.class, conversationId, requestId);
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline
                && (service.holdsGatewayMcpPermit(messageId) || RECORDER.currentEntry() != null)) {
            Thread.sleep(10);
        }
        assertThat(service.holdsGatewayMcpPermit(messageId)).as("终态之后许可必须已经释放").isFalse();
        assertThat(RECORDER.currentEntry()).as("终态之后网关条目必须被清除").isNull();
    }

    /** 等这一轮把网关级许可放掉（释放发生在终态清条目之后；不等就会把 PERMIT_BUSY 漏给下一个用例）。 */
    private void awaitPermitReleased(String conversationId, String requestId) throws Exception {
        List<String> ids = jdbc.queryForList("select id from assistant_conversation_messages"
            + " where conversation_id=? and request_id=? and role='USER'", String.class, conversationId, requestId);
        if (ids.isEmpty()) {
            return;
        }
        String messageId = ids.getFirst();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline && service.holdsGatewayMcpPermit(messageId)) {
            Thread.sleep(10);
        }
        assertThat(service.holdsGatewayMcpPermit(messageId)).as("这一轮的许可必须在用例结束时已经释放").isFalse();
    }

    private static String leaseIdOf(String endpoint) {
        return endpoint.substring(endpoint.lastIndexOf('/') + 1);
    }

    private String messageState(String conversationId, String requestId) {
        return jdbc.queryForObject("select state from assistant_conversation_messages"
            + " where conversation_id=? and request_id=?", String.class, conversationId, requestId);
    }

    private String createConversation() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/assistant/conversations").with(user(tenant.username()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("requestId", "conv-" + UUID.randomUUID(),
                    "projectId", tenant.projectId(), "title", "mcp-config"))))
            .andReturn();
        assertThat(created.getResponse().getStatus())
            .as("create body=%s", created.getResponse().getContentAsString())
            .isEqualTo(201);
        return read(created).path("conversationId").asString();
    }

    private MvcResult submit(String conversationId, String requestId, String text) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/assistant/conversations/{id}/messages", conversationId)
                .with(user(tenant.username())).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("requestId", requestId, "text", text))))
            .andReturn();
        if (result.getResponse().getStatus() == 202) {
            startedTurns.add(new String[] {conversationId, requestId});
        }
        return result;
    }

    /** 上一轮的占位由回复消费者释放，与「回复可见」之间有毫秒级窗口；窗口内的 409 不产生账本行，重试安全。 */
    private MvcResult submitWhenIdle(String conversationId, String requestId, String text) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        MvcResult last = null;
        while (System.nanoTime() < deadline) {
            last = submit(conversationId, requestId, text);
            if (last.getResponse().getStatus() != 409) {
                return last;
            }
            Thread.sleep(25);
        }
        return last;
    }

    private JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    @SuppressWarnings("unused")
    private void awaitReplies(String conversationId, int count) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            MvcResult listed = mockMvc.perform(get("/api/assistant/conversations/{id}/messages", conversationId)
                    .with(user(tenant.username()))).andExpect(status().isOk()).andReturn();
            int replies = 0;
            for (JsonNode message : read(listed).path("messages")) {
                if ("ASSISTANT".equals(message.path("role").asString())) {
                    replies++;
                }
            }
            if (replies >= count) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("5s 内没有等到 " + count + " 条助手回复：" + conversationId);
    }
}
