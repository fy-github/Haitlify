package com.haitlify.aitest.platform.ai.analysis;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.haitlify.aitest.platform.ai.runtime.artifact.AiArtifactGateAuditService;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
@Transactional
class AiDraftMergeService {


    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AiArtifactGateAuditService gateAuditService;

    AiDraftMergeService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, AiArtifactGateAuditService gateAuditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.gateAuditService = gateAuditService;
    }

    int mergeDraftPoints(String runId, Long projectId, List<AiDraftMergeCandidate> candidates, String providerRequestId) {
        Long runPk = jdbcTemplate.queryForObject("select id from ai_analysis_runs where run_id = ?", Long.class, runId);
        boolean runtimeEvidence = providerRequestId != null && !providerRequestId.isBlank();
        Set<String> seen = new LinkedHashSet<>();
        int inserted = 0;
        for (AiDraftMergeCandidate candidate : candidates) {
            String key = dedupeKey(projectId, candidate);
            if (!seen.add(key)) {
                continue;
            }
            insertDraftPoint(runPk, projectId, candidate, runtimeEvidence);
            inserted += 1;
        }
        return inserted;
    }

    private void insertDraftPoint(Long runPk, Long projectId, AiDraftMergeCandidate candidate, boolean runtimeEvidence) {
        Long sourceDocumentPk = candidate.sourceChunkPk() == null ? null : jdbcTemplate.queryForObject(
            "select document_id from ai_source_chunks where id = ?",
            Long.class,
            candidate.sourceChunkPk()
        );
        String sourceFile = candidate.sourceChunkPk() == null ? "" : jdbcTemplate.queryForObject(
            """
            select d.file_name
            from ai_source_chunks c
            join ai_source_documents d on d.id = c.document_id
            where c.id = ?
            """,
            String.class,
            candidate.sourceChunkPk()
        );
        String sourceText = candidate.sourceChunkPk() == null ? "" : jdbcTemplate.queryForObject(
            "select content_text from ai_source_chunks where id = ?",
            String.class,
            candidate.sourceChunkPk()
        );
        Instant now = Instant.now();
        String resultOrigin = runtimeEvidence ? "MODEL_GENERATED" : "BLOCKED";
        String acceptanceState = runtimeEvidence ? "PENDING_HUMAN_CONFIRMATION" : "BLOCKED";
        String reviewStatus = runtimeEvidence ? "ai_generated" : "blocked";
        jdbcTemplate.update("""
            insert into ai_draft_test_points(
              point_id, run_id, project_id, source_document_id, source_chunk_id, title, module_name,
              page_or_feature, case_kind, test_type, priority_level, precondition_text, test_objective,
              suggested_steps, expected_result, source_file, source_section, source_page, source_text,
              source_image_id, extraction_method, confidence_score, inferred, review_status, review_comment,
              result_origin, acceptance_state, revision, parent_artifact_id, idempotency_key, template_id, metadata_json, field_origins_json, actor_id, actor_name, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "draft-point-" + UUID.randomUUID(),
            runPk,
            projectId,
            sourceDocumentPk,
            candidate.sourceChunkPk(),
            candidate.title(),
            candidate.module(),
            candidate.pageOrFeature(),
            candidate.caseKind(),
            candidate.testType(),
            candidate.priority(),
            candidate.precondition(),
            candidate.objective(),
            candidate.steps(),
            candidate.expectedResult(),
            sourceFile,
            "",
            "",
            sourceText,
            "",
            "ai_generated",
            candidate.confidenceScore(),
            candidate.inferred(),
            reviewStatus,
            "",
            resultOrigin,
            acceptanceState,
            1,
            "",
            "draft-merge:" + runPk + ":" + candidate.evidenceHash(),
            "",
            stringify(metadata(candidate)),
            runtimeEvidence ? "[\"MODEL_PROPOSED\"]" : "[]",
            com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystem(),
            com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystemName(),
            Timestamp.from(now),
            Timestamp.from(now)
        );
        List<String> evidenceIds = candidate.sourceChunkId() == null || candidate.sourceChunkId().isBlank()
            ? List.of()
            : List.of(candidate.sourceChunkId());
        gateAuditService.record(new AiArtifactGateAuditService.GateRecord(
            "draft-point:" + runPk + ":" + candidate.evidenceHash(), String.valueOf(runPk), projectId,
            resultOrigin, acceptanceState, 1, "", "draft-merge:" + runPk + ":" + candidate.evidenceHash(), evidenceIds, List.of(), com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystem()));
    }

    private String dedupeKey(Long projectId, AiDraftMergeCandidate candidate) {
        return String.join("|",
            String.valueOf(projectId),
            candidate.caseKind(),
            candidate.module(),
            candidate.pageOrFeature(),
            candidate.objective(),
            candidate.expectedResult(),
            candidate.evidenceHash()
        );
    }

    private ObjectNode metadata(AiDraftMergeCandidate candidate) {
        return objectMapper.createObjectNode()
            .put("sourceChunkId", candidate.sourceChunkId())
            .put("evidenceHash", candidate.evidenceHash());
    }

    private String stringify(ObjectNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid draft merge metadata", exception);
        }
    }
}

record AiDraftMergeCandidate(
    String title,
    String module,
    String pageOrFeature,
    String caseKind,
    String testType,
    String priority,
    String precondition,
    String objective,
    String steps,
    String expectedResult,
    double confidenceScore,
    boolean inferred,
    String sourceChunkId,
    Long sourceChunkPk,
    String evidenceHash
) {
}
