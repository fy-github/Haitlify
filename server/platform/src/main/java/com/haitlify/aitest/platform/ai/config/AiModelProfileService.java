package com.haitlify.aitest.platform.ai.config;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.haitlify.aitest.platform.ai.runtime.AiRuntimeModels.Capability;

import tools.jackson.databind.ObjectMapper;

/**
 * T01：版本化模型档案注册表（追加式、非破坏性）。
 *
 * <ul>
 *   <li>档案必须引用已存在的连接（任何版本）；新版本永不覆写历史版本——历史快照可按版本复读；</li>
 *   <li>capabilities 只接受目录中的能力名（未知能力名 fail-closed），空列表表示"未声明/未知"——
 *       未声明的可选参数绝不发送；</li>
 *   <li>parameterPolicyJson/verificationJson 只存 JSON 对象；verification 默认状态 UNKNOWN（不假设支持）。</li>
 * </ul>
 */
@Service
public class AiModelProfileService {

    /** 未验证状态的默认 verification（导入/新注册共用；绝不假设能力）。 */
    static final String UNKNOWN_VERIFICATION = "{\"status\":\"UNKNOWN\"}";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    AiModelProfileService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public ProfileView register(String profileId, String connectionId, String modelId,
                                List<String> capabilities, String parameterPolicyJson, String verificationJson) {
        return register(profileId, connectionId, modelId, capabilities, parameterPolicyJson, verificationJson, null);
    }

    public ProfileView register(String profileId, String connectionId, String modelId,
                                List<String> capabilities, String parameterPolicyJson, String verificationJson,
                                Integer expectedVersion) {
        requireText(profileId, "profileId");
        requireText(connectionId, "connectionId");
        requireText(modelId, "modelId");
        List<String> normalizedCapabilities = normalizeCapabilities(capabilities);
        String parameterPolicy = requireJsonObject(parameterPolicyJson, "{}", "parameterPolicyJson");
        String verification = requireJsonObject(verificationJson, UNKNOWN_VERIFICATION, "verificationJson");
        for (int attempt = 0; attempt < 3; attempt++) {
            if (jdbcTemplate.queryForObject(
                    "select count(*) from ai_connections where connection_id = ?", Integer.class,
                    connectionId) == 0) {
                throw new IllegalArgumentException("connection not found: " + connectionId);
            }
            Integer currentVersion = jdbcTemplate.queryForObject(
                "select coalesce(max(version), 0) from ai_model_profiles where profile_id = ?",
                Integer.class, profileId);
            int current = currentVersion == null ? 0 : currentVersion;
            if (expectedVersion != null && current != expectedVersion) {
                throw new ConfigurationVersionConflictException("profile", profileId, expectedVersion, current);
            }
            int next = current + 1;
            Instant now = Instant.now();
            try {
                jdbcTemplate.update(
                    "insert into ai_model_profiles(profile_id, version, connection_id, model_id,"
                        + " capabilities_json, parameter_policy_json, verification_json, created_at, updated_at)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    profileId, next, connectionId, modelId,
                    objectMapper.valueToTree(normalizedCapabilities).toString(), parameterPolicy, verification,
                    Timestamp.from(now), Timestamp.from(now));
                return new ProfileView(profileId, next, connectionId, modelId, normalizedCapabilities,
                    parameterPolicy, verification, now, now);
            } catch (DuplicateKeyException racingInsert) {
                if (attempt == 2) {
                    throw new ConfigurationVersionConflictException("profile", profileId, next, -1);
                }
            }
        }
        throw new IllegalStateException("profile register exhausted retries: " + profileId);
    }

    public ProfileView latest(String profileId) {
        return row(profileId, null);
    }

    public List<ProfileView> list() {
        return jdbcTemplate.queryForList(
            "select profile_id, max(version) as version from ai_model_profiles group by profile_id"
                + " order by profile_id").stream()
            .map(row -> latest(String.valueOf(row.get("profile_id")))).toList();
    }

    public ProfileView at(String profileId, int version) {
        return row(profileId, version);
    }

    java.util.Optional<ProfileView> latestIfEqual(String profileId, String connectionId, String modelId) {
        try {
            ProfileView latest = latest(profileId);
            return latest.connectionId().equals(connectionId) && latest.modelId().equals(modelId)
                ? java.util.Optional.of(latest) : java.util.Optional.empty();
        } catch (IllegalArgumentException missing) {
            return java.util.Optional.empty();
        }
    }

    private ProfileView row(String profileId, Integer version) {
        requireText(profileId, "profileId");
        List<ProfileView> rows = (version == null
            ? jdbcTemplate.queryForList(
                "select * from ai_model_profiles where profile_id = ? order by version desc limit 1", profileId)
            : jdbcTemplate.queryForList(
                "select * from ai_model_profiles where profile_id = ? and version = ?", profileId, version))
            .stream().map(this::toView).toList();
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("profile not found: " + profileId
                + (version == null ? "" : "@" + version));
        }
        return rows.get(0);
    }

    private ProfileView toView(java.util.Map<String, Object> row) {
        List<String> capabilities = new ArrayList<>();
        Object raw = row.get("capabilities_json");
        if (raw != null) {
            try {
                objectMapper.readTree(String.valueOf(raw)).forEach(node -> capabilities.add(node.asText()));
            } catch (RuntimeException malformed) {
                capabilities.clear();
            }
        }
        return new ProfileView(
            String.valueOf(row.get("profile_id")),
            ((Number) row.get("version")).intValue(),
            String.valueOf(row.get("connection_id")),
            String.valueOf(row.get("model_id")),
            List.copyOf(capabilities),
            row.get("parameter_policy_json") == null ? "{}" : String.valueOf(row.get("parameter_policy_json")),
            row.get("verification_json") == null ? UNKNOWN_VERIFICATION : String.valueOf(row.get("verification_json")),
            timestamp(row.get("created_at")),
            timestamp(row.get("updated_at")));
    }

    /** 未知能力名 fail-closed；单字母大小写归一。 */
    static List<String> normalizeCapabilities(List<String> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String capability : capabilities) {
            String upper = capability == null ? "" : capability.trim().toUpperCase(Locale.ROOT);
            try {
                Capability.valueOf(upper);
            } catch (IllegalArgumentException unknown) {
                throw new IllegalArgumentException("unknown capability: " + capability);
            }
            if (!upper.isEmpty()) {
                normalized.add(upper);
            }
        }
        return List.copyOf(normalized);
    }

    private String requireJsonObject(String raw, String fallback, String field) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            if (!objectMapper.readTree(raw).isObject()) {
                throw new IllegalArgumentException(field + " must be a JSON object");
            }
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException(field + " must be a JSON object");
        }
        return raw;
    }

    private static Instant timestamp(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (raw instanceof Instant instant) {
            return instant;
        }
        return Instant.parse(String.valueOf(raw).replace(' ', 'T'));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " required");
        }
    }
}
