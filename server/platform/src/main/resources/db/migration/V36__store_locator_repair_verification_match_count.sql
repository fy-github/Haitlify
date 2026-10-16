-- Uniqueness dimension for locator repair verifications (reviewer round 19, A2.21/A2.22).
-- A candidate that resolves to anything other than exactly one element is not a usable repair,
-- even when the single matched element is visible/enabled/actionable: without this column the
-- verification row could carry visible/enabled/receives_events/correct_page=true while the
-- verification verdict was `locator_not_actionable`, so a failed verification could still create
-- a proposal and rewrite the knowledge entry. The count is now stored at verification time and
-- re-checked on proposal creation and acceptance. Existing rows default to 0 (fail-closed).
ALTER TABLE ai_locator_repair_verifications
    ADD COLUMN matched_element_count INT NOT NULL DEFAULT 0;
