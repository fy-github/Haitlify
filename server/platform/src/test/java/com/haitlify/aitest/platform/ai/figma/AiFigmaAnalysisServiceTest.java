package com.haitlify.aitest.platform.ai.figma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiFigmaAnalysisServiceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
        jdbcTemplate.update("delete from projects where code = 'AI_FIGMA_ANALYSIS'");

        Instant now = Instant.now();
        jdbcTemplate.update(
            "insert into projects(code, name, description, active, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
            "AI_FIGMA_ANALYSIS",
            "AI Figma Analysis Project",
            "Figma analysis tests",
            true,
            now,
            now
        );
        projectId = jdbcTemplate.queryForObject("select id from projects where code = 'AI_FIGMA_ANALYSIS'", Long.class);
        jdbcTemplate.update("""
            insert into ai_figma_files(
              figma_file_id, project_id, file_key, selected_node_id, file_name, snapshot_hash,
              raw_summary_json, actor_id, actor_name, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "figma-file-analysis",
            projectId,
            "FileAnalysis123",
            "1:1",
            "Login Flow",
            "hash-analysis",
            "{}",
            "system-dev",
            "Development User",
            now,
            now
        );
        figmaFilePk = jdbcTemplate.queryForObject("select id from ai_figma_files where figma_file_id = ?", Long.class, "figma-file-analysis");
        insertNode("figma-node-frame", "1:1", "FRAME", "Login Page", "", "", "", "{}", "VERTICAL");
        insertNode("figma-node-button", "1:2", "INSTANCE", "Submit Button", "1:1", "button-primary", "Button / Primary", "{\"State\":{\"value\":\"Default\"}}", "");
        insertNode("figma-node-input", "1:3", "INSTANCE", "Email Input", "1:1", "input-email", "Input / Text Field", "{}", "");
        insertNode("figma-node-settings", "9:1", "FRAME", "Settings Page", "", "", "", "{}", "");
    }

    @Test
    void startingRunForFileCreatesDraftPointsWithFigmaEvidence() throws Exception {
        String response = mockMvc.perform(post("/api/ai/figma/files/figma-file-analysis/analysis-runs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("waiting_review"))
            .andExpect(jsonPath("$.resultOrigin").value("RULE_EXTRACTED"))
            .andExpect(jsonPath("$.acceptanceState").value("PENDING_HUMAN_CONFIRMATION"))
            .andExpect(jsonPath("$.draftTestPointCount").value(org.hamcrest.Matchers.greaterThan(0)))
            .andReturn()
            .getResponse()
            .getContentAsString();

        String runId = new tools.jackson.databind.ObjectMapper().readTree(response).path("runId").asText();
        mockMvc.perform(get("/api/ai/figma/analysis-runs/{runId}/draft-test-points", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].resultOrigin").value("RULE_EXTRACTED"))
            .andExpect(jsonPath("$.items[0].acceptanceState").value("PENDING_HUMAN_CONFIRMATION"))
            .andExpect(jsonPath("$.items[0].metadata.evidence.sourceType").value("figma"))
            .andExpect(jsonPath("$.items[0].metadata.evidence.fileKey").value("FileAnalysis123"));
    }

    @Test
    void longNodeNameKeepsCandidateAndGateKeysWithinColumnWidth() throws Exception {
        // 与 draft-merge 同类：figma 候选键 = "figma:" + runPk + ":" + 节点名（模型可见文本）。
        // 超长节点名直拼会顶爆 candidate_id/artifact_id 的 varchar(128)，整个分析直接失败。
        jdbcTemplate.update("update ai_figma_nodes set node_name = ? where figma_node_id = ?",
            "超长按钮名称".repeat(25), "figma-node-button");

        mockMvc.perform(post("/api/ai/figma/files/figma-file-analysis/analysis-runs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.draftTestPointCount").value(org.hamcrest.Matchers.greaterThan(0)));

        java.util.List<String> artifactIds = jdbcTemplate.queryForList(
            "select artifact_id from ai_artifact_gate_audits order by id desc", String.class);
        assertThat(artifactIds).isNotEmpty();
        for (String artifactId : artifactIds) {
            assertThat(artifactId.length()).isLessThanOrEqualTo(128);
        }
        java.util.List<String> pointKeys = jdbcTemplate.queryForList(
            "select idempotency_key from ai_draft_test_points order by id desc", String.class);
        assertThat(pointKeys).isNotEmpty();
        for (String pointKey : pointKeys) {
            assertThat(pointKey.length()).isLessThanOrEqualTo(255);
        }
    }

    @Test
    void startingRunForNodeScopesDraftPointsToSelectedSubtree() throws Exception {
        String response = mockMvc.perform(post("/api/ai/figma/nodes/figma-node-button/analysis-runs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("waiting_review"))
            .andExpect(jsonPath("$.resultOrigin").value("RULE_EXTRACTED"))
            .andExpect(jsonPath("$.acceptanceState").value("PENDING_HUMAN_CONFIRMATION"))
            .andReturn()
            .getResponse()
            .getContentAsString();

        String runId = new tools.jackson.databind.ObjectMapper().readTree(response).path("runId").asText();
        mockMvc.perform(get("/api/ai/figma/analysis-runs/{runId}/draft-test-points", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].metadata.evidence.nodeId").value("1:2"));
    }

    @Test
    void unreviewedFigmaDraftPointsCannotGenerateFormalCases() throws Exception {
        String response = mockMvc.perform(post("/api/ai/figma/files/figma-file-analysis/analysis-runs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        String runId = new tools.jackson.databind.ObjectMapper().readTree(response).path("runId").asText();
        String pointId = jdbcTemplate.queryForObject("""
            select p.point_id
            from ai_draft_test_points p
            join ai_analysis_runs r on r.id = p.run_id
            where r.run_id = ?
            order by p.id
            limit 1
            """, String.class, runId);

        mockMvc.perform(post("/api/ai/draft-test-points/generate-cases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pointIds\": [\"" + pointId + "\"]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("confirmed/modified")));
    }

    private void insertNode(
        String figmaNodeId,
        String nodeId,
        String nodeType,
        String nodeName,
        String parentNodeId,
        String componentKey,
        String componentName,
        String variantPropertiesJson,
        String layoutMode
    ) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
            insert into ai_figma_nodes(
              figma_node_id, figma_file_pk, node_id, node_type, node_name, parent_node_id,
              component_key, component_name, variant_properties_json, style_json, token_json,
              layout_mode, constraints_json, absolute_bounds_json, prototype_link_json,
              source_snapshot_hash, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            figmaNodeId,
            figmaFilePk,
            nodeId,
            nodeType,
            nodeName,
            parentNodeId,
            componentKey,
            componentName,
            variantPropertiesJson,
            "{}",
            "{}",
            layoutMode,
            "{}",
            "{}",
            "{}",
            "hash-analysis",
            now,
            now
        );
    }
}
