package com.haitlify.aitest.platform.ai.visual;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.haitlify.aitest.platform.ai.gateway.AiGateway;
import com.haitlify.aitest.platform.ai.gateway.AiVisionResponse;

@SpringBootTest
@ActiveProfiles("test")
class AiVisualAnalysisServiceTest {

    @Autowired
    private AiVisualAnalysisService analysisService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AiGateway aiGateway;

    private String assetId;

    @BeforeEach
    void resetData() {
        jdbcTemplate.update("delete from ai_review_actions");
        jdbcTemplate.update("delete from ai_draft_test_points");
        jdbcTemplate.update("delete from ai_tool_call_records");
        jdbcTemplate.update("delete from ai_visual_analysis_results");
        jdbcTemplate.update("delete from ai_artifact_gate_audits");
        jdbcTemplate.update("delete from ai_coverage_summaries");
        jdbcTemplate.update("delete from ai_unclear_questions");
        jdbcTemplate.update("delete from ai_analysis_chunks");
        jdbcTemplate.update("delete from ai_call_records");
        jdbcTemplate.update("delete from ai_analysis_runs");
        jdbcTemplate.update("delete from ai_visual_regions");
        jdbcTemplate.update("delete from ai_visual_assets");
        jdbcTemplate.update("delete from ai_system_configs");
        jdbcTemplate.update("delete from projects where code = 'AI_VISUAL_ANALYSIS'");
        Instant now = Instant.now();
        jdbcTemplate.update(
            "insert into projects(code, name, description, active, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
            "AI_VISUAL_ANALYSIS",
            "AI Visual Analysis Project",
            "Visual analysis tests",
            true,
            now,
            now
        );
        long projectId = jdbcTemplate.queryForObject("select id from projects where code = 'AI_VISUAL_ANALYSIS'", Long.class);
        jdbcTemplate.update("""
            insert into ai_system_configs(
              config_key, enabled, provider_type, base_url, text_model, vision_model, embedding_model,
              api_key_ciphertext, credential_configured, request_timeout_ms, max_input_length,
              max_output_length, retry_count, request_logging_enabled, external_document_send_allowed,
              provider_config_json, masked_config_json, vision_enabled, max_image_bytes, allowed_image_types_json,
              actor_id, actor_name, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "default", true, "openai-compatible", "https://api.example.com/v1", "gpt-text", "gpt-vision", "",
            "sk-test", true, 30000, 0, 0, 0, false, false, null, "****test", true, 4096,
            "[\"image/png\"]", "system-dev", "Development User", now, now
        );
        assetId = "vis-analysis-1";
        jdbcTemplate.update("""
            insert into ai_visual_assets(
              asset_id, project_id, file_name, content_type, content_hash, storage_location,
              byte_size, width, height, content_base64, actor_id, actor_name, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            assetId, projectId, "login.png", "image/png", "hash-1", "db:ai_visual_assets/vis-analysis-1",
            3, 800, 600, "AQID", "system-dev", "Development User", now, now
        );
    }

    @Test
    void validVisualJsonPersistsAnalysisResultAndRegions() {
        when(aiGateway.visionAnalyze(any(), any()))
            .thenReturn(new AiVisionResponse(true, validVisualJson(), "vision-1", 12, 34, null));

        AiVisualAnalysisResponse response = analysisService.analyze(assetId);

        assertThat(response.analysisId()).isNotBlank();
        assertThat(response.status()).isEqualTo("succeeded");
        assertThat(response.layout().path("pageType").asText()).isEqualTo("login");
        assertThat(response.regions()).hasSize(1);
        assertThat(response.regions().getFirst().regionType()).isEqualTo("button");
        assertThat(response.suggestedTestPoints()).hasSize(1);
        assertThat(response.suggestedTestPoints().getFirst().sourceRegionId()).isEqualTo(response.regions().getFirst().regionId());
        assertThat(response.interactions().getFirst()).contains("submit navigates");
        assertThat(jdbcTemplate.queryForObject("select count(*) from ai_visual_analysis_results", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from ai_visual_regions", Integer.class)).isEqualTo(1);
    }

    @Test
    void missingLayoutFailsValidationSafely() {
        when(aiGateway.visionAnalyze(any(), any()))
            .thenReturn(new AiVisionResponse(true, "{\"components\":[]}", "vision-2", 1, 1, null));

        assertThatThrownBy(() -> analysisService.analyze(assetId))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("layout");
    }

    @Test
    void interactionHintsArePreserved() {
        when(aiGateway.visionAnalyze(any(), any()))
            .thenReturn(new AiVisionResponse(true, validVisualJson(), "vision-3", 12, 34, null));

        AiVisualAnalysisResponse response = analysisService.analyze(assetId);

        assertThat(response.interactions()).contains("submit navigates to dashboard");
    }

    @Test
    void visualAnalysisCreatesDraftTestPointsWithRegionEvidence() {
        when(aiGateway.visionAnalyze(any(), any()))
            .thenReturn(new AiVisionResponse(true, validVisualJson(), "vision-4", 12, 34, null));
        long projectId = jdbcTemplate.queryForObject("select project_id from ai_visual_assets where asset_id = ?", Long.class, assetId);
        String runId = "run-visual-1";
        Instant now = Instant.now();
        jdbcTemplate.update("""
            insert into ai_analysis_runs(
              run_id, project_id, target_case_kind, template_id, analysis_strategy, execution_mode,
              status, worker_status, coverage_status, total_chunks, completed_chunks, current_chunk_id,
              resume_from_chunk_id, error_summary, config_snapshot_json, rule_snapshot_json,
              skill_snapshot_json, schema_snapshot_json, input_summary_json, output_summary_json,
              actor_id, actor_name, started_at, finished_at, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            runId, projectId, "web_automation", "", "balanced", "inline", "running", "running",
            "pending", 0, 0, "", "", "", "{}", "{}", "{}", "{}", "{}", "{}",
            "system-dev", "Development User", now, null, now, now
        );

        AiVisualAnalysisResponse analysis = analysisService.analyze(assetId);
        int inserted = analysisService.createDraftTestPoints(runId, projectId, analysis);

        assertThat(inserted).isGreaterThan(0);
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from ai_draft_test_points where source_region_id is not null and source_image_id = ?",
            Integer.class,
            assetId
        )).isGreaterThan(0);
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from ai_draft_test_points where inferred = true and source_region_id is not null",
            Integer.class
        )).isEqualTo(1);
    }

    @Test
    void visualDraftPointsCarryModelGeneratedOriginOnlyWithProviderEvidence() {
        when(aiGateway.visionAnalyze(any(), any()))
            .thenReturn(new AiVisionResponse(true, validVisualJson(), "vision-4", 12, 34, null));
        long projectId = jdbcTemplate.queryForObject("select project_id from ai_visual_assets where asset_id = ?", Long.class, assetId);
        String runId = insertVisualRun(projectId);

        AiVisualAnalysisResponse analysis = analysisService.analyze(assetId);
        analysisService.createDraftTestPoints(runId, projectId, analysis);

        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from ai_draft_test_points where result_origin = 'MODEL_GENERATED' and acceptance_state = 'PENDING_HUMAN_CONFIRMATION'",
            Integer.class)).isGreaterThan(0);
        assertThat(jdbcTemplate.queryForObject(
            "select result_origin from ai_visual_analysis_results where analysis_id = ?", String.class, analysis.analysisId()))
            .isEqualTo("MODEL_GENERATED");
    }

    @Test
    void visualDraftPointsAreBlockedWhenProviderRequestIdIsMissing() {
        when(aiGateway.visionAnalyze(any(), any()))
            .thenReturn(new AiVisionResponse(true, validVisualJson(), "", 12, 34, null));
        long projectId = jdbcTemplate.queryForObject("select project_id from ai_visual_assets where asset_id = ?", Long.class, assetId);
        String runId = insertVisualRun(projectId);

        AiVisualAnalysisResponse analysis = analysisService.analyze(assetId);
        analysisService.createDraftTestPoints(runId, projectId, analysis);

        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from ai_draft_test_points where result_origin = 'BLOCKED' and acceptance_state = 'BLOCKED'",
            Integer.class)).isGreaterThan(0);
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from ai_draft_test_points where result_origin = 'MODEL_GENERATED'",
            Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "select result_origin from ai_visual_analysis_results where analysis_id = ?", String.class, analysis.analysisId()))
            .isEqualTo("BLOCKED");
    }

    private String insertVisualRun(long projectId) {
        String runId = "run-visual-1";
        Instant now = Instant.now();
        jdbcTemplate.update("""
            insert into ai_analysis_runs(
              run_id, project_id, target_case_kind, template_id, analysis_strategy, execution_mode,
              status, worker_status, coverage_status, total_chunks, completed_chunks, current_chunk_id,
              resume_from_chunk_id, error_summary, config_snapshot_json, rule_snapshot_json,
              skill_snapshot_json, schema_snapshot_json, input_summary_json, output_summary_json,
              actor_id, actor_name, started_at, finished_at, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            runId, projectId, "web_automation", "", "balanced", "inline", "running", "running",
            "pending", 0, 0, "", "", "", "{}", "{}", "{}", "{}", "{}", "{}",
            "system-dev", "Development User", now, null, now, now
        );
        return runId;
    }

    private String validVisualJson() {
        return """
            {
              "layout": { "pageType": "login", "hierarchy": ["form", "submit"] },
              "components": [
                {
                  "name": "Submit",
                  "type": "button",
                  "region": { "x": 240, "y": 420, "width": 128, "height": 40 },
                  "text": "登录",
                  "state": "enabled",
                  "styleHints": ["primary", "blue"],
                  "confidence": 0.84
                }
              ],
              "styles": { "density": "comfortable" },
              "interactions": ["submit navigates to dashboard"],
              "states": ["enabled"],
              "risks": ["missing error state"],
              "suggestedTestPoints": [
                {
                  "title": "登录按钮可提交",
                  "regionName": "Submit",
                  "category": "interaction"
                }
              ],
              "unclearQuestions": []
            }
            """;
    }
}
