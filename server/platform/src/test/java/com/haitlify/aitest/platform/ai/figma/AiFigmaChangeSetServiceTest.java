package com.haitlify.aitest.platform.ai.figma;

import static org.assertj.core.api.Assertions.assertThat;
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

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiFigmaChangeSetServiceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private long projectId;
    private long figmaFilePk;

    @BeforeEach
    void resetData() {
        jdbcTemplate.update("delete from ai_figma_element_candidates");
        jdbcTemplate.update("delete from ai_figma_change_sets");
        jdbcTemplate.update("delete from ai_figma_nodes");
        jdbcTemplate.update("delete from ai_figma_files");
        jdbcTemplate.update("delete from projects where code = 'AI_FIGMA_CHANGE'");

        Instant now = Instant.now();
        jdbcTemplate.update(
            "insert into projects(code, name, description, active, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
            "AI_FIGMA_CHANGE",
            "AI Figma Change Project",
            "Figma change tests",
            true,
            now,
            now
        );
        projectId = jdbcTemplate.queryForObject("select id from projects where code = 'AI_FIGMA_CHANGE'", Long.class);
        jdbcTemplate.update("""
            insert into ai_figma_files(
              figma_file_id, project_id, file_key, selected_node_id, file_name, snapshot_hash,
              raw_summary_json, actor_id, actor_name, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "figma-file-change",
            projectId,
            "FileChange123",
            "1:1",
            "Settings Flow",
            "hash-change",
            "{}",
            "system-dev",
            "Development User",
            now,
            now
        );
        figmaFilePk = jdbcTemplate.queryForObject("select id from ai_figma_files where figma_file_id = ?", Long.class, "figma-file-change");
        insertNode("1:1", "FRAME", "Settings Page", "", "", "{}", "{\"width\":1440}", "{}");
        insertNode("1:2", "INSTANCE", "Removed Button", "1:1", "button-removed", "{}", "{\"width\":120}", "{}");
        insertNode("1:3", "INSTANCE", "Styled Card", "1:1", "card", "{\"fill\":\"#111111\"}", "{\"width\":320}", "{}");
        insertNode("1:4", "INSTANCE", "Next Button", "1:1", "button-next", "{}", "{\"width\":160}", "{\"target\":\"2:1\"}");
    }

    @Test
    void refreshDetectsAddedRemovedAndChangedNodes() throws Exception {
        String response = mockMvc.perform(post("/api/ai/figma/files/figma-file-change/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "nodes": [
                        {"nodeId": "1:1", "nodeType": "FRAME", "nodeName": "Settings Page", "parentNodeId": "", "componentKey": "", "styleJson": "{}", "absoluteBoundsJson": "{\\"width\\":1440}", "prototypeLinkJson": "{}", "childrenIds": ["1:3", "1:4", "1:5"]},
                        {"nodeId": "1:3", "nodeType": "INSTANCE", "nodeName": "Styled Card", "parentNodeId": "1:1", "componentKey": "card", "styleJson": "{\\"fill\\":\\"#222222\\"}", "absoluteBoundsJson": "{\\"width\\":320}", "prototypeLinkJson": "{}", "childrenIds": []},
                        {"nodeId": "1:4", "nodeType": "INSTANCE", "nodeName": "Next Button", "parentNodeId": "1:1", "componentKey": "button-next", "styleJson": "{}", "absoluteBoundsJson": "{\\"width\\":160}", "prototypeLinkJson": "{\\"target\\":\\"2:2\\"}", "childrenIds": []},
                        {"nodeId": "1:5", "nodeType": "INSTANCE", "nodeName": "New Toggle", "parentNodeId": "1:1", "componentKey": "toggle", "styleJson": "{}", "absoluteBoundsJson": "{\\"width\\":80}", "prototypeLinkJson": "{}", "childrenIds": []}
                      ]
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("created"))
            .andExpect(jsonPath("$.changes.length()").value(5))
            .andReturn()
            .getResponse()
            .getContentAsString();

        var changeSet = objectMapper.readTree(response);
        assertThat(changeSet.path("changes").findValuesAsString("changeType"))
            .contains("added", "removed", "changed");
        assertThat(changeSet.path("changes").toString()).contains("1:5", "1:2", "style", "prototype", "children");
    }

    @Test
    void listsAndGetsChangeSet() throws Exception {
        String response = mockMvc.perform(post("/api/ai/figma/files/figma-file-change/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"nodes": [
                      {"nodeId": "1:1", "nodeType": "FRAME", "nodeName": "Settings Page Renamed", "parentNodeId": "", "componentKey": "", "styleJson": "{}", "absoluteBoundsJson": "{\\"width\\":1440}", "prototypeLinkJson": "{}", "childrenIds": []}
                    ]}
                    """))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        String changeSetId = objectMapper.readTree(response).path("changeSetId").asText();

        mockMvc.perform(get("/api/ai/figma/files/figma-file-change/change-sets"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].changeSetId").value(changeSetId));
        mockMvc.perform(get("/api/ai/figma/change-sets/{changeSetId}", changeSetId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.changeSetId").value(changeSetId))
            .andExpect(jsonPath("$.changes.length()").value(4));
    }

    private void insertNode(
        String nodeId,
        String nodeType,
        String nodeName,
        String parentNodeId,
        String componentKey,
        String styleJson,
        String absoluteBoundsJson,
        String prototypeLinkJson
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
            "figma-node-" + nodeId.replace(":", "-"),
            figmaFilePk,
            nodeId,
            nodeType,
            nodeName,
            parentNodeId,
            componentKey,
            componentKey,
            "{}",
            styleJson,
            "{}",
            "",
            "{}",
            absoluteBoundsJson,
            prototypeLinkJson,
            "hash-change",
            now,
            now
        );
    }
}
