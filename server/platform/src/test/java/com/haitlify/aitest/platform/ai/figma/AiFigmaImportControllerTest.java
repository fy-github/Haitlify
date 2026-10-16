package com.haitlify.aitest.platform.ai.figma;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

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

import com.haitlify.aitest.platform.document.FigmaDesignClient;
import com.haitlify.aitest.platform.document.FigmaDesignParseResult;
import com.haitlify.aitest.platform.document.RequirementVisualSummary;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiFigmaImportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private FigmaDesignClient figmaDesignClient;

    private long projectId;

    @BeforeEach
    void resetData() {
        jdbcTemplate.update("delete from ai_figma_element_candidates");
        jdbcTemplate.update("delete from ai_figma_change_sets");
        jdbcTemplate.update("delete from ai_figma_nodes");
        jdbcTemplate.update("delete from ai_figma_files");
        jdbcTemplate.update("delete from ai_system_configs");
        jdbcTemplate.update("delete from projects where code = 'AI_FIGMA'");
        Instant now = Instant.now();
        jdbcTemplate.update(
            "insert into projects(code, name, description, active, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
            "AI_FIGMA",
            "AI Figma Project",
            "Figma import tests",
            true,
            now,
            now
        );
        projectId = jdbcTemplate.queryForObject("select id from projects where code = 'AI_FIGMA'", Long.class);
        insertFigmaConfig(true);
    }

    @Test
    void importParsesFileKeyAndNodeIdFromUrl() throws Exception {
        mockFigma("AbCdEf123456", "12:34", "Login Page");

        mockMvc.perform(post("/api/ai/figma/import")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "projectId": %d,
                      "figmaUrl": "https://www.figma.com/design/AbCdEf123456/Login-Page?node-id=12-34&m=dev"
                    }
                    """.formatted(projectId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.figmaFileId").isNotEmpty())
            .andExpect(jsonPath("$.projectId").value(projectId))
            .andExpect(jsonPath("$.fileKey").value("AbCdEf123456"))
            .andExpect(jsonPath("$.selectedNodeId").value("12:34"))
            .andExpect(jsonPath("$.fileName").value("Login Page"))
            .andExpect(jsonPath("$.snapshotHash").isNotEmpty())
            .andExpect(jsonPath("$.actorId").value("system"));
    }

    @Test
    void disabledFigmaConfigReturnsControlledError() throws Exception {
        jdbcTemplate.update("delete from ai_system_configs");
        insertFigmaConfig(false);

        mockMvc.perform(post("/api/ai/figma/import")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "projectId": %d,
                      "fileKey": "AbCdEf123456",
                      "nodeId": "12:34"
                    }
                    """.formatted(projectId)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Figma import is disabled"));
    }

    @Test
    void importStoresExplicitFileKeyAndNodeId() throws Exception {
        mockFigma("AbCdEf123456", "12:34", "Login Page");

        mockMvc.perform(post("/api/ai/figma/import")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "projectId": %d,
                      "fileKey": "AbCdEf123456",
                      "nodeId": "12:34"
                    }
                    """.formatted(projectId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fileKey").value("AbCdEf123456"))
            .andExpect(jsonPath("$.selectedNodeId").value("12:34"));

        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from ai_figma_files where file_key = ? and selected_node_id = ?",
            Integer.class,
            "AbCdEf123456",
            "12:34"
        );
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(1);
    }

    @Test
    void repeatedImportUpdatesExistingFileNodeSnapshot() throws Exception {
        mockFigma("AbCdEf123456", "12:34", "Login Page v1");
        String body = """
            {
              "projectId": %d,
              "fileKey": "AbCdEf123456",
              "nodeId": "12:34"
            }
            """.formatted(projectId);

        mockMvc.perform(post("/api/ai/figma/import").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fileName").value("Login Page v1"));

        mockFigma("AbCdEf123456", "12:34", "Login Page v2");
        mockMvc.perform(post("/api/ai/figma/import").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fileName").value("Login Page v2"));

        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from ai_figma_files where project_id = ? and file_key = ? and selected_node_id = ?",
            Integer.class,
            projectId,
            "AbCdEf123456",
            "12:34"
        );
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(1);
    }

    private void mockFigma(String fileKey, String nodeId, String fileName) {
        when(figmaDesignClient.fetchDesign(eq(fileKey), eq(nodeId))).thenReturn(new FigmaDesignParseResult(
            fileName,
            fileName,
            "Figma File Key: " + fileKey + "\nFigma Node ID: " + nodeId,
            new RequirementVisualSummary(1440, 900, 1.6D, "desktop", List.of("Auto Layout"), List.of("Color token"), List.of("Button"), "available"),
            List.of("按钮状态与设计稿一致")
        ));
    }

    private void insertFigmaConfig(boolean enabled) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
            insert into ai_system_configs(
              config_key, enabled, provider_type, base_url, text_model, vision_model, embedding_model,
              api_key_ciphertext, credential_configured, request_timeout_ms, max_input_length,
              max_output_length, retry_count, request_logging_enabled, external_document_send_allowed,
              provider_config_json, masked_config_json, vision_enabled, max_image_bytes, allowed_image_types_json,
              actor_id, actor_name, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "default",
            true,
            "openai-compatible",
            "https://api.example.com/v1",
            "gpt-text",
            "gpt-vision",
            "",
            "sk-test",
            true,
            30000,
            0,
            0,
            0,
            false,
            false,
            "{\"figmaEnabled\":" + enabled + ",\"figmaTokenConfigured\":true}",
            "****test",
            true,
            4096,
            "[\"image/png\"]",
            "system-dev",
            "Development User",
            now,
            now
        );
    }


    @Test
    void candidateReviewEndpointHonoursRevisionAndActorContract() throws Exception {
        mockFigma("ReviewKey01", "12:90", "Review Page");
        mockMvc.perform(post("/api/ai/figma/import")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileKey\":\"ReviewKey01\",\"nodeId\":\"12:90\",\"projectId\":" + projectId + "}"))
            .andExpect(status().isOk());

        String figmaFileId = jdbcTemplate.queryForObject(
            "select figma_file_id from ai_figma_files where file_key = 'ReviewKey01'", String.class);
        Long figmaFilePk = jdbcTemplate.queryForObject(
            "select id from ai_figma_files where file_key = 'ReviewKey01'", Long.class);
        java.time.Instant seedNow = java.time.Instant.now();
        jdbcTemplate.update("""
            insert into ai_figma_nodes(
              figma_node_id, figma_file_pk, node_id, node_type, node_name, parent_node_id,
              component_key, component_name, variant_properties_json, style_json, token_json,
              layout_mode, constraints_json, absolute_bounds_json, prototype_link_json,
              source_snapshot_hash, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "figma-node-12-91", figmaFilePk, "12:91", "INSTANCE", "确认按钮", "1:0",
            "button-primary", "主要按钮", "{}", "{}", "{}", "", "{}", "{}", "{}", "hash-review",
            seedNow, seedNow);
        mockMvc.perform(get("/api/ai/figma/files/" + figmaFileId + "/element-candidates"))
            .andExpect(status().isOk());
        String candidateId = jdbcTemplate.queryForObject(
            "select candidate_id from ai_figma_element_candidates where figma_file_pk ="
                + " (select id from ai_figma_files where file_key = 'ReviewKey01') fetch first 1 rows only",
            String.class);

        // 过期 revision ⇒ 409 stale_revision（与全平台同形）；以真实登录用户发起
        mockMvc.perform(post("/api/ai/figma/element-candidates/" + candidateId + "/confirm")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("figma-endpoint-editor"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"revision\":99}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("stale_revision"));

        // 正确 revision ⇒ 200 且记录真实 actor
        mockMvc.perform(post("/api/ai/figma/element-candidates/" + candidateId + "/confirm")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("figma-endpoint-editor"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"revision\":1}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("confirmed"))
            .andExpect(jsonPath("$.reviewedBy").value("figma-endpoint-editor"));

        // 重复确认幂等 ⇒ 仍 200 confirmed
        mockMvc.perform(post("/api/ai/figma/element-candidates/" + candidateId + "/confirm")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("figma-endpoint-editor")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("confirmed"));
    }

}
