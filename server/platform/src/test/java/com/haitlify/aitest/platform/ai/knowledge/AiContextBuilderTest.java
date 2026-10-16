package com.haitlify.aitest.platform.ai.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * T11:314 浏览器 E2E 抓出的真实缺陷回归：证据上下文分块查询曾引用不存在的列 `c.content`
 * （schema 与全部其他读取方均为 `content_text`），仅在真实数据库执行时才暴露——
 * mock 掉 JdbcTemplate/ResultSet 的单测对列名错误盲区。本测试用迁移后的真实 H2 执行同一 SQL。
 */
@SpringBootTest
@ActiveProfiles("test")
class AiContextBuilderTest {

    @Autowired
    private AiContextBuilder contextBuilder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedRealChunks() {
        jdbcTemplate.update("delete from ai_source_chunks");
        jdbcTemplate.update("delete from ai_source_documents");
        jdbcTemplate.update("delete from projects where id in (77, 78)");
        // 真实 projects 行（FK：document.project_id → projects.id）。
        jdbcTemplate.update("""
            insert into projects(id, code, name, description, active, created_at, updated_at)
            values (77, 'e2e-p77', 'E2E 项目 77', '', true, ?, ?)
            """, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        jdbcTemplate.update("""
            insert into projects(id, code, name, description, active, created_at, updated_at)
            values (78, 'e2e-p78', 'E2E 项目 78', '', true, ?, ?)
            """, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        jdbcTemplate.update("""
            insert into ai_source_documents(id, document_id, project_id, file_name, document_type,
              content_hash, storage_location, parser_version, actor_id, actor_name, created_at, updated_at)
            values (9001, 'doc-e2e-1', 77, '需求.docx', 'requirement', 'hash-1', '', '', 'e2e', 'E2E',
              ?, ?)
            """, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        jdbcTemplate.update("""
            insert into ai_source_chunks(id, chunk_id, document_id, section_heading, page_number,
              line_start, line_end, chunk_type, content_hash, content_text, created_at, updated_at)
            values (9101, 'chunk-e2e-1', 9001, '登录章节', 3, 10, 14, 'text', 'hash-c1',
              '登录成功后应跳转首页（真实分块内容）', ?, ?)
            """, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
    }

    @Test
    void projectChunksAreReadThroughRealSchemaColumns() {
        AiContextBuilder.BuiltContext built = contextBuilder.build(77L, "登录 跳转", 20000);

        assertThat(built.items()).anySatisfy(item -> {
            assertThat(item.kind()).isEqualTo("CHUNK");
            assertThat(item.documentId()).isEqualTo("doc-e2e-1");
            assertThat(item.heading()).isEqualTo("登录章节");
            assertThat(item.text()).contains("登录成功后应跳转首页");
        });
        assertThat(built.estimatedTokens()).isGreaterThan(0);
    }

    @Test
    void otherProjectsChunksAreNotVisibleInTheContext() {
        AiContextBuilder.BuiltContext built = contextBuilder.build(78L, "登录", 20000);
        assertThat(built.items()).noneSatisfy(item ->
            assertThat(item.documentId()).isEqualTo("doc-e2e-1"));
    }
}
