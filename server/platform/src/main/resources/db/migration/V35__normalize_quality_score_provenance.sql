-- Quality scores stored the field-level label RULE_COMPUTED as both the artifact origin and the
-- acceptance state (V24 gave the columns that default, and the writer copied it through). RULE_COMPUTED
-- says *how a field was computed*; it is not an artifact origin and certainly not an acceptance state —
-- a score wrote 'RULE_COMPUTED' where the state machine only allows
-- PENDING_HUMAN_CONFIRMATION / ACCEPTED / EXECUTED / DEGRADED / BLOCKED.
--
-- Normalize to the artifact-level origin (RULE_EXTRACTED) plus the real state of a freshly computed,
-- never-confirmed score (PENDING_HUMAN_CONFIRMATION), then close both vocabularies at the schema level.
UPDATE ai_quality_scores
SET result_origin = 'RULE_EXTRACTED'
WHERE result_origin NOT IN ('MODEL_GENERATED', 'RULE_EXTRACTED', 'TOOL_OBSERVED', 'HUMAN_EDITED', 'BLOCKED');

UPDATE ai_quality_scores
SET acceptance_state = 'PENDING_HUMAN_CONFIRMATION'
WHERE acceptance_state NOT IN ('PENDING_HUMAN_CONFIRMATION', 'ACCEPTED', 'EXECUTED', 'DEGRADED', 'BLOCKED');

ALTER TABLE ai_quality_scores ALTER COLUMN result_origin SET DEFAULT 'BLOCKED';
ALTER TABLE ai_quality_scores ALTER COLUMN acceptance_state SET DEFAULT 'BLOCKED';

ALTER TABLE ai_quality_scores
    ADD CONSTRAINT ck_ai_quality_scores_origin
        CHECK (result_origin IN ('MODEL_GENERATED', 'RULE_EXTRACTED', 'TOOL_OBSERVED', 'HUMAN_EDITED', 'BLOCKED'));

ALTER TABLE ai_quality_scores
    ADD CONSTRAINT ck_ai_quality_scores_acceptance_state
        CHECK (acceptance_state IN ('PENDING_HUMAN_CONFIRMATION', 'ACCEPTED', 'EXECUTED', 'DEGRADED', 'BLOCKED'));
