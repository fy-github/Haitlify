package com.haitlify.aitest.platform.ai.analysis.chunk;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional
public class AiAnalysisChunkPlanningService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AiChunkPlanner planner = new AiChunkPlanner();

    AiAnalysisChunkPlanningService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public AiAnalysisChunkPlanResponse planChunks(String runId, AiAnalysisChunkPlanRequest request) {
        Map<String, Object> run = jdbcTemplate.queryForMap("select * from ai_analysis_runs where run_id = ?", runId);
        Long runPk = ((Number) run.get("ID")).longValue();
        List<String> documentIds = documentIds((String) run.get("INPUT_SUMMARY_JSON"));
        List<AiChunkSource> sources = loadSources(documentIds);
        List<AiPlannedAnalysisChunk> planned = planner.plan(new AiChunkPlanningRequest(
            sources,
            request.maxInputTokens(),
            request.maxChunks(),
            request.analysisScope()
        ));

        jdbcTemplate.update("delete from ai_analysis_chunks where run_id = ?", runPk);
        Instant now = Instant.now();
        for (AiPlannedAnalysisChunk chunk : planned) {
            jdbcTemplate.update("""
                insert into ai_analysis_chunks(
                  planned_chunk_id, run_id, source_chunk_ids_json, section_heading, token_estimate,
                  status, skip_reason, content_text, evidence_metadata_json, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                runId + "-" + chunk.plannedChunkId(),
                runPk,
                stringify(objectMapper.valueToTree(chunk.sourceChunkIds())),
                chunk.section(),
                chunk.tokenEstimate(),
                chunk.status(),
                chunk.skipReason(),
                chunk.contentText(),
                "{}",
                Timestamp.from(now),
                Timestamp.from(now)
            );
        }
        jdbcTemplate.update(
            "update ai_analysis_runs set total_chunks = ?, coverage_status = ?, updated_at = ? where id = ?",
            planned.size(),
            "planned",
            Timestamp.from(now),
            runPk
        );
        return new AiAnalysisChunkPlanResponse(planned.stream()
            .map(chunk -> new AiAnalysisChunkPlanItem(
                chunk.plannedChunkId(),
                chunk.sourceChunkIds(),
                chunk.section(),
                chunk.tokenEstimate(),
                chunk.status(),
                chunk.skipReason(),
                chunk.contentText()
            ))
            .toList());
    }

    private List<AiChunkSource> loadSources(List<String> documentIds) {
        if (documentIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", documentIds.stream().map(ignored -> "?").toList());
        return jdbcTemplate.query("""
                select c.chunk_id, d.document_id, c.section_heading, c.content_text
                from ai_source_chunks c
                join ai_source_documents d on d.id = c.document_id
                where d.document_id in (%s)
                order by c.id asc
                """.formatted(placeholders),
            documentIds.toArray(),
            (rs, rowNum) -> new AiChunkSource(
                rs.getString("chunk_id"),
                rs.getString("document_id"),
                rs.getString("section_heading"),
                rs.getString("content_text")
            )
        );
    }

    private List<String> documentIds(String inputSummaryJson) {
        JsonNode inputSummary = parse(inputSummaryJson);
        return objectMapper.convertValue(
            inputSummary.path("documentIds"),
            objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)
        );
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid AI analysis input summary", exception);
        }
    }

    private String stringify(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node == null ? objectMapper.createObjectNode() : node);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid AI analysis chunk JSON", exception);
        }
    }

    public record AiAnalysisChunkPlanRequest(
        int maxInputTokens,
        int maxChunks,
        String analysisScope
    ) {
    }

    public record AiAnalysisChunkPlanResponse(
        List<AiAnalysisChunkPlanItem> items
    ) {
    }

    public record AiAnalysisChunkPlanItem(
        String plannedChunkId,
        List<String> sourceChunkIds,
        String section,
        int tokenEstimate,
        String status,
        String skipReason,
        String contentText
    ) {
    }
}
