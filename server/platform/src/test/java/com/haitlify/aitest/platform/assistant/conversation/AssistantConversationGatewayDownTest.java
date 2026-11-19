package com.haitlify.aitest.platform.assistant.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.ServerSocket;
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
 * Hermes 不可达时的 fail-closed：入口开着，但执行单元连不上——绝不返回成功、绝不回退旧 AI、
 * 也绝不留下一条「看起来成功但正文为空」的消息。
 */
@SpringBootTest(properties = {
    "app.assistant.conversation.enabled=true",
    "app.assistant.hermes.transport=tcp",
    "app.assistant.hermes.timeout-seconds=2",
    "app.assistant.conversation.reply-timeout-seconds=2",
    "app.security.enabled=false",
    "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
    "spring.datasource.url=jdbc:h2:mem:assistant-conversation-down;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class AssistantConversationGatewayDownTest {

    /** 一个刚释放的空闲端口：没有监听者，连接必然被拒绝。 */
    private static final int DEAD_PORT = deadPort();

    @DynamicPropertySource
    static void hermesEndpoint(DynamicPropertyRegistry registry) {
        registry.add("app.assistant.hermes.host", () -> "127.0.0.1");
        registry.add("app.assistant.hermes.port", () -> DEAD_PORT);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    private String username;
    private long projectId;

    @BeforeEach
    void seed() {
        username = "down-" + UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();
        jdbc.update("insert into platform_users(username,password_hash,role,enabled,created_at,updated_at)"
            + " values (?,'unused','ADMIN',true,?,?)", username, now, now);
        jdbc.update("insert into tenants(code,name,active,created_at,updated_at) values (?,'Down',true,?,?)",
            "tenant-" + username, now, now);
        long tenant = jdbc.queryForObject("select id from tenants where code=?", Long.class, "tenant-" + username);
        jdbc.update("insert into projects(code,name,description,active,tenant_id,created_at,updated_at)"
            + " values (?,'Down','',true,?,?,?)", "project-" + username, tenant, now, now);
        projectId = jdbc.queryForObject("select id from projects where code=?", Long.class, "project-" + username);
    }

    @Test
    void anUnreachableGatewayFailsClosedWithoutAnySuccessSignal() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/assistant/conversations").with(user(username))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("requestId", "create-down", "projectId", projectId))))
            .andExpect(status().isCreated()).andReturn();
        String conversationId = json.readTree(new String(created.getResponse().getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8))
            .path("conversationId").asString();

        MvcResult submitted = mockMvc.perform(post("/api/assistant/conversations/{id}/messages", conversationId)
                .with(user(username)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("requestId", "msg-down", "text", "网关挂了"))))
            .andReturn();

        JsonNode receipt = json.readTree(new String(submitted.getResponse().getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8));
        assertThat(submitted.getResponse().getStatus()).isEqualTo(503);
        assertThat(receipt.path("state").asString()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select count(*) from assistant_conversation_messages"
            + " where conversation_id=? and state='COMPLETED'", Integer.class, conversationId)).isZero();
    }

    private static int deadPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new IllegalStateException("无法获取空闲端口", ex);
        }
    }
}
