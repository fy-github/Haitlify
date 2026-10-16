package com.haitlify.aitest.platform.ai.visual;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional
class AiVisualRegionService {

    private static final Set<String> SUPPORTED_REGION_TYPES = Set.of(
        "text",
        "container",
        "navigation",
        "form",
        "field",
        "button",
        "table",
        "dialog",
        "toast",
        "state_badge",
        "unknown"
    );

    private final AiVisualRegionRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    AiVisualRegionService(AiVisualRegionRepository repository, JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    AiVisualRegionResponse createRegion(CreateAiVisualRegionRequest request) {
        Long assetPk = assetPk(request.assetId());
        String regionType = normalizeRegionType(request.regionType());
        Instant now = Instant.now();
        AiVisualRegion region = repository.save(new AiVisualRegion(
            null,
            "region-" + UUID.randomUUID(),
            assetPk,
            regionType,
            Math.max(request.x(), 0),
            Math.max(request.y(), 0),
            Math.max(request.width(), 0),
            Math.max(request.height(), 0),
            blankToDefault(request.text(), ""),
            blankToDefault(request.componentHint(), ""),
            stringify(request.styleHints() == null ? List.of() : request.styleHints()),
            clampConfidence(request.confidence()),
            now,
            now
        ));
        return toResponse(request.assetId(), region);
    }

    AiVisualRegionListResponse listRegions(String assetId) {
        Long assetPk = assetPk(assetId);
        return new AiVisualRegionListResponse(
            repository.findByAssetIdOrderByIdAsc(assetPk).stream()
                .map(region -> toResponse(assetId, region))
                .toList()
        );
    }

    AiVisualOcrStatus recordOcrUnavailable(String assetId, String reason) {
        assetPk(assetId);
        String detail = blankToDefault(reason, "OCR engine unavailable");
        return new AiVisualOcrStatus(
            assetId,
            "unavailable",
            true,
            List.of("OCR unavailable: " + detail)
        );
    }

    private Long assetPk(String assetId) {
        List<Long> ids = jdbcTemplate.queryForList(
            "select id from ai_visual_assets where asset_id = ?",
            Long.class,
            assetId
        );
        if (ids.isEmpty()) {
            throw com.haitlify.aitest.platform.ai.security.AiInvocationAuthorization.notFound();
        }
        return ids.getFirst();
    }

    private String normalizeRegionType(String regionType) {
        String normalized = blankToDefault(regionType, "unknown");
        if (!SUPPORTED_REGION_TYPES.contains(normalized)) {
            return "unknown";
        }
        return normalized;
    }

    private double clampConfidence(double confidence) {
        if (confidence < 0) {
            return 0;
        }
        if (confidence > 1) {
            return 1;
        }
        return confidence;
    }

    private String stringify(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Invalid visual region style hints", exception);
        }
    }

    private List<String> parseStyleHints(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(
                json,
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)
            );
        } catch (JacksonException exception) {
            return List.of();
        }
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private AiVisualRegionResponse toResponse(String externalAssetId, AiVisualRegion region) {
        return new AiVisualRegionResponse(
            region.getRegionId(),
            externalAssetId,
            region.getRegionType(),
            region.getX(),
            region.getY(),
            region.getWidth(),
            region.getHeight(),
            region.getText(),
            region.getComponentHint(),
            parseStyleHints(region.getStyleHintJson()),
            region.getConfidence()
        );
    }
}

record CreateAiVisualRegionRequest(
    String assetId,
    String regionType,
    int x,
    int y,
    int width,
    int height,
    String text,
    String componentHint,
    List<String> styleHints,
    double confidence
) {
}

record AiVisualRegionResponse(
    String regionId,
    String assetId,
    String regionType,
    int x,
    int y,
    int width,
    int height,
    String text,
    String componentHint,
    List<String> styleHints,
    double confidence
) {
}

record AiVisualRegionListResponse(
    List<AiVisualRegionResponse> items
) {
}

record AiVisualOcrStatus(
    String assetId,
    String ocrStatus,
    boolean visionAnalysisAllowed,
    List<String> warnings
) {
}
