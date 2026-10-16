package com.haitlify.aitest.platform.ai.agent;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.haitlify.aitest.platform.ai.gateway.AiGenerationResult;
import com.haitlify.aitest.platform.ai.gateway.AiGatewaySettingsService;
import com.haitlify.aitest.platform.ai.gateway.ResolvedAiGatewaySettings;
import com.haitlify.aitest.platform.ai.call.AiCallRecordPayloads;
import com.haitlify.aitest.platform.ai.runtime.artifact.AiArtifactGateAuditService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
@Transactional
class AiMultiAgentRunService {


    private final AiAgentRegistry registry = new AiAgentRegistry();
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AiAgentHandoffService handoffService;
    private final AiAgentCritiqueService critiqueService;
    private final com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade taskFacade;
    private final AiGatewaySettingsService aiGatewaySettingsService;
    private final AiArtifactGateAuditService gateAuditService;
    private final AiAgentNodeExecutor nodeExecutor;

    AiMultiAgentRunService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        AiAgentHandoffService handoffService,
        AiAgentCritiqueService critiqueService,
        com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade taskFacade,
        AiGatewaySettingsService aiGatewaySettingsService,
        AiAgentNodeExecutor nodeExecutor
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.handoffService = handoffService;
        this.critiqueService = critiqueService;
        this.taskFacade = taskFacade;
        // settingsService 仅用于调用记录（provider/model 元数据），模型调用路径一律走 facade。
        this.aiGatewaySettingsService = aiGatewaySettingsService;
        this.gateAuditService = new AiArtifactGateAuditService(jdbcTemplate);
        this.nodeExecutor = nodeExecutor;
    }

    AiMultiAgentRunResponse createRun(CreateMultiAgentRunRequest request) {
        Instant now = Instant.now();
        String runId = "multi-agent-run-" + UUID.randomUUID();
        String graphId = "multi-agent-graph-" + UUID.randomUUID();
        JsonNode inputSnapshot = request.inputSummary() == null ? objectMapper.createObjectNode() : request.inputSummary();

        jdbcTemplate.update(
            """
            insert into ai_agent_run_graphs(
              graph_id, run_id, project_id, status, input_snapshot_json, output_snapshot_json,
              error_summary, result_origin, acceptance_state, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            graphId,
            runId,
            request.projectId(),
            "running",
            stringify(inputSnapshot),
            "{}",
            "",
            "BLOCKED",
            "BLOCKED",
            now,
            now
        );
        Long graphPk = jdbcTemplate.queryForObject(
            "select id from ai_agent_run_graphs where graph_id = ?",
            Long.class,
            graphId
        );

        AiReviewInvocation reviewInvocation = runAiReviewIfRequested(request.projectId(), runId, inputSnapshot);
        String finalStatus = reviewInvocation.failed() ? "failed" : "merge_ready";
        String errorSummary = reviewInvocation.failed()
            ? reviewInvocation.output().path("errorSummary").asText("AI评审失败")
            : "";
        JsonNode previousOutput = reviewInvocation.output();
        String previousAgentId = "";
        String previousNodeId = "";
        boolean failed = reviewInvocation.failed();
        boolean waitingHuman = false;
        List<BuiltInAgentDefinition> agents = registry.all();
        for (int index = 0; index < agents.size(); index++) {
            BuiltInAgentDefinition agent = agents.get(index);
            String nodeId = "multi-agent-node-" + UUID.randomUUID();
            String status;
            String nodeError = "";
            ObjectNode nodeInput = objectMapper.createObjectNode()
                .put("targetCaseKind", textOrDefault(request.targetCaseKind(), "functional"))
                .put("agentId", agent.agentId());
            nodeInput.set("previousOutput", previousOutput);

            String resultOrigin;
            String acceptanceState;
            ObjectNode nodeOutput;
            JsonNode currentOutput;
            if (failed || waitingHuman) {
                // T09 bullet 6：失败/等待人审后一律 skipped——绝不伪造下游完成。
                status = "skipped";
                nodeOutput = objectMapper.createObjectNode()
                    .put("status", "skipped")
                    .put("reason", failed ? "upstream_failure" : "waiting_human");
                currentOutput = nodeOutput;
                resultOrigin = "BLOCKED";
                acceptanceState = "BLOCKED";
            } else {
                // 真执行器：RULE / MODEL / TOOL / HUMAN（模型节点失败即失败，无假成功）。
                AiAgentNodeExecutor.NodeExecution execution =
                    nodeExecutor.execute(agent, request.projectId(), runId, nodeInput, previousOutput);
                status = execution.status();
                nodeError = execution.error();
                nodeOutput = execution.output();
                currentOutput = nodeOutput;
                resultOrigin = execution.resultOrigin();
                acceptanceState = execution.acceptanceState();
                if (AiAgentNodeExecutor.STATUS_FAILED.equals(status)) {
                    finalStatus = "failed";
                    errorSummary = nodeError.isBlank() ? "Agent node failed: " + agent.agentId() : nodeError;
                    failed = true;
                } else if (AiAgentNodeExecutor.STATUS_WAITING_HUMAN.equals(status)) {
                    finalStatus = "waiting_human";
                    waitingHuman = true;
                }
            }
            Instant startedAt = "skipped".equals(status) ? null : now;
            Instant finishedAt = ("skipped".equals(status)
                || AiAgentNodeExecutor.STATUS_WAITING_HUMAN.equals(status)) ? null : now;

            jdbcTemplate.update(
                """
                insert into ai_agent_run_nodes(
                  node_id, graph_id, agent_id, agent_role, execution_order, status,
                  input_snapshot_json, output_snapshot_json, error_summary,
                  result_origin, acceptance_state, started_at, finished_at, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                nodeId,
                graphPk,
                agent.agentId(),
                agent.agentRole(),
                index + 1,
                status,
                stringify(nodeInput),
                stringify(nodeOutput),
                nodeError,
                resultOrigin,
                acceptanceState,
                startedAt,
                finishedAt,
                now,
                now
            );

            if ("succeeded".equals(status) && !previousAgentId.isBlank()) {
                handoffService.recordHandoff(
                    runId,
                    previousAgentId,
                    agent.agentId(),
                    previousNodeId,
                    nodeId,
                    previousOutput
                );
            }
            if ("succeeded".equals(status) && "test-design".equals(agent.agentId())) {
                currentOutput = critiqueService.critiqueAndRepairTestDesign(runId, nodeId, currentOutput);
            }
            if ("succeeded".equals(status)) {
                previousAgentId = agent.agentId();
                previousNodeId = nodeId;
                previousOutput = currentOutput;
            }
        }

        ObjectNode graphOutput = objectMapper.createObjectNode()
            .put("status", finalStatus)
            .put("nodeCount", agents.size())
            .put("resultOrigin", failed ? "BLOCKED" : "RULE_COMPUTED")
            .put("acceptanceState", failed ? "BLOCKED" : "PENDING_HUMAN_CONFIRMATION");
        if (reviewInvocation.reviewStage() != null && !reviewInvocation.reviewStage().isBlank()) {
            graphOutput.put("reviewStage", reviewInvocation.reviewStage());
            graphOutput.set("aiReview", reviewInvocation.output().path("aiReview"));
        }
        jdbcTemplate.update(
            """
            update ai_agent_run_graphs
            set status = ?, output_snapshot_json = ?, error_summary = ?, result_origin = ?, acceptance_state = ?, updated_at = ?
            where graph_id = ?
            """,
            finalStatus,
            stringify(graphOutput),
            errorSummary,
            failed ? "BLOCKED" : "RULE_COMPUTED",
            failed ? "BLOCKED" : "PENDING_HUMAN_CONFIRMATION",
            Instant.now(),
            graphId
        );
        gateAuditService.record(new AiArtifactGateAuditService.GateRecord(
            "multi-agent:" + runId, runId, request.projectId(),
            failed ? "BLOCKED" : "RULE_COMPUTED", failed ? "BLOCKED" : "PENDING_HUMAN_CONFIRMATION", 1, "", "multi-agent:" + runId,
            List.of(), failed ? List.of(errorSummary) : List.of(), com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystem()));

        return toRunResponse(requireGraph(runId), agents.size());
    }

    /** T09 bullet 8：人工中断——标记 interrupted，节点检查点（行）保留供恢复。 */
    AiMultiAgentRunResponse interruptRun(String runId) {
        AiAgentRunGraph graph = requireGraph(runId);
        String status = graph.status() == null ? "" : graph.status();
        if (!"running".equals(status) && !"waiting_human".equals(status) && !"failed".equals(status)) {
            throw new IllegalArgumentException("Multi-agent run is not interruptible: " + status);
        }
        jdbcTemplate.update(
            "update ai_agent_run_graphs set status = ?, updated_at = ? where graph_id = ?",
            "interrupted", Instant.now(), graph.graphId());
        return toRunResponse(requireGraph(runId), countNodes(graph.id()));
    }

    /**
     * T09 bullet 8：从持久 checkpoint 恢复——已 succeeded 的节点直接沿用其输出，
     * 其余（中断/失败/未执行）节点按序重跑并原地更新检查点行；恢复不重复记账 handoff。
     */
    AiMultiAgentRunResponse resumeRun(String runId) {
        AiAgentRunGraph graph = requireGraph(runId);
        String graphStatus = graph.status() == null ? "" : graph.status();
        if (!"interrupted".equals(graphStatus) && !"failed".equals(graphStatus)) {
            throw new IllegalArgumentException("Multi-agent run is not resumable: " + graphStatus);
        }
        Long graphPk = graph.id();
        Map<String, AiAgentRunNode> latestByAgent = new LinkedHashMap<>();
        for (AiAgentRunNode node : jdbcTemplate.query(
                """
                select id, node_id, graph_id, agent_id, agent_role, execution_order, status,
                input_snapshot_json, output_snapshot_json, error_summary, result_origin, acceptance_state,
                started_at, finished_at, created_at, updated_at
                from ai_agent_run_nodes where graph_id = ? order by id asc
                """,
                (rs, rowNum) -> mapNode(rs),
                graphPk)) {
            latestByAgent.put(node.agentId(), node);
        }

        JsonNode previousOutput = objectMapper.createObjectNode();
        for (AiAgentRunNode node : latestByAgent.values()) {
            if ("succeeded".equals(node.status())) {
                previousOutput = parseOrObject(node.outputSnapshotJson());
            }
        }
        boolean failed = false;
        boolean waitingHuman = false;
        String errorSummary = "";
        List<BuiltInAgentDefinition> agents = registry.all();
        for (BuiltInAgentDefinition agent : agents) {
            AiAgentRunNode existing = latestByAgent.get(agent.agentId());
            boolean carryForward = existing != null && "succeeded".equals(existing.status());
            if (carryForward) {
                continue;
            }
            if (failed || waitingHuman) {
                continue;
            }
            if (existing == null) {
                throw new IllegalStateException("Checkpoint row missing for agent: " + agent.agentId());
            }
            ObjectNode nodeInput = objectMapper.createObjectNode()
                .put("targetCaseKind", textOrDefault(inputTargetCaseKind(graph), "functional"))
                .put("agentId", agent.agentId())
                .put("resumed", true);
            nodeInput.set("previousOutput", previousOutput);
            AiAgentNodeExecutor.NodeExecution execution =
                nodeExecutor.execute(agent, graph.projectId(), runId, nodeInput, previousOutput);
            Instant startedAt = "skipped".equals(execution.status()) ? existing.startedAt() : Instant.now();
            Instant finishedAt = ("skipped".equals(execution.status())
                || AiAgentNodeExecutor.STATUS_WAITING_HUMAN.equals(execution.status())) ? null : Instant.now();
            ObjectNode output = execution.output() == null ? objectMapper.createObjectNode() : execution.output();
            output.put("resumed", true);
            jdbcTemplate.update(
                """
                update ai_agent_run_nodes
                set status = ?, input_snapshot_json = ?, output_snapshot_json = ?, error_summary = ?,
                    result_origin = ?, acceptance_state = ?, started_at = ?, finished_at = ?, updated_at = ?
                where id = ?
                """,
                execution.status(),
                stringify(nodeInput),
                stringify(output),
                execution.error(),
                execution.resultOrigin(),
                execution.acceptanceState(),
                startedAt,
                finishedAt,
                Instant.now(),
                existing.id());
            if (AiAgentNodeExecutor.STATUS_FAILED.equals(execution.status())) {
                failed = true;
                errorSummary = execution.error().isBlank()
                    ? "Agent node failed: " + agent.agentId() : execution.error();
            } else if (AiAgentNodeExecutor.STATUS_WAITING_HUMAN.equals(execution.status())) {
                waitingHuman = true;
            } else if ("succeeded".equals(execution.status())) {
                previousOutput = output;
            }
        }
        String finalStatus = failed ? "failed" : (waitingHuman ? "waiting_human" : "merge_ready");
        ObjectNode graphOutput = objectMapper.createObjectNode()
            .put("status", finalStatus)
            .put("nodeCount", agents.size())
            .put("resumed", true)
            .put("resultOrigin", failed ? "BLOCKED" : "RULE_COMPUTED")
            .put("acceptanceState", failed ? "BLOCKED" : "PENDING_HUMAN_CONFIRMATION");
        jdbcTemplate.update(
            """
            update ai_agent_run_graphs
            set status = ?, output_snapshot_json = ?, error_summary = ?, result_origin = ?, acceptance_state = ?, updated_at = ?
            where graph_id = ?
            """,
            finalStatus,
            stringify(graphOutput),
            errorSummary,
            failed ? "BLOCKED" : "RULE_COMPUTED",
            failed ? "BLOCKED" : "PENDING_HUMAN_CONFIRMATION",
            Instant.now(),
            graph.graphId());
        return toRunResponse(requireGraph(runId), countNodes(graphPk));
    }

    private String inputTargetCaseKind(AiAgentRunGraph graph) {
        return parseOrObject(graph.inputSnapshotJson()).path("targetCaseKind").asText("");
    }

    private AiReviewInvocation runAiReviewIfRequested(Long projectId, String runId, JsonNode inputSnapshot) {
        String reviewStage = inputSnapshot.path("reviewStage").asText("");
        if (reviewStage.isBlank()) {
            return new AiReviewInvocation(inputSnapshot, "", false);
        }
        try {
            ResolvedAiGatewaySettings settings = aiGatewaySettingsService.loadDefaultTextSettings();
            ObjectNode prompt = objectMapper.createObjectNode();
            prompt.put("skillId", "ai-stage-artifact-review");
            prompt.put("reviewStage", reviewStage);
            prompt.put("target", "requirement".equals(reviewStage) ? "评审需求测试点并给出缺失补充建议" : "评审已生成用例并给出需要补充的用例建议");
            prompt.put("outputContract", "仅返回 JSON 对象，可使用 reviewSummary、missingPoints、missingCases、suggestions 字段；不要输出 Markdown 或解释性前缀。");
            prompt.set("inputSummary", inputSnapshot);
            long started = System.currentTimeMillis();
            AiGenerationResult result = taskFacade.generate(
                new com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade.TaskExecution(
                    "multi_agent_stage_review", projectId, "system", runId, prompt, null));
            insertAiReviewCallRecord(projectId, settings, result, System.currentTimeMillis() - started, prompt);
            if (result == null || !result.success()) {
                return failedReviewInvocation(reviewStage, result == null ? "AI评审失败" : result.errorMessage());
            }
            ObjectNode output = objectMapper.createObjectNode()
                .put("reviewStage", reviewStage)
                .put("status", "succeeded");
            output.set("aiReview", parseAiReviewOutput(result.content()));
            return new AiReviewInvocation(output, reviewStage, false);
        } catch (RuntimeException exception) {
            return failedReviewInvocation(reviewStage, exception.getMessage());
        }
    }

    private AiReviewInvocation failedReviewInvocation(String reviewStage, String reason) {
        ObjectNode failedOutput = objectMapper.createObjectNode()
            .put("reviewStage", reviewStage)
            .put("status", "failed")
            .put("errorSummary", safeReviewFailureMessage(reason));
        return new AiReviewInvocation(failedOutput, reviewStage, true);
    }

    private String safeReviewFailureMessage(String reason) {
        String message = textOrDefault(reason, "AI评审失败")
            .replaceAll("(?i)https?://[^\\s,;]+", "[url]")
            .replaceAll("(?i)(access[_-]?token|auth[_-]?token|api[_-]?key|secret[_-]?key|password|cookie)\\s*[:=]\\s*[^\\s,;]+", "[redacted]");
        return message.length() > 240 ? message.substring(0, 237) + "..." : message;
    }

    private void insertAiReviewCallRecord(
        Long projectId,
        ResolvedAiGatewaySettings settings,
        AiGenerationResult result,
        long durationMs,
        JsonNode inputSummary
    ) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
            insert into ai_call_records(
              record_id, run_id, project_id, provider_type, model_name, config_snapshot_id, agent_id,
              skill_ids_json, rule_version, modality, request_id, status, error_code, error_message,
              duration_ms, token_usage_input, token_usage_output, estimated_cost, input_summary_json,
              output_summary_json, raw_payload_json, actor_id, actor_name, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "call-" + UUID.randomUUID(),
            null,
            projectId,
            settings.providerType(),
            settings.model(),
            "default",
            "ai-review",
            "[\"ai-stage-artifact-review\"]",
            "1",
            "text",
            result == null ? "" : result.requestId(),
            result != null && result.success() ? "succeeded" : "failed",
            "",
            result == null ? "AI评审失败" : textOrDefault(result.errorMessage(), ""),
            durationMs,
            result == null ? 0 : result.inputTokens(),
            result == null ? 0 : result.outputTokens(),
            0,
            AiCallRecordPayloads.compactJson(objectMapper, inputSummary),
            result == null ? null : stringify(objectMapper.createObjectNode().put("contentLength", result.content().length())),
            result == null ? null : AiCallRecordPayloads.compactText(objectMapper, result.content()),
            com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystem(),
            com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystemName(),
            Timestamp.from(now),
            Timestamp.from(now)
        );
    }

    AiMultiAgentRunResponse getRun(String runId) {
        AiAgentRunGraph graph = requireGraph(runId);
        return toRunResponse(graph, countNodes(graph.id()));
    }

    AiMultiAgentGraphResponse getGraph(String runId) {
        AiAgentRunGraph graph = requireGraph(runId);
        List<AiAgentRunNodeResponse> nodes = jdbcTemplate.query(
            "select * from ai_agent_run_nodes where graph_id = ? order by execution_order asc",
            (rs, rowNum) -> toNodeResponse(mapNode(rs)),
            graph.id()
        );
        return new AiMultiAgentGraphResponse(
            graph.runId(),
            graph.graphId(),
            graph.status(),
            parseOrObject(graph.inputSnapshotJson()),
            parseOrObject(graph.outputSnapshotJson()),
            graph.errorSummary(),
            nodes
        );
    }

    private AiMultiAgentRunResponse toRunResponse(AiAgentRunGraph graph, int nodeCount) {
        return new AiMultiAgentRunResponse(
            graph.runId(),
            graph.graphId(),
            graph.projectId(),
            graph.status(),
            nodeCount,
            parseOrObject(graph.inputSnapshotJson()),
            parseOrObject(graph.outputSnapshotJson()),
            graph.errorSummary(),
            graph.resultOrigin(),
            graph.acceptanceState(),
            graph.createdAt().toString(),
            graph.updatedAt().toString()
        );
    }

    private AiAgentRunNodeResponse toNodeResponse(AiAgentRunNode node) {
        return new AiAgentRunNodeResponse(
            node.nodeId(),
            node.agentId(),
            node.agentRole(),
            node.executionOrder(),
            node.status(),
            parseOrObject(node.inputSnapshotJson()),
            parseOrObject(node.outputSnapshotJson()),
            node.errorSummary(),
            node.startedAt() == null ? null : node.startedAt().toString(),
            node.finishedAt() == null ? null : node.finishedAt().toString()
        );
    }

    private AiAgentRunGraph requireGraph(String runId) {
        return jdbcTemplate.query(
            "select * from ai_agent_run_graphs where run_id = ?",
            (rs, rowNum) -> mapGraph(rs),
            runId
        ).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown multi-agent run: " + runId));
    }

    private int countNodes(Long graphPk) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from ai_agent_run_nodes where graph_id = ?",
            Integer.class,
            graphPk
        );
        return count == null ? 0 : count;
    }

    private AiAgentRunGraph mapGraph(ResultSet rs) throws SQLException {
        return new AiAgentRunGraph(
            rs.getLong("id"),
            rs.getString("graph_id"),
            rs.getString("run_id"),
            rs.getLong("project_id"),
            rs.getString("status"),
            rs.getString("input_snapshot_json"),
            rs.getString("output_snapshot_json"),
            rs.getString("error_summary"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
        );
    }

    private AiAgentRunNode mapNode(ResultSet rs) throws SQLException {
        return new AiAgentRunNode(
            rs.getLong("id"),
            rs.getString("node_id"),
            rs.getLong("graph_id"),
            rs.getString("agent_id"),
            rs.getString("agent_role"),
            rs.getInt("execution_order"),
            rs.getString("status"),
            rs.getString("input_snapshot_json"),
            rs.getString("output_snapshot_json"),
            rs.getString("error_summary"),
            toInstant(rs.getTimestamp("started_at")),
            toInstant(rs.getTimestamp("finished_at")),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
        );
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private JsonNode parseOrObject(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid multi-agent JSON", exception);
        }
    }

    private JsonNode parseAiReviewOutput(String content) {
        if (content == null || content.isBlank()) return objectMapper.createObjectNode();
        try {
            JsonNode parsed = objectMapper.readTree(content);
            if (parsed != null && parsed.isTextual()) {
                try {
                    JsonNode nested = objectMapper.readTree(parsed.asText());
                    return nested == null ? objectMapper.createObjectNode() : nested;
                } catch (Exception ignored) {
                    return objectMapper.createObjectNode().put("summary", parsed.asText());
                }
            }
            return parsed == null ? objectMapper.createObjectNode() : parsed;
        } catch (Exception ignored) {
            return objectMapper.createObjectNode().put("summary", content.trim());
        }
    }

    private String stringify(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node == null ? objectMapper.createObjectNode() : node);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid multi-agent payload", exception);
        }
    }

    private String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}

record AiReviewInvocation(
    JsonNode output,
    String reviewStage,
    boolean failed
) {
}

record CreateMultiAgentRunRequest(
    Long projectId,
    String targetCaseKind,
    JsonNode inputSummary
) {
}

record AiMultiAgentRunResponse(
    String runId,
    String graphId,
    Long projectId,
    String status,
    int nodeCount,
    JsonNode inputSnapshot,
    JsonNode outputSnapshot,
    String errorSummary,
    String resultOrigin,
    String acceptanceState,
    String createdAt,
    String updatedAt
) {
}

record AiMultiAgentGraphResponse(
    String runId,
    String graphId,
    String status,
    JsonNode inputSnapshot,
    JsonNode outputSnapshot,
    String errorSummary,
    List<AiAgentRunNodeResponse> nodes
) {
}

record AiAgentRunNodeResponse(
    String nodeId,
    String agentId,
    String agentRole,
    int executionOrder,
    String status,
    JsonNode inputSnapshot,
    JsonNode outputSnapshot,
    String errorSummary,
    String startedAt,
    String finishedAt
) {
}
