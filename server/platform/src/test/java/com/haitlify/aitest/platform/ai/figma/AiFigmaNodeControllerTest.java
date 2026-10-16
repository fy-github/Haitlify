package com.haitlify.aitest.platform.ai.figma;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class AiFigmaNodeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long figmaFilePk;

    @BeforeEach
    void resetData() {
        jdbcTemplate.update("delete from ai_figma_element_candidates");
        jdbcTemplate.update("delete from ai_figma_change_sets");
        jdbcTemplate.update("delete from ai_figma_nodes");
        jdbcTemplate.update("delete from ai_figma_files");
        jdbcTemplate.update("delete from projects where code = 'AI_FIGMA_NODE_API'");
        Instant now = Instant.now();
        jdbcTemplate.update(
            "insert into projects(code, name, description, active, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
            "AI_FIGMA_NODE_API",
            "AI Figma Node API Project",
            "Figma node API tests",
            true,
            now,
            now
        );
        Long projectId = jdbcTemplate.queryForObject("select id from projects where code = 'AI_FIGMA_NODE_API'", Long.class);
        jdbcTemplate.update("""
            insert into ai_figma_files(
              figma_file_id, project_id, file_key, selected_node_id, file_name, snapshot_hash,
              raw_summary_json, actor_id, actor_name, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "figma-file-api-test",
            projectId,
            "FileApi123",
            "1:2",
            "Login Flow",
            "hash-api",
            "{}",
            "system-dev",
            "Development User",
            now,
            now
        );
        figmaFilePk = jdbcTemplate.queryForObject("select id from ai_figma_files where figma_file_id = ?", Long.class, "figma-file-api-test");
        insertNode("figma-node-frame", "1:2", "FRAME", "Login Frame", "", "", "", "VERTICAL", "{\"width\":1440}", "{}");
        insertNode("figma-node-button", "1:3", "INSTANCE", "Primary Button", "1:2", "button-primary", "Button / Primary", "", "{\"width\":240}", "{\"State\":{\"value\":\"Default\"}}");
    }

    @Test
    void listsNodesForImportedFigmaFile() throws Exception {
        mockMvc.perform(get("/api/ai/figma/files/figma-file-api-test/nodes"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].nodeId").value("1:2"))
            .andExpect(jsonPath("$.items[0].nodeType").value("FRAME"))
            .andExpect(jsonPath("$.items[1].parentNodeId").value("1:2"));
    }

    @Test
    void getsNodeByStableFigmaNodeId() throws Exception {
        mockMvc.perform(get("/api/ai/figma/nodes/figma-node-button"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.nodeId").value("1:3"))
            .andExpect(jsonPath("$.componentKey").value("button-primary"))
            .andExpect(jsonPath("$.componentName").value("Button / Primary"))
            .andExpect(jsonPath("$.variantProperties.State.value").value("Default"));
    }

    private void insertNode(
        String figmaNodeId,
        String nodeId,
        String nodeType,
        String nodeName,
        String parentNodeId,
        String componentKey,
        String componentName,
        String layoutMode,
        String boundsJson,
        String variantPropertiesJson
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
            boundsJson,
            "{}",
            "hash-api",
            now,
            now
        );
    }
}
