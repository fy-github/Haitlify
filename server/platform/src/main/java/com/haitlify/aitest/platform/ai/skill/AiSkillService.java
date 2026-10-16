package com.haitlify.aitest.platform.ai.skill;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.haitlify.aitest.platform.ai.tool.AiToolRegistry;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
@Transactional
class AiSkillService {

    private static final Long SYSTEM_PROJECT_ID = null;
    private static final String SYSTEM_SCOPE = "system";

    private final AiSkillRepository skillRepository;
    private final AiSkillVersionRepository versionRepository;
    private final AiToolRegistry toolRegistry;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    private final AiSkillOverrideRepository overrideRepository;
    private final ObjectMapper objectMapper;

    AiSkillService(
        AiSkillRepository skillRepository,
        AiSkillVersionRepository versionRepository,
        AiToolRegistry toolRegistry,
        org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
        AiSkillOverrideRepository overrideRepository,
        ObjectMapper objectMapper
    ) {
        this.skillRepository = skillRepository;
        this.versionRepository = versionRepository;
        this.toolRegistry = toolRegistry;
        this.jdbcTemplate = jdbcTemplate;
        this.overrideRepository = overrideRepository;
        this.objectMapper = objectMapper;
    }

    List<AiSkillResponse> listSkills() {
        List<AiSkillResponse> responses = new ArrayList<>(AiBuiltInSkills.all().stream()
            .map(this::toEffectiveResponse)
            .toList());
        skillRepository.findAll().stream()
            .filter(AiSkill::isCustom)
            .map(this::toCustomResponse)
            .forEach(responses::add);
        return responses;
    }

    AiSkillResponse getSkill(String skillId) {
        return findBuiltInOptional(skillId)
            .map(this::toEffectiveResponse)
            .or(() -> skillRepository.findBySkillId(skillId).filter(AiSkill::isCustom).map(this::toCustomResponse))
            .orElseThrow(() -> new IllegalArgumentException("Unknown AI skill: " + skillId));
    }

    AiSkillResponse saveOverride(String skillId, SaveAiSkillOverrideRequest request) {
        AiSkillDefinition definition = findBuiltIn(skillId);
        AiSkillOverride current = overrideRepository.findByBaseSkillIdAndProjectIdAndScope(skillId, SYSTEM_PROJECT_ID, SYSTEM_SCOPE)
            .orElse(null);
        Instant now = Instant.now();
        Set<String> overriddenFields = new LinkedHashSet<>();
        if (request.name() != null) overriddenFields.add("name");
        if (request.description() != null) overriddenFields.add("description");
        if (request.tags() != null) overriddenFields.add("tags");
        if (request.applicableCaseKinds() != null) overriddenFields.add("applicableCaseKinds");
        if (request.applicableFileTypes() != null) overriddenFields.add("applicableFileTypes");
        if (request.promptTemplate() != null) overriddenFields.add("promptTemplate");
        if (request.outputJsonSchema() != null) overriddenFields.add("outputJsonSchema");
        if (request.availableTools() != null) overriddenFields.add("availableTools");
        if (request.examples() != null) overriddenFields.add("examples");
        if (request.enabled() != null) overriddenFields.add("enabled");
        validatePublishFields(request.availableTools(), request.outputJsonSchema());

        AiSkillOverride next = new AiSkillOverride(
            current == null ? null : current.getId(),
            skillId,
            SYSTEM_PROJECT_ID,
            SYSTEM_SCOPE,
            request.name(),
            request.description(),
            stringify(request.tags()),
            stringify(request.applicableCaseKinds()),
            stringify(request.applicableFileTypes()),
            request.promptTemplate(),
            stringify(request.outputJsonSchema()),
            stringify(request.availableTools()),
            stringify(request.examples()),
            request.enabled(),
            current == null ? definition.version() + 1 : current.getVersion() + 1,
            stringify(objectMapper.valueToTree(overriddenFields)),
            com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystem(),
            com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystemName(),
            current == null ? now : current.getCreatedAt(),
            now
        );

        overrideRepository.save(next);
        return toEffectiveResponse(definition);
    }

    void deleteOverride(String skillId) {
        overrideRepository.findByBaseSkillIdAndProjectIdAndScope(skillId, SYSTEM_PROJECT_ID, SYSTEM_SCOPE)
            .ifPresent(overrideRepository::delete);
    }

    AiSkillResponse createCustomSkill(SaveCustomAiSkillRequest request) {
        if (request.skillId() == null || request.skillId().isBlank()) {
            throw new IllegalArgumentException("AI skillId is required");
        }
        String skillId = request.skillId().trim();
        if (findBuiltInOptional(skillId).isPresent() || skillRepository.findBySkillId(skillId).isPresent()) {
            throw new IllegalArgumentException("AI skill already exists: " + skillId);
        }
        validatePublishFields(request.availableTools(), request.outputJsonSchema());

        Instant now = Instant.now();
        AiSkill skill = new AiSkill(
            null,
            skillId,
            textOrDefault(request.name(), skillId),
            textOrDefault(request.description(), ""),
            stringify(defaultArray(request.tags())),
            stringify(defaultArray(request.applicableCaseKinds())),
            stringify(defaultArray(request.applicableFileTypes())),
            textOrDefault(request.promptTemplate(), ""),
            stringify(defaultObject(request.outputJsonSchema())),
            stringify(defaultArray(request.availableTools())),
            stringify(examplesWithSkillPath(request.examples(), request.skillPath())),
            request.enabled() == null || request.enabled(),
            1,
            false,
            textOrDefault(request.skillKind(), "analysis"),
            true,
            com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystem(),
            com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystemName(),
            now,
            now
        );
        AiSkill saved = skillRepository.save(skill);
        saveVersionSnapshot(saved);
        return toCustomResponse(saved);
    }

    AiSkillResponse updateCustomSkill(String skillId, SaveCustomAiSkillRequest request) {
        if (findBuiltInOptional(skillId).isPresent()) {
            throw new IllegalArgumentException("Built-in AI skills can only be customized through override endpoint: " + skillId);
        }
        AiSkill current = skillRepository.findBySkillId(skillId)
            .filter(AiSkill::isCustom)
            .orElseThrow(() -> new IllegalArgumentException("Unknown AI skill: " + skillId));
        validatePublishFields(request.availableTools(), request.outputJsonSchema());
        Instant now = Instant.now();
        AiSkill next = new AiSkill(
            current.getId(),
            current.getSkillId(),
            request.name() == null ? current.getName() : request.name(),
            request.description() == null ? current.getDescription() : request.description(),
            request.tags() == null ? current.getTagsJson() : stringify(request.tags()),
            request.applicableCaseKinds() == null ? current.getApplicableCaseKindsJson() : stringify(request.applicableCaseKinds()),
            request.applicableFileTypes() == null ? current.getApplicableFileTypesJson() : stringify(request.applicableFileTypes()),
            request.promptTemplate() == null ? current.getPromptTemplate() : request.promptTemplate(),
            request.outputJsonSchema() == null ? current.getOutputJsonSchema() : stringify(request.outputJsonSchema()),
            request.availableTools() == null ? current.getAvailableToolsJson() : stringify(request.availableTools()),
            request.examples() == null && request.skillPath() == null ? current.getExamplesJson() : stringify(examplesWithSkillPath(request.examples(), request.skillPath())),
            request.enabled() == null ? current.isEnabled() : request.enabled(),
            current.getVersion() + 1,
            false,
            request.skillKind() == null ? current.getSkillKind() : request.skillKind(),
            true,
            com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystem(),
            com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystemName(),
            current.getCreatedAt(),
            now
        );
        AiSkill saved = skillRepository.save(next);
        saveVersionSnapshot(saved);
        return toCustomResponse(saved);
    }

    AiSkillVersionListResponse listVersions(String skillId) {
        requireCustomSkill(skillId);
        return new AiSkillVersionListResponse(versionRepository.findBySkillIdOrderByVersionAsc(skillId).stream()
            .map(this::toVersionResponse)
            .toList());
    }

    AiSkillDiffResponse diffVersions(String skillId, int fromVersion, int toVersion) {
        requireCustomSkill(skillId);
        AiSkillVersion before = versionRepository.findBySkillIdAndVersion(skillId, fromVersion)
            .orElseThrow(() -> new IllegalArgumentException("Unknown AI skill version: " + fromVersion));
        AiSkillVersion after = versionRepository.findBySkillIdAndVersion(skillId, toVersion)
            .orElseThrow(() -> new IllegalArgumentException("Unknown AI skill version: " + toVersion));

        List<String> changedFields = new ArrayList<>();
        ObjectNode changes = objectMapper.createObjectNode();
        addChange(changedFields, changes, "promptTemplate", objectMapper.valueToTree(before.getPromptTemplate()), objectMapper.valueToTree(after.getPromptTemplate()));
        addChange(changedFields, changes, "outputJsonSchema", parse(before.getOutputJsonSchema()), parse(after.getOutputJsonSchema()));
        addChange(changedFields, changes, "availableTools", parse(before.getAvailableToolsJson()), parse(after.getAvailableToolsJson()));
        addChange(changedFields, changes, "applicableCaseKinds", parse(before.getApplicableCaseKindsJson()), parse(after.getApplicableCaseKindsJson()));
        return new AiSkillDiffResponse(skillId, fromVersion, toVersion, changedFields, changes);
    }

    private AiSkillDefinition findBuiltIn(String skillId) {
        return findBuiltInOptional(skillId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown AI skill: " + skillId));
    }

    private java.util.Optional<AiSkillDefinition> findBuiltInOptional(String skillId) {
        return AiBuiltInSkills.all().stream()
            .filter(skill -> skill.skillId().equals(skillId))
            .findFirst();
    }

    private AiSkillResponse toEffectiveResponse(AiSkillDefinition definition) {
        AiSkillOverride override = overrideRepository.findByBaseSkillIdAndProjectIdAndScope(definition.skillId(), SYSTEM_PROJECT_ID, SYSTEM_SCOPE)
            .orElse(null);
        return new AiSkillResponse(
            definition.skillId(),
            override != null && override.getNameOverride() != null ? override.getNameOverride() : definition.name(),
            override != null && override.getDescriptionOverride() != null ? override.getDescriptionOverride() : definition.description(),
            override != null && override.getTagsOverrideJson() != null ? parse(override.getTagsOverrideJson()) : definition.tags(),
            override != null && override.getApplicableCaseKindsOverrideJson() != null ? parse(override.getApplicableCaseKindsOverrideJson()) : definition.applicableCaseKinds(),
            override != null && override.getApplicableFileTypesOverrideJson() != null ? parse(override.getApplicableFileTypesOverrideJson()) : definition.applicableFileTypes(),
            override != null && override.getPromptTemplateOverride() != null ? override.getPromptTemplateOverride() : definition.promptTemplate(),
            override != null && override.getOutputJsonSchemaOverride() != null ? parse(override.getOutputJsonSchemaOverride()) : definition.outputJsonSchema(),
            override != null && override.getAvailableToolsOverrideJson() != null ? parse(override.getAvailableToolsOverrideJson()) : definition.availableTools(),
            override != null && override.getExamplesOverrideJson() != null ? parse(override.getExamplesOverrideJson()) : definition.examples(),
            skillPathFrom(override != null && override.getExamplesOverrideJson() != null ? parse(override.getExamplesOverrideJson()) : definition.examples()),
            override != null && override.getEnabledOverride() != null ? override.getEnabledOverride() : definition.enabled(),
            definition.builtIn(),
            override != null,
            override != null ? override.getVersion() : definition.version(),
            override != null ? override.getUpdatedAt().toString() : null
        );
    }

    private AiSkillResponse toCustomResponse(AiSkill skill) {
        return new AiSkillResponse(
            skill.getSkillId(),
            skill.getName(),
            skill.getDescription(),
            parseOrDefault(skill.getTagsJson(), objectMapper.createArrayNode()),
            parseOrDefault(skill.getApplicableCaseKindsJson(), objectMapper.createArrayNode()),
            parseOrDefault(skill.getApplicableFileTypesJson(), objectMapper.createArrayNode()),
            skill.getPromptTemplate(),
            parseOrDefault(skill.getOutputJsonSchema(), objectMapper.createObjectNode()),
            parseOrDefault(skill.getAvailableToolsJson(), objectMapper.createArrayNode()),
            parseOrDefault(skill.getExamplesJson(), objectMapper.createArrayNode()),
            skillPathFrom(parseOrDefault(skill.getExamplesJson(), objectMapper.createObjectNode())),
            skill.isEnabled(),
            false,
            false,
            skill.getVersion(),
            skill.getUpdatedAt() == null ? null : skill.getUpdatedAt().toString()
        );
    }

    private void saveVersionSnapshot(AiSkill skill) {
        versionRepository.save(new AiSkillVersion(
            null,
            skill.getSkillId(),
            skill.getVersion(),
            skill.getName(),
            skill.getDescription(),
            skill.getTagsJson(),
            skill.getApplicableCaseKindsJson(),
            skill.getApplicableFileTypesJson(),
            skill.getPromptTemplate(),
            skill.getOutputJsonSchema(),
            skill.getAvailableToolsJson(),
            skill.getExamplesJson(),
            skill.isEnabled(),
            com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystem(),
            com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystemName(),
            Instant.now()
        ));
    }

    private AiSkill requireCustomSkill(String skillId) {
        return skillRepository.findBySkillId(skillId)
            .filter(AiSkill::isCustom)
            .orElseThrow(() -> new IllegalArgumentException("Unknown AI skill: " + skillId));
    }

    /**
     * T09 bullet 5：Skill 发布校验——outputJsonSchema 形状、availableTools 必须是真实工具：
     * 内部注册表 id 或已启用且已允许的 MCP 引用（mcp:<serverId>:<toolName>）。
     * 禁用服务/未知工具一律拒绝——发布时就把住门。
     */
    private void validatePublishFields(JsonNode availableTools, JsonNode outputJsonSchema) {
        if (availableTools != null && !availableTools.isNull()) {
            if (!availableTools.isArray()) {
                throw new IllegalArgumentException("AI skill availableTools must be an array");
            }
            for (JsonNode item : availableTools) {
                String toolId = item.asText("");
                if (toolId.isBlank()) {
                    throw new IllegalArgumentException("AI skill availableTools contains a blank tool id");
                }
                if (toolId.startsWith("mcp:")) {
                    validateMcpToolReference(toolId);
                    continue;
                }
                if (toolRegistry.findTool(toolId).isEmpty()) {
                    throw new IllegalArgumentException("Unknown AI tool id: " + toolId);
                }
            }
        }
        if (outputJsonSchema != null && !outputJsonSchema.isNull() && outputJsonSchema.isObject()
                && outputJsonSchema.size() > 0) {
            if (!"object".equals(outputJsonSchema.path("type").asText(""))
                || !outputJsonSchema.path("properties").isObject()) {
                throw new IllegalArgumentException(
                    "AI skill outputJsonSchema must declare type=object with a properties object");
            }
        }
    }

    private void validateMcpToolReference(String reference) {
        String[] parts = reference.split(":", 3);
        if (parts.length != 3 || parts[1].isBlank() || parts[2].isBlank()) {
            throw new IllegalArgumentException(
                "Invalid MCP tool reference (expect mcp:<serverId>:<toolName>): " + reference);
        }
        var rows = jdbcTemplate.queryForList(
            "select enabled, tool_allowlist_json from ai_mcp_servers where server_id = ?", parts[1]);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Unknown MCP server in tool reference: " + parts[1]);
        }
        Object enabled = rows.get(0).get("enabled");
        boolean isEnabled = Boolean.TRUE.equals(enabled) || Integer.valueOf(1).equals(enabled);
        if (!isEnabled) {
            throw new IllegalArgumentException(
                "MCP server is disabled and cannot back a new skill capability: " + parts[1]);
        }
        String allowlist = rows.get(0).get("tool_allowlist_json") == null
            ? "" : rows.get(0).get("tool_allowlist_json").toString();
        if (!allowlist.contains("\"" + parts[2] + "\"")) {
            throw new IllegalArgumentException(
                "MCP tool is not allowlisted on server " + parts[1] + ": " + parts[2]);
        }
    }

    private AiSkillVersionResponse toVersionResponse(AiSkillVersion version) {
        return new AiSkillVersionResponse(
            version.getVersion(),
            version.getName(),
            version.getDescription(),
            parseOrDefault(version.getApplicableCaseKindsJson(), objectMapper.createArrayNode()),
            version.getPromptTemplate(),
            parseOrDefault(version.getOutputJsonSchema(), objectMapper.createObjectNode()),
            parseOrDefault(version.getAvailableToolsJson(), objectMapper.createArrayNode()),
            version.isEnabled(),
            version.getCreatedAt() == null ? null : version.getCreatedAt().toString()
        );
    }

    private void addChange(List<String> changedFields, ObjectNode changes, String field, JsonNode before, JsonNode after) {
        if (before.equals(after)) {
            return;
        }
        changedFields.add(field);
        ObjectNode change = changes.putObject(field);
        change.set("before", before);
        change.set("after", after);
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid AI skill JSON", exception);
        }
    }

    private JsonNode parseOrDefault(String json, JsonNode fallback) {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        return parse(json);
    }

    private String stringify(JsonNode node) {
        if (node == null) return null;
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid AI skill payload", exception);
        }
    }

    private JsonNode defaultArray(JsonNode node) {
        return node == null ? objectMapper.createArrayNode() : node;
    }

    private JsonNode defaultObject(JsonNode node) {
        return node == null ? objectMapper.createObjectNode() : node;
    }

    private JsonNode examplesWithSkillPath(JsonNode examples, String skillPath) {
        if (skillPath == null || skillPath.isBlank()) {
            return examples == null ? objectMapper.createArrayNode() : examples;
        }
        ObjectNode object = examples != null && examples.isObject()
            ? (ObjectNode) examples.deepCopy()
            : objectMapper.createObjectNode();
        if (examples == null || !examples.isObject()) {
            object.set("items", examples == null ? objectMapper.createArrayNode() : examples);
        }
        object.put("skillPath", skillPath.trim());
        return object;
    }

    private String skillPathFrom(JsonNode examples) {
        if (examples == null || !examples.isObject()) return "";
        return examples.path("skillPath").asText("");
    }

    private String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}

record SaveCustomAiSkillRequest(
    String skillId,
    String name,
    String description,
    JsonNode tags,
    JsonNode applicableCaseKinds,
    JsonNode applicableFileTypes,
    String promptTemplate,
    JsonNode outputJsonSchema,
    JsonNode availableTools,
    JsonNode examples,
    Boolean enabled,
    String skillKind,
    String skillPath
) {
}

record SaveAiSkillOverrideRequest(
    String name,
    String description,
    JsonNode tags,
    JsonNode applicableCaseKinds,
    JsonNode applicableFileTypes,
    String promptTemplate,
    JsonNode outputJsonSchema,
    JsonNode availableTools,
    JsonNode examples,
    Boolean enabled
) {
}

record AiSkillResponse(
    String skillId,
    String name,
    String description,
    JsonNode tags,
    JsonNode applicableCaseKinds,
    JsonNode applicableFileTypes,
    String promptTemplate,
    JsonNode outputJsonSchema,
    JsonNode availableTools,
    JsonNode examples,
    String skillPath,
    boolean enabled,
    boolean builtIn,
    boolean customized,
    int version,
    String updatedAt
) {
}

record AiSkillVersionListResponse(
    List<AiSkillVersionResponse> items
) {
}

record AiSkillVersionResponse(
    int version,
    String name,
    String description,
    JsonNode applicableCaseKinds,
    String promptTemplate,
    JsonNode outputJsonSchema,
    JsonNode availableTools,
    boolean enabled,
    String createdAt
) {
}

record AiSkillDiffResponse(
    String skillId,
    int fromVersion,
    int toVersion,
    List<String> changedFields,
    JsonNode changes
) {
}
