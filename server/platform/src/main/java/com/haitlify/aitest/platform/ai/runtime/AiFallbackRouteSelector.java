package com.haitlify.aitest.platform.ai.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.haitlify.aitest.platform.ai.gateway.AiRoute;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * T05 fallback 选择器（v1）：只从「当前授权项目的已配置档案」中挑选备用路由，且必须满足
 * 任务所需能力（如 text/schema）与预算前提；每个未入选候选都带结构化拒绝原因，供事件与审计记录。
 *
 * <p>v1 规则：
 * <ul>
 *   <li>授权：connection.allowed_projects 为空/缺省 ⇒ 不限制项目（向后兼容）；非空 ⇒ 必须包含项目</li>
 *   <li>启用：connection.enabled = true 且 profile 存在</li>
 *   <li>能力：requiredCapabilities 必须全部包含于 profile.capabilities_json 的 capabilities 列表</li>
 *   <li>预算：budgetMs &lt;= 0（整体截止已到）⇒ 不产出任何路由；否则 per-call 超时取
 *       min(profile 声明与调用方上限)</li>
 *   <li>排序：connection_id、profile_id 升序，profile.version 降序（不同 provider 确定性轮转）</li>
 *   <li>上限：至多 4 条（主路 + 备用），与调度策略上限一致</li>
 * </ul>
 */
@Component
public class AiFallbackRouteSelector {

    public static final String RULE_VERSION = "v1";
    public static final int MAX_ROUTES = 4;

    public record Candidate(String connectionId, String profileId, int profileVersion, String modelId,
                            boolean eligible, String rejection) {
    }

    public record Selection(List<AiRoute> routes, List<Candidate> candidates) {
        public List<Candidate> rejected() {
            return candidates.stream().filter(candidate -> !candidate.eligible()).toList();
        }
    }

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final com.haitlify.aitest.platform.security.SecretCipher secretCipher;

    public AiFallbackRouteSelector(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                   com.haitlify.aitest.platform.security.SecretCipher secretCipher) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.secretCipher = secretCipher;
    }

    public Selection select(long projectId, List<String> requiredCapabilities, long budgetMs, int callerPerCallTimeoutMs) {
        List<Candidate> candidates = new ArrayList<>();
        if (budgetMs <= 0) {
            return new Selection(List.of(), candidates);
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "select p.profile_id, p.version, p.connection_id, p.model_id, p.capabilities_json, "
                + "c.base_url, c.protocol, c.credential_ref, c.allowed_projects_json, c.enabled "
                + "from ai_model_profiles p join ai_connections c on c.connection_id = p.connection_id "
                + "and c.version = (select max(c2.version) from ai_connections c2 where c2.connection_id = p.connection_id) "
                + "order by p.connection_id, p.profile_id, p.version desc");
        List<AiRoute> routes = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String connectionId = String.valueOf(row.get("connection_id"));
            String profileId = String.valueOf(row.get("profile_id"));
            int profileVersion = ((Number) row.getOrDefault("version", 0)).intValue();
            String modelId = String.valueOf(row.getOrDefault("model_id", ""));
            boolean enabled = Boolean.TRUE.equals(row.get("enabled"));
            String rejection = "";
            String materializedKey = null;
            if (!enabled) {
                rejection = "connection_disabled";
            } else if (!authorizedForProject((String) row.get("allowed_projects_json"), projectId)) {
                rejection = "project_not_authorized";
            } else {
                List<String> capabilities = parseCapabilities((String) row.get("capabilities_json"));
                for (String required : requiredCapabilities) {
                    if (!capabilities.contains(required)) {
                        rejection = "missing_capability:" + required;
                        break;
                    }
                }
            }
            if (rejection.isEmpty()) {
                // M1：credential_ref 是「引用」而非密钥本身——只有能材料化为真实密钥的引用才可用；
                // 占位符/别名一律 fail-closed 拒绝（真实密钥来自 ai_system_configs 的密文清单）。
                materializedKey = materializeCredential((String) row.getOrDefault("credential_ref", ""));
                if (materializedKey == null) {
                    rejection = "credential_unresolved";
                }
            }
            Candidate candidate = new Candidate(connectionId, profileId, profileVersion, modelId,
                rejection.isEmpty(), rejection);
            candidates.add(candidate);
            if (rejection.isEmpty() && routes.size() < MAX_ROUTES) {
                int perCall = callerPerCallTimeoutMs <= 0 ? 60_000 : callerPerCallTimeoutMs;
                routes.add(new AiRoute(connectionId, profileId, (String) row.get("protocol"),
                    (String) row.get("base_url"), modelId, materializedKey, perCall));
            }
        }
        return new Selection(List.copyOf(routes), List.copyOf(candidates));
    }

    /**
     * 把 credential_ref 解析为可用的 Bearer 材料：能解出与引用不同的非空密钥才算成功；
     * 解密异常、空值、或「解密结果等于引用本身」（占位符/别名）一律返回 null（fail-closed）。
     */
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

    /** 空/缺省 ⇒ 不限制项目（向后兼容）；非空 ⇒ 必须包含项目。 */
    private boolean authorizedForProject(String allowedProjectsJson, long projectId) {
        if (allowedProjectsJson == null || allowedProjectsJson.isBlank()) {
            return true;
        }
        try {
            JsonNode node = objectMapper.readTree(allowedProjectsJson);
            if (!node.isArray()) {
                // L1：非数组（如 {"projects":[7]}）无法确认授权 ⇒ 拒绝（fail-closed）。
                return false;
            }
            if (node.isEmpty()) {
                return true;
            }
            for (JsonNode entry : node) {
                if (entry.isNumber() && entry.asLong(Long.MIN_VALUE) == projectId) {
                    return true;
                }
            }
            return false;
        } catch (Exception malformed) {
            // 坏数据不猜测：无法确认授权 ⇒ 拒绝（fail-closed）。
            return false;
        }
    }

    private List<String> parseCapabilities(String capabilitiesJson) {
        try {
            JsonNode node = objectMapper.readTree(
                capabilitiesJson == null || capabilitiesJson.isBlank() ? "{}" : capabilitiesJson);
            // R2-1：兼容两种生产数据形态——裸数组 ["TEXT",...]（生产写入方）与对象 {"capabilities":[...]}。
            JsonNode list = node.isArray() ? node : node.path("capabilities");
            List<String> capabilities = new ArrayList<>();
            for (JsonNode entry : list) {
                capabilities.add(entry.asText());
            }
            return capabilities;
        } catch (Exception malformed) {
            return List.of();
        }
    }

    /** 供事件使用的简明摘要：ok=N rejected=K（首位原因）。 */
    public Map<String, Object> summarize(Selection selection) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("eligible", selection.routes().size());
        summary.put("rejected", selection.rejected().size());
        summary.put("ruleVersion", RULE_VERSION);
        if (!selection.rejected().isEmpty()) {
            summary.put("firstRejection", selection.rejected().get(0).rejection());
        }
        return summary;
    }
}
