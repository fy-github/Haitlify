package com.haitlify.aitest.platform.ai.visual;

import static org.assertj.core.api.Assertions.assertThat;

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
class AiVisualRegionServiceTest {

    @Autowired
    private AiVisualRegionService regionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long assetPk;
    private String assetId;

    @BeforeEach
    void resetData() {
        jdbcTemplate.update("delete from ai_review_actions");
        jdbcTemplate.update("delete from ai_draft_test_points");
        jdbcTemplate.update("delete from ai_visual_analysis_results");
        jdbcTemplate.update("delete from ai_visual_regions");
        jdbcTemplate.update("delete from ai_source_chunks");
        jdbcTemplate.update("delete from ai_source_documents");
        jdbcTemplate.update("delete from ai_visual_assets");
        jdbcTemplate.update("delete from projects where code = 'AI_REGION'");
        Instant now = Instant.now();
        jdbcTemplate.update(
            "insert into projects(code, name, description, active, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
            "AI_REGION",
            "AI Region Project",
            "Visual region tests",
            true,
            now,
            now
        );
        long projectId = jdbcTemplate.queryForObject("select id from projects where code = 'AI_REGION'", Long.class);
        assetId = "vis-region-1";
        jdbcTemplate.update("""
            insert into ai_visual_assets(
              asset_id, project_id, file_name, content_type, content_hash, storage_location,
              byte_size, width, height, content_base64, actor_id, actor_name, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            assetId,
            projectId,
            "login.png",
            "image/png",
            "hash-1",
            "db:ai_visual_assets/vis-region-1",
            128,
            800,
            600,
            "",
            "system-dev",
            "Development User",
            now,
            now
        );
        assetPk = jdbcTemplate.queryForObject("select id from ai_visual_assets where asset_id = ?", Long.class, assetId);
    }

    @Test
    void storesBoundingBoxAndTextRegion() {
        AiVisualRegionResponse region = regionService.createRegion(new CreateAiVisualRegionRequest(
            assetId,
            "text",
            12,
            34,
            160,
            24,
            "登录",
            "",
            List.of("font-weight: 600"),
            0.92
        ));

        assertThat(region.regionId()).isNotBlank();
        assertThat(region.regionType()).isEqualTo("text");
        assertThat(region.text()).isEqualTo("登录");
        assertThat(region.x()).isEqualTo(12);
        assertThat(region.y()).isEqualTo(34);
        assertThat(region.width()).isEqualTo(160);
        assertThat(region.height()).isEqualTo(24);
        assertThat(region.styleHints()).contains("font-weight: 600");
        assertThat(region.confidence()).isEqualTo(0.92);
        assertThat(regionService.listRegions(assetId).items()).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from ai_visual_regions where asset_id = ?", Integer.class, assetPk)).isEqualTo(1);
    }

    @Test
    void ocrUnavailableCreatesExplicitWarningWithoutBlockingVisionAnalysis() {
        AiVisualOcrStatus status = regionService.recordOcrUnavailable(assetId, "tesseract is not installed");

        assertThat(status.assetId()).isEqualTo(assetId);
        assertThat(status.ocrStatus()).isEqualTo("unavailable");
        assertThat(status.visionAnalysisAllowed()).isTrue();
        assertThat(status.warnings()).contains("OCR unavailable: tesseract is not installed");
    }

    @Test
    void storesComponentCandidateRegionTypeAndConfidence() {
        AiVisualRegionResponse region = regionService.createRegion(new CreateAiVisualRegionRequest(
            assetId,
            "button",
            240,
            420,
            128,
            40,
            "提交",
            "primary button",
            List.of("blue fill", "rounded"),
            0.81
        ));

        assertThat(region.regionType()).isEqualTo("button");
        assertThat(region.componentHint()).isEqualTo("primary button");
        assertThat(region.confidence()).isEqualTo(0.81);
    }
}
