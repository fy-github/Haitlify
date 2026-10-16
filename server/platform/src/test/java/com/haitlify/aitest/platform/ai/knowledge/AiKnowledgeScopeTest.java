package com.haitlify.aitest.platform.ai.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * T08 bullet 5-6：知识检索作用域（仅 PUBLISHED + 当前项目）、引用只来自存储行、
 * 无证据 ⇒ need_confirmation；上下文预算、层级保留与保守估算。
 */
class AiKnowledgeScopeTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AiKnowledgeRetrievalService retrievalService = new AiKnowledgeRetrievalService(jdbc);
    private final AiContextBuilder contextBuilder = new AiContextBuilder(jdbc, retrievalService);

    private ResultSet knowledgeRow(String knowledgeId, String title, int version, String content, String sourceRef)
        throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("knowledge_id")).thenReturn(knowledgeId);
        when(rs.getString("title")).thenReturn(title);
        when(rs.getInt("version")).thenReturn(version);
        when(rs.getString("content")).thenReturn(content);
        when(rs.getString("source_ref")).thenReturn(sourceRef);
        return rs;
    }

    private ResultSet chunkRow(String documentId, String heading, String content, Integer page, Integer lineStart,
                               Integer lineEnd) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("document_id")).thenReturn(documentId);
        when(rs.getString("section_heading")).thenReturn(heading);
        when(rs.getString("content")).thenReturn(content);
        when(rs.getObject("page_number")).thenReturn(page);
        when(rs.getObject("line_start")).thenReturn(lineStart);
        when(rs.getObject("line_end")).thenReturn(lineEnd);
        if (page != null) {
            when(rs.getInt("page_number")).thenReturn(page);
        }
        if (lineStart != null) {
            when(rs.getInt("line_start")).thenReturn(lineStart);
        }
        if (lineEnd != null) {
            when(rs.getInt("line_end")).thenReturn(lineEnd);
        }
        return rs;
    }

    @SuppressWarnings("unchecked")
    private void stubKnowledgeRows(Long projectId, ResultSet... rows) throws Exception {
        when(jdbc.query(contains("ai_knowledge_documents"), any(RowMapper.class), any(), any()))
            .thenAnswer(invocation -> {
                RowMapper<Object> mapper = (RowMapper<Object>) invocation.getArgument(1, RowMapper.class);
                List<Object> mapped = new java.util.ArrayList<>();
                for (int index = 0; index < rows.length; index++) {
                    mapped.add(mapper.mapRow(rows[index], index));
                }
                return mapped;
            });
    }

    @SuppressWarnings("unchecked")
    private void stubChunkRows(ResultSet... rows) throws Exception {
        when(jdbc.query(contains("ai_source_chunks"), any(RowMapper.class), any()))
            .thenAnswer(invocation -> {
                RowMapper<Object> mapper = (RowMapper<Object>) invocation.getArgument(1, RowMapper.class);
                List<Object> mapped = new java.util.ArrayList<>();
                for (int index = 0; index < rows.length; index++) {
                    mapped.add(mapper.mapRow(rows[index], index));
                }
                return mapped;
            });
    }

    @Test
    void retrievalIsScopedToPublishedRowsOfTheCurrentProject() throws Exception {
        stubKnowledgeRows(42L, knowledgeRow("k-1", "补光灯需求", 3, "亮度需支持 0-100 区间", "doc-9"));
        AiKnowledgeRetrievalService.KnowledgeSearchResult result = retrievalService.search(42L, "补光灯");

        // 作用域由 SQL 参数强制：项目 + PUBLISHED（DRAFT/ARCHIVED 与跨项目行进不了结果）。
        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).query(contains("status = ?"), any(RowMapper.class), args.capture(), args.capture());
        assertThat(args.getAllValues()).containsExactly(42L, "PUBLISHED");
        assertThat(result.hits()).hasSize(1);
        assertThat(result.needConfirmation()).isFalse();
    }

    @Test
    void citationsComeFromStoredRowsOnly() throws Exception {
        stubKnowledgeRows(42L, knowledgeRow("k-7", "回程链路要求", 5, "断链 30 秒内自动回程", "doc-77"));
        AiKnowledgeRetrievalService.KnowledgeSearchResult result = retrievalService.search(42L, "回程");

        AiKnowledgeRetrievalService.KnowledgeHit hit = result.hits().get(0);
        // 标题/版本/来源与存储行逐字一致——绝不编造引用、知识标题或来源行。
        assertThat(hit.knowledgeId()).isEqualTo("k-7");
        assertThat(hit.title()).isEqualTo("回程链路要求");
        assertThat(hit.version()).isEqualTo(5);
        assertThat(hit.sourceRef()).isEqualTo("doc-77");
        assertThat(hit.snippet()).contains("断链");
    }

    @Test
    void noPublishedMatchRequiresConfirmationInsteadOfFabricatedAnswers() throws Exception {
        stubKnowledgeRows(42L);
        AiKnowledgeRetrievalService.KnowledgeSearchResult result = retrievalService.search(42L, "不存在的主题");

        assertThat(result.hits()).isEmpty();
        assertThat(result.needConfirmation()).isTrue();
        assertThat(result.note()).contains("need_confirmation");
    }

    @Test
    void contextKeepsHierarchyAndListsBudgetSkippedItems() throws Exception {
        stubKnowledgeRows(42L, knowledgeRow("k-1", "亮度规范", 1, "亮度需支持 0-100 区间", "doc-1"));
        stubChunkRows(
            chunkRow("doc-1", "补光灯控制", "亮度需支持 0-100 区间，默认 50", 2, 10, 24),
            chunkRow("doc-1", "补光灯控制", "异常时恢复默认亮度", 2, 25, 40),
            chunkRow("doc-2", "监控", "运行状态每分钟上报一次", 1, 1, 12));

        AiContextBuilder.BuiltContext context = contextBuilder.build(42L, "亮度", 400);

        assertThat(context.estimateVersion()).isEqualTo(AiContextBuilder.ESTIMATE_VERSION);
        assertThat(context.estimatedTokens()).isLessThanOrEqualTo(context.budgetTokens() - context.outputReserve());
        assertThat(context.items()).isNotEmpty();
        assertThat(context.items().get(0).kind()).isEqualTo("KNOWLEDGE");
        // 层级与位置保留（文档/章节/页码/行号），供引用定位。
        AiContextBuilder.ContextItem chunkItem = context.items().stream()
            .filter(item -> "CHUNK".equals(item.kind())).findFirst().orElseThrow();
        assertThat(chunkItem.documentId()).isEqualTo("doc-1");
        assertThat(chunkItem.heading()).isEqualTo("补光灯控制");
        assertThat(chunkItem.location()).contains("补光灯控制").contains("@p2").contains("L10-24");
    }

    @Test
    void tinyBudgetSkipsChunksExplicitlyInsteadOfSilentTruncation() throws Exception {
        stubKnowledgeRows(42L);
        stubChunkRows(
            chunkRow("doc-1", "长章节", "很长的内容".repeat(200), 1, 1, 400),
            chunkRow("doc-1", "长章节", "另一段很长的内容".repeat(200), 2, 401, 800));

        AiContextBuilder.BuiltContext context = contextBuilder.build(42L, "内容", 120);

        assertThat(context.items()).isEmpty();
        assertThat(context.skipped()).filteredOn(skip -> skip.contains("budget_exceeded")).hasSize(2);
        assertThat(context.skipped()).anySatisfy(skip -> assertThat(skip).contains("need_confirmation"));
    }

    @Test
    void tokenEstimateIsConservativeForCjkAndAscii() {
        // 非 ASCII 按 1 token/字符保守高估；ASCII 按 1/3；绝不允许低估。
        assertThat(AiContextBuilder.estimateTokens("补光灯亮度")).isGreaterThanOrEqualTo(5);
        assertThat(AiContextBuilder.estimateTokens("abcd")).isGreaterThanOrEqualTo(2);
        assertThat(AiContextBuilder.estimateTokens("")).isZero();
    }
}
