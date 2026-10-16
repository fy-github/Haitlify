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

import tools.jackson.databind.ObjectMapper;

/**
 * T01：版本化连接注册表（追加式、非破坏性）。
 *
 * <ul>
 *   <li>register 追加新版本（version = max+1，重复键竞态用重试收敛）；历史版本永不覆写；</li>
 *   <li>{@code expectedVersion} 与当前版本不符 ⇒ {@link ConfigurationVersionConflictException}（409）；</li>
 *   <li>credential_ref 是 opaque 引用——本服务不做形态假设、绝不接受或存储密钥值；</li>
 *   <li>协议仅允许 CHAT_COMPLETIONS/RESPONSES（大小写不敏感，存储统一大写）。</li>
 * </ul>
 */
@Service
public class AiConnectionService {

    public static final String SOURCE_LEGACY_IMPORT = "legacy-import";

    static final Set<String> KNOWN_PROTOCOLS = Set.of("CHAT_COMPLETIONS", "RESPONSES");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    AiConnectionService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public ConnectionView register(String connectionId, String baseUrl, String credentialRef,
                                   String protocol, boolean enabled, List<Long> allowedProjectIds) {
        return register(connectionId, baseUrl, credentialRef, protocol, enabled, allowedProjectIds, null);
    }

    /** 带乐观并发的注册：expectedVersion=null 时不校验；冲突抛 409 语义异常。 */
    public ConnectionView register(String connectionId, String baseUrl, String credentialRef,
                                   String protocol, boolean enabled, List<Long> allowedProjectIds,
                                   Integer expectedVersion) {
        requireText(connectionId, "connectionId");
        requireText(baseUrl, "baseUrl");
        requireText(credentialRef, "credentialRef");
        String normalizedProtocol = normalizeProtocol(protocol);
        for (int attempt = 0; attempt < 3; attempt++) {
            Integer currentVersion = jdbcTemplate.queryForObject(
                "select coalesce(max(version), 0) from ai_connections where connection_id = ?",
                Integer.class, connectionId);
            int current = currentVersion == null ? 0 : currentVersion;
            if (expectedVersion != null && current != expectedVersion) {
                throw new ConfigurationVersionConflictException("connection", connectionId, expectedVersion, current);
            }
            int next = current + 1;
            Instant now = Instant.now();
            try {
                jdbcTemplate.update(
                    "insert into ai_connections(connection_id, version, base_url, credential_ref, protocol,"
                        + " enabled, allowed_projects_json, created_at, updated_at)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    connectionId, next, baseUrl, credentialRef, normalizedProtocol, enabled,
                    allowedProjectsJson(allowedProjectIds), Timestamp.from(now), Timestamp.from(now));
                return new ConnectionView(connectionId, next, baseUrl, credentialRef, normalizedProtocol,
                    enabled, allowedProjectIds == null ? List.of() : List.copyOf(allowedProjectIds), now, now);
            } catch (DuplicateKeyException racingInsert) {
                // 版本号被并发注册抢占——重读 max 后重试（≤3 次）。
                if (attempt == 2) {
                    throw new ConfigurationVersionConflictException("connection", connectionId, next, -1);
                }
            }
        }
        throw new IllegalStateException("connection register exhausted retries: " + connectionId);
    }

    public ConnectionView latest(String connectionId) {
        return row(connectionId, null);
    }

    public ConnectionView at(String connectionId, int version) {
        return row(connectionId, version);
    }

    public List<ConnectionView> list() {
        return jdbcTemplate.queryForList(
            "select connection_id, max(version) as version from ai_connections group by connection_id"
                + " order by connection_id").stream()
            .map(row -> latest(String.valueOf(row.get("connection_id")))).toList();
    }

    /** 与给定内容完全一致的当前最新版本（用于幂等导入）；不一致则返回 empty。 */
    java.util.Optional<ConnectionView> latestIfEqual(String connectionId, String baseUrl, String credentialRef,
                                                     String protocol) {
        try {
            ConnectionView latest = latest(connectionId);
            return latest.baseUrl().equals(baseUrl) && latest.credentialRef().equals(credentialRef)
                && latest.protocol().equals(normalizeProtocol(protocol))
                ? java.util.Optional.of(latest) : java.util.Optional.empty();
        } catch (IllegalArgumentException missing) {
            return java.util.Optional.empty();
        }
    }

    private ConnectionView row(String connectionId, Integer version) {
        requireText(connectionId, "connectionId");
        List<ConnectionView> rows = (version == null
            ? jdbcTemplate.queryForList(
                "select * from ai_connections where connection_id = ? order by version desc limit 1", connectionId)
            : jdbcTemplate.queryForList(
                "select * from ai_connections where connection_id = ? and version = ?", connectionId, version))
            .stream().map(this::toView).toList();
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("connection not found: " + connectionId
                + (version == null ? "" : "@" + version));
        }
        return rows.get(0);
    }

    private ConnectionView toView(java.util.Map<String, Object> row) {
        return new ConnectionView(
            String.valueOf(row.get("connection_id")),
            ((Number) row.get("version")).intValue(),
            String.valueOf(row.get("base_url")),
            String.valueOf(row.get("credential_ref")),
            String.valueOf(row.get("protocol")),
            Boolean.TRUE.equals(row.get("enabled")),
            parseAllowedProjects(row.get("allowed_projects_json")),
            timestamp(row.get("created_at")),
            timestamp(row.get("updated_at")));
    }

    private List<Long> parseAllowedProjects(Object raw) {
        if (raw == null) {
            return List.of();
        }
        try {
            List<Long> projects = new ArrayList<>();
            objectMapper.readTree(String.valueOf(raw)).forEach(node -> projects.add(node.asLong()));
            return List.copyOf(projects);
        } catch (RuntimeException malformed) {
            return List.of();
        }
    }

    private String allowedProjectsJson(List<Long> allowedProjectIds) {
        return allowedProjectIds == null ? null : objectMapper.valueToTree(allowedProjectIds).toString();
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

    static String normalizeProtocol(String protocol) {
        String upper = protocol == null ? "" : protocol.trim().toUpperCase(Locale.ROOT);
        if (!KNOWN_PROTOCOLS.contains(upper)) {
            throw new IllegalArgumentException("unknown protocol: " + protocol);
        }
        return upper;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " required");
        }
        if (value.length() > 1024) {
            throw new IllegalArgumentException(field + " too long");
        }
    }
}
