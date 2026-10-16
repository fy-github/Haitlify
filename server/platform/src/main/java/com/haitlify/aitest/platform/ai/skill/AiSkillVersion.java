package com.haitlify.aitest.platform.ai.skill;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("ai_skill_versions")
class AiSkillVersion {

    @Id
    @Column("id")
    private Long id;
    @Column("skill_id")
    private String skillId;
    @Column("version")
    private int version;
    @Column("name")
    private String name;
    @Column("description")
    private String description;
    @Column("tags_json")
    private String tagsJson;
    @Column("applicable_case_kinds_json")
    private String applicableCaseKindsJson;
    @Column("applicable_file_types_json")
    private String applicableFileTypesJson;
    @Column("prompt_template")
    private String promptTemplate;
    @Column("output_json_schema")
    private String outputJsonSchema;
    @Column("available_tools_json")
    private String availableToolsJson;
    @Column("examples_json")
    private String examplesJson;
    @Column("enabled")
    private boolean enabled;
    @Column("actor_id")
    private String actorId;
    @Column("actor_name")
    private String actorName;
    @Column("created_at")
    private Instant createdAt;

    AiSkillVersion() {
    }

    AiSkillVersion(
        Long id,
        String skillId,
        int version,
        String name,
        String description,
        String tagsJson,
        String applicableCaseKindsJson,
        String applicableFileTypesJson,
        String promptTemplate,
        String outputJsonSchema,
        String availableToolsJson,
        String examplesJson,
        boolean enabled,
        String actorId,
        String actorName,
        Instant createdAt
    ) {
        this.id = id;
        this.skillId = skillId;
        this.version = version;
        this.name = name;
        this.description = description;
        this.tagsJson = tagsJson;
        this.applicableCaseKindsJson = applicableCaseKindsJson;
        this.applicableFileTypesJson = applicableFileTypesJson;
        this.promptTemplate = promptTemplate;
        this.outputJsonSchema = outputJsonSchema;
        this.availableToolsJson = availableToolsJson;
        this.examplesJson = examplesJson;
        this.enabled = enabled;
        this.actorId = actorId;
        this.actorName = actorName;
        this.createdAt = createdAt;
    }

    String getSkillId() {
        return skillId;
    }

    int getVersion() {
        return version;
    }

    String getName() {
        return name;
    }

    String getDescription() {
        return description;
    }

    String getTagsJson() {
        return tagsJson;
    }

    String getApplicableCaseKindsJson() {
        return applicableCaseKindsJson;
    }

    String getApplicableFileTypesJson() {
        return applicableFileTypesJson;
    }

    String getPromptTemplate() {
        return promptTemplate;
    }

    String getOutputJsonSchema() {
        return outputJsonSchema;
    }

    String getAvailableToolsJson() {
        return availableToolsJson;
    }

    String getExamplesJson() {
        return examplesJson;
    }

    boolean isEnabled() {
        return enabled;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
