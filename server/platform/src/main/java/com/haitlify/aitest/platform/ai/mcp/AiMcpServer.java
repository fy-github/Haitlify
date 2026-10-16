package com.haitlify.aitest.platform.ai.mcp;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("ai_mcp_servers")
class AiMcpServer {

    @Id
    @Column("id")
    private Long id;
    @Column("server_id")
    private String serverId;
    @Column("project_id")
    private Long projectId;
    @Column("name")
    private String name;
    @Column("server_type")
    private String serverType;
    @Column("endpoint")
    private String endpoint;
    @Column("auth_mode")
    private String authMode;
    @Column("auth_secret_ref")
    private String authSecretRef;
    @Column("auth_secret_ciphertext")
    private String authSecretCiphertext;
    @Column("available_tools_json")
    private String availableToolsJson;
    @Column("tool_allowlist_json")
    private String toolAllowlistJson;
    @Column("project_scope_json")
    private String projectScopeJson;
    @Column("write_operations_allowed")
    private boolean writeOperationsAllowed;
    @Column("timeout_ms")
    private int timeoutMs;
    @Column("enabled")
    private boolean enabled;
    @Column("audit_enabled")
    private boolean auditEnabled;
    @Column("sandbox_config_json")
    private String sandboxConfigJson;
    @Column("actor_id")
    private String actorId;
    @Column("actor_name")
    private String actorName;
    @Column("created_at")
    private Instant createdAt;
    @Column("updated_at")
    private Instant updatedAt;

    AiMcpServer() {
    }

    AiMcpServer(
        Long id,
        String serverId,
        Long projectId,
        String name,
        String serverType,
        String endpoint,
        String authMode,
        String authSecretRef,
        String authSecretCiphertext,
        String availableToolsJson,
        String toolAllowlistJson,
        String projectScopeJson,
        boolean writeOperationsAllowed,
        int timeoutMs,
        boolean enabled,
        boolean auditEnabled,
        String sandboxConfigJson,
        String actorId,
        String actorName,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.id = id;
        this.serverId = serverId;
        this.projectId = projectId;
        this.name = name;
        this.serverType = serverType;
        this.endpoint = endpoint;
        this.authMode = authMode;
        this.authSecretRef = authSecretRef;
        this.authSecretCiphertext = authSecretCiphertext;
        this.availableToolsJson = availableToolsJson;
        this.toolAllowlistJson = toolAllowlistJson;
        this.projectScopeJson = projectScopeJson;
        this.writeOperationsAllowed = writeOperationsAllowed;
        this.timeoutMs = timeoutMs;
        this.enabled = enabled;
        this.auditEnabled = auditEnabled;
        this.sandboxConfigJson = sandboxConfigJson;
        this.actorId = actorId;
        this.actorName = actorName;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    Long getId() {
        return id;
    }

    String getServerId() {
        return serverId;
    }

    Long getProjectId() {
        return projectId;
    }

    String getName() {
        return name;
    }

    String getServerType() {
        return serverType;
    }

    String getEndpoint() {
        return endpoint;
    }

    String getAuthMode() {
        return authMode;
    }

    String getAuthSecretRef() {
        return authSecretRef;
    }

    String getAuthSecretCiphertext() {
        return authSecretCiphertext;
    }

    String getAvailableToolsJson() {
        return availableToolsJson;
    }

    String getToolAllowlistJson() {
        return toolAllowlistJson;
    }

    String getProjectScopeJson() {
        return projectScopeJson;
    }

    boolean isWriteOperationsAllowed() {
        return writeOperationsAllowed;
    }

    int getTimeoutMs() {
        return timeoutMs;
    }

    boolean isEnabled() {
        return enabled;
    }

    boolean isAuditEnabled() {
        return auditEnabled;
    }

    String getSandboxConfigJson() {
        return sandboxConfigJson;
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
