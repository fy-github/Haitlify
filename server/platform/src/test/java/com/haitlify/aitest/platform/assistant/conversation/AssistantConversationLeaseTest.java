package com.haitlify.aitest.platform.assistant.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
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
 * 对话入口 → MCP 租约的接线：一轮对话被受理后，平台为该轮 requestId 对应的活跃 generation 铸一张 grant，
 * 把 token 留在内存注册表里，只在进程内交出 {@code <base>/mcp-lease/<leaseId>}。
 *
 * <p>默认关闭由 {@link AssistantConversationLeaseDisabledTest} 覆盖；这里覆盖「打开后确实铸了、而且没落盘」。
 *
 * <p>按轮绑定是这里最关键的一条：租约必须指向<b>武装它的那一轮</b>的 generation。若两轮共用一条租约，
 * 模型在新一轮的 {@code draft_create} 就会写到上一轮的需求上——那是一个跨轮写入缺陷，而不是「少一个端点」。
 */
@SpringBootTest(properties = {
    "app.assistant.conversation.enabled=true",
    "app.assistant.conversation.mcp-lease.enabled=true",
    "app.assistant.conversation.mcp-lease.public-base-url=https://assistant.internal",
    "app.assistant.conversation.mcp-lease.ttl-seconds=120",
    "app.assistant.mcp.lease.enabled=true",
    "app.assistant.mcp.lease.ttl-seconds=120",
    "app.assistant.hermes.transport=tcp",
    "app.assistant.hermes.timeout-seconds=5",
    "app.assistant.conversation.reply-timeout-seconds=3",
    "app.security.enabled=false",
    "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
    "spring.datasource.url=jdbc:h2:mem:assistant-conversation-lease;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class AssistantConversationLeaseTest {

    private static final ConversationHermesStub STUB = ConversationHermesStub.start();

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
    @Autowired com.haitlify.aitest.platform.assistant.security.capability.CapabilityTokenCodec codec;
    @Autowired McpLeaseRegistry registry;
    @Autowired AssistantConversationLeaseProvisioner provisioner;

    private String username;
    private long actorId;
    private long tenantId;
    private long projectId;
    private String generationId;
    private String bindingId;
    private static final String TURN_REQUEST_ID = "turn-lease-1";
    private static final String SECOND_TURN_REQUEST_ID = "turn-lease-2";

    /** 一次 seed 出来的一轮：generation 与它的 active binding。 */
    private record Turn(String generationId, String bindingId) {}

    @BeforeEach
    void seed() throws Exception {
        STUB.reset();
        username = "lease-user-" + UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();
        jdbc.update("insert into platform_users(username,password_hash,role,enabled,created_at,updated_at)"
            + " values (?,'unused','ADMIN',true,?,?)", username, now, now);
        actorId = jdbc.queryForObject("select id from platform_users where username=?", Long.class, username);
        jdbc.update("insert into tenants(code,name,active,created_at,updated_at) values (?,'Lease',true,?,?)",
            "tenant-" + username, now, now);
        tenantId = jdbc.queryForObject("select id from tenants where code=?", Long.class, "tenant-" + username);
        jdbc.update("insert into projects(code,name,description,active,tenant_id,created_at,updated_at)"
            + " values (?,'Lease','',true,?,?,?)", "project-" + username, tenantId, now, now);
        projectId = jdbc.queryForObject("select id from projects where code=?", Long.class, "project-" + username);
        Turn first = seedTurn(now, TURN_REQUEST_ID);
        generationId = first.generationId();
        bindingId = first.bindingId();
    }

    @Test
    void acceptedTurnArmsExactlyOneLeaseThatResolvesOnlyInMemory() throws Exception {
        String conversationId = createConversation();
        MvcResult submit = submit(conversationId, TURN_REQUEST_ID, "请生成一个用例");
        assertThat(submit.getResponse().getStatus()).isEqualTo(202);
        assertThat(STUB.submits()).isEqualTo(1);

        String endpoint = provisioner.endpointFor(conversationId).orElseThrow();
        assertThat(endpoint).matches("https://assistant\\.internal/mcp-lease/[A-Za-z0-9_-]{43}");
        String leaseId = leaseIdOf(endpoint);

        var resolved = registry.resolve(leaseId).orElseThrow();
        assertThat(resolved.generationId()).isEqualTo(generationId);
        assertThat(resolved.bindingId()).isEqualTo(bindingId);
        assertThat(resolved.actorId()).isEqualTo(actorId);
        assertThat(resolved.expiresAt()).isAfter(Instant.now());

        // 平台为这一轮铸了一张 grant（token 只在内存里；库里只有 hash）
        assertThat(grantsFor(bindingId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select token_hash from assistant_execution_grants where binding_id=?",
            String.class, bindingId)).isEqualTo(codec.hashPresented(resolved.token()));

        // 响应与库里都没有 lease id / token
        assertThat(submit.getResponse().getContentAsString()).doesNotContain(leaseId, resolved.token());
        assertThat(json.readTree(submit.getResponse().getContentAsString()).has("lease")).isFalse();
        for (String table : List.of("assistant_conversations", "assistant_conversation_messages",
                "assistant_execution_grants", "assistant_execution_bindings", "assistant_generations")) {
            assertThat(jdbc.queryForList("select * from " + table).toString())
                .as(table).doesNotContain(leaseId, resolved.token());
        }

        // 再次为同一轮铸租约：只轮换不透明 id（一个 binding 只允许一张 grant），旧 id 立即撤销
        String token = resolved.token();
        provisioner.arm(conversationId, actorId, projectId, TURN_REQUEST_ID);
        String secondEndpoint = provisioner.endpointFor(conversationId).orElseThrow();
        String secondLeaseId = leaseIdOf(secondEndpoint);
        assertThat(secondLeaseId).isNotEqualTo(leaseId);
        assertThat(registry.resolve(leaseId)).isEmpty();
        assertThat(registry.resolve(secondLeaseId).orElseThrow().token()).isEqualTo(token);
        assertThat(grantsFor(bindingId)).isEqualTo(1);

        provisioner.disarm(conversationId);
        assertThat(provisioner.endpointFor(conversationId)).isEmpty();
        assertThat(registry.resolve(secondLeaseId)).isEmpty();
    }

    /**
     * 两轮必须各自武装到本轮的 generation。
     *
     * <p>旧实现以 conversationId 为键：第二轮的 {@code arm} 命中「已武装」分支，直接轮换第一轮的租约就返回，
     * 于是第二轮的 URL 解析到<b>第一轮</b>的 generation（同一张 token、同一个 binding），模型在新一轮的
     * {@code draft_create} 会写回上一轮的需求。这里断言第二轮指向第二轮，且第一轮的 URL 立即作废。
     */
    @Test
    void aSecondTurnGetsALeaseThatTargetsItsOwnGeneration() throws Exception {
        Turn second = seedTurn(Instant.now(), SECOND_TURN_REQUEST_ID);
        String conversationId = createConversation();

        assertThat(submitWhenIdle(conversationId, TURN_REQUEST_ID, "第一轮：生成用例").getResponse().getStatus())
            .isEqualTo(202);
        String firstLeaseId = leaseIdOf(provisioner.endpointFor(conversationId).orElseThrow());
        assertThat(registry.resolve(firstLeaseId).orElseThrow().generationId()).isEqualTo(generationId);

        awaitReplies(conversationId, 1);
        assertThat(submitWhenIdle(conversationId, SECOND_TURN_REQUEST_ID, "第二轮：换个需求")
            .getResponse().getStatus()).isEqualTo(202);
        assertThat(STUB.submits()).isEqualTo(2);

        String secondLeaseId = leaseIdOf(provisioner.endpointFor(conversationId).orElseThrow());
        assertThat(secondLeaseId).isNotEqualTo(firstLeaseId);
        var live = registry.resolve(secondLeaseId).orElseThrow();
        assertThat(live.generationId()).isEqualTo(second.generationId());
        assertThat(live.bindingId()).isEqualTo(second.bindingId());
        assertThat(live.actorId()).isEqualTo(actorId);
        assertThat(live.expiresAt()).isAfter(Instant.now());

        // 上一轮的 URL 不得继续可用：新一轮不能借它写回上一轮的需求。
        assertThat(registry.resolve(firstLeaseId)).isEmpty();

        // 一 binding 一 grant：两轮各一张，第二轮没有复用也没有多铸。
        assertThat(grantsFor(bindingId)).isEqualTo(1);
        assertThat(grantsFor(second.bindingId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select token_hash from assistant_execution_grants where binding_id=?",
            String.class, second.bindingId())).isEqualTo(codec.hashPresented(live.token()));

        // 换轮不是扩权：第二轮租约的 generation 只能看到本轮自己的 binding。
        assertThat(live.bindingId()).isNotEqualTo(bindingId);
        assertThat(live.generationId()).isNotEqualTo(generationId);
    }

    /**
     * 对照用例：真正的同一轮重试（同 conversationId + 同 requestId）仍只轮换不透明 id——
     * 不新签 grant、不延长截止、旧 id 立即失效。改「按轮绑定」不得把轮换语义改坏。
     */
    @Test
    void retryingTheSameRequestIdRotatesWithoutAnotherGrantOrALaterDeadline() throws Exception {
        String conversationId = createConversation();
        assertThat(submitWhenIdle(conversationId, TURN_REQUEST_ID, "重试前的一轮").getResponse().getStatus())
            .isEqualTo(202);

        String firstLeaseId = leaseIdOf(provisioner.endpointFor(conversationId).orElseThrow());
        var first = registry.resolve(firstLeaseId).orElseThrow();

        provisioner.arm(conversationId, actorId, projectId, TURN_REQUEST_ID);

        String retriedLeaseId = leaseIdOf(provisioner.endpointFor(conversationId).orElseThrow());
        var retried = registry.resolve(retriedLeaseId).orElseThrow();
        assertThat(retriedLeaseId).isNotEqualTo(firstLeaseId);
        assertThat(registry.resolve(firstLeaseId)).isEmpty();
        // 同一轮：同一张能力、同一个 generation/binding
        assertThat(retried.token()).isEqualTo(first.token());
        assertThat(retried.generationId()).isEqualTo(generationId);
        assertThat(retried.bindingId()).isEqualTo(bindingId);
        // 不新签 grant（uq_assistant_execution_grant_binding），不扩期
        assertThat(grantsFor(bindingId)).isEqualTo(1);
        assertThat(retried.expiresAt()).isBeforeOrEqualTo(first.expiresAt());

        // 再重试一次仍然只是轮换
        provisioner.arm(conversationId, actorId, projectId, TURN_REQUEST_ID);
        String thirdLeaseId = leaseIdOf(provisioner.endpointFor(conversationId).orElseThrow());
        assertThat(thirdLeaseId).isNotIn(firstLeaseId, retriedLeaseId);
        assertThat(registry.resolve(retriedLeaseId)).isEmpty();
        assertThat(registry.resolve(thirdLeaseId).orElseThrow().token()).isEqualTo(first.token());
        assertThat(grantsFor(bindingId)).isEqualTo(1);
        assertThat(registry.resolve(thirdLeaseId).orElseThrow().expiresAt()).isBeforeOrEqualTo(first.expiresAt());
    }

    @Test
    void aTurnWithoutAnActiveGenerationGetsNoLeaseAtAll() throws Exception {
        String conversationId = createConversation();
        assertThat(submit(conversationId, "turn-without-generation", "无绑定的一轮").getResponse().getStatus())
            .isEqualTo(202);
        assertThat(provisioner.endpointFor(conversationId)).isEmpty();
        assertThat(grantsFor(bindingId)).isZero();
    }

    /**
     * 新的一轮没有可用 binding 时必须 fail-closed：上一轮留下的租约 URL 不能被新一轮继续借用，
     * 否则「本轮无 MCP 端点」会被一条陈旧能力悄悄顶替。
     */
    @Test
    void aLaterTurnWithoutItsOwnGenerationInheritsNoLeaseFromThePreviousOne() throws Exception {
        String conversationId = createConversation();
        assertThat(submitWhenIdle(conversationId, TURN_REQUEST_ID, "第一轮有绑定").getResponse().getStatus())
            .isEqualTo(202);
        String firstLeaseId = leaseIdOf(provisioner.endpointFor(conversationId).orElseThrow());

        // 第二轮：账本里有这一轮，但没有对应的 generation/binding。
        provisioner.arm(conversationId, actorId, projectId, "turn-without-generation");

        assertThat(provisioner.endpointFor(conversationId)).isEmpty();
        assertThat(registry.resolve(firstLeaseId)).isEmpty();
        assertThat(grantsFor(bindingId)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- helpers

    private Turn seedTurn(Instant now, String requestId) throws Exception {
        String generation = UUID.randomUUID().toString();
        String snapshot = json.writeValueAsString(new java.util.TreeMap<>(Map.of("requestId", requestId,
            "projectId", projectId, "requirementRef", "requirement:conversation",
            "requirementText", "对话里产生的需求", "targetUrl", "https://example.com/app", "targetRevision", "v1")));
        jdbc.update("insert into assistant_generations(id,actor_id,project_id,tenant_id,request_id,payload_digest,"
                + "snapshot_ciphertext,state,created_at) values (?,?,?,?,?,?,?,'WAITING_WORKER',?)",
            generation, actorId, projectId, tenantId, requestId, sha256(snapshot), cipher.encrypt(snapshot), now);
        String unit = "unit-" + UUID.randomUUID();
        execution.confirmUnitCleaned(execution.registerUnit(
            new ExecutionBindingService.WorkerUnit(unit, "sha256:" + "b".repeat(64), "policy-v1")));
        String binding = execution.allocate(
            new ExecutionBindingService.AllocationRequest(generation, unit, Duration.ofMinutes(5))).bindingId();
        return new Turn(generation, binding);
    }

    private int grantsFor(String binding) {
        return jdbc.queryForObject("select count(*) from assistant_execution_grants where binding_id=?",
            Integer.class, binding);
    }

    private static String leaseIdOf(String endpoint) {
        return endpoint.substring(endpoint.lastIndexOf('/') + 1);
    }

    private String createConversation() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/assistant/conversations").with(user(username))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("requestId", "conv-" + UUID.randomUUID(),
                    "projectId", projectId, "title", "lease"))))
            .andReturn();
        org.assertj.core.api.Assertions.assertThat(created.getResponse().getStatus())
            .as("create body=%s", created.getResponse().getContentAsString())
            .isEqualTo(201);
        return read(created).path("conversationId").asString();
    }

    private MvcResult submit(String conversationId, String requestId, String text) throws Exception {
        return mockMvc.perform(post("/api/assistant/conversations/{id}/messages", conversationId).with(user(username))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("requestId", requestId, "text", text))))
            .andReturn();
    }

    /**
     * 提交一轮，直到平台接受。上一轮的 {@code activeTurns} 占位由回复消费者在写完终态后释放，
     * 这个释放与「助手回复已可见」之间有毫秒级窗口，窗口里的提交会被 409 挡住（服务端在插入前就拒绝，
     * 不产生任何账本行），重试是安全的。
     */
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

    private void awaitReplies(String conversationId, int count) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            MvcResult listed = mockMvc.perform(get("/api/assistant/conversations/{id}/messages", conversationId)
                    .with(user(username))).andExpect(status().isOk()).andReturn();
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

    private JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
