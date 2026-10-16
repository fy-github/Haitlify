package com.haitlify.aitest.platform.ai.quality;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 生成质量洞察:把一个项目内累积的人工评分与固化回归集对比聚合为可行动的反馈信号,
 * 回答「哪一维最弱该优先迭代、重生成时丢失了哪些用例」,驱动生成质量迭代。
 */
@Service
@Transactional
public class WebCaseQualityInsightService {

    /** 与 {@link WebCaseQualityRatingService} 六维契约一致,顺序即展示顺序。 */
    private static final List<String> DIMENSION_KEYS = List.of(
        "coverage", "source_traceability", "testability", "duplicate_risk", "automation_readiness", "review_risk");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final WebCaseRegressionComparisonService comparisonService;
    private final WebCaseObjectiveQualityService objectiveQualityService;

    WebCaseQualityInsightService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, WebCaseRegressionComparisonService comparisonService, WebCaseObjectiveQualityService objectiveQualityService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.comparisonService = comparisonService;
        this.objectiveQualityService = objectiveQualityService;
    }

    public WebCaseQualityInsightResponse insight(Long projectId) {
        return insight(projectId, null);
    }

    /** 与 {@link #insight(Long)} 相同,但聚合回归时排除 {@code excludeRunId}(用于反馈注入时排除当前在途 run)。 */
    WebCaseQualityInsightResponse insight(Long projectId, String excludeRunId) {
        if (projectId == null) throw new IllegalArgumentException("项目不能为空");

        // 先惰性回填客观分,确保每次洞察都基于最新的交付产物计算自动基线。
        objectiveQualityService.backfillProject(projectId);

        List<RatingRow> ratings = jdbcTemplate.query(
            "select run_id, overall_score, dimension_scores_json from ai_web_case_quality_ratings where project_id = ?",
            (rs, rowNum) -> new RatingRow(rs.getString("run_id"), rs.getInt("overall_score"), readDimensions(rs.getString("dimension_scores_json"))),
            projectId);
        List<RatingRow> objectives = jdbcTemplate.query(
            "select run_id, overall_score, dimension_scores_json from ai_web_case_objective_scores where project_id = ?",
            (rs, rowNum) -> new RatingRow(rs.getString("run_id"), rs.getInt("overall_score"), readDimensions(rs.getString("dimension_scores_json"))),
            projectId);

        // 合并人工分与客观分:同一 run 有人工分则以人工为准(人工校准覆盖自动基线),其余用客观分。
        int humanCount = ratings.size();
        List<RatingRow> effective = new ArrayList<>(ratings);
        java.util.Set<String> humanRatedRuns = new java.util.HashSet<>();
        for (RatingRow rating : ratings) humanRatedRuns.add(rating.runId());
        int objectiveCount = 0;
        for (RatingRow row : objectives) {
            if (humanRatedRuns.contains(row.runId())) continue;
            effective.add(row);
            objectiveCount++;
        }

        int ratedCount = effective.size();
        int baselineCount = 0;
        int overallSum = 0;
        Map<String, Double> dimensionSums = new LinkedHashMap<>();
        Map<String, Integer> dimensionCounts = new LinkedHashMap<>();
        for (String key : DIMENSION_KEYS) { dimensionSums.put(key, 0.0); dimensionCounts.put(key, 0); }
        for (RatingRow rating : effective) {
            overallSum += rating.overallScore();
            if (rating.overallScore() >= WebCaseQualityRatingService.BASELINE_THRESHOLD) baselineCount++;
            for (Map.Entry<String, Integer> dimension : rating.dimensions().entrySet()) {
                if (!dimensionSums.containsKey(dimension.getKey())) continue;
                dimensionSums.merge(dimension.getKey(), (double) dimension.getValue(), Double::sum);
                dimensionCounts.merge(dimension.getKey(), 1, Integer::sum);
            }
        }
        double overallAvg = ratedCount == 0 ? 0.0 : (double) overallSum / ratedCount;

        Map<String, Double> dimensionAvgs = new LinkedHashMap<>();
        List<WeakDimension> weakest = new ArrayList<>();
        for (String key : DIMENSION_KEYS) {
            int count = dimensionCounts.get(key);
            double avg = count == 0 ? 0.0 : dimensionSums.get(key) / count;
            dimensionAvgs.put(key, round(avg));
            if (count > 0 && avg < WebCaseQualityRatingService.BASELINE_THRESHOLD) {
                // 保留未取整的原始均值,供反馈文案向下取整展示,避免「7 分(低于 7 分基线)」的自相矛盾。
                weakest.add(new WeakDimension(key, avg));
            }
        }
        weakest.sort((left, right) -> Double.compare(left.avg(), right.avg()));

        // 无任何评分时不可能存在基准 run,回归聚合必然为空,直接短路避免无谓的全项目扫描。
        RegressionAggregate regression = ratedCount == 0 ? new RegressionAggregate(0.0, 0, List.of()) : aggregateRegression(projectId, excludeRunId);

        return new WebCaseQualityInsightResponse(
            projectId, ratedCount, baselineCount, round(overallAvg), round(regression.recallAvg()),
            regression.lostCaseCount(), dimensionAvgs, weakest, regression.lostCases(), humanCount, objectiveCount);
    }

    /** 遍历项目内每个 run 与固化基准的对比,聚合召回率与丢失用例(把「丢失」作为可迭代的回归信号);排除在途 run。 */
    private RegressionAggregate aggregateRegression(Long projectId, String excludeRunId) {
        List<String> runIds = jdbcTemplate.queryForList(
            "select run_id from ai_web_case_agent_runs where project_id = ? order by created_at", String.class, projectId);
        double recallSum = 0.0;
        int recallCount = 0;
        int lostCaseCount = 0;
        Map<String, Integer> lostTitles = new LinkedHashMap<>();
        for (String runId : runIds) {
            if (runId.equals(excludeRunId)) continue; // 在途 run 尚未完成,其「待生成点」不应计为丢失。
            WebCaseRegressionComparisonResponse comparison;
            try {
                comparison = comparisonService.compare(runId, null);
            } catch (IllegalArgumentException ignored) {
                continue; // 当前 run 自身不可比或缺失,跳过。
            }
            if (!comparison.available() || comparison.summary() == null || comparison.summary().baselineTotal() == 0) continue;
            recallSum += comparison.summary().recallRatio();
            recallCount++;
            lostCaseCount += comparison.summary().lost();
            for (PointComparison point : comparison.points()) {
                for (CaseRef lost : point.lost()) {
                    lostTitles.merge(lost.title(), 1, Integer::sum);
                }
            }
        }
        double recallAvg = recallCount == 0 ? 0.0 : recallSum / recallCount;
        List<LostCase> lostCases = lostTitles.entrySet().stream()
            .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
            .limit(10)
            .map(entry -> new LostCase(entry.getKey(), entry.getValue()))
            .toList();
        return new RegressionAggregate(recallAvg, lostCaseCount, lostCases);
    }

    private Map<String, Integer> readDimensions(String value) {
        try { return objectMapper.readValue(value == null || value.isBlank() ? "{}" : value, new TypeReference<>() {}); }
        catch (Exception exception) { return Map.of(); }
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record RatingRow(String runId, int overallScore, Map<String, Integer> dimensions) { }
    private record RegressionAggregate(double recallAvg, int lostCaseCount, List<LostCase> lostCases) { }
}

record WebCaseQualityInsightResponse(
    Long projectId,
    int ratedCount,
    int baselineCount,
    double overallAvg,
    double recallAvg,
    int lostCaseCount,
    Map<String, Double> dimensionAvgs,
    List<WeakDimension> weakestDimensions,
    List<LostCase> lostCases,
    int humanCount,
    int objectiveCount
) {
}

record WeakDimension(
    String key,
    double avg
) {
}

record LostCase(
    String title,
    int count
) {
}
