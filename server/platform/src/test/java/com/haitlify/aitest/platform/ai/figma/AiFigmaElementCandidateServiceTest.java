package com.haitlify.aitest.platform.ai.figma;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiFigmaElementCandidateServiceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long figmaFilePk;

    @BeforeEach
    void resetData() {
        jdbcTemplate.update("delete from ai_figma_element_candidates");
        jdbcTemplate.update("delete from ai_figma_change_sets");
        jdbcTemplate.update("delete from ai_figma_nodes");
        jdbcTemplate.update("delete from ai_figma_files");
        jdbcTemplate.update("delete from projects where code = 'AI_FIGMA_CANDIDATE'");

        Instant now = Instant.now();
        jdbcTemplate.update(
            "insert into projects(code, name, description, active, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
            "AI_FIGMA_CANDIDATE",
            "AI Figma Candidate Project",
            "Figma element candidate tests",
            true,
            now,
            now
        );
        projectId = jdbcTemplate.queryForObject("select id from projects where code = 'AI_FIGMA_CANDIDATE'", Long.class);
        jdbcTemplate.update("""
            insert into ai_figma_files(
              figma_file_id, project_id, file_key, selected_node_id, file_name, snapshot_hash,
              raw_summary_json, actor_id, actor_name, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "figma-file-candidate",
            projectId,
            "FileCandidate123",
            "1:1",
            "Login Flow",
            "hash-candidate",
            "{}",
            "system-dev",
            "Development User",
            now,
            now
        );
        figmaFilePk = jdbcTemplate.queryForObject("select id from ai_figma_files where figma_file_id = ?", Long.class, "figma-file-candidate");
        insertNode("1:1", "Submit Button", "button-primary", "Button / Primary");
        insertNode("1:2", "Submit Button Mobile", "button-primary", "Button / Primary");
        insertNode("1:3", "Email Input", "input-email", "Input / Text Field");
        insertNode("1:4", "Marketing Card", "card-promo", "Card / Promo");
    }

    @Test
    void listsButtonAndInputCandidatesAndMergesDuplicates() throws Exception {
        mockMvc.perform(get("/api/ai/figma/files/figma-file-candidate/element-candidates"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].role").value("button"))
            .andExpect(jsonPath("$.items[0].componentKey").value("button-primary"))
            .andExpect(jsonPath("$.items[0].suggestedLocatorName").value("button-primary"))
            .andExpect(jsonPath("$.items[0].status").value("pending"))
            .andExpect(jsonPath("$.items[1].role").value("textbox"));

        Integer buttonCandidates = jdbcTemplate.queryForObject(
            "select count(*) from ai_figma_element_candidates where component_key = ? and role = ?",
            Integer.class,
            "button-primary",
            "button"
        );
        org.assertj.core.api.Assertions.assertThat(buttonCandidates).isEqualTo(1);
    }

    @Test
    void confirmAndRejectCandidateChangesStatus() throws Exception {
        mockMvc.perform(get("/api/ai/figma/files/figma-file-candidate/element-candidates"))
            .andExpect(status().isOk());
        String buttonCandidateId = jdbcTemplate.queryForObject(
            "select candidate_id from ai_figma_element_candidates where role = 'button'",
            String.class
        );
        String inputCandidateId = jdbcTemplate.queryForObject(
            "select candidate_id from ai_figma_element_candidates where role = 'textbox'",
            String.class
        );

        mockMvc.perform(post("/api/ai/figma/element-candidates/{candidateId}/confirm", buttonCandidateId)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("figma-reviewer")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("confirmed"))
            .andExpect(jsonPath("$.reviewedBy").value("figma-reviewer"));
        mockMvc.perform(post("/api/ai/figma/element-candidates/{candidateId}/reject", inputCandidateId)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("figma-reviewer")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("rejected"));
    }

    private void insertNode(String nodeId, String nodeName, String componentKey, String componentName) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
            insert into ai_figma_nodes(
              figma_node_id, figma_file_pk, node_id, node_type, node_name, parent_node_id,
              component_key, component_name, variant_properties_json, style_json, token_json,
              layout_mode, constraints_json, absolute_bounds_json, prototype_link_json,
              source_snapshot_hash, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "figma-node-" + nodeId.replace(":", "-"),
            figmaFilePk,
            nodeId,
            "INSTANCE",
            nodeName,
            "1:0",
            componentKey,
            componentName,
            "{}",
            "{}",
            "{}",
            "",
            "{}",
            "{}",
            "{}",
            "hash-candidate",
            now,
            now
        );
    }
}
