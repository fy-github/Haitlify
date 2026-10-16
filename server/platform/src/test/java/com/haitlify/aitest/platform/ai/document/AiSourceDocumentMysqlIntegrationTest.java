package com.haitlify.aitest.platform.ai.document;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 MySQL 上的源文档落库体积锁：`ai_source_documents.extracted_text` 必须能容纳数 MB 的文档全文。
 *
 * <p>背景（用户实测）：上传 2MB 需求文档 → `INSERT ai_source_documents` 报
 * `Data too long for column 'extracted_text'`（TEXT 仅 64KB）→ 接口 500。
 * `extracted_text`（全文）与 `ai_source_chunks.content_text`（文档级 chunk 落整篇）均已由 V54
 * 扩为 LONGTEXT；本测试在真实 InnoDB 上写回读全量文本，防止列型回退。
 *
 * <p>使用独立 `ai_source_doc_it` 库，不指向业务库；无 MySQL 环境时整类跳过（类级 @EnabledIf，上下文加载前求值）。
 */
@SpringBootTest(properties = {
    "spring.datasource.url=${AI_SRC_DOC_IT_DB_URL:jdbc:mysql://127.0.0.1:3306/ai_source_doc_it"
        + "?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai}",
    "spring.datasource.username=${AI_SRC_DOC_IT_DB_USER:root}",
    "spring.datasource.password=${AI_SRC_DOC_IT_DB_PASSWORD:root}",
    "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver"
})
@ActiveProfiles("mysql-it")
@org.junit.jupiter.api.condition.EnabledIf("mysqlItAvailable")
class AiSourceDocumentMysqlIntegrationTest {

    static boolean mysqlItAvailable() {
        String url = System.getenv().getOrDefault("AI_SRC_DOC_IT_DB_URL",
            "jdbc:mysql://127.0.0.1:3306/ai_source_doc_it");
        String host = "127.0.0.1";
        int port = 3306;
        try {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("jdbc:mysql://([^/:]+):(\\d+)").matcher(url);
            if (matcher.find()) {
                host = matcher.group(1);
                port = Integer.parseInt(matcher.group(2));
            }
        } catch (RuntimeException ignored) {
            // 保持默认值
        }
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), 800);
            return true;
        } catch (Exception unavailable) {
            return false;
        }
    }

    private static final String PROJECT_CODE = "AI_SRC_DOC_IT";

    @Autowired
    private AiSourceDocumentService documentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetData() {
        jdbcTemplate.update("delete from ai_source_chunks");
        jdbcTemplate.update("delete from ai_source_documents");
        jdbcTemplate.update("delete from projects where code = ?", PROJECT_CODE);
        Instant now = Instant.now();
        jdbcTemplate.update(
            "insert into projects(code, name, description, active, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
            PROJECT_CODE,
            "AI Source Document IT",
            "mysql-it 专用合成项目",
            true,
            now,
            now
        );
    }

    @Test
    void multiMegabyteDocumentTextRoundTripsThroughRealMysql() {
        long projectId = jdbcTemplate.queryForObject(
            "select id from projects where code = ?", Long.class, PROJECT_CODE);

        // 合成文档：约 1.2MB 纯文本（> TEXT 64KB 上限、≈ 用户实测 2MB 文档的场景量级），不含真实业务数据。
        StringBuilder builder = new StringBuilder("# 大文档落库锁（合成）\n");
        while (builder.length() < 1_200_000) {
            builder.append("- 合成行：验证 extracted_text 可容纳数 MB 文本，不含真实业务数据。\n");
        }
        String text = builder.toString();
        MockMultipartFile file = new MockMultipartFile(
            "file", "large-roundtrip.md", "text/markdown", text.getBytes(StandardCharsets.UTF_8));

        AiSourceDocumentResponse response = documentService.upload(projectId, file);

        String stored = jdbcTemplate.queryForObject(
            "select extracted_text from ai_source_documents where document_id = ?",
            String.class,
            response.documentId()
        );
        assertThat(stored).isNotNull();
        assertThat(stored.length()).isGreaterThan(1024 * 1024);
        assertThat(stored).startsWith("# 大文档落库锁（合成）");
        assertThat(response.documentId()).startsWith("doc-");
    }
}
