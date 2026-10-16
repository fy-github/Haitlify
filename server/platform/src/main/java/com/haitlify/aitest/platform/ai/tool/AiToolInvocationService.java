package com.haitlify.aitest.platform.ai.tool;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.haitlify.aitest.platform.ai.tool.mcp.AiMcpObservedResult;
import com.haitlify.aitest.platform.ai.tool.mcp.AiMcpRemoteTransport;
import com.haitlify.aitest.platform.ai.tool.mcp.McpRemoteRequest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * T09 bullet 3：工具调用编排——网络 allowlist → 实时 tools/list 定位与 input schema 验证 →
 * tools/call → 输出脱敏（密钥形态一律 [REDACTED] 并计数） → 限长。任何一步失败即 fail-closed，
 * 绝不把未验证的参数或未脱敏的原始输出作为成功交付。
 */
@Service
public class AiToolInvocationService {

    public static final String SCHEMA_VERSION = "mcp-input-schema-v1";

    public record ToolCallSpec(
        String serverId,
        Long projectId,
        String runId,
        String serverType,
        String endpoint,
        String authMode,
        String authSecretRef,
        String authSecretCiphertext,
        int timeoutMs,
        String toolName,
        JsonNode arguments,
        int maxResultBytes,
        List<String> networkAllowlist
    ) {}

    public record InvocationOutcome(
        boolean ok,
        String failure,
        JsonNode result,
        int resultBytes,
        boolean truncated,
        boolean sanitized,
        int redactions,
        String callId,
        int httpStatus,
        String sessionId,
        String schemaVersion
    ) {}

    private static final List<Pattern> SECRET_PATTERNS = List.of(
        Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=-]{8,}"),
        Pattern.compile("sk-[A-Za-z0-9]{16,}"),
        Pattern.compile("AKIA[0-9A-Z]{16}"),
        Pattern.compile("(?i)(api[_-]?key|token|password|secret|authorization)\"?\\s*[:=]\\s*\"?[A-Za-z0-9._~+/=-]{8,}")
    );

    private final AiMcpRemoteTransport transport;
    private final ObjectMapper objectMapper;

    @Autowired
    public AiToolInvocationService(AiMcpRemoteTransport transport, ObjectMapper objectMapper) {
        this.transport = transport;
        this.objectMapper = objectMapper;
    }

    public InvocationOutcome invoke(ToolCallSpec spec) {
        if (!hostAllowed(spec.endpoint(), spec.networkAllowlist())) {
            return failure("MCP endpoint host is not in the network allowlist: " + spec.endpoint());
        }
        McpRemoteRequest request = new McpRemoteRequest(spec.serverType(), spec.endpoint(), spec.authMode(),
            spec.authSecretRef(), spec.authSecretCiphertext(), spec.timeoutMs(), spec.toolName(), spec.arguments());
        JsonNode schema;
        try {
            ArrayNode tools = transport.tools(request);
            JsonNode tool = null;
            for (JsonNode candidate : tools) {
                if (spec.toolName().equals(candidate.path("name").asText(""))) {
                    tool = candidate;
                    break;
                }
            }
            if (tool == null) {
                return failure("MCP tool is not present in the live tools/list: " + spec.toolName());
            }
            schema = tool.path("inputSchema");
            if (!schema.isObject()) {
                return failure("MCP tool has no input schema in the live tools/list: " + spec.toolName());
            }
        } catch (Exception exception) {
            return failure("MCP tools/list verification failed: " + safeMessage(exception));
        }
        String schemaViolation = validate(schema, spec.arguments() == null ? objectMapper.createObjectNode() : spec.arguments(), "$");
        if (schemaViolation != null) {
            return failure("MCP tool argument invalid: " + schemaViolation);
        }
        try {
            AiMcpObservedResult observed = transport.call(request);
            SanitizedResult sanitized = sanitize(observed.result());
            String serialized = objectMapper.writeValueAsString(sanitized.result());
            int resultBytes = serialized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            boolean truncated = resultBytes > spec.maxResultBytes();
            JsonNode delivered;
            if (truncated) {
                delivered = objectMapper.createObjectNode()
                    .put("truncated", true)
                    .put("preview", serialized.substring(0, Math.min(serialized.length(), 4096)));
            } else {
                delivered = sanitized.result();
            }
            return new InvocationOutcome(true, "", delivered, resultBytes, truncated, sanitized.redactions() > 0,
                sanitized.redactions(), observed.callId(), observed.httpStatus(), observed.sessionId(), SCHEMA_VERSION);
        } catch (Exception exception) {
            return failure("MCP remote transport failed: " + safeMessage(exception));
        }
    }

    /** 网络 allowlist：缺省不设限；配置后 host 必须逐字或按 *.suffix 通配匹配。 */
    private boolean hostAllowed(String endpoint, List<String> networkAllowlist) {
        if (networkAllowlist == null || networkAllowlist.isEmpty()) {
            return true;
        }
        String host;
        try {
            host = java.net.URI.create(endpoint).getHost();
        } catch (Exception malformed) {
            return false;
        }
        if (host == null || host.isBlank()) {
            return false;
        }
        for (String rule : networkAllowlist) {
            if (rule == null || rule.isBlank()) {
                continue;
            }
            String trimmed = rule.trim();
            if (trimmed.equalsIgnoreCase(host)) {
                return true;
            }
            if (trimmed.startsWith("*.") && host.toLowerCase().endsWith(trimmed.substring(1).toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /** 最小 JSON-schema 子集校验（type/required/properties/additionalProperties/items）——
     * 违反即返回人类可读路径，调用 fail-closed。 */
    private String validate(JsonNode schema, JsonNode value, String path) {
        if (schema == null || !schema.isObject()) {
            return null;
        }
        String type = schema.path("type").asText("");
        if (!type.isBlank() && !typeMatches(type, value)) {
            return path + ": expected " + type;
        }
        if ("object".equals(type) || schema.path("properties").isObject()) {
            if (!value.isObject()) {
                return path + ": expected object";
            }
            for (JsonNode required : schema.path("required")) {
                String name = required.asText("");
                if (!name.isBlank() && !value.has(name)) {
                    return path + "." + name + ": required";
                }
            }
            JsonNode properties = schema.path("properties");
            if (schema.path("additionalProperties").isBoolean() && !schema.path("additionalProperties").asBoolean()) {
                for (var entry : value.properties()) {
                    if (!properties.has(entry.getKey())) {
                        return path + "." + entry.getKey() + ": unknown property";
                    }
                }
            }
            for (var entry : value.properties()) {
                JsonNode subSchema = properties.get(entry.getKey());
                if (subSchema != null) {
                    String violation = validate(subSchema, entry.getValue(), path + "." + entry.getKey());
                    if (violation != null) {
                        return violation;
                    }
                }
            }
        }
        if ("array".equals(type)) {
            for (int index = 0; index < value.size(); index++) {
                String violation = validate(schema.path("items"), value.get(index), path + "[" + index + "]");
                if (violation != null) {
                    return violation;
                }
            }
        }
        return null;
    }

    private boolean typeMatches(String type, JsonNode value) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> true;
        };
    }

    private record SanitizedResult(JsonNode result, int redactions) {}

    /** 输出脱敏：密钥形态文本替换为 [REDACTED]，计数供审计。 */
    private SanitizedResult sanitize(JsonNode node) {
        int[] redactions = new int[1];
        return new SanitizedResult(sanitizeNode(node, redactions), redactions[0]);
    }

    private JsonNode sanitizeNode(JsonNode node, int[] redactions) {
        if (node == null) {
            return objectMapper.createObjectNode();
        }
        if (node.isTextual()) {
            String text = node.asText();
            for (Pattern pattern : SECRET_PATTERNS) {
                Matcher matcher = pattern.matcher(text);
                StringBuilder collector = new StringBuilder();
                while (matcher.find()) {
                    redactions[0]++;
                    String masked = matcher.group().contains("=") || matcher.group().contains(":")
                        ? matcher.group().replaceAll("[:=]\\s*\"?[A-Za-z0-9._~+/=-]{8,}", "=[REDACTED]")
                        : "[REDACTED]";
                    matcher.appendReplacement(collector, Matcher.quoteReplacement(masked));
                }
                matcher.appendTail(collector);
                text = collector.toString();
            }
            return objectMapper.getNodeFactory().textNode(text);
        }
        if (node.isObject()) {
            ObjectNode copy = objectMapper.createObjectNode();
            for (var entry : node.properties()) {
                copy.set(entry.getKey(), sanitizeNode(entry.getValue(), redactions));
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = objectMapper.createArrayNode();
            for (JsonNode item : node) {
                copy.add(sanitizeNode(item, redactions));
            }
            return copy;
        }
        return node.deepCopy();
    }

    private InvocationOutcome failure(String message) {
        return new InvocationOutcome(false, message, null, 0, false, false, 0, "", 0, "", SCHEMA_VERSION);
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        return message.length() > 240 ? message.substring(0, 237) + "..." : message;
    }
}
