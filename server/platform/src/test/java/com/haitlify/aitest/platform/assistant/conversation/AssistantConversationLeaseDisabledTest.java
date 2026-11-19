package com.haitlify.aitest.platform.assistant.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.haitlify.aitest.platform.assistant.mcp.McpLeaseRegistry;

import tools.jackson.databind.ObjectMapper;

/**
 * 对话铸租约的开关默认关闭：即使 MCP 租约面本身开着，只要
 * {@code app.assistant.conversation.mcp-lease.enabled} 不是 true，就没有 provisioner Bean，
 * 一轮对话受理之后注册表里也不会多出任何东西。
 */
@SpringBootTest(properties = {
    "app.assistant.conversation.enabled=true",
    "app.assistant.mcp.lease.enabled=true",
    "app.assistant.hermes.transport=tcp",
    "app.assistant.hermes.timeout-seconds=5",
    "app.assistant.conversation.reply-timeout-seconds=3",
    "app.security.enabled=false",
    "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
    "spring.datasource.url=jdbc:h2:mem:assistant-conversation-lease-off;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class AssistantConversationLeaseDisabledTest {

    private static final ConversationHermesStub STUB = ConversationHermesStub.start();

    @DynamicPropertySource
    static void hermesEndpoint(DynamicPropertyRegistry registry) {
        registry.add("app.assistant.hermes.host", () -> "127.0.0.1");
        registry.add("app.assistant.hermes.port", STUB::port);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired ApplicationContext context;
    @Autowired McpLeaseRegistry registry;

    @Test
    void noProvisionerBeanAndNoLeaseIsMintedWhenTheSwitchIsOff() throws Exception {
        assertThat(context.getBeanNamesForType(AssistantConversationLeaseProvisioner.class)).isEmpty();
        int before = registry.size();

        String username = "lease-off-" + UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();
        jdbc.update("insert into platform_users(username,password_hash,role,enabled,created_at,updated_at)"
            + " values (?,'unused','ADMIN',true,?,?)", username, now, now);
        jdbc.update("insert into tenants(code,name,active,created_at,updated_at) values (?,'Lease',true,?,?)",
            "tenant-" + username, now, now);
        long tenant = jdbc.queryForObject("select id from tenants where code=?", Long.class, "tenant-" + username);
        jdbc.update("insert into projects(code,name,description,active,tenant_id,created_at,updated_at)"
            + " values (?,'Lease','',true,?,?,?)", "project-" + username, tenant, now, now);
        long project = jdbc.queryForObject("select id from projects where code=?", Long.class, "project-" + username);

        MvcResult created = mockMvc.perform(post("/api/assistant/conversations").with(user(username))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("requestId", "conv-off", "projectId", project))))
            .andExpect(status().isCreated()).andReturn();
        String conversationId = json.readTree(created.getResponse().getContentAsString()).path("conversationId").asString();

        MvcResult submit = mockMvc.perform(post("/api/assistant/conversations/{id}/messages", conversationId)
                .with(user(username)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("requestId", "turn-off", "text", "hello"))))
            .andReturn();
        assertThat(submit.getResponse().getStatus()).isEqualTo(202);
        assertThat(STUB.submits()).isEqualTo(1);

        assertThat(registry.size()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from assistant_execution_grants", Integer.class)).isZero();
    }
}
