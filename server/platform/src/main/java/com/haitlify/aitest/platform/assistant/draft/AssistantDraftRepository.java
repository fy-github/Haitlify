package com.haitlify.aitest.platform.assistant.draft;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import com.haitlify.aitest.platform.security.SecretCipher;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 草稿存储：DRAFT → CONFIRMED 只有一次显式确认；确认人与时间留痕。 */
@Repository
public class AssistantDraftRepository {

    public record DraftRecord(String draftId, long tenantId, Long projectId, String requirementRef,
                              String requirementDigest, String draftJson, String state, long createdBy,
                              Instant createdAt, Long confirmedBy, Instant confirmedAt,
                              String actionCode, String targetRef, String targetRevision,
                              String inputsJson, String inputsDigest, Long rejectedBy,
                              int revision, String supersededBy, String rootDraftId,
                              String requirementSummaryJson) {}

    private final JdbcTemplate jdbcTemplate;
    private final SecretCipher cipher;

    public AssistantDraftRepository(JdbcTemplate jdbcTemplate, SecretCipher cipher) {
        this.jdbcTemplate = jdbcTemplate;
        this.cipher = cipher;
    }

    public String insert(long tenantId, Long projectId, String requirementRef, String requirementDigest,
                         String draftJson, long createdBy, String actionCode, String targetRef,
                         String targetRevision, String inputsJson, String inputsDigest,
                         int revision, String rootDraftId, String requirementSummaryJson) {
        String draftId = UUID.randomUUID().toString();
        try {
            jdbcTemplate.update(
                "insert into assistant_draft(draft_id, tenant_id, project_id, requirement_ref, requirement_digest,"
                    + " draft_json, state, created_by, created_at, action_code, target_ref, target_revision,"
                    + " inputs_json, inputs_digest, revision, root_draft_id, requirement_summary_json)"
                    + " values (?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                draftId, tenantId, projectId, requirementRef, requirementDigest, draftJson, createdBy,
                Timestamp.from(Instant.now()), actionCode, targetRef, targetRevision, cipher.encrypt(inputsJson), inputsDigest,
                revision, rootDraftId, requirementSummaryJson);
        } catch (DuplicateKeyException ex) {
            throw new IllegalStateException("草稿 ID 冲突", ex);
        }
        return draftId;
    }

    /** 按项目列草稿：默认只返回每个修订链的最新一版（superseded_by is null）。 */
    public List<DraftRecord> listByProject(long tenantId, long projectId, String state, int limit, int offset) {
        StringBuilder sql = new StringBuilder(
            "select draft_id, tenant_id, project_id, requirement_ref, requirement_digest, draft_json, state,"
                + " created_by, created_at, confirmed_by, confirmed_at, action_code, target_ref, target_revision,"
                + " inputs_json, inputs_digest, rejected_by, revision, superseded_by, root_draft_id,"
                + " requirement_summary_json"
                + " from assistant_draft where tenant_id = ? and project_id = ? and superseded_by is null");
        java.util.List<Object> args = new java.util.ArrayList<>(List.of(tenantId, projectId));
        if (state != null && !state.isBlank()) {
            sql.append(" and state = ?");
            args.add(state);
        }
        sql.append(" order by created_at desc limit ? offset ?");
        args.add(Math.max(1, Math.min(limit, 200)));
        args.add(Math.max(0, offset));
        return jdbcTemplate.query(sql.toString(), (rs, i) -> map(rs), args.toArray());
    }

    /** 修订链：同一个 root 的所有版本，按 revision 升序。 */
    public List<DraftRecord> listRevisions(String draftId, long tenantId) {
        return jdbcTemplate.query(
            "select draft_id, tenant_id, project_id, requirement_ref, requirement_digest, draft_json, state,"
                + " created_by, created_at, confirmed_by, confirmed_at, action_code, target_ref, target_revision,"
                + " inputs_json, inputs_digest, rejected_by, revision, superseded_by, root_draft_id,"
                + " requirement_summary_json"
                + " from assistant_draft where tenant_id = ? and root_draft_id ="
                + " (select root_draft_id from assistant_draft where draft_id = ? and tenant_id = ?)"
                + " order by revision asc",
            (rs, i) -> map(rs), tenantId, draftId, tenantId);
    }

    /** 所有读取都带 tenant：拿到 draftId 不等于拿到了跨租户权限。 */
    public Optional<DraftRecord> find(String draftId, long tenantId) {
        List<DraftRecord> rows = jdbcTemplate.query(
            "select draft_id, tenant_id, project_id, requirement_ref, requirement_digest, draft_json, state,"
                + " created_by, created_at, confirmed_by, confirmed_at, action_code, target_ref, target_revision,"
                + " inputs_json, inputs_digest, rejected_by, revision, superseded_by, root_draft_id,"
                + " requirement_summary_json"
                + " from assistant_draft"
                + " where draft_id = ? and tenant_id = ?",
            (rs, i) -> map(rs), draftId, tenantId);
        return rows.stream().findFirst();
    }

    /** 只有 DRAFT 能确认；重复确认返回 false（调用方可读回既有状态，不覆盖确认人）。 */
    public boolean confirm(String draftId, long tenantId, long actorId) {
        return jdbcTemplate.update(
            "update assistant_draft set state = 'CONFIRMED', confirmed_by = ?, confirmed_at = ?"
                + " where draft_id = ? and tenant_id = ? and state = 'DRAFT'",
            actorId, Timestamp.from(Instant.now()), draftId, tenantId) == 1;
    }

    public void setRootDraft(String draftId, long tenantId, String rootDraftId) {
        jdbcTemplate.update(
            "update assistant_draft set root_draft_id = ? where draft_id = ? and tenant_id = ?",
            rootDraftId, draftId, tenantId);
    }

    /** 旧修订被新修订取代：状态 SUPERSEDED，指向新 draftId。 */
    public boolean supersede(String draftId, long tenantId, String newDraftId) {
        return jdbcTemplate.update(
            "update assistant_draft set state = 'SUPERSEDED', superseded_by = ?"
                + " where draft_id = ? and tenant_id = ? and state in ('DRAFT', 'CONFIRMED')",
            newDraftId, draftId, tenantId) == 1;
    }

    public boolean reject(String draftId, long tenantId, long actorId) {
        return jdbcTemplate.update(
            "update assistant_draft set state = 'REJECTED', rejected_by = ?, rejected_at = ?"
                + " where draft_id = ? and tenant_id = ? and state in ('DRAFT', 'CONFIRMED')",
            actorId, Timestamp.from(Instant.now()), draftId, tenantId) == 1;
    }

    /**
     * 读取时透明解密：inputs_json 里可能含手机号/口令等测试数据，落库前用 SecretCipher 加密
     * （enc:v1: 前缀自描述，历史明文行原样返回；没有配置 APP_SECRET_KEY 时退化为明文并告警）。
     */
    private DraftRecord map(ResultSet rs) throws SQLException {
        long project = rs.getLong("project_id");
        Long projectId = rs.wasNull() ? null : project;
        long confirmedByRaw = rs.getLong("confirmed_by");
        Long confirmedBy = rs.wasNull() ? null : confirmedByRaw;
        Timestamp confirmedAt = rs.getTimestamp("confirmed_at");
        return new DraftRecord(rs.getString("draft_id"), rs.getLong("tenant_id"), projectId,
            rs.getString("requirement_ref"), rs.getString("requirement_digest"), rs.getString("draft_json"),
            rs.getString("state"), rs.getLong("created_by"), rs.getTimestamp("created_at").toInstant(),
            confirmedBy, confirmedAt == null ? null : confirmedAt.toInstant(),
            rs.getString("action_code"), rs.getString("target_ref"), rs.getString("target_revision"),
            cipher.decrypt(rs.getString("inputs_json")), rs.getString("inputs_digest"), (Long) rs.getObject("rejected_by"),
            rs.getInt("revision"), rs.getString("superseded_by"), rs.getString("root_draft_id"),
            rs.getString("requirement_summary_json"));
    }
}
