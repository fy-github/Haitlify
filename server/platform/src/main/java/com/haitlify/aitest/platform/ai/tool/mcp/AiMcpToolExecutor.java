package com.haitlify.aitest.platform.ai.tool.mcp;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import com.haitlify.aitest.platform.ai.tool.AiToolInvocationService;

@Service
@Transactional
public class AiMcpToolExecutor {

    private static final String STATUS_BLOCKED = "blocked";
    private static final String STATUS_SUCCESS = "success";

    private final JdbcTemplate jdbcTemplate;
    private final AiMcpToolCallRepository toolCallRepository;
    private final ObjectMapper objectMapper;
    private final AiToolInvocationService invocationService;
    private final com.haitlify.aitest.platform.ai.runtime.artifact.AiArtifactGateAuditService gateAuditService;

    AiMcpToolExecutor(JdbcTemplate jdbcTemplate, AiMcpToolCallRepository toolCallRepository, ObjectMapper objectMapper,
                      AiToolInvocationService invocationService,
                      com.haitlify.aitest.platform.ai.runtime.artifact.AiArtifactGateAuditService gateAuditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.toolCallRepository = toolCallRepository;
        this.objectMapper = objectMapper;
        this.invocationService = invocationService;
        this.gateAuditService = gateAuditService;
    }

    public AiMcpToolExecutionResult execute(AiMcpToolExecutionRequest request) {
        Optional<McpServerConfig> server = findServer(request.serverId());
        if (server.isEmpty()) {
            return blocked(request, "Unknown MCP server: " + request.serverId());
        }
        McpServerConfig config = server.get();
        if (!config.enabled()) {
            return blocked(request, "MCP server is disabled: " + request.serverId());
        }
        if (!isProjectAllowed(config.projectScopeJson(), request.projectId())) {
            return blocked(request, "MCP server is not scoped to project: " + request.projectId());
        }
        ToolPolicy toolPolicy = toolPolicy(config.toolAllowlistJson(), request.toolName());
        if (!toolPolicy.allowlisted()) {
            return blocked(request, "MCP tool is not allowlisted: " + request.toolName());
        }
        // T09 bullet 2：readOnly 由 server metadata 与管理员策略裁决，绝不信任请求体。
        if (toolPolicy.readOnly() == null) {
            return blocked(request, "MCP tool lacks server-side read-only metadata;"
                + " migrate the allowlist entry to the object form {name, readOnly}: " + request.toolName());
        }
        if (!toolPolicy.readOnly()) {
            // T09 bullet 8：副作用工具必须带幂等键，否则直接 BLOCKED；带键也仍需人审放行。
            if (!hasIdempotencyKey(request.arguments())) {
                return blocked(request, "MCP side-effect tool requires an idempotency key"
                    + " (arguments._idempotencyKey): " + request.toolName());
            }
            return blocked(request, config.writeOperationsAllowed()
                ? "MCP write tools require human review and are not enabled: " + request.toolName()
                : "MCP server policy does not allow write operations: " + request.toolName());
        }
        if (toolCallRepository.countByRunId(nonNull(request.runId())) >= maxCallsPerRun(config.sandboxConfigJson())) {
            return blocked(request, "MCP tool call limit exceeded for run: " + request.runId());
        }
        // T09 bullet 7：重复参数循环检测——同一 run 内相同 (tool, 参数 hash) 达到上限即拒绝。
        int repeatLimit = maxRepeatedParameterCalls(config.sandboxConfigJson());
        if (toolCallRepository.countByRunIdAndParameterHash(nonNull(request.runId()), parameterHash(request))
                >= repeatLimit) {
            return blocked(request, "MCP repeated parameter loop detected for run: " + request.runId());
        }
        try {
            AiToolInvocationService.ToolCallSpec spec = new AiToolInvocationService.ToolCallSpec(
                request.serverId(), request.projectId(), nonNull(request.runId()),
                config.serverType(), config.endpoint(), config.authMode(), config.authSecretRef(),
                config.authSecretCiphertext(), config.timeoutMs(), request.toolName(), request.arguments(),
                maxResultBytes(config.sandboxConfigJson()), networkAllowlist(config.sandboxConfigJson()));
            AiToolInvocationService.InvocationOutcome outcome = invocationService.invoke(spec);
            if (!outcome.ok()) {
                return blocked(request, outcome.failure());
            }
            ObjectNode response = objectMapper.createObjectNode()
                .put("executed", true)
                .put("serverId", request.serverId())
                .put("toolName", request.toolName())
                .put("resultOrigin", "TOOL_OBSERVED")
                .put("acceptanceState", "PENDING_HUMAN_CONFIRMATION")
                .put("providerCallId", outcome.callId())
                .put("evidenceId", "mcp:" + request.runId() + ":" + request.toolName())
                .put("httpStatus", outcome.httpStatus())
                .put("resultBytes", outcome.resultBytes())
                .put("resultTruncated", outcome.truncated())
                .put("resultSanitized", outcome.sanitized())
                .put("resultRedactions", outcome.redactions())
                .put("schemaVersion", outcome.schemaVersion());
            response.set("result", outcome.result());
            // T09 bullet 3：证据登记——工具结果以 TOOL_OBSERVED 入账，仍留人审门禁。
            gateAuditService.record(new com.haitlify.aitest.platform.ai.runtime.artifact.AiArtifactGateAuditService.GateRecord(
                "mcp:" + request.runId() + ":" + request.toolName(),
                nonNull(request.runId()),
                request.projectId(),
                "TOOL_OBSERVED",
                "PENDING_HUMAN_CONFIRMATION",
                1,
                "",
                "mcp:" + request.runId() + ":" + request.toolName(),
                java.util.List.of("mcp:" + request.runId() + ":" + request.toolName(), "mcp-call:" + outcome.callId()),
                java.util.List.of(),
                request.actorId() == null || request.actorId().isBlank() ? com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystem() : request.actorId()));
            saveAudit(request, STATUS_SUCCESS, response, "");
            return new AiMcpToolExecutionResult(STATUS_SUCCESS, response, "");
        } catch (Exception exception) {
            return blocked(request, "MCP remote transport failed: " + safeMessage(exception));
        }
    }

    private java.util.List<String> networkAllowlist(String sandboxConfigJson) {
        java.util.List<String> allowlist = new java.util.ArrayList<>();
        for (JsonNode item : parseObject(sandboxConfigJson).path("networkAllowlist")) {
            if (item.isTextual() && !item.asText().isBlank()) {
                allowlist.add(item.asText());
            }
        }
        return allowlist;
    }

    private boolean hasIdempotencyKey(JsonNode arguments) {
        return arguments != null && arguments.isObject()
            && !arguments.path("_idempotencyKey").asText("").isBlank();
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        return message.length() > 240 ? message.substring(0, 237) + "..." : message;
    }

    private AiMcpToolExecutionResult blocked(AiMcpToolExecutionRequest request, String message) {
        ObjectNode response = objectMapper.createObjectNode()
            .put("blocked", true)
            .put("resultOrigin", "BLOCKED")
            .put("acceptanceState", "BLOCKED");
        saveAudit(request, STATUS_BLOCKED, response, message);
        return new AiMcpToolExecutionResult(STATUS_BLOCKED, response, message);
    }

    private Optional<McpServerConfig> findServer(String serverId) {
        return jdbcTemplate.query("""
            select server_id, enabled, server_type, endpoint, auth_mode, auth_secret_ref, auth_secret_ciphertext, timeout_ms,
        tool_allowlist_json, project_scope_json, sandbox_config_json, write_operations_allowed
            from ai_mcp_servers
            where server_id = ?
            """,
            (rs, rowNum) -> new McpServerConfig(
                rs.getString("server_id"),
                rs.getBoolean("enabled"),
                rs.getString("server_type"),
                rs.getString("endpoint"),
                rs.getString("auth_mode"),
                rs.getString("auth_secret_ref"),
                rs.getString("auth_secret_ciphertext"),
                rs.getInt("timeout_ms"),
                rs.getString("tool_allowlist_json"),
                rs.getString("project_scope_json"),
                rs.getString("sandbox_config_json"),
                rs.getBoolean("write_operations_allowed")
            ),
            serverId
        )
        .stream()
        .findFirst();
        }

        /**
        * 工具策略：允许名单支持对象形式 {name, readOnly}（服务端元数据；权威）；
        * 旧字符串形式不含 read-only 声明 ⇒ 一律不可授权（fail-closed，提示迁移）。
        */
        private ToolPolicy toolPolicy(String allowlistJson, String toolName) {
        JsonNode allowlist = parseArray(allowlistJson);
        if (!allowlist.isArray()) {
        return new ToolPolicy(false, null);
        }
        for (JsonNode item : allowlist) {
        if (item.isObject() && toolName.equals(item.path("name").asText(""))) {
            JsonNode readOnly = item.path("readOnly");
            return new ToolPolicy(true, readOnly.isBoolean() ? readOnly.asBoolean() : null);
        }
        }
        for (JsonNode item : allowlist) {
        if (item.isTextual() && toolName.equals(item.asText())) {
            return new ToolPolicy(true, null);
        }
        }
        return new ToolPolicy(false, null);
        }

        private int maxResultBytes(String sandboxConfigJson) {
            int configured = parseObject(sandboxConfigJson).path("maxResultBytes").asInt(262144);
            return configured <= 0 ? 262144 : configured;
        }

        private int maxRepeatedParameterCalls(String sandboxConfigJson) {
            int configured = parseObject(sandboxConfigJson).path("maxRepeatedParameterCalls").asInt(3);
            return configured <= 0 ? 3 : configured;
        }

    private boolean isProjectAllowed(String projectScopeJson, Long projectId) {
        JsonNode scope = parseObject(projectScopeJson);
        if (scope.isEmpty() || "all".equals(scope.path("mode").asText())) {
            return true;
        }
        JsonNode projectIds = scope.path("projectIds");
        if (projectIds.isArray()) {
            for (JsonNode item : projectIds) {
                if (projectId != null && item.asLong() == projectId) {
                    return true;
                }
            }
            return false;
        }
        if (scope.has("projectId")) {
            return projectId != null && scope.path("projectId").asLong() == projectId;
        }
        return true;
    }

    private int maxCallsPerRun(String sandboxConfigJson) {
        int configured = parseObject(sandboxConfigJson).path("maxCallsPerRun").asInt(Integer.MAX_VALUE);
        return configured <= 0 ? Integer.MAX_VALUE : configured;
    }

    private void saveAudit(AiMcpToolExecutionRequest request, String status, JsonNode response, String errorMessage) {
        toolCallRepository.save(new AiMcpToolCallRecord(
            null,
            "mcp-call-" + UUID.randomUUID(),
            nonNull(request.runId()),
            request.projectId(),
            nonNull(request.serverId()),
            nonNull(request.toolName()),
            request.readOnly(),
            status,
            stringify(requestSummary(request)),
            stringify(response),
            response.path("resultOrigin").asText("BLOCKED"),
            response.path("acceptanceState").asText("BLOCKED"),
            response.path("providerCallId").asText(""),
            parameterHash(request),
            response.path("evidenceId").asText(""),
            response.path("resultOrigin").asText("").equals("TOOL_OBSERVED") ? Instant.now() : null,
            errorMessage == null ? "" : errorMessage,
            request.actorId() == null || request.actorId().isBlank() ? com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystem() : request.actorId(),
            nonNull(request.nodeId()),
            nonNull(request.attemptId()),
            Instant.now()
        ));
    }

    private String parameterHash(AiMcpToolExecutionRequest request) {
        try {
            String value = request.toolName() + ":" + stringify(request.arguments());
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception exception) {
            return "";
        }
    }
    private ObjectNode requestSummary(AiMcpToolExecutionRequest request) {
        ObjectNode summary = objectMapper.createObjectNode()
            .put("serverId", nonNull(request.serverId()))
            .put("toolName", nonNull(request.toolName()));
        ArrayNode keys = summary.putArray("argumentKeys");
        if (request.arguments() != null && request.arguments().isObject()) {
            for (Map.Entry<String, JsonNode> argumentEntry : request.arguments().properties()) {
                keys.add(argumentEntry.getKey());
            }
        }
        return summary;
    }

    private JsonNode parseObject(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return node == null || !node.isObject() ? objectMapper.createObjectNode() : node;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid MCP JSON config", exception);
        }
    }

    private JsonNode parseArray(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createArrayNode();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return node == null || !node.isArray() ? objectMapper.createArrayNode() : node;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid MCP JSON config", exception);
        }
    }

    private String stringify(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node == null ? objectMapper.createObjectNode() : node);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid MCP audit JSON", exception);
        }
    }

    private String nonNull(String value) {
        return value == null ? "" : value;
    }

    private record McpServerConfig(
        String serverId,
        boolean enabled,
        String serverType,
        String endpoint,
        String authMode,
        String authSecretRef,
        String authSecretCiphertext,
        int timeoutMs,
        String toolAllowlistJson,
        String projectScopeJson,
        String sandboxConfigJson,
        boolean writeOperationsAllowed
    ) {
    }

    /** readOnly=null 表示服务端未声明（不可授权）；false=写工具（管理员策略否决）。 */
    private record ToolPolicy(boolean allowlisted, Boolean readOnly) {}
}
