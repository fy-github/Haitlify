package com.haitlify.aitest.platform.webautomation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.List;

import org.springframework.stereotype.Component;

@Component
class PlaywrightRunClient {

    private static final Set<String> CONTROL_ACTIONS = Set.of("pause", "resume", "stop");
    private static final Set<String> STABLE_CATEGORIES = Set.of(
        "auth_context_lost",
        "login_failed",
        "captcha_required",
        "page_not_ready",
        "wrong_page",
        "network_blocked_critical",
        "network_blocked_unknown",
        "business_api_failed",
        "element_not_found",
        "ambiguous_locator",
        "unsafe_overlay",
        "unsupported_dom",
        "duration_exhausted",
        "manual_review_required",
        // runner 的步骤级/Action IR 分类：以前不在表里 → normalizeCategory 返回空串，
        // 这些取证失败会被降级成「runner 调用异常」，看不出真实原因。
        "unsupported_frame",
        "unsupported_shadow_dom",
        "unsupported_action_target",
        "invalid_action_ir",
        "action_evidence_missing",
        "action_evidence_mismatch",
        "assertion_failed",
        "observation_failed",
        "settle_timeout",
        "session_busy",
        // 与 runner 的 EVIDENCE_CATEGORIES 对齐：同一语义的分类两边都要认，否则平台把它归一成空串。
        "pagination_not_changed",
        "action_failed",
        "unsafe_action",
        "navigation_failed",
        "locator_persistence",
        "network_blocked_optional",
        "overlay_not_ready",
        "auth_dependency",
        "unsupported_action"
    );
    /** 失败响应里步骤证据的白名单字段：与 runner 的 buildActionEvidence 输出对齐。 */
    private static final List<String> STEP_EVIDENCE_FIELDS = List.of(
        "actionId", "actionType", "sourcePointId", "evidenceId", "before", "after", "resolvedTarget", "execution"
    );
    private static final int MAX_EVIDENCE_ENTRIES = 20;

    private final WebAutomationProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    PlaywrightRunClient(WebAutomationProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(properties.runner().timeoutMs()))
            .build();
    }

    WebAutomationRunSnapshotResponse createRun(WebAutomationRunRequest request) {
        return send("POST", "/runs", request, WebAutomationRunSnapshotResponse.class);
    }

    WebAutomationRunSnapshotResponse getRun(String runId) {
        return send("GET", "/runs/" + pathSegment(runId), null, WebAutomationRunSnapshotResponse.class);
    }

    WebAutomationRunSnapshotResponse controlRun(String runId, String action) {
        if (!CONTROL_ACTIONS.contains(action)) {
            throw new IllegalArgumentException("Unsupported Web run control: " + action);
        }
        return send(
            "POST",
            "/runs/" + pathSegment(runId) + "/" + action,
            null,
            WebAutomationRunSnapshotResponse.class
        );
    }

    WebAutomationCapabilitiesResponse capabilities() {
        return send("GET", "/capabilities", null, WebAutomationCapabilitiesResponse.class);
    }

    WebAutomationSessionListResponse listSessions() {
        return send("GET", "/sessions", null, WebAutomationSessionListResponse.class);
    }

    WebLoginDiscoverySnapshotResponse createLoginDiscovery(WebLoginDiscoveryRequest request) {
        return send("POST", "/login-discovery", request, WebLoginDiscoverySnapshotResponse.class);
    }

    WebLoginDiscoverySnapshotResponse getLoginDiscovery(String discoveryId) {
        return send(
            "GET",
            "/login-discovery/" + pathSegment(discoveryId),
            null,
            WebLoginDiscoverySnapshotResponse.class
        );
    }

    WebLoginDiscoverySnapshotResponse confirmLoginDiscovery(
        String discoveryId,
        WebLoginDiscoveryConfirmRequest request
    ) {
        return send(
            "POST",
            "/login-discovery/" + pathSegment(discoveryId) + "/confirm",
            request,
            WebLoginDiscoverySnapshotResponse.class
        );
    }

    WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse discoverPageLocators(
        WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryRequest request
    ) {
        return send(
            "POST",
            "/page-locator-discovery",
            request,
            WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse.class
        );
    }

    WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse resumeEvidenceHandoff(
        String handoffId,
        WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryRequest request
    ) {
        var payload = objectMapper.valueToTree(request);
        if (payload != null && payload.isObject()) {
            payload = payload.deepCopy();
            ((tools.jackson.databind.node.ObjectNode) payload).put("handoffId", handoffId);
            ((tools.jackson.databind.node.ObjectNode) payload).put("resumeFromHandoff", true);
        }
        return send(
            "POST",
            "/page-locator-discovery",
            payload,
            WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse.class
        );
    }

    private <T> T send(String method, String path, JsonNode body, Class<T> responseType) {
        ensureEnabled();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(properties.runner().baseUrl().replaceAll("/$", "") + path))
                .timeout(Duration.ofMillis(properties.runner().timeoutMs()))
                .header("Accept", "application/json");
            if (method.equals("POST")) {
                builder.header("Content-Type", "application/json")
                    .POST(body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
            } else {
                builder.GET();
            }

            HttpResponse<String> response = httpClient.send(
                builder.build(),
                HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() >= 400) {
                throw runnerError(response.statusCode(), response.body());
            }
            return objectMapper.readValue(response.body(), responseType);
        } catch (WebAutomationRunnerUnavailableException | WebAutomationEvidenceException exception) {
            throw exception;
        } catch (HttpTimeoutException exception) {
            throw new WebAutomationRunnerUnavailableException(
                503,
                "Web automation runner command timed out after " + properties.runner().timeoutMs() + " ms",
                exception
            );
        } catch (Exception exception) {
            throw new WebAutomationRunnerUnavailableException(
                503,
                "Web automation runner is unavailable",
                exception
            );
        }
    }

    private <T> T send(String method, String path, Object body, Class<T> responseType) {
        return send(method, path, body == null ? null : objectMapper.valueToTree(body), responseType);
    }

    private RuntimeException runnerError(int statusCode, String body) {
        String message = "";
        JsonNode payload = null;
        try {
            payload = objectMapper.readTree(body);
            message = payload.path("message").asText("");
        } catch (Exception ignored) {
            message = body == null ? "" : body.trim();
        }
        String detail = "Web automation runner returned HTTP " + statusCode;
        String safeMessage = safeDiagnosticText(message, 160);
        if (!safeMessage.isBlank()) detail += ": " + safeMessage;
        JsonNode safeDetails = safeEvidenceDetails(payload);
        String category = safeCategory(payload);
        if (!category.isBlank()) {
            if (detail.length() < 400) detail += " [" + category + "]";
            return new WebAutomationEvidenceException(422, detail, category, safeDetails);
        }
        return new WebAutomationRunnerUnavailableException(statusCode, detail, null, safeDetails);
    }

    private String safeCategory(JsonNode payload) {
        if (payload == null || payload.isNull()) return "";
        String category = normalizeCategory(payload.path("category").asText(""));
        if (!category.isBlank()) return category;

        category = normalizeHandoffCategory(payload.path("handoff"));
        if (!category.isBlank()) return category;

        if (payload.path("handoff").path("required").asBoolean(false)) return "manual_review_required";

        category = normalizeCategory(firstNonBlank(
            payload.path("failureReason").asText(""),
            payload.path("exploration").path("failureReason").asText("")
        ));
        return category;
    }

    private JsonNode safeEvidenceDetails(JsonNode payload) {
        if (payload == null || payload.isNull()) return null;
        var details = objectMapper.createObjectNode();
        String category = safeCategory(payload);
        if (!category.isBlank()) details.put("category", category);
        // 动作级失败的定位信息：授权重试/重绑需要知道是哪条 target 失效（实测 2026-10-14 被白名单吞掉后无法自动重授权）。
        String targetEvidenceId = payload.path("targetEvidenceId").asText("");
        if (!targetEvidenceId.isBlank()) details.put("targetEvidenceId", safeDiagnosticText(targetEvidenceId, 120));
        if (payload.path("matchedElementCount").canConvertToInt()) details.put("matchedElementCount", payload.path("matchedElementCount").asInt());
        String failedActionId = payload.path("actionId").asText("");
        if (!failedActionId.isBlank()) details.put("actionId", safeDiagnosticText(failedActionId, 120));
        String pageUrl = payload.path("pageUrl").asText(payload.path("exploration").path("finalObservation").path("url").asText(""));
        if (!pageUrl.isBlank()) details.put("pageUrl", safePageUrl(pageUrl));
        JsonNode exploration = payload.path("exploration");
        JsonNode repairTrail = exploration.path("repairTrail");
        if (!repairTrail.isArray()) repairTrail = payload.path("repairTrail");
        if (repairTrail.isArray()) details.set("repairTrail", safeRepairTrail(repairTrail));
        JsonNode network = exploration.path("network").isObject() ? exploration.path("network") : payload.path("network");
        if (network.isObject()) {
            var safeNetwork = objectMapper.createObjectNode();
            safeNetwork.set("criticalBlocked", safeBlocked(network.path("criticalBlocked")));
            safeNetwork.set("optionalBlocked", safeBlocked(network.path("optionalBlocked")));
            safeNetwork.set("unknownBlocked", safeBlocked(network.path("unknownBlocked")));
            safeNetwork.set("authDependencies", safeBlocked(network.path("authDependencies")));
            details.set("network", safeNetwork);
        }
        JsonNode handoff = payload.path("handoff");
        if (handoff.isObject()) {
            var safeHandoff = objectMapper.createObjectNode();
            if (handoff.path("required").isBoolean()) safeHandoff.put("required", handoff.path("required").asBoolean());
            if (handoff.path("mode").isTextual()) safeHandoff.put("mode", handoff.path("mode").asText(""));
            String reason = normalizeHandoffCategory(handoff);
            if (reason.isBlank() && handoff.path("reason").isTextual()) reason = safeDiagnosticText(handoff.path("reason").asText(""));
            if (!reason.isBlank()) safeHandoff.put("reason", reason);
            if (handoff.path("resumeSupported").isBoolean()) safeHandoff.put("resumeSupported", handoff.path("resumeSupported").asBoolean());
            if (handoff.path("handoffId").isTextual()) safeHandoff.put("handoffId", safeDiagnosticText(handoff.path("handoffId").asText("")));
            if (handoff.path("sessionId").isTextual()) safeHandoff.put("sessionId", safeDiagnosticText(handoff.path("sessionId").asText("")));
            details.set("handoff", safeHandoff);
        }
        if (payload.path("retryable").isBoolean()) details.put("retryable", payload.path("retryable").asBoolean());
        // 失败响应同样要带步骤级证据：runner 已在 422 里返回 stepEvidence/pageSnapshots，
        // 这里不转发的话，平台侧的阻塞报告只剩分类，看不到失败那一步的 before/after。
        if (payload.path("stepEvidence").isArray() && payload.path("stepEvidence").size() > 0) {
            details.set("stepEvidence", safeStepEvidence(payload.path("stepEvidence")));
        }
        if (payload.path("pageSnapshots").isArray() && payload.path("pageSnapshots").size() > 0) {
            details.set("pageSnapshots", limitedCopy(payload.path("pageSnapshots"), MAX_EVIDENCE_ENTRIES));
        }
        return details;
    }

    /** 步骤证据按字段白名单复制：只保留 before/after/resolvedTarget/execution 等结构化字段。 */
    private JsonNode safeStepEvidence(JsonNode steps) {
        var result = objectMapper.createArrayNode();
        for (JsonNode step : limitedCopy(steps, MAX_EVIDENCE_ENTRIES)) {
            if (!step.isObject()) continue;
            var safe = objectMapper.createObjectNode();
            for (String field : STEP_EVIDENCE_FIELDS) {
                JsonNode value = step.path(field);
                if (!value.isMissingNode() && !value.isNull()) safe.set(field, value);
            }
            if (!safe.isEmpty()) result.add(safe);
        }
        return result;
    }

    private JsonNode limitedCopy(JsonNode array, int limit) {
        var result = objectMapper.createArrayNode();
        if (array == null || !array.isArray()) return result;
        int index = 0;
        for (JsonNode entry : array) {
            if (index >= limit) break;
            result.add(entry);
            index += 1;
        }
        return result;
    }

    private JsonNode safeBlocked(JsonNode entries) {
        var result = objectMapper.createArrayNode();
        if (!entries.isArray()) return result;
        for (JsonNode entry : entries) {
            var safe = objectMapper.createObjectNode();
            String origin = entry.path("origin").asText("");
            if (!origin.isBlank()) safe.put("origin", safeOrigin(origin));
            String path = entry.path("path").asText("").replaceFirst("[?#].*$", "");
            if (!path.isBlank()) safe.put("path", path);
            for (String field : List.of("resourceType", "criticality", "failureType")) {
                if (entry.path(field).isValueNode()) {
                    String value = entry.path(field).asText("");
                    if ("failureType".equals(field)) value = normalizeCategory(value);
                    safe.put(field, value);
                }
            }
            result.add(safe);
        }
        return result;
    }

    private JsonNode safeRepairTrail(JsonNode entries) {
        var result = objectMapper.createArrayNode();
        if (!entries.isArray()) return result;
        int count = 0;
        for (JsonNode entry : entries) {
            if (count++ >= 5) break;
            if (entry == null || !entry.isObject()) continue;
            var safe = objectMapper.createObjectNode();
            if (entry.path("category").isTextual()) {
                safe.put("category", safeDiagnosticText(entry.path("category").asText("")));
            }
            if (entry.path("round").isNumber()) {
                safe.put("round", entry.path("round").asInt());
            }
            if (entry.path("changedFingerprint").isBoolean()) {
                safe.put("changedFingerprint", entry.path("changedFingerprint").asBoolean());
            }
            if (entry.path("pending").isBoolean()) {
                safe.put("pending", entry.path("pending").asBoolean());
            }
            if (safe.size() > 0) result.add(safe);
        }
        return result;
    }

    private String safeOrigin(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getScheme() == null || uri.getHost() == null) return safe(value).replaceFirst("[/?#].*$", "");
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        }
        catch (Exception ignored) { return value.replaceFirst("[/?#].*$", ""); }
    }

    private String safePageUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getScheme() == null || uri.getHost() == null) return safe(value).replaceFirst("[?#].*$", "");
            String fragment = uri.getRawFragment() == null ? "" : uri.getRawFragment().split("[?#]", 2)[0].replaceAll("[^A-Za-z0-9/_-]", "");
            StringBuilder normalized = new StringBuilder(uri.getScheme()).append("://").append(uri.getHost());
            if (uri.getPort() > 0) normalized.append(':').append(uri.getPort());
            normalized.append(uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath());
            if (!fragment.isBlank()) normalized.append('#').append(fragment);
            return normalized.toString();
        }
        catch (Exception ignored) { return value.replaceFirst("[?].*$", ""); }
    }

    private String normalizeCategory(String value) {
        String category = safe(value).strip();
        if (category.isBlank()) return "";
        return switch (category) {
            case "manual", "manual_required" -> "manual_review_required";
            case "captcha" -> "captcha_required";
            case "network_blocked", "network_blocked_optional" -> "network_blocked_unknown";
            // unsafe_action / locator_persistence / navigation_failed 都是 runner 的稳定分类，
            // 必须原样保留：折叠成 unsupported_dom/wrong_page 会让诊断失真，并触发错误的「需要人工接管」建议。
            // 只有不在稳定表里的历史词（page_error / no_safe_action / unsafe_repair_category）才折叠。
            case "page_error", "no_safe_action", "unsafe_repair_category" -> "unsupported_dom";
            case "round_budget_exhausted", "same_failure_limit", "observation_stagnated" -> "duration_exhausted";
            default -> STABLE_CATEGORIES.contains(category) ? category : "";
        };
    }

    private String normalizeHandoffCategory(JsonNode handoff) {
        if (handoff == null || !handoff.isObject()) return "";
        return normalizeCategory(firstNonBlank(
            handoff.path("reason").asText(""),
            handoff.path("mode").asText("")
        ));
    }

    private String safe(String value) { return value == null ? "" : value; }

    private String safeDiagnosticText(String value) {
        return safeDiagnosticText(value, 96);
    }

    private String safeDiagnosticText(String value, int maxLength) {
        String text = safe(value).replaceAll("[\\p{Cntrl}]", "").replaceAll("\\s+", " ").trim();
        text = text.replaceAll("(?i)\\b(query|search)\\s*[:=]\\s*(?:\"[^\"]*\"|'[^']*'|\\{[^}]*\\}|\\[[^\\]]*\\]|[^\\s,;]+)", "$1=[redacted]");
        text = text.replaceAll("(?i)[\"']?\\b(locator|selector)[\"']?\\s*[:=]\\s*(?:\"[^\"]*\"|'[^']*'|\\{[^}]*\\}|\\[[^\\]]*\\]|[^\\s,;]+)", "$1=[redacted]");
        text = text.replaceAll("(?i)(access[_-]?token|auth[_-]?token|api[_-]?key|secret[_-]?key|token|password|cookie|authorization)\\s*[:=]\\s*(?:\"[^\"]*\"|'[^']*'|\\{[^}]*\\}|\\[[^\\]]*\\]|[^\\s,;]+)", "$1=[redacted]");
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private void ensureEnabled() {
        if (!properties.runner().enabled()) {
            throw new WebAutomationRunnerDisabledException("Web automation runner is not enabled");
        }
    }

    private String pathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
