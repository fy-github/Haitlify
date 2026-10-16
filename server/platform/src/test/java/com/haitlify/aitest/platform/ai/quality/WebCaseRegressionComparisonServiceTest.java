package com.haitlify.aitest.platform.ai.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import tools.jackson.databind.ObjectMapper;

class WebCaseRegressionComparisonServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JdbcTemplate jdbcTemplate;
    private WebCaseRegressionComparisonService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:web-case-regression-comparison;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("drop table if exists ai_web_case_agent_artifacts");
        jdbcTemplate.execute("drop table if exists ai_web_case_quality_ratings");
        jdbcTemplate.execute("drop table if exists ai_web_case_agent_runs");
        jdbcTemplate.execute("""
            create table ai_web_case_agent_runs (
              run_id varchar(128) primary key,
              project_id bigint not null,
              created_at timestamp not null
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
              constraint uk_artifact_id unique (artifact_id)
            )
            """);
        service = new WebCaseRegressionComparisonService(jdbcTemplate, objectMapper);
    }

    @Test
    void compareReturnsUnavailableWhenNoBaselineRatingExists() {
        Instant now = Instant.now();
        insertRun("run-1", 1L, now);
        insertArtifact("art-1", "run-1", "p1", "{\"title\":\"登录\",\"assertions\":\"显示登录按钮\"}", true, now);

        WebCaseRegressionComparisonResponse response = service.compare("run-1", null);

        assertThat(response.available()).isFalse();
        assertThat(response.baselineRunId()).isNull();
        assertThat(response.baselineOverallScore()).isNull();
        assertThat(response.points()).isEmpty();
        assertThat(response.summary().baselineTotal()).isZero();
        assertThat(response.summary().recalled()).isZero();
        assertThat(response.summary().added()).isZero();
        assertThat(response.summary().lost()).isZero();
        assertThat(response.summary().recallRatio()).isEqualTo(0.0);
    }

    @Test
    void compareRecallsMatchingAndFlagsAddedLost() {
        Instant now = Instant.now();
        insertRun("baseline-1", 1L, now);
        insertRun("run-1", 1L, now.plusSeconds(10));
        insertRating("baseline-1", 1L, 8, now);
        insertArtifact("art-b1", "baseline-1", "p1", "{\"title\":\"A\",\"assertions\":\"X\"}", true, now);
        insertArtifact("art-b2", "baseline-1", "p1", "{\"title\":\"B\",\"assertions\":\"Y\"}", true, now);
        insertArtifact("art-1", "run-1", "p1", "{\"title\":\"A\",\"assertions\":\"X\"}", true, now.plusSeconds(10));
        insertArtifact("art-2", "run-1", "p1", "{\"title\":\"B\",\"assertions\":\"Z\"}", true, now.plusSeconds(10));
        insertArtifact("art-3", "run-1", "p1", "{\"title\":\"C\",\"assertions\":\"W\"}", true, now.plusSeconds(10));

        WebCaseRegressionComparisonResponse response = service.compare("run-1", "baseline-1");

        assertThat(response.available()).isTrue();
        assertThat(response.baselineRunId()).isEqualTo("baseline-1");
        assertThat(response.baselineOverallScore()).isEqualTo(8);
        assertThat(response.points()).hasSize(1);
        PointComparison point = response.points().get(0);
        assertThat(point.pointId()).isEqualTo("p1");
        assertThat(point.baselineCount()).isEqualTo(2);
        assertThat(point.recalledCount()).isEqualTo(1);
        assertThat(point.lostCount()).isEqualTo(1);
        assertThat(point.addedCount()).isEqualTo(2);
        assertThat(point.lost()).containsExactly(new CaseRef("B", "Y"));
        assertThat(point.added()).containsExactly(new CaseRef("B", "Z"), new CaseRef("C", "W"));

        Summary summary = response.summary();
        assertThat(summary.baselineTotal()).isEqualTo(2);
        assertThat(summary.recalled()).isEqualTo(1);
        assertThat(summary.added()).isEqualTo(2);
        assertThat(summary.lost()).isEqualTo(1);
        assertThat(summary.recallRatio()).isEqualTo(0.5);
    }

    @Test
    void compareAutoSelectsMostRecentBaselineRunSharingPoint() {
        Instant now = Instant.now();
        insertRun("run-1", 1L, now.plusSeconds(40));
        insertRun("baseline-old", 1L, now);
        insertRun("baseline-new", 1L, now.plusSeconds(10));
        insertRun("other-run", 1L, now.plusSeconds(20));
        insertRating("baseline-old", 1L, 8, now);
        insertRating("baseline-new", 1L, 8, now.plusSeconds(10));
        insertRating("other-run", 1L, 7, now.plusSeconds(20));
        insertArtifact("art-1", "run-1", "p1", "{\"title\":\"登录\",\"assertions\":\"显示登录按钮\"}", true, now.plusSeconds(40));
        insertArtifact("art-bo", "baseline-old", "p1", "{\"title\":\"登录\",\"assertions\":\"显示登录按钮\"}", true, now);
        insertArtifact("art-bn", "baseline-new", "p1", "{\"title\":\"登录\",\"assertions\":\"显示登录按钮\"}", true, now.plusSeconds(10));
        insertArtifact("art-o1", "other-run", "p2", "{\"title\":\"其他\",\"assertions\":\"不共享测试点\"}", true, now.plusSeconds(20));

        WebCaseRegressionComparisonResponse response = service.compare("run-1", null);

        assertThat(response.available()).isTrue();
        assertThat(response.baselineRunId()).isEqualTo("baseline-new");
        assertThat(response.baselineOverallScore()).isEqualTo(8);
    }

    @Test
    void compareRespectsExplicitBaselineRunId() {
        Instant now = Instant.now();
        insertRun("run-1", 1L, now.plusSeconds(40));
        insertRun("baseline-old", 1L, now);
        insertRun("baseline-new", 1L, now.plusSeconds(10));
        insertRating("baseline-old", 1L, 8, now);
        insertRating("baseline-new", 1L, 9, now.plusSeconds(10));
        insertArtifact("art-1", "run-1", "p1", "{\"title\":\"登录\",\"assertions\":\"显示登录按钮\"}", true, now.plusSeconds(40));
        insertArtifact("art-bo", "baseline-old", "p1", "{\"title\":\"登录\",\"assertions\":\"显示登录按钮\"}", true, now);
        insertArtifact("art-bn", "baseline-new", "p1", "{\"title\":\"登录\",\"assertions\":\"显示登录按钮\"}", true, now.plusSeconds(10));

        WebCaseRegressionComparisonResponse response = service.compare("run-1", "baseline-old");

        assertThat(response.available()).isTrue();
        assertThat(response.baselineRunId()).isEqualTo("baseline-old");
        assertThat(response.baselineOverallScore()).isEqualTo(8);
    }

    @Test
    void compareRejectsExplicitBaselineEqualToSelf() {
        Instant now = Instant.now();
        insertRun("run-1", 1L, now);

        assertThatThrownBy(() -> service.compare("run-1", "run-1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不能与当前任务相同");
    }

    @Test
    void compareRejectsExplicitBaselineFromDifferentProject() {
        Instant now = Instant.now();
        insertRun("run-1", 1L, now);
        insertRun("baseline-other", 2L, now);

        assertThatThrownBy(() -> service.compare("run-1", "baseline-other"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不在同一项目");
    }

    @Test
    void compareRejectsExplicitBaselineWithoutRating() {
        Instant now = Instant.now();
        insertRun("run-1", 1L, now);
        insertRun("baseline-1", 1L, now);

        assertThatThrownBy(() -> service.compare("run-1", "baseline-1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("尚未固化");
    }

    @Test
    void compareRejectsExplicitBaselineWithoutAcceptedArtifacts() {
        Instant now = Instant.now();
        insertRun("run-1", 1L, now);
        insertRun("baseline-1", 1L, now);
        insertRating("baseline-1", 1L, 8, now);

        assertThatThrownBy(() -> service.compare("run-1", "baseline-1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("没有已接受的 Web 用例");
    }

    @Test
    void compareRecallsDuplicateIdenticalCasesWithMultisetSemantics() {
        Instant now = Instant.now();
        insertRun("baseline-1", 1L, now);
        insertRun("run-1", 1L, now.plusSeconds(10));
        insertRating("baseline-1", 1L, 8, now);
        insertArtifact("art-b1", "baseline-1", "p1", "{\"title\":\"A\",\"assertions\":\"X\"}", true, now);
        insertArtifact("art-b2", "baseline-1", "p1", "{\"title\":\"A\",\"assertions\":\"X\"}", true, now);
        insertArtifact("art-1", "run-1", "p1", "{\"title\":\"A\",\"assertions\":\"X\"}", true, now.plusSeconds(10));

        WebCaseRegressionComparisonResponse response = service.compare("run-1", "baseline-1");

        PointComparison point = response.points().get(0);
        assertThat(point.baselineCount()).isEqualTo(2);
        assertThat(point.recalledCount()).isEqualTo(1);
        assertThat(point.lostCount()).isEqualTo(1);
        assertThat(point.addedCount()).isZero();
    }

    @Test
    void compareFlagsPointOnlyInBaselineOrCurrent() {
        Instant now = Instant.now();
        insertRun("baseline-1", 1L, now);
        insertRun("run-1", 1L, now.plusSeconds(10));
        insertRating("baseline-1", 1L, 8, now);
        insertArtifact("art-b1", "baseline-1", "p-only-baseline", "{\"title\":\"只有基准\",\"assertions\":\"X\"}", true, now);
        insertArtifact("art-1", "run-1", "p-only-current", "{\"title\":\"只有当前\",\"assertions\":\"Y\"}", true, now.plusSeconds(10));

        WebCaseRegressionComparisonResponse response = service.compare("run-1", "baseline-1");

        assertThat(response.points()).hasSize(2);
        PointComparison baselineOnly = response.points().get(0);
        assertThat(baselineOnly.pointId()).isEqualTo("p-only-baseline");
        assertThat(baselineOnly.recalledCount()).isZero();
        assertThat(baselineOnly.lostCount()).isEqualTo(1);
        assertThat(baselineOnly.addedCount()).isZero();
        PointComparison currentOnly = response.points().get(1);
        assertThat(currentOnly.pointId()).isEqualTo("p-only-current");
        assertThat(currentOnly.baselineCount()).isZero();
        assertThat(currentOnly.addedCount()).isEqualTo(1);
        assertThat(currentOnly.lostCount()).isZero();
    }

    @Test
    void compareAutoSelectBreaksTieDeterministically() {
        Instant now = Instant.now();
        insertRun("run-1", 1L, now.plusSeconds(40));
        insertRun("baseline-b", 1L, now);
        insertRun("baseline-a", 1L, now);
        insertRating("baseline-a", 1L, 8, now);
        insertRating("baseline-b", 1L, 8, now);
        insertArtifact("art-1", "run-1", "p1", "{\"title\":\"登录\",\"assertions\":\"显示登录按钮\"}", true, now.plusSeconds(40));
        insertArtifact("art-ba", "baseline-a", "p1", "{\"title\":\"登录\",\"assertions\":\"显示登录按钮\"}", true, now);
        insertArtifact("art-bb", "baseline-b", "p1", "{\"title\":\"登录\",\"assertions\":\"显示登录按钮\"}", true, now);

        WebCaseRegressionComparisonResponse response = service.compare("run-1", null);

        // 相同 created_at 时按 run_id 升序稳定取最小者,保证结果可复现。
        assertThat(response.baselineRunId()).isEqualTo("baseline-a");
    }

    private void insertRun(String runId, long projectId, Instant createdAt) {
        jdbcTemplate.update("insert into ai_web_case_agent_runs(run_id, project_id, created_at) values (?, ?, ?)",
            runId, projectId, Timestamp.from(createdAt));
    }

    private void insertRating(String runId, long projectId, int overallScore, Instant createdAt) {
        jdbcTemplate.update("insert into ai_web_case_quality_ratings(rating_id, run_id, project_id, overall_score, dimension_scores_json, comment, created_at, updated_at) values (?, ?, ?, ?, '{}', '', ?, ?)",
            "rating-" + runId, runId, projectId, overallScore, Timestamp.from(createdAt), Timestamp.from(createdAt));
    }

    private void insertArtifact(String artifactId, String runId, String pointId, String candidateJson, boolean accepted, Instant createdAt) {
        jdbcTemplate.update("insert into ai_web_case_agent_artifacts(artifact_id, run_id, point_id, artifact_type, candidate_json, accepted, created_at, updated_at) values (?, ?, ?, 'web_case', ?, ?, ?, ?)",
            artifactId, runId, pointId, candidateJson, accepted, Timestamp.from(createdAt), Timestamp.from(createdAt));
    }
}
