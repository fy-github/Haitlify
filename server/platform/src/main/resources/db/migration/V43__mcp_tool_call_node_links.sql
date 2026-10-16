-- T09 bullet 7: link every MCP tool call to the node and runtime attempt
-- that produced it, so tool results can be traced back to the model round
-- (or DAG node) that requested them.
ALTER TABLE ai_tool_call_records ADD COLUMN node_id VARCHAR(128) NOT NULL DEFAULT '';
ALTER TABLE ai_tool_call_records ADD COLUMN attempt_id VARCHAR(128) NOT NULL DEFAULT '';
