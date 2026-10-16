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

@Service
@Transactional
class AiAgentHandoffService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    AiAgentHandoffService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    void recordHandoff(
        String runId,
        String fromAgentId,
        String toAgentId,
        String sourceRunNodeId,
        String targetRunNodeId,
        JsonNode payload
    ) {
        Instant now = Instant.now();
        jdbcTemplate.update(
            """
            insert into ai_agent_handoffs(
              handoff_id, run_id, from_agent_id, to_agent_id, source_run_node_id,
              target_run_node_id, payload_summary, payload_json, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "agent-handoff-" + UUID.randomUUID(),
            runId,
            fromAgentId,
            toAgentId,
            sourceRunNodeId,
            targetRunNodeId,
            summary(payload),
            stringify(payload),
            now,
            now
        );
    }

    AiAgentHandoffListResponse listHandoffs(String runId) {
        return new AiAgentHandoffListResponse(jdbcTemplate.query(
            "select * from ai_agent_handoffs where run_id = ? order by id asc",
            (rs, rowNum) -> mapResponse(rs),
            runId
        ));
    }

    private AiAgentHandoffResponse mapResponse(ResultSet rs) throws SQLException {
        return new AiAgentHandoffResponse(
            rs.getString("handoff_id"),
            rs.getString("run_id"),
            rs.getString("from_agent_id"),
            rs.getString("to_agent_id"),
            rs.getString("source_run_node_id"),
            rs.getString("target_run_node_id"),
            rs.getString("payload_summary"),
            parse(rs.getString("payload_json")),
            rs.getTimestamp("created_at").toInstant().toString()
        );
    }

    private String summary(JsonNode payload) {
        String value = payload == null ? "" : payload.path("summary").asText("");
        return value.isBlank() ? "Agent handoff payload" : value;
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid agent handoff JSON", exception);
        }
    }

    private String stringify(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node == null ? objectMapper.createObjectNode() : node);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid agent handoff payload", exception);
        }
    }
}

record AiAgentHandoffListResponse(
    List<AiAgentHandoffResponse> items
) {
}

record AiAgentHandoffResponse(
    String handoffId,
    String runId,
    String fromAgentId,
    String toAgentId,
    String sourceRunNodeId,
    String targetRunNodeId,
    String payloadSummary,
    JsonNode payloadJson,
    String createdAt
) {
}
