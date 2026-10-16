package com.haitlify.aitest.platform.ai.draft;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.haitlify.aitest.platform.ai.gateway.AiGateway;
import com.haitlify.aitest.platform.ai.gateway.AiGatewayConfig;
import com.haitlify.aitest.platform.ai.gateway.AiGenerationResult;
import com.haitlify.aitest.platform.webautomation.WebAutomationLocatorDiscoveryService;
import tools.jackson.databind.JsonNode;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.security.enabled=false")
class AiDraftTestPointReviewTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AiGateway aiGateway;

    @MockitoBean
    private WebAutomationLocatorDiscoveryService webAutomationLocatorDiscoveryService;

    private long projectId;
    private long runPk;

    @BeforeEach
    void resetData() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("editor", "n/a", List.of()));
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
        jdbcTemplate.update("delete from ai_rules");
        jdbcTemplate.update("delete from ai_locator_knowledge_history");
        jdbcTemplate.update("delete from ai_locator_knowledge_entries");
        jdbcTemplate.update("delete from project_members where username = 'editor'");
        jdbcTemplate.update("delete from platform_users where username = 'editor'");
        jdbcTemplate.update("delete from projects where code = 'AI_REVIEW'");

        Instant now = Instant.now();
        jdbcTemplate.update(
            "insert into platform_users(username, password_hash, display_name, role, enabled, must_change_password, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?)",
            "editor", "test-only", "Editor", "EDITOR", true, false, now, now
        );
        jdbcTemplate.update(
            "insert into projects(code, name, description, active, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
            "AI_REVIEW",
            "AI Review Project",
            "Review tests",
            true,
            now,
            now
        );
        projectId = jdbcTemplate.queryForObject("select id from projects where code = 'AI_REVIEW'", Long.class);
        jdbcTemplate.update("insert into project_members(project_id, username, created_at) values (?, 'editor', ?)", projectId, now);
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
        jdbcTemplate.update("""
            insert into ai_analysis_runs(
              run_id, project_id, target_case_kind, template_id, analysis_strategy, execution_mode,
              status, worker_status, coverage_status, total_chunks, completed_chunks, current_chunk_id,
              resume_from_chunk_id, error_summary, config_snapshot_json, rule_snapshot_json,
              skill_snapshot_json, schema_snapshot_json, input_summary_json, output_summary_json,
              actor_id, actor_name, started_at, finished_at, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "run-review", projectId, "interface", "builtin-interface", "balanced", "inline",
            "succeeded", "idle", "completed", 1, 1, "", "", "", "{}", "{}", "{}", "{}",
            "{}", "{}", "system-dev", "Development User", now, now, now, now
        );
        runPk = jdbcTemplate.queryForObject("select id from ai_analysis_runs where run_id = 'run-review'", Long.class);
    }

    @Test
    void confirmingDraftChangesStatusAndWritesReviewAction() throws Exception {
        insertDraft("point-confirm", "ai_generated");

        mockMvc.perform(post("/api/ai/draft-test-points/{pointId}/confirm", "point-confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\": \"确认可生成\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reviewStatus").value("confirmed"));

        Integer actions = jdbcTemplate.queryForObject(
            "select count(*) from ai_review_actions where action_type = 'confirm'",
            Integer.class
        );
        org.assertj.core.api.Assertions.assertThat(actions).isEqualTo(1);
    }

    @Test
    void editConfirmChangesStatusToModifiedAndPreservesBeforeAfterContent() throws Exception {
        insertDraft("point-edit", "ai_generated");

        mockMvc.perform(put("/api/ai/draft-test-points/{pointId}", "point-edit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "title": "修改后的测试点",
                      "objective": "修改后的目标",
                      "steps": "修改后的步骤",
                      "expectedResult": "修改后的预期",
                      "comment": "补充验收口径"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("修改后的测试点"))
            .andExpect(jsonPath("$.reviewStatus").value("modified"));

        String afterValue = jdbcTemplate.queryForObject(
            "select after_value_json from ai_review_actions where action_type = 'edit_confirm'",
            String.class
        );
        org.assertj.core.api.Assertions.assertThat(afterValue).contains("修改后的测试点");
    }

    @Test
    void rejectingDraftExcludesItFromGeneration() throws Exception {
        insertDraft("point-reject", "ai_generated");

        mockMvc.perform(post("/api/ai/draft-test-points/{pointId}/reject", "point-reject")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\": \"需求依据不足\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reviewStatus").value("rejected"));

        mockMvc.perform(post("/api/ai/draft-test-points/generate-cases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pointIds\": [\"point-reject\"]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("confirmed/modified")));
    }

    @Test
    void generatingCasesRejectsUnreviewedDraft() throws Exception {
        insertDraft("point-unreviewed", "ai_generated");

        mockMvc.perform(post("/api/ai/draft-test-points/generate-cases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pointIds\": [\"point-unreviewed\"]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("confirmed/modified")));
    }

    @Test
    void generatingCasesUsesClearErrorWhenPointIsMissing() throws Exception {
        mockMvc.perform(post("/api/ai/draft-test-points/generate-cases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pointIds\": [\"missing-point\"]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("当前批次测试点不存在或已被删除")));
    }

    @Test
    void generatingCasesAcceptsConfirmedDraft() throws Exception {
        insertDraft("point-ready", "confirmed");
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, """
                {
                  "cases": [
                    {
                      "sourcePointId": "point-ready",
                      "title": "用户登录成功进入工作台",
                      "module": "登录",
                      "caseKind": "interface",
                      "objective": "验证正确账号密码可登录",
                      "steps": "输入正确账号密码并提交",
                      "expectedResult": "进入工作台"
                    }
                  ]
                }
                """, "case-req-ready", 80, 120, null));

        mockMvc.perform(post("/api/ai/draft-test-points/generate-cases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pointIds\": [\"point-ready\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].sourcePointId").value("point-ready"))
            .andExpect(jsonPath("$.items[0].title").value("用户登录成功进入工作台"));
    }

    @Test
    void generatingCasesCallsAiAndReturnsModelCases() throws Exception {
        insertDraft("point-ai-case", "confirmed");
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, """
                {
                  "cases": [
                    {
                      "sourcePointId": "point-ai-case",
                      "title": "AI生成：登录后校验工作台首屏",
                      "module": "登录",
                      "caseKind": "web_automation",
                      "pageName": "工作台",
                      "startUrl": "/dashboard",
                      "browser": "chromium",
                      "viewport": "1440x900",
                      "precondition": "环境登录流程已完成",
                      "objective": "验证登录后首屏关键元素展示",
                      "steps": "goto /dashboard\\nassertVisible text=工作台",
                      "assertions": "assertVisible text=工作台",
                      "expectedResult": "进入工作台并展示导航",
                      "sourceFile": "requirements.txt",
                      "sourceText": "用户登录后进入工作台"
                    }
                  ]
                }
                """, "case-req-1", 80, 120, null));

        mockMvc.perform(post("/api/ai/draft-test-points/generate-cases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pointIds\": [\"point-ai-case\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].sourcePointId").value("point-ai-case"))
            .andExpect(jsonPath("$.items[0].title").value("AI生成：登录后校验工作台首屏"));

        Integer calls = jdbcTemplate.queryForObject("select count(*) from ai_call_records where agent_id = 'formal-case-generation'", Integer.class);
        org.assertj.core.api.Assertions.assertThat(calls).isEqualTo(1);
    }

    @Test
    void compactsPromptEvidenceWhileKeepingTheOriginalEvidenceOnTheGeneratedCase() throws Exception {
        String originalSourceText = "日志管理导出脱敏规则\n" + "背景资料".repeat(2_000)
            + "\n用户管理导出不得包含 Token、密钥或 Cookie";
        insertDraft("point-compact-evidence", "confirmed", "web_automation", originalSourceText);
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, """
                {"cases":[{
                  "sourcePointId":"point-compact-evidence", "title":"日志导出脱敏", "module":"日志管理",
                  "caseKind":"web_automation", "pageName":"日志导出", "startUrl":"/logs",
                  "browser":"chromium", "viewport":"1440x900", "precondition":"已登录",
                  "objective":"验证导出脱敏", "steps":"goto /logs\\nassertUrl /logs",
                  "assertions":"assertUrl /logs", "expectedResult":"敏感字段未导出",
                  "sourceFile":"模型返回的文件名", "sourceText":"模型返回的摘要"
                }]}
                """, "case-compact-evidence", 20, 30, null));

        mockMvc.perform(post("/api/ai/draft-test-points/generate-cases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pointIds\": [\"point-compact-evidence\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].sourceText").value(originalSourceText))
            .andExpect(jsonPath("$.items[0].sourceFile").value("requirements.txt"));

        ArgumentCaptor<JsonNode> prompts = ArgumentCaptor.forClass(JsonNode.class);
        verify(aiGateway).structuredGenerate(any(AiGatewayConfig.class), prompts.capture());
        String promptEvidence = prompts.getValue().path("confirmedDraftTestPoints").get(0).path("sourceText").asText();
        org.assertj.core.api.Assertions.assertThat(promptEvidence.length()).isLessThanOrEqualTo(2_400);
        org.assertj.core.api.Assertions.assertThat(promptEvidence).contains("日志管理导出脱敏规则");
    }

    @Test
    void generatingWebAutomationCasesSendsStrictDslContractToAi() throws Exception {
        insertDraft("point-web-dsl", "confirmed", "web_automation");
        mockMvc.perform(put("/api/ai/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "rules": {
                        "selectorPolicy": "role-or-test-id",
                        "requireAssertion": true
                      }
                    }
                    """))
            .andExpect(status().isOk());
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, """
                {
                  "cases": [
                    {
                      "sourcePointId": "point-web-dsl",
                      "title": "AI生成：登录后进入用户管理",
                      "module": "登录",
                      "caseKind": "web_automation",
                      "pageName": "用户管理",
                      "startUrl": "/login",
                      "browser": "chromium",
                      "viewport": "1440x900",
                      "precondition": "环境登录流程已配置",
                      "objective": "验证登录后可进入用户管理",
                      "steps": "goto /login\\ninput css=#username = {{webUsername}}\\nclick role=button[name=\\"登录\\"]\\nassertVisible text=用户管理",
                      "assertions": "assertVisible text=用户管理",
                      "expectedResult": "用户管理可见",
                      "sourceFile": "requirements.txt",
                      "sourceText": "测试点: 用户登录成功"
                    }
                  ]
                }
                """, "case-req-web", 80, 120, null));

        mockMvc.perform(post("/api/ai/draft-test-points/generate-cases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pointIds\": [\"point-web-dsl\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].caseKind").value("web_automation"));

        ArgumentCaptor<JsonNode> promptCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(aiGateway).structuredGenerate(any(AiGatewayConfig.class), promptCaptor.capture());
        String promptJson = promptCaptor.getValue().toPrettyString();
        org.assertj.core.api.Assertions.assertThat(promptJson).contains("webAutomationDslActions");
        org.assertj.core.api.Assertions.assertThat(promptJson).contains("goto <url>");
        org.assertj.core.api.Assertions.assertThat(promptJson).contains("input <locator> = <literal|{{variable}}>");
        org.assertj.core.api.Assertions.assertThat(promptJson).doesNotContain("manual <instruction>");
        org.assertj.core.api.Assertions.assertThat(promptJson).contains("禁止输出自然语言段落");
        org.assertj.core.api.Assertions.assertThat(promptJson).contains("formal-web-case-generation");
        org.assertj.core.api.Assertions.assertThat(promptJson).contains("promptTemplate");
        org.assertj.core.api.Assertions.assertThat(promptJson).contains("system-v2");
        org.assertj.core.api.Assertions.assertThat(promptJson).contains("role-or-test-id");

        String ruleVersion = jdbcTemplate.queryForObject(
            "select rule_version from ai_call_records where agent_id = 'formal-case-generation'",
            String.class
        );
        org.assertj.core.api.Assertions.assertThat(ruleVersion).isEqualTo("system-v2");
    }

    @Test
    void repairsInvalidWebAutomationOutputOnce() throws Exception {
        insertDraft("point-repair", "confirmed", "web_automation");
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, """
                {"cases":[{
                  "sourcePointId":"point-repair",
                  "title":"进入用户管理",
                  "module":"登录",
                  "caseKind":"web_automation",
                  "pageName":"用户管理",
                  "startUrl":"/user-center/users",
                  "browser":"edge",
                  "viewport":"1440x900",
                  "precondition":"环境登录流程已完成",
                  "objective":"验证用户管理可访问",
                  "naturalSteps":"进入用户管理页面\\n查看用户列表",
                  "naturalExpectedResult":"用户管理页面可见",
                  "steps":"goto /user-center/users\\nassertVisible text=用户管理",
                  "assertions":"assertVisible text=用户管理",
                  "expectedResult":"用户管理标题可见",
                  "sourceFile":"requirements.txt",
                  "sourceText":"用户登录后可进入用户管理"
                }]}
                """, "case-repaired", 25, 45, null));

        mockMvc.perform(post("/api/ai/draft-test-points/generate-cases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pointIds\": [\"point-repair\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].browser").value("msedge"))
            .andExpect(jsonPath("$.items[0].viewport").value("1440x900"))
            .andExpect(jsonPath("$.items[0].pageName").value("用户管理"));

        ArgumentCaptor<JsonNode> prompts = ArgumentCaptor.forClass(JsonNode.class);
        verify(aiGateway, times(1)).structuredGenerate(any(AiGatewayConfig.class), prompts.capture());
        org.assertj.core.api.Assertions.assertThat(prompts.getValue().path("confirmedDraftTestPoints").toString())
            .contains("point-repair");
    }

    @Test
    void addsAnExecutableAssertionWhenTheModelOmitsOnlyThatStep() throws Exception {
        insertDraft("point-missing-assertion", "confirmed", "web_automation");
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, """
                {"cases":[{
                  "sourcePointId":"point-missing-assertion",
                  "title":"进入用户管理",
                  "module":"登录",
                  "caseKind":"web_automation",
                  "pageName":"用户管理",
                  "startUrl":"/user-center/users",
                  "browser":"chromium",
                  "viewport":"1440x900",
                  "precondition":"环境登录流程已完成",
                  "objective":"验证用户管理可访问",
                  "steps":"goto /user-center/users\\nassertVisible text=用户管理",
                  "assertions":"assertVisible text=用户管理",
                  "expectedResult":"用户管理标题可见",
                  "sourceFile":"requirements.txt",
                  "sourceText":"用户登录后可进入用户管理"
                }]}
                """, "case-missing-assertion", 25, 45, null));

        mockMvc.perform(post("/api/ai/draft-test-points/generate-cases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pointIds\": [\"point-missing-assertion\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].steps", containsString("assertVisible text=用户管理")))
            .andExpect(jsonPath("$.items[0].assertions", containsString("assertVisible text=用户管理")));

        verify(aiGateway, times(1)).structuredGenerate(any(AiGatewayConfig.class), any());
    }

    @Test
    void groundsEveryBrowserEnabledBatchInCollectedLocatorEvidence() throws Exception {
        insertDraft("point-browser-grounded", "confirmed", "web_automation");
        when(webAutomationLocatorDiscoveryService.discover(any()))
            .thenReturn(new WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse(
                "https://test.example.com/users",
                List.of(new WebAutomationLocatorDiscoveryService.PageLocatorEvidence(
                    "element-1", "role=heading[name=用户管理]", "用户管理", "heading", "h1", "", "role"
                ))
            ));
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, """
                {"cases":[{
                  "sourcePointId":"point-browser-grounded", "title":"查看用户管理", "module":"用户",
                  "caseKind":"web_automation", "pageName":"用户管理", "startUrl":"/users",
                  "browser":"chromium", "viewport":"1440x900", "precondition":"已登录",
                  "objective":"验证用户管理页面", "naturalSteps":"进入用户管理页面\\n查看用户管理标题",
                  "naturalExpectedResult":"用户管理标题可见",
                  "steps":"goto /users\\nassertVisible role=heading[name=用户管理]",
                  "assertions":"assertVisible role=heading[name=用户管理]", "expectedResult":"用户管理标题可见",
                  "sourceFile":"requirements.txt", "sourceText":"可查看用户管理"
                }]}
                """, "case-browser-grounded", 20, 30, null));

        mockMvc.perform(post("/api/ai/draft-test-points/generate-cases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"pointIds":["point-browser-grounded"],"locatorDiscovery":{
                      "baseUrl":"https://test.example.com","browser":"chromium","viewport":"1440x900",
                      "headless":true,"timeoutMs":10000,"waitUntil":"domcontentloaded",
                      "authToken":"token-1",
                      "extraHeaders":{"Cookie":"example-token=cookie-token","isAjax":"1"},
                      "cookieOrigins":["https://api.test.example.com"]
                    }}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].steps", containsString("role=heading[name=用户管理]")));

        ArgumentCaptor<WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryRequest> discoveryRequests = ArgumentCaptor.forClass(WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryRequest.class);
        verify(webAutomationLocatorDiscoveryService).discover(discoveryRequests.capture());
        org.assertj.core.api.Assertions.assertThat(discoveryRequests.getValue().url()).isEqualTo("https://test.example.com/users");
        org.assertj.core.api.Assertions.assertThat(discoveryRequests.getValue().authToken()).isEqualTo("token-1");
        org.assertj.core.api.Assertions.assertThat(discoveryRequests.getValue().extraHeaders()).containsEntry("Cookie", "example-token=cookie-token");
        org.assertj.core.api.Assertions.assertThat(discoveryRequests.getValue().cookieOrigins()).contains("https://api.test.example.com");

        ArgumentCaptor<JsonNode> prompts = ArgumentCaptor.forClass(JsonNode.class);
        verify(aiGateway, times(1)).structuredGenerate(any(AiGatewayConfig.class), prompts.capture());
        JsonNode groundedPrompt = prompts.getValue();
        org.assertj.core.api.Assertions.assertThat(groundedPrompt.path("skillId").asText()).isEqualTo("formal-web-case-generation");
        org.assertj.core.api.Assertions.assertThat(groundedPrompt.path("effectiveRules").isMissingNode()).isFalse();
        org.assertj.core.api.Assertions.assertThat(groundedPrompt.path("browserLocatorPlanning").asBoolean()).isTrue();
        org.assertj.core.api.Assertions.assertThat(groundedPrompt.path("confirmedDraftTestPoints").toString())
            .contains("point-browser-grounded");
    }

    @Test
    void blocksCompilationWhenBrowserExplorationIsNotMatched() throws Exception {
        insertDraft("point-browser-mismatch", "confirmed", "web_automation", "用户管理页面展示用户列表");
        WebAutomationLocatorDiscoveryService.ExplorationScores scores =
            new WebAutomationLocatorDiscoveryService.ExplorationScores(20, 0, 0, 20, false);
        WebAutomationLocatorDiscoveryService.ExplorationReport report =
            new WebAutomationLocatorDiscoveryService.ExplorationReport(
                "mismatch", List.of(), scores, List.of("https://test.example.com/users"),
                "目标元素未命中", 1200L, null, 8800L, null
            );
        when(webAutomationLocatorDiscoveryService.discover(any()))
            .thenReturn(new WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse(
                "https://test.example.com/users", List.of(), report
            ));
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, """
                {"cases":[{"sourcePointId":"point-browser-mismatch","title":"用户列表","module":"用户","pageName":"用户管理","startUrl":"/users","naturalSteps":["进入用户管理"],"naturalExpectedResult":"用户列表可见"}]}
                """, "case-plan-mismatch", 20, 30, null));

        mockMvc.perform(post("/api/ai/draft-test-points/generate-cases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"pointIds":["point-browser-mismatch"],"locatorDiscovery":{
                      "baseUrl":"https://test.example.com","browser":"chromium","viewport":"1440x900",
                      "headless":true,"timeoutMs":10000,"waitUntil":"domcontentloaded"
                    }}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("目标元素未命中")));

        verify(aiGateway, times(1)).structuredGenerate(any(AiGatewayConfig.class), any());
    }

    @Test
    void acceptsNaturalLanguageFirstPhaseCasesBeforeBrowserGrounding() throws Exception {
        insertDraft(
            "point-natural-first-phase",
            "confirmed",
            "web_automation",
            "项目管理页面需要展示项目列表，列表为空时展示空状态文案"
        );
        when(webAutomationLocatorDiscoveryService.discover(any()))
            .thenReturn(new WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse(
                "https://test.example.com/#/project",
                List.of(new WebAutomationLocatorDiscoveryService.PageLocatorEvidence(
                    "empty-state", "text=暂无数据", "暂无数据", "status", "div", "", "text"
                ))
            ));
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, """
                {"cases":[{
                  "sourcePointId":"point-natural-first-phase",
                  "title":"项目管理列表空状态展示",
                  "module":"项目",
                  "caseKind":"web_automation",
                  "pageName":"项目管理",
                  "startUrl":"/project",
                  "browser":"chromium", "viewport":"1440x900", "precondition":"已登录",
                  "objective":"验证项目管理列表空状态",
                  "naturalSteps":"进入项目管理页面\\n观察项目列表区域",
                  "naturalExpectedResult":"项目列表为空时展示暂无数据",
                  "steps":"goto /project\\nassertVisible text=暂无数据",
                  "assertions":"assertVisible text=暂无数据", "expectedResult":"项目列表为空时展示暂无数据",
                  "sourceFile":"requirements.txt", "sourceText":"项目管理页面需要展示项目列表，列表为空时展示空状态文案"
                }]}
                """, "case-natural-grounded", 20, 30, null));

        mockMvc.perform(post("/api/ai/draft-test-points/generate-cases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"pointIds":["point-natural-first-phase"],"locatorDiscovery":{
                      "baseUrl":"https://test.example.com/#/","browser":"chromium","viewport":"1440x900",
                      "headless":true,"timeoutMs":10000,"waitUntil":"domcontentloaded"
                    }}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].steps", containsString("assertVisible text=暂无数据")))
            .andExpect(jsonPath("$.items[0].naturalSteps", containsString("进入项目管理页面")))
            .andExpect(jsonPath("$.items[0].naturalSteps", containsString("观察项目列表区域")))
            .andExpect(jsonPath("$.items[0].naturalExpectedResult").value("项目列表为空时展示暂无数据"));
    }

    @Test
    void hydratesMissingNaturalFieldsFromArrayBasedFirstPhasePlan() throws Exception {
        insertDraft("point-natural-array-fallback", "confirmed", "web_automation", "项目管理页面展示项目列表");
        when(webAutomationLocatorDiscoveryService.discover(any()))
            .thenReturn(new WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse(
                "https://test.example.com/#/project",
                List.of(new WebAutomationLocatorDiscoveryService.PageLocatorEvidence(
                    "project-list", "text=项目列表", "项目列表", "heading", "div", "", "text"
                ))
            ));
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, """
                {"cases":[{
                  "sourcePointId":"point-natural-array-fallback",
                  "title":"项目管理列表展示", "module":"项目", "caseKind":"web_automation", "pageName":"项目管理", "startUrl":"/project",
                  "browser":"chromium", "viewport":"1440x900", "precondition":"已登录", "objective":"验证项目列表展示",
                  "naturalSteps":"进入项目管理页面\\n查看项目列表区域",
                  "naturalExpectedResult":"项目列表区域可见",
                  "steps":"goto /project\\nassertVisible text=项目列表", "assertions":"assertVisible text=项目列表",
                  "expectedResult":"项目列表区域可见", "sourceFile":"requirements.txt", "sourceText":"项目管理页面展示项目列表"
                }]}
                """, "case-natural-array-grounded", 20, 30, null));

        mockMvc.perform(post("/api/ai/draft-test-points/generate-cases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"pointIds":["point-natural-array-fallback"],"locatorDiscovery":{
                      "baseUrl":"https://test.example.com/#/","browser":"chromium","viewport":"1440x900",
                      "headless":true,"timeoutMs":10000,"waitUntil":"domcontentloaded"
                    }}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].naturalSteps", containsString("进入项目管理页面")))
            .andExpect(jsonPath("$.items[0].naturalSteps", containsString("查看项目列表区域")))
            .andExpect(jsonPath("$.items[0].naturalExpectedResult").value("项目列表区域可见"));
    }

    @Test
    void revalidatesLocatorKnowledgeInTheCurrentBrowserEvenWhenFingerprintMatches() throws Exception {
        insertDraft(
            "point-knowledge-hit",
            "confirmed",
            "web_automation",
            "日志管理页面应展示全部日志、操作日志、登录日志、设备日志、告警日志五个 Tab"
        );
        insertLocatorKnowledge("known-all-log", "日志管理", "日志管理页面", "全部日志", "text=全部日志");
        insertLocatorKnowledge("known-alarm-log", "日志管理", "日志管理页面", "告警日志", "text=告警日志");
        jdbcTemplate.update("update ai_locator_knowledge_entries set page_fingerprint = ?, confidence_score = 95, metadata_json = ? where entry_id in ('known-all-log', 'known-alarm-log')",
            "sha256:new", "{\"status\":\"matched\",\"navigationTrail\":[\"https://test.example.com/#/project\"]}");
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, """
                {"cases":[{
                  "sourcePointId":"point-knowledge-hit",
                  "title":"日志管理页面展示五个 Tab",
                  "module":"日志管理",
                  "caseKind":"web_automation",
                  "pageName":"日志管理页面",
                  "startUrl":"/project",
                  "pageFingerprint":"sha256:new",
                  "browser":"chromium", "viewport":"1440x900", "precondition":"已登录",
                  "objective":"验证日志管理页面 Tab 展示",
                  "naturalSteps":"进入日志管理页面\\n查看日志 Tab 区域",
                  "naturalExpectedResult":"展示全部日志、操作日志、登录日志、设备日志、告警日志",
                  "browser":"chromium", "viewport":"1440x900", "precondition":"已登录",
                  "steps":"goto /project\\nassertVisible text=全部日志\\nassertVisible text=告警日志",
                  "assertions":"assertVisible text=全部日志\\nassertVisible text=告警日志", "expectedResult":"日志管理页面展示目标 Tab",
                  "sourceFile":"requirements.txt", "sourceText":"日志管理页面应展示全部日志、操作日志、登录日志、设备日志、告警日志五个 Tab"
                }]}
                """, "case-knowledge-grounded", 20, 30, null));

        when(webAutomationLocatorDiscoveryService.discover(any()))
            .thenReturn(new WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse(
                "https://test.example.com/#/project",
                List.of(
                    new WebAutomationLocatorDiscoveryService.PageLocatorEvidence("all", "text=全部日志", "全部日志", "tab", "div", "", "text"),
                    new WebAutomationLocatorDiscoveryService.PageLocatorEvidence("alarm", "text=告警日志", "告警日志", "tab", "div", "", "text")
                )
            ));

        mockMvc.perform(post("/api/ai/draft-test-points/generate-cases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"pointIds":["point-knowledge-hit"],"locatorDiscovery":{
                      "baseUrl":"https://test.example.com/#/","browser":"chromium","viewport":"1440x900",
                      "headless":true,"timeoutMs":10000,"waitUntil":"domcontentloaded"
                    }}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].steps", containsString("assertVisible text=全部日志")))
            .andExpect(jsonPath("$.items[0].steps", containsString("assertVisible text=告警日志")));

        verify(webAutomationLocatorDiscoveryService).discover(any());
        ArgumentCaptor<JsonNode> prompts = ArgumentCaptor.forClass(JsonNode.class);
        verify(aiGateway, times(1)).structuredGenerate(any(AiGatewayConfig.class), prompts.capture());
        org.assertj.core.api.Assertions.assertThat(prompts.getValue().path("browserLocatorPlanning").asBoolean()).isTrue();
        org.assertj.core.api.Assertions.assertThat(prompts.getValue().path("confirmedDraftTestPoints").toString())
            .contains("point-knowledge-hit");
    }

    @Test
    void refreshesLocatorEvidenceWhenStoredPageEntriesMissBusinessAction() throws Exception {
        insertDraft("point-knowledge-business-action", "confirmed", "web_automation", "即将到期免费体验版显示停用按钮并允许停用");
        insertLocatorKnowledge("known-project-navigation", "威胁情报业务", "威胁情报业务列表页", "项目", "role=button[name=\"项目\"]");
        when(webAutomationLocatorDiscoveryService.discover(any()))
            .thenReturn(new WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse(
                "https://test.example.com/#/threat-intelligence",
                List.of(new WebAutomationLocatorDiscoveryService.PageLocatorEvidence(
                    "disable-button", "role=button[name=\"停用\"]", "停用", "button", "button", "", "role"
                ))
            ));
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, """
                {"cases":[{"sourcePointId":"point-knowledge-business-action","title":"即将到期免费体验版显示停用按钮并允许停用","module":"威胁情报业务","caseKind":"web_automation","pageName":"威胁情报业务列表页","startUrl":"/threat-intelligence","browser":"chromium","viewport":"1440x900","precondition":"已登录","objective":"验证即将到期的免费体验版可以执行停用","naturalSteps":"进入威胁情报业务列表页\\n检查即将到期记录的停用按钮","naturalExpectedResult":"即将到期的免费体验版记录操作列展示停用按钮","steps":"goto /threat-intelligence\\nassertVisible role=button[name=\\\"停用\\\"]","assertions":"assertVisible role=button[name=\\\"停用\\\"]","expectedResult":"即将到期的免费体验版记录操作列展示停用按钮","sourceFile":"requirements.txt","sourceText":"即将到期免费体验版显示停用按钮并允许停用"}]}
                """, "knowledge-action-grounded", 20, 30, null));

        mockMvc.perform(post("/api/ai/draft-test-points/generate-cases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"pointIds":["point-knowledge-business-action"],"locatorDiscovery":{
                      "baseUrl":"https://test.example.com/#/","browser":"chromium","viewport":"1440x900",
                      "headless":true,"timeoutMs":10000,"waitUntil":"domcontentloaded"
                    }}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].steps").value(containsString("assertVisible role=button[name=\"停用\"]")));

        verify(webAutomationLocatorDiscoveryService).discover(any());
    }

    @Test
    void storesBrowserDiscoveredLocatorsBackIntoKnowledgeLibraryWhenMissing() throws Exception {
        insertDraft("point-knowledge-miss", "confirmed", "web_automation", "设备日志页面应展示设备日志标题");
        when(webAutomationLocatorDiscoveryService.discover(any()))
            .thenReturn(new WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse(
                "https://test.example.com/#/device-log",
                List.of(new WebAutomationLocatorDiscoveryService.PageLocatorEvidence(
                    "device-log-title", "role=heading[name=设备日志]", "设备日志", "heading", "h1", "", "role"
                ))
            ));
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, """
                {"cases":[{
                  "sourcePointId":"point-knowledge-miss",
                  "title":"设备日志标题展示",
                  "module":"日志管理",
                  "caseKind":"web_automation",
                  "pageName":"设备日志",
                  "startUrl":"/device-log",
                  "browser":"chromium", "viewport":"1440x900", "precondition":"已登录",
                  "objective":"验证设备日志标题展示",
                  "naturalSteps":"进入设备日志页面",
                  "naturalExpectedResult":"设备日志标题可见",
                  "steps":"goto /device-log\\nassertVisible role=heading[name=设备日志]",
                  "assertions":"assertVisible role=heading[name=设备日志]", "expectedResult":"设备日志标题可见",
                  "sourceFile":"requirements.txt", "sourceText":"设备日志页面应展示设备日志标题"
                }]}
                """, "case-knowledge-miss-grounded", 20, 30, null));

        mockMvc.perform(post("/api/ai/draft-test-points/generate-cases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"pointIds":["point-knowledge-miss"],"locatorDiscovery":{
                      "baseUrl":"https://test.example.com/#/","browser":"chromium","viewport":"1440x900",
                      "headless":true,"timeoutMs":10000,"waitUntil":"domcontentloaded"
                    }}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].steps", containsString("role=heading[name=设备日志]")));

        Integer stored = jdbcTemplate.queryForObject(
            "select count(*) from ai_locator_knowledge_entries where project_id = ? and module_name = '日志管理' and page_name = '设备日志' and locator = 'role=heading[name=设备日志]' and source = 'agent_browser'",
            Integer.class,
            projectId
        );
        org.assertj.core.api.Assertions.assertThat(stored).isEqualTo(1);
    }

    @Test
    void recoversBrowserDiscoveryStartUrlFromPlanningGotoStep() throws Exception {
        insertDraft("point-planning-goto", "confirmed", "web_automation");
        when(webAutomationLocatorDiscoveryService.discover(any()))
            .thenReturn(new WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse(
                "https://test.example.com/project",
                List.of(new WebAutomationLocatorDiscoveryService.PageLocatorEvidence(
                    "element-1", "text=项目管理", "项目管理", "h1", "h1", "", "text"
                ))
            ));
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, """
                {"cases":[{
                  "sourcePointId":"point-planning-goto", "title":"查看项目管理", "module":"项目",
                  "caseKind":"web_automation", "pageName":"项目管理", "startUrl":"/project",
                  "browser":"chromium", "viewport":"1440x900", "precondition":"已登录",
                  "objective":"验证项目管理页面",
                  "naturalSteps":"进入项目管理页面",
                  "naturalExpectedResult":"项目管理标题可见",
                  "steps":"goto /project\\nassertVisible text=项目管理",
                  "assertions":"assertVisible text=项目管理", "expectedResult":"项目管理标题可见",
                  "sourceFile":"requirements.txt", "sourceText":"可查看项目管理"
                }]}
                """, "case-browser-grounded", 20, 30, null));

        mockMvc.perform(post("/api/ai/draft-test-points/generate-cases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"pointIds":["point-planning-goto"],"locatorDiscovery":{
                      "baseUrl":"https://test.example.com/#/","browser":"chromium","viewport":"1440x900",
                      "headless":true,"timeoutMs":10000,"waitUntil":"domcontentloaded"
                    }}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].startUrl").value("/project"));

        ArgumentCaptor<WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryRequest> discoveryRequests = ArgumentCaptor.forClass(WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryRequest.class);
        verify(webAutomationLocatorDiscoveryService).discover(discoveryRequests.capture());
        org.assertj.core.api.Assertions.assertThat(discoveryRequests.getValue().url()).isEqualTo("https://test.example.com/#/project");
        org.assertj.core.api.Assertions.assertThat(discoveryRequests.getValue().navigationHints())
            .contains("项目管理");
    }

    @Test
    void recoversBrowserDiscoveryStartUrlFromPlanningStepArray() throws Exception {
        insertDraft("point-planning-step-array", "confirmed", "web_automation");
        when(webAutomationLocatorDiscoveryService.discover(any()))
            .thenReturn(new WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse(
                "https://test.example.com/#/project",
                List.of(new WebAutomationLocatorDiscoveryService.PageLocatorEvidence(
                    "element-1", "text=项目管理", "项目管理", "h1", "h1", "", "text"
                ))
            ));
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, """
                {"cases":[{
                  "sourcePointId":"point-planning-step-array", "title":"查看项目管理", "module":"项目",
                  "caseKind":"web_automation", "pageName":"项目管理", "startUrl":"/project",
                  "browser":"chromium", "viewport":"1440x900", "precondition":"已登录",
                  "objective":"验证项目管理页面",
                  "naturalSteps":"进入项目管理页面",
                  "naturalExpectedResult":"项目管理标题可见",
                  "steps":"goto /project\\nassertVisible text=项目管理",
                  "assertions":"assertVisible text=项目管理", "expectedResult":"项目管理标题可见",
                  "sourceFile":"requirements.txt", "sourceText":"可查看项目管理"
                }]}
                """, "case-browser-grounded", 20, 30, null));

        mockMvc.perform(post("/api/ai/draft-test-points/generate-cases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"pointIds":["point-planning-step-array"],"locatorDiscovery":{
                      "baseUrl":"https://test.example.com/#/","browser":"chromium","viewport":"1440x900",
                      "headless":true,"timeoutMs":10000,"waitUntil":"domcontentloaded"
                    }}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].startUrl").value("/project"));

        ArgumentCaptor<WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryRequest> discoveryRequests = ArgumentCaptor.forClass(WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryRequest.class);
        verify(webAutomationLocatorDiscoveryService).discover(discoveryRequests.capture());
        org.assertj.core.api.Assertions.assertThat(discoveryRequests.getValue().url()).isEqualTo("https://test.example.com/#/project");
    }

    @Test
    void rejectsLocatorsThatWereNotObservedByTheBrowser() throws Exception {
        insertDraft("point-unobserved-locator", "confirmed", "web_automation");
        when(webAutomationLocatorDiscoveryService.discover(any()))
            .thenReturn(new WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse(
                "https://test.example.com/users",
                List.of(new WebAutomationLocatorDiscoveryService.PageLocatorEvidence(
                    "element-1", "testId=user-page", "用户页", "main", "main", "", "testId"
                ))
            ));
        AiGenerationResult plan = new AiGenerationResult(true, """
            {"cases":[{
              "sourcePointId":"point-unobserved-locator","title":"查看用户管理","module":"用户",
              "caseKind":"web_automation","pageName":"用户管理","startUrl":"/users","browser":"chromium",
              "viewport":"1440x900","precondition":"已登录","objective":"验证用户管理页面",
              "naturalSteps":"进入用户管理页面\\n查看用户管理标题","naturalExpectedResult":"用户管理标题可见",
              "steps":"goto /users\\nassertVisible testId=unobserved-locator","assertions":"assertVisible testId=unobserved-locator",
              "expectedResult":"用户管理标题可见","sourceFile":"requirements.txt","sourceText":"可查看用户管理"
            }]}
            """, "case-plan", 20, 30, null);
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(plan);

        mockMvc.perform(post("/api/ai/draft-test-points/generate-cases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"pointIds":["point-unobserved-locator"],"locatorDiscovery":{
                      "baseUrl":"https://test.example.com","browser":"chromium","viewport":"1440x900",
                      "headless":true,"timeoutMs":10000,"waitUntil":"domcontentloaded"
                    }}
            """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("执行步骤中的元素断言未命中自然语言步骤/预期中的目标元素，不能用全局导航按钮替代业务页面断言")));

        verify(aiGateway, times(1)).structuredGenerate(any(AiGatewayConfig.class), any());
    }

    @Test
    void recoversGoalAssertionsWithoutKeepingGenericNavigationAssertions() throws Exception {
        insertDraft(
            "point-log-permission-generic-menu",
            "confirmed",
            "web_automation",
            "有日志权限角色可查看日志管理菜单及日志范围；无日志权限普通成员不可查看日志管理菜单"
        );
        when(webAutomationLocatorDiscoveryService.discover(any()))
            .thenReturn(new WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse(
                "https://app-dev.example.test/",
                List.of(
                    new WebAutomationLocatorDiscoveryService.PageLocatorEvidence(
                        "nav-manage", "role=button[name=\"管理\"]", "管理", "button", "button", "", "role"
                    ),
                    new WebAutomationLocatorDiscoveryService.PageLocatorEvidence(
                        "nav-project", "role=button[name=\"项目\"]", "项目", "button", "button", "", "role"
                    ),
                    new WebAutomationLocatorDiscoveryService.PageLocatorEvidence(
                        "nav-user", "role=button[name=\"用户\"]", "用户", "button", "button", "", "role"
                    ),
                    new WebAutomationLocatorDiscoveryService.PageLocatorEvidence(
                        "log-menu", "text=日志管理", "日志管理", "menuitem", "div", "", "text"
                    )
                )
            ));
        AiGenerationResult plan = new AiGenerationResult(true, """
            {"cases":[{
              "sourcePointId":"point-log-permission-generic-menu",
              "title":"有日志权限角色可查看日志管理菜单及日志范围",
              "module":"日志管理",
              "caseKind":"web_automation",
              "pageName":"日志管理",
              "startUrl":"https://app-dev.example.test/",
              "browser":"chromium", "viewport":"1440x900", "precondition":"已存在有日志权限账号",
              "objective":"验证有日志权限角色可查看日志管理菜单及日志范围",
              "naturalSteps":"进入已登录首页\\n查看日志管理菜单\\n进入日志管理页面并查看日志范围",
              "naturalExpectedResult":"日志管理菜单可见，且日志列表/范围信息与权限一致",
              "browser":"chromium", "viewport":"1440x900", "precondition":"已存在有日志权限账号",
              "steps":"goto https://app-dev.example.test/\\nassertVisible text=日志管理",
              "assertions":"assertVisible text=日志管理",
              "expectedResult":"日志管理菜单可见，且日志列表/范围信息与权限一致",
              "sourceFile":"requirements.txt", "sourceText":"有日志权限角色可查看日志管理菜单及日志范围；无日志权限普通成员不可查看日志管理菜单"
            }]}
            """, "case-generic-menu", 20, 30, null);
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(plan);

        mockMvc.perform(post("/api/ai/draft-test-points/generate-cases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"pointIds":["point-log-permission-generic-menu"],"locatorDiscovery":{
                      "baseUrl":"https://app-dev.example.test/","browser":"chromium","viewport":"1440x900",
                      "headless":true,"timeoutMs":10000,"waitUntil":"domcontentloaded"
                    }}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].steps").value(containsString("assertVisible text=日志管理")))
            .andExpect(jsonPath("$.items[0].steps").value(not(containsString("role=button[name=\"管理\"]"))))
            .andExpect(jsonPath("$.items[0].steps").value(not(containsString("role=button[name=\"用户\"]"))));
    }

    @Test
    void acceptsBusinessAssertionEvenWhenItsLabelUsesGenericGoalTerms() throws Exception {
        insertDraft("point-project-business-status", "confirmed", "web_automation", "项目页面展示业务状态");
        when(webAutomationLocatorDiscoveryService.discover(any()))
            .thenReturn(new WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse(
                "https://app-dev.example.test/",
                List.of(new WebAutomationLocatorDiscoveryService.PageLocatorEvidence(
                    "nav-project", "role=button[name=\"项目\"]", "项目", "button", "button", "", "role"
                ))
            ));
        AiGenerationResult plan = new AiGenerationResult(true, """
            {"cases":[{"sourcePointId":"point-project-business-status","title":"项目页面展示业务状态","module":"项目","caseKind":"web_automation","pageName":"项目页面","startUrl":"/project","browser":"chromium","viewport":"1440x900","precondition":"已登录","objective":"验证项目页面展示业务状态","naturalSteps":"进入项目页面\\n查看业务状态","naturalExpectedResult":"业务状态信息正确展示","steps":"goto /project\\nassertVisible text=业务状态","assertions":"assertVisible text=业务状态","expectedResult":"业务状态信息正确展示","sourceFile":"requirements.txt","sourceText":"项目页面展示业务状态"}]}
            """, "project-plan", 20, 30, null);
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(plan);

        mockMvc.perform(post("/api/ai/draft-test-points/generate-cases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"pointIds":["point-project-business-status"],"locatorDiscovery":{
                      "baseUrl":"https://app-dev.example.test/","browser":"chromium","viewport":"1440x900",
                      "headless":true,"timeoutMs":10000,"waitUntil":"domcontentloaded"
                    }}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].steps").value(containsString("assertVisible text=业务状态")));
    }

    @Test
    void acceptsBusinessActionAssertionWhenTheModelProvidesPageElementAssertions() throws Exception {
        insertDraft("point-disable-expiring-trial", "confirmed", "web_automation", "即将到期免费体验版显示停用按钮并允许停用");
        when(webAutomationLocatorDiscoveryService.discover(any()))
            .thenReturn(new WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse(
                "https://test.example.com/#/threat-intelligence",
                List.of(new WebAutomationLocatorDiscoveryService.PageLocatorEvidence(
                    "disable-button", "role=button[name=\"停用\"]", "停用", "button", "button", "", "role"
                ))
            ));
        AiGenerationResult plan = new AiGenerationResult(true, """
            {"cases":[{"sourcePointId":"point-disable-expiring-trial","title":"即将到期免费体验版显示停用按钮并允许停用","module":"威胁情报业务","caseKind":"web_automation","pageName":"威胁情报业务列表页","startUrl":"/threat-intelligence","browser":"chromium","viewport":"1440x900","precondition":"已登录","objective":"验证即将到期的免费体验版可以执行停用","naturalSteps":"进入威胁情报业务列表页\\n检查即将到期记录的停用按钮","naturalExpectedResult":"即将到期的免费体验版记录操作列展示停用按钮","steps":"goto /threat-intelligence\\nassertVisible role=button[name=\\\"停用\\\"]","assertions":"assertVisible role=button[name=\\\"停用\\\"]","expectedResult":"即将到期的免费体验版记录操作列展示停用按钮","sourceFile":"requirements.txt","sourceText":"即将到期免费体验版显示停用按钮并允许停用"}]}
            """, "trial-plan", 20, 30, null);
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(plan);

        mockMvc.perform(post("/api/ai/draft-test-points/generate-cases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"pointIds":["point-disable-expiring-trial"],"locatorDiscovery":{
                      "baseUrl":"https://test.example.com/#/","browser":"chromium","viewport":"1440x900",
                      "headless":true,"timeoutMs":10000,"waitUntil":"domcontentloaded"
                    }}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].steps").value(containsString("assertVisible role=button[name=\"停用\"]")))
            .andExpect(jsonPath("$.items[0].steps").value(not(containsString("role=button[name=\"项目\"]"))));

        ArgumentCaptor<WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryRequest> requests =
            ArgumentCaptor.forClass(WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryRequest.class);
        verify(webAutomationLocatorDiscoveryService).discover(requests.capture());
        var request = requests.getValue();
        org.assertj.core.api.Assertions.assertThat(request.targetIntent().pageAliases())
            .contains("威胁情报业务列表页", "威胁情报业务");
        org.assertj.core.api.Assertions.assertThat(request.targetIntent().elementTerms()).contains("停用", "按钮");
        org.assertj.core.api.Assertions.assertThat(request.targetIntent().stateTerms()).contains("即将到期");
        org.assertj.core.api.Assertions.assertThat(request.targetIntent().negativeTerms()).doesNotContain("项目", "服务");
        org.assertj.core.api.Assertions.assertThat(request.budget())
            .isEqualTo(new WebAutomationLocatorDiscoveryService.ExplorationBudget(5, 90_000, 2, 1, 1, 3, 70, 25));
        org.assertj.core.api.Assertions.assertThat(request.previousAttempts()).isEmpty();
    }

    @Test
    void repairPromptKeepsRequiredPointCoverageAndRuleContextWhenBrowserGroundingDropsAPoint() throws Exception {
        insertDraft("point-kept", "confirmed", "web_automation");
        insertDraft("point-b96aaca1-a29a-448b-998c-9bfb9e6fe0cd", "confirmed", "web_automation");
        mockMvc.perform(put("/api/ai/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "rules": {
                        "selectorPolicy": "role-or-test-id",
                        "webAutomationOutput": "必须覆盖本批次所有 sourcePointId"
                      }
                    }
                    """))
            .andExpect(status().isOk());

        when(webAutomationLocatorDiscoveryService.discover(any()))
            .thenReturn(new WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse(
                "https://test.example.com/project",
                List.of()
            ));
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, """
                {"cases":[
                  {
                    "sourcePointId":"point-kept", "title":"进入项目管理", "module":"项目",
                    "caseKind":"web_automation", "pageName":"项目管理", "startUrl":"/project",
                    "browser":"chromium", "viewport":"1440x900", "precondition":"已登录",
                    "objective":"验证项目管理可访问", "naturalSteps":"进入项目管理页面","naturalExpectedResult":"项目管理路由可访问",
                    "steps":"goto /project\\nassertVisible text=项目管理",
                    "assertions":"assertVisible text=项目管理", "expectedResult":"项目管理路由可访问",
                    "sourceFile":"requirements.txt", "sourceText":"测试点: 用户登录成功"
                  },
                  {
                    "sourcePointId":"point-b96aaca1-a29a-448b-998c-9bfb9e6fe0cd", "title":"进入项目管理", "module":"项目",
                    "caseKind":"web_automation", "pageName":"项目管理", "startUrl":"/project",
                    "browser":"chromium", "viewport":"1440x900", "precondition":"已登录",
                    "objective":"验证项目管理可访问", "naturalSteps":"进入项目管理页面","naturalExpectedResult":"项目管理路由可访问",
                    "steps":"goto /project\\nassertVisible text=项目管理",
                    "assertions":"assertVisible text=项目管理", "expectedResult":"项目管理路由可访问",
                    "sourceFile":"requirements.txt", "sourceText":"测试点: 用户登录成功"
                  }
                ]}
                """, "case-repaired-coverage", 20, 30, null));

        mockMvc.perform(post("/api/ai/draft-test-points/generate-cases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"pointIds":["point-kept","point-b96aaca1-a29a-448b-998c-9bfb9e6fe0cd"],"locatorDiscovery":{
                      "baseUrl":"https://test.example.com","browser":"chromium","viewport":"1440x900",
                      "headless":true,"timeoutMs":10000,"waitUntil":"domcontentloaded"
                    }}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].sourcePointId").value("point-kept"))
            .andExpect(jsonPath("$.items[1].sourcePointId").value("point-b96aaca1-a29a-448b-998c-9bfb9e6fe0cd"))
            .andExpect(jsonPath("$.items[1].viewport").value("1440x900"));

        ArgumentCaptor<JsonNode> prompts = ArgumentCaptor.forClass(JsonNode.class);
        verify(aiGateway, times(1)).structuredGenerate(any(AiGatewayConfig.class), prompts.capture());
        org.assertj.core.api.Assertions.assertThat(prompts.getValue().path("confirmedDraftTestPoints").toString())
            .contains("point-kept", "point-b96aaca1-a29a-448b-998c-9bfb9e6fe0cd");
    }

    @Test
    void acceptsPageElementAssertionsWhenPointRequiresPageElementAssertions() throws Exception {
        insertDraft(
            "point-tab-order",
            "confirmed",
            "web_automation",
            "日志管理页面应展示全部日志、操作日志、登录日志、设备日志、告警日志五个 Tab，且顺序正确"
        );
        when(webAutomationLocatorDiscoveryService.discover(any()))
            .thenReturn(new WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse(
                "https://test.example.com/#/project",
                List.of(
                    new WebAutomationLocatorDiscoveryService.PageLocatorEvidence(
                        "tab-1", "text=全部日志", "全部日志", "tab", "div", "", "text"
                    ),
                    new WebAutomationLocatorDiscoveryService.PageLocatorEvidence(
                        "tab-2", "text=操作日志", "操作日志", "tab", "div", "", "text"
                    ),
                    new WebAutomationLocatorDiscoveryService.PageLocatorEvidence(
                        "tab-3", "text=登录日志", "登录日志", "tab", "div", "", "text"
                    ),
                    new WebAutomationLocatorDiscoveryService.PageLocatorEvidence(
                        "tab-4", "text=设备日志", "设备日志", "tab", "div", "", "text"
                    ),
                    new WebAutomationLocatorDiscoveryService.PageLocatorEvidence(
                        "tab-5", "text=告警日志", "告警日志", "tab", "div", "", "text"
                    )
                )
            ));
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, """
                {"cases":[{
                  "sourcePointId":"point-tab-order", "title":"日志管理页面展示五个 Tab 且顺序正确", "module":"日志管理",
                  "caseKind":"web_automation", "pageName":"日志管理页面", "startUrl":"/project",
                  "browser":"chromium", "viewport":"1440x900", "precondition":"已登录",
                  "objective":"验证日志管理页面 Tab 展示和顺序",
                  "naturalSteps":"进入日志管理页面\\n查看全部日志到告警日志的顺序",
                  "naturalExpectedResult":"五个 Tab 顺序正确",
                  "steps":"goto /project\\nassertVisible text=全部日志\\nassertVisible text=告警日志",
                  "assertions":"assertVisible text=全部日志\\nassertVisible text=告警日志", "expectedResult":"五个 Tab 顺序正确",
                  "sourceFile":"requirements.txt", "sourceText":"日志管理页面应展示五个 Tab 且顺序正确"
                }]}
                """, "case-generic-url", 20, 30, null));

        mockMvc.perform(post("/api/ai/draft-test-points/generate-cases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"pointIds":["point-tab-order"],"locatorDiscovery":{
                      "baseUrl":"https://test.example.com/#/","browser":"chromium","viewport":"1440x900",
                      "headless":true,"timeoutMs":10000,"waitUntil":"domcontentloaded"
                    }}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].steps", containsString("assertVisible text=全部日志")))
            .andExpect(jsonPath("$.items[0].steps", containsString("assertVisible text=告警日志")))
            .andExpect(jsonPath("$.items[0].expectedResult", containsString("五个 Tab")));

        ArgumentCaptor<JsonNode> prompts = ArgumentCaptor.forClass(JsonNode.class);
        verify(aiGateway, times(1)).structuredGenerate(any(AiGatewayConfig.class), prompts.capture());
        JsonNode groundedPrompt = prompts.getValue();
        org.assertj.core.api.Assertions.assertThat(groundedPrompt.path("instruction").asText())
            .contains("只生成自然语言业务用例")
            .contains("第二阶段会优先读取定位器素材库");
    }

    @Test
    void rejectsOutputWhenSingleRepairIsStillInvalid() throws Exception {
        insertDraft("point-repair-failed", "confirmed", "web_automation");
        AiGenerationResult invalid = new AiGenerationResult(true, """
            {"cases":[{
              "sourcePointId":"point-repair-failed",
              "title":"进入用户管理",
              "module":"登录",
              "caseKind":"web_automation",
              "pageName":"用户管理",
              "startUrl":"/user-center/users",
              "browser":"chromium",
              "viewport":"1440x900",
              "precondition":"环境登录流程已完成",
              "objective":"验证用户管理可访问",
              "naturalSteps":"进入用户管理页面",
              "naturalExpectedResult":"用户管理页面可见",
              "steps":"manual 人工登录",
              "assertions":"assertVisible text=用户管理",
              "expectedResult":"用户管理标题可见",
              "sourceFile":"requirements.txt",
              "sourceText":"用户登录后可进入用户管理"
            }]}
            """, "case-invalid", 20, 30, null);
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(invalid)
            .thenReturn(invalid);

        mockMvc.perform(post("/api/ai/draft-test-points/generate-cases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pointIds\": [\"point-repair-failed\"]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("动作 manual 不支持")));

        verify(aiGateway, times(1)).structuredGenerate(any(AiGatewayConfig.class), any());
    }

    @Test
    void rejectsContentObjectiveThatFallsBackToAssertUrlOnly() throws Exception {
        insertDraft("point-device-status-url-only", "confirmed", "web_automation", "展示设备在线状态");
        when(webAutomationLocatorDiscoveryService.discover(any()))
            .thenReturn(new WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse(
                "https://test.example.com/devices",
                List.of()
            ));
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, """
                {"cases":[{
                  "sourcePointId":"point-device-status-url-only","title":"展示设备状态","module":"设备",
                  "caseKind":"web_automation","pageName":"设备管理","startUrl":"/devices","browser":"chromium",
                  "viewport":"1440x900","precondition":"已登录","objective":"验证设备列表展示在线状态",
                  "naturalSteps":"进入设备管理页\\n查看设备状态列","naturalExpectedResult":"设备状态可见",
                  "steps":"goto /devices\\nassertUrl /devices","assertions":"assertUrl /devices",
                  "expectedResult":"设备状态可见","sourceFile":"requirements.txt","sourceText":"展示设备在线状态"
                }]}
                """, "url-only-grounded", 10, 20, null));

        mockMvc.perform(post("/api/ai/draft-test-points/generate-cases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"pointIds":["point-device-status-url-only"],"locatorDiscovery":{
                      "baseUrl":"https://test.example.com","browser":"chromium","viewport":"1440x900",
                      "headless":true,"timeoutMs":10000,"waitUntil":"domcontentloaded"
                    }}
                    """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("不能只使用 assertUrl")));

        verify(aiGateway, times(1)).structuredGenerate(any(AiGatewayConfig.class), any());
    }

    @Test
    void browserDiscoveryRequestsKeepEachPointIntentAndCandidatesIsolatedForSharedStartUrl() throws Exception {
        insertDraft("point-project-status", "confirmed", "web_automation", "项目页面展示项目运行状态");
        insertDraft("point-project-quota", "confirmed", "web_automation", "项目页面展示资源配额");
        insertLocatorKnowledge("known-status", "项目", "项目页面", "运行状态", "testId=project-status");
        insertLocatorKnowledge("known-quota", "项目", "项目页面", "资源配额", "testId=project-quota");
        jdbcTemplate.update("update ai_locator_knowledge_entries set page_fingerprint = ? where entry_id in ('known-status', 'known-quota')", "sha256:project");
        jdbcTemplate.update("update ai_locator_knowledge_entries set confidence_score = 60 where entry_id in ('known-status', 'known-quota')");
        when(webAutomationLocatorDiscoveryService.discover(any()))
            .thenReturn(new WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse(
                "https://test.example.com/#/project",
                List.of(new WebAutomationLocatorDiscoveryService.PageLocatorEvidence(
                    "status", "testId=project-status", "运行状态", "status", "span", "", "testId"
                ))
            ))
            .thenReturn(new WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse(
                "https://test.example.com/#/project",
                List.of(new WebAutomationLocatorDiscoveryService.PageLocatorEvidence(
                    "quota", "testId=project-quota", "资源配额", "status", "span", "", "testId"
                ))
            ));
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, """
                {"cases":[
                  {"sourcePointId":"point-project-status","title":"项目运行状态展示","module":"项目","caseKind":"web_automation","pageName":"项目页面","startUrl":"/project","browser":"chromium","viewport":"1440x900","precondition":"已登录","objective":"验证项目页面展示项目运行状态","naturalSteps":"进入项目页面\\n查看运行状态","naturalExpectedResult":"运行状态可见","steps":"goto /project\\nassertVisible testId=project-status","assertions":"assertVisible testId=project-status","expectedResult":"运行状态可见","sourceFile":"requirements.txt","sourceText":"项目页面展示项目运行状态"},
                  {"sourcePointId":"point-project-quota","title":"项目资源配额展示","module":"项目","caseKind":"web_automation","pageName":"项目页面","startUrl":"/project","browser":"chromium","viewport":"1440x900","precondition":"已登录","objective":"验证项目页面展示资源配额","naturalSteps":"进入项目页面\\n查看资源配额","naturalExpectedResult":"资源配额可见","steps":"goto /project\\nassertVisible testId=project-quota","assertions":"assertVisible testId=project-quota","expectedResult":"资源配额可见","sourceFile":"requirements.txt","sourceText":"项目页面展示资源配额"}
                ]}
                """, "case-shared-project-grounded", 20, 30, null));

        mockMvc.perform(post("/api/ai/draft-test-points/generate-cases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"pointIds":["point-project-status","point-project-quota"],"locatorDiscovery":{
                      "baseUrl":"https://test.example.com/#/","browser":"chromium","viewport":"1440x900",
                      "headless":true,"timeoutMs":10000,"waitUntil":"domcontentloaded"
                    }}
                    """))
            .andExpect(status().isOk());

        ArgumentCaptor<WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryRequest> requests =
            ArgumentCaptor.forClass(WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryRequest.class);
        verify(webAutomationLocatorDiscoveryService, times(2)).discover(requests.capture());
        org.assertj.core.api.Assertions.assertThat(requests.getAllValues())
            .extracting(WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryRequest::sourcePointId)
            .containsExactly("point-project-status", "point-project-quota");
        org.assertj.core.api.Assertions.assertThat(requests.getAllValues().get(0).targetIntent().elementTerms())
            .contains("状态")
            .doesNotContain("资源配额");
        org.assertj.core.api.Assertions.assertThat(requests.getAllValues().get(1).targetIntent().elementTerms())
            .doesNotContain("运行状态");
        org.assertj.core.api.Assertions.assertThat(requests.getAllValues().get(0).historicalLocators())
            .extracting(WebAutomationLocatorDiscoveryService.HistoricalLocatorCandidate::entryId)
            .contains("known-status", "known-quota");
        org.assertj.core.api.Assertions.assertThat(requests.getAllValues().get(0).historicalLocators())
            .allSatisfy(candidate -> {
                org.assertj.core.api.Assertions.assertThat(candidate.pageUrl()).isEqualTo("https://test.example.com/#/project");
                org.assertj.core.api.Assertions.assertThat(candidate.sourcePointId()).isEqualTo("point-project-status");
            });
        org.assertj.core.api.Assertions.assertThat(requests.getAllValues().get(1).targetIntent().elementTerms())
            .doesNotContain("状态");
    }

    private void insertDraft(String pointId, String status) {
        insertDraft(pointId, status, "interface");
    }

    private void insertDraft(String pointId, String status, String caseKind) {
        insertDraft(pointId, status, caseKind, "测试点: 用户登录成功");
    }

    private void insertDraft(String pointId, String status, String caseKind, String sourceText) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
            insert into ai_draft_test_points(
              point_id, run_id, project_id, source_document_id, source_chunk_id, title, module_name,
              page_or_feature, case_kind, test_type, priority_level, precondition_text, test_objective,
              suggested_steps, expected_result, source_file, source_section, source_page, source_text,
              source_image_id, extraction_method, confidence_score, inferred, review_status, review_comment,
              result_origin, acceptance_state, revision, parent_artifact_id, idempotency_key, template_id, metadata_json, actor_id, actor_name, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            pointId, runPk, projectId, null, null, "用户登录成功进入工作台", "登录",
            "登录页", caseKind, "功能", "P1", "", "验证正确账号密码可登录",
            "输入正确账号密码并提交", "进入工作台", "requirements.txt", "", "", sourceText,
            "", "ai_generated", 0.91, false, status, "",
            "MODEL_PROPOSED", "PENDING_HUMAN_CONFIRMATION", 1, "", pointId,
            "builtin-interface", "{}",
            "system-dev", "Development User", now, now
        );
    }

    private void insertLocatorKnowledge(String entryId, String moduleName, String pageName, String naturalName, String locator) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
            insert into ai_locator_knowledge_entries(
              entry_id, project_id, environment_id, module_name, page_name, page_url,
              page_fingerprint, natural_name, locator, role_name, accessible_name, element_type,
              source, status, evidence_version, collected_at, last_verified_at, metadata_json,
              created_at, updated_at
            ) values (?, ?, '', ?, ?, 'https://test.example.com/#/project', '', ?, ?, '', ?, 'tab', 'browser', 'active', 1, ?, ?, '{}', ?, ?)
            """,
            entryId, projectId, moduleName, pageName, naturalName, locator, naturalName,
            now, now, now, now
        );
    }
}
