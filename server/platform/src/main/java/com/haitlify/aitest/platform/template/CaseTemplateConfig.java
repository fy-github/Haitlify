package com.haitlify.aitest.platform.template;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("case_template_configs")
class CaseTemplateConfig {

    @Id
    @Column("id")
    private Long id;
    @Column("project_id")
    private Long projectId;
    @Column("kind")
    private String kind;
    @Column("schema_version")
    private int schemaVersion;
    @Column("interface_fields_json")
    private String interfaceFieldsJson;
    @Column("case_types_json")
    private String caseTypesJson;
    @Column("created_at")
    private Instant createdAt;
    @Column("updated_at")
    private Instant updatedAt;

    CaseTemplateConfig() {
    }

    CaseTemplateConfig(
        Long id,
        Long projectId,
        String kind,
        int schemaVersion,
        String interfaceFieldsJson,
        String caseTypesJson,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.id = id;
        this.projectId = projectId;
        this.kind = kind;
        this.schemaVersion = schemaVersion;
        this.interfaceFieldsJson = interfaceFieldsJson;
        this.caseTypesJson = caseTypesJson;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    Long getId() {
        return id;
    }

    Long getProjectId() {
        return projectId;
    }

    String getKind() {
        return kind;
    }

    int getSchemaVersion() {
        return schemaVersion;
    }

    String getInterfaceFieldsJson() {
        return interfaceFieldsJson;
    }

    String getCaseTypesJson() {
        return caseTypesJson;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
