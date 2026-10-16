package com.haitlify.aitest.platform.ai.rule;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("ai_rules")
class AiRule {

    @Id
    @Column("id")
    private Long id;
    @Column("project_id")
    private Long projectId;
    @Column("rule_scope")
    private String ruleScope;
    @Column("version")
    private int version;
    @Column("forced_human_review")
    private boolean forcedHumanReview;
    @Column("source_evidence_required")
    private boolean sourceEvidenceRequired;
    @Column("inferred_requires_confirmation")
    private boolean inferredRequiresConfirmation;
    @Column("allowed_case_kinds_json")
    private String allowedCaseKindsJson;
    @Column("formal_generation_statuses_json")
    private String formalGenerationStatusesJson;
    @Column("forbidden_patterns_json")
    private String forbiddenPatternsJson;
    @Column("coverage_dimensions_json")
    private String coverageDimensionsJson;
    @Column("rules_json")
    private String rulesJson;
    @Column("actor_id")
    private String actorId;
    @Column("actor_name")
    private String actorName;
    @Column("created_at")
    private Instant createdAt;
    @Column("updated_at")
    private Instant updatedAt;

    AiRule() {
    }

    AiRule(
        Long id,
        Long projectId,
        String ruleScope,
        int version,
        boolean forcedHumanReview,
        boolean sourceEvidenceRequired,
        boolean inferredRequiresConfirmation,
        String allowedCaseKindsJson,
        String formalGenerationStatusesJson,
        String forbiddenPatternsJson,
        String coverageDimensionsJson,
        String rulesJson,
        String actorId,
        String actorName,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.id = id;
        this.projectId = projectId;
        this.ruleScope = ruleScope;
        this.version = version;
        this.forcedHumanReview = forcedHumanReview;
        this.sourceEvidenceRequired = sourceEvidenceRequired;
        this.inferredRequiresConfirmation = inferredRequiresConfirmation;
        this.allowedCaseKindsJson = allowedCaseKindsJson;
        this.formalGenerationStatusesJson = formalGenerationStatusesJson;
        this.forbiddenPatternsJson = forbiddenPatternsJson;
        this.coverageDimensionsJson = coverageDimensionsJson;
        this.rulesJson = rulesJson;
        this.actorId = actorId;
        this.actorName = actorName;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    Long getId() {
        return id;
    }

    Long getProjectId() {
        return projectId;
    }

    String getRuleScope() {
        return ruleScope;
    }

    int getVersion() {
        return version;
    }

    boolean isForcedHumanReview() {
        return forcedHumanReview;
    }

    boolean isSourceEvidenceRequired() {
        return sourceEvidenceRequired;
    }

    boolean isInferredRequiresConfirmation() {
        return inferredRequiresConfirmation;
    }

    String getAllowedCaseKindsJson() {
        return allowedCaseKindsJson;
    }

    String getFormalGenerationStatusesJson() {
        return formalGenerationStatusesJson;
    }

    String getForbiddenPatternsJson() {
        return forbiddenPatternsJson;
    }

    String getCoverageDimensionsJson() {
        return coverageDimensionsJson;
    }

    String getRulesJson() {
        return rulesJson;
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
