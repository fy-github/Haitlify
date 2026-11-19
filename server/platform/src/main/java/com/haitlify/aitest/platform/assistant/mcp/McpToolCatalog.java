package com.haitlify.aitest.platform.assistant.mcp;

import java.util.List;
import java.util.Map;

/** Fixed catalog, deliberately not runtime-discoverable or model-configurable. */
final class McpToolCatalog {
    private McpToolCatalog() {}
    static List<Map<String, Object>> tools() {
        var empty = object(Map.of(), List.of());
        var step = object(Map.of("id", text(64), "action", Map.of("type", "string", "enum", List.of(
            "goto", "click", "hover", "scrollIntoView", "assertVisible", "assertHidden", "assertText", "assertUrl",
            "assertEnabled", "assertDisabled", "waitForVisible", "waitForUrl", "waitForText", "waitForHidden", "waitForNetworkIdle")),
            "target", text(2048), "value", Map.of("anyOf", List.of(text(200), Map.of("type", "null")))),
            List.of("id", "action", "target"));
        var spec = object(Map.of("id", text(64), "name", text(256), "startUrl", text(2048),
            "steps", Map.of("type", "array", "minItems", 1, "maxItems", 100, "items", step)), List.of("id", "name", "startUrl", "steps"));
        return List.of(tool("generation_context", "Read immutable authorized generation context.", empty),
            tool("draft_create", "Create or replay one DRAFT only. Spec <=32KiB, same authorized URL origin; no inputs or execution.",
                object(Map.of("spec", spec), List.of("spec"))),
            tool("draft_get", "Read this generation's draft only.", empty));
    }
    private static Map<String, Object> text(int max) {
        return Map.of("type", "string", "minLength", 1, "maxLength", max, "pattern", "\\S");
    }
    private static Map<String, Object> object(Map<String, ?> properties, List<String> required) {
        return Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false);
    }
    private static Map<String, Object> tool(String name, String description, Map<String, Object> schema) {
        return Map.of("name", name, "description", description, "inputSchema", schema);
    }
}
