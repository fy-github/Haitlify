package com.haitlify.aitest.platform.assistant.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;

/**
 * 默认关闭：开关为 false 时入口不存在，且 Hermes 连接一次也不会建立
 * （否则「关掉的功能」仍会在启动/请求期间把凭据与网络通道挂上去）。
 *
 * <p>假端点在本类里是<b>真的在监听</b>的：连接计数为 0 才说明平台确实没有去连。
 */
@SpringBootTest(properties = {
    "app.assistant.conversation.enabled=false",
    "app.assistant.hermes.transport=tcp",
    "app.assistant.hermes.timeout-seconds=2",
    "app.security.enabled=false",
    "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
    "spring.datasource.url=jdbc:h2:mem:assistant-conversation-disabled;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class AssistantConversationDisabledTest {

    private static final ConversationHermesStub STUB = ConversationHermesStub.start();

    @DynamicPropertySource
    static void hermesEndpoint(DynamicPropertyRegistry registry) {
        registry.add("app.assistant.hermes.host", () -> "127.0.0.1");
        registry.add("app.assistant.hermes.port", STUB::port);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ApplicationContext context;

    @Test
    void theConversationEntryIsAbsentWhenTheFeatureIsOff() throws Exception {
        mockMvc.perform(post("/api/assistant/conversations").with(user("off-user").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("requestId", "off-1", "projectId", 1L))))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        mockMvc.perform(get("/api/assistant/conversations/{id}/messages", "any").with(user("off-user").roles("ADMIN")))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        assertThat(context.containsBean("assistantConversationService")).isFalse();
        assertThat(STUB.accepts()).isZero();
    }
}
