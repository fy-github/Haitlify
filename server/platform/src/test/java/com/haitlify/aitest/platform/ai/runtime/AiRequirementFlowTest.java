package com.haitlify.aitest.platform.ai.runtime;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T07：需求分析端到端 integration——fixture endpoint（本地 HTTP 服务扮演模型）→ 上传证据
 * → 分析（真实链路：settings→transport→校验）→ draft point 入库（携带来源与 origin）
 * → 人审门禁（ai_generated，绝不自动 ACCEPTED）。模型不可用/输出不合法路径由既有
 * 负向套件覆盖；本测试钉住成功闭环的真实接线。
 */
@SpringBootTest(properties = "app.ai.runtime.worker-enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiRequirementFlowTest {

    private static final String PROJECT_CODE = "AI_FLOW";

    private static final String CANDIDATE_JSON = """
        {
          "draftTestPoints": [
            {
              "title": "fixture 需求分析端到端",
              "module": "登录",
              "caseKind": "interface",
              "testType": "功能",
              "priority": "P1",
              "objective": "验证 fixture 模型输出被真实链路接收",
              "steps": "输入正确账号密码并提交",
              "expectedResult": "进入工作台",
              "sourceChunkId": "chunk-analysis-1",
              "confidence": 0.93,
              "inferred": false
            }
          ]
        }
        """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private HttpServer fixture;
    private String fixtureBaseUrl;
    private Long projectId;
    private String documentId;

    @BeforeEach
    void setUp() throws Exception {
        fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/v1/chat/completions", exchange -> {
            String body = "{\"id\":\"req-flow-1\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"content\":" + jsonString(CANDIDATE_JSON) + "},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":90,\"total_tokens\":210}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        fixture.start();
        fixtureBaseUrl = "http://127.0.0.1:" + fixture.getAddress().getPort() + "/v1";

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
        jdbcTemplate.update("delete from projects where code = ?", PROJECT_CODE);

        Instant now = Instant.now();
        jdbcTemplate.update(
            "insert into projects(code, name, description, active, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
            PROJECT_CODE, "AI Flow Project", "T07 end-to-end fixture", true, now, now);
        projectId = jdbcTemplate.queryForObject("select id from projects where code = ?", Long.class, PROJECT_CODE);
        jdbcTemplate.update("""
            insert into ai_system_configs(
              config_key, enabled, provider_type, base_url, text_model, vision_model, embedding_model,
              api_key_ciphertext, credential_configured, request_timeout_ms, max_input_length, max_output_length,
              retry_count, request_logging_enabled, external_document_send_allowed, provider_config_json,
              masked_config_json, actor_id, actor_name, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "default", true, "openai-compatible", fixtureBaseUrl, "fixture-model", "", "",
            "fixture-key", true, 8000, 0, 0, 0, false, true, null,
            "****test", "system-dev", "Development User", now, now);

        documentId = "doc-flow-1";
        jdbcTemplate.update("""
            insert into ai_source_documents(
              document_id, project_id, file_name, document_type, content_hash, storage_location,
              extracted_text, parse_metadata_json, parser_version, source_file_metadata_json,
              actor_id, actor_name, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            documentId, projectId, "requirements.txt", "text", "hash-flow-doc", "",
            "项目: Haitlify\n模块: 登录\n测试点: 用户输入正确账号密码后进入工作台",
            "{\"lineCount\":3}", "requirement-document-parser-v1", "{\"fileName\":\"requirements.txt\"}",
            "system-dev", "Development User", now, now);
        Long documentPk = jdbcTemplate.queryForObject(
            "select id from ai_source_documents where document_id = ?", Long.class, documentId);
        jdbcTemplate.update("""
            insert into ai_source_chunks(
              chunk_id, document_id, section_heading, page_number, line_start, line_end,
              table_identifier, image_identifier, figma_node_id, chunk_type, content_hash, content_text,
              evidence_metadata_json, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "chunk-analysis-1", documentPk, "登录", null, 1, 3,
            "", "", "", "text", "hash-flow-chunk",
            "测试点: 用户输入正确账号密码后进入工作台",
            "{\"fileName\":\"requirements.txt\",\"documentId\":\"doc-flow-1\"}", now, now);
    }

    @AfterEach
    void tearDown() {
        fixture.stop(0);
    }

    @Test
    void requirementAnalysisRunsEndToEndAgainstTheFixtureEndpointAndParksAtTheHumanGate() throws Exception {
        String createResponse = mockMvc.perform(post("/api/ai/analysis-runs")
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
            .andReturn().getResponse().getContentAsString();
        String runId = createResponse.replaceAll(".*\"runId\":\"([^\"]+)\".*", "$1");

        awaitRunStatus(runId, "succeeded");

        mockMvc.perform(get("/api/ai/analysis-runs/{runId}/draft-test-points", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].title").value("fixture 需求分析端到端"))
            .andExpect(jsonPath("$.items[0].sourceChunkId").value("chunk-analysis-1"))
            .andExpect(jsonPath("$.items[0].reviewStatus").value("ai_generated"));

        // 人审门禁：产物绝不被自动接受；来源与 origin 可追溯。
        Integer accepted = jdbcTemplate.queryForObject(
            "select count(*) from ai_draft_test_points where acceptance_state = 'ACCEPTED'", Integer.class);
        assertThat(accepted).isZero();
        String origin = jdbcTemplate.queryForObject(
            "select result_origin from ai_draft_test_points where title = ?", String.class,
            "fixture 需求分析端到端");
        assertThat(origin).isEqualTo("MODEL_GENERATED");

        // 调用审计（真实链路经 transport 发往 fixture）：call record 带 provider 证据。
        Integer callRecords = jdbcTemplate.queryForObject(
            "select count(*) from ai_call_records where request_id is not null"
                + " and request_id <> ''", Integer.class);
        assertThat(callRecords).isGreaterThanOrEqualTo(1);
    }

    private void awaitRunStatus(String runId, String expected) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        String status = "";
        while (System.currentTimeMillis() < deadline) {
            String body = mockMvc.perform(get("/api/ai/analysis-runs/{runId}", runId))
                .andReturn().getResponse().getContentAsString();
            status = body.replaceAll(".*\"status\":\"([^\"]+)\".*", "$1");
            if (expected.equals(status)) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        throw new AssertionError("run " + runId + " did not reach " + expected + " (last=" + status + ")");
    }

    private static String jsonString(String raw) {
        StringBuilder escaped = new StringBuilder("\"");
        for (char c : raw.toCharArray()) {
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(c);
            }
        }
        return escaped.append('"').toString();
    }
}
