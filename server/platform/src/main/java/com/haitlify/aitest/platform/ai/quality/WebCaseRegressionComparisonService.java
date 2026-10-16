package com.haitlify.aitest.platform.ai.quality;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 固化回归集对比:将当前 run 已接受的 Web 用例与基准(baseline) run 逐测试点做确定性匹配,
 * 输出召回 / 新增 / 丢失三类差异与召回率,供人工判断重生成结果相对固化基准是否退化。
 */
@Service
@Transactional
public class WebCaseRegressionComparisonService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    WebCaseRegressionComparisonService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public WebCaseRegressionComparisonResponse compare(String runId, String baselineRunId) {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("Agent 任务不能为空");
        List<Long> projectIds = jdbcTemplate.queryForList("select project_id from ai_web_case_agent_runs where run_id = ?", Long.class, runId);
        if (projectIds.isEmpty()) throw new IllegalArgumentException("Agent 任务不存在");
        Long projectId = projectIds.get(0);

        String resolvedBaseline = resolveBaseline(runId, projectId, baselineRunId);
        if (resolvedBaseline == null) {
            return new WebCaseRegressionComparisonResponse(runId, null, null, false, List.of(), new Summary(0, 0, 0, 0, 0.0));
        }

        Map<String, List<CaseRef>> baseline = loadAcceptedCases(resolvedBaseline);
        Map<String, List<CaseRef>> current = loadAcceptedCases(runId);

        List<PointComparison> points = new ArrayList<>();
        int totalBaseline = 0;
        int totalRecalled = 0;
        int totalAdded = 0;
        int totalLost = 0;
        List<String> pointOrder = new ArrayList<>(baseline.keySet());
        for (String pointId : current.keySet()) {
            if (!baseline.containsKey(pointId)) pointOrder.add(pointId);
        }
        for (String pointId : pointOrder) {
            List<CaseRef> baselineCases = baseline.getOrDefault(pointId, List.of());
            List<CaseRef> currentCases = current.getOrDefault(pointId, List.of());
            Map<String, Integer> currentCounts = new HashMap<>();
            for (CaseRef candidate : currentCases) currentCounts.merge(matchKey(candidate), 1, Integer::sum);

            int recalled = 0;
            List<CaseRef> lost = new ArrayList<>();
            for (CaseRef baselineCase : baselineCases) {
                String key = matchKey(baselineCase);
                int remaining = currentCounts.getOrDefault(key, 0);
                if (remaining > 0) {
                    currentCounts.put(key, remaining - 1);
                    recalled++;
                } else {
                    lost.add(baselineCase);
                }
            }
            List<CaseRef> added = new ArrayList<>();
            for (CaseRef candidate : currentCases) {
                String key = matchKey(candidate);
                int remaining = currentCounts.getOrDefault(key, 0);
                if (remaining > 0) {
                    currentCounts.put(key, remaining - 1);
                    added.add(candidate);
                }
            }

            points.add(new PointComparison(pointId, baselineCases.size(), recalled, added.size(), lost.size(), added, lost));
            totalBaseline += baselineCases.size();
            totalRecalled += recalled;
            totalAdded += added.size();
            totalLost += lost.size();
        }

        double recallRatio = totalBaseline == 0 ? 0.0 : (double) totalRecalled / totalBaseline;
        Summary summary = new Summary(totalBaseline, totalRecalled, totalAdded, totalLost, recallRatio);
        return new WebCaseRegressionComparisonResponse(runId, resolvedBaseline, baselineScore(resolvedBaseline), true, points, summary);
    }

    private String resolveBaseline(String runId, Long projectId, String baselineRunId) {
        if (baselineRunId != null && !baselineRunId.isBlank()) {
            return validateExplicitBaseline(runId, projectId, baselineRunId);
        }
        List<String> candidates = jdbcTemplate.queryForList("""
            select r.run_id from ai_web_case_agent_runs r
            join ai_web_case_quality_ratings q on q.run_id = r.run_id and q.overall_score >= 7
            where r.project_id = ? and r.run_id <> ?
              and exists (select 1 from ai_web_case_agent_artifacts a1 join ai_web_case_agent_artifacts a2 on a1.point_id = a2.point_id
                          where a1.run_id = r.run_id and a2.run_id = ? and a1.accepted = true and a1.acceptance_state = 'ACCEPTED' and a2.accepted = true and a2.acceptance_state = 'ACCEPTED'
                            and a1.artifact_type = 'web_case' and a2.artifact_type = 'web_case')
            order by r.created_at desc, r.run_id limit 1
            """, String.class, projectId, runId, runId);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /** 显式指定的基准 run 必须是一个有效的「固化基准」:非自身、同项目、总分达标、且已交付可对比的 Web 用例。 */
    private String validateExplicitBaseline(String runId, Long projectId, String baselineRunId) {
        if (baselineRunId.equals(runId)) throw new IllegalArgumentException("基准 run 不能与当前任务相同");
        List<Long> projects = jdbcTemplate.queryForList("select project_id from ai_web_case_agent_runs where run_id = ?", Long.class, baselineRunId);
        if (projects.isEmpty()) throw new IllegalArgumentException("基准 run 不存在");
        if (!projects.get(0).equals(projectId)) throw new IllegalArgumentException("基准 run 与当前任务不在同一项目");
        List<Integer> scores = jdbcTemplate.queryForList("select overall_score from ai_web_case_quality_ratings where run_id = ? and overall_score >= 7", Integer.class, baselineRunId);
        if (scores.isEmpty()) throw new IllegalArgumentException("基准 run 尚未固化(总分不足 7)");
        List<Integer> accepted = jdbcTemplate.queryForList("select count(*) from ai_web_case_agent_artifacts where run_id = ? and artifact_type = 'web_case' and accepted = true and acceptance_state = 'ACCEPTED'", Integer.class, baselineRunId);
        if (accepted.isEmpty() || accepted.get(0) == null || accepted.get(0) == 0) throw new IllegalArgumentException("基准 run 没有已接受的 Web 用例");
        return baselineRunId;
    }

    private Map<String, List<CaseRef>> loadAcceptedCases(String runId) {
        Map<String, List<CaseRef>> byPoint = new LinkedHashMap<>();
        jdbcTemplate.query("""
            select point_id, candidate_json from ai_web_case_agent_artifacts
            where run_id = ? and artifact_type = 'web_case' and accepted = true and acceptance_state = 'ACCEPTED'
            order by created_at, id
            """, (RowCallbackHandler) rs -> {
            String pointId = rs.getString("point_id");
            JsonNode node = parse(rs.getString("candidate_json"));
            String title = node.path("title").asText("");
            String assertions = node.path("assertions").asText("");
            byPoint.computeIfAbsent(pointId, key -> new ArrayList<>()).add(new CaseRef(title, assertions));
        }, runId);
        return byPoint;
    }

    private JsonNode parse(String value) {
        try { return objectMapper.readTree(value == null || value.isBlank() ? "{}" : value); }
        catch (Exception exception) { return objectMapper.createObjectNode(); }
    }

    private String matchKey(CaseRef candidate) {
        return normalize(candidate.title()) + " " + normalize(candidate.assertions());
    }

    private String normalize(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ");
    }

    private Integer baselineScore(String baselineRunId) {
        List<Integer> scores = jdbcTemplate.queryForList("select overall_score from ai_web_case_quality_ratings where run_id = ?", Integer.class, baselineRunId);
        return scores.isEmpty() ? null : scores.get(0);
    }
}

record WebCaseRegressionComparisonResponse(
    String runId,
    String baselineRunId,
    Integer baselineOverallScore,
    boolean available,
    List<PointComparison> points,
    Summary summary
) {
}

record PointComparison(
    String pointId,
    int baselineCount,
    int recalledCount,
    int addedCount,
    int lostCount,
    List<CaseRef> added,
    List<CaseRef> lost
) {
}

record CaseRef(
    String title,
    String assertions
) {
}

record Summary(
    int baselineTotal,
    int recalled,
    int added,
    int lost,
    double recallRatio
) {
}
