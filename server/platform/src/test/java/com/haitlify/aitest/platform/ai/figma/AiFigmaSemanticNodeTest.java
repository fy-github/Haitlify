package com.haitlify.aitest.platform.ai.figma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.haitlify.aitest.platform.ai.gateway.AiGenerationResult;
import com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade;

import tools.jackson.databind.ObjectMapper;

/**
 * T08 bullet 4：Figma 语义分析为单独的真实模型节点——经统一 Runtime facade 调用
 * （taskType=figma_semantic_analysis），成功输出 ⇒ MODEL_GENERATED + 人审门禁；
 * 失败/无通道 ⇒ 如实 BLOCKED，零假候选；不同 nodeIds/快照 ⇒ 版本指纹不同、绝不混用。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiFigmaSemanticNodeTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AiTaskExecutionFacade taskExecutionFacade;

    private long projectId;
    private long figmaFilePk;

    @BeforeEach
    void resetData() {
        jdbcTemplate.update("delete from ai_review_actions");
        jdbcTemplate.update("delete from ai_draft_test_points");
        jdbcTemplate.update("delete from ai_call_records");
        jdbcTemplate.update("delete from ai_artifact_gate_audits");
        jdbcTemplate.update("delete from ai_coverage_summaries");
        jdbcTemplate.update("delete from ai_unclear_questions");
        jdbcTemplate.update("delete from ai_analysis_chunks");
        jdbcTemplate.update("delete from ai_analysis_runs");
        jdbcTemplate.update("delete from ai_figma_element_candidates");
        jdbcTemplate.update("delete from ai_figma_change_sets");
        jdbcTemplate.update("delete from ai_figma_nodes");
        jdbcTemplate.update("delete from ai_figma_files");
        jdbcTemplate.update("delete from projects where code = 'AI_FIGMA_SEMANTIC'");

        Instant now = Instant.now();
        jdbcTemplate.update(
            "insert into projects(code, name, description, active, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
            "AI_FIGMA_SEMANTIC", "AI Figma Semantic Project", "semantic node tests", true, now, now);
        projectId = jdbcTemplate.queryForObject("select id from projects where code = 'AI_FIGMA_SEMANTIC'", Long.class);
        jdbcTemplate.update("""
            insert into ai_figma_files(
              figma_file_id, project_id, file_key, selected_node_id, file_name, snapshot_hash,
              raw_summary_json, actor_id, actor_name, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "figma-file-semantic", projectId, "SemanticKey456", "1:1", "Login Flow", "hash-semantic-1",
            "{}", "system-dev", "Development User", now, now);
        figmaFilePk = jdbcTemplate.queryForObject(
            "select id from ai_figma_files where figma_file_id = ?", Long.class, "figma-file-semantic");
        insertNode("figma-sem-node-frame", "1:1", "FRAME", "Login Page", "", "", "", "{}", "VERTICAL");
        insertNode("figma-sem-node-button", "1:2", "INSTANCE", "Submit Button", "1:1", "button-primary",
            "Button / Primary", "{}", "");
    }

    private void insertNode(String figmaNodeId, String nodeId, String nodeType, String nodeName,
                            String parentNodeId, String componentKey, String componentName,
                            String variantJson, String layoutMode) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
            insert into ai_figma_nodes(
              figma_node_id, figma_file_pk, node_id, node_type, node_name, parent_node_id,
              component_key, component_name, variant_properties_json, style_json, token_json,
              layout_mode, constraints_json, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            figmaNodeId, figmaFilePk, nodeId, nodeType, nodeName, parentNodeId,
            componentKey, componentName, variantJson, "{}", "{}", layoutMode, "{}", now, now);
    }

    private String startFileRun() throws Exception {
        String response = mockMvc.perform(post("/api/ai/figma/files/figma-file-semantic/analysis-runs"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(response).path("runId").asText();
    }

    private List<Map<String, Object>> draftPoints(String runId) throws Exception {
        String response = mockMvc.perform(get("/api/ai/figma/analysis-runs/{runId}/draft-test-points", runId))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        var items = new ObjectMapper().readTree(response).path("items");
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (var item : items) {
            rows.add(Map.of(
                "resultOrigin", item.path("resultOrigin").asText(""),
                "acceptanceState", item.path("acceptanceState").asText(""),
                "versionHash", item.path("metadata").path("figmaVersionHash").asText(""),
                "nodeId", item.path("metadata").path("evidence").path("nodeId").asText("")));
        }
        return rows;
    }

    @Test
    void semanticSuccessInsertsModelGeneratedCandidatesBehindHumanGate() throws Exception {
        when(taskExecutionFacade.generate(any())).thenReturn(new AiGenerationResult(
            true,
            "[{\"title\":\"登录页提交按钮默认态校验\",\"module\":\"认证\",\"testType\":\"ui\","
                + "\"objective\":\"验证默认态\",\"steps\":\"打开登录页\",\"expectedResult\":\"按钮可点击\",\"nodeId\":\"1:2\"}]",
            "req-sem-1", 12, 24, "", false, "", 0));

        String runId = startFileRun();

        ArgumentCaptor<AiTaskExecutionFacade.TaskExecution> captor =
            ArgumentCaptor.forClass(AiTaskExecutionFacade.TaskExecution.class);
        verify(taskExecutionFacade).generate(captor.capture());
        assertThat(captor.getValue().taskType()).isEqualTo("figma_semantic_analysis");
        assertThat(captor.getValue().projectId()).isEqualTo(projectId);

        List<Map<String, Object>> points = draftPoints(runId);
        List<Map<String, Object>> semantic = points.stream()
            .filter(point -> "MODEL_GENERATED".equals(point.get("resultOrigin")))
            .toList();
        assertThat(semantic).hasSize(1);
        assertThat(semantic.get(0).get("acceptanceState")).isEqualTo("PENDING_HUMAN_CONFIRMATION");
        assertThat(semantic.get(0).get("nodeId")).isEqualTo("1:2");
        assertThat((String) semantic.get(0).get("versionHash")).isNotBlank();
        // 规则候选与模型候选同运行共存，但各自携带来源；模型候选绝不自动接受。
        assertThat(points).anySatisfy(point -> assertThat(point.get("resultOrigin")).isEqualTo("RULE_EXTRACTED"));
        assertThat(points).allSatisfy(point ->
            assertThat(point.get("acceptanceState")).isEqualTo("PENDING_HUMAN_CONFIRMATION"));

        String summary = jdbcTemplate.queryForObject(
            "select output_summary_json from ai_analysis_runs where run_id = ?", String.class, runId);
        assertThat(summary).contains("COMPLETED").contains("figmaVersionHash");
    }

    @Test
    void semanticFailureIsRecordedAsBlockedWithZeroFakeCandidates() throws Exception {
        when(taskExecutionFacade.generate(any())).thenReturn(new AiGenerationResult(
            false, "", "", 0, 0, "无可用通道", false, "credential_unresolved", 0));

        String runId = startFileRun();

        List<Map<String, Object>> points = draftPoints(runId);
        assertThat(points).isNotEmpty();
        assertThat(points).noneSatisfy(point ->
            assertThat(point.get("resultOrigin")).isEqualTo("MODEL_GENERATED"));

        String summary = jdbcTemplate.queryForObject(
            "select output_summary_json from ai_analysis_runs where run_id = ?", String.class, runId);
        assertThat(summary).contains("BLOCKED").contains("credential_unresolved");
    }

    @Test
    void differentNodeSetsProduceDifferentVersionHashes() throws Exception {
        when(taskExecutionFacade.generate(any())).thenReturn(new AiGenerationResult(
            false, "", "", 0, 0, "blocked", false, "no_route", 0));

        String fileRunId = startFileRun();
        List<Map<String, Object>> filePoints = draftPoints(fileRunId);
        String fileHash = (String) filePoints.get(0).get("versionHash");
        assertThat(filePoints).allSatisfy(point ->
            assertThat(point.get("versionHash")).isEqualTo(fileHash));

        String response = mockMvc.perform(post("/api/ai/figma/nodes/figma-sem-node-button/analysis-runs"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String nodeRunId = new ObjectMapper().readTree(response).path("runId").asText();
        List<Map<String, Object>> nodePoints = draftPoints(nodeRunId);
        String nodeHash = (String) nodePoints.get(0).get("versionHash");

        // 不同快照/节点集合 ⇒ 指纹必须不同：跨版本候选不得混用。
        assertThat(nodeHash).isNotBlank().isNotEqualTo(fileHash);
    }
}
