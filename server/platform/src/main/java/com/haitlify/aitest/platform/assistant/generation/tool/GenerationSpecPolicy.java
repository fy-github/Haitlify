package com.haitlify.aitest.platform.assistant.generation.tool;

import com.haitlify.aitest.platform.assistant.compile.AssistantInputBindingService;
import com.haitlify.aitest.platform.assistant.compile.AssistantScriptCompiler;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Syntax/origin confinement only: NOT DNS resolution, redirect or browser SSRF authorization.
 * Inputs, fill/input/select and secretRef are deliberately unavailable. Any future input support
 * requires a separate authorization design; do not relax this list to make a model result pass. */
final class GenerationSpecPolicy {
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> ACTIONS = Set.of("goto", "click", "hover", "scrollIntoView",
        "assertVisible", "assertHidden", "assertText", "assertUrl", "assertEnabled", "assertDisabled",
        "waitForVisible", "waitForUrl", "waitForText", "waitForHidden", "waitForNetworkIdle");

    private GenerationSpecPolicy() {}
    static AssistantScriptCompiler.CaseSpec parse(String raw, String authorizedUrl) {
        if (raw == null || raw.length() > 32768 || raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 32768)
            throw new IllegalArgumentException();
        JsonNode body = JSON.readTree(raw);
        exact(body, Set.of("id", "name", "startUrl", "steps"));
        String start = text(body, "startUrl", 2048);
        URI origin = absolute(authorizedUrl);
        sameOrigin(origin, absolute(start));
        JsonNode steps = body.path("steps");
        if (!steps.isArray() || steps.isEmpty() || steps.size() > 100) throw new IllegalArgumentException();
        List<AssistantScriptCompiler.Step> result = new ArrayList<>();
        for (JsonNode step : steps) {
            if (!step.isObject() || !Set.of("id", "action", "target", "value").containsAll(step.propertyNames())
                || !step.has("id") || !step.has("action") || !step.has("target")) throw new IllegalArgumentException();
            String action = text(step, "action", 32);
            if (!ACTIONS.contains(action)) throw new IllegalArgumentException();
            String target = text(step, "target", 2048);
            String value = !step.hasNonNull("value") ? null : text(step, "value", 200);
            if (value != null) AssistantInputBindingService.validateValues(Map.of("value", value));
            if ("goto".equals(action)) {
                URI destination = target.startsWith("/") && !target.startsWith("//") ? absolute(start).resolve(target) : absolute(target);
                sameOrigin(origin, destination);
            }
            result.add(new AssistantScriptCompiler.Step(text(step, "id", 64), action, target, value));
        }
        return new AssistantScriptCompiler.CaseSpec(text(body, "id", 64), text(body, "name", 256), start, List.copyOf(result));
    }
    private static void exact(JsonNode body, Set<String> fields) {
        if (!body.isObject() || !body.propertyNames().equals(fields)) throw new IllegalArgumentException();
    }
    private static String text(JsonNode body, String field, int max) {
        JsonNode value = body.path(field);
        if (!value.isString() || value.asString().isBlank() || value.asString().length() > max) throw new IllegalArgumentException();
        return value.asString();
    }
    private static URI absolute(String raw) {
        URI uri = URI.create(raw);
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null
            || uri.getRawUserInfo() != null || uri.getPort() > 65535 || uri.getPort() == 0 || raw.contains("\\")) throw new IllegalArgumentException();
        return uri;
    }
    private static void sameOrigin(URI authorized, URI actual) {
        if (actual.getUserInfo() != null || !authorized.getScheme().equals(actual.getScheme())
            || actual.getHost() == null || !authorized.getHost().equalsIgnoreCase(actual.getHost())
            || port(authorized) != port(actual)) throw new IllegalArgumentException();
    }
    private static int port(URI uri) { return uri.getPort() == -1 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort(); }
}
