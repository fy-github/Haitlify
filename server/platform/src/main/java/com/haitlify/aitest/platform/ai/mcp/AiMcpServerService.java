package com.haitlify.aitest.platform.ai.mcp;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.haitlify.aitest.platform.security.SecretCipher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional
class AiMcpServerService {

    private static final Long SYSTEM_PROJECT_ID = null;

    private final AiMcpServerRepository repository;
    private final ObjectMapper objectMapper;
    private final SecretCipher secretCipher;

    AiMcpServerService(AiMcpServerRepository repository, ObjectMapper objectMapper, SecretCipher secretCipher) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.secretCipher = secretCipher;
    }

    List<AiMcpServerResponse> listServers() {
        return repository.findAll().stream()
            .map(this::toResponse)
            .toList();
    }

    AiMcpServerResponse createServer(SaveAiMcpServerRequest request) {
        if (request.serverId() == null || request.serverId().isBlank()) {
            throw new IllegalArgumentException("MCP serverId is required");
        }
        return save(null, request.serverId().trim(), request);
    }

    AiMcpServerResponse updateServer(String serverId, SaveAiMcpServerRequest request) {
        AiMcpServer current = repository.findByServerId(serverId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown MCP server: " + serverId));
        return save(current, serverId, request);
    }

    void deleteServer(String serverId) {
        repository.findByServerId(serverId).ifPresent(repository::delete);
    }

    private AiMcpServerResponse save(AiMcpServer current, String serverId, SaveAiMcpServerRequest request) {
        Instant now = Instant.now();
        AiMcpServer next = new AiMcpServer(
            current == null ? null : current.getId(),
            serverId,
            SYSTEM_PROJECT_ID,
            blankToDefault(request.name(), current == null ? "" : current.getName()),
            blankToDefault(request.serverType(), current == null ? "" : current.getServerType()),
            blankToDefault(request.endpoint(), current == null ? "" : current.getEndpoint()),
            blankToDefault(request.authMode(), current == null ? "" : current.getAuthMode()),
            blankToDefault(request.authSecretRef(), current == null ? "" : current.getAuthSecretRef()),
            secretCipher.encrypt(request.authSecret() == null || request.authSecret().isBlank()
                ? current == null ? "" : secretCipher.decrypt(current.getAuthSecretCiphertext())
                : request.authSecret()),
            stringify(request.availableTools()),
            stringify(request.toolAllowlist()),
            stringify(request.projectScope()),
            false,
            request.timeoutMs() == null || request.timeoutMs() <= 0 ? 30000 : request.timeoutMs(),
            request.enabled() != null && request.enabled(),
            true,
            stringify(request.sandboxConfig()),
            com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystem(),
            com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystemName(),
            current == null ? now : current.getCreatedAt(),
            now
        );
        return toResponse(repository.save(next));
    }

    private AiMcpServerResponse toResponse(AiMcpServer server) {
        return new AiMcpServerResponse(
            server.getServerId(),
            server.getName(),
            server.getServerType(),
            server.getEndpoint(),
            server.getAuthMode(),
            server.getAuthSecretRef(),
            parseArray(server.getAvailableToolsJson()),
            parseArray(server.getToolAllowlistJson()),
            parse(server.getProjectScopeJson()),
            server.isWriteOperationsAllowed(),
            server.getTimeoutMs(),
            server.isEnabled(),
            server.isAuditEnabled(),
            parse(server.getSandboxConfigJson()),
            server.getUpdatedAt() == null ? null : server.getUpdatedAt().toString()
        );
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid MCP server JSON", exception);
        }
    }

    private JsonNode parseArray(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createArrayNode();
        }
        return parse(json);
    }

    private String stringify(JsonNode node) {
        if (node == null) return null;
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid MCP server payload", exception);
        }
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}

record SaveAiMcpServerRequest(
    String serverId,
    String name,
    String serverType,
    String endpoint,
    String authMode,
    String authSecretRef,
    String authSecret,
    JsonNode availableTools,
    JsonNode toolAllowlist,
    JsonNode projectScope,
    Boolean writeOperationsAllowed,
    Integer timeoutMs,
    Boolean enabled,
    Boolean auditEnabled,
    JsonNode sandboxConfig
) {
}

record AiMcpServerResponse(
    String serverId,
    String name,
    String serverType,
    String endpoint,
    String authMode,
    String authSecretRef,
    JsonNode availableTools,
    JsonNode toolAllowlist,
    JsonNode projectScope,
    boolean writeOperationsAllowed,
    int timeoutMs,
    boolean enabled,
    boolean auditEnabled,
    JsonNode sandboxConfig,
    String updatedAt
) {
}
