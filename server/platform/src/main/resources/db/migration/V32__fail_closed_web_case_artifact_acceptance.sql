-- T-Review-3 fail-closed repair.
--
-- Artifacts written before the acceptance gate existed could carry accepted = TRUE while
-- acceptance_state stayed at its column default (PENDING_HUMAN_CONFIRMATION) and no Gate audit row was
-- ever recorded — a state that claims human delivery without human confirmation. Those historical rows
-- have no gate record to justify delivery, so they are returned to not-accepted and stay pending review.
UPDATE ai_web_case_agent_artifacts
SET accepted = FALSE
WHERE accepted = TRUE AND acceptance_state <> 'ACCEPTED';

-- Make the invalid combination impossible from now on, including for writers that only set `accepted`.
ALTER TABLE ai_web_case_agent_artifacts
    ADD CONSTRAINT ck_ai_web_case_agent_artifact_acceptance
        CHECK (accepted = FALSE OR acceptance_state = 'ACCEPTED');

-- A row whose writer never stated an origin must not claim model generation by default.
ALTER TABLE ai_web_case_agent_artifacts ALTER COLUMN result_origin SET DEFAULT 'BLOCKED';
