package com.haitlify.aitest.platform.ai.document;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("ai_source_documents")
class AiSourceDocument {

    @Id
    @Column("id")
    private Long id;
    @Column("document_id")
    private String documentId;
    @Column("project_id")
    private Long projectId;
    @Column("file_name")
    private String fileName;
    @Column("document_type")
    private String documentType;
    @Column("content_hash")
    private String contentHash;
    @Column("storage_location")
    private String storageLocation;
    @Column("extracted_text")
    private String extractedText;
    @Column("parse_metadata_json")
    private String parseMetadataJson;
    @Column("parser_version")
    private String parserVersion;
    @Column("source_file_metadata_json")
    private String sourceFileMetadataJson;
    @Column("visual_asset_id")
    private Long visualAssetId;
    @Column("actor_id")
    private String actorId;
    @Column("actor_name")
    private String actorName;
    @Column("created_at")
    private Instant createdAt;
    @Column("updated_at")
    private Instant updatedAt;

    AiSourceDocument() {
    }

    AiSourceDocument(
        Long id,
        String documentId,
        Long projectId,
        String fileName,
        String documentType,
        String contentHash,
        String storageLocation,
        String extractedText,
        String parseMetadataJson,
        String parserVersion,
        String sourceFileMetadataJson,
        Long visualAssetId,
        String actorId,
        String actorName,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.id = id;
        this.documentId = documentId;
        this.projectId = projectId;
        this.fileName = fileName;
        this.documentType = documentType;
        this.contentHash = contentHash;
        this.storageLocation = storageLocation;
        this.extractedText = extractedText;
        this.parseMetadataJson = parseMetadataJson;
        this.parserVersion = parserVersion;
        this.sourceFileMetadataJson = sourceFileMetadataJson;
        this.visualAssetId = visualAssetId;
        this.actorId = actorId;
        this.actorName = actorName;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    Long getId() {
        return id;
    }

    String getDocumentId() {
        return documentId;
    }

    Long getProjectId() {
        return projectId;
    }

    String getFileName() {
        return fileName;
    }

    String getDocumentType() {
        return documentType;
    }

    String getContentHash() {
        return contentHash;
    }

    String getStorageLocation() {
        return storageLocation;
    }

    String getExtractedText() {
        return extractedText;
    }

    String getParseMetadataJson() {
        return parseMetadataJson;
    }

    String getParserVersion() {
        return parserVersion;
    }

    String getSourceFileMetadataJson() {
        return sourceFileMetadataJson;
    }

    Long getVisualAssetId() {
        return visualAssetId;
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
