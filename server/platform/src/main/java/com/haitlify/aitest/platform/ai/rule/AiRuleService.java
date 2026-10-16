package com.haitlify.aitest.platform.ai.rule;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
@Transactional
public class AiRuleService {

    private static final Long SYSTEM_PROJECT_ID = null;
    private static final String SYSTEM_SCOPE = "system";
    private static final String PROJECT_SCOPE = "project";

    private final AiRuleRepository repository;
    private final ObjectMapper objectMapper;

    AiRuleService(AiRuleRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public EffectiveAiRule getRules(Long projectId) {
        return resolveEffectiveRules(projectId);
    }

    EffectiveAiRule saveRules(Long projectId, SaveAiRuleRequest request) {
        AiRule current = projectId == null ? findSystemRule().orElse(null) : findProjectRule(projectId).orElse(null);
        EffectiveAiRule systemRules = resolveSystemRules();
        Instant now = Instant.now();
        boolean systemScope = projectId == null;
        AiRule next = new AiRule(
            current == null ? null : current.getId(),
            projectId,
            systemScope ? SYSTEM_SCOPE : PROJECT_SCOPE,
            current == null ? (systemScope ? 2 : 1) : current.getVersion() + 1,
            true,
            true,
            request.inferredRequiresConfirmation() == null
                ? systemRules.inferredRequiresConfirmation()
                : request.inferredRequiresConfirmation(),
            stringify(request.allowedCaseKinds()),
            systemScope ? stringify(defaultFormalGenerationStatuses()) : stringify(request.formalGenerationStatuses()),
            stringify(request.forbiddenPatterns()),
            stringify(request.coverageDimensions()),
            stringify(request.rules()),
            com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystem(),
            com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystemName(),
            current == null ? now : current.getCreatedAt(),
            now
        );
        repository.save(next);
        return resolveEffectiveRules(projectId);
    }

    public EffectiveAiRule resolveEffectiveRules(Long projectId) {
        EffectiveAiRule system = resolveSystemRules();
        if (projectId == null) return system;

        return findProjectRule(projectId)
            .map(project -> mergeRules(projectId, system, project))
            .orElseGet(() -> new EffectiveAiRule(
                projectId,
                system.version(),
                system.systemVersion(),
                null,
                system.effectiveVersion(),
                system.forcedHumanReview(),
                system.sourceEvidenceRequired(),
                system.inferredRequiresConfirmation(),
                system.allowedCaseKinds(),
                system.formalGenerationStatuses(),
                system.forbiddenPatterns(),
                system.coverageDimensions(),
                system.rules(),
                system.updatedAt()
            ));
    }

    private java.util.Optional<AiRule> findSystemRule() {
        return repository.findAll().stream()
            .filter(rule -> rule.getProjectId() == null && SYSTEM_SCOPE.equals(rule.getRuleScope()))
            .findFirst();
    }

    private java.util.Optional<AiRule> findProjectRule(Long projectId) {
        return repository.findAll().stream()
            .filter(rule -> projectId.equals(rule.getProjectId()) && PROJECT_SCOPE.equals(rule.getRuleScope()))
            .findFirst();
    }

    private EffectiveAiRule resolveSystemRules() {
        return findSystemRule()
            .map(this::toSystemRules)
            .orElseGet(this::defaultResponse);
    }

    private EffectiveAiRule toSystemRules(AiRule rule) {
        return new EffectiveAiRule(
            null,
            rule.getVersion(),
            rule.getVersion(),
            null,
            "system-v" + rule.getVersion(),
            rule.isForcedHumanReview(),
            rule.isSourceEvidenceRequired(),
            rule.isInferredRequiresConfirmation(),
            parseArray(rule.getAllowedCaseKindsJson()),
            parseArray(rule.getFormalGenerationStatusesJson()),
            parseArray(rule.getForbiddenPatternsJson()),
            parseArray(rule.getCoverageDimensionsJson()),
            parse(rule.getRulesJson()),
            rule.getUpdatedAt() == null ? null : rule.getUpdatedAt().toString()
        );
    }

    private EffectiveAiRule defaultResponse() {
        return new EffectiveAiRule(
            null,
            1,
            1,
            null,
            "system-v1",
            true,
            true,
            true,
            objectMapper.createArrayNode(),
            defaultFormalGenerationStatuses(),
            objectMapper.createArrayNode(),
            objectMapper.createArrayNode(),
            objectMapper.createObjectNode(),
            null
        );
    }

    private EffectiveAiRule mergeRules(Long projectId, EffectiveAiRule system, AiRule project) {
        JsonNode projectAllowedCaseKinds = parseNullable(project.getAllowedCaseKindsJson());
        JsonNode projectFormalStatuses = parseNullable(project.getFormalGenerationStatusesJson());
        JsonNode projectForbiddenPatterns = parseNullable(project.getForbiddenPatternsJson());
        JsonNode projectCoverageDimensions = parseNullable(project.getCoverageDimensionsJson());
        JsonNode projectRules = parseNullable(project.getRulesJson());

        return new EffectiveAiRule(
            projectId,
            project.getVersion(),
            system.systemVersion(),
            project.getVersion(),
            "system-v" + system.systemVersion() + "+project-v" + project.getVersion(),
            system.forcedHumanReview() || project.isForcedHumanReview(),
            system.sourceEvidenceRequired() || project.isSourceEvidenceRequired(),
            project.isInferredRequiresConfirmation(),
            projectAllowedCaseKinds == null ? system.allowedCaseKinds() : projectAllowedCaseKinds,
            projectFormalStatuses == null ? system.formalGenerationStatuses() : projectFormalStatuses,
            projectForbiddenPatterns == null ? system.forbiddenPatterns() : projectForbiddenPatterns,
            projectCoverageDimensions == null ? system.coverageDimensions() : projectCoverageDimensions,
            mergeRuleObjects(system.rules(), projectRules),
            project.getUpdatedAt() == null ? system.updatedAt() : project.getUpdatedAt().toString()
        );
    }

    private JsonNode mergeRuleObjects(JsonNode systemRules, JsonNode projectRules) {
        if (projectRules == null) return systemRules;
        if (!systemRules.isObject() || !projectRules.isObject()) return projectRules;
        ObjectNode merged = ((ObjectNode) systemRules).deepCopy();
        projectRules.properties().forEach(entry -> merged.set(entry.getKey(), entry.getValue()));
        return merged;
    }

    private ArrayNode defaultFormalGenerationStatuses() {
        ArrayNode statuses = objectMapper.createArrayNode();
        statuses.add("confirmed");
        statuses.add("modified");
        return statuses;
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid AI rule JSON", exception);
        }
    }

    private JsonNode parseArray(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createArrayNode();
        }
        return parse(json);
    }

    private JsonNode parseNullable(String json) {
        if (json == null || json.isBlank()) return null;
        return parse(json);
    }

    private String stringify(JsonNode node) {
        if (node == null) return null;
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid AI rule payload", exception);
        }
    }
}

record SaveAiRuleRequest(
    Boolean forcedHumanReview,
    Boolean sourceEvidenceRequired,
    Boolean inferredRequiresConfirmation,
    JsonNode allowedCaseKinds,
    JsonNode formalGenerationStatuses,
    JsonNode forbiddenPatterns,
    JsonNode coverageDimensions,
    JsonNode rules
) {
}
