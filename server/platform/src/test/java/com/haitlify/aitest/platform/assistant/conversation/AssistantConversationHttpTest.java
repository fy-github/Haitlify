package com.haitlify.aitest.platform.assistant.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 助手对话 HTTP 入口的端到端行为：真实 Spring 上下文 + MockMvc + H2 + <b>真实</b>
 * {@code HermesTcpGateway} 指向 loopback 假端点（不 mock 被测服务、不 mock 传输层、不连真实模型）。
 *
 * <p>断言只落在对外契约与 wire 事实上：HTTP 状态、响应字段、会话账本行，以及假端点收到了几帧
 * {@code session.create}/{@code prompt.submit}。这样实现可以重构，契约不能变。
 */
@SpringBootTest(properties = {
    "app.assistant.conversation.enabled=true",
    "app.assistant.hermes.transport=tcp",
    "app.assistant.hermes.timeout-seconds=5",
    "app.assistant.conversation.reply-timeout-seconds=3",
    "app.security.enabled=false",
    "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
    "spring.datasource.url=jdbc:h2:mem:assistant-conversation-http;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class AssistantConversationHttpTest {

    private static final ConversationHermesStub STUB = ConversationHermesStub.start();

    @DynamicPropertySource
    static void hermesEndpoint(DynamicPropertyRegistry registry) {
        registry.add("app.assistant.hermes.host", () -> "127.0.0.1");
        registry.add("app.assistant.hermes.port", STUB::port);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    private String admin;
    private String editor;
    private long projectId;
    private long foreignProjectId;
    private long tenantId;

    @BeforeEach
    void seed() {
        STUB.reset();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        admin = "conv-admin-" + suffix;
        editor = "conv-editor-" + suffix;
        tenantId = seedUser(admin, "ADMIN");
        projectId = seedProject(admin, tenantId);
        long foreignTenant = seedUser(editor, "EDITOR");
        foreignProjectId = seedProject(editor, foreignTenant);
        join(foreignTenant, foreignProjectId, editor);
    }

    // --- 1) 创建对话：幂等 -------------------------------------------------

    @Test
    void creatingTheSameRequestIdTwiceYieldsOneConversation() throws Exception {
        String body = json.writeValueAsString(Map.of("requestId", "create-1", "projectId", projectId, "title", "第一段"));

        MvcResult first = mockMvc.perform(post("/api/assistant/conversations").with(user(admin))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/assistant/conversations").with(user(admin))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn();

        assertThat(read(replay).path("conversationId").asString())
            .isEqualTo(read(first).path("conversationId").asString());
        assertThat(jdbc.queryForObject("select count(*) from assistant_conversations where actor_id=?"
            + " and project_id=?", Integer.class, idOfUser(admin), projectId)).isEqualTo(1);
    }

    // --- 2) 提交一轮：202 + 自己的 turnId + SUBMITTED -----------------------

    @Test
    void submittingTextIsAcceptedWithOurOwnTurnIdAndSubmittedState() throws Exception {
        String conversationId = createConversation(admin, projectId, "create-2");

        MvcResult submitted = submit(admin, conversationId, "你好", "msg-1");

        assertThat(submitted.getResponse().getStatus()).isEqualTo(202);
        JsonNode receipt = read(submitted);
        assertThat(receipt.path("state").asString()).isEqualTo("SUBMITTED");
        assertThat(receipt.path("turnId").asString()).isNotBlank()
            .isEqualTo(receipt.path("messageId").asString());
        assertThat(STUB.submits()).isEqualTo(1);
    }

    // --- 3) 读取：状态与文本 ------------------------------------------------

    @Test
    void replyIsReadableWithItsTextAndCompletedState() throws Exception {
        String conversationId = createConversation(admin, projectId, "create-3");
        submit(admin, conversationId, "介绍一下自己", "msg-3");

        JsonNode reply = awaitAssistantMessage(admin, conversationId);

        assertThat(reply.path("text").asString()).isEqualTo("reply:介绍一下自己");
        assertThat(reply.path("state").asString()).isEqualTo("COMPLETED");
    }

    // --- 4) 幂等：重复 requestId 不得重复提交到网关 ------------------------

    @Test
    void repeatingAMessageRequestIdIsNotSubmittedTwiceToTheGateway() throws Exception {
        String conversationId = createConversation(admin, projectId, "create-4");

        MvcResult first = submit(admin, conversationId, "同一问", "msg-4");
        MvcResult replay = submit(admin, conversationId, "同一问", "msg-4");

        assertThat(read(replay).path("messageId").asString())
            .isEqualTo(read(first).path("messageId").asString());
        assertThat(STUB.submits()).isEqualTo(1);
    }

    // --- 5) 并发：同一对话不得双开轮次 --------------------------------------

    @Test
    void aSecondTurnOnTheSameConversationIsRejectedWhileTheFirstIsUnfinished() throws Exception {
        STUB.mode(ConversationHermesStub.Mode.HANG);
        String conversationId = createConversation(admin, projectId, "create-5");
        submit(admin, conversationId, "第一问", "msg-5a");

        mockMvc.perform(post("/api/assistant/conversations/{id}/messages", conversationId).with(user(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("requestId", "msg-5b", "text", "第二问"))))
            .andExpect(status().isConflict());
        assertThat(STUB.submits()).isEqualTo(1);
    }

    // --- 6) 不伪造成功：queued 不是「本轮已开始」 ---------------------------

    @Test
    void aQueuedAcceptanceIsReportedAsUnknownInsteadOfSuccess() throws Exception {
        STUB.mode(ConversationHermesStub.Mode.QUEUED);
        String conversationId = createConversation(admin, projectId, "create-6");

        MvcResult failed = submit(admin, conversationId, "排队的一问", "msg-6");

        assertThat(failed.getResponse().getStatus()).isEqualTo(503);
        assertThat(read(failed).path("state").asString()).isEqualTo("UNKNOWN");
        assertThat(read(failed).path("reconcileRequired").asBoolean()).isTrue();
    }

    // --- 7) 身份只能来自登录主体 --------------------------------------------

    @Test
    void aRequestBodyCarryingItsOwnIdentityIsRejected() throws Exception {
        String body = json.writeValueAsString(Map.of("requestId", "create-7", "projectId", projectId,
            "actorId", idOfUser(admin), "tenantId", tenantId));

        mockMvc.perform(post("/api/assistant/conversations").with(user(admin))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    void anUnauthenticatedSubmitIsRejected() throws Exception {
        mockMvc.perform(post("/api/assistant/conversations/{id}/messages", "any").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("requestId", "msg-8", "text", "无身份"))))
            .andExpect(status().isUnauthorized());
    }

    // --- 8) 越权：他人对话 / 他人项目 ---------------------------------------

    @Test
    void anotherActorsConversationIsNotReadable() throws Exception {
        String conversationId = createConversation(admin, projectId, "create-9");

        mockMvc.perform(get("/api/assistant/conversations/{id}/messages", conversationId).with(user(editor)))
            .andExpect(status().isNotFound());
    }

    @Test
    void creatingAConversationInAForeignProjectIsRejected() throws Exception {
        String body = json.writeValueAsString(Map.of("requestId", "create-10", "projectId", projectId));

        mockMvc.perform(post("/api/assistant/conversations").with(user(editor))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden());
    }

    // --- 9) 重启句柄：持久化但绝不外泄 --------------------------------------

    @Test
    void theStoredSessionIdIsPersistedButNeverReturnedToClients() throws Exception {
        String conversationId = createConversation(admin, projectId, "create-11");
        submit(admin, conversationId, "持久化检查", "msg-11");

        String stored = jdbc.queryForObject("select stored_session_id from assistant_conversations where id=?",
            String.class, conversationId);
        MvcResult listed = mockMvc.perform(get("/api/assistant/conversations/{id}/messages", conversationId)
                .with(user(admin))).andExpect(status().isOk()).andReturn();

        assertThat(stored).isEqualTo(STUB.lastCreatedStoredId()).isNotBlank();
        assertThat(new String(listed.getResponse().getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("stored-1", "stored_session_id");
    }

    // --- 10) 分页读取 -------------------------------------------------------

    @Test
    void messagesArePagedByLimitAndOffset() throws Exception {
        String conversationId = createConversation(admin, projectId, "create-12");
        Instant now = Instant.now();
        for (int index = 0; index < 3; index++) {
            jdbc.update("insert into assistant_conversation_messages(id,conversation_id,actor_id,request_id,role,body_text,"
                    + "state,turn_id,created_at,updated_at) values (?,?,?,?,'USER',?,'SUBMITTED',?,?,?)",
                UUID.randomUUID().toString(), conversationId, idOfUser(admin), "seed-" + index, "第" + index + "问",
                "seed-" + index, now.plusMillis(index), now.plusMillis(index));
        }

        mockMvc.perform(get("/api/assistant/conversations/{id}/messages", conversationId).with(user(admin))
                .param("limit", "2").param("offset", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(3))
            .andExpect(jsonPath("$.messages.length()").value(2))
            .andExpect(jsonPath("$.messages[0].text").value("第1问"));
    }

    // --- 11) 同一对话的多轮：复用同一 live 会话 ------------------------------

    @Test
    void aSecondTurnReusesTheSameLiveSessionInsteadOfCreatingANewOne() throws Exception {
        String conversationId = createConversation(admin, projectId, "create-13");
        submit(admin, conversationId, "第一问", "msg-13a");
        awaitAssistantMessages(admin, conversationId, 1);

        MvcResult second = submit(admin, conversationId, "第二问", "msg-13b");
        assertThat(second.getResponse().getStatus()).isEqualTo(202);
        awaitAssistantMessages(admin, conversationId, 2);

        assertThat(STUB.creates()).isEqualTo(1);
        assertThat(STUB.submittedSessionIds()).hasSize(2)
            .containsOnly(STUB.submittedSessionIds().getFirst());
    }

    // --- 12) 消息接口同样不接受请求体里的身份 --------------------------------

    @Test
    void aMessageBodyCarryingItsOwnActorIsRejected() throws Exception {
        String conversationId = createConversation(admin, projectId, "create-14");

        mockMvc.perform(post("/api/assistant/conversations/{id}/messages", conversationId).with(user(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("requestId", "msg-14", "text", "冒充",
                    "actorId", idOfUser(admin)))))
            .andExpect(status().isBadRequest());
    }

    // --- helpers -----------------------------------------------------------

    private String createConversation(String username, long project, String requestId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/assistant/conversations").with(user(username))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("requestId", requestId, "projectId", project))))
            .andExpect(status().isCreated()).andReturn();
        return read(result).path("conversationId").asString();
    }

    private MvcResult submit(String username, String conversationId, String text, String requestId) throws Exception {
        return mockMvc.perform(post("/api/assistant/conversations/{id}/messages", conversationId).with(user(username))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("requestId", requestId, "text", text))))
            .andReturn();
    }

    private JsonNode awaitAssistantMessage(String username, String conversationId) throws Exception {
        return awaitAssistantMessages(username, conversationId, 1).getFirst();
    }

    private java.util.List<JsonNode> awaitAssistantMessages(String username, String conversationId, int count)
            throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            MvcResult listed = mockMvc.perform(get("/api/assistant/conversations/{id}/messages", conversationId)
                    .with(user(username))).andExpect(status().isOk()).andReturn();
            java.util.List<JsonNode> replied = new java.util.ArrayList<>();
            for (JsonNode message : read(listed).path("messages")) {
                if ("ASSISTANT".equals(message.path("role").asString())) {
                    replied.add(message);
                }
            }
            if (replied.size() >= count) {
                return replied;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("5s 内没有等到 " + count + " 条助手回复：会话 " + conversationId);
    }

    private JsonNode read(MvcResult result) {
        return json.readTree(new String(result.getResponse().getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8));
    }

    private long seedUser(String username, String role) {
        Instant now = Instant.now();
        jdbc.update("insert into platform_users(username,password_hash,role,enabled,created_at,updated_at)"
            + " values (?,'unused',?,true,?,?)", username, role, now, now);
        jdbc.update("insert into tenants(code,name,active,created_at,updated_at) values (?,'Conversation',true,?,?)",
            "tenant-" + username, now, now);
        return jdbc.queryForObject("select id from tenants where code=?", Long.class, "tenant-" + username);
    }

    private long seedProject(String username, long tenant) {
        Instant now = Instant.now();
        jdbc.update("insert into projects(code,name,description,active,tenant_id,created_at,updated_at)"
            + " values (?,'Conversation','',true,?,?,?)", "project-" + username, tenant, now, now);
        return jdbc.queryForObject("select id from projects where code=?", Long.class, "project-" + username);
    }

    private void join(long tenant, long project, String username) {
        Instant now = Instant.now();
        jdbc.update("insert into tenant_members(tenant_id,username,created_at) values (?,?,?)", tenant, username, now);
        jdbc.update("insert into project_members(project_id,username,created_at) values (?,?,?)", project, username, now);
    }

    private long idOfUser(String username) {
        return jdbc.queryForObject("select id from platform_users where username=?", Long.class, username);
    }
}
