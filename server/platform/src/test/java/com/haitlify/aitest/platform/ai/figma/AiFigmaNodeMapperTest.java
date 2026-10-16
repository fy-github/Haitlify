package com.haitlify.aitest.platform.ai.figma;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
class AiFigmaNodeMapperTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AiFigmaNodeRepository repository;

    private AiFigmaNodeMapper mapper;
    private long figmaFilePk;

    @BeforeEach
    void resetData() {
        mapper = new AiFigmaNodeMapper(objectMapper);
        jdbcTemplate.update("delete from ai_figma_element_candidates");
        jdbcTemplate.update("delete from ai_figma_change_sets");
        jdbcTemplate.update("delete from ai_figma_nodes");
        jdbcTemplate.update("delete from ai_figma_files");
        jdbcTemplate.update("delete from projects where code = 'AI_FIGMA_NODES'");
        Instant now = Instant.now();
        jdbcTemplate.update(
            "insert into projects(code, name, description, active, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
            "AI_FIGMA_NODES",
            "AI Figma Nodes Project",
            "Figma node mapper tests",
            true,
            now,
            now
        );
        Long projectId = jdbcTemplate.queryForObject("select id from projects where code = 'AI_FIGMA_NODES'", Long.class);
        jdbcTemplate.update("""
            insert into ai_figma_files(
              figma_file_id, project_id, file_key, selected_node_id, file_name, snapshot_hash,
              raw_summary_json, actor_id, actor_name, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "figma-file-node-test",
            projectId,
            "File123",
            "1:2",
            "Login Flow",
            "hash-1",
            "{}",
            "system-dev",
            "Development User",
            now,
            now
        );
        figmaFilePk = jdbcTemplate.queryForObject("select id from ai_figma_files where figma_file_id = ?", Long.class, "figma-file-node-test");
    }

    @Test
    void mapsFrameWithBoundsAndHierarchy() throws Exception {
        JsonNode root = objectMapper.readTree("""
            {
              "id": "1:2",
              "name": "Login Frame",
              "type": "FRAME",
              "layoutMode": "VERTICAL",
              "absoluteBoundingBox": {"x": 0, "y": 0, "width": 1440, "height": 900},
              "constraints": {"horizontal": "LEFT_RIGHT", "vertical": "TOP"},
              "children": [
                {
                  "id": "1:3",
                  "name": "Primary Button",
                  "type": "INSTANCE",
                  "componentKey": "button-primary",
                  "componentName": "Button / Primary",
                  "componentProperties": {
                    "State": {"type": "VARIANT", "value": "Default"},
                    "Size": {"type": "VARIANT", "value": "Large"}
                  },
                  "absoluteBoundingBox": {"x": 120, "y": 320, "width": 240, "height": 48}
                }
              ]
            }
            """);

        var nodes = mapper.mapTree(figmaFilePk, root, "hash-1");

        assertThat(nodes).hasSize(2);
        AiFigmaNode frame = nodes.getFirst();
        assertThat(frame.getNodeId()).isEqualTo("1:2");
        assertThat(frame.getNodeType()).isEqualTo("FRAME");
        assertThat(frame.getNodeName()).isEqualTo("Login Frame");
        assertThat(frame.getParentNodeId()).isEmpty();
        assertThat(frame.getLayoutMode()).isEqualTo("VERTICAL");
        assertThat(frame.getAbsoluteBoundsJson()).contains("\"width\":1440");
        assertThat(frame.getConstraintsJson()).contains("LEFT_RIGHT");

        AiFigmaNode button = nodes.get(1);
        assertThat(button.getNodeId()).isEqualTo("1:3");
        assertThat(button.getParentNodeId()).isEqualTo("1:2");
        assertThat(button.getComponentKey()).isEqualTo("button-primary");
        assertThat(button.getComponentName()).isEqualTo("Button / Primary");
        assertThat(button.getVariantPropertiesJson()).contains("\"State\"", "\"Default\"", "\"Size\"", "\"Large\"");
    }

    @Test
    void persistedChildHierarchyCanBeQueriedByParentNodeId() throws Exception {
        JsonNode root = objectMapper.readTree("""
            {
              "id": "2:1",
              "name": "Settings Page",
              "type": "FRAME",
              "children": [
                {"id": "2:2", "name": "Account Table", "type": "FRAME"},
                {"id": "2:3", "name": "Edit Drawer", "type": "COMPONENT"}
              ]
            }
            """);

        repository.saveAll(mapper.mapTree(figmaFilePk, root, "hash-2"));

        var children = repository.findByFigmaFilePkAndParentNodeId(figmaFilePk, "2:1");

        assertThat(children)
            .extracting(AiFigmaNode::getNodeName)
            .containsExactlyInAnyOrder("Account Table", "Edit Drawer");
    }
}
