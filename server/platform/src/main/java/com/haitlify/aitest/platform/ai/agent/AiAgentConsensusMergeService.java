package com.haitlify.aitest.platform.ai.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.haitlify.aitest.platform.ai.analysis.AiRunOriginSyncService;
import com.haitlify.aitest.platform.ai.runtime.artifact.AiArtifactGateAuditService;
import com.haitlify.aitest.platform.ai.gateway.AiGenerationResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
@Transactional
class AiAgentConsensusMergeService {

    private static final Logger log = LoggerFactory.getLogger(AiAgentConsensusMergeService.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AiAgentConflictService conflictService;
    private final com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade taskFacade;
    private final AiArtifactGateAuditService gateAuditService;
    private final AiRunOriginSyncService runOriginSyncService;

    AiAgentConsensusMergeService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, AiAgentConflictService conflictService, com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade taskFacade, AiRunOriginSyncService runOriginSyncService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.conflictService = conflictService;
        this.taskFacade = taskFacade;
        this.gateAuditService = new AiArtifactGateAuditService(jdbcTemplate);
        this.runOriginSyncService = runOriginSyncService;
    }

    AiAgentMergeResponse merge(String runId, MergeAgentRunRequest request) {
        List<MergeCandidate> candidates = request == null || request.candidates() == null
            ? List.of()
            : request.candidates();
        detectConflicts(runId, candidates);
        int unresolved = conflictService.unresolvedCount(runId);
        if (unresolved > 0) {
            return new AiAgentMergeResponse("blocked", 0, unresolved, List.of(), objectMapper.createArrayNode(), false);
        }

        AiAgentRunGraph graph = requireGraph(runId);
        CandidateExpansion expansion = expandCandidatesWithAi(graph, candidates);
        List<MergeCandidate> merged = dedupe(expansion.candidates());
        boolean modelBacked = !expansion.fallback() && !expansion.providerRequestId().isBlank();
        String resultOrigin = modelBacked ? "MODEL_GENERATED" : "RULE_EXTRACTED";
        List<String> fieldOrigins = modelBacked ? List.of("MODEL_PROPOSED") : List.of("RULE_COMPUTED");
        Long analysisRunPk = ensureAnalysisRun(graph);
        List<String> pointIds = new ArrayList<>();
        Instant now = Instant.now();
        for (MergeCandidate candidate : merged) {
            String pointId = "multi-agent-point-" + UUID.randomUUID();
            pointIds.add(pointId);
            jdbcTemplate.update(
                """
                insert into ai_draft_test_points(
                  point_id, run_id, project_id, source_document_id, source_chunk_id, title, module_name,
                  page_or_feature, case_kind, test_type, priority_level, precondition_text, test_objective,
                  suggested_steps, expected_result, source_file, source_section, source_page, source_text,
                  source_image_id, extraction_method, confidence_score, inferred, review_status, review_comment,
                  result_origin, acceptance_state, revision, parent_artifact_id, idempotency_key, template_id, metadata_json, actor_id, actor_name, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                pointId,
                analysisRunPk,
                graph.projectId(),
                null,
                null,
                textOrDefault(candidate.title(), "多Agent合并测试点"),
                "",
                textOrDefault(candidate.targetKey(), ""),
                textOrDefault(candidate.caseKind(), "functional"),
                "multi_agent",
                textOrDefault(candidate.priority(), ""),
                "",
                textOrDefault(candidate.title(), ""),
                String.join("\n", candidate.suggestedSteps() == null ? List.of() : candidate.suggestedSteps()),
                textOrDefault(candidate.expectedResult(), ""),
                "multi-agent",
                "",
                "",
                String.join("\n", candidate.sourceEvidence() == null ? List.of() : candidate.sourceEvidence()),
                "",
                "ai_generated",
                0.80,
                false,
                "ai_generated",
                "",
                resultOrigin,
                "PENDING_HUMAN_CONFIRMATION",
                1,
                "",
                "multi-agent:" + runId + ":" + pointId,
                "",
                stringify(candidateMetadata(candidate, fieldOrigins)),
                com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystem(),
                com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystemName(),
                now,
                now
            );
            gateAuditService.record(new AiArtifactGateAuditService.GateRecord(pointId, runId, graph.projectId(), resultOrigin, "PENDING_HUMAN_CONFIRMATION", 1, "", "multi-agent:" + runId + ":" + pointId, candidate.sourceEvidence() == null ? List.of() : candidate.sourceEvidence(), List.of(), com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystem()));
        }

        ArrayNode created = objectMapper.valueToTree(pointIds);
        if (!pointIds.isEmpty()) {
            runOriginSyncService.syncFromArtifacts(analysisRunPk);
        }
        ObjectNode mergedOutput = objectMapper.createObjectNode();
        mergedOutput.set("items", objectMapper.valueToTree(merged));
        mergedOutput.put("duplicateTargetCount", Math.max(0, candidates.size() - merged.size()));
        jdbcTemplate.update(
            """
            insert into ai_agent_consensus_records(
              consensus_id, run_id, status, merged_output_json, source_agent_ids_json,
              created_draft_point_ids_json, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "agent-consensus-" + UUID.randomUUID(),
            runId,
            "merged",
            stringify(mergedOutput),
            "[\"merge-coordinator\"]",
            stringify(created),
            now,
            now
        );

        return new AiAgentMergeResponse("merged", merged.size(), 0, pointIds, objectMapper.valueToTree(merged), expansion.fallback());
    }

    private void detectConflicts(String runId, List<MergeCandidate> candidates) {
        Map<String, List<MergeCandidate>> byTarget = candidates.stream()
            .collect(Collectors.groupingBy(candidate -> textOrDefault(candidate.targetKey(), candidate.title()), LinkedHashMap::new, Collectors.toList()));
        byTarget.forEach((targetKey, group) -> {
            LinkedHashSet<String> priorities = group.stream()
                .map(candidate -> textOrDefault(candidate.priority(), ""))
                .filter(value -> !value.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
            LinkedHashSet<String> expectedResults = group.stream()
                .map(candidate -> textOrDefault(candidate.expectedResult(), ""))
                .filter(value -> !value.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
            if (priorities.size() > 1) {
                conflictService.createConflictIfAbsent(runId, "priority_conflict", targetKey, conflictPayload(targetKey, "priorities", priorities));
            }
            if (expectedResults.size() > 1) {
                conflictService.createConflictIfAbsent(runId, "expected_result_conflict", targetKey, conflictPayload(targetKey, "expectedResults", expectedResults));
            }
        });
    }

    private ObjectNode conflictPayload(String targetKey, String field, LinkedHashSet<String> values) {
        ObjectNode payload = objectMapper.createObjectNode().put("targetKey", targetKey);
        payload.set(field, objectMapper.valueToTree(values));
        return payload;
    }

    private List<MergeCandidate> dedupe(List<MergeCandidate> candidates) {
        Map<String, MergeCandidate> merged = new LinkedHashMap<>();
        for (MergeCandidate candidate : candidates) {
            String key = textOrDefault(candidate.targetKey(), candidate.title());
            merged.putIfAbsent(key, candidate);
        }
        return new ArrayList<>(merged.values());
    }

    private CandidateExpansion expandCandidatesWithAi(AiAgentRunGraph graph, List<MergeCandidate> candidates) {
        if (candidates.isEmpty()) return new CandidateExpansion(List.of(), false, "");
        ObjectNode prompt = objectMapper.createObjectNode()
            .put("task", "将质量评审建议扩写为可执行的增量测试点")
            .put("targetCaseKind", "web_automation")
            .put("outputContract", "仅返回 JSON 对象 {items:[{title,targetKey,caseKind,priority,expectedResult,sourceEvidence,suggestedSteps}]}；每项保留原建议意图并补全步骤和预期。");
        prompt.set("suggestions", objectMapper.valueToTree(candidates));
        final AiGenerationResult result;
        try {
            result = taskFacade.generate(new com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade.TaskExecution(
                "agent_consensus_merge", null, "system", graph == null ? null : graph.runId(), prompt, null));
        } catch (RuntimeException exception) {
            // AI enrichment is opportunistic; an unavailable or incomplete config must not
            // turn an otherwise valid review merge into a 500 response.
            log.warn("AI 增量补充不可用，保留原评审候选，原因类型={}", exception.getClass().getSimpleName());
            return new CandidateExpansion(candidates, true, "");
        }
        if (result == null || !result.success()) {
            log.warn("AI 增量补充未返回成功结果，保留原评审候选");
            return new CandidateExpansion(candidates, true, "");
        }
        if (result.requestId() == null || result.requestId().isBlank()) {
            // Fail-closed: without an auditable provider request id the model output must not
            // be recorded as MODEL_GENERATED evidence, so the merge falls back to rule origin.
            log.warn("AI 增量补充缺少可审计的 provider request id，按规则合并处理");
            return new CandidateExpansion(candidates, true, "");
        }
        try {
            JsonNode items = objectMapper.readTree(result.content()).path("items");
            if (!items.isArray() || items.isEmpty()) return new CandidateExpansion(candidates, true, "");
            List<MergeCandidate> expanded = new ArrayList<>();
            for (JsonNode item : items) {
                expanded.add(new MergeCandidate(
                    textOrDefault(item.path("title").asText(), "增量补充测试点"),
                    textOrDefault(item.path("targetKey").asText(), item.path("title").asText()),
                    textOrDefault(item.path("caseKind").asText(), "web_automation"),
                    textOrDefault(item.path("priority").asText(), "P2"),
                    textOrDefault(item.path("expectedResult").asText(), "按评审建议补充并完成验收"),
                    textList(item.path("sourceEvidence")),
                    textList(item.path("suggestedSteps"))
                ));
            }
            return expanded.isEmpty()
                ? new CandidateExpansion(candidates, true, "")
                : new CandidateExpansion(expanded, false, result.requestId());
        } catch (Exception exception) {
            log.warn("AI 增量补充结果格式异常，保留原评审候选，原因类型={}", exception.getClass().getSimpleName());
            return new CandidateExpansion(candidates, true, "");
        }
    }

    private record CandidateExpansion(List<MergeCandidate> candidates, boolean fallback, String providerRequestId) {
    }

    private List<String> textList(JsonNode value) {
        if (!value.isArray()) return List.of();
        List<String> items = new ArrayList<>();
        value.forEach(item -> { if (!item.asText("").isBlank()) items.add(item.asText()); });
        return List.copyOf(items);
    }

    private Long ensureAnalysisRun(AiAgentRunGraph graph) {
        List<Long> existing = jdbcTemplate.query(
            "select id from ai_analysis_runs where run_id = ?",
            (rs, rowNum) -> rs.getLong("id"),
            graph.runId()
        );
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        Instant now = Instant.now();
        try {
            jdbcTemplate.update(
            """
            insert into ai_analysis_runs(
              run_id, project_id, target_case_kind, template_id, analysis_strategy, execution_mode,
              status, worker_status, coverage_status, total_chunks, completed_chunks, current_chunk_id,
              resume_from_chunk_id, error_summary, config_snapshot_json, rule_snapshot_json, skill_snapshot_json,
              schema_snapshot_json, input_summary_json, output_summary_json, actor_id, actor_name,
              started_at, finished_at, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            graph.runId(),
            graph.projectId(),
            "multi_agent",
            "multi-agent",
            "balanced",
            "multi_agent",
            "succeeded",
            "completed",
            "pending",
            0,
            0,
            "",
            "",
            "",
            "{}",
            "{}",
            "{}",
            "{}",
            graph.inputSnapshotJson(),
            graph.outputSnapshotJson(),
            com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystem(),
            com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystemName(),
            now,
            now,
            now,
            now
            );
        } catch (DuplicateKeyException duplicate) {
            // 并发点击“对齐补充”时，另一请求可能已完成同一 run 的初始化。
        }
        return jdbcTemplate.queryForObject("select id from ai_analysis_runs where run_id = ?", Long.class, graph.runId());
    }

    private AiAgentRunGraph requireGraph(String runId) {
        return jdbcTemplate.query(
            "select * from ai_agent_run_graphs where run_id = ?",
            (rs, rowNum) -> new AiAgentRunGraph(
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
            ),
            runId
        ).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown multi-agent run: " + runId));
    }

    private ObjectNode candidateMetadata(MergeCandidate candidate, List<String> fieldOrigins) {
        ObjectNode metadata = objectMapper.createObjectNode()
            .put("sourceType", "multi_agent")
            .put("targetKey", textOrDefault(candidate.targetKey(), ""));
        metadata.set("sourceEvidence", objectMapper.valueToTree(candidate.sourceEvidence() == null ? List.of() : candidate.sourceEvidence()));
        metadata.set("fieldOrigins", objectMapper.valueToTree(fieldOrigins));
        return metadata;
    }

    private String stringify(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node == null ? objectMapper.createObjectNode() : node);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid agent merge payload", exception);
        }
    }

    private String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}

record MergeAgentRunRequest(
    List<MergeCandidate> candidates
) {
}

record MergeCandidate(
    String title,
    String targetKey,
    String caseKind,
    String priority,
    String expectedResult,
    List<String> sourceEvidence,
    List<String> suggestedSteps
) {
}

record AiAgentMergeResponse(
    String status,
    int mergedCount,
    int unresolvedConflictCount,
    List<String> createdDraftPointIds,
    JsonNode mergedOutput,
    boolean aiEnrichmentFallback
) {
}
