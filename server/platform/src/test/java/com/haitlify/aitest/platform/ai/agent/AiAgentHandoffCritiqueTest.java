package com.haitlify.aitest.platform.ai.agent;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.haitlify.aitest.platform.ai.gateway.AiGateway;
import com.haitlify.aitest.platform.ai.gateway.AiGatewayConfig;
import com.haitlify.aitest.platform.ai.gateway.AiGenerationResult;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiAgentHandoffCritiqueTest {

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
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true,
                "{\"draftPoints\":[{\"title\":\"登录失败提示\",\"expectedResult\":\"提示错误\",\"steps\":\"提交\"}]}",
                "node-model-handoff", 10, 10, null));
        jdbcTemplate.update("delete from ai_agent_critiques");
        jdbcTemplate.update("delete from ai_agent_handoffs");
        jdbcTemplate.update("delete from ai_agent_run_nodes");
        jdbcTemplate.update("delete from ai_agent_run_graphs");
        jdbcTemplate.update("delete from ai_system_configs");
        jdbcTemplate.update("delete from projects where code = 'AI_AGENT_HANDOFF'");

        Instant now = Instant.now();
        jdbcTemplate.update(
            "insert into projects(code, name, description, active, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
            "AI_AGENT_HANDOFF",
            "AI Agent Handoff Project",
            "Handoff tests",
            true,
            now,
            now
        );
        projectId = jdbcTemplate.queryForObject("select id from projects where code = 'AI_AGENT_HANDOFF'", Long.class);
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
    void requirementUnderstandingOutputIsHandedToTestDesign() throws Exception {
        String runId = createRun();

        mockMvc.perform(get("/api/ai/multi-agent-runs/{runId}/handoffs", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(4))
            .andExpect(jsonPath("$.items[0].fromAgentId").value("requirement-understanding"))
            .andExpect(jsonPath("$.items[0].toAgentId").value("test-design"))
            .andExpect(jsonPath("$.items[0].payloadJson.agentId").value("requirement-understanding"))
            .andExpect(jsonPath("$.items[0].payloadSummary").value("需求理解 Agent completed via RULE executor"));
    }

    @Test
    void qualityReviewCritiquesMissingSourceEvidenceAndRepairsOnce() throws Exception {
        String runId = createRun();

        mockMvc.perform(get("/api/ai/multi-agent-runs/{runId}/critiques", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].critiqueType").value("source_evidence_missing"))
            .andExpect(jsonPath("$.items[0].repairRequested").value(true))
            .andExpect(jsonPath("$.items[0].repairPass").value(1));

        mockMvc.perform(get("/api/ai/multi-agent-runs/{runId}/graph", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.nodes[1].agentId").value("test-design"))
            .andExpect(jsonPath("$.nodes[1].outputSnapshot.repairPass").value(1))
            .andExpect(jsonPath("$.nodes[1].outputSnapshot.sourceEvidence[0]").value("repair:source-evidence-required"));

        Integer critiqueCount = jdbcTemplate.queryForObject(
            "select count(*) from ai_agent_critiques where run_id = ?",
            Integer.class,
            runId
        );
        org.junit.jupiter.api.Assertions.assertEquals(1, critiqueCount);
    }

    @Test
    void interruptAndResumeRerunUnfinishedNodesFromCheckpoints() throws Exception {
        // 模型节点先失败 ⇒ 运行 failed（覆盖 @BeforeEach 的成功桩）。
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(false, "", "", 0, 0, "blocked", false, "no_route", 0));
        // 无模型成功响应 ⇒ 运行失败（test-design 节点失败，下游 skipped）。
        MvcResult first = mockMvc.perform(post("/api/ai/multi-agent-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"projectId": %d, "targetCaseKind": "interface"}
                    """.formatted(projectId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("failed"))
            .andReturn();
        String runId = objectMapper.readTree(first.getResponse().getContentAsString()).get("runId").asText();

        // 人工中断（失败后可标记），再恢复：已成功节点沿用，其余重跑。
        mockMvc.perform(post("/api/ai/multi-agent-runs/{runId}/interrupt", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("interrupted"));

        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, "[]", "node-model-resume", 10, 10, null));
        mockMvc.perform(post("/api/ai/multi-agent-runs/{runId}/resume", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("merge_ready"));

        mockMvc.perform(get("/api/ai/multi-agent-runs/{runId}/graph", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("merge_ready"))
            .andExpect(jsonPath("$.nodes[0].status").value("succeeded"))
            .andExpect(jsonPath("$.nodes[1].status").value("succeeded"))
            .andExpect(jsonPath("$.nodes[1].outputSnapshot.resumed").value(true))
            .andExpect(jsonPath("$.nodes[2].status").value("succeeded"))
            .andExpect(jsonPath("$.nodes[4].outputSnapshot.status").value("merge_ready"));
    }

    private String createRun() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/ai/multi-agent-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "projectId": %d,
                      "targetCaseKind": "web_automation",
                      "inputSummary": { "source": "prd-handoff" }
                    }
                    """.formatted(projectId)))
            .andExpect(status().isOk())
            .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString()).get("runId").asText();
    }
}
