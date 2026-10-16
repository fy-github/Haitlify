package com.haitlify.aitest.platform.caselibrary;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("interface_cases")
class InterfaceCaseRecord {

    @Id
    @Column("id")
    private Long id;
    @Column("project_id")
    private Long projectId;
    @Column("library_id")
    private String libraryId;
    @Column("case_id")
    private String caseId;
    @Column("module_name")
    private String moduleName;
    @Column("case_type")
    private String caseType;
    @Column("title")
    private String title;
    @Column("source_file")
    private String sourceFile;
    @Column("source_operation_id")
    private String sourceOperationId;
    @Column("run_order")
    private int runOrder;
    @Column("row_json")
    private String rowJson;
    @Column("metadata_json")
    private String metadataJson;
    @Column("saved_at")
    private String savedAt;
    @Column("created_at")
    private Instant createdAt;
    @Column("updated_at")
    private Instant updatedAt;

    InterfaceCaseRecord() {
    }

    InterfaceCaseRecord(
        Long id,
        Long projectId,
        String libraryId,
        String caseId,
        String moduleName,
        String caseType,
        String title,
        String sourceFile,
        String sourceOperationId,
        int runOrder,
        String rowJson,
        String metadataJson,
        String savedAt,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.id = id;
        this.projectId = projectId;
        this.libraryId = libraryId;
        this.caseId = caseId;
        this.moduleName = moduleName;
        this.caseType = caseType;
        this.title = title;
        this.sourceFile = sourceFile;
        this.sourceOperationId = sourceOperationId;
        this.runOrder = runOrder;
        this.rowJson = rowJson;
        this.metadataJson = metadataJson;
        this.savedAt = savedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    Long getId() {
        return id;
    }

    Long getProjectId() {
        return projectId;
    }

    String getLibraryId() {
        return libraryId;
    }

    String getCaseId() {
        return caseId;
    }

    int getRunOrder() {
        return runOrder;
    }

    String getRowJson() {
        return rowJson;
    }

    String getMetadataJson() {
        return metadataJson;
    }

    String getSavedAt() {
        return savedAt;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
