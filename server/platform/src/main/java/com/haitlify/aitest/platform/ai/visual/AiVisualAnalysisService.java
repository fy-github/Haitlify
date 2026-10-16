package com.haitlify.aitest.platform.ai.visual;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.haitlify.aitest.platform.ai.gateway.AiVisionRequest;
import com.haitlify.aitest.platform.ai.gateway.AiVisionResponse;
import com.haitlify.aitest.platform.ai.runtime.artifact.AiArtifactGateAuditService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
@Transactional
class AiVisualAnalysisService {

    private final JdbcTemplate jdbcTemplate;
    private final com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade taskFacade;
    private final AiVisualRegionService regionService;
    private final ObjectMapper objectMapper;
    private final AiVisualAnalysisSchema schema = new AiVisualAnalysisSchema();
    private final AiArtifactGateAuditService gateAuditService;

    AiVisualAnalysisService(
        JdbcTemplate jdbcTemplate,
        com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade taskFacade,
        AiVisualRegionService regionService,
        ObjectMapper objectMapper,
        AiArtifactGateAuditService gateAuditService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.taskFacade = taskFacade;
        this.regionService = regionService;
        this.objectMapper = objectMapper;
        this.gateAuditService = gateAuditService;
    }

    AiVisualAnalysisResponse analyze(String assetId) {
        VisualAssetRow asset = loadAsset(assetId);
        ObjectNode prompt = buildPrompt(asset);
        AiVisionResponse vision = taskFacade.generateVision(
            new com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade.VisionTaskExecution(
                "visual_analysis", null, "system", null, prompt.toString(),
                asset.contentType(), Base64.getDecoder().decode(asset.contentBase64()), 2048, null));
        if (!vision.success()) {
            throw new IllegalArgumentException(blankToDefault(vision.errorMessage(), "Visual analysis failed"));
        }

        JsonNode output = parseOutput(vision.content());
        schema.validate(output);
        List<AiVisualRegionResponse> regions = persistComponentRegions(asset.assetId(), output.path("components"));
        List<VisualSuggestedTestPoint> suggested = mapSuggestedTestPoints(output.path("suggestedTestPoints"), regions);
        String analysisId = "visual-analysis-" + UUID.randomUUID();
        persistAnalysis(asset.id(), analysisId, output, vision);
        return new AiVisualAnalysisResponse(
            analysisId,
            asset.assetId(),
            "succeeded",
            output.path("layout"),
            regions,
            interactions(output.path("interactions")),
            suggested,
            output.path("unclearQuestions")
        );
    }

    int createDraftTestPoints(String runId, Long projectId, AiVisualAnalysisResponse analysis) {
        Long runPk = jdbcTemplate.queryForObject("select id from ai_analysis_runs where run_id = ?", Long.class, runId);
        boolean runtimeEvidence = hasProviderRequestId(analysis.analysisId());
        AiVisualTestPointMapper mapper = new AiVisualTestPointMapper(objectMapper);
        int inserted = 0;
        for (VisualDraftTestPointCandidate candidate : mapper.map(analysis)) {
            insertDraftPoint(runPk, projectId, analysis.assetId(), candidate, runtimeEvidence);
            inserted += 1;
        }
        return inserted;
    }

    /**
     * Fail-closed evidence check: only a persisted provider request id proves the vision model call
     * actually happened, so a missing/unreadable analysis record must never yield MODEL_GENERATED.
     */
    private boolean hasProviderRequestId(String analysisId) {
        if (analysisId == null || analysisId.isBlank()) return false;
        try {
            String requestId = jdbcTemplate.queryForObject(
                "select request_id from ai_visual_analysis_results where analysis_id = ?", String.class, analysisId);
            return requestId != null && !requestId.isBlank();
        } catch (Exception exception) {
            return false;
        }
    }

    private void insertDraftPoint(Long runPk, Long projectId, String assetId, VisualDraftTestPointCandidate candidate, boolean runtimeEvidence) {
        Instant now = Instant.now();
        String resultOrigin = runtimeEvidence ? "MODEL_GENERATED" : "BLOCKED";
        String acceptanceState = runtimeEvidence ? "PENDING_HUMAN_CONFIRMATION" : "BLOCKED";
        jdbcTemplate.update("""
            insert into ai_draft_test_points(
              point_id, run_id, project_id, source_document_id, source_chunk_id, source_region_id, title,
              module_name, page_or_feature, case_kind, test_type, priority_level, precondition_text,
              test_objective, suggested_steps, expected_result, source_file, source_section, source_page,
              source_text, source_image_id, extraction_method, confidence_score, inferred, review_status,
              review_comment, result_origin, acceptance_state, revision, parent_artifact_id, idempotency_key, template_id, metadata_json, field_origins_json, actor_id, actor_name, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "point-" + UUID.randomUUID(),
            runPk,
            projectId,
            null,
            null,
            sourceRegionPk(candidate.sourceRegionId()),
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
            "",
            "",
            "",
            "视觉区域证据: " + blankToDefault(candidate.sourceRegionId(), assetId),
            assetId,
            "visual_ai",
            0.80,
            candidate.inferred(),
            runtimeEvidence ? "ai_generated" : "blocked",
            "",
            resultOrigin,
            acceptanceState,
            1,
            "",
            "visual:" + runPk + ":" + candidate.title(),
            "",
            "{}",
            runtimeEvidence ? "[\"MODEL_PROPOSED\"]" : "[]",
            com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystem(),
            com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystemName(),
            Timestamp.from(now),
            Timestamp.from(now)
        );
        gateAuditService.record(new AiArtifactGateAuditService.GateRecord(
            "visual:" + runPk + ":" + candidate.title(), String.valueOf(runPk), projectId,
            resultOrigin, acceptanceState, 1, "", "visual:" + runPk + ":" + candidate.title(),
            candidate.sourceRegionId() == null || candidate.sourceRegionId().isBlank() ? List.of() : List.of(candidate.sourceRegionId()), List.of(), com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystem()));
    }

    private Long sourceRegionPk(String sourceRegionId) {
        if (sourceRegionId == null || sourceRegionId.isBlank()) {
            return null;
        }
        List<Long> ids = jdbcTemplate.queryForList(
            "select id from ai_visual_regions where region_id = ?",
            Long.class,
            sourceRegionId
        );
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private ObjectNode buildPrompt(VisualAssetRow asset) {
        ObjectNode prompt = objectMapper.createObjectNode();
        prompt.put("task", "analyze_visual_requirement");
        prompt.put("fileName", asset.fileName());
        prompt.put("width", asset.width());
        prompt.put("height", asset.height());
        prompt.put("instruction", "Return JSON with layout, components, styles, interactions, states, risks, suggestedTestPoints, and unclearQuestions.");
        return prompt;
    }

    private List<AiVisualRegionResponse> persistComponentRegions(String assetId, JsonNode components) {
        List<AiVisualRegionResponse> regions = new ArrayList<>();
        for (JsonNode component : components) {
            JsonNode region = component.path("region");
            regions.add(regionService.createRegion(new CreateAiVisualRegionRequest(
                assetId,
                blankToDefault(component.path("type").asText("unknown"), "unknown"),
                region.path("x").asInt(0),
                region.path("y").asInt(0),
                region.path("width").asInt(0),
                region.path("height").asInt(0),
                component.path("text").asText(""),
                component.path("name").asText(""),
                toStringList(component.path("styleHints")),
                component.path("confidence").asDouble(0)
            )));
        }
        return regions;
    }

    private List<VisualSuggestedTestPoint> mapSuggestedTestPoints(JsonNode suggestedTestPoints, List<AiVisualRegionResponse> regions) {
        Map<String, String> regionIdsByName = new LinkedHashMap<>();
        for (AiVisualRegionResponse region : regions) {
            if (!region.componentHint().isBlank()) {
                regionIdsByName.put(region.componentHint(), region.regionId());
            }
        }
        List<VisualSuggestedTestPoint> items = new ArrayList<>();
        for (JsonNode point : suggestedTestPoints) {
            String regionName = point.path("regionName").asText("");
            items.add(new VisualSuggestedTestPoint(
                point.path("title").asText(""),
                point.path("category").asText("visual"),
                regionIdsByName.getOrDefault(regionName, regions.isEmpty() ? "" : regions.getFirst().regionId())
            ));
        }
        return items;
    }

    private void persistAnalysis(Long assetPk, String analysisId, JsonNode output, AiVisionResponse vision) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
            insert into ai_visual_analysis_results(
              analysis_id, asset_id, status, ocr_status, layout_json, components_json, styles_json,
              interactions_json, states_json, risks_json, suggested_test_points_json, unclear_questions_json,
              warnings_json, raw_output_json, request_id, token_usage_input, token_usage_output, error_message,
              result_origin, acceptance_state, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            analysisId,
            assetPk,
            "succeeded",
            "unavailable",
            output.path("layout").toString(),
            output.path("components").toString(),
            output.path("styles").toString(),
            output.path("interactions").toString(),
            output.path("states").toString(),
            output.path("risks").toString(),
            output.path("suggestedTestPoints").toString(),
            output.path("unclearQuestions").toString(),
            "[]",
            output.toString(),
            blankToDefault(vision.requestId(), ""),
            vision.inputTokens(),
            vision.outputTokens(),
            "",
            blankToDefault(vision.requestId(), "").isBlank() ? "BLOCKED" : "MODEL_GENERATED",
            blankToDefault(vision.requestId(), "").isBlank() ? "BLOCKED" : "PENDING_HUMAN_CONFIRMATION",
            Timestamp.from(now),
            Timestamp.from(now)
        );
    }

    private VisualAssetRow loadAsset(String assetId) {
        List<VisualAssetRow> assets = jdbcTemplate.query("""
            select id, asset_id, file_name, content_type, width, height, content_base64
            from ai_visual_assets
            where asset_id = ?
            """, this::mapAsset, assetId);
        if (assets.isEmpty()) {
            throw com.haitlify.aitest.platform.ai.security.AiInvocationAuthorization.notFound();
        }
        return assets.getFirst();
    }

    private VisualAssetRow mapAsset(ResultSet rs, int rowNum) throws SQLException {
        return new VisualAssetRow(
            rs.getLong("id"),
            rs.getString("asset_id"),
            rs.getString("file_name"),
            rs.getString("content_type"),
            rs.getInt("width"),
            rs.getInt("height"),
            rs.getString("content_base64")
        );
    }

    private JsonNode parseOutput(String content) {
        try {
            return objectMapper.readTree(content);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid visual analysis output JSON", exception);
        }
    }

    private List<String> interactions(JsonNode node) {
        return toStringList(node);
    }

    private List<String> toStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            values.add(item.asText(""));
        }
        return values;
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}

record AiVisualAnalysisResponse(
    String analysisId,
    String assetId,
    String status,
    JsonNode layout,
    List<AiVisualRegionResponse> regions,
    List<String> interactions,
    List<VisualSuggestedTestPoint> suggestedTestPoints,
    JsonNode unclearQuestions
) {
}

record VisualSuggestedTestPoint(
    String title,
    String category,
    String sourceRegionId
) {
}

record VisualAssetRow(
    Long id,
    String assetId,
    String fileName,
    String contentType,
    int width,
    int height,
    String contentBase64
) {
}

