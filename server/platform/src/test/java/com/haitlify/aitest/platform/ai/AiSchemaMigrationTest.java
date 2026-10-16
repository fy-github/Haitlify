package com.haitlify.aitest.platform.ai;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class AiSchemaMigrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void createsAiAgentFoundationTables() {
        Set<String> tables = jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where lower(table_schema) = 'public'",
                String.class
            )
            .stream()
            .map(String::toLowerCase)
            .collect(Collectors.toSet());

        assertTrue(tables.contains("ai_system_configs"));
        assertTrue(tables.contains("ai_project_configs"));
        assertTrue(tables.contains("ai_rules"));
        assertTrue(tables.contains("ai_skills"));
        assertTrue(tables.contains("ai_skill_overrides"));
        assertTrue(tables.contains("ai_mcp_servers"));
        assertTrue(tables.contains("ai_source_documents"));
        assertTrue(tables.contains("ai_source_chunks"));
        assertTrue(tables.contains("ai_analysis_runs"));
        assertTrue(tables.contains("ai_draft_test_points"));
        assertTrue(tables.contains("ai_review_actions"));
        assertTrue(tables.contains("ai_call_records"));
        assertTrue(tables.contains("ai_run_policies"));
        assertTrue(tables.contains("ai_run_events"));
    }

    /**
     * Quality scores used to store the field-level label RULE_COMPUTED as both origin and acceptance
     * state. Both vocabularies are closed now, so a writer cannot smuggle a field label into an
     * artifact-level provenance column again.
     */
    @Test
    void qualityScoreProvenanceColumnsRejectFieldLevelLabels() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.update("""
            insert into ai_quality_scores(
              score_id, run_id, quality_dimension, score, score_level, score_reason,
              evidence_json, result_origin, acceptance_state, created_at, updated_at
            ) values ('probe-score', 'probe-run', 'coverage', 0.8, 'ok', 'probe', '{}', 'RULE_COMPUTED', 'PENDING_HUMAN_CONFIRMATION', now(), now())
            """))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.update("""
            insert into ai_quality_scores(
              score_id, run_id, quality_dimension, score, score_level, score_reason,
              evidence_json, result_origin, acceptance_state, created_at, updated_at
            ) values ('probe-score-2', 'probe-run', 'coverage', 0.8, 'ok', 'probe', '{}', 'RULE_EXTRACTED', 'RULE_COMPUTED', now(), now())
            """))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        jdbcTemplate.update("""
            insert into ai_quality_scores(
              score_id, run_id, quality_dimension, score, score_level, score_reason,
              evidence_json, result_origin, acceptance_state, created_at, updated_at
            ) values ('probe-score-ok', 'probe-run', 'coverage', 0.8, 'ok', 'probe', '{}', 'RULE_EXTRACTED', 'PENDING_HUMAN_CONFIRMATION', now(), now())
            """);
    }
}
