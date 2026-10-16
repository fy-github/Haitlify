-- Close the artifact-level origin vocabulary at the schema level.
--
-- 'MODEL_PROPOSED' is a *field*-level label, but it was the column default and the pre-V32 plan writer
-- stored it as the artifact origin, so shipped rows claimed a model proposal as an artifact origin.
-- V33 normalized those rows; this constraint stops the whole class of mistake: only the artifact-level
-- vocabulary accepted by the code (AiArtifactOrigin plus the fail-closed BLOCKED) can be stored, so a
-- writer that invents a label fails loudly instead of silently claiming an origin it cannot support.
ALTER TABLE ai_web_case_agent_artifacts
    ADD CONSTRAINT ck_ai_web_case_agent_artifact_origin
        CHECK (result_origin IN ('MODEL_GENERATED', 'RULE_EXTRACTED', 'TOOL_OBSERVED', 'HUMAN_EDITED', 'BLOCKED'));
