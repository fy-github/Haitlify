package com.haitlify.aitest.platform.ai.document;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("ai_source_chunks")
class AiSourceChunk {

    @Id
    @Column("id")
    private Long id;
    @Column("chunk_id")
    private String chunkId;
    @Column("document_id")
    private Long documentId;
    @Column("section_heading")
    private String sectionHeading;
    @Column("page_number")
    private Integer pageNumber;
    @Column("line_start")
    private Integer lineStart;
    @Column("line_end")
    private Integer lineEnd;
    @Column("table_identifier")
    private String tableIdentifier;
    @Column("image_identifier")
    private String imageIdentifier;
    @Column("figma_node_id")
    private String figmaNodeId;
    @Column("chunk_type")
    private String chunkType;
    @Column("content_hash")
    private String contentHash;
    @Column("content_text")
    private String contentText;
    @Column("evidence_metadata_json")
    private String evidenceMetadataJson;
    @Column("created_at")
    private Instant createdAt;
    @Column("updated_at")
    private Instant updatedAt;

    AiSourceChunk() {
    }

    AiSourceChunk(
        Long id,
        String chunkId,
        Long documentId,
        String sectionHeading,
        Integer pageNumber,
        Integer lineStart,
        Integer lineEnd,
        String tableIdentifier,
        String imageIdentifier,
        String figmaNodeId,
        String chunkType,
        String contentHash,
        String contentText,
        String evidenceMetadataJson,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.id = id;
        this.chunkId = chunkId;
        this.documentId = documentId;
        this.sectionHeading = sectionHeading;
        this.pageNumber = pageNumber;
        this.lineStart = lineStart;
        this.lineEnd = lineEnd;
        this.tableIdentifier = tableIdentifier;
        this.imageIdentifier = imageIdentifier;
        this.figmaNodeId = figmaNodeId;
        this.chunkType = chunkType;
        this.contentHash = contentHash;
        this.contentText = contentText;
        this.evidenceMetadataJson = evidenceMetadataJson;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    String getChunkId() {
        return chunkId;
    }

    Long getDocumentId() {
        return documentId;
    }

    String getSectionHeading() {
        return sectionHeading;
    }

    Integer getLineStart() {
        return lineStart;
    }

    Integer getLineEnd() {
        return lineEnd;
    }

    String getChunkType() {
        return chunkType;
    }

    String getContentHash() {
        return contentHash;
    }

    String getContentText() {
        return contentText;
    }

    String getEvidenceMetadataJson() {
        return evidenceMetadataJson;
    }
}
