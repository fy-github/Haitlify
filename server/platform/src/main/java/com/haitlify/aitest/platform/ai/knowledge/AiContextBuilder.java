package com.haitlify.aitest.platform.ai.knowledge;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * T08 bullet 6：上下文装配——按保守估算（ASCII ≈ 1 token/3 chars、非 ASCII ≈ 1 token/char）
 * 计算长度并预留输出；分块保留层级（文档/章节）与重叠引用；估算与实际 usage 分开记录
 * （本构件只给出估算与版本号，真实 usage 由调用记录链路单独落库）。
 */
@Service
public class AiContextBuilder {

    public static final String ESTIMATE_VERSION = "conservative-chars-v1";

    public record ContextItem(String kind, String refId, String documentId, String heading, String text,
                              String location, int estimatedTokens, boolean truncated) {}

    public record BuiltContext(List<ContextItem> items, int estimatedTokens, int outputReserve, int budgetTokens,
                               List<String> skipped, String estimateVersion) {}

    private final JdbcTemplate jdbcTemplate;
    private final AiKnowledgeRetrievalService knowledgeRetrievalService;

    @org.springframework.beans.factory.annotation.Autowired
    public AiContextBuilder(JdbcTemplate jdbcTemplate, AiKnowledgeRetrievalService knowledgeRetrievalService) {
        this.jdbcTemplate = jdbcTemplate;
        this.knowledgeRetrievalService = knowledgeRetrievalService;
    }

    /** 保守 token 估算：只允许高估，不允许低估（真实 usage 另行记录，绝不以此冒充）。 */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int ascii = 0;
        int nonAscii = 0;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) < 128) {
                ascii++;
            } else {
                nonAscii++;
            }
        }
        return (int) Math.ceil(ascii / 3.0) + nonAscii;
    }

    public BuiltContext build(Long projectId, String query, int budgetTokens) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId required");
        }
        if (budgetTokens <= 0) {
            throw new IllegalArgumentException("budgetTokens must be positive");
        }
        int outputReserve = Math.min(Math.max(budgetTokens / 4, 1), 2048);
        int usable = budgetTokens - outputReserve;

        List<ContextItem> items = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int used = 0;

        // 优先级 1：已发布知识命中（引用来自存储行，注明来源与版本）。
        AiKnowledgeRetrievalService.KnowledgeSearchResult knowledge =
            knowledgeRetrievalService.search(projectId, query);
        for (AiKnowledgeRetrievalService.KnowledgeHit hit : knowledge.hits()) {
            String text = hit.title() + "\n" + hit.snippet();
            int estimate = estimateTokens(text);
            if (used + estimate > usable) {
                skipped.add("knowledge:" + hit.knowledgeId() + ":budget_exceeded");
                continue;
            }
            items.add(new ContextItem("KNOWLEDGE", hit.knowledgeId(), "", hit.title(), text,
                "source=" + hit.sourceRef() + " v" + hit.version(), estimate, false));
            used += estimate;
        }

        // 优先级 2：项目证据分块（保留文档/章节层级与位置；预算不足的整块跳过并记录）。
        List<ChunkRow> chunks = jdbcTemplate.query(
            "select d.document_id as document_id, c.section_heading as section_heading,"
                + " c.content_text as content, c.page_number as page_number, c.line_start as line_start,"
                + " c.line_end as line_end from ai_source_chunks c"
                + " join ai_source_documents d on d.id = c.document_id"
                + " where d.project_id = ? order by d.id asc, c.id asc",
            (rs, rowNum) -> new ChunkRow(
                rs.getString("document_id"),
                rs.getString("section_heading") == null ? "" : rs.getString("section_heading"),
                rs.getString("content") == null ? "" : rs.getString("content"),
                rs.getObject("page_number") == null ? null : rs.getInt("page_number"),
                rs.getObject("line_start") == null ? null : rs.getInt("line_start"),
                rs.getObject("line_end") == null ? null : rs.getInt("line_end")),
            projectId);
        for (ChunkRow chunk : chunks) {
            String text = chunk.content();
            int estimate = estimateTokens(text);
            String location = locationOf(chunk);
            if (used + estimate > usable) {
                skipped.add("chunk:" + chunk.documentId() + ":" + location + ":budget_exceeded");
                continue;
            }
            items.add(new ContextItem("CHUNK", chunk.documentId() + ":" + location, chunk.documentId(),
                chunk.heading(), text, location, estimate, false));
            used += estimate;
        }

        if (knowledge.needConfirmation() && !knowledge.note().isBlank()) {
            skipped.add("knowledge:need_confirmation:" + knowledge.note());
        }
        return new BuiltContext(List.copyOf(items), used, outputReserve, budgetTokens,
            List.copyOf(skipped), ESTIMATE_VERSION);
    }

    private static String locationOf(ChunkRow chunk) {
        StringBuilder location = new StringBuilder();
        if (!chunk.heading().isBlank()) {
            location.append(chunk.heading());
        }
        if (chunk.page() != null) {
            location.append(location.isEmpty() ? "" : " @").append("p").append(chunk.page());
        }
        if (chunk.lineStart() != null) {
            location.append(location.isEmpty() ? "" : " ").append("L").append(chunk.lineStart());
            if (chunk.lineEnd() != null) {
                location.append("-").append(chunk.lineEnd());
            }
        }
        return location.isEmpty() ? "(no location)" : location.toString();
    }

    private record ChunkRow(String documentId, String heading, String content, Integer page, Integer lineStart,
                            Integer lineEnd) {}
}
