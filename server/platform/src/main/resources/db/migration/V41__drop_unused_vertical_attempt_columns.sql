-- T06 round-1 C-6: drop the three columns V39 added to ai_runtime_vertical_attempts.
-- They were reserved for attempt linkage, but the generic runtime attempts live in
-- ai_runtime_attempts (V40) with proper job/node fields, and nothing reads or writes
-- these three - dead schema is removed rather than kept as false wiring.
ALTER TABLE ai_runtime_vertical_attempts DROP COLUMN request_hash;
ALTER TABLE ai_runtime_vertical_attempts DROP COLUMN job_id;
ALTER TABLE ai_runtime_vertical_attempts DROP COLUMN node_key;
