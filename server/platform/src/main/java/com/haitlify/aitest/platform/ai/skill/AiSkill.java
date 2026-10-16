package com.haitlify.aitest.platform.ai.skill;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("ai_skills")
class AiSkill {

    @Id
    @Column("id")
    private Long id;
    @Column("skill_id")
    private String skillId;
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
    @Column("version")
    private int version;
    @Column("built_in")
    private boolean builtIn;
    @Column("skill_kind")
    private String skillKind;
    @Column("is_custom")
    private boolean custom;
    @Column("actor_id")
    private String actorId;
    @Column("actor_name")
    private String actorName;
    @Column("created_at")
    private Instant createdAt;
    @Column("updated_at")
    private Instant updatedAt;

    AiSkill() {
    }

    AiSkill(
        Long id,
        String skillId,
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
        int version,
        boolean builtIn,
        String skillKind,
        boolean custom,
        String actorId,
        String actorName,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.id = id;
        this.skillId = skillId;
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
        this.version = version;
        this.builtIn = builtIn;
        this.skillKind = skillKind;
        this.custom = custom;
        this.actorId = actorId;
        this.actorName = actorName;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    Long getId() {
        return id;
    }

    String getSkillId() {
        return skillId;
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

    int getVersion() {
        return version;
    }

    boolean isBuiltIn() {
        return builtIn;
    }

    String getSkillKind() {
        return skillKind;
    }

    boolean isCustom() {
        return custom;
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
