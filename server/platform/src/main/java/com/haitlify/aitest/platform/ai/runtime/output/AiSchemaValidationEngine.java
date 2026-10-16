package com.haitlify.aitest.platform.ai.runtime.output;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import tools.jackson.databind.JsonNode;

/**
 * T03 输出契约校验引擎：覆盖 object/items/enum/required/additionalProperties/bounds，
 * 错误路径使用 RFC 6901 JSON Pointer（第三子弹要求）。纯函数、无 Spring 依赖，
 * 供 {@code AiStructuredOutputValidator}（本地语义唯一来源）与契约编译/发布自校验共用。
 */
public final class AiSchemaValidationEngine {

    private static final Set<String> KNOWN_TYPES = Set.of(
        "object", "array", "string", "number", "integer", "boolean", "null");

    private AiSchemaValidationEngine() {
    }

    /** 校验输出是否满足 schema；返回 JSON Pointer 路径的错误列表（空 = 通过）。 */
    public static List<String> validate(JsonNode output, JsonNode schema) {
        List<String> errors = new ArrayList<>();
        validateNode("", output, schema, errors);
        return List.copyOf(errors);
    }

    /** schema 发布前自校验：关键字结构不合法一律拒绝（fail-closed）。 */
    public static List<String> validateSchema(JsonNode schema) {
        List<String> errors = new ArrayList<>();
        validateSchemaNode("", schema, errors);
        return List.copyOf(errors);
    }

    private static void validateNode(String pointer, JsonNode output, JsonNode schema, List<String> errors) {
        if (schema == null || !schema.isObject()) {
            return;
        }
        JsonNode typeNode = schema.path("type");
        if (!typeNode.isMissingNode() && !typeNode.isNull()) {
            boolean typeOk = typeNode.isArray()
                ? typeNode.valueStream().anyMatch(candidate -> matchesType(output, candidate.asText("")))
                : matchesType(output, typeNode.asText(""));
            if (!typeOk) {
                String expected = typeNode.isArray() ? compact(typeNode) : typeNode.asText("");
                errors.add(pointer + ": expected type " + expected + " but got " + actualType(output));
                return;
            }
        }
        if (schema.path("enum").isArray()) {
            boolean matched = false;
            for (JsonNode candidate : schema.path("enum")) {
                if (enumMatches(candidate, output)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                errors.add(pointer + ": value " + compact(output) + " is not in enum " + compact(schema.path("enum")));
                return;
            }
        }
        if (output.isObject()) {
            validateObject(pointer, output, schema, errors);
        } else if (output.isArray()) {
            validateArrayItems(pointer, output, schema, errors);
        }
        validateBounds(pointer, output, schema, errors);
    }

    private static void validateObject(String pointer, JsonNode output, JsonNode schema, List<String> errors) {
        JsonNode required = schema.path("required");
        if (required.isArray()) {
            for (JsonNode field : required) {
                String name = field.asText();
                if (!output.has(name)) {
                    errors.add(childPointer(pointer, name) + ": missing required field");
                }
            }
        }
        JsonNode properties = schema.path("properties");
        if (properties.isObject()) {
            properties.properties().forEach(entry -> {
                if (output.has(entry.getKey())) {
                    validateNode(childPointer(pointer, entry.getKey()), output.path(entry.getKey()), entry.getValue(), errors);
                }
            });
        }
        boolean additionalAllowed = !schema.has("additionalProperties") || schema.path("additionalProperties").asBoolean(true);
        JsonNode additionalSchema = schema.path("additionalProperties");
        if (additionalSchema.isObject()) {
            // C6：additionalProperties 为子 schema 时，额外属性必须逐个按其校验（此前被静默忽略）。
            output.properties().forEach(entry -> {
                if (!properties.isObject() || !properties.has(entry.getKey())) {
                    validateNode(childPointer(pointer, entry.getKey()), entry.getValue(), additionalSchema, errors);
                }
            });
        } else if (!additionalAllowed) {
            output.properties().forEach(entry -> {
                if (!properties.isObject() || !properties.has(entry.getKey())) {
                    errors.add(childPointer(pointer, entry.getKey()) + ": additional properties are not allowed");
                }
            });
        }
    }

    private static void validateArrayItems(String pointer, JsonNode output, JsonNode schema, List<String> errors) {
        int validated = 0;
        JsonNode prefixItems = schema.path("prefixItems");
        if (prefixItems.isArray()) {
            // C6：tuple 形式——前 prefixItems.size() 项逐位校验，其余项由 items（如有）校验。
            for (int index = 0; index < output.size() && index < prefixItems.size(); index++) {
                validateNode(pointer + "/" + index, output.get(index), prefixItems.get(index), errors);
            }
            validated = Math.min(output.size(), prefixItems.size());
        }
        JsonNode items = schema.path("items");
        if (items.isArray()) {
            // C-R2-1：draft-07 tuple 形态虽不允许发布，ad-hoc 调用仍按 tuple 语义逐位校验（防御）。
            for (int index = 0; index < output.size() && index < items.size(); index++) {
                validateNode(pointer + "/" + index, output.get(index), items.get(index), errors);
            }
            validated = Math.max(validated, Math.min(output.size(), items.size()));
        } else if (items.isObject()) {
            for (int index = validated; index < output.size(); index++) {
                validateNode(pointer + "/" + index, output.get(index), items, errors);
            }
        }
    }

    private static void validateBounds(String pointer, JsonNode output, JsonNode schema, List<String> errors) {
        if (output.isNumber()) {
            double value = output.asDouble();
            if (schema.has("minimum") && value < schema.path("minimum").asDouble()) {
                errors.add(pointer + ": value " + value + " is below minimum " + schema.path("minimum").asDouble());
            }
            if (schema.has("maximum") && value > schema.path("maximum").asDouble()) {
                errors.add(pointer + ": value " + value + " is above maximum " + schema.path("maximum").asDouble());
            }
            if (schema.has("exclusiveMinimum") && value <= schema.path("exclusiveMinimum").asDouble()) {
                errors.add(pointer + ": value " + value + " must be greater than exclusiveMinimum " + schema.path("exclusiveMinimum").asDouble());
            }
            if (schema.has("exclusiveMaximum") && value >= schema.path("exclusiveMaximum").asDouble()) {
                errors.add(pointer + ": value " + value + " must be less than exclusiveMaximum " + schema.path("exclusiveMaximum").asDouble());
            }
        }
        if (output.isTextual()) {
            int length = output.asText().codePointCount(0, output.asText().length());
            if (schema.has("minLength") && length < schema.path("minLength").asInt()) {
                errors.add(pointer + ": string length " + length + " is below minLength " + schema.path("minLength").asInt());
            }
            if (schema.has("maxLength") && length > schema.path("maxLength").asInt()) {
                errors.add(pointer + ": string length " + length + " exceeds maxLength " + schema.path("maxLength").asInt());
            }
        }
        if (output.isArray()) {
            if (schema.has("minItems") && output.size() < schema.path("minItems").asInt()) {
                errors.add(pointer + ": array size " + output.size() + " is below minItems " + schema.path("minItems").asInt());
            }
            if (schema.has("maxItems") && output.size() > schema.path("maxItems").asInt()) {
                errors.add(pointer + ": array size " + output.size() + " exceeds maxItems " + schema.path("maxItems").asInt());
            }
        }
    }

    private static void validateSchemaNode(String pointer, JsonNode schema, List<String> errors) {
        if (!schema.isObject()) {
            errors.add(pointer + ": schema node must be an object");
            return;
        }
        if (schema.has("type")) {
            JsonNode typeNode = schema.path("type");
            if (typeNode.isArray()) {
                for (JsonNode entry : typeNode) {
                    if (!entry.isTextual() || !KNOWN_TYPES.contains(entry.asText())) {
                        errors.add(pointer + "/type: unknown schema type '" + entry.asText("") + "'");
                        break;
                    }
                }
            } else {
                String type = typeNode.asText("");
                if (!KNOWN_TYPES.contains(type)) {
                    errors.add(pointer + "/type: unknown schema type '" + type + "'");
                }
            }
        }
        if (schema.has("required") && !schema.path("required").isArray()) {
            errors.add(pointer + "/required: must be an array of strings");
        } else if (schema.path("required").isArray()) {
            for (JsonNode entry : schema.path("required")) {
                if (!entry.isTextual()) {
                    errors.add(pointer + "/required: entries must be strings");
                    break;
                }
            }
        }
        if (schema.has("properties") && !schema.path("properties").isObject()) {
            errors.add(pointer + "/properties: must be an object");
        }
        if (schema.has("enum") && !schema.path("enum").isArray()) {
            errors.add(pointer + "/enum: must be an array");
        }
        for (String numericKeyword : List.of("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "minLength", "maxLength", "minItems", "maxItems")) {
            if (schema.has(numericKeyword) && !schema.path(numericKeyword).isNumber()) {
                errors.add(pointer + "/" + numericKeyword + ": must be a number");
            }
        }
        for (String nonNegativeKeyword : List.of("minLength", "maxLength", "minItems", "maxItems")) {
            if (schema.has(nonNegativeKeyword) && schema.path(nonNegativeKeyword).isNumber()
                    && schema.path(nonNegativeKeyword).asInt() < 0) {
                errors.add(pointer + "/" + nonNegativeKeyword + ": must be a non-negative integer");
            }
        }
        JsonNode properties = schema.path("properties");
        if (properties.isObject()) {
            properties.properties().forEach(entry ->
                validateSchemaNode(childPointer(pointer, entry.getKey()), entry.getValue(), errors));
        }
        JsonNode items = schema.path("items");
        if (items.isObject()) {
            validateSchemaNode(pointer + "/items", items, errors);
        } else if (items.isArray()) {
            // C-R2-1：2020-12 方言下 items 数组（draft-07 tuple）不合法，用 prefixItems 表达。
            errors.add(pointer + "/items: tuple form is not supported; use prefixItems");
        }
        // C6：全部子 schema 位置递归体检——additionalProperties/propertyNames/not 及数组形式
        // （anyOf/oneOf/allOf/prefixItems/$defs）内的坏 type 同样必须被发布自校验发现。
        for (String singleKeyword : List.of("additionalProperties", "propertyNames", "not")) {
            JsonNode child = schema.path(singleKeyword);
            if (child.isObject()) {
                validateSchemaNode(pointer + "/" + singleKeyword, child, errors);
            }
        }
        for (String arrayKeyword : List.of("anyOf", "oneOf", "allOf", "prefixItems")) {
            JsonNode child = schema.path(arrayKeyword);
            if (child.isArray()) {
                for (int index = 0; index < child.size(); index++) {
                    validateSchemaNode(pointer + "/" + arrayKeyword + "/" + index, child.get(index), errors);
                }
            }
        }
        JsonNode defs = schema.path("$defs");
        if (defs.isObject()) {
            defs.properties().forEach(entry ->
                validateSchemaNode(pointer + "/$defs/" + entry.getKey(), entry.getValue(), errors));
        }
    }

    private static boolean matchesType(JsonNode output, String type) {
        return switch (type) {
            case "object" -> output.isObject();
            case "array" -> output.isArray();
            case "string" -> output.isTextual();
            case "number" -> output.isNumber();
            case "integer" -> output.isIntegralNumber()
                || (output.isNumber() && Double.isFinite(output.asDouble()) && output.asDouble() == Math.rint(output.asDouble()));
            case "boolean" -> output.isBoolean();
            case "null" -> output.isNull();
            default -> false;
        };
    }

    /** C7：JSON 数值语义下 1.0 与 1 相等；Jackson 节点相等是类型敏感的，这里补数值比较。 */
    private static boolean enumMatches(JsonNode candidate, JsonNode output) {
        if (candidate.equals(output)) {
            return true;
        }
        return candidate.isNumber() && output.isNumber() && candidate.asDouble() == output.asDouble();
    }

    private static String actualType(JsonNode output) {
        if (output.isObject()) {
            return "object";
        }
        if (output.isArray()) {
            return "array";
        }
        if (output.isTextual()) {
            return "string";
        }
        if (output.isIntegralNumber()) {
            return "integer";
        }
        if (output.isNumber()) {
            return "number";
        }
        if (output.isBoolean()) {
            return "boolean";
        }
        if (output.isNull()) {
            return "null";
        }
        return "unknown";
    }

    /** RFC 6901：转义 ~ 与 / 后拼接 JSON Pointer。 */
    private static String childPointer(String pointer, String key) {
        return pointer + "/" + key.replace("~", "~0").replace("/", "~1");
    }

    private static String compact(JsonNode node) {
        return node == null ? "null" : node.toString();
    }
}
