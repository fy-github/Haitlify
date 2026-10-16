package com.haitlify.aitest.platform.ai.figma;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.haitlify.aitest.platform.ai.call.AiCallRecordPayloads;
import com.haitlify.aitest.platform.ai.gateway.AiGenerationResult;
import com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade;
import com.haitlify.aitest.platform.ai.runtime.artifact.AiArtifactGateAuditService;

@Service
@Transactional
class AiFigmaAnalysisService {


    private final AiFigmaFileRepository fileRepository;
    private final AiFigmaNodeRepository nodeRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AiArtifactGateAuditService gateAuditService;
    private final AiTaskExecutionFacade taskExecutionFacade;

    AiFigmaAnalysisService(
        AiFigmaFileRepository fileRepository,
        AiFigmaNodeRepository nodeRepository,
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        AiArtifactGateAuditService gateAuditService,
        AiTaskExecutionFacade taskExecutionFacade
    ) {
        this.fileRepository = fileRepository;
        this.nodeRepository = nodeRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.gateAuditService = gateAuditService;
        this.taskExecutionFacade = taskExecutionFacade;
    }

    AiFigmaAnalysisRunResponse startFileAnalysis(String figmaFileId) {
        AiFigmaFile file = fileRepository.findByFigmaFileId(figmaFileId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown Figma file: " + figmaFileId));
        List<AiFigmaNode> nodes = nodeRepository.findByFigmaFilePkOrderByIdAsc(file.getId());
        return createRun(file, nodes, "file", figmaFileId);
    }

    AiFigmaAnalysisRunResponse startNodeAnalysis(String figmaNodeId) {
        AiFigmaNode root = nodeRepository.findByFigmaNodeId(figmaNodeId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown Figma node: " + figmaNodeId));
        AiFigmaFile file = fileRepository.findById(root.getFigmaFilePk())
            .orElseThrow(() -> new IllegalArgumentException("Unknown Figma file for node: " + figmaNodeId));
        return createRun(file, scopedNodes(file.getId(), root), "node", figmaNodeId);
    }

    AiFigmaDraftPointListResponse listDraftPoints(String runId) {
        List<AiFigmaDraftPointResponse> items = jdbcTemplate.queryForList("""
                select p.*
                from ai_draft_test_points p
                join ai_analysis_runs r on r.id = p.run_id
                where r.run_id = ?
                order by p.id asc
                """,
                runId
            )
            .stream()
            .map(this::toDraftResponse)
            .toList();
        return new AiFigmaDraftPointListResponse(items);
    }

    private AiFigmaAnalysisRunResponse createRun(AiFigmaFile file, List<AiFigmaNode> nodes, String targetType, String targetId) {
        AiFigmaTestPointMapper mapper = new AiFigmaTestPointMapper(new AiFigmaEvidenceService(objectMapper));
        List<AiFigmaDraftTestPointCandidate> candidates = mapper.map(file, nodes, List.of(), List.of());
        String runId = "run-figma-" + UUID.randomUUID();
        Instant now = Instant.now();
        ObjectNode inputSummary = objectMapper.createObjectNode()
            .put("sourceType", "figma")
            .put("figmaFileId", file.getFigmaFileId())
            .put("fileKey", file.getFileKey())
            .put("targetType", targetType)
            .put("targetId", targetId);
        Long runPk = insertRun(file.getProjectId(), runId, inputSummary, candidates.size(), now);
        String figmaVersionHash = figmaVersionHash(file, nodes);
        for (AiFigmaDraftTestPointCandidate candidate : candidates) {
            insertDraftPoint(runPk, file, candidate, "RULE_EXTRACTED", 0.82, figmaVersionHash);
        }
        // T08 bullet 4：语义分析为单独的真实模型节点（经统一 Runtime facade；无通道/失败如实 BLOCKED，绝不冒充）。
        SemanticOutcome semantic = runSemanticAnalysis(file, nodes, figmaVersionHash, runPk);
        int totalDraftPoints = candidates.size() + semantic.inserted();
        ObjectNode outputSummary = objectMapper.createObjectNode()
            .put("draftTestPointCount", totalDraftPoints)
            .put("ruleExtractedCount", candidates.size())
            .put("semanticInsertedCount", semantic.inserted())
            .put("semanticAnalysisStatus", semantic.status())
            .put("figmaVersionHash", figmaVersionHash);
        if (!semantic.reason().isBlank()) {
            outputSummary.put("semanticAnalysisReason", semantic.reason());
        }
        jdbcTemplate.update("""
            update ai_analysis_runs
            set status = ?, worker_status = ?, coverage_status = ?, completed_chunks = ?,
                output_summary_json = ?, result_origin = ?, acceptance_state = ?, finished_at = ?, updated_at = ?
            where id = ?
            """,
            "waiting_review",
            "idle",
            "pending",
            totalDraftPoints,
            stringify(outputSummary),
            "RULE_EXTRACTED",
            "PENDING_HUMAN_CONFIRMATION",
            Timestamp.from(Instant.now()),
            Timestamp.from(Instant.now()),
            runPk
        );
        return new AiFigmaAnalysisRunResponse(runId, "waiting_review", totalDraftPoints, "RULE_EXTRACTED", "PENDING_HUMAN_CONFIRMATION");
    }

    /** 版本指纹：文件快照哈希 + 本次分析覆盖的 nodeIds——不同版本的候选绝不混用。 */
    static String figmaVersionHash(AiFigmaFile file, List<AiFigmaNode> nodes) {
        String nodeIds = nodes.stream()
            .map(AiFigmaNode::getNodeId)
            .sorted()
            .collect(Collectors.joining(","));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                (file.getFileKey() + "|" + file.getSnapshotHash() + "|" + nodeIds).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /**
     * 真实模型语义节点：单个 figma_semantic_analysis 调用（统一 Runtime；临时预算键，调用后回收）。
     * 成功且输出可解析 ⇒ MODEL_GENERATED 候选（人审门禁 PENDING_HUMAN_CONFIRMATION）；
     * 失败/无通道/输出非法 ⇒ 如实记录 BLOCKED 状态，绝不生成假候选。
     */
    private SemanticOutcome runSemanticAnalysis(AiFigmaFile file, List<AiFigmaNode> nodes, String versionHash,
                                                Long runPk) {
        ObjectNode prompt = objectMapper.createObjectNode();
        prompt.put("instruction", "基于 Figma 节点结构输出测试点 JSON 数组，元素字段: title, module, testType,"
            + " objective, steps, expectedResult, nodeId；只输出 JSON。");
        var nodeArray = prompt.putArray("nodes");
        for (int index = 0; index < Math.min(nodes.size(), 30); index++) {
            AiFigmaNode node = nodes.get(index);
            nodeArray.addObject()
                .put("nodeId", node.getNodeId())
                .put("name", node.getNodeName())
                .put("type", node.getNodeType());
        }
        AiGenerationResult result;
        try {
            result = taskExecutionFacade.generate(new AiTaskExecutionFacade.TaskExecution(
                "figma_semantic_analysis", file.getProjectId(), com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystem(), null, prompt, null));
        } catch (RuntimeException failure) {
            return new SemanticOutcome("BLOCKED", 0, clip("call failed: " + failure.getClass().getSimpleName()));
        }
        if (result == null || !result.success()) {
            String message = result == null ? "no result"
                : (result.errorMessage() == null || result.errorMessage().isBlank()
                    ? result.retryReason() : result.errorMessage()
                        + (result.retryReason() == null || result.retryReason().isBlank() ? "" : " (" + result.retryReason() + ")"));
            return new SemanticOutcome("BLOCKED", 0, clip(message));
        }
        List<AiFigmaDraftTestPointCandidate> semanticCandidates = parseSemanticCandidates(file, result.content());
        if (semanticCandidates.isEmpty()) {
            return new SemanticOutcome("INVALID_OUTPUT", 0, "model output carried no usable candidate");
        }
        for (AiFigmaDraftTestPointCandidate candidate : semanticCandidates) {
            insertDraftPoint(runPk, file, candidate, "MODEL_GENERATED", 0.80, versionHash);
        }
        return new SemanticOutcome("COMPLETED", semanticCandidates.size(), "");
    }

    private List<AiFigmaDraftTestPointCandidate> parseSemanticCandidates(AiFigmaFile file, String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String text = content.trim();
        int arrayStart = text.indexOf('[');
        int arrayEnd = text.lastIndexOf(']');
        if (arrayStart < 0 || arrayEnd <= arrayStart) {
            return List.of();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(text.substring(arrayStart, arrayEnd + 1));
        } catch (Exception invalid) {
            return List.of();
        }
        List<AiFigmaDraftTestPointCandidate> candidates = new ArrayList<>();
        for (JsonNode item : root) {
            String title = item.path("title").asText("");
            if (title.isBlank()) {
                continue;
            }
            String nodeId = item.path("nodeId").asText("");
            AiFigmaNode node = nodeId.isBlank() ? null : nodesFor(file, nodeId);
            candidates.add(new AiFigmaDraftTestPointCandidate(
                title,
                item.path("module").asText(""),
                item.path("pageOrFeature").asText(""),
                item.path("caseKind").asText("functional"),
                item.path("testType").asText("semantic"),
                item.path("objective").asText(""),
                item.path("steps").asText(""),
                item.path("expectedResult").asText(""),
                new AiFigmaRequirementEvidence("figma",
                    file.getFileKey() == null ? "" : file.getFileKey(),
                    nodeId,
                    node == null || node.getNodeName() == null ? "" : node.getNodeName(),
                    node == null || node.getNodeType() == null ? "" : node.getNodeType(),
                    node == null || node.getComponentName() == null ? "" : node.getComponentName(),
                    "",
                    "")));
        }
        return candidates;
    }

    private AiFigmaNode nodesFor(AiFigmaFile file, String nodeId) {
        return nodeRepository.findByFigmaNodeId(nodeId).orElse(null);
    }

    private static String clip(String message) {
        if (message == null) {
            return "";
        }
        return message.length() <= 200 ? message : message.substring(0, 200);
    }

    private record SemanticOutcome(String status, int inserted, String reason) {}

    private Long insertRun(Long projectId, String runId, ObjectNode inputSummary, int totalChunks, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                insert into ai_analysis_runs(
                  run_id, project_id, target_case_kind, template_id, analysis_strategy, execution_mode,
                  status, worker_status, coverage_status, total_chunks, completed_chunks, current_chunk_id,
                  resume_from_chunk_id, error_summary, config_snapshot_json, rule_snapshot_json,
                  skill_snapshot_json, schema_snapshot_json, input_summary_json, output_summary_json,
                  result_origin, acceptance_state, actor_id, actor_name, started_at, finished_at, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, runId);
            statement.setLong(2, projectId);
            statement.setString(3, "web_automation");
            statement.setString(4, "figma-derived");
            statement.setString(5, "figma");
            statement.setString(6, "inline");
            statement.setString(7, "running");
            statement.setString(8, "running");
            statement.setString(9, "pending");
            statement.setInt(10, totalChunks);
            statement.setInt(11, 0);
            statement.setString(12, "");
            statement.setString(13, "");
            statement.setString(14, "");
            statement.setString(15, "{}");
            statement.setString(16, "{}");
            statement.setString(17, "{}");
            statement.setString(18, "{}");
            statement.setString(19, AiCallRecordPayloads.compactJson(objectMapper, inputSummary));
            statement.setString(20, "{}");
            statement.setString(21, "RULE_EXTRACTED");
            statement.setString(22, "PENDING_HUMAN_CONFIRMATION");
            statement.setString(23, com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystem());
            statement.setString(24, com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystemName());
            statement.setTimestamp(25, Timestamp.from(now));
            statement.setTimestamp(26, null);
            statement.setTimestamp(27, Timestamp.from(now));
            statement.setTimestamp(28, Timestamp.from(now));
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    private void insertDraftPoint(Long runPk, AiFigmaFile file, AiFigmaDraftTestPointCandidate candidate,
                                  String resultOrigin, double confidence, String versionHash) {
        Instant now = Instant.now();
        // 候选表 candidate_id 与 gate artifact_id 均受 varchar(128) 约束：标题是模型文本，
        // 直拼会在长标题上顶爆列宽（与 draft-merge 同类缺陷）；散列后长度恒定且保持确定性。
        String figmaCandidateKey = "figma:" + runPk + ":" + sha256Hex(candidate.title());
        jdbcTemplate.update("""
            insert into ai_draft_test_points(
              point_id, run_id, project_id, source_document_id, source_chunk_id, title, module_name,
              page_or_feature, case_kind, test_type, priority_level, precondition_text, test_objective,
              suggested_steps, expected_result, source_file, source_section, source_page, source_text,
              source_image_id, extraction_method, confidence_score, inferred, review_status, review_comment,
              result_origin, acceptance_state, revision, parent_artifact_id, idempotency_key, template_id, metadata_json, actor_id, actor_name, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "point-" + UUID.randomUUID(),
            runPk,
            file.getProjectId(),
            null,
            null,
            candidate.title(),
            candidate.module(),
            candidate.pageOrFeature(),
            candidate.caseKind(),
            candidate.testType(),
            "P2",
            "",
            candidate.objective(),
            candidate.steps(),
            candidate.expectedResult(),
            file.getFileName(),
            candidate.evidence().nodeName(),
            "",
            sourceText(file, candidate.evidence()),
            "",
            "RULE_EXTRACTED".equals(resultOrigin) ? "figma_generated" : "figma_semantic",
            confidence,
            false,
            "ai_generated",
            "",
            resultOrigin,
            "PENDING_HUMAN_CONFIRMATION",
            1,
            "",
            figmaCandidateKey,
            "figma-derived",
            stringify(metadata(candidate, versionHash)),
            com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystem(),
            com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystemName(),
            Timestamp.from(now),
            Timestamp.from(now)
        );
        List<String> evidenceIds = new ArrayList<>();
        evidenceIds.add("figma-node:" + candidate.evidence().nodeId());
        evidenceIds.add("figma-version:" + versionHash);
        gateAuditService.record(new AiArtifactGateAuditService.GateRecord(
            figmaCandidateKey, String.valueOf(runPk), file.getProjectId(),
            resultOrigin, "PENDING_HUMAN_CONFIRMATION", 1, "", figmaCandidateKey, evidenceIds, List.of(), com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystem()));
    }

    private static String sha256Hex(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private List<AiFigmaNode> scopedNodes(Long figmaFilePk, AiFigmaNode root) {
        List<AiFigmaNode> scoped = new ArrayList<>();
        scoped.add(root);
        collectChildren(figmaFilePk, root.getNodeId(), scoped);
        return scoped;
    }

    private void collectChildren(Long figmaFilePk, String parentNodeId, List<AiFigmaNode> scoped) {
        for (AiFigmaNode child : nodeRepository.findByFigmaFilePkAndParentNodeId(figmaFilePk, parentNodeId)) {
            scoped.add(child);
            collectChildren(figmaFilePk, child.getNodeId(), scoped);
        }
    }

    private AiFigmaDraftPointResponse toDraftResponse(Map<String, Object> row) {
        return new AiFigmaDraftPointResponse(
            (String) row.get("POINT_ID"),
            (String) row.get("TITLE"),
            (String) row.get("MODULE_NAME"),
            (String) row.get("PAGE_OR_FEATURE"),
            (String) row.get("CASE_KIND"),
            (String) row.get("TEST_TYPE"),
            (String) row.get("TEST_OBJECTIVE"),
            (String) row.get("SUGGESTED_STEPS"),
            (String) row.get("EXPECTED_RESULT"),
            (String) row.get("REVIEW_STATUS"),
            (String) row.get("RESULT_ORIGIN"),
            (String) row.get("ACCEPTANCE_STATE"),
            parse((String) row.get("METADATA_JSON"))
        );
    }

    private ObjectNode metadata(AiFigmaDraftTestPointCandidate candidate, String versionHash) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("sourceType", "figma");
        metadata.put("figmaVersionHash", versionHash);
        metadata.set("evidence", objectMapper.valueToTree(candidate.evidence()));
        return metadata;
    }

    private String sourceText(AiFigmaFile file, AiFigmaRequirementEvidence evidence) {
        return "Figma File Key: " + file.getFileKey()
            + "\nFigma Node ID: " + evidence.nodeId()
            + "\nFigma Node Name: " + evidence.nodeName()
            + "\nFigma Component: " + evidence.componentName();
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            return objectMapper.createObjectNode();
        }
    }

    private String stringify(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid Figma analysis payload", exception);
        }
    }
}

record AiFigmaAnalysisRunResponse(
    String runId,
    String status,
    int draftTestPointCount,
    String resultOrigin,
    String acceptanceState
) {
}

record AiFigmaDraftPointListResponse(
    List<AiFigmaDraftPointResponse> items
) {
}

record AiFigmaDraftPointResponse(
    String pointId,
    String title,
    String module,
    String pageOrFeature,
    String caseKind,
    String testType,
    String objective,
    String steps,
    String expectedResult,
    String reviewStatus,
    String resultOrigin,
    String acceptanceState,
    JsonNode metadata
) {
}
