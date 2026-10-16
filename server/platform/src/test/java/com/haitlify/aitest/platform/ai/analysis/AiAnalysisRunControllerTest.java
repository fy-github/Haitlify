package com.haitlify.aitest.platform.ai.analysis;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.haitlify.aitest.platform.ai.gateway.AiGateway;
import com.haitlify.aitest.platform.ai.gateway.AiGatewayConfig;
import com.haitlify.aitest.platform.ai.gateway.AiGenerationResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiAnalysisRunControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AiGateway aiGateway;

    private long projectId;
    private String documentId;

    @BeforeEach
    void resetData() {
        jdbcTemplate.update("delete from ai_tool_call_records");
        jdbcTemplate.update("delete from ai_unclear_questions");
        jdbcTemplate.update("delete from ai_coverage_summaries");
        jdbcTemplate.update("delete from ai_analysis_chunks");
        jdbcTemplate.update("delete from ai_call_records");
        jdbcTemplate.update("delete from ai_review_actions");
        jdbcTemplate.update("delete from ai_draft_test_points");
        jdbcTemplate.update("delete from ai_analysis_runs");
        jdbcTemplate.update("delete from ai_source_chunks");
        jdbcTemplate.update("delete from ai_source_documents");
        jdbcTemplate.update("delete from ai_system_configs");
        jdbcTemplate.update("delete from projects where code = 'AI_ANALYSIS'");

        Instant now = Instant.now();
        jdbcTemplate.update(
            "insert into projects(code, name, description, active, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
            "AI_ANALYSIS",
            "AI Analysis Project",
            "Analysis tests",
            true,
            now,
            now
        );
        projectId = jdbcTemplate.queryForObject("select id from projects where code = 'AI_ANALYSIS'", Long.class);
        jdbcTemplate.update("""
            insert into ai_system_configs(
              config_key, enabled, provider_type, base_url, text_model, vision_model, embedding_model,
              api_key_ciphertext, credential_configured, request_timeout_ms, max_input_length, max_output_length,
              retry_count, request_logging_enabled, external_document_send_allowed, provider_config_json,
              masked_config_json, actor_id, actor_name, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "default", true, "openai-compatible", "https://api.example.com/v1", "gpt-test", "", "",
            "sk-test", true, 30000, 0, 0, 0, false, true, null,
            "****test", "system-dev", "Development User", now, now
        );

        documentId = "doc-analysis-1";
        jdbcTemplate.update("""
            insert into ai_source_documents(
              document_id, project_id, file_name, document_type, content_hash, storage_location,
              extracted_text, parse_metadata_json, parser_version, source_file_metadata_json,
              actor_id, actor_name, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            documentId, projectId, "requirements.txt", "text", "hash-doc", "",
            "项目: Haitlify\n模块: 登录\n测试点: 用户输入正确账号密码后进入工作台",
            "{\"lineCount\":3}", "requirement-document-parser-v1", "{\"fileName\":\"requirements.txt\"}",
            "system-dev", "Development User", now, now
        );
        Long sourceDocumentPk = jdbcTemplate.queryForObject(
            "select id from ai_source_documents where document_id = ?",
            Long.class,
            documentId
        );
        jdbcTemplate.update("""
            insert into ai_source_chunks(
              chunk_id, document_id, section_heading, page_number, line_start, line_end,
              table_identifier, image_identifier, figma_node_id, chunk_type, content_hash, content_text,
              evidence_metadata_json, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "chunk-analysis-1", sourceDocumentPk, "登录", null, 1, 3,
            "", "", "", "text", "hash-chunk",
            "测试点: 用户输入正确账号密码后进入工作台",
            "{\"fileName\":\"requirements.txt\",\"documentId\":\"doc-analysis-1\"}", now, now
        );
    }

    @Test
    void createsRunWithoutBlockingOnSlowGatewayWorker() throws Exception {
        CountDownLatch gatewayEntered = new CountDownLatch(1);
        CountDownLatch releaseGateway = new CountDownLatch(1);
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenAnswer(invocation -> {
                gatewayEntered.countDown();
                assertTrue(releaseGateway.await(2, TimeUnit.SECONDS));
                return new AiGenerationResult(true, """
                    { "draftTestPoints": [{ "title": "异步完成", "module": "登录", "sourceChunkId": "chunk-analysis-1" }] }
                    """, "req-async", 100, 40, null);
            });

        long started = System.nanoTime();
        MvcResult result = mockMvc.perform(post("/api/ai/analysis-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "projectId": %d,
                      "documentIds": ["%s"],
                      "targetCaseKind": "web_automation",
                      "templateId": "builtin-web",
                      "analysisStrategy": "full"
                    }
                    """.formatted(projectId, documentId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runId").isNotEmpty())
            .andReturn();

        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(durationMs < 1000, "create run should return before the gateway worker completes");

        String runId = objectMapper.readTree(result.getResponse().getContentAsString()).get("runId").asText();
        assertTrue(gatewayEntered.await(1, TimeUnit.SECONDS));
        releaseGateway.countDown();
        awaitRunStatus(runId, "succeeded");
    }

    @Test
    void createsSucceededRunAndDraftTestPointsWhenGatewayReturnsValidJson() throws Exception {
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, """
                {
                  "draftTestPoints": [
                    {
                      "title": "用户登录成功进入工作台",
                      "module": "登录",
                      "caseKind": "interface",
                      "testType": "功能",
                      "priority": "P1",
                      "objective": "验证正确账号密码可登录",
                      "steps": "输入正确账号密码并提交",
                      "expectedResult": "进入工作台",
                      "sourceChunkId": "chunk-analysis-1",
                      "confidence": 0.92,
                      "inferred": false
                    }
                  ]
                }
                """, "req-1", 120, 90, null));

        MvcResult result = mockMvc.perform(post("/api/ai/analysis-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "projectId": %d,
                      "documentIds": ["%s"],
                      "targetCaseKind": "interface",
                      "templateId": "builtin-interface",
                      "analysisStrategy": "balanced"
                    }
                    """.formatted(projectId, documentId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runId").isNotEmpty())
            .andExpect(jsonPath("$.totalChunks").value(1))
            .andReturn();

        String runId = objectMapper.readTree(result.getResponse().getContentAsString()).get("runId").asText();
        awaitRunStatus(runId, "succeeded");

        mockMvc.perform(get("/api/ai/analysis-runs/{runId}/draft-test-points", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].title").value("用户登录成功进入工作台"))
            .andExpect(jsonPath("$.items[0].reviewStatus").value("ai_generated"))
            .andExpect(jsonPath("$.items[0].sourceChunkId").value("chunk-analysis-1"))
            .andExpect(jsonPath("$.items[0].confidenceScore").value(0.92));

        mockMvc.perform(get("/api/ai/analysis-runs/{runId}", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("succeeded"))
            .andExpect(jsonPath("$.completedChunks").value(1))
            .andExpect(jsonPath("$.outputSummary.draftTestPointCount").value(1));
    }

    @Test
    void longDocumentRunProcessesMultipleChunksAndRecordsMultipleLlmCalls() throws Exception {
        jdbcTemplate.update(
            "update ai_source_chunks set content_text = ? where chunk_id = ?",
            """
                # 登录
                用户输入正确账号密码后进入工作台。
                ## 权限
                管理员可以查看用户列表并导出结果。
                """,
            "chunk-analysis-1"
        );

        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, """
                {
                  "draftTestPoints": [
                    {
                      "title": "登录成功进入工作台",
                      "module": "登录",
                      "caseKind": "web_automation",
                      "testType": "功能",
                      "priority": "P1",
                      "objective": "验证正确账号密码可登录",
                      "steps": "goto /login\\ninput #username = admin\\ninput #password = ***\\nclick #submit",
                      "expectedResult": "assertVisible .dashboard",
                      "sourceChunkId": "run-placeholder-planned-1"
                    }
                  ]
                }
                """, "chunk-req-1", 120, 80, null))
            .thenReturn(new AiGenerationResult(true, """
                {
                  "draftTestPoints": [
                    {
                      "title": "管理员导出用户列表",
                      "module": "权限",
                      "caseKind": "web_automation",
                      "testType": "功能",
                      "priority": "P1",
                      "objective": "验证管理员可导出用户列表",
                      "steps": "goto /users\\nclick .export-button",
                      "expectedResult": "assertVisible .export-success",
                      "sourceChunkId": "run-placeholder-planned-2"
                    }
                  ]
                }
                """, "chunk-req-2", 118, 75, null));

        MvcResult result = mockMvc.perform(post("/api/ai/analysis-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "projectId": %d,
                      "documentIds": ["%s"],
                      "targetCaseKind": "web_automation",
                      "templateId": "builtin-web",
                      "analysisStrategy": "full"
                    }
                    """.formatted(projectId, documentId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalChunks").value(2))
            .andReturn();

        String runId = objectMapper.readTree(result.getResponse().getContentAsString()).get("runId").asText();
        awaitRunStatus(runId, "succeeded");

        mockMvc.perform(get("/api/ai/analysis-runs/{runId}/draft-test-points", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].title").value("登录成功进入工作台"))
            .andExpect(jsonPath("$.items[1].title").value("管理员导出用户列表"));

        Integer callCount = jdbcTemplate.queryForObject(
            "select count(*) from ai_call_records where run_id = (select id from ai_analysis_runs where run_id = ?)",
            Integer.class,
            runId
        );
        org.assertj.core.api.Assertions.assertThat(callCount).isEqualTo(2);
    }

    @Test
    void malformedGatewayJsonFailsRunAndDoesNotPersistDraftPoints() throws Exception {
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, "not-json", "req-bad", 10, 2, null));

        MvcResult result = mockMvc.perform(post("/api/ai/analysis-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "projectId": %d,
                      "documentIds": ["%s"],
                      "targetCaseKind": "interface"
                    }
                    """.formatted(projectId, documentId)))
            .andExpect(status().isOk())
            .andReturn();

        String runId = objectMapper.readTree(result.getResponse().getContentAsString()).get("runId").asText();
        awaitRunStatus(runId, "failed");
        mockMvc.perform(get("/api/ai/analysis-runs/{runId}", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errorSummary").value(containsString("Invalid AI output JSON")));
        mockMvc.perform(get("/api/ai/analysis-runs/{runId}/draft-test-points", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void retryFailedRunCreatesNewAttemptWithStoredInputContext() throws Exception {
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, "not-json", "req-bad", 10, 2, null))
            .thenReturn(new AiGenerationResult(true, """
                { "draftTestPoints": [{ "title": "重试后生成", "module": "登录", "sourceChunkId": "chunk-analysis-1" }] }
                """, "req-good", 20, 5, null));

        MvcResult failed = mockMvc.perform(post("/api/ai/analysis-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "projectId": %d,
                      "documentIds": ["%s"],
                      "targetCaseKind": "interface"
                    }
                    """.formatted(projectId, documentId)))
            .andExpect(status().isOk())
            .andReturn();

        String failedRunId = objectMapper.readTree(failed.getResponse().getContentAsString()).get("runId").asText();
        awaitRunStatus(failedRunId, "failed");

        MvcResult retry = mockMvc.perform(post("/api/ai/analysis-runs/{runId}/retry", failedRunId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runId").value(org.hamcrest.Matchers.not(failedRunId)))
            .andExpect(jsonPath("$.inputSummary.retryOfRunId").value(failedRunId))
            .andReturn();
        String retryRunId = objectMapper.readTree(retry.getResponse().getContentAsString()).get("runId").asText();
        awaitRunStatus(retryRunId, "succeeded");
    }

    @Test
    void missingGatewayConfigFailsRunGracefullyInsteadOfReturningServerError() throws Exception {
        jdbcTemplate.update("delete from ai_system_configs");

        MvcResult result = mockMvc.perform(post("/api/ai/analysis-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "projectId": %d,
                      "documentIds": ["%s"],
                      "targetCaseKind": "web_automation"
                    }
                    """.formatted(projectId, documentId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runId").isNotEmpty())
            .andReturn();

        String runId = objectMapper.readTree(result.getResponse().getContentAsString()).get("runId").asText();
        awaitRunStatus(runId, "failed");
        mockMvc.perform(get("/api/ai/analysis-runs/{runId}", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errorSummary").value(containsString("AI 配置未完成")));
    }

    @Test
    void cancelMarksNonCompleteRunAsCancelled() throws Exception {
        String runId = "run-cancellable";
        Instant now = Instant.now();
        jdbcTemplate.update("""
            insert into ai_analysis_runs(
              run_id, project_id, target_case_kind, template_id, analysis_strategy, execution_mode,
              status, worker_status, coverage_status, total_chunks, completed_chunks, current_chunk_id,
              resume_from_chunk_id, error_summary, config_snapshot_json, rule_snapshot_json,
              skill_snapshot_json, schema_snapshot_json, input_summary_json, output_summary_json,
              actor_id, actor_name, started_at, finished_at, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            runId, projectId, "interface", "", "balanced", "inline",
            "queued", "idle", "pending", 0, 0, "", "", "",
            null, null, null, null, "{}", null,
            "system-dev", "Development User", null, null, now, now
        );

        mockMvc.perform(post("/api/ai/analysis-runs/{runId}/cancel", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("cancelled"))
            .andExpect(jsonPath("$.errorSummary").value("用户取消"));
    }

    @Test
    void runEventsSupportAfterSeqReconnectAnchors() throws Exception {
        String runId = "run-events-anchor";
        Instant now = Instant.now();
        jdbcTemplate.update("""
            insert into ai_run_events(
              event_id, run_id, occurred_at, stage, severity, summary, chunk_index, chunk_total,
              attempt_index, provider_id, provider_type, model, duration_ms, retry_reason,
              strategy_snapshot_id, event_seq, created_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "evt-anchor-1", runId, java.sql.Timestamp.from(now), "attempt_started", "info", "开始调用",
            null, null, 1, "prov-1", "openai", "gpt-4.1", 0, "", "", 1L, java.sql.Timestamp.from(now));
        jdbcTemplate.update("""
            insert into ai_run_events(
              event_id, run_id, occurred_at, stage, severity, summary, chunk_index, chunk_total,
              attempt_index, provider_id, provider_type, model, duration_ms, retry_reason,
              strategy_snapshot_id, event_seq, created_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "evt-anchor-2", runId, java.sql.Timestamp.from(now), "run_succeeded", "info", "运行完成",
            null, null, 1, "prov-1", "openai", "gpt-4.1", 12, "", "", 2L, java.sql.Timestamp.from(now));

        // 全量拉取
        mockMvc.perform(get("/api/ai/analysis-runs/{runId}/events", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2));
        // 断线续接：afterSeq=1 只补拉严格更新的 seq=2
        mockMvc.perform(get("/api/ai/analysis-runs/{runId}/events", runId).param("afterSeq", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].eventSeq").value(2));
        // 已追平：afterSeq=2 无新事件（可安全轮询）
        mockMvc.perform(get("/api/ai/analysis-runs/{runId}/events", runId).param("afterSeq", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void planChunksPersistsSemanticAnalysisChunks() throws Exception {
        jdbcTemplate.update(
            "update ai_source_chunks set content_text = ? where chunk_id = ?",
            "# 登录\n用户输入账号密码。\n# 权限\n管理员查看用户列表。",
            "chunk-analysis-1"
        );
        String runId = "run-plan-chunks";
        Instant now = Instant.now();
        jdbcTemplate.update("""
            insert into ai_analysis_runs(
              run_id, project_id, target_case_kind, template_id, analysis_strategy, execution_mode,
              status, worker_status, coverage_status, total_chunks, completed_chunks, current_chunk_id,
              resume_from_chunk_id, error_summary, config_snapshot_json, rule_snapshot_json,
              skill_snapshot_json, schema_snapshot_json, input_summary_json, output_summary_json,
              actor_id, actor_name, started_at, finished_at, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            runId, projectId, "interface", "", "balanced", "inline",
            "queued", "idle", "pending", 0, 0, "", "", "",
            null, null, null, null, "{\"documentIds\":[\"%s\"]}".formatted(documentId), null,
            "system-dev", "Development User", null, null, now, now
        );

        mockMvc.perform(post("/api/ai/analysis-runs/{runId}/plan-chunks", runId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "maxInputTokens": 200,
                      "maxChunks": 1,
                      "analysisScope": "all"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].section").value("登录"))
            .andExpect(jsonPath("$.items[0].status").value("planned"))
            .andExpect(jsonPath("$.items[1].section").value("权限"))
            .andExpect(jsonPath("$.items[1].status").value("skipped"))
            .andExpect(jsonPath("$.items[1].skipReason").value("max_chunks_exceeded"));

        Integer persisted = jdbcTemplate.queryForObject(
            "select count(*) from ai_analysis_chunks where run_id = (select id from ai_analysis_runs where run_id = ?)",
            Integer.class,
            runId
        );
        org.junit.jupiter.api.Assertions.assertEquals(2, persisted);

        mockMvc.perform(get("/api/ai/analysis-runs/{runId}", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalChunks").value(2))
            .andExpect(jsonPath("$.coverageStatus").value("planned"));
    }

    @Test
    void exposesCoverageQuestionsAndToolCallsForRunDetail() throws Exception {
        String runId = "run-detail";
        Instant now = Instant.now();
        jdbcTemplate.update("""
            insert into ai_analysis_runs(
              run_id, project_id, target_case_kind, template_id, analysis_strategy, execution_mode,
              status, worker_status, coverage_status, total_chunks, completed_chunks, current_chunk_id,
              resume_from_chunk_id, error_summary, config_snapshot_json, rule_snapshot_json,
              skill_snapshot_json, schema_snapshot_json, input_summary_json, output_summary_json,
              actor_id, actor_name, started_at, finished_at, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            runId, projectId, "web_automation", "", "full", "multi_pass",
            "failed", "idle", "completed", 2, 1, "chunk-2", "chunk-2", "model timeout",
            "{}", "{}", "{}", "{}", "{\"documentIds\":[\"%s\"]}".formatted(documentId), null,
            "system-dev", "Development User", now, now, now, now
        );
        Long runPk = jdbcTemplate.queryForObject("select id from ai_analysis_runs where run_id = ?", Long.class, runId);
        jdbcTemplate.update("""
            insert into ai_coverage_summaries(run_id, summary_json, warnings_json, created_at, updated_at)
            values (?, ?, ?, ?, ?)
            """,
            runPk,
            "{\"totalDraftPoints\":2,\"moduleCounts\":{\"登录\":1}}",
            "[\"Skipped chunk chunk-2: max_chunks_exceeded\"]",
            now,
            now
        );
        jdbcTemplate.update("""
            insert into ai_unclear_questions(
              question_id, run_id, project_id, severity, question_text, source_ref, status, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "question-detail-1", runPk, projectId, "high", "缺少失败状态说明", "chunk-2", "open", now, now
        );
        jdbcTemplate.update("""
            insert into ai_tool_call_records(
              call_id, run_id, project_id, server_id, tool_name, read_only, status,
              request_summary_json, response_summary_json, error_message, actor_id, created_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "tool-call-detail-1", runId, projectId, "mcp-docs", "searchDocs", true, "succeeded",
            "{\"keyword\":\"登录\"}", "{\"matches\":1}", "", "system-dev", now
        );

        mockMvc.perform(get("/api/ai/analysis-runs/{runId}/coverage-summary", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runId").value(runId))
            .andExpect(jsonPath("$.summary.totalDraftPoints").value(2))
            .andExpect(jsonPath("$.warnings[0]").value("Skipped chunk chunk-2: max_chunks_exceeded"));

        mockMvc.perform(get("/api/ai/analysis-runs/{runId}/unclear-questions", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].questionId").value("question-detail-1"))
            .andExpect(jsonPath("$.items[0].severity").value("high"));

        mockMvc.perform(get("/api/ai/analysis-runs/{runId}/tool-calls", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].callId").value("tool-call-detail-1"))
            .andExpect(jsonPath("$.items[0].toolName").value("searchDocs"));
    }

    private void awaitRunStatus(String runId, String expectedStatus) throws Exception {
        for (int attempt = 0; attempt < 80; attempt += 1) {
            String status = jdbcTemplate.queryForObject(
                "select status from ai_analysis_runs where run_id = ?",
                String.class,
                runId
            );
            if (expectedStatus.equals(status)) {
                return;
            }
            Thread.sleep(50);
        }
        String status = jdbcTemplate.queryForObject(
            "select status from ai_analysis_runs where run_id = ?",
            String.class,
            runId
        );
        throw new AssertionError("Expected run " + runId + " to reach " + expectedStatus + " but was " + status);
    }
}
