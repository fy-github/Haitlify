-- AI analysis liveness marker. It is updated by the worker heartbeat while a run is waiting
-- for a provider response, so clients can distinguish a long-running run from a dead worker.
ALTER TABLE ai_analysis_runs ADD COLUMN heartbeat_at TIMESTAMP;
