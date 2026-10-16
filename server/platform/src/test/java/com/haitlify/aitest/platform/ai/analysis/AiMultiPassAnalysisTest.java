package com.haitlify.aitest.platform.ai.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class AiMultiPassAnalysisTest {

    @Autowired
    private AiDraftMergeService mergeService;

    @Autowired
    private AiCoverageScanService coverageScanService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long runPk;
    private String runId;
    private long sourceChunkPk;

    @BeforeEach
    void resetData() {
        jdbcTemplate.update("delete from ai_unclear_questions");
        jdbcTemplate.update("delete from ai_coverage_summaries");
        jdbcTemplate.update("delete from ai_analysis_chunks");
        jdbcTemplate.update("delete from ai_call_records");
        jdbcTemplate.update("delete from ai_review_actions");
        jdbcTemplate.update("delete from ai_draft_test_points");
        jdbcTemplate.update("delete from ai_analysis_runs");
        jdbcTemplate.update("delete from ai_source_chunks");
        jdbcTemplate.update("delete from ai_source_documents");
        jdbcTemplate.update("delete from projects where code = 'AI_MULTIPASS'");

        Instant now = Instant.now();
        jdbcTemplate.update(
            "insert into projects(code, name, description, active, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
            "AI_MULTIPASS",
            "AI Multi Pass Project",
            "Multi pass tests",
            true,
            now,
            now
        );
        projectId = jdbcTemplate.queryForObject("select id from projects where code = 'AI_MULTIPASS'", Long.class);
        jdbcTemplate.update("""
            insert into ai_source_documents(
              document_id, project_id, file_name, document_type, content_hash, storage_location,
              extracted_text, parse_metadata_json, parser_version, source_file_metadata_json,
              actor_id, actor_name, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "doc-multipass", projectId, "requirements.md", "markdown", "hash-doc", "",
            "# 项目\n保存项目后状态为草稿", "{}", "parser-v1", "{}",
            "system-dev", "Development User", now, now
        );
        long documentPk = jdbcTemplate.queryForObject("select id from ai_source_documents where document_id = ?", Long.class, "doc-multipass");
        jdbcTemplate.update("""
            insert into ai_source_chunks(
              chunk_id, document_id, section_heading, page_number, line_start, line_end,
              table_identifier, image_identifier, figma_node_id, chunk_type, content_hash, content_text,
              evidence_metadata_json, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "source-chunk-1", documentPk, "项目", null, 1, 3,
            "", "", "", "text", "hash-source-1", "保存项目后状态为草稿",
            "{}", now, now
        );
        sourceChunkPk = jdbcTemplate.queryForObject("select id from ai_source_chunks where chunk_id = ?", Long.class, "source-chunk-1");
        runId = "run-multipass";
        jdbcTemplate.update("""
            insert into ai_analysis_runs(
              run_id, project_id, target_case_kind, template_id, analysis_strategy, execution_mode,
              status, worker_status, coverage_status, total_chunks, completed_chunks, current_chunk_id,
              resume_from_chunk_id, error_summary, config_snapshot_json, rule_snapshot_json,
              skill_snapshot_json, schema_snapshot_json, input_summary_json, output_summary_json,
              actor_id, actor_name, started_at, finished_at, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            runId, projectId, "functional", "", "balanced", "multi_pass",
            "running", "running", "pending", 2, 1, "", "", "",
            "{}", "{}", "{}", "{}", "{}", null,
            "system-dev", "Development User", now, null, now, now
        );
        runPk = jdbcTemplate.queryForObject("select id from ai_analysis_runs where run_id = ?", Long.class, runId);
        jdbcTemplate.update("""
            insert into ai_analysis_chunks(
              planned_chunk_id, run_id, source_chunk_ids_json, section_heading, token_estimate,
              status, skip_reason, content_text, evidence_metadata_json, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "planned-1", runPk, "[\"source-chunk-1\"]", "项目", 12, "planned", "", "保存项目后状态为草稿", "{}", now, now
        );
        jdbcTemplate.update("""
            insert into ai_analysis_chunks(
              planned_chunk_id, run_id, source_chunk_ids_json, section_heading, token_estimate,
              status, skip_reason, content_text, evidence_metadata_json, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "planned-2", runPk, "[]", "超限章节", 80, "skipped", "max_chunks_exceeded", "未分析内容", "{}", now, now
        );
    }

    @Test
    void mergesDuplicateDraftTargetsAndScansCoverage() {
        int persisted = mergeService.mergeDraftPoints(runId, projectId, List.of(
            new AiDraftMergeCandidate(
                "保存项目后状态为草稿",
                "项目管理",
                "项目编辑",
                "functional",
                "功能",
                "P2",
                "",
                "验证保存项目",
                "点击保存项目",
                "状态为草稿",
                0.8,
                false,
                "source-chunk-1",
                sourceChunkPk,
                "same-evidence"
            ),
            new AiDraftMergeCandidate(
                "保存项目后状态为草稿",
                "项目管理",
                "项目编辑",
                "functional",
                "功能",
                "P2",
                "",
                "验证保存项目",
                "点击保存项目",
                "状态为草稿",
                0.8,
                false,
                "source-chunk-1",
                sourceChunkPk,
                "same-evidence"
            )
        ), "multi-pass-req-1");

        assertEquals(1, persisted);
        assertEquals(1, jdbcTemplate.queryForObject("select count(*) from ai_draft_test_points where run_id = ?", Integer.class, runPk));
        jdbcTemplate.update("update ai_draft_test_points set review_status = 'confirmed', acceptance_state = 'ACCEPTED' where run_id = ?", runPk);

        AiCoverageScanResult result = coverageScanService.scan(runId);

        assertEquals(1, result.totalDraftPoints());
        assertEquals(1, result.moduleCounts().get("项目管理"));
        assertEquals(1, result.operationCounts().get("保存项目"));
        assertEquals(1, jdbcTemplate.queryForObject("select count(*) from ai_coverage_summaries where run_id = ?", Integer.class, runPk));
        assertEquals(1, jdbcTemplate.queryForObject("select count(*) from ai_unclear_questions where run_id = ?", Integer.class, runPk));
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("max_chunks_exceeded")));
    }
}
