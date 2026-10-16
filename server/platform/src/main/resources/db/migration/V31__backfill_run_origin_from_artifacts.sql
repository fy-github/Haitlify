-- Backfill run-level provenance for runs created before AiRunOriginSyncService existed.
-- The rule matches the service exactly: a run mirrors the artifacts it produced, and never claims a
-- model origin (MODEL_GENERATED) unless one of its artifacts is backed by real runtime evidence.
UPDATE ai_analysis_runs
SET result_origin = (
        SELECT CASE
            WHEN SUM(CASE WHEN p.result_origin = 'MODEL_GENERATED' THEN 1 ELSE 0 END) > 0 THEN 'MODEL_GENERATED'
            WHEN SUM(CASE WHEN p.result_origin = 'TOOL_OBSERVED' THEN 1 ELSE 0 END) > 0 THEN 'TOOL_OBSERVED'
            WHEN SUM(CASE WHEN p.result_origin = 'RULE_EXTRACTED' THEN 1 ELSE 0 END) > 0 THEN 'RULE_EXTRACTED'
            WHEN SUM(CASE WHEN p.result_origin = 'HUMAN_EDITED' THEN 1 ELSE 0 END) > 0 THEN 'HUMAN_EDITED'
            ELSE 'BLOCKED'
        END
        FROM ai_draft_test_points p WHERE p.run_id = ai_analysis_runs.id
    ),
    acceptance_state = (
        SELECT CASE
            WHEN SUM(CASE WHEN p.acceptance_state = 'PENDING_HUMAN_CONFIRMATION' THEN 1 ELSE 0 END) > 0 THEN 'PENDING_HUMAN_CONFIRMATION'
            WHEN SUM(CASE WHEN p.acceptance_state = 'ACCEPTED' THEN 1 ELSE 0 END) > 0 THEN 'ACCEPTED'
            ELSE 'BLOCKED'
        END
        FROM ai_draft_test_points p WHERE p.run_id = ai_analysis_runs.id
    )
WHERE status = 'succeeded';
