package com.haitlify.aitest.platform.ai.quality;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 客观质量基线:对一个已完成的 Web 用例 run,基于其已交付(accepted)用例的 artifacts
 * 自动计算六维客观分(1-10,与人工评分 {@link WebCaseQualityRatingService} 同量纲同对象),
 * 供 {@link WebCaseQualityInsightService} 在无人工评分时形成自动基线。
 * 只读已存在产物,不依赖 LLM 与 DSL 解析,不触碰 agent 运行时代码。
 */
@Service
@Transactional
public class WebCaseObjectiveQualityService {

    private static final List<String> DIMENSION_KEYS = List.of(
        "coverage", "source_traceability", "testability", "duplicate_risk", "automation_readiness", "review_risk");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    WebCaseObjectiveQualityService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 惰性回填:对项目内所有已完成且交付了 accepted 用例、但尚无客观分的 run 计算并写入。
     * 幂等(uk run_id),供洞察聚合调用以覆盖历史 run,无需改动 agent 运行时触发点。
     */
    public void backfillProject(Long projectId) {
        List<String> runIds = jdbcTemplate.queryForList(
            """
            select r.run_id from ai_web_case_agent_runs r
            where r.project_id = ? and r.status in ('succeeded', 'failed')
              and not exists (select 1 from ai_web_case_objective_scores o where o.run_id = r.run_id)
              and exists (select 1 from ai_web_case_agent_artifacts a
                          where a.run_id = r.run_id and a.accepted = true and a.acceptance_state = 'ACCEPTED' and a.artifact_type = 'web_case')
            """,
            String.class, projectId);
        for (String runId : runIds) {
            try {
                compute(runId);
            } catch (Exception ignored) {
                // 单 run 计算失败不影响其余回填。
            }
        }
    }

    /** 计算某 run 的六维客观分并写入(幂等:已存在则跳过)。 */
    void compute(String runId) {
        List<String> existing = jdbcTemplate.queryForList("select score_id from ai_web_case_objective_scores where run_id = ?", String.class, runId);
        if (!existing.isEmpty()) return;
        List<Long> projectIds = jdbcTemplate.queryForList("select project_id from ai_web_case_agent_runs where run_id = ?", Long.class, runId);
        if (projectIds.isEmpty()) return;
        Long projectId = projectIds.get(0);

        int requestedCount = readList(jdbcTemplate.queryForObject(
            "select requested_point_ids_json from ai_web_case_agent_runs where run_id = ?", String.class, runId)).size();

        List<ArtifactRow> artifacts = jdbcTemplate.query(
            """
            select a.point_id, a.candidate_json, a.evidence_ids_json
            from ai_web_case_agent_artifacts a
            join (
                select point_id, max(repair_round) as max_round
                from ai_web_case_agent_artifacts
                where run_id = ? and accepted = true and acceptance_state = 'ACCEPTED' and artifact_type = 'web_case'
                group by point_id
            ) latest on latest.point_id = a.point_id and latest.max_round = a.repair_round
            where a.run_id = ? and a.accepted = true and a.acceptance_state = 'ACCEPTED' and a.artifact_type = 'web_case'
            """,
            (rs, rowNum) -> new ArtifactRow(rs.getString("point_id"), rs.getString("candidate_json"), rs.getString("evidence_ids_json")),
            runId, runId);

        int acceptedCount = artifacts.size();
        if (acceptedCount == 0) return;

        int evidenceBound = 0;
        int expectedCount = 0;
        int assertCount = 0;
        int fullDesignCount = 0;
        Map<String, Integer> uniqueTitles = new LinkedHashMap<>();
        for (ArtifactRow artifact : artifacts) {
            if (hasEvidence(artifact.evidenceIdsJson())) evidenceBound++;
            JsonNode candidate = parse(artifact.candidateJson());
            String expected = candidate.path("naturalExpectedResult").asText("");
            String assertions = candidate.path("assertions").asText("");
            String naturalSteps = candidate.path("naturalSteps").asText("");
            if (!expected.isBlank()) expectedCount++;
            if (!assertions.isBlank()) assertCount++;
            if (!naturalSteps.isBlank() && !expected.isBlank()) fullDesignCount++;
            uniqueTitles.merge(normalize(candidate.path("title").asText("")), 1, Integer::sum);
        }

        Map<String, Integer> dimensions = new LinkedHashMap<>();
        dimensions.put("coverage", score(acceptedCount, Math.max(1, requestedCount)));
        dimensions.put("source_traceability", score(evidenceBound, acceptedCount));
        dimensions.put("testability", score(expectedCount, acceptedCount));
        dimensions.put("duplicate_risk", score(uniqueTitles.size(), acceptedCount));
        dimensions.put("automation_readiness", score(assertCount, acceptedCount));
        dimensions.put("review_risk", score(fullDesignCount, acceptedCount));
        int overall = (int) Math.round(dimensions.values().stream().mapToInt(Integer::intValue).average().orElse(0.0));

        Instant now = Instant.now();
        jdbcTemplate.update(
            "insert into ai_web_case_objective_scores(score_id, run_id, project_id, overall_score, dimension_scores_json, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?)",
            "objective-score-" + UUID.randomUUID(), runId, projectId, overall, write(dimensions), Timestamp.from(now), Timestamp.from(now));
    }

    /** 10 分制比例分:count/total 映射到 0-10。 */
    private int score(int count, int total) {
        return (int) Math.round(10.0 * count / Math.max(1, total));
    }

    private boolean hasEvidence(String evidenceIdsJson) {
        if (evidenceIdsJson == null || evidenceIdsJson.isBlank()) return false;
        try {
            List<String> ids = objectMapper.readValue(evidenceIdsJson, new TypeReference<>() {});
            return ids != null && !ids.isEmpty();
        } catch (Exception exception) {
            return false;
        }
    }

    private String normalize(String title) {
        return (title == null ? "" : title.trim().replaceAll("\\s+", " ").toLowerCase());
    }

    private JsonNode parse(String json) {
        try { return objectMapper.readTree(json == null || json.isBlank() ? "{}" : json); }
        catch (Exception exception) { return objectMapper.createObjectNode(); }
    }

    private String write(Map<String, Integer> value) {
        try { return objectMapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception exception) { throw new IllegalStateException("维度分序列化失败", exception); }
    }

    private List<String> readList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception exception) { return List.of(); }
    }

    private record ArtifactRow(String pointId, String candidateJson, String evidenceIdsJson) { }
}
