package com.haitlify.aitest.platform.ai.quality;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import tools.jackson.databind.ObjectMapper;

class WebCaseQualityInsightServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JdbcTemplate jdbcTemplate;
    private WebCaseQualityInsightService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:web-case-quality-insight;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("drop table if exists ai_web_case_objective_scores");
        jdbcTemplate.execute("drop table if exists ai_web_case_agent_artifacts");
        jdbcTemplate.execute("drop table if exists ai_web_case_quality_ratings");
        jdbcTemplate.execute("drop table if exists ai_web_case_agent_runs");
        jdbcTemplate.execute("""
            create table ai_web_case_agent_runs (
              run_id varchar(128) primary key,
              project_id bigint not null,
              status varchar(32) not null default 'queued',
              requested_point_ids_json text not null default '[]',
              created_at timestamp not null
            )
            """);
        jdbcTemplate.execute("""
            create table ai_web_case_objective_scores (
              score_id varchar(128) primary key,
              run_id varchar(128) not null,
              project_id bigint not null,
              overall_score int not null,
              dimension_scores_json text not null default '{}',
              created_at timestamp not null,
              updated_at timestamp not null,
              constraint uk_insight_objective_run unique (run_id)
            )
            """);
        jdbcTemplate.execute("""
            create table ai_web_case_quality_ratings (
              rating_id varchar(128) primary key,
              run_id varchar(128) not null,
              project_id bigint not null,
              overall_score int not null,
              dimension_scores_json text not null default '{}',
              comment text not null default '',
              created_at timestamp not null,
              updated_at timestamp not null
            )
            """);
        jdbcTemplate.execute("""
            create table ai_web_case_agent_artifacts (
              id bigint primary key auto_increment,
              artifact_id varchar(128) not null,
              run_id varchar(128) not null,
              point_id varchar(128) not null,
              artifact_type varchar(32) not null,
              candidate_json text not null,
              evidence_ids_json text not null default '[]',
              validation_errors_json text not null default '[]',
              accepted boolean not null default false,
              acceptance_state varchar(64) not null default 'ACCEPTED',
              repair_round int not null default 0,
              created_at timestamp not null,
              updated_at timestamp not null,
              constraint uk_insight_artifact_id unique (artifact_id)
            )
            """);
        WebCaseRegressionComparisonService comparisonService = new WebCaseRegressionComparisonService(jdbcTemplate, objectMapper);
        WebCaseObjectiveQualityService objectiveQualityService = new WebCaseObjectiveQualityService(jdbcTemplate, objectMapper);
        service = new WebCaseQualityInsightService(jdbcTemplate, objectMapper, comparisonService, objectiveQualityService);
    }

    @Test
    void insightAggregatesRatingsAndWeakestDimensions() {
        Instant now = Instant.now();
        insertRating("run-1", 1L, 8, "{\"coverage\":6,\"source_traceability\":8}", now);
        insertRating("run-2", 1L, 7, "{\"coverage\":5,\"source_traceability\":7}", now);
        insertRating("run-3", 1L, 6, "{\"coverage\":7,\"source_traceability\":6}", now);

        WebCaseQualityInsightResponse insight = service.insight(1L);

        assertThat(insight.projectId()).isEqualTo(1L);
        assertThat(insight.ratedCount()).isEqualTo(3);
        assertThat(insight.baselineCount()).isEqualTo(2);
        assertThat(insight.overallAvg()).isEqualTo(7.0);
        assertThat(insight.dimensionAvgs().get("coverage")).isEqualTo(6.0);
        assertThat(insight.dimensionAvgs().get("source_traceability")).isEqualTo(7.0);
        assertThat(insight.dimensionAvgs().get("testability")).isEqualTo(0.0);
        assertThat(insight.weakestDimensions()).containsExactly(new WeakDimension("coverage", 6.0));
        assertThat(insight.recallAvg()).isEqualTo(0.0);
        assertThat(insight.lostCaseCount()).isZero();
    }

    @Test
    void insightReturnsZeroAggregatesWhenNoRatings() {
        WebCaseQualityInsightResponse insight = service.insight(1L);

        assertThat(insight.ratedCount()).isZero();
        assertThat(insight.baselineCount()).isZero();
        assertThat(insight.overallAvg()).isEqualTo(0.0);
        assertThat(insight.weakestDimensions()).isEmpty();
        assertThat(insight.lostCases()).isEmpty();
    }

    @Test
    void insightAggregatesRegressionRecallAndLostCases() {
        Instant now = Instant.now();
        insertRun("baseline-1", 1L, now);
        insertRun("run-1", 1L, now.plusSeconds(10));
        insertRating("baseline-1", 1L, 8, "{}", now);
        insertArtifact("art-b1", "baseline-1", "p1", "{\"title\":\"A\",\"assertions\":\"X\"}", now);
        insertArtifact("art-b2", "baseline-1", "p1", "{\"title\":\"B\",\"assertions\":\"Y\"}", now);
        insertArtifact("art-1", "run-1", "p1", "{\"title\":\"A\",\"assertions\":\"X\"}", now.plusSeconds(10));

        WebCaseQualityInsightResponse insight = service.insight(1L);

        assertThat(insight.recallAvg()).isEqualTo(0.5);
        assertThat(insight.lostCaseCount()).isEqualTo(1);
        assertThat(insight.lostCases()).containsExactly(new LostCase("B", 1));
    }

    @Test
    void insightUsesObjectiveScoresAsAutomaticBaseline() {
        Instant now = Instant.now();
        insertRun("run-1", 1L, now, "succeeded", "[\"p1\",\"p2\"]");
        insertArtifact("art-1", "run-1", "p1", "{\"title\":\"登录成功\",\"naturalExpectedResult\":\"进入工作台\",\"naturalSteps\":\"打开页面\",\"assertions\":\"assertVisible .dashboard\"}", "[\"locator-1\"]", now);
        insertArtifact("art-2", "run-1", "p2", "{\"title\":\"登出\",\"naturalExpectedResult\":\"\",\"naturalSteps\":\"\",\"assertions\":\"\"}", now);

        WebCaseQualityInsightResponse insight = service.insight(1L);

        assertThat(insight.ratedCount()).isEqualTo(1);
        assertThat(insight.humanCount()).isZero();
        assertThat(insight.objectiveCount()).isEqualTo(1);
        assertThat(insight.baselineCount()).isEqualTo(1); // 客观 overall = round((10+5+5+10+5+5)/6)=7 ≥ 阈值
        assertThat(insight.overallAvg()).isEqualTo(7.0);
        assertThat(insight.dimensionAvgs().get("coverage")).isEqualTo(10.0);
        assertThat(insight.dimensionAvgs().get("source_traceability")).isEqualTo(5.0);
        assertThat(insight.dimensionAvgs().get("testability")).isEqualTo(5.0);
        assertThat(insight.dimensionAvgs().get("duplicate_risk")).isEqualTo(10.0);
        assertThat(insight.dimensionAvgs().get("automation_readiness")).isEqualTo(5.0);
        assertThat(insight.dimensionAvgs().get("review_risk")).isEqualTo(5.0);
    }

    @Test
    void insightPrefersHumanRatingOverObjectiveScoreForSameRun() {
        Instant now = Instant.now();
        insertRun("run-1", 1L, now, "succeeded", "[\"p1\",\"p2\"]");
        insertArtifact("art-1", "run-1", "p1", "{\"title\":\"登录成功\",\"naturalExpectedResult\":\"进入工作台\",\"naturalSteps\":\"打开页面\",\"assertions\":\"assertVisible .dashboard\"}", "[\"locator-1\"]", now);
        insertArtifact("art-2", "run-1", "p2", "{\"title\":\"登出\",\"naturalExpectedResult\":\"\",\"naturalSteps\":\"\",\"assertions\":\"\"}", now);
        insertRating("run-1", 1L, 8, "{\"coverage\":9}", now);

        WebCaseQualityInsightResponse insight = service.insight(1L);

        assertThat(insight.ratedCount()).isEqualTo(1);
        assertThat(insight.humanCount()).isEqualTo(1);
        assertThat(insight.objectiveCount()).isZero();
        assertThat(insight.overallAvg()).isEqualTo(8.0);
        assertThat(insight.dimensionAvgs().get("coverage")).isEqualTo(9.0);
    }

    @Test
    void insightExcludesGivenRunFromRegressionAggregation() {
        Instant now = Instant.now();
        insertRun("baseline-1", 1L, now);
        insertRun("run-1", 1L, now.plusSeconds(10));
        insertRating("baseline-1", 1L, 8, "{}", now);
        insertArtifact("art-b1", "baseline-1", "p1", "{\"title\":\"A\",\"assertions\":\"X\"}", now);
        insertArtifact("art-1", "run-1", "p1", "{\"title\":\"登录\",\"assertions\":\"Y\"}", now.plusSeconds(10));

        // 排除 run-1 后,丢失用例来自 run-1 的对比,应不再计入。
        WebCaseQualityInsightResponse insight = service.insight(1L, "run-1");

        assertThat(insight.lostCaseCount()).isZero();
        assertThat(insight.lostCases()).isEmpty();
    }

    private void insertRun(String runId, long projectId, Instant createdAt) {
        insertRun(runId, projectId, createdAt, "queued", "[]");
    }

    private void insertRun(String runId, long projectId, Instant createdAt, String status, String requestedPointIds) {
        jdbcTemplate.update("insert into ai_web_case_agent_runs(run_id, project_id, status, requested_point_ids_json, created_at) values (?, ?, ?, ?, ?)",
            runId, projectId, status, requestedPointIds, Timestamp.from(createdAt));
    }

    private void insertRating(String runId, long projectId, int overallScore, String dimensionScores, Instant createdAt) {
        jdbcTemplate.update("insert into ai_web_case_quality_ratings(rating_id, run_id, project_id, overall_score, dimension_scores_json, comment, created_at, updated_at) values (?, ?, ?, ?, ?, '', ?, ?)",
            "rating-" + runId, runId, projectId, overallScore, dimensionScores, Timestamp.from(createdAt), Timestamp.from(createdAt));
    }

    private void insertArtifact(String artifactId, String runId, String pointId, String candidateJson, Instant createdAt) {
        insertArtifact(artifactId, runId, pointId, candidateJson, "[]", createdAt);
    }

    private void insertArtifact(String artifactId, String runId, String pointId, String candidateJson, String evidenceIdsJson, Instant createdAt) {
        jdbcTemplate.update("insert into ai_web_case_agent_artifacts(artifact_id, run_id, point_id, artifact_type, candidate_json, evidence_ids_json, accepted, created_at, updated_at) values (?, ?, ?, 'web_case', ?, ?, true, ?, ?)",
            artifactId, runId, pointId, candidateJson, evidenceIdsJson, Timestamp.from(createdAt), Timestamp.from(createdAt));
    }
}
