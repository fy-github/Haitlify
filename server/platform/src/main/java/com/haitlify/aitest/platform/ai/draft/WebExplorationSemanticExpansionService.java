package com.haitlify.aitest.platform.ai.draft;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import jakarta.annotation.PreDestroy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.haitlify.aitest.platform.ai.call.AiCallRecordPayloads;
import com.haitlify.aitest.platform.ai.gateway.AiGenerationResult;
import com.haitlify.aitest.platform.webautomation.WebAutomationLocatorDiscoveryService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Optional, bounded semantic expansion for browser exploration terms. */
@Service
final class WebExplorationSemanticExpansionService {
    static final String FLAG_KEY = "webExplorationSemanticExpansionEnabled";
    private static final int MAX_PAGE_ALIASES = 8;
    private static final int MAX_ELEMENTS = 8;
    private static final int MAX_STATES = 6;
    private static final int MAX_ACTIONS = 4;
    private static final int MAX_SOURCE_CHARS = 2_400;
    private static final int MAX_TOTAL_CHARS = 8_000;
    private static final int MAX_EXPANSION_CACHE_ENTRIES = 1_024;
    private static final int MAX_REPAIR_REASON_CHARS = 120;
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile("(?i)(authorization|cookie|password|token|apiKey|secretKey)\\s*[:=]\\s*[^\\s,;]+[^\\n]*");
    private static final Pattern URL_QUERY_OR_FRAGMENT = Pattern.compile("[?#].*$");
    private static final Pattern FORBIDDEN_RESPONSE_VALUE = Pattern.compile("(?i)(origin|locator|dsl|credential|password|token|cookie|authorization|https?://\\S+\\?\\S*)");
    private static final Set<String> GENERIC_TERMS = Set.of("项目", "设备", "应用", "系统", "服务", "用户", "首页", "管理", "页面", "列表", "查看", "展示", "显示", "进入");
    private static final Set<String> FORBIDDEN_TERMS = Set.of("确定", "确认", "提交", "保存", "删除", "停用", "启用", "开通", "支付", "发送", "上传");
    private static final Set<String> SAFE_STATE_TERMS = Set.of("已启用", "已停用", "在线", "离线", "可见", "隐藏", "启用状态", "停用状态", "在线状态", "离线状态", "可见状态", "隐藏状态");
    private static final Set<String> SAFE_AI_REPAIR_CATEGORIES = Set.of("wait_ready", "reobserve", "reauthenticate");
    private static final Set<String> SAFE_REPAIR_CATEGORIES = Set.of("wait_ready", "reobserve", "reauthenticate", "menu", "overlay", "tab", "scroll", "paginate");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade taskFacade;
    private final LinkedHashMap<String, ExpansionResult> expandedIntents = new LinkedHashMap<>(128, 0.75f, true);
    private final ConcurrentHashMap<String, InFlightExpansion> inFlightExpansions = new ConcurrentHashMap<>();
    private final Object inFlightStateLock = new Object();
    private final ExecutorService expansionExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "web-exploration-semantic-expansion");
        thread.setDaemon(true);
        return thread;
    });

    WebExplorationSemanticExpansionService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade taskFacade) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.taskFacade = taskFacade;
    }

    WebAutomationLocatorDiscoveryService.TargetIntent expand(WebAutomationLocatorDiscoveryService.TargetIntent intent, List<String> sources) {
        return expandWithDiagnostics(intent, sources).intent();
    }

    ExpansionResult expandWithDiagnostics(WebAutomationLocatorDiscoveryService.TargetIntent intent, List<String> sources) {
        return expandWithDiagnostics(intent, sources, enabled());
    }

    ExpansionResult expandWithDiagnostics(WebAutomationLocatorDiscoveryService.TargetIntent intent, List<String> sources, boolean enabled) {
        if (intent == null) return new ExpansionResult(null, null, "disabled");
        if (!enabled) return new ExpansionResult(intent, null, "disabled");
        WebAutomationLocatorDiscoveryService.TargetIntent safeIntent = sanitizeIntent(intent);
        String sanitized = sanitizeExpansionInput(sources);
        if (sanitized.isBlank()) return new ExpansionResult(intent, null, "config_missing");
        // The point identity is represented by its deterministic intent plus evidence summary.
        // This prevents retries from issuing a second expansion while allowing distinct points
        // that happen to share a page label to expand independently.
        String cacheKey = expansionCacheKey(safeIntent, sanitized);
        ObjectNode prompt = objectMapper.createObjectNode();
        prompt.put("instruction", "Return JSON only with pageAliases, elementTerms, stateTerms, actionTerms, and optional repairSuggestion {category, reason}. repairSuggestion.category must be one of wait_ready, reobserve, reauthenticate. Do not return origin, URL query strings, locators, DSL, credentials, or business-changing actions.");
        ObjectNode semanticIntent = objectMapper.createObjectNode();
        semanticIntent.set("pageAliases", objectMapper.valueToTree(safeIntent.pageAliases()));
        semanticIntent.set("elementTerms", objectMapper.valueToTree(safeIntent.elementTerms()));
        semanticIntent.set("stateTerms", objectMapper.valueToTree(safeIntent.stateTerms()));
        semanticIntent.set("actionTerms", objectMapper.valueToTree(safeIntent.actionTerms()));
        prompt.set("targetIntent", semanticIntent);
        prompt.put("evidenceSummary", sanitized);
        try {
            InFlightExpansion state;
            synchronized (inFlightStateLock) {
                ExpansionResult cached = cachedExpansion(safeIntent, sanitized);
                if (cached != null) return cached;
                // 绝不 join 已完成但未进成功缓存的旧 state：其 whenComplete 回调（remember/remove）可能
                // 仍在本锁上等待，直接 join 会把上一次的失败结果原样返回给本次重试（CI 曾逐字复现：
                // failed attempt 被 retry 复用 → provider_failure）。已完成即重新发起，失败因此可重试。
                state = inFlightExpansions.compute(cacheKey, (key, existing) -> {
                    if (existing == null) {
                        return startExpansion(key, intent, safeIntent, sanitized, prompt);
                    }
                    if (!existing.future.isDone()) {
                        return existing;
                    }
                    // 已完成：成功态直接复用——其成功即将被回调 remember，复用保证并发等待者共享同一次调用
                    // （避免并发用例出现第二次真实调用）；仅「完成且失败」的旧态不允许 join（失败必须可重试）。
                    ExpansionResult completed = existing.future.getNow(null);
                    boolean completedSuccessfully = completed != null && completed.fallbackReason().isEmpty();
                    return completedSuccessfully ? existing : startExpansion(key, intent, safeIntent, sanitized, prompt);
                });
                state.waiterCount.incrementAndGet();
            }
            try {
                return state.future.get(5, TimeUnit.SECONDS);
            } finally {
                releaseWaiter(cacheKey, state);
            }
        } catch (java.util.concurrent.TimeoutException exception) {
            audit(prompt, null, 5_000, exception.getClass().getSimpleName());
            return new ExpansionResult(intent, null, "timeout");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            audit(prompt, null, 0, exception.getClass().getSimpleName());
            return new ExpansionResult(intent, null, "provider_failure");
        } catch (ExecutionException exception) {
            audit(prompt, null, 0, exception.getClass().getSimpleName());
            inFlightExpansions.remove(cacheKey);
            return new ExpansionResult(intent, null, "provider_failure");
        } catch (Exception exception) {
            audit(prompt, null, 0, exception.getClass().getSimpleName());
            inFlightExpansions.remove(cacheKey);
            return new ExpansionResult(intent, null, "provider_failure");
        }
    }

    private InFlightExpansion startExpansion(String cacheKey, WebAutomationLocatorDiscoveryService.TargetIntent intent,
        WebAutomationLocatorDiscoveryService.TargetIntent safeIntent, String sanitized, ObjectNode prompt) {
        InFlightExpansion state = new InFlightExpansion();
        state.workerFuture = expansionExecutor.submit(() -> executeExpansion(state, intent, safeIntent, sanitized, prompt));
        state.future.whenComplete((result, error) -> {
            synchronized (inFlightStateLock) {
                if (result != null && result.fallbackReason().isEmpty()) {
                    rememberExpansion(safeIntent, sanitized, result);
                }
                inFlightExpansions.remove(cacheKey, state);
            }
        });
        return state;
    }

    private void releaseWaiter(String cacheKey, InFlightExpansion state) {
        synchronized (inFlightStateLock) {
            int remaining = state.waiterCount.decrementAndGet();
            if (remaining <= 0 && !state.future.isDone()) {
                state.cancelled.set(true);
                Future<?> worker = state.workerFuture;
                if (worker != null) worker.cancel(true);
                inFlightExpansions.remove(cacheKey, state);
            }
        }
    }

    private void executeExpansion(InFlightExpansion state, WebAutomationLocatorDiscoveryService.TargetIntent intent,
        WebAutomationLocatorDiscoveryService.TargetIntent safeIntent, String sanitized, ObjectNode prompt) {
        long started = System.currentTimeMillis();
        AiGenerationResult result = null;
        try {
            result = taskFacade.generate(new com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade.TaskExecution(
                "web_semantic_expansion", null, "system", null, prompt, 5_000L));
            if (state.cancelled.get()) return;
            if (result == null || !result.success()) {
                audit(prompt, result, System.currentTimeMillis() - started, result == null ? "empty_result" : result.errorMessage());
                if (state.cancelled.get()) return;
                state.future.complete(new ExpansionResult(intent, null, "provider_failure"));
                return;
            }
            JsonNode output;
            try {
                output = objectMapper.readTree(result.content());
            } catch (Exception parseException) {
                audit(prompt, result, System.currentTimeMillis() - started, "malformed_json");
                state.future.complete(new ExpansionResult(intent, null, "malformed_output"));
                return;
            }
            if (!isValidOutput(output)) {
                audit(prompt, result, System.currentTimeMillis() - started, "malformed_json");
                if (state.cancelled.get()) return;
                state.future.complete(new ExpansionResult(intent, null, "malformed_output"));
                return;
            }
            WebAutomationLocatorDiscoveryService.TargetIntent merged = merge(intent, output);
            RepairSuggestion suggestion = repairSuggestion(output.path("repairSuggestion"));
            if (suggestion == null && output.path("repairSuggestion").isObject()) {
                audit(prompt, result, System.currentTimeMillis() - started, "unsafe_repair_suggestion");
                if (state.cancelled.get()) return;
                state.future.complete(new ExpansionResult(merged, null, "unsafe_repair_suggestion"));
                return;
            }
            audit(prompt, result, System.currentTimeMillis() - started, null);
            if (state.cancelled.get()) return;
            state.future.complete(new ExpansionResult(merged, suggestion, ""));
        } catch (IllegalStateException exception) {
            if (state.cancelled.get()) return;
            audit(prompt, result, System.currentTimeMillis() - started, exception.getClass().getSimpleName());
            state.future.complete(new ExpansionResult(intent, null, "config_missing"));
        } catch (Exception exception) {
            if (state.cancelled.get()) return;
            audit(prompt, result, System.currentTimeMillis() - started, exception.getClass().getSimpleName());
            state.future.complete(new ExpansionResult(intent, null, "provider_failure"));
        }
    }

    private RepairSuggestion repairSuggestion(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        String category = node.path("category").asText("").strip();
        if (!SAFE_AI_REPAIR_CATEGORIES.contains(category)) return null;
        String reason = node.path("reason").asText("").strip().replaceAll("[\\r\\n]", " ");
        if (reason.length() > MAX_REPAIR_REASON_CHARS) reason = reason.substring(0, MAX_REPAIR_REASON_CHARS);
        if (reason.isBlank() || containsUnsafeResponseText(reason, false)) return null;
        return new RepairSuggestion(category, reason);
    }

    record RepairSuggestion(String category, String reason) { }
    record ExpansionResult(WebAutomationLocatorDiscoveryService.TargetIntent intent, RepairSuggestion repairSuggestion, String fallbackReason) { }

    private WebAutomationLocatorDiscoveryService.TargetIntent sanitizeIntent(WebAutomationLocatorDiscoveryService.TargetIntent intent) {
        return new WebAutomationLocatorDiscoveryService.TargetIntent(
            sanitizeValues(intent.pageAliases()),
            sanitizeValues(intent.routeHints()),
            sanitizeValues(intent.elementTerms()),
            sanitizeValues(intent.actionTerms()),
            sanitizeValues(intent.stateTerms()),
            sanitizeValues(intent.expectedTexts()),
            sanitizeValues(intent.negativeTerms())
        );
    }

    private List<String> sanitizeValues(List<String> values) {
        if (values == null) return List.of();
        return values.stream().map(this::sanitizeRouteValue).filter(value -> !value.isBlank()).toList();
    }

    private String sanitizeRouteValue(String value) {
        String candidate = value == null ? "" : value.strip();
        if (candidate.isBlank()) return "";
        if (candidate.startsWith("#")) return stripRouteQuery(candidate);
        try {
            java.net.URI uri = java.net.URI.create(candidate);
            if (uri.getScheme() != null && uri.getHost() != null) {
                String fragment = uri.getRawFragment();
                if (fragment != null && !fragment.isBlank()) {
                    String sanitizedFragment = stripRouteQuery("#" + fragment);
                    if (!sanitizedFragment.isBlank()) return sanitizedFragment;
                }
                String path = uri.getRawPath();
                return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "")
                    + (path == null || path.isBlank() ? "/" : path);
            }
        } catch (RuntimeException ignored) {
            // Fall through to the conservative delimiter strip below.
        }
        return stripRouteQuery(candidate);
    }

    private boolean isValidOutput(JsonNode output) {
        if (output == null || !output.isObject()) return false;
        Set<String> allowedFields = Set.of("pageAliases", "elementTerms", "stateTerms", "actionTerms", "repairSuggestion");
        for (Map.Entry<String, JsonNode> field : output.properties()) {
            String fieldName = field.getKey();
            if (!allowedFields.contains(fieldName)) return false;
        }
        for (String fieldName : List.of("pageAliases", "elementTerms", "stateTerms", "actionTerms")) {
            if (output.has(fieldName) && !validateArrayField(output.path(fieldName), "stateTerms".equals(fieldName))) return false;
        }
        return validateRepairSuggestionShape(output.path("repairSuggestion"));
    }

    private boolean validateArrayField(JsonNode node, boolean allowStateTerms) {
        if (node == null || node.isMissingNode() || node.isNull()) return true;
        if (!node.isArray()) return false;
        for (JsonNode item : node) {
            String value = item.asText("").strip();
            if (value.isBlank() || value.length() > 80 || containsUnsafeResponseText(value, allowStateTerms)) return false;
        }
        return true;
    }

    private boolean validateRepairSuggestionShape(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return true;
        if (!node.isObject()) return false;
        Set<String> allowed = Set.of("category", "reason");
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            if (!allowed.contains(entry.getKey())) return false;
        }
        return true;
    }

    private String stripRouteQuery(String value) {
        return value.replaceFirst("\\?.*$", "");
    }

    private boolean containsUnsafeResponseText(String value, boolean allowStateTerms) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) return true;
        if (FORBIDDEN_RESPONSE_VALUE.matcher(normalized).find()) return true;
        String lowered = normalized.toLowerCase(Locale.ROOT);
        if (allowStateTerms && isSafeStateTerm(lowered)) return false;
        if (FORBIDDEN_TERMS.stream().anyMatch(term -> lowered.contains(term.toLowerCase(Locale.ROOT)))) return true;
        if (allowStateTerms && looksLikeStateTerm(lowered)) {
            if (lowered.contains("business change") || lowered.contains("execute javascript") || lowered.contains("open external")) return true;
            return false;
        }
        if (lowered.contains("business change") || lowered.contains("execute javascript") || lowered.contains("open external")) return true;
        return false;
    }

    private boolean looksLikeStateTerm(String lowered) {
        return lowered.startsWith("已")
            || lowered.startsWith("未")
            || lowered.startsWith("待")
            || lowered.startsWith("在")
            || lowered.startsWith("处于")
            || lowered.endsWith("中")
            || lowered.endsWith("态")
            || lowered.contains("状态");
    }

    int expansionCacheSize() {
        synchronized (expandedIntents) {
            return expandedIntents.size();
        }
    }

    private ExpansionResult cachedExpansion(WebAutomationLocatorDiscoveryService.TargetIntent intent, String sanitized) {
        String key = expansionCacheKey(intent, sanitized);
        synchronized (expandedIntents) {
            return expandedIntents.get(key);
        }
    }

    private boolean rememberExpansion(WebAutomationLocatorDiscoveryService.TargetIntent intent, String sanitized, ExpansionResult result) {
        String key = expansionCacheKey(intent, sanitized);
        synchronized (expandedIntents) {
            if (expandedIntents.containsKey(key)) {
                expandedIntents.get(key);
                return false;
            }
            if (expandedIntents.size() >= MAX_EXPANSION_CACHE_ENTRIES) {
                expandedIntents.remove(expandedIntents.keySet().iterator().next());
            }
            expandedIntents.put(key, result);
            return true;
        }
    }

    private boolean isSafeStateTerm(String lowered) {
        return SAFE_STATE_TERMS.stream().map(term -> term.toLowerCase(Locale.ROOT)).anyMatch(lowered::equals);
    }

    private String expansionCacheKey(WebAutomationLocatorDiscoveryService.TargetIntent intent, String sanitized) {
        StringBuilder key = new StringBuilder(256);
        appendKeyPart(key, intent.pageAliases());
        appendKeyPart(key, intent.routeHints());
        appendKeyPart(key, intent.elementTerms());
        appendKeyPart(key, intent.actionTerms());
        appendKeyPart(key, intent.stateTerms());
        appendKeyPart(key, intent.expectedTexts());
        appendKeyPart(key, intent.negativeTerms());
        appendScalarKey(key, sanitized);
        return key.toString();
    }

    private void appendKeyPart(StringBuilder key, List<String> values) {
        key.append('|');
        if (values == null) {
            key.append("-1");
            return;
        }
        key.append(values.size());
        for (String value : values) {
            key.append(':');
            if (value == null) {
                key.append("-1");
            } else {
                key.append(value.length()).append('#').append(value);
            }
        }
    }

    private void appendScalarKey(StringBuilder key, String value) {
        key.append('|');
        if (value == null) {
            key.append("-1");
            return;
        }
        key.append(value.length()).append('#').append(value);
    }

    String sanitizeExpansionInput(List<String> sources) {
        StringBuilder combined = new StringBuilder();
        if (sources == null) return "";
        for (String source : sources) {
            if (source == null || combined.length() >= MAX_TOTAL_CHARS) continue;
            String value = URL_QUERY_OR_FRAGMENT.matcher(source).replaceFirst("");
            value = SENSITIVE_ASSIGNMENT.matcher(value).replaceAll("[REDACTED]");
            value = value.replaceAll("(?i)(?:raw\\s*dom|screenshot|headers?|cookies?)\\s*[:=].*", "[REDACTED]");
            value = value.strip();
            if (value.length() > MAX_SOURCE_CHARS) value = value.substring(0, MAX_SOURCE_CHARS);
            int remaining = MAX_TOTAL_CHARS - combined.length();
            if (remaining <= 0) break;
            if (value.length() > remaining) value = value.substring(0, remaining);
            if (!value.isBlank()) {
                if (combined.length() > 0) combined.append('\n');
                combined.append(value);
            }
        }
        return combined.toString();
    }

    private boolean enabled() {
        String json = null;
        try {
            json = jdbcTemplate.queryForObject("select provider_config_json from ai_system_configs where config_key = 'default'", String.class);
            JsonNode config = json == null || json.isBlank() ? null : objectMapper.readTree(json);
            if (config != null && config.path(FLAG_KEY).asBoolean(false)) return true;
        } catch (Exception ignored) {
            // Fall through to the optional standalone flag row for older schemas.
        }
        try {
            Boolean standalone = jdbcTemplate.queryForObject("select enabled from ai_system_configs where config_key = ?", Boolean.class, FLAG_KEY);
            return Boolean.TRUE.equals(standalone);
        } catch (Exception ignored) {
            return false;
        }
    }

    private WebAutomationLocatorDiscoveryService.TargetIntent merge(WebAutomationLocatorDiscoveryService.TargetIntent base, JsonNode output) {
        return new WebAutomationLocatorDiscoveryService.TargetIntent(
            bounded(base.pageAliases(), output.path("pageAliases"), MAX_PAGE_ALIASES, forbiddenSet(base), false),
            base.routeHints(),
            bounded(base.elementTerms(), output.path("elementTerms"), MAX_ELEMENTS, forbiddenSet(base), false),
            bounded(base.actionTerms(), output.path("actionTerms"), MAX_ACTIONS, forbiddenSet(base), false),
            bounded(base.stateTerms(), output.path("stateTerms"), MAX_STATES, forbiddenSet(base), true),
            base.expectedTexts(),
            base.negativeTerms() == null ? List.of() : base.negativeTerms()
        );
    }

    private Set<String> forbiddenSet(WebAutomationLocatorDiscoveryService.TargetIntent base) {
        Set<String> blocked = new LinkedHashSet<>(GENERIC_TERMS);
        blocked.addAll(FORBIDDEN_TERMS);
        if (base.negativeTerms() != null) base.negativeTerms().forEach(value -> blocked.add(normalize(value)));
        return blocked;
    }

    private List<String> bounded(List<String> existing, JsonNode additions, int max, Set<String> blocked, boolean allowStateTerms) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (existing != null) values.addAll(existing);
        if (additions != null && additions.isArray()) {
            for (JsonNode node : additions) {
                String value = node.asText("").strip();
                String normalized = normalize(value);
                if (normalized.length() < 2 || normalized.length() > 80 || blocked.contains(normalized) || containsUnsafeResponseText(value, allowStateTerms)) continue;
                values.add(value);
                if (values.size() >= max) break;
            }
        }
        return List.copyOf(values.stream().limit(max).toList());
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "").strip();
    }

    private void audit(ObjectNode prompt, AiGenerationResult result, long durationMs, String error) {
        try {
            jdbcTemplate.update("""
                insert into ai_call_records(record_id, run_id, project_id, provider_type, model_name, config_snapshot_id, agent_id,
                  skill_ids_json, rule_version, modality, request_id, status, error_code, error_message, duration_ms,
                  token_usage_input, token_usage_output, estimated_cost, input_summary_json, output_summary_json, raw_payload_json,
                  actor_id, actor_name, created_at, updated_at)
                values (?, null, null, ?, ?, 'default', 'web-exploration-semantic-expansion', '[]', 'system-v1', 'text', ?, ?, '', ?, ?, ?, ?, 0, ?, ?, ?, 'system', 'System', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, "call-" + java.util.UUID.randomUUID(), "", "", result == null ? "" : result.requestId(),
                result != null && result.success() ? "succeeded" : "failed", error == null ? "" : error, durationMs,
                result == null ? 0 : result.inputTokens(), result == null ? 0 : result.outputTokens(),
                AiCallRecordPayloads.compactJson(objectMapper, prompt), "{\"redacted\":true}", "{\"redacted\":true}");
        } catch (Exception ignored) {
            // Exploration enhancement is best effort; auditing must not block browser discovery.
        }
    }

    private static final class InFlightExpansion {
        private final CompletableFuture<ExpansionResult> future = new CompletableFuture<>();
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicInteger waiterCount = new AtomicInteger();
        private volatile Future<?> workerFuture;
    }

    @PreDestroy
    void shutdown() {
        expansionExecutor.shutdownNow();
    }
}
