package com.haitlify.aitest.platform.ai.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.haitlify.aitest.platform.ai.evidence.AiEvidenceManifestService;
import com.haitlify.aitest.platform.ai.event.AiRunEventService;
import com.haitlify.aitest.platform.ai.gateway.AiGenerationResult;
import com.haitlify.aitest.platform.ai.knowledge.AiContextBuilder;
import com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade;
import com.haitlify.aitest.platform.ai.tool.mcp.AiMcpToolExecutionRequest;
import com.haitlify.aitest.platform.ai.tool.mcp.AiMcpToolExecutionResult;
import com.haitlify.aitest.platform.ai.tool.mcp.AiMcpToolExecutor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * T-R5 轮 2 回归（复核发现 2 / T09 Exit Gate）：bug_analysis 与 log_triage 必须真实使用
 * MCP 只读工具证据——启用服务器 + 服务端裁决 readOnly 的工具被真实调用，结果以
 * TOOL_OBSERVED 进入 prompt 与产物来源清单；无服务器/旧字符串白名单/被策略拒绝一律如实处理。
 */
class AiAssistantMcpEvidenceTest {

    private static final String NONCE = "nonce-7f3a91c2-来自fixture";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JdbcTemplate jdbcTemplate;
    private AiTaskExecutionFacade facade;
    private AiMcpToolExecutor executor;
    private AiAssistantService service;
    private JsonNode capturedPrompt;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:assistant-mcp;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
            create table if not exists ai_assistant_artifacts(
              artifact_id varchar(160) primary key, run_id varchar(160) not null, task_type varchar(64) not null,
              project_id bigint not null, revision int not null, acceptance_state varchar(32) not null,
              result_origin varchar(32) not null, payload_json clob, violations_json clob,
              source_manifest_json clob, created_by varchar(128) default '', reviewed_by varchar(128) default '',
              created_at timestamp, updated_at timestamp, deterministic_facts_json clob)
            """);
        jdbcTemplate.execute("alter table ai_assistant_artifacts"
            + " add column if not exists deterministic_facts_json clob");
        jdbcTemplate.execute("""
            create table if not exists ai_mcp_servers(
              id bigint auto_increment primary key, server_id varchar(128) not null, project_id bigint not null,
              enabled boolean not null, tool_allowlist_json clob)
            """);
        jdbcTemplate.execute("delete from ai_assistant_artifacts");
        jdbcTemplate.execute("delete from ai_mcp_servers");

        facade = mock(AiTaskExecutionFacade.class);
        when(facade.generate(any())).thenAnswer(invocation -> {
            capturedPrompt = invocation.getArgument(0, AiTaskExecutionFacade.TaskExecution.class).prompt();
            return new AiGenerationResult(true, """
                {"taskType": "bug_analysis", "summary": "ok",
                 "hypotheses": [{"claim": "c", "evidenceIds": ["ev-1"], "status": "needs_confirmation"}],
                 "humanReviewRequired": true}
                """, "req-1", 5, 5, null, false, "", 0);
        });
        AiEvidenceManifestService manifestService = mock(AiEvidenceManifestService.class);
        when(manifestService.manifestForProject(any())).thenReturn(
            new AiEvidenceManifestService.EvidenceManifest(1L, List.of(), 0, 0, 0, List.of()));
        AiContextBuilder contextBuilder = mock(AiContextBuilder.class);
        when(contextBuilder.build(any(), any(), any(Integer.class))).thenReturn(
            new AiContextBuilder.BuiltContext(List.of(), 0, 0, 4000, List.of(), AiContextBuilder.ESTIMATE_VERSION));
        executor = mock(AiMcpToolExecutor.class);
        service = new AiAssistantService(jdbcTemplate, objectMapper, facade, manifestService,
            contextBuilder, mock(AiRunEventService.class), null, executor);
    }

    private void enableServer(String allowlistJson) {
        jdbcTemplate.update("insert into ai_mcp_servers(server_id, project_id, enabled, tool_allowlist_json)"
            + " values ('mcp-1', 1, true, ?)", allowlistJson);
    }

    @Test
    void bugAnalysisReallyUsesTheMcpReadOnlyToolAndCarriesTheResultAsEvidence() {
        enableServer("[{\"name\":\"fetch_trace\",\"readOnly\":true}]");
        when(executor.execute(any())).thenAnswer(invocation -> {
            AiMcpToolExecutionRequest request = invocation.getArgument(0);
            JsonNode response = objectMapper.createObjectNode()
                .put("resultOrigin", "TOOL_OBSERVED")
                .put("nonce", NONCE);
            return new AiMcpToolExecutionResult("ok", response, null);
        });

        AiAssistantService.AssistantRun run = service.startRun(
            "bug_analysis", 1L, objectMapper.readTree("{\"symptom\":\"偶发失败\"}"), "tester");

        // 真实调用了被服务端裁决为只读的 allowlist 工具。
        verify(executor).execute(org.mockito.ArgumentMatchers.argThat(request ->
            "mcp-1".equals(request.serverId()) && "fetch_trace".equals(request.toolName())
                && request.readOnly()));
        // fixture 的 nonce 原样进入 prompt 的 toolObservations（可反回显验证）。
        assertThat(capturedPrompt.path("toolObservations").isArray()).isTrue();
        assertThat(capturedPrompt.path("toolObservations").toString()).contains(NONCE);
        assertThat(capturedPrompt.path("toolObservations").get(0).path("resultOrigin").asText())
            .isEqualTo("TOOL_OBSERVED");

        // 产物来源清单同时携带该工具证据（可审计）。
        String sourceManifest = jdbcTemplate.queryForObject(
            "select source_manifest_json from ai_assistant_artifacts where artifact_id = ?",
            String.class, run.artifactId());
        assertThat(sourceManifest).contains("toolObservations").contains(NONCE);
    }

    @Test
    void logTriageUsesMcpTooWhileOtherTaskTypesDoNot() {
        enableServer("[{\"name\":\"fetch_trace\",\"readOnly\":true}]");
        when(executor.execute(any())).thenReturn(new AiMcpToolExecutionResult("ok",
            objectMapper.createObjectNode().put("resultOrigin", "TOOL_OBSERVED").put("nonce", NONCE), null));

        service.startRun("log_triage", 1L,
            objectMapper.readTree("{\"logExcerpt\":\"stack...\",\"timeRange\":\"10:00~10:05\"}"), "tester");
        verify(executor, org.mockito.Mockito.atLeastOnce()).execute(any());

        // 其他任务类型不采集 MCP 证据（作用域受控）。
        service.startRun("sql_analysis", 1L, objectMapper.readTree("{\"sqlText\":\"select 1\"}"), "tester");
        assertThat(capturedPrompt.has("toolObservations")).isFalse();
    }

    @Test
    void legacyStringAllowlistsAndMissingServersAreHandledFailClosed() {
        // 旧字符串形式（无 readOnly 元数据）⇒ 不采集、不调用。
        enableServer("[\"fetch_trace\"]");
        service.startRun("bug_analysis", 1L, objectMapper.readTree("{\"symptom\":\"s\"}"), "tester");
        verify(executor, never()).execute(any());
        assertThat(capturedPrompt.has("toolObservations")).isFalse();

        // 无启用服务器 ⇒ 同样不调用。
        jdbcTemplate.execute("delete from ai_mcp_servers");
        service.startRun("bug_analysis", 1L, objectMapper.readTree("{\"symptom\":\"s\"}"), "tester");
        verify(executor, never()).execute(any());
    }

    @Test
    void aBlockedToolCallIsRecordedAsUnavailableInsteadOfFabricated() {
        enableServer("[{\"name\":\"fetch_trace\",\"readOnly\":true}]");
        when(executor.execute(any())).thenReturn(new AiMcpToolExecutionResult(
            "blocked", null, "MCP server is disabled: mcp-1"));

        service.startRun("bug_analysis", 1L, objectMapper.readTree("{\"symptom\":\"s\"}"), "tester");

        JsonNode observation = capturedPrompt.path("toolObservations").get(0);
        assertThat(observation.path("resultOrigin").asText()).isEqualTo("BLOCKED");
        assertThat(observation.path("unavailable").asText()).contains("disabled");
        assertThat(observation.has("result")).isFalse();
    }
}
