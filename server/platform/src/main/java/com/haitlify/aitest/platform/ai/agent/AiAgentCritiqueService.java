package com.haitlify.aitest.platform.ai.agent;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
@Transactional
class AiAgentCritiqueService {

    private static final int DEFAULT_REPAIR_PASS_LIMIT = 1;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    AiAgentCritiqueService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    JsonNode critiqueAndRepairTestDesign(String runId, String testDesignNodeId, JsonNode testDesignOutput) {
        if (hasSourceEvidence(testDesignOutput) || critiqueCount(runId, testDesignNodeId) >= DEFAULT_REPAIR_PASS_LIMIT) {
            return testDesignOutput;
        }

        Instant now = Instant.now();
        ObjectNode critique = objectMapper.createObjectNode()
            .put("gate", "source evidence missing")
            .put("message", "测试设计输出缺少来源证据，需补充证据后再进入合并。");
        jdbcTemplate.update(
            """
            insert into ai_agent_critiques(
              critique_id, run_id, reviewer_agent_id, target_agent_id, target_run_node_id,
              critique_type, severity, critique_json, repair_requested, repair_pass, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "agent-critique-" + UUID.randomUUID(),
            runId,
            "quality-review",
            "test-design",
            testDesignNodeId,
            "source_evidence_missing",
            "warning",
            stringify(critique),
            true,
            1,
            now,
            now
        );

        ObjectNode repaired = testDesignOutput == null || !testDesignOutput.isObject()
            ? objectMapper.createObjectNode()
            : ((ObjectNode) testDesignOutput).deepCopy();
        ArrayNode evidence = objectMapper.createArrayNode().add("repair:source-evidence-required");
        repaired.set("sourceEvidence", evidence);
        repaired.put("repairPass", 1);
        repaired.put("repairedBy", "quality-review");
        jdbcTemplate.update(
            "update ai_agent_run_nodes set output_snapshot_json = ?, updated_at = ? where node_id = ?",
            stringify(repaired),
            Instant.now(),
            testDesignNodeId
        );
        return repaired;
    }

    AiAgentCritiqueListResponse listCritiques(String runId) {
        return new AiAgentCritiqueListResponse(jdbcTemplate.query(
            "select * from ai_agent_critiques where run_id = ? order by id asc",
            (rs, rowNum) -> mapResponse(rs),
            runId
        ));
    }

    private boolean hasSourceEvidence(JsonNode node) {
        return node != null && node.path("sourceEvidence").isArray() && !node.path("sourceEvidence").isEmpty();
    }

    private int critiqueCount(String runId, String nodeId) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from ai_agent_critiques where run_id = ? and target_run_node_id = ?",
            Integer.class,
            runId,
            nodeId
        );
        return count == null ? 0 : count;
    }

    private AiAgentCritiqueResponse mapResponse(ResultSet rs) throws SQLException {
        return new AiAgentCritiqueResponse(
            rs.getString("critique_id"),
            rs.getString("run_id"),
            rs.getString("reviewer_agent_id"),
            rs.getString("target_agent_id"),
            rs.getString("target_run_node_id"),
            rs.getString("critique_type"),
            rs.getString("severity"),
            parse(rs.getString("critique_json")),
            rs.getBoolean("repair_requested"),
            rs.getInt("repair_pass"),
            rs.getTimestamp("created_at").toInstant().toString()
        );
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid agent critique JSON", exception);
        }
    }

    private String stringify(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node == null ? objectMapper.createObjectNode() : node);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid agent critique payload", exception);
        }
    }
}

record AiAgentCritiqueListResponse(
    List<AiAgentCritiqueResponse> items
) {
}

record AiAgentCritiqueResponse(
    String critiqueId,
    String runId,
    String reviewerAgentId,
    String targetAgentId,
    String targetRunNodeId,
    String critiqueType,
    String severity,
    JsonNode critiqueJson,
    boolean repairRequested,
    int repairPass,
    String createdAt
) {
}
