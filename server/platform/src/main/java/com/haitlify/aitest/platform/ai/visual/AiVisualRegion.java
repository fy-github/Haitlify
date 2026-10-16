package com.haitlify.aitest.platform.ai.visual;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("ai_visual_regions")
class AiVisualRegion {

    @Id
    @Column("id")
    private Long id;
    @Column("region_id")
    private String regionId;
    @Column("asset_id")
    private Long assetId;
    @Column("region_type")
    private String regionType;
    @Column("x")
    private int x;
    @Column("y")
    private int y;
    @Column("width")
    private int width;
    @Column("height")
    private int height;
    @Column("text")
    private String text;
    @Column("component_hint")
    private String componentHint;
    @Column("style_hint_json")
    private String styleHintJson;
    @Column("confidence")
    private double confidence;
    @Column("created_at")
    private Instant createdAt;
    @Column("updated_at")
    private Instant updatedAt;

    AiVisualRegion() {
    }

    AiVisualRegion(
        Long id,
        String regionId,
        Long assetId,
        String regionType,
        int x,
        int y,
        int width,
        int height,
        String text,
        String componentHint,
        String styleHintJson,
        double confidence,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.id = id;
        this.regionId = regionId;
        this.assetId = assetId;
        this.regionType = regionType;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.text = text;
        this.componentHint = componentHint;
        this.styleHintJson = styleHintJson;
        this.confidence = confidence;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    String getRegionId() {
        return regionId;
    }

    Long getAssetId() {
        return assetId;
    }

    String getRegionType() {
        return regionType;
    }

    int getX() {
        return x;
    }

    int getY() {
        return y;
    }

    int getWidth() {
        return width;
    }

    int getHeight() {
        return height;
    }

    String getText() {
        return text;
    }

    String getComponentHint() {
        return componentHint;
    }

    String getStyleHintJson() {
        return styleHintJson;
    }

    double getConfidence() {
        return confidence;
    }
}
