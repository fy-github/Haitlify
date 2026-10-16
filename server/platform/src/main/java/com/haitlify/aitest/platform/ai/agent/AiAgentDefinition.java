package com.haitlify.aitest.platform.ai.agent;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("ai_agent_definitions")
class AiAgentDefinition {

    @Id
    @Column("id")
    private Long id;
    @Column("agent_id")
    private String agentId;
    @Column("project_id")
    private Long projectId;
    @Column("scope")
    private String scope;
    @Column("name_override")
    private String nameOverride;
    @Column("description_override")
    private String descriptionOverride;
    @Column("agent_role_override")
    private String agentRoleOverride;
    @Column("prompt_template_override")
    private String promptTemplateOverride;
    @Column("output_json_schema_override")
    private String outputJsonSchemaOverride;
    @Column("allowed_tools_override_json")
    private String allowedToolsOverrideJson;
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

    AiAgentDefinition() {
    }

    AiAgentDefinition(
        Long id,
        String agentId,
        Long projectId,
        String scope,
        String nameOverride,
        String descriptionOverride,
        String agentRoleOverride,
        String promptTemplateOverride,
        String outputJsonSchemaOverride,
        String allowedToolsOverrideJson,
        Boolean enabledOverride,
        int version,
        String overriddenFieldsJson,
        String actorId,
        String actorName,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.id = id;
        this.agentId = agentId;
        this.projectId = projectId;
        this.scope = scope;
        this.nameOverride = nameOverride;
        this.descriptionOverride = descriptionOverride;
        this.agentRoleOverride = agentRoleOverride;
        this.promptTemplateOverride = promptTemplateOverride;
        this.outputJsonSchemaOverride = outputJsonSchemaOverride;
        this.allowedToolsOverrideJson = allowedToolsOverrideJson;
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

    String getAgentId() {
        return agentId;
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

    String getAgentRoleOverride() {
        return agentRoleOverride;
    }

    String getPromptTemplateOverride() {
        return promptTemplateOverride;
    }

    String getOutputJsonSchemaOverride() {
        return outputJsonSchemaOverride;
    }

    String getAllowedToolsOverrideJson() {
        return allowedToolsOverrideJson;
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
