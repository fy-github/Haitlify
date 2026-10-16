package com.haitlify.aitest.platform.ai.quality;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import tools.jackson.databind.ObjectMapper;

class WebCaseObjectiveQualityServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JdbcTemplate jdbcTemplate;
    private WebCaseObjectiveQualityService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:web-case-objective-quality;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("drop table if exists ai_web_case_objective_scores");
        jdbcTemplate.execute("drop table if exists ai_web_case_agent_artifacts");
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
              updated_at timestamp not null
            )
            """);
        jdbcTemplate.execute("""
            create table ai_web_case_objective_scores (
              id bigint primary key auto_increment,
              score_id varchar(128) not null,
              run_id varchar(128) not null,
              project_id bigint not null,
              overall_score int not null,
              dimension_scores_json text not null default '{}',
              created_at timestamp not null,
              updated_at timestamp not null,
              constraint uk_objective_score_id unique (score_id),
              constraint uk_objective_score_run unique (run_id)
            )
            """);
        service = new WebCaseObjectiveQualityService(jdbcTemplate, objectMapper);
    }

    @Test
    void backfillUsesLatestRepairRoundAndIsIdempotent() {
        insertRun("run-1", 1L, "succeeded", "[\"p1\"]");
        insertArtifact("art-1", "run-1", "p1", 0, "{\"title\":\"登录成功\",\"naturalSteps\":\"打开页面\",\"naturalExpectedResult\":\"进入工作台\",\"assertions\":\"assertVisible .dashboard\"}", "[]", Instant.now());
        insertArtifact("art-2", "run-1", "p1", 1, "{\"title\":\"登录成功\",\"naturalSteps\":\"打开页面\",\"naturalExpectedResult\":\"进入工作台\",\"assertions\":\"assertVisible .dashboard\"}", "[\"locator-1\"]", Instant.now());

        service.backfillProject(1L);
        service.backfillProject(1L); // 幂等:uk run_id + 已存在跳过

        List<String> scoreIds = jdbcTemplate.queryForList("select score_id from ai_web_case_objective_scores where run_id = 'run-1'", String.class);
        assertThat(scoreIds).hasSize(1);
        String dimensions = jdbcTemplate.queryForObject("select dimension_scores_json from ai_web_case_objective_scores where run_id = 'run-1'", String.class);
        // 取最新 repair_round:round 1 绑定了证据,source_traceability 应为满分而非 round 0 的 0 分。
        assertThat(dimensions).contains("\"source_traceability\":10");
        assertThat(dimensions).contains("\"testability\":10");
        assertThat(dimensions).contains("\"automation_readiness\":10");
        assertThat(dimensions).contains("\"review_risk\":10");
        assertThat(dimensions).contains("\"duplicate_risk\":10");
        assertThat(dimensions).contains("\"coverage\":10");
    }

    @Test
    void backfillSkipsRunWithoutAcceptedArtifacts() {
        insertRun("run-1", 1L, "succeeded", "[\"p1\"]");
        service.backfillProject(1L);
        assertThat(jdbcTemplate.queryForList("select score_id from ai_web_case_objective_scores", String.class)).isEmpty();
    }

    @Test
    void backfillSkipsQueuedRun() {
        insertRun("run-1", 1L, "queued", "[\"p1\"]");
        insertArtifact("art-1", "run-1", "p1", 0, "{}", "[]", Instant.now());
        service.backfillProject(1L);
        assertThat(jdbcTemplate.queryForList("select score_id from ai_web_case_objective_scores", String.class)).isEmpty();
    }

    private void insertRun(String runId, long projectId, String status, String requestedPointIds) {
        jdbcTemplate.update("insert into ai_web_case_agent_runs(run_id, project_id, status, requested_point_ids_json, created_at) values (?, ?, ?, ?, ?)",
            runId, projectId, status, requestedPointIds, Timestamp.from(Instant.now()));
    }

    private void insertArtifact(String artifactId, String runId, String pointId, int repairRound, String candidateJson, String evidenceIdsJson, Instant createdAt) {
        jdbcTemplate.update("insert into ai_web_case_agent_artifacts(artifact_id, run_id, point_id, artifact_type, candidate_json, evidence_ids_json, accepted, repair_round, created_at, updated_at) values (?, ?, ?, 'web_case', ?, ?, true, ?, ?, ?)",
            artifactId, runId, pointId, candidateJson, evidenceIdsJson, repairRound, Timestamp.from(createdAt), Timestamp.from(createdAt));
    }
}
