package com.haitlify.aitest.platform.ai.analysis;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for run-level provenance: the analysis run mirrors the artifacts it actually
 * produced. A run may only claim MODEL_GENERATED / PENDING_HUMAN_CONFIRMATION while at least one artifact
 * is backed by real runtime evidence; rule/tool derived artifacts are reported as such (never upgraded to
 * a model claim), and a run without artifacts stays BLOCKED. status=succeeded alone is never provenance.
 */
@Component
public class AiRunOriginSyncService {

    private final JdbcTemplate jdbcTemplate;

    AiRunOriginSyncService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void syncFromArtifacts(Long runPk) {
        jdbcTemplate.update("""
            update ai_analysis_runs
            set result_origin = case
                    when exists (select 1 from ai_draft_test_points p where p.run_id = ? and p.result_origin = 'MODEL_GENERATED') then 'MODEL_GENERATED'
                    when exists (select 1 from ai_draft_test_points p where p.run_id = ? and p.result_origin = 'TOOL_OBSERVED') then 'TOOL_OBSERVED'
                    when exists (select 1 from ai_draft_test_points p where p.run_id = ? and p.result_origin = 'RULE_EXTRACTED') then 'RULE_EXTRACTED'
                    when exists (select 1 from ai_draft_test_points p where p.run_id = ? and p.result_origin = 'HUMAN_EDITED') then 'HUMAN_EDITED'
                    else 'BLOCKED' end,
                acceptance_state = case
                    when exists (select 1 from ai_draft_test_points p where p.run_id = ? and p.acceptance_state = 'PENDING_HUMAN_CONFIRMATION') then 'PENDING_HUMAN_CONFIRMATION'
                    when exists (select 1 from ai_draft_test_points p where p.run_id = ? and p.acceptance_state = 'ACCEPTED') then 'ACCEPTED'
                    else 'BLOCKED' end,
                updated_at = ?
            where id = ?
            """, runPk, runPk, runPk, runPk, runPk, runPk, Timestamp.from(Instant.now()), runPk);
    }
}
