package com.haitlify.aitest.platform.ai.quality;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
@Transactional
class AiQualityScoreService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    AiQualityScoreService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    AiQualityScoreListResponse listScores(String runId) {
        jdbcTemplate.update("delete from ai_quality_scores where run_id = ?", runId);
        QualityInput input = loadInput(runId);
        List<ScoreDraft> scores = List.of(
            score("coverage", input.draftCount() == 0 ? 0.3 : 0.8, "RULE_COMPUTED:基于合并草稿数量评估覆盖基础。"),
            score("source_traceability", input.missingEvidenceCount() > 0 ? 0.4 : 0.9, "RULE_COMPUTED:检查每个草稿是否保留来源证据。"),
            score("testability", input.missingExpectedResultCount() > 0 ? 0.5 : 0.9, "RULE_COMPUTED:检查期望结果是否可验证。"),
            score("duplicate_risk", input.duplicateTargetCount() > 0 ? 0.6 : 0.9, "RULE_COMPUTED:检查合并前是否存在重复验证目标。"),
            score("automation_readiness", input.structuredStepCount() > 0 ? 0.9 : 0.5, "RULE_COMPUTED:检查是否有结构化步骤可供自动化参考。"),
            score("review_risk", input.unresolvedConflictCount() > 0 ? 0.3 : 0.8, "RULE_COMPUTED:检查是否存在未解决冲突。")
        );
        Instant now = Instant.now();
        for (ScoreDraft score : scores) {
            jdbcTemplate.update(
                """
                insert into ai_quality_scores(
                  score_id, run_id, quality_dimension, score, score_level, score_reason,
                  evidence_json, result_origin, acceptance_state, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "quality-score-" + UUID.randomUUID(),
                runId,
                score.dimension(),
                score.score(),
                level(score.score()),
                score.reason(),
                "{}",
                "RULE_EXTRACTED",
                "PENDING_HUMAN_CONFIRMATION",
                now,
                now
            );
        }

        return new AiQualityScoreListResponse(jdbcTemplate.query(
            "select * from ai_quality_scores where run_id = ? order by id asc",
            (rs, rowNum) -> mapResponse(rs),
            runId
        ));
    }

    private QualityInput loadInput(String runId) {
        List<JsonNode> candidates = latestConsensusItems(runId);
        int missingEvidence = 0;
        int missingExpected = 0;
        int structuredSteps = 0;
        for (JsonNode candidate : candidates) {
            if (!candidate.path("sourceEvidence").isArray() || candidate.path("sourceEvidence").isEmpty()) {
                missingEvidence++;
            }
            if (candidate.path("expectedResult").asText("").isBlank()) {
                missingExpected++;
            }
            if (candidate.path("suggestedSteps").isArray() && !candidate.path("suggestedSteps").isEmpty()) {
                structuredSteps++;
            }
        }
        int duplicateTargetCount = latestDuplicateTargetCount(runId);
        int unresolved = queryCount("select count(*) from ai_agent_conflicts where run_id = ? and resolution_status = 'unresolved'", runId);
        return new QualityInput(candidates.size(), missingEvidence, missingExpected, duplicateTargetCount, structuredSteps, unresolved);
    }

    private List<JsonNode> latestConsensusItems(String runId) {
        List<String> rows = jdbcTemplate.query(
            "select merged_output_json from ai_agent_consensus_records where run_id = ? order by id desc limit 1",
            (rs, rowNum) -> rs.getString("merged_output_json"),
            runId
        );
        if (rows.isEmpty()) {
            return List.of();
        }
        JsonNode root = parse(rows.get(0));
        JsonNode items = root.path("items");
        if (!items.isArray()) {
            return List.of();
        }
        List<JsonNode> result = new ArrayList<>();
        items.forEach(result::add);
        return result;
    }

    private int latestDuplicateTargetCount(String runId) {
        List<String> rows = jdbcTemplate.query(
            "select merged_output_json from ai_agent_consensus_records where run_id = ? order by id desc limit 1",
            (rs, rowNum) -> rs.getString("merged_output_json"),
            runId
        );
        if (rows.isEmpty()) {
            return 0;
        }
        return parse(rows.get(0)).path("duplicateTargetCount").asInt(0);
    }

    private int queryCount(String sql, String runId) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, runId);
        return count == null ? 0 : count;
    }

    private ScoreDraft score(String dimension, double score, String reason) {
        return new ScoreDraft(dimension, score, reason);
    }

    private String level(double score) {
        if (score >= 0.8) return "good";
        if (score >= 0.4) return "warning";
        return "risk";
    }

    private AiQualityScoreResponse mapResponse(ResultSet rs) throws SQLException {
        return new AiQualityScoreResponse(
            rs.getString("score_id"),
            rs.getString("run_id"),
            rs.getString("quality_dimension"),
            rs.getDouble("score"),
            rs.getString("score_level"),
            rs.getString("score_reason"),
            parse(rs.getString("evidence_json")),
            rs.getTimestamp("created_at").toInstant().toString()
        );
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid quality score JSON", exception);
        }
    }
}

record QualityInput(
    int draftCount,
    int missingEvidenceCount,
    int missingExpectedResultCount,
    int duplicateTargetCount,
    int structuredStepCount,
    int unresolvedConflictCount
) {
}

record ScoreDraft(
    String dimension,
    double score,
    String reason
) {
}

record AiQualityScoreListResponse(
    List<AiQualityScoreResponse> items
) {
}

record AiQualityScoreResponse(
    String scoreId,
    String runId,
    String qualityDimension,
    double score,
    String scoreLevel,
    String scoreReason,
    JsonNode evidenceJson,
    String createdAt
) {
}
