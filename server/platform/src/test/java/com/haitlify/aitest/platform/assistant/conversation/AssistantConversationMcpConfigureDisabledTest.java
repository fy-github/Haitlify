package com.haitlify.aitest.platform.assistant.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.ApplicationContext;
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

import tools.jackson.databind.ObjectMapper;

/**
 * {@code app.assistant.conversation.mcp-lease.configure-gateway} 默认关闭：即使租约照铸、即使上下文里
 * 确实存在一个 {@link GatewayMcpConfigurator} 实现，服务层也<b>一次都不调用</b>它。
 *
 * <p>为什么要专门钉住「上下文里有实现也不调用」：默认关闭的意义不是「没有实现所以调不到」，
 * 而是「开关没开就绝不去改网关配置」。把配置器注册成 Bean 而让开关去决定是否注入，正好能区分这两件事。
 */
@SpringBootTest(properties = {
    "app.assistant.conversation.enabled=true",
    "app.assistant.conversation.mcp-lease.enabled=true",
    "app.assistant.conversation.mcp-lease.public-base-url=https://assistant.internal",
    "app.assistant.mcp.lease.enabled=true",
    // configure-gateway 不设置：必须是默认的 false，不许因为「有 implementation」就被装配。
    "app.assistant.hermes.transport=tcp",
    "app.assistant.hermes.timeout-seconds=5",
    "app.assistant.conversation.reply-timeout-seconds=3",
    "app.security.enabled=false",
    "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
    "spring.datasource.url=jdbc:h2:mem:assistant-conversation-mcp-configure-off;MODE=MySQL;"
        + "DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class AssistantConversationMcpConfigureDisabledTest {

    private static final ConversationHermesStub STUB = ConversationHermesStub.start();
    private static final RecordingGatewayMcpControl RECORDER = new RecordingGatewayMcpControl();

    @TestConfiguration
    static class Wiring {
        /**
         * 故意注册一个<b>非租约</b>实现：开关关闭时装配连类型都不校验（更不会调用），所以它在这里是
         * 「上下文里确实存在一个实现」的证据，而不是被静默接受的真实配置器。
         */
        @Bean
        GatewayMcpConfigurator recordingGatewayMcpConfigurator() {
            return leaseEndpoint -> {
                RECORDER.writeServerEntry(leaseEndpoint);
                RECORDER.reload();
            };
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
    @Autowired ApplicationContext context;

    private ConversationTurnFixture.Tenant tenant;

    @BeforeEach
    void seed() throws Exception {
        STUB.reset();
        RECORDER.reset();
        tenant = ConversationTurnFixture.seedTenant(jdbc, "mcp-cfg-off");
        ConversationTurnFixture.seedTurn(jdbc, json, cipher, execution, tenant, "turn-cfg-off");
    }

    @Test
    void theDisabledSwitchMeansZeroConfiguratorCallsEvenWhenAnImplementationExists() throws Exception {
        assertThat(context.getBeanNamesForType(GatewayMcpLeaseConfigurator.class))
            .as("默认装配不得创建真实配置器").isEmpty();

        MvcResult created = mockMvc.perform(post("/api/assistant/conversations").with(user(tenant.username()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("requestId", "conv-" + UUID.randomUUID(),
                    "projectId", tenant.projectId()))))
            .andReturn();
        String conversationId = json.readTree(created.getResponse().getContentAsString())
            .path("conversationId").asString();

        MvcResult submit = mockMvc.perform(post("/api/assistant/conversations/{id}/messages", conversationId)
                .with(user(tenant.username())).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("requestId", "turn-cfg-off", "text", "你好"))))
            .andReturn();
        assertThat(submit.getResponse().getStatus()).isEqualTo(202);
        assertThat(STUB.submits()).isEqualTo(1);

        // 租约照铸（开关只关「写网关」这一跳），但配置器一次都没被调用。
        assertThat(provisioner.endpointFor(conversationId)).isPresent();
        assertThat(registry.size()).isPositive();
        assertThat(RECORDER.writeCount()).as("配置器关闭时零调用").isZero();
        assertThat(RECORDER.currentEntry()).isNull();
        assertThat(service.mcpTrace(conversationId, "turn-cfg-off")).as("没有配置动作就没有痕迹").isNull();
    }
}
