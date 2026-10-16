package com.haitlify.aitest.platform.ai.visual;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("ai_visual_assets")
class AiVisualAsset {

    @Id
    @Column("id")
    private Long id;
    @Column("asset_id")
    private String assetId;
    @Column("project_id")
    private Long projectId;
    @Column("source_document_id")
    private Long sourceDocumentId;
    @Column("file_name")
    private String fileName;
    @Column("content_type")
    private String contentType;
    @Column("content_hash")
    private String contentHash;
    @Column("storage_location")
    private String storageLocation;
    @Column("byte_size")
    private long byteSize;
    @Column("width")
    private int width;
    @Column("height")
    private int height;
    @Column("content_base64")
    private String contentBase64;
    @Column("actor_id")
    private String actorId;
    @Column("actor_name")
    private String actorName;
    @Column("created_at")
    private Instant createdAt;
    @Column("updated_at")
    private Instant updatedAt;

    AiVisualAsset() {
    }

    AiVisualAsset(
        Long id,
        String assetId,
        Long projectId,
        Long sourceDocumentId,
        String fileName,
        String contentType,
        String contentHash,
        String storageLocation,
        long byteSize,
        int width,
        int height,
        String contentBase64,
        String actorId,
        String actorName,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.id = id;
        this.assetId = assetId;
        this.projectId = projectId;
        this.sourceDocumentId = sourceDocumentId;
        this.fileName = fileName;
        this.contentType = contentType;
        this.contentHash = contentHash;
        this.storageLocation = storageLocation;
        this.byteSize = byteSize;
        this.width = width;
        this.height = height;
        this.contentBase64 = contentBase64;
        this.actorId = actorId;
        this.actorName = actorName;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    Long getId() {
        return id;
    }

    String getAssetId() {
        return assetId;
    }

    Long getProjectId() {
        return projectId;
    }

    Long getSourceDocumentId() {
        return sourceDocumentId;
    }

    String getFileName() {
        return fileName;
    }

    String getContentType() {
        return contentType;
    }

    String getContentHash() {
        return contentHash;
    }

    String getStorageLocation() {
        return storageLocation;
    }

    long getByteSize() {
        return byteSize;
    }

    int getWidth() {
        return width;
    }

    int getHeight() {
        return height;
    }

    String getContentBase64() {
        return contentBase64;
    }

    String getActorId() {
        return actorId;
    }

    String getActorName() {
        return actorName;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
