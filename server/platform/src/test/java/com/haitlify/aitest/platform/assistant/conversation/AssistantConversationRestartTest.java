package com.haitlify.aitest.platform.assistant.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * 重启连续：账本里已有的 {@code stored_session_id} 是唯一恢复依据。
 *
 * <p>本类用<b>独立的内存库 + 独立上下文</b>代表「新进程」：会话服务的内存绑定表是空的，只有 DB 里的
 * 账本行（模拟上一进程写的）可用。因此这里断言的正是「服务启动后首次提交该对话」的行为。
 */
@SpringBootTest(properties = {
    "app.assistant.conversation.enabled=true",
    "app.assistant.hermes.transport=tcp",
    "app.assistant.hermes.timeout-seconds=5",
    "app.assistant.conversation.reply-timeout-seconds=3",
    "app.security.enabled=false",
    "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
    "spring.datasource.url=jdbc:h2:mem:assistant-conversation-restart;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class AssistantConversationRestartTest {

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

    private String username;
    private String conversationId;

    @BeforeEach
    void seedLedgerOfPreviousProcess() {
        STUB.reset();
        username = "restart-" + UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();
        jdbc.update("insert into platform_users(username,password_hash,role,enabled,created_at,updated_at)"
            + " values (?,'unused','ADMIN',true,?,?)", username, now, now);
        long actor = jdbc.queryForObject("select id from platform_users where username=?", Long.class, username);
        jdbc.update("insert into tenants(code,name,active,created_at,updated_at) values (?,'Restart',true,?,?)",
            "tenant-" + username, now, now);
        long tenant = jdbc.queryForObject("select id from tenants where code=?", Long.class, "tenant-" + username);
        jdbc.update("insert into projects(code,name,description,active,tenant_id,created_at,updated_at)"
            + " values (?,'Restart','',true,?,?,?)", "project-" + username, tenant, now, now);
        long project = jdbc.queryForObject("select id from projects where code=?", Long.class, "project-" + username);
        conversationId = UUID.randomUUID().toString();
        jdbc.update("insert into assistant_conversations(id,tenant_id,project_id,actor_id,request_id,title,"
                + "stored_session_id,created_at,updated_at) values (?,?,?,?,?,'上一进程的对话',?,?,?)",
            conversationId, tenant, project, actor, "previous-process", "stored-restart", now, now);
    }

    @Test
    void theFirstSubmitAfterRestartResumesTheStoredSessionInsteadOfCreatingOne() throws Exception {
        MvcResult submitted = mockMvc.perform(post("/api/assistant/conversations/{id}/messages", conversationId)
                .with(user(username)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("requestId", "msg-restart", "text", "继续"))))
            .andReturn();

        assertThat(submitted.getResponse().getStatus()).isEqualTo(202);
        assertThat(STUB.resumedStoredIds()).containsExactly("stored-restart");
        assertThat(STUB.creates()).isZero();
        assertThat(STUB.submits()).isEqualTo(1);
    }

    /**
     * 上一进程在「已记账、未终结」处死掉：一小时前的 SUBMITTED 不能被读成事实终态。
     *
     * <p>这是重启后的真实 SQL 路径（不是内存假仓储）：读取必须先做有界陈旧归一化，
     * 把孤儿行透出为 UNKNOWN；并且它不得被随后的新轮次补成 COMPLETED。
     */
    @Test
    void aSubmittedRowLeftByACrashedProcessIsNotReadAsATerminalState() throws Exception {
        String staleId = UUID.randomUUID().toString();
        Instant hourAgo = Instant.now().minusSeconds(3600);
        jdbc.update("insert into assistant_conversation_messages(id,conversation_id,actor_id,request_id,role,body_text,"
                + "state,turn_id,created_at,updated_at) values (?,?,?,?,'USER',?,'SUBMITTED',?,?,?)",
            staleId, conversationId, actorId(), "msg-crashed", "崩溃前的一问", staleId, hourAgo, hourAgo);

        MvcResult listed = mockMvc.perform(get("/api/assistant/conversations/{id}/messages", conversationId)
                .with(user(username))).andExpect(status().isOk()).andReturn();

        JsonNode stale = null;
        for (JsonNode message : read(listed).path("messages")) {
            if (staleId.equals(message.path("messageId").asString())) {
                stale = message;
            }
        }
        assertThat(stale).isNotNull();
        assertThat(stale.path("state").asString()).as("陈旧 SUBMITTED 必须透出为 UNKNOWN").isEqualTo("UNKNOWN");

        // 新的一轮照常可以推进（对账闸门不是砖墙），且不得把上一轮的残行补成本轮答案
        MvcResult next = mockMvc.perform(post("/api/assistant/conversations/{id}/messages", conversationId)
                .with(user(username)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("requestId", "msg-after-crash", "text", "重启后的一问"))))
            .andReturn();
        assertThat(next.getResponse().getStatus()).isEqualTo(202);

        java.util.List<JsonNode> replies = awaitAssistantMessages(1);
        assertThat(replies).hasSize(1);
        assertThat(replies.getFirst().path("text").asString()).isEqualTo("reply:重启后的一问");
        assertThat(jdbc.queryForObject("select state from assistant_conversation_messages where id=?",
            String.class, staleId)).as("上一轮的残行不得被补成 COMPLETED").isEqualTo("UNKNOWN");
    }

    private long actorId() {
        return jdbc.queryForObject("select id from platform_users where username=?", Long.class, username);
    }

    private java.util.List<JsonNode> awaitAssistantMessages(int count) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            MvcResult listed = mockMvc.perform(get("/api/assistant/conversations/{id}/messages", conversationId)
                    .with(user(username))).andExpect(status().isOk()).andReturn();
            java.util.List<JsonNode> replies = new java.util.ArrayList<>();
            for (JsonNode message : read(listed).path("messages")) {
                if ("ASSISTANT".equals(message.path("role").asString())) {
                    replies.add(message);
                }
            }
            if (replies.size() >= count) {
                return replies;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("5s 内没有等到助手回复");
    }

    private JsonNode read(MvcResult result) {
        return json.readTree(new String(result.getResponse().getContentAsByteArray(),
            java.nio.charset.StandardCharsets.UTF_8));
    }
}

