package com.haitlify.aitest.platform.ai.runtime.output;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * T03 输出契约编译器：同一份本地 schema 语义 → 按协议目标产出接线形态；
 * 目标不支持的关键字一律 fail-closed 抛错（不静默降级）。
 */
@Service
public class AiSchemaCompiler {

    /** 协议目标：与统一调用契约的四种结构化输出接入方式一一对应。 */
    public enum Target {
        /** Chat Completions native response_format=json_schema（strict 子集）。 */
        CHAT_JSON_SCHEMA,
        /** Responses API text.format（strict 子集）。 */
        RESPONSES_TEXT_FORMAT,
        /** 仅 json_object 模式：schema 不进协议层，本地校验仍是唯一语义来源。 */
        JSON_MODE,
        /** 纯 prompt 注入：schema 字节随指令下发，本地校验兜底。 */
        PROMPT_JSON
    }

    /** strict 目标（JSON Schema 下发）不允许出现的关键字（方言未纳入风险的保守清单）。 */
    private static final Set<String> STRICT_UNSUPPORTED = Set.of(
        "pattern", "anyOf", "oneOf", "allOf", "not", "if", "then", "else", "$ref", "definitions", "$defs");

    private final ObjectMapper objectMapper;

    public AiSchemaCompiler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public CompiledAiOutputContract compile(JsonNode schema, Target target) {
        List<String> schemaErrors = AiSchemaValidationEngine.validateSchema(schema);
        if (!schemaErrors.isEmpty()) {
            throw new IllegalArgumentException("Invalid output schema: " + String.join("; ", schemaErrors));
        }
        if (target == Target.CHAT_JSON_SCHEMA || target == Target.RESPONSES_TEXT_FORMAT) {
            List<String> unsupported = collectUnsupported(schema);
            if (!unsupported.isEmpty()) {
                throw new IllegalArgumentException(
                    "Schema uses keywords unsupported by the " + target + " strict dialect: " + String.join(", ", unsupported));
            }
        }
        String bytes = schema.toString();
        String promptDirective = switch (target) {
            case CHAT_JSON_SCHEMA, RESPONSES_TEXT_FORMAT -> "";
            case JSON_MODE -> "";
            case PROMPT_JSON -> "Respond with a single JSON document that strictly matches this JSON Schema:\n" + bytes;
        };
        return new CompiledAiOutputContract(target, bytes, promptDirective);
    }

    private List<String> collectUnsupported(JsonNode schema) {
        List<String> found = new java.util.ArrayList<>();
        collectUnsupported(schema, "", found);
        return List.copyOf(found);
    }

    private void collectUnsupported(JsonNode node, String pointer, List<String> found) {
        if (node == null || !node.isObject()) {
            return;
        }
        for (String keyword : STRICT_UNSUPPORTED) {
            if (node.has(keyword)) {
                found.add(pointer + "/" + escapePointerSegment(keyword));
            }
        }
        // C2：递归覆盖全部子 schema 位置——此前只走 properties/items，导致
        // additionalProperties({...})/propertyNames/prefixItems/not/$defs 内的
        // anyOf/pattern/$ref 等可直接进入 strict 目标的请求载荷（静默降级）。
        JsonNode properties = node.path("properties");
        if (properties.isObject()) {
            properties.properties().forEach(entry ->
                collectUnsupported(entry.getValue(), pointer + "/properties/" + escapePointerSegment(entry.getKey()), found));
        }
        JsonNode patternProperties = node.path("patternProperties");
        if (patternProperties.isObject()) {
            patternProperties.properties().forEach(entry ->
                collectUnsupported(entry.getValue(), pointer + "/patternProperties/" + escapePointerSegment(entry.getKey()), found));
        }
        JsonNode defs = node.path("$defs");
        if (defs.isObject()) {
            defs.properties().forEach(entry ->
                collectUnsupported(entry.getValue(), pointer + "/$defs/" + escapePointerSegment(entry.getKey()), found));
        }
        for (String keyword : List.of("items", "additionalProperties", "propertyNames", "not", "contains", "if", "then", "else")) {
            JsonNode child = node.path(keyword);
            if (child.isObject()) {
                collectUnsupported(child, pointer + "/" + keyword, found);
            } else if ("items".equals(keyword) && child.isArray()) {
                // C-R2-1：items 数组（draft-07 tuple）本身就是 strict 目标不支持的形态；
                // 直接标记并递归其条目，避免任何嵌套关键字绕过。
                found.add(pointer + "/items");
                for (int index = 0; index < child.size(); index++) {
                    collectUnsupported(child.get(index), pointer + "/items/" + index, found);
                }
            }
        }
        for (String keyword : List.of("anyOf", "oneOf", "allOf", "prefixItems")) {
            JsonNode child = node.path(keyword);
            if (child.isArray()) {
                for (int index = 0; index < child.size(); index++) {
                    collectUnsupported(child.get(index), pointer + "/" + keyword + "/" + index, found);
                }
            }
        }
    }

    /** RFC 6901 JSON Pointer 段转义（~ → ~0，/ → ~1）。 */
    private static String escapePointerSegment(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }

    /** 编译产物：目标 + schema 字节（独立 JSON 字节接口） + prompt 注入指令（仅 PROMPT_JSON）。 */
    public record CompiledAiOutputContract(Target target, String schemaJsonBytes, String promptDirective) {
    }
}
