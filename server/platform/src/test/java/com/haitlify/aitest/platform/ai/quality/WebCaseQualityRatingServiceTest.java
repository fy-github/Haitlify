package com.haitlify.aitest.platform.ai.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import tools.jackson.databind.ObjectMapper;

class WebCaseQualityRatingServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JdbcTemplate jdbcTemplate;
    private WebCaseQualityRatingService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:web-case-quality-rating;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("drop table if exists ai_web_case_quality_ratings");
        jdbcTemplate.execute("drop table if exists ai_web_case_agent_runs");
        jdbcTemplate.execute("""
            create table ai_web_case_agent_runs (
              run_id varchar(128) primary key,
              project_id bigint not null
            )
            """);
        jdbcTemplate.execute("""
            create table ai_web_case_quality_ratings (
              id bigint primary key auto_increment,
              rating_id varchar(128) not null,
              run_id varchar(128) not null,
              project_id bigint not null,
              overall_score integer not null default 0,
              dimension_scores_json text not null default '{}',
              comment text not null default '',
              created_at timestamp not null,
              updated_at timestamp not null,
              constraint uk_rating_id unique (rating_id),
              constraint uk_rating_run unique (run_id)
            )
            """);
        jdbcTemplate.update("insert into ai_web_case_agent_runs(run_id, project_id) values (?, ?)", "web-agent-1", 1L);
        service = new WebCaseQualityRatingService(jdbcTemplate, objectMapper);
    }

    @Test
    void rateStoresOverallAndDimensionsAndMarksBaselineAboveThreshold() {
        WebCaseQualityRatingResponse rating = service.rate("web-agent-1",
            new RateWebCaseQualityRequest(8, Map.of("coverage", 8, "testability", 7), "覆盖较全"));

        assertThat(rating.overallScore()).isEqualTo(8);
        assertThat(rating.baseline()).isTrue();
        assertThat(rating.dimensionScores()).containsEntry("coverage", 8).containsEntry("testability", 7);
        assertThat(rating.comment()).isEqualTo("覆盖较全");
        assertThat(rating.projectId()).isEqualTo(1L);
    }

    @Test
    void baselineFlagIsFalseBelowThreshold() {
        WebCaseQualityRatingResponse rating = service.rate("web-agent-1", new RateWebCaseQualityRequest(6, Map.of(), ""));

        assertThat(rating.baseline()).isFalse();
    }

    @Test
    void reRatingSameRunUpdatesInsteadOfDuplicating() {
        service.rate("web-agent-1", new RateWebCaseQualityRequest(6, Map.of(), ""));
        service.rate("web-agent-1", new RateWebCaseQualityRequest(9, Map.of("coverage", 9), "重评更高"));

        Integer count = jdbcTemplate.queryForObject("select count(*) from ai_web_case_quality_ratings where run_id = 'web-agent-1'", Integer.class);
        assertThat(count).isEqualTo(1);
        assertThat(service.get("web-agent-1").overallScore()).isEqualTo(9);
    }

    @Test
    void rejectsOutOfRangeOverallScore() {
        assertThatThrownBy(() -> service.rate("web-agent-1", new RateWebCaseQualityRequest(11, Map.of(), "")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("总分必须在 1-10 之间");
    }

    @Test
    void rejectsUnknownRun() {
        assertThatThrownBy(() -> service.rate("web-agent-missing", new RateWebCaseQualityRequest(7, Map.of(), "")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Agent 任务不存在");
    }

    @Test
    void listsRatingsForProjectNewestFirst() {
        jdbcTemplate.update("insert into ai_web_case_agent_runs(run_id, project_id) values (?, ?)", "web-agent-2", 1L);
        service.rate("web-agent-1", new RateWebCaseQualityRequest(5, Map.of(), ""));
        service.rate("web-agent-2", new RateWebCaseQualityRequest(8, Map.of(), ""));

        List<WebCaseQualityRatingResponse> items = service.list(1L).items();

        assertThat(items).hasSize(2);
        assertThat(items.get(0).runId()).isEqualTo("web-agent-2");
        assertThat(items.get(1).runId()).isEqualTo("web-agent-1");
    }
}
