package com.haitlify.aitest.platform.ai.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.haitlify.aitest.platform.ai.runtime.AiRuntimeModels.Capability;
import com.haitlify.aitest.platform.ai.runtime.AiRuntimeModels.Protocol;
import com.haitlify.aitest.platform.ai.runtime.transport.AiProtocolTransportClient;
import com.haitlify.aitest.platform.ai.runtime.transport.AiTransportRequest;
import com.haitlify.aitest.platform.ai.runtime.transport.AiTransportResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * T04 生成级与业务级能力预检：五类探针（reachability / text / task-json / image / read-only-tool），
 * 每类返回 pass/fail/unknown/unsupported 与 diagnosticsId；报告绑定 profile/config hash 并带可配置 TTL，
 * 档案更新（新版本行/新 config hash）或 TTL 过期即视为需要重新验证（不自动换 URL 发请求、不自动保存/启用）。
 *
 * 边界（如实记录，不粉饰）：契约 transport（T06 接好前的 Runtime 通道）目前只有文本请求路径，
 * image/read-only-tool 两类探针在 T06 接通相应 transport 前以 UNSUPPORTED 记入，绝不冒充 pass；
 * reachability 探针只做 GET /models 类可用性检查，不作为任何生成证据。
 */
@Service
public class AiCapabilityCheckService {

    public static final String STATUS_PASS = "pass";
    public static final String STATUS_FAIL = "fail";
    public static final String STATUS_UNKNOWN = "unknown";
    public static final String STATUS_UNSUPPORTED = "unsupported";

    /** 仓库脱敏固定样例：两条测试点必须各带一个 evidenceId，用于业务级（task-json）探针。 */
    static final String FIXTURE_REQUIREMENT =
        "需求：在「设备分组」页面，用户可为分组重命名；重命名成功后列表显示新名称。";
    static final List<String> FIXTURE_EVIDENCE_IDS = List.of("probe-evidence-1", "probe-evidence-2");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AiProtocolTransportClient transportClient;
    private final com.haitlify.aitest.platform.security.SecretCipher secretCipher;
    private final HttpClient httpClient;
    private final long ttlSeconds;

    @org.springframework.beans.factory.annotation.Autowired
    public AiCapabilityCheckService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        AiProtocolTransportClient transportClient,
        com.haitlify.aitest.platform.security.SecretCipher secretCipher,
        @Value("${app.ai.capability-check.ttl-seconds:86400}") long ttlSeconds
    ) {
        this(jdbcTemplate, objectMapper, transportClient, secretCipher, HttpClient.newHttpClient(), ttlSeconds);
    }

    AiCapabilityCheckService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        AiProtocolTransportClient transportClient,
        com.haitlify.aitest.platform.security.SecretCipher secretCipher,
        HttpClient httpClient,
        long ttlSeconds
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.transportClient = transportClient;
        this.secretCipher = secretCipher;
        this.httpClient = httpClient;
        this.ttlSeconds = ttlSeconds;
    }

    public record ProbeResult(String probe, String status, String diagnosticsId, String detail) {
    }

    public record CapabilityCheckReport(
        String profileId,
        int profileVersion,
        String configHash,
        Instant checkedAt,
        long ttlSeconds,
        String overall,
        List<ProbeResult> probes
    ) {
        /** 报告当前是否仍然有效：profile/config hash 必须一致且在 TTL 内。 */
        public boolean isFreshAt(Instant now, String currentConfigHash) {
            if (configHash == null || currentConfigHash == null || !configHash.equals(currentConfigHash)
                    || checkedAt == null || ttlSeconds <= 0) {
                return false;
            }
            // F2b：存储来的 ttlSeconds 不可信——绝不调用 plusSeconds（病态值会 long overflow）；
            // 改为先算已过秒数与 TTL 比较（Duration 自带饱和处理）。
            long elapsedSeconds = java.time.Duration.between(checkedAt, now == null ? Instant.now() : now).getSeconds();
            return elapsedSeconds <= ttlSeconds;
        }
    }

    /**
     * 对指定 profile 行执行五类探针并（可选）把报告写入 ai_model_profiles.verification_json。
     * profile 行不存在时抛 IllegalArgumentException——不猜测、不用默认 URL 发请求。
     */
    public CapabilityCheckReport checkProfile(String profileId, int profileVersion, boolean persist) {
        Map<String, Object> row = loadProfileRow(profileId, profileVersion);
        String baseUrl = (String) row.get("base_url");
        String protocolRaw = (String) row.get("protocol");
        String credentialRef = (String) row.get("credential_ref");
        String modelId = (String) row.get("model_id");
        String capabilitiesJson = (String) row.getOrDefault("capabilities_json", "{}");
        String parameterPolicyJson = (String) row.getOrDefault("parameter_policy_json", "{}");

        List<Capability> capabilities = parseCapabilities(capabilitiesJson);
        String configHash = configHash(baseUrl, protocolRaw, modelId, capabilitiesJson, parameterPolicyJson, credentialRef);
        Protocol protocol = "responses".equalsIgnoreCase(protocolRaw)
            ? Protocol.RESPONSES : Protocol.CHAT_COMPLETIONS;

        // R2-3：与 M1 同一原则——credential_ref 是引用而非密钥；不可材料化时拒绝检查，
        // 绝不把占位符/别名当 Bearer 发往真实端点。
        String materializedCredential = materializeCredential(credentialRef);
        if (materializedCredential == null) {
            throw new IllegalArgumentException(
                "credential unresolved for profile: " + profileId + "@" + profileVersion);
        }

        List<ProbeResult> probes = new ArrayList<>();
        probes.add(reachabilityProbe(baseUrl, materializedCredential));
        probes.add(textProbe(protocol, baseUrl, modelId, materializedCredential));
        probes.add(taskJsonProbe(protocol, baseUrl, modelId, materializedCredential, capabilities));
        probes.add(imageProbe(capabilities));
        probes.add(readOnlyToolProbe(capabilities, protocol));
        probes.add(streamingProbe(capabilities));

        String overall = overall(probes);
        CapabilityCheckReport report = new CapabilityCheckReport(
            profileId, profileVersion, configHash, Instant.now(), ttlSeconds, overall, List.copyOf(probes));
        if (persist) {
            persistReport(report);
        }
        return report;
    }

    /** 当前 config hash（对外暴露，用于判定已存报告的陈旧性）；凭据引用（header 材料）在覆盖范围内。 */
    public String currentConfigHash(String profileId, int profileVersion) {
        Map<String, Object> row = loadProfileRow(profileId, profileVersion);
        return configHash(
            (String) row.get("base_url"),
            (String) row.get("protocol"),
            (String) row.get("model_id"),
            (String) row.getOrDefault("capabilities_json", "{}"),
            (String) row.getOrDefault("parameter_policy_json", "{}"),
            (String) row.get("credential_ref")
        );
    }

    public record StoredCapabilityCheck(CapabilityCheckReport report, boolean fresh, String currentConfigHash) {
    }

    /** 读取已存报告（verification_json）并对照当前 config hash/TTL 判定是否新鲜；坏数据不猜测，按“需重新验证”处理。 */
    public StoredCapabilityCheck storedReport(String profileId, int profileVersion) {
        Map<String, Object> row = loadProfileRow(profileId, profileVersion);
        String current = configHash(
            (String) row.get("base_url"),
            (String) row.get("protocol"),
            (String) row.get("model_id"),
            (String) row.getOrDefault("capabilities_json", "{}"),
            (String) row.getOrDefault("parameter_policy_json", "{}"),
            (String) row.get("credential_ref")
        );
        Object raw = row.get("verification_json");
        CapabilityCheckReport report = null;
        if (raw != null && !raw.toString().isBlank() && !"{}".equals(raw.toString().trim())) {
            try {
                report = objectMapper.readValue(raw.toString(), CapabilityCheckReport.class);
            } catch (Exception malformed) {
                report = null;
            }
        }
        // F2：结构性坏数据（可解析但必需字段缺失，如只有 profileId）同样按“需要重新验证”处理，
        // 绝不让 null 字段进入 isFreshAt 造成 500。
        if (report != null && (report.profileId() == null || report.profileId().isBlank()
                || report.configHash() == null || report.configHash().isBlank()
                || report.checkedAt() == null || report.probes() == null)) {
            report = null;
        }
        boolean fresh = report != null && report.isFreshAt(Instant.now(), current);
        return new StoredCapabilityCheck(report, fresh, current);
    }

    // ------------------------------------------------------------------ 探针实现

    private ProbeResult reachabilityProbe(String baseUrl, String credentialRef) {
        String diagnosticsId = diagnosticsId("reachability", baseUrl + "|" + credentialRef);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(trimTrailingSlash(baseUrl) + "/models"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + credentialRef)
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                // 仅表示端点可达/模型可列出；绝不作为生成可用证据。
                return new ProbeResult("reachability", STATUS_PASS, diagnosticsId, "endpoint reachable, http 200");
            }
            return new ProbeResult("reachability", STATUS_FAIL, diagnosticsId, "http " + response.statusCode());
        } catch (Exception exception) {
            return new ProbeResult("reachability", STATUS_UNKNOWN, diagnosticsId,
                "endpoint unreachable: " + exception.getClass().getSimpleName());
        }
    }

    private ProbeResult textProbe(Protocol protocol, String baseUrl, String modelId, String credentialRef) {
        String diagnosticsId = diagnosticsId("text", baseUrl + "|" + modelId);
        try {
            AiTransportResult result = transportClient.execute(new AiTransportRequest(
                protocol, baseUrl, modelId, credentialRef,
                "Reply with the single word: ok", List.of(Capability.TEXT), Map.of(), Duration.ofSeconds(20)));
            if (result.httpStatus() < 200 || result.httpStatus() >= 300) {
                return new ProbeResult("text", STATUS_FAIL, diagnosticsId, "http " + result.httpStatus());
            }
            // HTTP 200 + 空内容/仅空白绝不通过：生成级证据必须是非空可用文本 + provider request id。
            if (!result.response().hasUsableText()) {
                return new ProbeResult("text", STATUS_FAIL, diagnosticsId, "http 200 without usable text");
            }
            String requestId = result.response().providerRequestId();
            if (requestId == null || requestId.isBlank()) {
                return new ProbeResult("text", STATUS_FAIL, diagnosticsId, "usable text without provider request id");
            }
            return new ProbeResult("text", STATUS_PASS, diagnosticsId, "provider_request_id=" + requestId);
        } catch (Exception exception) {
            return new ProbeResult("text", STATUS_UNKNOWN, diagnosticsId,
                "transport failure: " + exception.getClass().getSimpleName());
        }
    }

    private ProbeResult taskJsonProbe(Protocol protocol, String baseUrl, String modelId, String credentialRef,
                                      List<Capability> capabilities) {
        String diagnosticsId = diagnosticsId("task-json", baseUrl + "|" + modelId);
        boolean nativeSchema = capabilities.contains(Capability.JSON_SCHEMA);
        boolean jsonMode = capabilities.contains(Capability.JSON_OBJECT);
        String prompt = "Return JSON only: {\"items\":[{title, steps, expectedResult, evidenceIds}, ...]} with exactly two test points; "
            + "evidenceIds must come from " + FIXTURE_EVIDENCE_IDS + ". " + FIXTURE_REQUIREMENT;
        try {
            // F3：声明 JSON_SCHEMA 时必须真的把契约 schema 交给 transport（response_format/text.format），
            // 而不是仅按声明打标；仅 JSON_OBJECT 时走 json_mode；否则 prompt 回退。
            List<Capability> requestCapabilities;
            tools.jackson.databind.node.ObjectNode probeSchema = null;
            if (nativeSchema) {
                requestCapabilities = List.of(Capability.TEXT, Capability.JSON_SCHEMA);
                probeSchema = fixtureProbeSchema();
            } else if (jsonMode) {
                requestCapabilities = List.of(Capability.TEXT, Capability.JSON_OBJECT);
            } else {
                requestCapabilities = List.of(Capability.TEXT);
            }
            AiTransportResult result = transportClient.execute(new AiTransportRequest(
                protocol, baseUrl, modelId, credentialRef, prompt,
                requestCapabilities, Map.of(), Duration.ofSeconds(30), probeSchema));
            String mode = nativeSchema ? "native_schema" : (jsonMode ? "json_mode" : "prompt_fallback");
            if (result.httpStatus() < 200 || result.httpStatus() >= 300) {
                return new ProbeResult("task-json", STATUS_FAIL, diagnosticsId, "http " + result.httpStatus());
            }
            if (!result.response().hasUsableText()) {
                return new ProbeResult("task-json", STATUS_FAIL, diagnosticsId, "http 200 without usable text");
            }
            JsonNode output;
            try {
                output = objectMapper.readTree(result.response().text());
            } catch (Exception parseFailure) {
                // 有响应体但不是合法 JSON：这是真实的负面信号，归 FAIL（不是 unknown）。
                return new ProbeResult("task-json", STATUS_FAIL, diagnosticsId, mode + ": response body is not valid JSON");
            }
            String validation = validateFixtureOutput(output);
            if (!validation.isEmpty()) {
                return new ProbeResult("task-json", STATUS_FAIL, diagnosticsId, mode + ": " + validation);
            }
            String requestId = result.response().providerRequestId();
            return new ProbeResult("task-json", STATUS_PASS, diagnosticsId,
                mode + ": 2 test points with evidenceIds validated; provider_request_id=" + requestId);
        } catch (Exception exception) {
            return new ProbeResult("task-json", STATUS_UNKNOWN, diagnosticsId,
                "transport failure: " + exception.getClass().getSimpleName());
        }
    }

    /** 业务探针下发的原生 schema（strict 方言安全：仅 type/required/properties/items/minItems/maxItems）。 */
    private tools.jackson.databind.node.ObjectNode fixtureProbeSchema() {
        var schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.putArray("required").add("items");
        var items = schema.putObject("properties").putObject("items");
        items.put("type", "array");
        items.put("minItems", 2);
        items.put("maxItems", 2);
        var item = items.putObject("items");
        item.put("type", "object");
        item.putArray("required").add("title").add("steps").add("expectedResult").add("evidenceIds");
        var props = item.putObject("properties");
        props.putObject("title").put("type", "string");
        props.putObject("steps").put("type", "string");
        props.putObject("expectedResult").put("type", "string");
        props.putObject("evidenceIds").put("type", "array").putObject("items").put("type", "string");
        return schema;
    }

    /** 业务探针验收：恰两条测试点、题目/步骤/期望非空、evidenceId 必须来自固定样例集合。 */
    private String validateFixtureOutput(JsonNode output) {
        JsonNode items = output == null ? null : output.path("items");
        if (items == null || !items.isArray() || items.size() != 2) {
            return "expected exactly two test points";
        }
        for (JsonNode item : items) {
            if (item.path("title").asText("").isBlank()) return "missing title";
            JsonNode steps = item.path("steps");
            if (steps.isTextual() ? steps.asText().isBlank() : (!steps.isArray() || steps.isEmpty())) {
                return "missing steps";
            }
            if (item.path("expectedResult").asText("").isBlank()) return "missing expectedResult";
            JsonNode evidenceIds = item.path("evidenceIds");
            if (!evidenceIds.isArray() || evidenceIds.isEmpty()) return "missing evidenceIds";
            for (JsonNode evidenceId : evidenceIds) {
                if (!FIXTURE_EVIDENCE_IDS.contains(evidenceId.asText())) {
                    return "unknown evidenceId " + evidenceId.asText();
                }
            }
        }
        return "";
    }

    /**
     * T02 流式立场（显式化）：消费端（事件顺序/断流/取消/usage 明细）尚未实现——
     * 声明 streaming 的档案一律 UNKNOWN（未验证），绝不显示为 pass/supported；
     * 未声明则为 UNSUPPORTED。非流式探针的结果不能冒充流式验收。
     */
    private ProbeResult streamingProbe(List<Capability> capabilities) {
        String diagnosticsId = diagnosticsId("streaming", capabilities.toString());
        if (!capabilities.contains(Capability.STREAMING)) {
            return new ProbeResult("streaming", STATUS_UNSUPPORTED, diagnosticsId,
                "profile does not declare streaming capability");
        }
        return new ProbeResult("streaming", STATUS_UNKNOWN, diagnosticsId,
            "streaming declared but the streaming consumer is not implemented (T02): not verified");
    }

    private ProbeResult imageProbe(List<Capability> capabilities) {
        String diagnosticsId = diagnosticsId("image", capabilities.toString());
        if (!capabilities.contains(Capability.IMAGE_INPUT)) {
            return new ProbeResult("image", STATUS_UNSUPPORTED, diagnosticsId, "profile does not declare vision capability");
        }
        // F4：声明了但契约 transport（T06 前）没有图像请求路径 ⇒ 未验证（unknown），不是 unsupported。
        return new ProbeResult("image", STATUS_UNKNOWN, diagnosticsId,
            "vision declared but image transport is not wired until the T06 runtime: not verified");
    }

    private ProbeResult readOnlyToolProbe(List<Capability> capabilities, Protocol protocol) {
        String diagnosticsId = diagnosticsId("read-only-tool", capabilities.toString());
        if (!capabilities.contains(Capability.TOOL_CALLS)) {
            return new ProbeResult("read-only-tool", STATUS_UNSUPPORTED, diagnosticsId, "profile does not declare tools capability");
        }
        // F4：同上——声明但未接通记为未验证（unknown）。
        return new ProbeResult("read-only-tool", STATUS_UNKNOWN, diagnosticsId,
            "tools declared but tool transport is not wired until the T06 runtime: not verified");
    }

    // ------------------------------------------------------------------ 持久化与工具

    private void persistReport(CapabilityCheckReport report) {
        try {
            String json = objectMapper.writeValueAsString(report);
            int updated = jdbcTemplate.update(
                "update ai_model_profiles set verification_json = ?, updated_at = ? where profile_id = ? and version = ?",
                json, java.sql.Timestamp.from(Instant.now()), report.profileId(), report.profileVersion());
            if (updated != 1) {
                // F6：影响行数必须为 1（loadProfileRow 已确认存在；0 行意味着并发删除，不能静默报成功）。
                throw new IllegalStateException("capability check report not persisted: profile row not found");
            }
        } catch (IllegalStateException state) {
            throw state;
        } catch (Exception exception) {
            throw new IllegalStateException("cannot persist capability check report", exception);
        }
    }

    private Map<String, Object> loadProfileRow(String profileId, int profileVersion) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "select p.profile_id, p.version, p.connection_id, p.model_id, p.capabilities_json, p.parameter_policy_json, "
                + "p.verification_json, c.base_url, c.protocol, c.credential_ref "
                + "from ai_model_profiles p join ai_connections c on c.connection_id = p.connection_id "
                + "and c.version = (select max(c2.version) from ai_connections c2 where c2.connection_id = p.connection_id) "
                + "where p.profile_id = ? and p.version = ?",
            profileId, profileVersion);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("profile not registered: " + profileId + "@" + profileVersion);
        }
        return rows.get(0);
    }

    /** 与选择器同一原则：能解出与引用不同的非空密钥才算成功；异常/空值/自返回一律 null（fail-closed）。 */
    private String materializeCredential(String credentialRef) {
        if (credentialRef == null || credentialRef.isBlank()) {
            return null;
        }
        try {
            String decrypted = secretCipher.decrypt(credentialRef);
            if (decrypted == null || decrypted.isBlank() || decrypted.equals(credentialRef)) {
                return null;
            }
            return decrypted;
        } catch (Exception notResolvable) {
            return null;
        }
    }

    private List<Capability> parseCapabilities(String capabilitiesJson) {
        try {
            JsonNode node = objectMapper.readTree(capabilitiesJson == null || capabilitiesJson.isBlank() ? "{}" : capabilitiesJson);
            // R2-1：兼容两种生产数据形态——裸数组（生产写入方）与 {"capabilities":[...]} 对象。
            JsonNode list = node.isArray() ? node : node.path("capabilities");
            List<Capability> capabilities = new ArrayList<>();
            for (JsonNode entry : list) {
                try {
                    capabilities.add(Capability.valueOf(entry.asText()));
                } catch (IllegalArgumentException unknown) {
                    // 未知能力名不猜测语义：跳过并保持其余探针行为不变。
                }
            }
            return capabilities;
        } catch (Exception exception) {
            return List.of();
        }
    }

    private String overall(List<ProbeResult> probes) {
        if (probes.stream().anyMatch(p -> STATUS_FAIL.equals(p.status()))) {
            return STATUS_FAIL;
        }
        // F1：只有生成级探针（text 与 task-json）双双 pass 才算整体通过。
        // reachability 只是端点发现信息，单独 pass 绝不能被抬成整体 pass；
        // 任何一个生成探针为 unknown/unsupported 时整体必须是 unknown（绝不被 pass 掩盖）。
        boolean textPass = probes.stream()
            .anyMatch(p -> "text".equals(p.probe()) && STATUS_PASS.equals(p.status()));
        boolean taskJsonPass = probes.stream()
            .anyMatch(p -> "task-json".equals(p.probe()) && STATUS_PASS.equals(p.status()));
        return (textPass && taskJsonPass) ? STATUS_PASS : STATUS_UNKNOWN;
    }

    private String configHash(String baseUrl, String protocol, String modelId, String capabilitiesJson,
                              String parameterPolicyJson, String credentialRef) {
        return sha256(String.join("|",
            nullSafe(baseUrl), nullSafe(protocol), nullSafe(modelId),
            nullSafe(capabilitiesJson), nullSafe(parameterPolicyJson), nullSafe(credentialRef)));
    }

    private String diagnosticsId(String probe, String seed) {
        return "probe-" + probe + "-" + sha256(seed).substring(0, 12);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("cannot hash capability check input", exception);
        }
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
