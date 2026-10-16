package com.haitlify.aitest.platform.ai.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * T08 bullet 5：项目知识检索——仅返回「已发布且当前项目可访问」的版本；
 * 引用一律来自存储行（标题/来源/版本），绝不编造引用或来源行；
 * 无命中时以 need_confirmation 显式呈现，由人工补充证据。
 */
@Service
public class AiKnowledgeRetrievalService {

    public static final String STATUS_PUBLISHED = "PUBLISHED";

    public record KnowledgeHit(String knowledgeId, String title, int version, String sourceRef, String snippet,
                               int score) {}

    public record KnowledgeSearchResult(List<KnowledgeHit> hits, boolean needConfirmation, String note) {}

    private static final int SNIPPET_RADIUS = 120;

    private final JdbcTemplate jdbcTemplate;

    @org.springframework.beans.factory.annotation.Autowired
    public AiKnowledgeRetrievalService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 仅 PUBLISHED 的当前项目行参与检索；DRAFT/ARCHIVED 与跨项目行一律不可见。 */
    public KnowledgeSearchResult search(Long projectId, String query) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId required");
        }
        String normalized = query == null ? "" : query.trim();
        if (normalized.isBlank()) {
            return new KnowledgeSearchResult(List.of(), true, "empty query: evidence required");
        }
        List<KnowledgeDocument> documents = loadPublished(projectId);
        List<String> terms = terms(normalized);
        List<KnowledgeHit> hits = new ArrayList<>();
        for (KnowledgeDocument document : documents) {
            int score = score(document.content(), document.title(), terms);
            if (score <= 0) {
                continue;
            }
            hits.add(new KnowledgeHit(document.knowledgeId(), document.title(), document.version(),
                document.sourceRef(), snippet(document.content(), terms), score));
        }
        hits.sort(Comparator.comparingInt(KnowledgeHit::score).reversed()
            .thenComparing(KnowledgeHit::knowledgeId));
        if (hits.isEmpty()) {
            return new KnowledgeSearchResult(List.of(), true,
                "no published knowledge matched; treat as need_confirmation and attach source evidence");
        }
        return new KnowledgeSearchResult(List.copyOf(hits), false, "");
    }

    private List<KnowledgeDocument> loadPublished(Long projectId) {
        return jdbcTemplate.query(
            "select knowledge_id, title, version, content, source_ref from ai_knowledge_documents"
                + " where project_id = ? and status = ? order by id asc",
            (rs, rowNum) -> new KnowledgeDocument(
                rs.getString("knowledge_id"),
                rs.getString("title"),
                rs.getInt("version"),
                rs.getString("content") == null ? "" : rs.getString("content"),
                rs.getString("source_ref") == null ? "" : rs.getString("source_ref")),
            projectId, STATUS_PUBLISHED);
    }

    private static List<String> terms(String query) {
        List<String> terms = new ArrayList<>();
        for (String token : query.toLowerCase(Locale.ROOT).split("[\\s,，。;；:：/]+")) {
            if (token.length() >= 2) {
                terms.add(token);
            }
        }
        if (terms.isEmpty()) {
            terms.add(query.toLowerCase(Locale.ROOT));
        }
        return terms;
    }

    private static int score(String content, String title, List<String> terms) {
        String haystack = (title + "\n" + content).toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            int index = 0;
            while ((index = haystack.indexOf(term, index)) >= 0) {
                score += title.toLowerCase(Locale.ROOT).contains(term) ? 3 : 1;
                index += term.length();
            }
        }
        return score;
    }

    private static String snippet(String content, List<String> terms) {
        String haystack = content.toLowerCase(Locale.ROOT);
        int firstMatch = -1;
        for (String term : terms) {
            int index = haystack.indexOf(term);
            if (index >= 0 && (firstMatch < 0 || index < firstMatch)) {
                firstMatch = index;
            }
        }
        if (firstMatch < 0) {
            return content.length() <= SNIPPET_RADIUS * 2 ? content : content.substring(0, SNIPPET_RADIUS * 2);
        }
        int start = Math.max(0, firstMatch - SNIPPET_RADIUS);
        int end = Math.min(content.length(), firstMatch + SNIPPET_RADIUS);
        return (start > 0 ? "…" : "") + content.substring(start, end) + (end < content.length() ? "…" : "");
    }

    private record KnowledgeDocument(String knowledgeId, String title, int version, String content,
                                     String sourceRef) {}
}
