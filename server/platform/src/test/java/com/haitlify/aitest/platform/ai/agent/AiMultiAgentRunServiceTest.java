package com.haitlify.aitest.platform.ai.agent;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.haitlify.aitest.platform.ai.gateway.AiGateway;
import com.haitlify.aitest.platform.ai.gateway.AiGatewayConfig;
import com.haitlify.aitest.platform.ai.gateway.AiGenerationResult;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiMultiAgentRunServiceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AiGateway aiGateway;

    private long projectId;

    @BeforeEach
    void resetData() {
        jdbcTemplate.update("delete from ai_agent_run_nodes");
        jdbcTemplate.update("delete from ai_agent_run_graphs");
        jdbcTemplate.update("delete from ai_call_records");
        jdbcTemplate.update("delete from ai_system_configs");
        jdbcTemplate.update("delete from projects where code = 'AI_MULTI_AGENT'");

        Instant now = Instant.now();
        jdbcTemplate.update(
            "insert into projects(code, name, description, active, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
            "AI_MULTI_AGENT",
            "AI Multi Agent Project",
            "Multi agent tests",
            true,
            now,
            now
        );
        projectId = jdbcTemplate.queryForObject("select id from projects where code = 'AI_MULTI_AGENT'", Long.class);
        jdbcTemplate.update("""
            insert into ai_system_configs(
              config_key, enabled, provider_type, base_url, text_model, vision_model, embedding_model,
              api_key_ciphertext, credential_configured, request_timeout_ms, max_input_length, max_output_length,
              retry_count, request_logging_enabled, external_document_send_allowed, provider_config_json,
              masked_config_json, actor_id, actor_name, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "default", true, "openai-compatible", "https://api.example.com/v1", "gpt-test", "", "",
            "sk-test", true, 30000, 0, 0, 0, false, true, null,
            "****test", "system-dev", "Development User", now, now
        );
    }

    @Test
    void creatingRunBuildsFiveNodeGraphInRoleOrder() throws Exception {
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, "[]", "node-model-1", 10, 10, null));
        MvcResult result = mockMvc.perform(post("/api/ai/multi-agent-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "projectId": %d,
                      "targetCaseKind": "web_automation",
                      "inputSummary": {
                        "source": "requirement-doc",
                        "goal": "生成 Web 用例测试点"
                      }
                    }
                    """.formatted(projectId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("merge_ready"))
            .andExpect(jsonPath("$.nodeCount").value(5))
            .andExpect(jsonPath("$.runId").isNotEmpty())
            .andReturn();

        String runId = objectMapper.readTree(result.getResponse().getContentAsString()).get("runId").asText();

        mockMvc.perform(get("/api/ai/multi-agent-runs/{runId}/graph", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runId").value(runId))
            .andExpect(jsonPath("$.status").value("merge_ready"))
            .andExpect(jsonPath("$.nodes.length()").value(5))
            .andExpect(jsonPath("$.nodes[0].agentId").value("requirement-understanding"))
            .andExpect(jsonPath("$.nodes[0].status").value("succeeded"))
            .andExpect(jsonPath("$.nodes[1].agentId").value("test-design"))
            .andExpect(jsonPath("$.nodes[2].agentId").value("quality-review"))
            .andExpect(jsonPath("$.nodes[3].agentId").value("automation-suggestion"))
            .andExpect(jsonPath("$.nodes[4].agentId").value("merge-coordinator"))
            .andExpect(jsonPath("$.nodes[4].outputSnapshot.status").value("merge_ready"));
    }

    @Test
    void failedNodeFailsRunAndStopsLaterNodes() throws Exception {
        // B6：不再有模拟失败开关——模型节点（test-design）在无成功响应时真实失败，
        // 下游节点一律 skipped，运行 status=failed，绝不伪造完成。
        MvcResult result = mockMvc.perform(post("/api/ai/multi-agent-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "projectId": %d,
                      "targetCaseKind": "interface"
                    }
                    """.formatted(projectId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("failed"))
            .andExpect(jsonPath("$.errorSummary").value(containsString("Model node failed")))
            .andReturn();

        String runId = objectMapper.readTree(result.getResponse().getContentAsString()).get("runId").asText();

        mockMvc.perform(get("/api/ai/multi-agent-runs/{runId}/graph", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("failed"))
            .andExpect(jsonPath("$.nodes[0].status").value("succeeded"))
            .andExpect(jsonPath("$.nodes[1].status").value("failed"))
            .andExpect(jsonPath("$.nodes[2].status").value("skipped"))
            .andExpect(jsonPath("$.nodes[3].status").value("skipped"))
            .andExpect(jsonPath("$.nodes[4].status").value("skipped"));
    }

    @Test
    void runDetailReturnsStoredInputAndOutputSnapshots() throws Exception {
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, "[]", "node-model-2", 10, 10, null));
        MvcResult result = mockMvc.perform(post("/api/ai/multi-agent-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "projectId": %d,
                      "targetCaseKind": "interface",
                      "inputSummary": { "source": "prd-v1" }
                    }
                    """.formatted(projectId)))
            .andExpect(status().isOk())
            .andReturn();

        String runId = objectMapper.readTree(result.getResponse().getContentAsString()).get("runId").asText();

        mockMvc.perform(get("/api/ai/multi-agent-runs/{runId}", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runId").value(runId))
            .andExpect(jsonPath("$.status").value("merge_ready"))
            .andExpect(jsonPath("$.inputSnapshot.source").value("prd-v1"))
            .andExpect(jsonPath("$.outputSnapshot.status").value("merge_ready"));
    }

    @Test
    void creatingAiReviewRunCallsGatewayAndRecordsStageSpecificCall() throws Exception {
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, """
                {
                  "reviewSummary": "需求点缺少异常登录路径",
                  "supplementSuggestions": [
                    {
                      "title": "补充登录失败提示校验",
                      "priority": "P1",
                      "expectedResult": "提示账号或密码错误"
                    }
                  ]
                }
                """, "review-req-1", 70, 100, null));

        MvcResult result = mockMvc.perform(post("/api/ai/multi-agent-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "projectId": %d,
                      "targetCaseKind": "web_automation",
                      "inputSummary": {
                        "reviewStage": "requirement",
                        "artifacts": [
                          { "title": "登录成功", "expectedResult": "进入工作台" }
                        ]
                      }
                    }
                    """.formatted(projectId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("merge_ready"))
            .andReturn();

        String runId = objectMapper.readTree(result.getResponse().getContentAsString()).get("runId").asText();

        Integer calls = jdbcTemplate.queryForObject(
            "select count(*) from ai_call_records where agent_id = 'ai-review' and raw_payload_json like '%登录失败提示%'",
            Integer.class
        );
        org.assertj.core.api.Assertions.assertThat(calls).isEqualTo(1);

        mockMvc.perform(get("/api/ai/multi-agent-runs/{runId}/graph", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outputSnapshot.reviewStage").value("requirement"))
            .andExpect(jsonPath("$.nodes[0].inputSnapshot.previousOutput.reviewStage").value("requirement"));
    }

    @Test
    void keepsPlainTextAiReviewResultsAsAReadableSummary() throws Exception {
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, "评审结论：建议补充取消操作和权限边界用例", "review-plain-1", 70, 100, null))
            .thenReturn(new AiGenerationResult(true, "[]", "node-model-3", 10, 10, null));

        MvcResult result = mockMvc.perform(post("/api/ai/multi-agent-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "projectId": %d,
                      "targetCaseKind": "web_automation",
                      "inputSummary": { "reviewStage": "case", "artifacts": [{ "title": "取消停用" }] }
                    }
                    """.formatted(projectId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("merge_ready"))
            .andReturn();

        String runId = objectMapper.readTree(result.getResponse().getContentAsString()).get("runId").asText();
        mockMvc.perform(get("/api/ai/multi-agent-runs/{runId}/graph", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outputSnapshot.aiReview.summary").value("评审结论：建议补充取消操作和权限边界用例"));
    }

    @Test
    void parsesAiReviewJsonEncodedInsideAJsonString() throws Exception {
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true,
                "\"{\\\"reviewSummary\\\":\\\"覆盖摘要\\\",\\\"missingPoints\\\":[{\\\"title\\\":\\\"补充失败路径\\\"}]}\"",
                "review-nested-json", 70, 100, null));

        MvcResult result = mockMvc.perform(post("/api/ai/multi-agent-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "projectId": %d,
                      "targetCaseKind": "web_automation",
                      "inputSummary": { "reviewStage": "case", "artifacts": [{ "title": "登录" }] }
                    }
                    """.formatted(projectId)))
            .andExpect(status().isOk())
            .andReturn();

        String runId = objectMapper.readTree(result.getResponse().getContentAsString()).get("runId").asText();
        mockMvc.perform(get("/api/ai/multi-agent-runs/{runId}/graph", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outputSnapshot.aiReview.reviewSummary").value("覆盖摘要"))
            .andExpect(jsonPath("$.outputSnapshot.aiReview.missingPoints[0].title").value("补充失败路径"));
    }

    @Test
    void missingAiReviewConfigReturnsControlledFailedRunInsteadOfHttp500() throws Exception {
        jdbcTemplate.update("delete from ai_system_configs where config_key = 'default'");

        mockMvc.perform(post("/api/ai/multi-agent-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "projectId": %d,
                      "targetCaseKind": "web_automation",
                      "inputSummary": { "reviewStage": "case", "artifacts": [{ "title": "登录" }] }
                    }
                    """.formatted(projectId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("failed"))
            .andExpect(jsonPath("$.errorSummary").value(containsString("AI 配置未完成")));
    }
}
