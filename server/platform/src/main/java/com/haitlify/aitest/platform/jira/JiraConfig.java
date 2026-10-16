package com.haitlify.aitest.platform.jira;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("jira_configs")
class JiraConfig {

    @Id
    @Column("id")
    private Long id;
    @Column("config_key")
    private String configKey;
    @Column("enabled")
    private boolean enabled;
    @Column("base_url")
    private String baseUrl;
    @Column("auth_mode")
    private String authMode;
    @Column("username")
    private String username;
    @Column("token")
    private String token;
    @Column("credential_configured")
    private boolean credentialConfigured;
    @Column("project_jql_json")
    private String projectJqlJson;
    @Column("actor_id")
    private String actorId;
    @Column("actor_name")
    private String actorName;
    @Column("created_at")
    private Instant createdAt;
    @Column("updated_at")
    private Instant updatedAt;

    JiraConfig() {
    }

    JiraConfig(
        Long id,
        String configKey,
        boolean enabled,
        String baseUrl,
        String authMode,
        String username,
        String token,
        boolean credentialConfigured,
        String projectJqlJson,
        String actorId,
        String actorName,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.id = id;
        this.configKey = configKey;
        this.enabled = enabled;
        this.baseUrl = baseUrl;
        this.authMode = authMode;
        this.username = username;
        this.token = token;
        this.credentialConfigured = credentialConfigured;
        this.projectJqlJson = projectJqlJson;
        this.actorId = actorId;
        this.actorName = actorName;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    Long getId() {
        return id;
    }

    String getConfigKey() {
        return configKey;
    }

    boolean isEnabled() {
        return enabled;
    }

    String getBaseUrl() {
        return baseUrl;
    }

    String getAuthMode() {
        return authMode;
    }

    String getUsername() {
        return username;
    }

    String getToken() {
        return token;
    }

    boolean isCredentialConfigured() {
        return credentialConfigured;
    }

    String getProjectJqlJson() {
        return projectJqlJson;
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
