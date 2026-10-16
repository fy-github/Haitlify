package com.haitlify.aitest.platform.ai.skill;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("ai_skill_overrides")
class AiSkillOverride {

    @Id
    @Column("id")
    private Long id;
    @Column("base_skill_id")
    private String baseSkillId;
    @Column("project_id")
    private Long projectId;
    @Column("scope")
    private String scope;
    @Column("name_override")
    private String nameOverride;
    @Column("description_override")
    private String descriptionOverride;
    @Column("tags_override_json")
    private String tagsOverrideJson;
    @Column("applicable_case_kinds_override_json")
    private String applicableCaseKindsOverrideJson;
    @Column("applicable_file_types_override_json")
    private String applicableFileTypesOverrideJson;
    @Column("prompt_template_override")
    private String promptTemplateOverride;
    @Column("output_json_schema_override")
    private String outputJsonSchemaOverride;
    @Column("available_tools_override_json")
    private String availableToolsOverrideJson;
    @Column("examples_override_json")
    private String examplesOverrideJson;
    @Column("enabled_override")
    private Boolean enabledOverride;
    @Column("version")
    private int version;
    @Column("overridden_fields_json")
    private String overriddenFieldsJson;
    @Column("actor_id")
    private String actorId;
    @Column("actor_name")
    private String actorName;
    @Column("created_at")
    private Instant createdAt;
    @Column("updated_at")
    private Instant updatedAt;

    AiSkillOverride() {
    }

    AiSkillOverride(
        Long id,
        String baseSkillId,
        Long projectId,
        String scope,
        String nameOverride,
        String descriptionOverride,
        String tagsOverrideJson,
        String applicableCaseKindsOverrideJson,
        String applicableFileTypesOverrideJson,
        String promptTemplateOverride,
        String outputJsonSchemaOverride,
        String availableToolsOverrideJson,
        String examplesOverrideJson,
        Boolean enabledOverride,
        int version,
        String overriddenFieldsJson,
        String actorId,
        String actorName,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.id = id;
        this.baseSkillId = baseSkillId;
        this.projectId = projectId;
        this.scope = scope;
        this.nameOverride = nameOverride;
        this.descriptionOverride = descriptionOverride;
        this.tagsOverrideJson = tagsOverrideJson;
        this.applicableCaseKindsOverrideJson = applicableCaseKindsOverrideJson;
        this.applicableFileTypesOverrideJson = applicableFileTypesOverrideJson;
        this.promptTemplateOverride = promptTemplateOverride;
        this.outputJsonSchemaOverride = outputJsonSchemaOverride;
        this.availableToolsOverrideJson = availableToolsOverrideJson;
        this.examplesOverrideJson = examplesOverrideJson;
        this.enabledOverride = enabledOverride;
        this.version = version;
        this.overriddenFieldsJson = overriddenFieldsJson;
        this.actorId = actorId;
        this.actorName = actorName;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    Long getId() {
        return id;
    }

    String getBaseSkillId() {
        return baseSkillId;
    }

    Long getProjectId() {
        return projectId;
    }

    String getScope() {
        return scope;
    }

    String getNameOverride() {
        return nameOverride;
    }

    String getDescriptionOverride() {
        return descriptionOverride;
    }

    String getTagsOverrideJson() {
        return tagsOverrideJson;
    }

    String getApplicableCaseKindsOverrideJson() {
        return applicableCaseKindsOverrideJson;
    }

    String getApplicableFileTypesOverrideJson() {
        return applicableFileTypesOverrideJson;
    }

    String getPromptTemplateOverride() {
        return promptTemplateOverride;
    }

    String getOutputJsonSchemaOverride() {
        return outputJsonSchemaOverride;
    }

    String getAvailableToolsOverrideJson() {
        return availableToolsOverrideJson;
    }

    String getExamplesOverrideJson() {
        return examplesOverrideJson;
    }

    Boolean getEnabledOverride() {
        return enabledOverride;
    }

    int getVersion() {
        return version;
    }

    String getOverriddenFieldsJson() {
        return overriddenFieldsJson;
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
