package com.haitlify.aitest.platform.ai.analysis;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 MySQL 上的合并落库体积锁：`ai_draft_test_points.source_text` 必须容纳整段来源文本。
 *
 * <p>背景（用户实测类缺陷）：文档级 chunk 的全文会原样抄进草稿点的 `source_text`，
 * 该列原为 TEXT（64KB）；上传上限 25MB 生效后，内容型文档（数十 KB 起步）会在合并时截断。
 * V55 将该列扩为 LONGTEXT；本测试在真实 InnoDB 上以 >64KB 的来源文本走完整合并路径。
 *
 * <p>使用独立 `ai_merge_it` 库；无 MySQL 环境时整类跳过（类级 @EnabledIf，上下文加载前求值）。
 */
@SpringBootTest(properties = {
    "spring.datasource.url=${AI_MERGE_IT_DB_URL:jdbc:mysql://127.0.0.1:3306/ai_merge_it"
        + "?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai}",
    "spring.datasource.username=${AI_MERGE_IT_DB_USER:root}",
    "spring.datasource.password=${AI_MERGE_IT_DB_PASSWORD:root}",
    "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver"
})
@ActiveProfiles("mysql-it")
@org.junit.jupiter.api.condition.EnabledIf("mysqlItAvailable")
class AiDraftMergeMysqlIntegrationTest {

    static boolean mysqlItAvailable() {
        String url = System.getenv().getOrDefault("AI_MERGE_IT_DB_URL",
            "jdbc:mysql://127.0.0.1:3306/ai_merge_it");
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

    private static final String PROJECT_CODE = "AI_MERGE_IT";
    private static final String RUN_ID = "run-merge-it";

    @Autowired
    private AiDraftMergeService mergeService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long runPk;
    private long chunkPk;

    @BeforeEach
    void resetData() {
        jdbcTemplate.update("delete from ai_artifact_gate_audits");
        jdbcTemplate.update("delete from ai_draft_test_points");
        jdbcTemplate.update("delete from ai_source_chunks");
        jdbcTemplate.update("delete from ai_source_documents");
        jdbcTemplate.update("delete from ai_analysis_runs");
        jdbcTemplate.update("delete from projects where code = ?", PROJECT_CODE);
        Instant now = Instant.now();
        jdbcTemplate.update(
            "insert into projects(code, name, description, active, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
            PROJECT_CODE, "AI Draft Merge IT", "mysql-it 专用合成项目", true, now, now);
        projectId = jdbcTemplate.queryForObject("select id from projects where code = ?", Long.class, PROJECT_CODE);

        jdbcTemplate.update("""
            insert into ai_analysis_runs(
              run_id, project_id, target_case_kind, template_id, analysis_strategy, execution_mode,
              status, worker_status, coverage_status, total_chunks, completed_chunks, current_chunk_id,
              resume_from_chunk_id, error_summary, config_snapshot_json, rule_snapshot_json,
              skill_snapshot_json, schema_snapshot_json, input_summary_json, output_summary_json,
              actor_id, actor_name, started_at, finished_at, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            RUN_ID, projectId, "web_automation", "", "balanced", "multi_pass",
            "running", "running", "planned", 1, 0, "", "", "",
            "{}", "{}", "{}", "{}", "{\"documentIds\":[]}", null,
            "system-dev", "Development User", Timestamp.from(now), null, Timestamp.from(now), Timestamp.from(now));
        runPk = jdbcTemplate.queryForObject("select id from ai_analysis_runs where run_id = ?", Long.class, RUN_ID);

        // 超过 TEXT(64KB) 的整段来源文本（合成），约 100KB。
        StringBuilder content = new StringBuilder("# 来源文本体积锁（合成）\n");
        while (content.length() < 100 * 1024) {
            content.append("- 合成行：验证草稿点 source_text 可容纳整段来源文本，不含真实业务数据。\n");
        }
        String chunkText = content.toString();
        jdbcTemplate.update("""
            insert into ai_source_documents(
              document_id, project_id, file_name, document_type, content_hash, storage_location,
              extracted_text, parse_metadata_json, parser_version, source_file_metadata_json,
              actor_id, actor_name, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "doc-merge-it", projectId, "merge-it.md", "markdown", "hash-merge-it", "",
            chunkText, "{}", "requirement-document-parser-v1", "{}",
            "system-dev", "Development User", Timestamp.from(now), Timestamp.from(now));
        long documentPk = jdbcTemplate.queryForObject(
            "select id from ai_source_documents where document_id = ?", Long.class, "doc-merge-it");
        jdbcTemplate.update("""
            insert into ai_source_chunks(
              chunk_id, document_id, section_heading, line_start, line_end, page_number,
              table_identifier, image_identifier, figma_node_id, chunk_type, content_hash, content_text,
              evidence_metadata_json, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "chunk-merge-it", documentPk, "", 1, 1000, 0,
            "", "", "", "text", "hash-chunk-merge-it", chunkText,
            "{}", Timestamp.from(now), Timestamp.from(now));
        chunkPk = jdbcTemplate.queryForObject("select id from ai_source_chunks where chunk_id = ?", Long.class, "chunk-merge-it");
    }

    @Test
    void mergeCopiesLargeChunkTextIntoDraftPointWithoutTruncation() {
        AiDraftMergeCandidate candidate = new AiDraftMergeCandidate(
            "大来源文本合并锁", "合并", "合并页", "web_automation", "功能", "P2",
            "", "验证 source_text 可容纳整段来源文本", "步骤一", "预期一",
            0.9, false, "chunk-merge-it", chunkPk, "hash-merge-it");

        int inserted = mergeService.mergeDraftPoints(RUN_ID, projectId, List.of(candidate), "req-merge-it");

        assertThat(inserted).isEqualTo(1);
        String sourceText = jdbcTemplate.queryForObject(
            "select source_text from ai_draft_test_points where run_id = ? order by id desc limit 1",
            String.class, runPk);
        assertThat(sourceText).isNotNull();
        assertThat(sourceText.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(64 * 1024);
        assertThat(sourceText).startsWith("# 来源文本体积锁（合成）");
    }
}
