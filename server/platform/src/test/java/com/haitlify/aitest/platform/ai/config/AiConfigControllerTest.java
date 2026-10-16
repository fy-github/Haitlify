package com.haitlify.aitest.platform.ai.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.haitlify.aitest.platform.ai.gateway.AiConnectionTestResult;
import com.haitlify.aitest.platform.ai.gateway.AiGenerationResult;
import com.haitlify.aitest.platform.ai.gateway.AiGateway;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AiGateway aiGateway;

    @BeforeEach
    void clearConfigTable() {
        jdbcTemplate.update("delete from ai_run_policies");
        jdbcTemplate.update("delete from ai_system_configs");
    }

    @Test
    void returnsDefaultConfigWhenEmpty() throws Exception {
        mockMvc.perform(get("/api/ai/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(false))
            .andExpect(jsonPath("$.providerType").value("openai-compatible"))
            .andExpect(jsonPath("$.credentialConfigured").value(false))
            .andExpect(jsonPath("$.maskedApiKey").doesNotExist());
    }

    @Test
    void savesConfigAndReturnsMaskedApiKey() throws Exception {
        mockMvc.perform(put("/api/ai/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "enabled": true,
                      "providerType": "openai-compatible",
                      "baseUrl": "https://api.example.com/v1",
                      "model": "gpt-4.1",
                      "visionModel": "gpt-4.1-vision",
                      "visionEnabled": true,
                      "maxImageBytes": 5242880,
                      "allowedImageTypes": ["image/png", "image/jpeg"],
                      "apiKey": "sk-demo-secret",
                      "requestTimeoutMs": 15000
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.providerType").value("openai-compatible"))
            .andExpect(jsonPath("$.baseUrl").value("https://api.example.com/v1"))
            .andExpect(jsonPath("$.model").value("gpt-4.1"))
            .andExpect(jsonPath("$.visionModel").value("gpt-4.1-vision"))
            .andExpect(jsonPath("$.visionEnabled").value(true))
            .andExpect(jsonPath("$.maxImageBytes").value(5242880))
            .andExpect(jsonPath("$.allowedImageTypes[0]").value("image/png"))
            .andExpect(jsonPath("$.allowedImageTypes[1]").value("image/jpeg"))
            .andExpect(jsonPath("$.credentialConfigured").value(true))
            .andExpect(jsonPath("$.maskedApiKey").value("****cret"))
            .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        mockMvc.perform(get("/api/ai/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.credentialConfigured").value(true))
            .andExpect(jsonPath("$.visionModel").value("gpt-4.1-vision"))
            .andExpect(jsonPath("$.visionEnabled").value(true))
            .andExpect(jsonPath("$.maskedApiKey").value("****cret"))
            .andExpect(jsonPath("$.maskedApiKey").value(org.hamcrest.Matchers.not("sk-demo-secret")));
    }

    @Test
    void savesProviderListAndReturnsOnlyMaskedCredentials() throws Exception {
        mockMvc.perform(put("/api/ai/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "enabled": true,
                      "providerType": "openai-compatible",
                      "baseUrl": "https://legacy.example.com/v1",
                      "model": "legacy-model",
                      "apiKey": "",
                      "requestTimeoutMs": 120000,
                      "providers": [
                        {
                          "id": "prod-openai",
                          "providerId": "openai",
                          "name": "生产 OpenAI",
                          "baseURL": "https://api.openai.com/v1",
                          "defaultModel": "gpt-5.5",
                          "apiKey": "sk-prod-secret",
                          "enabled": true,
                          "requestTimeoutMs": 180000
                        },
                        {
                          "id": "local-ollama",
                          "providerId": "ollama",
                          "name": "本地 Ollama",
                          "baseURL": "http://127.0.0.1:11434/v1",
                          "defaultModel": "llama3.1",
                          "enabled": false
                        }
                      ],
                      "activeProviderId": "prod-openai",
                      "activeModel": "gpt-5.5",
                      "agentModelMap": { "requirement-analysis": "gpt-5.5" },
                      "agentProviderMap": { "requirement-analysis": "prod-openai" },
                      "commandTimeoutMs": 150000
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.providerType").value("openai"))
            .andExpect(jsonPath("$.baseUrl").value("https://api.openai.com/v1"))
            .andExpect(jsonPath("$.model").value("gpt-5.5"))
            .andExpect(jsonPath("$.maskedApiKey").value("****cret"))
            .andExpect(jsonPath("$.activeProviderId").value("prod-openai"))
            .andExpect(jsonPath("$.activeModel").value("gpt-5.5"))
            .andExpect(jsonPath("$.providers[0].id").value("prod-openai"))
            .andExpect(jsonPath("$.providers[0].maskedApiKey").value("****cret"))
            .andExpect(jsonPath("$.providers[0].apiKey").doesNotExist())
            .andExpect(jsonPath("$.providers[1].providerId").value("ollama"))
            .andExpect(jsonPath("$.agentModelMap['requirement-analysis']").value("gpt-5.5"))
            .andExpect(jsonPath("$.commandTimeoutMs").value(150000));
    }

    @Test
    void updatingConnectionTimeoutAlsoUpdatesDefaultRunPolicies() throws Exception {
        saveProviderConfigWithTimeout(120000);

        saveProviderConfigWithTimeout(300000);

        Integer timeout = jdbcTemplate.queryForObject(
            "select per_call_timeout_ms from ai_run_policies where policy_id = 'default-requirement_analysis'",
            Integer.class
        );
        org.assertj.core.api.Assertions.assertThat(timeout).isEqualTo(300000);
    }

    @Test
    void testsConnectionThroughGateway() throws Exception {
        // T11：连接测试走真实结构化探针，成功仅在输出通过 JSON 合同校验时成立。
        when(aiGateway.structuredGenerate(any(), any()))
            .thenReturn(new AiGenerationResult(true, "{\"ok\":true}", "req-probe-1", 5, 5, null, false, "", 0));

        mockMvc.perform(post("/api/ai/config/test-connection")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "providerType": "openai-compatible",
                      "baseUrl": "https://api.example.com/v1",
                      "model": "gpt-4.1",
                      "requestTimeoutMs": 12000
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("结构化探针通过")))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("未自动保存")));
    }

    @Test
    void returnsFailureMessageWhenGatewayFails() throws Exception {
        // T14：上游错误体回显请求头（真实泄漏面）——金丝雀不得进入响应。
        String syntheticCanary = "s" + "k-" + "synthetic-canary-9f3e7a1b2c4d5e6f";
        when(aiGateway.structuredGenerate(any(), any()))
            .thenReturn(new AiGenerationResult(false, "", "", 0, 0,
                "连接失败: HTTP 401 body={\"detail\":\"invalid key: " + syntheticCanary + "\"}"
                    + " Authorization: Bearer ***",
                false, "auth_denied", 0));

        mockMvc.perform(post("/api/ai/config/test-connection")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "providerType": "openai-compatible",
                      "baseUrl": "https://api.example.com/v1",
                      "model": "gpt-4.1",
                      "requestTimeoutMs": 12000
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("连接失败")))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("s" + "k-" + "synthetic-canary-9f3e7a1b2c4d5e6f"))))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("***")));
    }

    @Test
    void validatesModelBeforeTestingConnection() throws Exception {
        mockMvc.perform(post("/api/ai/config/test-connection")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "providerType": "openai-compatible",
                      "baseUrl": "https://api.example.com/v1",
                      "model": "",
                      "apiKey": "sk-demo",
                      "requestTimeoutMs": 12000
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.message").value("AI 测试连接失败: 请先填写默认模型"));

        verify(aiGateway, never()).structuredGenerate(any(), any());
    }

    private void saveProviderConfigWithTimeout(int timeoutMs) throws Exception {
        mockMvc.perform(put("/api/ai/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "enabled": true,
                      "providerType": "openai",
                      "baseUrl": "https://api.example.com/v1",
                      "model": "gpt-5.6-sol",
                      "apiKey": "sk-test",
                      "requestTimeoutMs": %d,
                      "providers": [{
                        "id": "default",
                        "providerId": "openai",
                        "name": "OpenAI Compatible",
                        "baseURL": "https://api.example.com/v1",
                        "defaultModel": "gpt-5.6-sol",
                        "apiKey": "sk-test",
                        "enabled": true,
                        "requestTimeoutMs": %d
                      }],
                      "activeProviderId": "default",
                      "activeModel": "gpt-5.6-sol"
                    }
                    """.formatted(timeoutMs, timeoutMs)))
            .andExpect(status().isOk());
    }

    @Test
    void reservedCustomHeadersCannotOverrideTransportHeaders() throws Exception {
        // T14：普通用户不得通过自定义 headers 覆盖核心 Authorization/Host 等。
        mockMvc.perform(put("/api/ai/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"enabled":true,"providers":[{"id":"p1","providerId":"openai","customHeaders":{"Authorization":"Bearer attacker-supplied"}}]}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("reserved_header")));

        mockMvc.perform(put("/api/ai/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"enabled":true,"providers":[{"id":"p1","providerId":"openai","customHeaders":{"Host":"evil.example"}}]}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Host")));
    }
}
