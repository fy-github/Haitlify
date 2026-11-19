package com.haitlify.aitest.platform.assistant.generation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import com.haitlify.aitest.platform.auth.ProjectAccessService;
import com.haitlify.aitest.platform.security.SecretCipher;
import com.haitlify.aitest.platform.tenant.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Admission only: intentionally has no worker, model, Hermes or Runner dependency. */
@Service
public class AssistantGenerationService {
    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    private final ObjectMapper json;
    private final TenantAccessService tenants;
    private final ProjectAccessService projects;

    public AssistantGenerationService(JdbcTemplate jdbc, SecretCipher cipher, ObjectMapper json,
                                      TenantAccessService tenants, ProjectAccessService projects) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.json = json;
        this.tenants = tenants;
        this.projects = projects;
    }

    record Actor(long id, String username, String role) {}
    public record Receipt(String id, String requestId, long projectId, String state, String createdAt) {}

    public Receipt admit(Map<String, Object> payload) {
        Actor actor = actor();
        if (!payload.keySet().equals(java.util.Set.of("requestId", "projectId", "requirementRef", "requirementText", "targetUrl", "targetRevision"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid generation request");
        }
        Object projectValue = payload.get("projectId");
        if (!(projectValue instanceof Integer || projectValue instanceof Long)
            || ((Number) projectValue).longValue() <= 0) throw invalid();
        requireText(payload, "requestId", 64);
        if (!payload.get("requestId").toString().matches("[A-Za-z0-9._-]{1,64}")) throw invalid();
        requireText(payload, "requirementRef", 512);
        requireText(payload, "requirementText", 65536);
        requireText(payload, "targetUrl", 2048);
        requireText(payload, "targetRevision", 128);
        GenerationTargetPolicy.validate((String) payload.get("targetUrl"));
        long projectId = ((Number) projectValue).longValue();
        long tenantId = access(actor, projectId);
        String snapshot = json.writeValueAsString(new java.util.TreeMap<>(payload));
        String encrypted = cipher.encrypt(snapshot);
        if (snapshot.equals(encrypted) || !encrypted.startsWith("enc:v1:")) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Generation admission unavailable");
        }
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        try {
            // Autocommit insert: a unique-key loser reads the committed winner outside a
            // rollback-only transaction (also safe under MySQL REPEATABLE READ).
            jdbc.update("insert into assistant_generations(id,actor_id,project_id,tenant_id,request_id,payload_digest,snapshot_ciphertext,state,created_at) values (?,?,?,?,?,?,?,'WAITING_WORKER',?)",
                id, actor.id(), projectId, tenantId, payload.get("requestId"), digest(snapshot), encrypted, now);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            // Read by the full idempotency scope, never by requestId alone.
        }
        Stored stored = jdbc.query("select * from assistant_generations where actor_id=? and project_id=? and request_id=?",
            (rs, n) -> stored(rs), actor.id(), projectId, payload.get("requestId")).stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Generation admission unavailable"));
        if (stored.tenantId() != tenantId || !stored.digest().equals(digest(snapshot))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Request conflicts with existing admission");
        }
        return stored.receipt();
    }

    public Receipt get(String id) {
        Actor actor = actor();
        Stored stored = jdbc.query("select * from assistant_generations where id=? and actor_id=?",
            (rs, n) -> stored(rs), id, actor.id()).stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Generation not found"));
        if (access(actor, stored.receipt().projectId()) != stored.tenantId()) throw denied();
        return stored.receipt();
    }

    private record Stored(Receipt receipt, long actorId, long tenantId, String digest) {}

    private static Stored stored(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Stored(new Receipt(rs.getString("id"), rs.getString("request_id"), rs.getLong("project_id"),
            rs.getString("state"), rs.getTimestamp("created_at").toInstant().toString()),
            rs.getLong("actor_id"), rs.getLong("tenant_id"), rs.getString("payload_digest"));
    }

    private static void requireText(Map<String, Object> payload, String field, int max) {
        if (!(payload.get(field) instanceof String value) || value.isBlank() || value.length() > max) throw invalid();
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid generation request");
    }

    private Actor actor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        Actor actor = jdbc.query("select id,username,role from platform_users where username=? and enabled=true",
            (rs, n) -> new Actor(rs.getLong("id"), rs.getString("username"), rs.getString("role")), authentication.getName())
            .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));
        if (!"ADMIN".equals(actor.role()) && !"EDITOR".equals(actor.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
        return actor;
    }

    private long access(Actor actor, long projectId) {
        Long tenantId = jdbc.query("select tenant_id from projects where id=? and active=true and tenant_id is not null",
            (rs, n) -> rs.getLong(1), projectId).stream().findFirst().orElseThrow(AssistantGenerationService::denied);
        // These checks intentionally remain active when the legacy global security switch is off.
        if (jdbc.queryForObject("select count(*) from tenants where id=? and active=true", Integer.class, tenantId) != 1) throw denied();
        if (!"ADMIN".equals(actor.role())) {
            if (jdbc.queryForObject("select count(*) from tenant_members where tenant_id=? and username=?", Integer.class, tenantId, actor.username()) != 1
                || jdbc.queryForObject("select count(*) from project_members where project_id=? and username=?", Integer.class, projectId, actor.username()) != 1) throw denied();
        }
        try {
            tenants.requireTenantProject(tenantId, projectId);
            projects.requireAccess(projectId);
        } catch (ResponseStatusException ex) {
            throw denied();
        }
        return tenantId;
    }

    private static ResponseStatusException denied() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
