package com.haitlify.aitest.platform.ai.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.haitlify.aitest.platform.ai.gateway.AiGenerationResult;
import com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade;
import com.haitlify.aitest.platform.ai.tool.AiToolRegistry;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * T09 bullet 6：多 Agent DAG 的节点真执行器——每个节点按其 Agent profile 运行
 * RULE / MODEL / TOOL / HUMAN 之一：
 *  · RULE  = 确定性规则（标 RULE_COMPUTED），如需求范围提取、质量评审、合并；
 *  · MODEL = 经统一 Runtime 的真实模型节点（task route = multi_agent_<role>），失败即
 *            节点失败，绝不伪造下游完成；
 *  · TOOL  = 内部工具真实执行（受 agent.allowedTools 约束）；
 *  · HUMAN = 人审门禁，节点停为 waiting_human，绝不自动 completed。
 */
@Service
class AiAgentNodeExecutor {

    record NodeExecution(String status, String resultOrigin, String acceptanceState, ObjectNode output,
                         String error) {}

    static final String STATUS_SUCCEEDED = "succeeded";
    static final String STATUS_FAILED = "failed";
    static final String STATUS_WAITING_HUMAN = "waiting_human";

    private final AiTaskExecutionFacade taskFacade;
    private final AiToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private static final int MAX_MODEL_ROUNDS_PER_RUN = 10;

    @org.springframework.beans.factory.annotation.Autowired
    AiAgentNodeExecutor(AiTaskExecutionFacade taskFacade, AiToolRegistry toolRegistry, ObjectMapper objectMapper,
                        org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.taskFacade = taskFacade;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    NodeExecution execute(BuiltInAgentDefinition agent, Long projectId, String runId, JsonNode input,
                          JsonNode previousOutput) {
        String executorKind = switch (agent.agentId()) {
            case "test-design" -> "MODEL";
            case "automation-suggestion" -> "TOOL";
            case "human-gate" -> "HUMAN";
            default -> "RULE";
        };
        NodeExecution execution = switch (agent.agentId()) {
            case "requirement-understanding" -> ruleRequirementUnderstanding(input, previousOutput);
            case "quality-review" -> ruleQualityReview(previousOutput);
            case "merge-coordinator" -> ruleMerge(previousOutput);
            case "test-design" -> modelTestDesign(agent, projectId, runId, input, previousOutput);
            case "automation-suggestion" -> toolAutomationSuggestion(agent, projectId);
            case "human-gate" -> humanGate(agent);
            default -> failed("No executor registered for agent: " + agent.agentId());
        };
        if (STATUS_SUCCEEDED.equals(execution.status()) && execution.output() != null) {
            execution.output().put("summary", agent.name() + " completed via " + executorKind + " executor");
        }
        return execution;
    }

    // ---------- RULE ----------

    private NodeExecution ruleRequirementUnderstanding(JsonNode input, JsonNode previousOutput) {
        String text = previousOutput == null ? "" : previousOutput.toPrettyString();
        List<String> scopeItems = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.contains("需求") || trimmed.contains("规则") || trimmed.contains("范围")
                || trimmed.contains("requirement") || trimmed.contains("rule")) {
                if (scopeItems.size() < 20 && !trimmed.isBlank()) {
                    scopeItems.add(trimmed.length() > 160 ? trimmed.substring(0, 160) : trimmed);
                }
            }
        }
        ObjectNode output = objectMapper.createObjectNode()
            .put("status", STATUS_SUCCEEDED)
            .put("agentId", "requirement-understanding")
            .put("resultOrigin", "RULE_COMPUTED")
            .put("reviewedChars", text.length());
        ArrayNode items = output.putArray("scopeItems");
        scopeItems.forEach(items::add);
        output.put("scopeItemCount", scopeItems.size());
        output.set("inputEcho", input == null ? objectMapper.createObjectNode() : input);
        return new NodeExecution(STATUS_SUCCEEDED, "RULE_COMPUTED", "PENDING_HUMAN_CONFIRMATION", output, "");
    }

    private NodeExecution ruleQualityReview(JsonNode previousOutput) {
        ObjectNode output = objectMapper.createObjectNode()
            .put("status", STATUS_SUCCEEDED)
            .put("agentId", "quality-review")
            .put("resultOrigin", "RULE_COMPUTED");
        ArrayNode findings = output.putArray("findings");
        JsonNode points = previousOutput == null ? null : firstArray(previousOutput, "draftPoints", "testPoints", "points");
        if (points == null || points.isEmpty()) {
            findings.add("no draft points found in upstream output; nothing to review");
        } else {
            Map<String, Integer> titles = new LinkedHashMap<>();
            int missingEvidence = 0;
            int weakExpected = 0;
            for (JsonNode point : points) {
                String title = point.path("title").asText("");
                if (!title.isBlank()) {
                    titles.merge(title, 1, Integer::sum);
                }
                if (point.path("evidence").isMissingNode() && point.path("evidenceId").asText("").isBlank()) {
                    missingEvidence++;
                }
                if (point.path("expectedResult").asText("").length() < 4) {
                    weakExpected++;
                }
            }
            if (missingEvidence > 0) {
                findings.add(missingEvidence + " draft point(s) missing evidence references");
            }
            if (weakExpected > 0) {
                findings.add(weakExpected + " draft point(s) with unverifiable expected results");
            }
            long duplicates = titles.values().stream().filter(count -> count > 1).count();
            if (duplicates > 0) {
                findings.add(duplicates + " duplicated draft point title(s)");
            }
            if (findings.isEmpty()) {
                findings.add("no blocking quality findings");
            }
        }
        return new NodeExecution(STATUS_SUCCEEDED, "RULE_COMPUTED", "PENDING_HUMAN_CONFIRMATION", output, "");
    }

    private NodeExecution ruleMerge(JsonNode previousOutput) {
        ObjectNode output = objectMapper.createObjectNode()
            .put("status", "merge_ready")
            .put("agentId", "merge-coordinator")
            .put("resultOrigin", "RULE_COMPUTED")
            .put("mergedFromChars", previousOutput == null ? 0 : previousOutput.toPrettyString().length());
        output.set("merged", previousOutput == null ? objectMapper.createObjectNode() : previousOutput);
        return new NodeExecution(STATUS_SUCCEEDED, "RULE_COMPUTED", "PENDING_HUMAN_CONFIRMATION", output, "");
    }

    // ---------- MODEL ----------

    private NodeExecution modelTestDesign(BuiltInAgentDefinition agent, Long projectId, String runId,
                                          JsonNode input, JsonNode previousOutput) {
        // T09 bullet 7：模型轮次上限——同一 run 的既有 attempt 达到上限即拒绝，不再继续烧调用。
        if (runId != null && !runId.isBlank()) {
            Integer usedRounds = jdbcTemplate.queryForObject(
                "select count(*) from ai_runtime_attempts where run_id = ?", Integer.class, runId);
            if (usedRounds != null && usedRounds >= MAX_MODEL_ROUNDS_PER_RUN) {
                return failed("Model round limit exceeded for run: " + runId);
            }
        }
        ObjectNode prompt = objectMapper.createObjectNode();
        prompt.put("agentId", agent.agentId());
        prompt.put("agentPrompt", agent.promptTemplate());
        prompt.put("instruction", "基于上游输出生成结构化测试点 JSON（数组，元素含 title/objective/steps/expectedResult/evidence）；只输出 JSON。");
        prompt.set("input", input == null ? objectMapper.createObjectNode() : input);
        prompt.set("previousOutput", previousOutput == null ? objectMapper.createObjectNode() : previousOutput);
        AiGenerationResult result;
        try {
            result = taskFacade.generate(new AiTaskExecutionFacade.TaskExecution(
                "multi_agent_" + agent.agentRole(), projectId, "system", null, prompt, null));
        } catch (RuntimeException failure) {
            return failed("Model node failed: " + clip(failure.getMessage() == null
                ? failure.getClass().getSimpleName() : failure.getMessage()));
        }
        if (result == null || !result.success()) {
            String reason = result == null ? "no result"
                : (result.errorMessage() == null || result.errorMessage().isBlank()
                    ? result.retryReason() : result.errorMessage()
                        + (result.retryReason() == null || result.retryReason().isBlank() ? "" : " (" + result.retryReason() + ")"));
            return failed("Model node failed: " + clip(reason));
        }
        JsonNode parsed = parseJsonLoose(result.content());
        if (parsed == null) {
            return failed("Model node returned unparsable output");
        }
        ObjectNode output = objectMapper.createObjectNode()
            .put("status", STATUS_SUCCEEDED)
            .put("agentId", agent.agentId())
            .put("resultOrigin", "MODEL_GENERATED")
            .put("acceptanceState", "PENDING_HUMAN_CONFIRMATION");
        String requestId = result.requestId() == null ? "" : result.requestId();
        output.put("providerCallId", requestId);
        if (requestId.isBlank()) {
            // 回执缺失如实标记：结果可以继续，但审计必须能看到没有原生 call id。
            output.put("callIdMissing", true);
        }
        output.set("draftPoints", parsed);
        return new NodeExecution(STATUS_SUCCEEDED, "MODEL_GENERATED", "PENDING_HUMAN_CONFIRMATION", output, "");
    }

    // ---------- TOOL ----------

    private NodeExecution toolAutomationSuggestion(BuiltInAgentDefinition agent, Long projectId) {
        String toolId = "check-coverage-gaps";
        if (agent.allowedTools() != null && agent.allowedTools().isArray() && agent.allowedTools().size() > 0) {
            boolean allowed = false;
            for (JsonNode allowedTool : agent.allowedTools()) {
                if (toolId.equals(allowedTool.asText(""))) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                return failed("Agent is not allowed to run tool: " + toolId);
            }
        }
        var tool = toolRegistry.findTool(toolId);
        if (tool.isEmpty()) {
            return failed("Tool not registered: " + toolId);
        }
        try {
            JsonNode coverage = tool.get().dryRun(objectMapper.createObjectNode().put("projectId", projectId));
            String callId = "tool-call-" + java.util.UUID.randomUUID();
            ObjectNode output = objectMapper.createObjectNode()
                .put("status", STATUS_SUCCEEDED)
                .put("agentId", agent.agentId())
                .put("resultOrigin", "RULE_COMPUTED")
                .put("toolId", toolId)
                .put("callId", callId);
            output.set("coverage", coverage);
            return new NodeExecution(STATUS_SUCCEEDED, "RULE_COMPUTED", "PENDING_HUMAN_CONFIRMATION", output, "");
        } catch (RuntimeException failure) {
            return failed("Tool node failed (" + toolId + "): " + clip(failure.getMessage() == null
                ? failure.getClass().getSimpleName() : failure.getMessage()));
        }
    }

    // ---------- HUMAN ----------

    private NodeExecution humanGate(BuiltInAgentDefinition agent) {
        ObjectNode output = objectMapper.createObjectNode()
            .put("status", STATUS_WAITING_HUMAN)
            .put("agentId", agent.agentId())
            .put("resultOrigin", "BLOCKED")
            .put("acceptanceState", "PENDING_HUMAN_CONFIRMATION")
            .put("message", "human decision required; node will not auto-complete");
        return new NodeExecution(STATUS_WAITING_HUMAN, "BLOCKED", "PENDING_HUMAN_CONFIRMATION", output, "");
    }

    // ---------- helpers ----------

    private NodeExecution failed(String message) {
        String error = clip(message);
        ObjectNode output = objectMapper.createObjectNode()
            .put("status", STATUS_FAILED)
            .put("resultOrigin", "BLOCKED")
            .put("acceptanceState", "BLOCKED")
            .put("errorSummary", error);
        return new NodeExecution(STATUS_FAILED, "BLOCKED", "BLOCKED", output, error);
    }

    private JsonNode firstArray(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode candidate = node.path(field);
            if (candidate.isArray()) {
                return candidate;
            }
        }
        return null;
    }

    private JsonNode parseJsonLoose(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String text = content.trim();
        try {
            JsonNode direct = objectMapper.readTree(text);
            if (direct != null) {
                return direct;
            }
        } catch (Exception ignored) {
            // fall through to bracket extraction
        }
        int objectStart = text.indexOf('{');
        int arrayStart = text.indexOf('[');
        int start = objectStart < 0 ? arrayStart : (arrayStart < 0 ? objectStart : Math.min(objectStart, arrayStart));
        int end = Math.max(text.lastIndexOf('}'), text.lastIndexOf(']'));
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return objectMapper.readTree(text.substring(start, end + 1));
        } catch (Exception malformed) {
            return null;
        }
    }

    private static String clip(String message) {
        if (message == null) {
            return "";
        }
        String single = message.replaceAll("\\s+", " ");
        return single.length() <= 240 ? single : single.substring(0, 237) + "...";
    }
}
