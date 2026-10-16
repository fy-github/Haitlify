package com.haitlify.aitest.platform.ai.config;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.haitlify.aitest.platform.ai.gateway.AiRoute;
import com.haitlify.aitest.platform.ai.gateway.AiRoutingStrategy;
import com.haitlify.aitest.platform.auth.ProjectAccessService;
import com.haitlify.aitest.platform.security.SecretCipher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Authoring layer for Platform AI run policies (deliverable 1).
 *
 * <p>A default policy exists for each supported task type (requirement analysis, Web/API case
 * generation) and is seeded lazily and idempotently from the currently active provider connection.
 * Policies are authored here; at run creation time a snapshot is copied into
 * {@code ai_analysis_runs.strategy_snapshot_json} (deliverable 2) so in-flight runs are insulated
 * from later policy edits.
 *
 * <p>The service reads the provider connection store ({@code ai_system_configs.provider_config_json})
 * directly via {@link AiSystemConfigRepository}; it never holds API-key values — only the
 * {@code credentialConfigured} flag — so this layer stays free of secrets and free of any
 * {@link AiConfigService} dependency (which would create a constructor cycle, since
 * {@code AiConfigService} depends on this service for seeding/sync).
 */
@Service
@Transactional
public class AiRunPolicyService {

    private static final String DEFAULT_SCOPE = "default";
    private static final int DEFAULT_PER_CALL_TIMEOUT_MS = 120000;
    private static final long DEFAULT_WHOLE_RUN_DEADLINE_MS = 900000L;
    private static final int DEFAULT_RETRY_BUDGET = 2;
    private static final int DEFAULT_MAX_INPUT_CHARS = 0;
    private static final List<String> TASK_TYPES = List.of(
        "requirement_analysis",
        "web_case_generation",
        "api_case_generation"
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AiSystemConfigRepository aiSystemConfigRepository;
    private final SecretCipher secretCipher;
    private final ProjectAccessService projectAccessService;

    AiRunPolicyService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, AiSystemConfigRepository aiSystemConfigRepository, SecretCipher secretCipher, ProjectAccessService projectAccessService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.aiSystemConfigRepository = aiSystemConfigRepository;
        this.secretCipher = secretCipher;
        this.projectAccessService = projectAccessService;
    }

    /**
     * Seeds the three default policies idempotently from the active provider connection.
     * Safe to call repeatedly; existing rows are never overwritten here.
     */
    void ensureDefaultPolicySeeded() {
        ConnectionState connections = loadConnections();
        ConnectionView active = connections.active();
        Instant now = Instant.now();
        for (String taskType : TASK_TYPES) {
            Integer count = jdbcTemplate.queryForObject(
                "select count(*) from ai_run_policies where scope = ? and task_type = ?",
                Integer.class, DEFAULT_SCOPE, taskType
            );
            if (count != null && count > 0) {
                continue;
            }
            List<AiRouteResponse> routes = active == null
                ? List.of()
                : List.of(new AiRouteResponse(0, active.id(), blankToDefault(connections.activeModel(), active.defaultModel())));
            int timeout = active == null ? DEFAULT_PER_CALL_TIMEOUT_MS : positiveOrDefault(active.requestTimeoutMs(), DEFAULT_PER_CALL_TIMEOUT_MS);
            insertPolicy(
                "default-" + taskType,
                DEFAULT_SCOPE,
                taskType,
                defaultPolicyName(taskType),
                true,
                timeout,
                DEFAULT_WHOLE_RUN_DEADLINE_MS,
                DEFAULT_RETRY_BUDGET,
                DEFAULT_MAX_INPUT_CHARS,
                routes,
                now
            );
        }
    }

    AiRunPolicyListResponse listPolicies() {
        ensureDefaultPolicySeeded();
        List<AiRunPolicyResponse> items = jdbcTemplate.queryForList(
                "select * from ai_run_policies order by scope, task_type"
            )
            .stream()
            .map(this::toPolicyResponse)
            .toList();
        return new AiRunPolicyListResponse(items);
    }

    AiRunPolicyResponse getPolicy(String policyId) {
        ensureDefaultPolicySeeded();
        return toPolicyResponse(findPolicyRow(policyId));
    }

    AiRunPolicyResponse savePolicy(String policyId, SaveAiRunPolicyRequest request) {
        ensureDefaultPolicySeeded();
        Map<String, Object> row = findPolicyRow(policyId);
        List<AiRouteResponse> routes = normalizeRoutes(request.routes());
        jdbcTemplate.update("""
            update ai_run_policies
            set name = ?, enabled = ?, per_call_timeout_ms = ?, whole_run_deadline_ms = ?,
                retry_budget = ?, max_input_chars = ?, routes_json = ?, updated_at = ?
            where policy_id = ?
            """,
            blankToDefault(request.name(), strValue(row, "NAME")),
            request.enabled() == null ? boolValue(row, "ENABLED", true) : request.enabled(),
            nonNegativeOrDefault(request.perCallTimeoutMs(), intValue(row, "PER_CALL_TIMEOUT_MS", DEFAULT_PER_CALL_TIMEOUT_MS)),
            nonNegativeOrDefault(request.wholeRunDeadlineMs(), longValue(row, "WHOLE_RUN_DEADLINE_MS", DEFAULT_WHOLE_RUN_DEADLINE_MS)),
            nonNegativeOrDefault(request.retryBudget(), intValue(row, "RETRY_BUDGET", DEFAULT_RETRY_BUDGET)),
            request.maxInputChars() == null ? intValue(row, "MAX_INPUT_CHARS", DEFAULT_MAX_INPUT_CHARS) : Math.max(0, request.maxInputChars()),
            serializeRoutes(routes),
            Timestamp.from(Instant.now()),
            policyId
        );
        return getPolicy(policyId);
    }

    /**
     * Keeps the default policies' primary route (position 0) in sync with the active connection
     * after the connection store changes. User-authored fallbacks at position >= 1 are preserved.
     */
    void syncDefaultPolicyPrimaryRoute(String connectionId, String model, int requestTimeoutMs) {
        ensureDefaultPolicySeeded();
        if (connectionId == null || connectionId.isBlank()) {
            return;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "select policy_id, routes_json from ai_run_policies where scope = ?", DEFAULT_SCOPE
        );
        Instant now = Instant.now();
        for (Map<String, Object> row : rows) {
            List<AiRouteResponse> current = parseRoutes(strValue(row, "ROUTES_JSON"));
            List<AiRouteResponse> next = new ArrayList<>();
            next.add(new AiRouteResponse(0, connectionId, blankToDefault(model, "")));
            for (AiRouteResponse route : current) {
                if (route.position() >= 1) {
                    next.add(route);
                }
            }
            jdbcTemplate.update(
                "update ai_run_policies set routes_json = ?, per_call_timeout_ms = ?, updated_at = ? where policy_id = ?",
                serializeRoutes(next), requestTimeoutMs, Timestamp.from(now), strValue(row, "POLICY_ID")
            );
        }
    }

    private void insertPolicy(
        String policyId, String scope, String taskType, String name, boolean enabled,
        int perCallTimeoutMs, long wholeRunDeadlineMs, int retryBudget, int maxInputChars,
        List<AiRouteResponse> routes, Instant now
    ) {
        jdbcTemplate.update("""
            insert into ai_run_policies(
              policy_id, scope, task_type, name, enabled, per_call_timeout_ms, whole_run_deadline_ms,
              retry_budget, max_input_chars, routes_json, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            policyId, scope, taskType, name, enabled, perCallTimeoutMs, wholeRunDeadlineMs,
            retryBudget, maxInputChars, serializeRoutes(routes),
            Timestamp.from(now), Timestamp.from(now)
        );
    }

    private AiRunPolicyResponse toPolicyResponse(Map<String, Object> row) {
        List<AiRouteResponse> routes = parseRoutes(strValue(row, "ROUTES_JSON"));
        return new AiRunPolicyResponse(
            strValue(row, "POLICY_ID"),
            strValue(row, "SCOPE"),
            strValue(row, "TASK_TYPE"),
            strValue(row, "NAME"),
            boolValue(row, "ENABLED", true),
            intValue(row, "PER_CALL_TIMEOUT_MS", DEFAULT_PER_CALL_TIMEOUT_MS),
            longValue(row, "WHOLE_RUN_DEADLINE_MS", DEFAULT_WHOLE_RUN_DEADLINE_MS),
            intValue(row, "RETRY_BUDGET", DEFAULT_RETRY_BUDGET),
            intValue(row, "MAX_INPUT_CHARS", DEFAULT_MAX_INPUT_CHARS),
            routes,
            validate(routes),
            toIso(row.get("UPDATED_AT"))
        );
    }

    private List<AiPolicyWarning> validate(List<AiRouteResponse> routes) {
        ConnectionState connections = loadConnections();
        Map<String, ConnectionView> byId = new LinkedHashMap<>();
        for (ConnectionView connection : connections.connections()) {
            byId.put(connection.id(), connection);
        }
        List<AiPolicyWarning> warnings = new ArrayList<>();
        if (routes.isEmpty()) {
            warnings.add(new AiPolicyWarning("missing_primary_route", "策略尚未配置主路由"));
            return warnings;
        }
        int position = 0;
        for (AiRouteResponse route : routes) {
            position += 1;
            ConnectionView connection = byId.get(route.connectionId());
            if (connection == null) {
                warnings.add(new AiPolicyWarning("unavailable_model", "路由 " + position + " 引用了不存在的模型连接"));
                continue;
            }
            if (!connection.enabled()) {
                warnings.add(new AiPolicyWarning("disabled_provider", "路由 " + position + " 的模型连接「" + connection.name() + "」已禁用"));
            }
            if (!connection.credentialConfigured() && !isLocalProvider(connection.providerType())) {
                warnings.add(new AiPolicyWarning("missing_credential", "路由 " + position + " 的模型连接「" + connection.name() + "」缺少凭据"));
            }
            if ((route.model() == null || route.model().isBlank()) && (connection.defaultModel() == null || connection.defaultModel().isBlank())) {
                warnings.add(new AiPolicyWarning("missing_model", "路由 " + position + " 未指定模型"));
            }
        }
        return warnings;
    }

    private Map<String, Object> findPolicyRow(String policyId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "select * from ai_run_policies where policy_id = ?", policyId
        );
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("未找到运行策略: " + policyId);
        }
        return rows.get(0);
    }

    private String serializeRoutes(List<AiRouteResponse> routes) {
        ArrayNode array = objectMapper.createArrayNode();
        for (AiRouteResponse route : routes) {
            ObjectNode item = array.addObject();
            item.put("position", route.position());
            item.put("connectionId", route.connectionId());
            item.put("model", route.model());
        }
        return array.toString();
    }

    private List<AiRouteResponse> parseRoutes(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) {
                return List.of();
            }
            List<AiRouteResponse> routes = new ArrayList<>();
            int fallbackPosition = 0;
            for (JsonNode item : root) {
                int position = item.has("position") ? item.path("position").asInt(fallbackPosition) : fallbackPosition;
                routes.add(new AiRouteResponse(position, item.path("connectionId").asText(""), item.path("model").asText("")));
                fallbackPosition += 1;
            }
            return routes;
        } catch (Exception exception) {
            return List.of();
        }
    }

    private List<AiRouteResponse> normalizeRoutes(List<SaveAiRouteRequest> input) {
        if (input == null) {
            return List.of();
        }
        List<AiRouteResponse> routes = new ArrayList<>();
        int position = 0;
        for (SaveAiRouteRequest route : input) {
            if (route == null) {
                continue;
            }
            routes.add(new AiRouteResponse(position, blankToDefault(route.connectionId(), ""), blankToDefault(route.model(), "")));
            position += 1;
        }
        return routes;
    }

    /**
     * Loads the provider connection store from {@code ai_system_configs}. The API key is read so
     * that {@link #resolveStrategy(String)} can materialize executable routes; it is never exposed
     * through policy responses or the visible event stream (validation uses only
     * {@code credentialConfigured}).
     */
    private ConnectionState loadConnections() {
        Optional<AiSystemConfig> optional = aiSystemConfigRepository.findByConfigKey("default");
        if (optional.isEmpty()) {
            return new ConnectionState(List.of(), "", "");
        }
        AiSystemConfig config = optional.get();
        String json = config.getProviderConfigJson();
        if (json == null || json.isBlank()) {
            return new ConnectionState(List.of(legacyConnection(config)), "default", blankToDefault(config.getTextModel(), ""));
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            List<ConnectionView> connections = new ArrayList<>();
            for (JsonNode item : root.path("providers")) {
                String apiKey = secretCipher.decrypt(blankToDefault(item.path("apiKey").asText(""), ""));
                connections.add(new ConnectionView(
                    blankToDefault(item.path("id").asText(""), "provider-" + connections.size()),
                    blankToDefault(item.path("providerId").asText(""), "openai-compatible"),
                    blankToDefault(item.path("name").asText(""), "Provider"),
                    blankToDefault(firstText(item, "baseURL", "baseUrl"), ""),
                    blankToDefault(item.path("defaultModel").asText(""), ""),
                    !item.has("enabled") || item.path("enabled").asBoolean(true),
                    item.path("credentialConfigured").asBoolean(!apiKey.isBlank()),
                    positiveOrDefault(item.path("requestTimeoutMs").asInt(0), DEFAULT_PER_CALL_TIMEOUT_MS),
                    apiKey
                ));
            }
            if (connections.isEmpty()) {
                return new ConnectionState(List.of(legacyConnection(config)), "default", blankToDefault(config.getTextModel(), ""));
            }
            String activeConnectionId = blankToDefault(root.path("activeProviderId").asText(""), connections.get(0).id());
            String activeModel = blankToDefault(root.path("activeModel").asText(""), "");
            return new ConnectionState(connections, activeConnectionId, activeModel);
        } catch (Exception exception) {
            return new ConnectionState(List.of(legacyConnection(config)), "default", blankToDefault(config.getTextModel(), ""));
        }
    }

    private ConnectionView legacyConnection(AiSystemConfig config) {
        return new ConnectionView(
            "default",
            blankToDefault(config.getProviderType(), "openai-compatible"),
            "default",
            blankToDefault(config.getBaseUrl(), ""),
            blankToDefault(config.getTextModel(), ""),
            config.isEnabled(),
            config.isCredentialConfigured(),
            positiveOrDefault(config.getRequestTimeoutMs(), DEFAULT_PER_CALL_TIMEOUT_MS),
            secretCipher.decrypt(blankToDefault(config.getApiKeyCiphertext(), ""))
        );
    }

    /**
     * Resolves the executable routing strategy for a task type. A task inherits the default policy
     * unless it has an enabled per-task override (scope='task_override'), which takes precedence.
     * Routes are materialized with credentials from the connection store; routes whose connection
     * no longer exists are skipped (validation flags them). The strategy snapshot id is provisional
     * here; the run service stamps a per-run id before persisting.
     */
    public AiRoutingStrategy resolveStrategy(String taskType) {
        // T-R5 轮 6：与灰度开关共用同一归一化——解析与写入口径必须一致。
        taskType = com.haitlify.aitest.platform.ai.runtime.AiRuntimeSwitches
            .normalizeIdentifier(taskType);
        ensureDefaultPolicySeeded();
        Map<String, Object> row = effectivePolicyRow(taskType);
        if (row == null) {
            return new AiRoutingStrategy(
                "strategy:" + taskType, taskType, List.of(), DEFAULT_RETRY_BUDGET,
                DEFAULT_PER_CALL_TIMEOUT_MS, DEFAULT_WHOLE_RUN_DEADLINE_MS, null
            );
        }
        return materializeStrategy(taskType, row);
    }

    private void requirePolicyProjectAccess(SaveAiRunPolicyRequest request) {
        // Run-policy DTOs are global and currently have no project scope.
    }

    /** When enabled, {@link #resolveStrategy} prefers it over
     * the default policy for that task type.
     */
    public AiRunPolicyResponse saveTaskOverride(String taskType, SaveAiRunPolicyRequest request) {
        // T-R5 轮 6：覆盖键按归一化后存储——变体/大写拼写不再静默失效、不再留下不可达行。
        taskType = com.haitlify.aitest.platform.ai.runtime.AiRuntimeSwitches
            .normalizeIdentifier(taskType);
        // L5①：写入口目录白名单——归一化值必须落在已知任务类型集合（策略默认集 ∪ QA 助手目录），
        // 未知类型 fail-closed，绝不产生永不可达的覆盖行；目录命中时落库取 canonical 拼写。
        String canonical = canonicalOverrideTaskType(taskType);
        if (canonical == null) {
            throw new IllegalArgumentException("unknown_task_type: " + taskType);
        }
        taskType = canonical;
        ensureDefaultPolicySeeded();
        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
            "select * from ai_run_policies where scope = ? and task_type = ? order by id asc limit 1",
            "task_override", taskType
        );
        String policyId = existing.isEmpty() ? "override-" + taskType : strValue(existing.get(0), "POLICY_ID");
        List<AiRouteResponse> routes = normalizeRoutes(request.routes());
        boolean enabled = request.enabled() == null ? (existing.isEmpty() || boolValue(existing.get(0), "ENABLED", true)) : request.enabled();
        String name = blankToDefault(request.name(), existing.isEmpty() ? defaultPolicyName(taskType) + "（覆盖）" : strValue(existing.get(0), "NAME"));
        int perCallTimeoutMs = nonNegativeOrDefault(request.perCallTimeoutMs(), existing.isEmpty() ? DEFAULT_PER_CALL_TIMEOUT_MS : intValue(existing.get(0), "PER_CALL_TIMEOUT_MS", DEFAULT_PER_CALL_TIMEOUT_MS));
        long wholeRunDeadlineMs = nonNegativeOrDefault(request.wholeRunDeadlineMs(), existing.isEmpty() ? DEFAULT_WHOLE_RUN_DEADLINE_MS : longValue(existing.get(0), "WHOLE_RUN_DEADLINE_MS", DEFAULT_WHOLE_RUN_DEADLINE_MS));
        int retryBudget = nonNegativeOrDefault(request.retryBudget(), existing.isEmpty() ? DEFAULT_RETRY_BUDGET : intValue(existing.get(0), "RETRY_BUDGET", DEFAULT_RETRY_BUDGET));
        int maxInputChars = request.maxInputChars() == null
            ? (existing.isEmpty() ? DEFAULT_MAX_INPUT_CHARS : intValue(existing.get(0), "MAX_INPUT_CHARS", DEFAULT_MAX_INPUT_CHARS))
            : Math.max(0, request.maxInputChars());
        Instant now = Instant.now();
        if (existing.isEmpty()) {
            insertPolicy(policyId, "task_override", taskType, name, enabled, perCallTimeoutMs, wholeRunDeadlineMs, retryBudget, maxInputChars, routes, now);
        } else {
            jdbcTemplate.update("""
                update ai_run_policies
                set name = ?, enabled = ?, per_call_timeout_ms = ?, whole_run_deadline_ms = ?,
                    retry_budget = ?, max_input_chars = ?, routes_json = ?, updated_at = ?
                where policy_id = ?
                """,
                name, enabled, perCallTimeoutMs, wholeRunDeadlineMs, retryBudget, maxInputChars,
                serializeRoutes(routes), Timestamp.from(now), policyId
            );
        }
        return getPolicy(policyId);
    }

    /** 已知任务类型集合：策略默认集 ∪ QA 助手目录；目录命中时返回目录的 canonical 拼写。 */
    private static String canonicalOverrideTaskType(String normalized) {
        if (TASK_TYPES.contains(normalized)) {
            return normalized;
        }
        return com.haitlify.aitest.platform.ai.assistant.AiTaskCatalog.find(normalized)
            .map(com.haitlify.aitest.platform.ai.assistant.AiTaskCatalog.TaskDefinition::taskType)
            .orElse(null);
    }

    /** Removes the optional per-task override so the task inherits its default policy again. */
    public void deleteTaskOverride(String taskType) {
        ensureDefaultPolicySeeded();
        // T-R5 轮 7：删除与保存/解析同入口径——变体拼写删除不得静默无效。
        jdbcTemplate.update(
            "delete from ai_run_policies where scope = ? and task_type = ?",
            "task_override", com.haitlify.aitest.platform.ai.runtime.AiRuntimeSwitches
                .normalizeIdentifier(taskType)
        );
    }

    private Map<String, Object> effectivePolicyRow(String taskType) {
        List<Map<String, Object>> overrides = jdbcTemplate.queryForList(
            "select * from ai_run_policies where scope = ? and task_type = ? and enabled = ? order by id asc limit 1",
            "task_override", taskType, true
        );
        if (!overrides.isEmpty()) {
            return overrides.get(0);
        }
        List<Map<String, Object>> defaults = jdbcTemplate.queryForList(
            "select * from ai_run_policies where scope = ? and task_type = ? order by id asc limit 1",
            DEFAULT_SCOPE, taskType
        );
        return defaults.isEmpty() ? null : defaults.get(0);
    }

    private AiRoutingStrategy materializeStrategy(String taskType, Map<String, Object> row) {
        List<AiRouteResponse> policyRoutes = parseRoutes(strValue(row, "ROUTES_JSON"));
        ConnectionState connections = loadConnections();
        Map<String, ConnectionView> byId = new LinkedHashMap<>();
        for (ConnectionView connection : connections.connections()) {
            byId.put(connection.id(), connection);
        }
        List<AiRoute> routes = new ArrayList<>();
        int perCallTimeoutMs = intValue(row, "PER_CALL_TIMEOUT_MS", DEFAULT_PER_CALL_TIMEOUT_MS);
        for (AiRouteResponse policyRoute : policyRoutes) {
            ConnectionView connection = byId.get(policyRoute.connectionId());
            if (connection == null) {
                continue;
            }
            routes.add(new AiRoute(
                connection.id(),
                connection.name(),
                connection.providerType(),
                connection.baseURL(),
                blankToDefault(policyRoute.model(), connection.defaultModel()),
                connection.apiKey(),
                perCallTimeoutMs
            ));
        }
        return new AiRoutingStrategy(
            "strategy:" + taskType,
            taskType,
            routes,
            intValue(row, "RETRY_BUDGET", DEFAULT_RETRY_BUDGET),
            perCallTimeoutMs,
            longValue(row, "WHOLE_RUN_DEADLINE_MS", DEFAULT_WHOLE_RUN_DEADLINE_MS),
            policySnapshotJson(row, policyRoutes)
        );
    }

    private JsonNode policySnapshotJson(Map<String, Object> row, List<AiRouteResponse> policyRoutes) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("policyId", strValue(row, "POLICY_ID"));
        snapshot.put("taskType", strValue(row, "TASK_TYPE"));
        snapshot.put("name", strValue(row, "NAME"));
        snapshot.put("perCallTimeoutMs", intValue(row, "PER_CALL_TIMEOUT_MS", DEFAULT_PER_CALL_TIMEOUT_MS));
        snapshot.put("wholeRunDeadlineMs", longValue(row, "WHOLE_RUN_DEADLINE_MS", DEFAULT_WHOLE_RUN_DEADLINE_MS));
        snapshot.put("retryBudget", intValue(row, "RETRY_BUDGET", DEFAULT_RETRY_BUDGET));
        ArrayNode routes = snapshot.putArray("routes");
        for (AiRouteResponse route : policyRoutes) {
            ObjectNode item = routes.addObject();
            item.put("position", route.position());
            item.put("connectionId", route.connectionId());
            item.put("model", route.model());
        }
        return snapshot;
    }

    private boolean isLocalProvider(String providerType) {
        return "ollama".equalsIgnoreCase(providerType);
    }

    private String firstText(JsonNode node, String first, String second) {
        String value = node.path(first).asText("");
        return value.isBlank() ? node.path(second).asText("") : value;
    }

    private String defaultPolicyName(String taskType) {
        return switch (taskType) {
            case "requirement_analysis" -> "需求分析默认策略";
            case "web_case_generation" -> "Web 用例生成默认策略";
            case "api_case_generation" -> "接口用例生成默认策略";
            default -> "默认策略";
        };
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private int positiveOrDefault(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private int nonNegativeOrDefault(Integer value, int fallback) {
        return value == null || value < 0 ? fallback : value;
    }

    private int positiveOrDefault(Long value, long fallback) {
        return value == null || value <= 0 ? (int) fallback : value.intValue();
    }

    private long nonNegativeOrDefault(Long value, long fallback) {
        return value == null || value < 0 ? fallback : value;
    }

    private int intValue(Map<String, Object> row, String column, int fallback) {
        Object value = row.get(column);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private long longValue(Map<String, Object> row, String column, long fallback) {
        Object value = row.get(column);
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private boolean boolValue(Map<String, Object> row, String column, boolean fallback) {
        Object value = row.get(column);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return fallback;
    }

    private String strValue(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value == null ? "" : value.toString();
    }

    private String toIso(Object value) {
        if (value == null) return null;
        if (value instanceof Timestamp timestamp) return timestamp.toInstant().toString();
        if (value instanceof Instant instant) return instant.toString();
        return String.valueOf(value);
    }

    private record ConnectionView(
        String id,
        String providerType,
        String name,
        String baseURL,
        String defaultModel,
        boolean enabled,
        boolean credentialConfigured,
        int requestTimeoutMs,
        String apiKey
    ) {
    }

    private record ConnectionState(
        List<ConnectionView> connections,
        String activeConnectionId,
        String activeModel
    ) {
        ConnectionView active() {
            for (ConnectionView connection : connections) {
                if (connection.id().equals(activeConnectionId)) {
                    return connection;
                }
            }
            return connections.isEmpty() ? null : connections.get(0);
        }
    }
}
