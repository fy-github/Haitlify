package com.haitlify.aitest.platform.ai.figma;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("ai_figma_files")
class AiFigmaFile {

    @Id
    @Column("id")
    private Long id;
    @Column("figma_file_id")
    private String figmaFileId;
    @Column("project_id")
    private Long projectId;
    @Column("file_key")
    private String fileKey;
    @Column("selected_node_id")
    private String selectedNodeId;
    @Column("file_name")
    private String fileName;
    @Column("snapshot_hash")
    private String snapshotHash;
    @Column("raw_summary_json")
    private String rawSummaryJson;
    @Column("actor_id")
    private String actorId;
    @Column("actor_name")
    private String actorName;
    @Column("created_at")
    private Instant createdAt;
    @Column("updated_at")
    private Instant updatedAt;

    AiFigmaFile() {
    }

    AiFigmaFile(
        Long id,
        String figmaFileId,
        Long projectId,
        String fileKey,
        String selectedNodeId,
        String fileName,
        String snapshotHash,
        String rawSummaryJson,
        String actorId,
        String actorName,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.id = id;
        this.figmaFileId = figmaFileId;
        this.projectId = projectId;
        this.fileKey = fileKey;
        this.selectedNodeId = selectedNodeId;
        this.fileName = fileName;
        this.snapshotHash = snapshotHash;
        this.rawSummaryJson = rawSummaryJson;
        this.actorId = actorId;
        this.actorName = actorName;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    Long getId() {
        return id;
    }

    String getFigmaFileId() {
        return figmaFileId;
    }

    Long getProjectId() {
        return projectId;
    }

    String getFileKey() {
        return fileKey;
    }

    String getSelectedNodeId() {
        return selectedNodeId;
    }

    String getFileName() {
        return fileName;
    }

    String getSnapshotHash() {
        return snapshotHash;
    }

    String getRawSummaryJson() {
        return rawSummaryJson;
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
