-- Normalize web-case artifact origins onto the artifact-level vocabulary.
--
-- The pre-V32 plan writer stored 'MODEL_PROPOSED' as the artifact origin, which is a *field*-level label
-- ('the model proposed this field') and never an artifact-level claim. Those rows also carry no
-- auditable provider evidence: no provider request id was recorded for them and no Gate audit exists,
-- so under the fail-closed rule their origin is BLOCKED (they remain PENDING_HUMAN_CONFIRMATION and are
-- not exportable, since export requires accepted = TRUE AND acceptance_state = 'ACCEPTED').
--
-- V32 is already applied to existing databases and must not be edited, so this repair ships separately.
UPDATE ai_web_case_agent_artifacts
SET result_origin = 'BLOCKED'
WHERE result_origin NOT IN ('MODEL_GENERATED', 'TOOL_OBSERVED', 'RULE_EXTRACTED', 'HUMAN_EDITED', 'BLOCKED');
