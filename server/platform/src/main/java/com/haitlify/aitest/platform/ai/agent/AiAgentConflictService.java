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
class AiAgentConflictService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    AiAgentConflictService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    void createConflictIfAbsent(String runId, String conflictType, String targetKey, JsonNode conflictJson) {
        Integer existing = jdbcTemplate.queryForObject(
            "select count(*) from ai_agent_conflicts where run_id = ? and conflict_type = ? and target_key = ?",
            Integer.class,
            runId,
            conflictType,
            targetKey
        );
        if (existing != null && existing > 0) {
            return;
        }
        Instant now = Instant.now();
        jdbcTemplate.update(
            """
            insert into ai_agent_conflicts(
              conflict_id, run_id, conflict_type, source_agent_ids_json, target_key,
              conflict_json, resolution_status, resolution_json, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "agent-conflict-" + UUID.randomUUID(),
            runId,
            conflictType,
            "[\"test-design\",\"quality-review\",\"merge-coordinator\"]",
            targetKey,
            stringify(conflictJson),
            "unresolved",
            "{}",
            now,
            now
        );
    }

    int unresolvedCount(String runId) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from ai_agent_conflicts where run_id = ? and resolution_status = 'unresolved'",
            Integer.class,
            runId
        );
        return count == null ? 0 : count;
    }

    AiAgentConflictListResponse listConflicts(String runId) {
        return new AiAgentConflictListResponse(jdbcTemplate.query(
            "select * from ai_agent_conflicts where run_id = ? order by id asc",
            (rs, rowNum) -> mapResponse(rs),
            runId
        ));
    }

    AiAgentConflictResponse resolve(String conflictId, ResolveAgentConflictRequest request) {
        return updateResolution(conflictId, "resolved", request == null ? objectMapper.createObjectNode() : objectMapper.valueToTree(request));
    }

    AiAgentConflictResponse ignore(String conflictId, ResolveAgentConflictRequest request) {
        return updateResolution(conflictId, "ignored", request == null ? objectMapper.createObjectNode() : objectMapper.valueToTree(request));
    }

    private AiAgentConflictResponse updateResolution(String conflictId, String status, JsonNode resolution) {
        jdbcTemplate.update(
            "update ai_agent_conflicts set resolution_status = ?, resolution_json = ?, updated_at = ? where conflict_id = ?",
            status,
            stringify(resolution),
            Instant.now(),
            conflictId
        );
        return jdbcTemplate.query(
            "select * from ai_agent_conflicts where conflict_id = ?",
            (rs, rowNum) -> mapResponse(rs),
            conflictId
        ).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown agent conflict: " + conflictId));
    }

    private AiAgentConflictResponse mapResponse(ResultSet rs) throws SQLException {
        return new AiAgentConflictResponse(
            rs.getString("conflict_id"),
            rs.getString("run_id"),
            rs.getString("conflict_type"),
            rs.getString("target_key"),
            parse(rs.getString("conflict_json")),
            rs.getString("resolution_status"),
            parse(rs.getString("resolution_json")),
            rs.getTimestamp("created_at").toInstant().toString()
        );
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid agent conflict JSON", exception);
        }
    }

    private String stringify(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node == null ? objectMapper.createObjectNode() : node);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid agent conflict payload", exception);
        }
    }
}

record ResolveAgentConflictRequest(
    String resolution,
    JsonNode resolutionJson
) {
}

record AiAgentConflictListResponse(
    List<AiAgentConflictResponse> items
) {
}

record AiAgentConflictResponse(
    String conflictId,
    String runId,
    String conflictType,
    String targetKey,
    JsonNode conflictJson,
    String resolutionStatus,
    JsonNode resolutionJson,
    String createdAt
) {
}
