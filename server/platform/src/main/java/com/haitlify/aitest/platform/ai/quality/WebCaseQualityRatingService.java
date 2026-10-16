package com.haitlify.aitest.platform.ai.quality;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 生成质量「人工打分闭环」:一次 Web 用例 run 交付后由人工打总分(1-10)与六维分,
 * 总分达到阈值的 run 即固化为回归基准(baseline),后续同需求重生成时据此对比。
 */
@Service
@Transactional
public class WebCaseQualityRatingService {

    /** 总分达到该值(含)即视为可回归的基准 run。 */
    public static final int BASELINE_THRESHOLD = 7;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    WebCaseQualityRatingService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public WebCaseQualityRatingResponse rate(String runId, RateWebCaseQualityRequest request) {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("Agent 任务不能为空");
        if (request == null || request.overallScore() < 1 || request.overallScore() > 10) throw new IllegalArgumentException("总分必须在 1-10 之间");
        Map<String, Integer> dimensions = normalizedDimensions(request.dimensionScores());
        List<Long> projectIds = jdbcTemplate.queryForList("select project_id from ai_web_case_agent_runs where run_id = ?", Long.class, runId);
        if (projectIds.isEmpty()) throw new IllegalArgumentException("Agent 任务不存在");
        Long projectId = projectIds.get(0);

        Instant now = Instant.now();
        List<String> existing = jdbcTemplate.queryForList("select rating_id from ai_web_case_quality_ratings where run_id = ?", String.class, runId);
        String ratingId = existing.isEmpty() ? "quality-rating-" + UUID.randomUUID() : existing.get(0);
        if (existing.isEmpty()) {
            jdbcTemplate.update(
                "insert into ai_web_case_quality_ratings(rating_id, run_id, project_id, overall_score, dimension_scores_json, comment, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?)",
                ratingId, runId, projectId, request.overallScore(), write(dimensions), value(request.comment()), Timestamp.from(now), Timestamp.from(now));
        } else {
            jdbcTemplate.update(
                "update ai_web_case_quality_ratings set overall_score = ?, dimension_scores_json = ?, comment = ?, updated_at = ? where rating_id = ?",
                request.overallScore(), write(dimensions), value(request.comment()), Timestamp.from(now), ratingId);
        }
        return get(runId);
    }

    public WebCaseQualityRatingResponse get(String runId) {
        List<WebCaseQualityRatingResponse> rows = jdbcTemplate.query(
            "select * from ai_web_case_quality_ratings where run_id = ?",
            (rs, rowNum) -> map(rs),
            runId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public WebCaseQualityRatingListResponse list(Long projectId) {
        if (projectId == null) throw new IllegalArgumentException("项目不能为空");
        List<WebCaseQualityRatingResponse> items = jdbcTemplate.query(
            "select * from ai_web_case_quality_ratings where project_id = ? order by created_at desc",
            (rs, rowNum) -> map(rs),
            projectId);
        return new WebCaseQualityRatingListResponse(items);
    }

    private Map<String, Integer> normalizedDimensions(Map<String, Integer> dimensions) {
        if (dimensions == null || dimensions.isEmpty()) return Map.of();
        for (Map.Entry<String, Integer> entry : dimensions.entrySet()) {
            if (entry.getValue() == null || entry.getValue() < 1 || entry.getValue() > 10) {
                throw new IllegalArgumentException("维度分必须在 1-10 之间");
            }
        }
        return Map.copyOf(dimensions);
    }

    private WebCaseQualityRatingResponse map(ResultSet rs) throws SQLException {
        int overall = rs.getInt("overall_score");
        return new WebCaseQualityRatingResponse(
            rs.getString("rating_id"),
            rs.getString("run_id"),
            rs.getLong("project_id"),
            overall,
            read(rs.getString("dimension_scores_json")),
            rs.getString("comment"),
            overall >= BASELINE_THRESHOLD,
            rs.getTimestamp("created_at").toInstant().toString()
        );
    }

    private String write(Map<String, Integer> value) {
        try { return objectMapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception exception) { throw new IllegalStateException("维度分序列化失败", exception); }
    }

    private Map<String, Integer> read(String value) {
        try { return objectMapper.readValue(value == null || value.isBlank() ? "{}" : value, new TypeReference<>() {}); }
        catch (Exception exception) { return Map.of(); }
    }

    private String value(String input) { return input == null ? "" : input; }
}

record RateWebCaseQualityRequest(
    int overallScore,
    Map<String, Integer> dimensionScores,
    String comment
) {
}

record WebCaseQualityRatingResponse(
    String ratingId,
    String runId,
    Long projectId,
    int overallScore,
    Map<String, Integer> dimensionScores,
    String comment,
    boolean baseline,
    String createdAt
) {
}

record WebCaseQualityRatingListResponse(
    List<WebCaseQualityRatingResponse> items
) {
}
