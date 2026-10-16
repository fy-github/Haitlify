package com.haitlify.aitest.platform.ai.agent;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.haitlify.aitest.platform.ai.gateway.AiGateway;
import com.haitlify.aitest.platform.ai.gateway.AiGatewayConfig;
import com.haitlify.aitest.platform.ai.gateway.AiGenerationResult;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiAgentConsensusMergeServiceTest {

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
        jdbcTemplate.update("delete from ai_review_actions");
        jdbcTemplate.update("delete from ai_draft_test_points");
        jdbcTemplate.update("delete from ai_artifact_gate_audits");
        jdbcTemplate.update("delete from ai_coverage_summaries");
        jdbcTemplate.update("delete from ai_unclear_questions");
        jdbcTemplate.update("delete from ai_analysis_chunks");
        jdbcTemplate.update("delete from ai_call_records");
        jdbcTemplate.update("delete from ai_agent_consensus_records");
        jdbcTemplate.update("delete from ai_agent_conflicts");
        jdbcTemplate.update("delete from ai_agent_critiques");
        jdbcTemplate.update("delete from ai_agent_handoffs");
        jdbcTemplate.update("delete from ai_agent_run_nodes");
        jdbcTemplate.update("delete from ai_agent_run_graphs");
        jdbcTemplate.update("delete from ai_system_configs");
        jdbcTemplate.update("delete from ai_analysis_runs");
        jdbcTemplate.update("delete from projects where code = 'AI_AGENT_MERGE'");

        Instant now = Instant.now();
        jdbcTemplate.update(
            "insert into projects(code, name, description, active, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
            "AI_AGENT_MERGE",
            "AI Agent Merge Project",
            "Merge tests",
            true,
            now,
            now
        );
        projectId = jdbcTemplate.queryForObject("select id from projects where code = 'AI_AGENT_MERGE'", Long.class);
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
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, "{\"items\":[]}", "merge-empty", 0, 0, null));
    }

    @Test
    void conflictingPriorityAndExpectedResultCreateConflictRecordsAndBlockMerge() throws Exception {
        String runId = createRun();

        mockMvc.perform(post("/api/ai/multi-agent-runs/{runId}/merge", runId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "candidates": [
                        {
                          "title": "登录成功",
                          "targetKey": "login-submit",
                          "caseKind": "web_automation",
                          "priority": "P1",
                          "expectedResult": "进入首页",
                          "sourceEvidence": ["doc:1"]
                        },
                        {
                          "title": "登录成功优先级冲突",
                          "targetKey": "login-submit",
                          "caseKind": "web_automation",
                          "priority": "P2",
                          "expectedResult": "进入首页",
                          "sourceEvidence": ["doc:1"]
                        },
                        {
                          "title": "登录成功期望冲突",
                          "targetKey": "login-submit",
                          "caseKind": "web_automation",
                          "priority": "P1",
                          "expectedResult": "提示登录失败",
                          "sourceEvidence": ["doc:2"]
                        }
                      ]
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("blocked"))
            .andExpect(jsonPath("$.unresolvedConflictCount").value(2))
            .andExpect(jsonPath("$.createdDraftPointIds.length()").value(0));

        mockMvc.perform(get("/api/ai/multi-agent-runs/{runId}/conflicts", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].resolutionStatus").value("unresolved"));
    }

    @Test
    void duplicateTargetsMergeIntoOneReviewGatedDraftPoint() throws Exception {
        String runId = createRun();

        mockMvc.perform(post("/api/ai/multi-agent-runs/{runId}/merge", runId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "candidates": [
                        {
                          "title": "登录成功",
                          "targetKey": "login-submit",
                          "caseKind": "web_automation",
                          "priority": "P1",
                          "expectedResult": "进入首页",
                          "sourceEvidence": ["doc:1"]
                        },
                        {
                          "title": "登录成功",
                          "targetKey": "login-submit",
                          "caseKind": "web_automation",
                          "priority": "P1",
                          "expectedResult": "进入首页",
                          "sourceEvidence": ["doc:1"]
                        }
                      ]
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("merged"))
            .andExpect(jsonPath("$.mergedCount").value(1))
            .andExpect(jsonPath("$.createdDraftPointIds.length()").value(1));

        Integer draftCount = jdbcTemplate.queryForObject(
            "select count(*) from ai_draft_test_points where review_status = 'ai_generated'",
            Integer.class
        );
        org.junit.jupiter.api.Assertions.assertEquals(1, draftCount);
    }

    @Test
    void aligningSupplementGeneratesAndPersistsAiEnrichedTestPoint() throws Exception {
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, """
                {
                  "items": [
                    {
                      "title": "登录失败提示与重试限制",
                      "targetKey": "login-failure-limit",
                      "caseKind": "web_automation",
                      "priority": "P1",
                      "expectedResult": "连续失败后展示统一提示并限制再次提交",
                      "sourceEvidence": ["评审建议: 补充失败路径"],
                      "suggestedSteps": ["输入错误账号密码", "连续提交登录请求"]
                    }
                  ]
                }
                """, "merge-enriched", 70, 100, null));

        String runId = createRun();
        clearInvocations(aiGateway);
        mockMvc.perform(post("/api/ai/multi-agent-runs/{runId}/merge", runId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "candidates": [
                        {
                          "title": "补充登录失败场景",
                          "targetKey": "login-failure",
                          "caseKind": "web_automation",
                          "priority": "P2",
                          "expectedResult": "展示失败提示",
                          "sourceEvidence": ["review:1"]
                        }
                      ]
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("merged"))
            .andExpect(jsonPath("$.mergedCount").value(1));

        verify(aiGateway).structuredGenerate(any(AiGatewayConfig.class), any());
        Map<String, Object> persisted = jdbcTemplate.queryForMap(
            "select title, page_or_feature, priority_level, suggested_steps, expected_result from ai_draft_test_points where title = ?",
            "登录失败提示与重试限制"
        );
        org.assertj.core.api.Assertions.assertThat(persisted)
            .containsEntry("title", "登录失败提示与重试限制")
            .containsEntry("page_or_feature", "login-failure-limit")
            .containsEntry("priority_level", "P1")
            .containsEntry("suggested_steps", String.join("\n", "输入错误账号密码", "连续提交登录请求"))
            .containsEntry("expected_result", "连续失败后展示统一提示并限制再次提交");
    }

    @Test
    void mergeResponsePointIdsMatchPersistedRowsWithCompleteIdentity() throws Exception {
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, """
                {
                  "items": [
                    {
                      "title": "登录失败提示与重试限制",
                      "targetKey": "login-failure-limit",
                      "caseKind": "web_automation",
                      "priority": "P1",
                      "expectedResult": "连续失败后展示统一提示并限制再次提交",
                      "sourceEvidence": ["评审建议: 补充失败路径"],
                      "suggestedSteps": ["输入错误账号密码", "连续提交登录请求"]
                    }
                  ]
                }
                """, "merge-identity", 70, 100, null));

        String runId = createRun();
        String body = mockMvc.perform(post("/api/ai/multi-agent-runs/{runId}/merge", runId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "candidates": [
                        {
                          "title": "补充登录失败场景",
                          "targetKey": "login-failure",
                          "caseKind": "web_automation",
                          "priority": "P2",
                          "expectedResult": "展示失败提示",
                          "sourceEvidence": ["review:1"]
                        }
                      ]
                    }
                    """))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        String pointId = objectMapper.readTree(body).path("createdDraftPointIds").path(0).asText();
        org.assertj.core.api.Assertions.assertThat(pointId).isNotBlank();

        Map<String, Object> identity = jdbcTemplate.queryForMap(
            "select point_id, result_origin, acceptance_state, revision, idempotency_key from ai_draft_test_points where point_id = ?",
            pointId
        );
        org.assertj.core.api.Assertions.assertThat(identity)
            .containsEntry("point_id", pointId)
            .containsEntry("result_origin", "MODEL_GENERATED")
            .containsEntry("acceptance_state", "PENDING_HUMAN_CONFIRMATION")
            .containsEntry("revision", 1)
            .containsEntry("idempotency_key", "multi-agent:" + runId + ":" + pointId);

        Integer audits = jdbcTemplate.queryForObject(
            "select count(*) from ai_artifact_gate_audits where artifact_id = ? and decision = 'PENDING_HUMAN_CONFIRMATION'",
            Integer.class,
            pointId
        );
        org.junit.jupiter.api.Assertions.assertEquals(1, audits);
    }

    @Test
    void resolvedConflictAllowsMerge() throws Exception {
        String runId = createRun();
        String payload = """
            {
              "candidates": [
                {
                  "title": "导出报表",
                  "targetKey": "report-export",
                  "caseKind": "interface",
                  "priority": "P1",
                  "expectedResult": "导出成功",
                  "sourceEvidence": ["doc:1"]
                },
                {
                  "title": "导出报表冲突",
                  "targetKey": "report-export",
                  "caseKind": "interface",
                  "priority": "P2",
                  "expectedResult": "导出成功",
                  "sourceEvidence": ["doc:2"]
                }
              ]
            }
            """;

        mockMvc.perform(post("/api/ai/multi-agent-runs/{runId}/merge", runId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("blocked"));

        String conflictId = jdbcTemplate.queryForObject(
            "select conflict_id from ai_agent_conflicts where run_id = ?",
            String.class,
            runId
        );

        mockMvc.perform(post("/api/ai/agent-conflicts/{conflictId}/resolve", conflictId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    { "resolution": "保留 P1" }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.resolutionStatus").value("resolved"));

        mockMvc.perform(post("/api/ai/multi-agent-runs/{runId}/merge", runId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("merged"))
            .andExpect(jsonPath("$.createdDraftPointIds.length()").value(1));
    }

    @Test
    void mergeFallsBackToRuleOriginWhenAiEnrichmentIsUnavailable() throws Exception {
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenThrow(new IllegalStateException("AI 配置未完成"));

        String runId = createRun();
        mockMvc.perform(post("/api/ai/multi-agent-runs/{runId}/merge", runId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "candidates": [
                        {
                          "title": "补充登录失败场景",
                          "targetKey": "login-failure",
                          "caseKind": "web_automation",
                          "priority": "P2",
                          "expectedResult": "展示失败提示",
                          "sourceEvidence": ["review:1"]
                        }
                      ]
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("merged"))
            .andExpect(jsonPath("$.aiEnrichmentFallback").value(true));

        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForMap(
            "select result_origin, acceptance_state from ai_draft_test_points where review_status = 'ai_generated'"))
            .containsEntry("result_origin", "RULE_EXTRACTED")
            .containsEntry("acceptance_state", "PENDING_HUMAN_CONFIRMATION");
    }

    @Test
    void mergeWithoutAuditableProviderRequestIdDoesNotClaimModelGeneration() throws Exception {
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, """
                {
                  "items": [
                    {
                      "title": "登录失败提示与重试限制",
                      "targetKey": "login-failure-limit",
                      "caseKind": "web_automation",
                      "priority": "P1",
                      "expectedResult": "连续失败后展示统一提示并限制再次提交",
                      "sourceEvidence": ["评审建议: 补充失败路径"],
                      "suggestedSteps": ["输入错误账号密码", "连续提交登录请求"]
                    }
                  ]
                }
                """, "", 70, 100, null));

        String runId = createRun();
        mockMvc.perform(post("/api/ai/multi-agent-runs/{runId}/merge", runId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "candidates": [
                        {
                          "title": "补充登录失败场景",
                          "targetKey": "login-failure",
                          "caseKind": "web_automation",
                          "priority": "P2",
                          "expectedResult": "展示失败提示",
                          "sourceEvidence": ["review:1"]
                        }
                      ]
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("merged"));

        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForMap(
            "select result_origin, acceptance_state from ai_draft_test_points where review_status = 'ai_generated'"))
            .containsEntry("result_origin", "RULE_EXTRACTED")
            .containsEntry("acceptance_state", "PENDING_HUMAN_CONFIRMATION");
    }

    @Test
    void mergeRecordsModelGeneratedOriginOnlyWithProviderEvidence() throws Exception {
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, """
                {
                  "items": [
                    {
                      "title": "登录失败提示与重试限制",
                      "targetKey": "login-failure-limit",
                      "caseKind": "web_automation",
                      "priority": "P1",
                      "expectedResult": "连续失败后展示统一提示并限制再次提交",
                      "sourceEvidence": ["评审建议: 补充失败路径"],
                      "suggestedSteps": ["输入错误账号密码", "连续提交登录请求"]
                    }
                  ]
                }
                """, "merge-evidence-1", 70, 100, null));

        String runId = createRun();
        mockMvc.perform(post("/api/ai/multi-agent-runs/{runId}/merge", runId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "candidates": [
                        {
                          "title": "补充登录失败场景",
                          "targetKey": "login-failure",
                          "caseKind": "web_automation",
                          "priority": "P2",
                          "expectedResult": "展示失败提示",
                          "sourceEvidence": ["review:1"]
                        }
                      ]
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("merged"));

        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForMap(
            "select result_origin, acceptance_state from ai_draft_test_points where review_status = 'ai_generated'"))
            .containsEntry("result_origin", "MODEL_GENERATED")
            .containsEntry("acceptance_state", "PENDING_HUMAN_CONFIRMATION");
    }

    private String createRun() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/ai/multi-agent-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "projectId": %d,
                      "targetCaseKind": "web_automation",
                      "inputSummary": { "source": "prd-merge" }
                    }
                    """.formatted(projectId)))
            .andExpect(status().isOk())
            .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString()).get("runId").asText();
    }
}
