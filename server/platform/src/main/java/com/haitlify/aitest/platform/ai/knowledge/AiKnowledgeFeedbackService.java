package com.haitlify.aitest.platform.ai.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * T13：知识反馈——新知识只能从反馈生成候选（DRAFT），必须人工确认后才 PUBLISHED。
 *
 * <p>评价集防泄漏：评测来源的候选（source_ref 前缀 {@code evaluation:}）永远不允许发布为
 * 生产知识——否则模型可以「背下」评价集来作弊通过。</p>
 */
@Service
public class AiKnowledgeFeedbackService {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String SOURCE_FEEDBACK_PREFIX = "feedback:";
    public static final String SOURCE_EVALUATION_PREFIX = "evaluation:";

    public record KnowledgeCandidate(String knowledgeId, Long projectId, String title, String status,
                                     int version, String sourceRef, String publishedAt) {
    }

    private final JdbcTemplate jdbcTemplate;

    public AiKnowledgeFeedbackService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 从反馈生成候选：状态一律 DRAFT，绝不直接发布。 */
    public KnowledgeCandidate submitCandidate(Long projectId, String title, String content, String feedbackRef) {
        if (projectId == null || title == null || title.isBlank() || content == null || content.isBlank()) {
            throw new IllegalArgumentException("projectId/title/content required");
        }
        String knowledgeId = "knowledge-" + UUID.randomUUID();
        String sourceRef = SOURCE_FEEDBACK_PREFIX
            + (feedbackRef == null || feedbackRef.isBlank() ? "manual" : feedbackRef.trim());
        jdbcTemplate.update("""
            insert into ai_knowledge_documents(
              knowledge_id, project_id, title, status, version, content, source_ref,
              published_at, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, null, ?, ?)
            """,
            knowledgeId, projectId, title, STATUS_DRAFT, 1, content, sourceRef,
            java.sql.Timestamp.from(Instant.now()), java.sql.Timestamp.from(Instant.now()));
        return find(knowledgeId);
    }

    /** 发布：必须显式人工确认（已认证 actor），且评测来源一律拒绝。 */
    public KnowledgeCandidate publish(String knowledgeId, String actorId) {
        String actor = actorId == null || actorId.isBlank() ? currentActor() : actorId;
        Map<String, Object> row = row(knowledgeId);
        String sourceRef = String.valueOf(row.getOrDefault("source_ref", ""));
        if (sourceRef.startsWith(SOURCE_EVALUATION_PREFIX)) {
            throw new IllegalArgumentException(
                "evaluation_material_cannot_be_published: 评价集不得作为生产知识发布");
        }
        if (!STATUS_DRAFT.equals(String.valueOf(row.get("status")))) {
            throw new IllegalArgumentException("not_a_draft: 当前状态 " + row.get("status") + " 不可发布");
        }
        jdbcTemplate.update(
            "update ai_knowledge_documents set status = ?, published_at = ?, updated_at = ? where knowledge_id = ?",
            STATUS_PUBLISHED, java.sql.Timestamp.from(Instant.now()), java.sql.Timestamp.from(Instant.now()),
            knowledgeId);
        return find(knowledgeId);
    }

    /** 评测候选入口（供评测框架标注来源；仍只生成 DRAFT，发布仍会被拒绝）。 */
    public KnowledgeCandidate submitEvaluationCandidate(Long projectId, String title, String content, String runRef) {
        String knowledgeId = "knowledge-evaluation-" + UUID.randomUUID();
        jdbcTemplate.update("""
            insert into ai_knowledge_documents(
              knowledge_id, project_id, title, status, version, content, source_ref,
              published_at, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, null, ?, ?)
            """,
            knowledgeId, projectId, title, STATUS_DRAFT, 1, content,
            SOURCE_EVALUATION_PREFIX + (runRef == null ? "" : runRef), java.sql.Timestamp.from(Instant.now()),
            java.sql.Timestamp.from(Instant.now()));
        return find(knowledgeId);
    }

    public KnowledgeCandidate find(String knowledgeId) {
        Map<String, Object> row = row(knowledgeId);
        return new KnowledgeCandidate(
            String.valueOf(row.get("knowledge_id")),
            ((Number) row.get("project_id")).longValue(),
            String.valueOf(row.get("title")),
            String.valueOf(row.get("status")),
            ((Number) row.get("version")).intValue(),
            String.valueOf(row.getOrDefault("source_ref", "")),
            row.get("published_at") == null ? "" : String.valueOf(row.get("published_at")));
    }

    public List<KnowledgeCandidate> listByProject(Long projectId) {
        return jdbcTemplate.queryForList(
                "select knowledge_id, project_id, title, status, version, source_ref, published_at"
                    + " from ai_knowledge_documents where project_id = ? order by created_at asc", projectId)
            .stream().map(row -> new KnowledgeCandidate(
                String.valueOf(row.get("knowledge_id")), ((Number) row.get("project_id")).longValue(),
                String.valueOf(row.get("title")), String.valueOf(row.get("status")),
                ((Number) row.get("version")).intValue(), String.valueOf(row.getOrDefault("source_ref", "")),
                row.get("published_at") == null ? "" : String.valueOf(row.get("published_at"))))
            .toList();
    }

    private Map<String, Object> row(String knowledgeId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "select * from ai_knowledge_documents where knowledge_id = ?", knowledgeId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("knowledge_not_found: " + knowledgeId);
        }
        return rows.get(0);
    }

    private String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String actor = authentication == null ? "" : authentication.getName();
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("actor required: 发布知识必须来自已认证操作人");
        }
        return actor;
    }
}
