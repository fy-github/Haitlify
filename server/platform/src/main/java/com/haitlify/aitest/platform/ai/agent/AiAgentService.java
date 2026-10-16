package com.haitlify.aitest.platform.ai.agent;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional
class AiAgentService {

    private static final String SYSTEM_SCOPE = "system";

    private final AiAgentRegistry registry = new AiAgentRegistry();
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    AiAgentService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    List<AiAgentResponse> listAgents() {
        List<AiAgentResponse> responses = new java.util.ArrayList<>(registry.all().stream()
            .map(this::toEffectiveResponse)
            .toList());
        customAgents().stream().map(this::toCustomResponse).forEach(responses::add);
        return responses;
    }

    AiAgentResponse getAgent(String agentId) {
        return findBuiltInOptional(agentId)
            .map(this::toEffectiveResponse)
            .or(() -> findCustomAgent(agentId).map(this::toCustomResponse))
            .orElseThrow(() -> new IllegalArgumentException("Unknown AI agent: " + agentId));
    }

    AiAgentResponse saveOverride(String agentId, SaveAiAgentOverrideRequest request) {
        BuiltInAgentDefinition definition = registry.find(agentId);
        Optional<AiAgentDefinition> current = findOverride(agentId);
        Instant now = Instant.now();
        Set<String> overriddenFields = new LinkedHashSet<>();
        if (request.name() != null) overriddenFields.add("name");
        if (request.description() != null) overriddenFields.add("description");
        if (request.agentRole() != null) overriddenFields.add("agentRole");
        if (request.promptTemplate() != null) overriddenFields.add("promptTemplate");
        if (request.outputJsonSchema() != null) overriddenFields.add("outputJsonSchema");
        if (request.allowedTools() != null) overriddenFields.add("allowedTools");
        if (request.enabled() != null) overriddenFields.add("enabled");

        if (current.isPresent()) {
            jdbcTemplate.update(
                """
                update ai_agent_definitions
                set name_override = ?, description_override = ?, agent_role_override = ?,
                    prompt_template_override = ?, output_json_schema_override = ?,
                    allowed_tools_override_json = ?, enabled_override = ?, version = ?,
                    overridden_fields_json = ?, actor_id = ?, actor_name = ?, updated_at = ?
                where id = ?
                """,
                request.name(),
                request.description(),
                request.agentRole(),
                request.promptTemplate(),
                stringify(request.outputJsonSchema()),
                stringify(request.allowedTools()),
                request.enabled(),
                current.get().getVersion() + 1,
                stringify(objectMapper.valueToTree(overriddenFields)),
                com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystem(),
                com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystemName(),
                now,
                current.get().getId()
            );
        } else {
            jdbcTemplate.update(
                """
                insert into ai_agent_definitions(
                  agent_id, project_id, scope, name_override, description_override, agent_role_override,
                  prompt_template_override, output_json_schema_override, allowed_tools_override_json,
                  enabled_override, version, overridden_fields_json, actor_id, actor_name, created_at, updated_at
                ) values (?, null, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                agentId,
                SYSTEM_SCOPE,
                request.name(),
                request.description(),
                request.agentRole(),
                request.promptTemplate(),
                stringify(request.outputJsonSchema()),
                stringify(request.allowedTools()),
                request.enabled(),
                definition.version() + 1,
                stringify(objectMapper.valueToTree(overriddenFields)),
                com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystem(),
                com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystemName(),
                now,
                now
            );
        }

        return getAgent(agentId);
    }

    void deleteOverride(String agentId) {
        registry.find(agentId);
        jdbcTemplate.update(
            "delete from ai_agent_definitions where agent_id = ? and project_id is null and scope = ?",
            agentId,
            SYSTEM_SCOPE
        );
    }

    AiAgentResponse createCustomAgent(SaveCustomAiAgentRequest request) {
        if (request.agentId() == null || request.agentId().isBlank()) {
            throw new IllegalArgumentException("AI agentId is required");
        }
        String agentId = request.agentId().trim();
        if (findBuiltInOptional(agentId).isPresent() || findCustomAgent(agentId).isPresent()) {
            throw new IllegalArgumentException("AI agent already exists: " + agentId);
        }
        Instant now = Instant.now();
        jdbcTemplate.update(
            """
            insert into ai_agent_definitions(
              agent_id, project_id, scope, name_override, description_override, agent_role_override,
              prompt_template_override, output_json_schema_override, allowed_tools_override_json,
              enabled_override, version, overridden_fields_json, actor_id, actor_name, created_at, updated_at
            ) values (?, null, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            agentId,
            SYSTEM_SCOPE,
            textOrDefault(request.name(), agentId),
            textOrDefault(request.description(), ""),
            textOrDefault(request.agentRole(), "custom"),
            textOrDefault(request.promptTemplate(), ""),
            stringify(request.outputJsonSchema() == null ? objectMapper.createObjectNode() : request.outputJsonSchema()),
            stringify(request.allowedTools() == null ? objectMapper.createObjectNode() : request.allowedTools()),
            request.enabled() == null || request.enabled(),
            1,
            stringify(objectMapper.valueToTree(List.of("name", "description", "agentRole", "promptTemplate", "outputJsonSchema", "allowedTools", "enabled"))),
            com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystem(),
            com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystemName(),
            now,
            now
        );
        return getAgent(agentId);
    }

    private AiAgentResponse toEffectiveResponse(BuiltInAgentDefinition definition) {
        AiAgentDefinition override = findOverride(definition.agentId()).orElse(null);
        return new AiAgentResponse(
            definition.agentId(),
            override != null && override.getNameOverride() != null ? override.getNameOverride() : definition.name(),
            override != null && override.getDescriptionOverride() != null ? override.getDescriptionOverride() : definition.description(),
            override != null && override.getAgentRoleOverride() != null ? override.getAgentRoleOverride() : definition.agentRole(),
            override != null && override.getPromptTemplateOverride() != null ? override.getPromptTemplateOverride() : definition.promptTemplate(),
            override != null && override.getOutputJsonSchemaOverride() != null ? parse(override.getOutputJsonSchemaOverride()) : definition.outputJsonSchema(),
            override != null && override.getAllowedToolsOverrideJson() != null ? parse(override.getAllowedToolsOverrideJson()) : definition.allowedTools(),
            override != null && override.getEnabledOverride() != null ? override.getEnabledOverride() : definition.enabled(),
            definition.builtIn(),
            override != null,
            override != null ? override.getVersion() : definition.version(),
            override != null ? override.getUpdatedAt().toString() : null
        );
    }

    private AiAgentResponse toCustomResponse(AiAgentDefinition definition) {
        return new AiAgentResponse(
            definition.getAgentId(),
            textOrDefault(definition.getNameOverride(), definition.getAgentId()),
            textOrDefault(definition.getDescriptionOverride(), ""),
            textOrDefault(definition.getAgentRoleOverride(), "custom"),
            textOrDefault(definition.getPromptTemplateOverride(), ""),
            definition.getOutputJsonSchemaOverride() != null ? parse(definition.getOutputJsonSchemaOverride()) : objectMapper.createObjectNode(),
            definition.getAllowedToolsOverrideJson() != null ? parse(definition.getAllowedToolsOverrideJson()) : objectMapper.createObjectNode(),
            definition.getEnabledOverride() == null || definition.getEnabledOverride(),
            false,
            false,
            definition.getVersion(),
            definition.getUpdatedAt() == null ? null : definition.getUpdatedAt().toString()
        );
    }

    private Optional<BuiltInAgentDefinition> findBuiltInOptional(String agentId) {
        return registry.all().stream()
            .filter(agent -> agent.agentId().equals(agentId))
            .findFirst();
    }

    private List<AiAgentDefinition> customAgents() {
        Set<String> builtInIds = registry.all().stream()
            .map(BuiltInAgentDefinition::agentId)
            .collect(java.util.stream.Collectors.toSet());
        return jdbcTemplate.query(
            """
            select * from ai_agent_definitions
            where project_id is null and scope = ?
            order by id asc
            """,
            (rs, rowNum) -> mapDefinition(rs),
            SYSTEM_SCOPE
        ).stream()
            .filter(definition -> !builtInIds.contains(definition.getAgentId()))
            .toList();
    }

    private Optional<AiAgentDefinition> findCustomAgent(String agentId) {
        return customAgents().stream()
            .filter(definition -> definition.getAgentId().equals(agentId))
            .findFirst();
    }

    private Optional<AiAgentDefinition> findOverride(String agentId) {
        return jdbcTemplate.query(
            """
            select * from ai_agent_definitions
            where agent_id = ? and project_id is null and scope = ?
            order by id desc
            limit 1
            """,
            (rs, rowNum) -> mapDefinition(rs),
            agentId,
            SYSTEM_SCOPE
        ).stream().findFirst();
    }

    private AiAgentDefinition mapDefinition(ResultSet resultSet) throws SQLException {
        return new AiAgentDefinition(
            resultSet.getLong("id"),
            resultSet.getString("agent_id"),
            resultSet.getObject("project_id", Long.class),
            resultSet.getString("scope"),
            resultSet.getString("name_override"),
            resultSet.getString("description_override"),
            resultSet.getString("agent_role_override"),
            resultSet.getString("prompt_template_override"),
            resultSet.getString("output_json_schema_override"),
            resultSet.getString("allowed_tools_override_json"),
            resultSet.getObject("enabled_override", Boolean.class),
            resultSet.getInt("version"),
            resultSet.getString("overridden_fields_json"),
            resultSet.getString("actor_id"),
            resultSet.getString("actor_name"),
            resultSet.getTimestamp("created_at").toInstant(),
            resultSet.getTimestamp("updated_at").toInstant()
        );
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid AI agent JSON", exception);
        }
    }

    private String stringify(JsonNode node) {
        if (node == null) return null;
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid AI agent payload", exception);
        }
    }

    private String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}

record SaveCustomAiAgentRequest(
    String agentId,
    String name,
    String description,
    String agentRole,
    String promptTemplate,
    JsonNode outputJsonSchema,
    JsonNode allowedTools,
    Boolean enabled
) {
}

record SaveAiAgentOverrideRequest(
    String name,
    String description,
    String agentRole,
    String promptTemplate,
    JsonNode outputJsonSchema,
    JsonNode allowedTools,
    Boolean enabled
) {
}

record AiAgentResponse(
    String agentId,
    String name,
    String description,
    String agentRole,
    String promptTemplate,
    JsonNode outputJsonSchema,
    JsonNode allowedTools,
    boolean enabled,
    boolean builtIn,
    boolean customized,
    int version,
    String updatedAt
) {
}
