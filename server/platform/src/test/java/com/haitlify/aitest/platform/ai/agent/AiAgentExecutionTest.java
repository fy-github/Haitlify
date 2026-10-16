package com.haitlify.aitest.platform.ai.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.haitlify.aitest.platform.ai.gateway.AiGenerationResult;
import com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade;
import com.haitlify.aitest.platform.ai.tool.AiTool;
import com.haitlify.aitest.platform.ai.tool.AiToolRegistry;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * T09 bullet 6：节点执行器四类真实路径——RULE 确定性产出、MODEL 走 Agent profile→task route
 * 且失败不伪造、TOOL 受 allowedTools 约束、HUMAN 门禁绝不自动 completed。
 */
class AiAgentExecutionTest {

    private final AiTaskExecutionFacade facade = mock(AiTaskExecutionFacade.class);
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate =
        mock(org.springframework.jdbc.core.JdbcTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private AiAgentNodeExecutor executorWith(AiTool... tools) {
        return new AiAgentNodeExecutor(facade, new AiToolRegistry(List.of(tools)), objectMapper, jdbcTemplate);
    }

    private BuiltInAgentDefinition agent(String agentId, String role, JsonNode allowedTools) {
        return new BuiltInAgentDefinition(agentId, agentId + " 名称", "描述", role, "你是 " + agentId, null,
            allowedTools, true, true, 1);
    }

    @Test
    void ruleRequirementUnderstandingExtractsScopeDeterministically() throws Exception {
        JsonNode previous = objectMapper.readTree("""
            {"draftPoints":[{"title":"需求点 A"}],"notes":["规则：亮度需支持 0-100","背景信息"]}
            """);

        AiAgentNodeExecutor.NodeExecution execution = executorWith().execute(
            agent("requirement-understanding", "requirement_understanding", null),
            7L, "run-1", objectMapper.createObjectNode(), previous);

        assertThat(execution.status()).isEqualTo("succeeded");
        assertThat(execution.resultOrigin()).isEqualTo("RULE_COMPUTED");
        assertThat(execution.output().path("scopeItemCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(execution.output().path("summary").asText()).contains("RULE executor");
    }

    @Test
    void ruleQualityReviewFlagsMissingEvidenceAndDuplicates() throws Exception {
        JsonNode previous = objectMapper.readTree("""
            {"draftPoints":[
              {"title":"登录成功","expectedResult":"进入工作台"},
              {"title":"登录成功","expectedResult":"进入工作台"},
              {"title":"失败提示","expectedResult":"提示错误","evidence":"doc-1"}
            ]}
            """);

        AiAgentNodeExecutor.NodeExecution execution = executorWith().execute(
            agent("quality-review", "quality_review", null), 7L, "run-1", objectMapper.createObjectNode(), previous);

        assertThat(execution.status()).isEqualTo("succeeded");
        List<String> findings = new java.util.ArrayList<>();
        execution.output().path("findings").forEach(node -> findings.add(node.asText()));
        assertThat(findings).anySatisfy(finding -> assertThat(finding).contains("missing evidence"));
        assertThat(findings).anySatisfy(finding -> assertThat(finding).contains("duplicated"));
    }

    @Test
    void modelNodeResolvesTaskRouteFromAgentProfileAndFailsClosed() throws Exception {
        when(facade.generate(any())).thenReturn(new AiGenerationResult(
            true, "```json\n[{\"title\":\"登录失败提示\",\"expectedResult\":\"提示错误\"}]\n```", "req-1", 10, 10, null, false, "", 0));

        AiAgentNodeExecutor.NodeExecution success = executorWith().execute(
            agent("test-design", "test_design", null), 7L, "run-1",
            objectMapper.createObjectNode(), objectMapper.createObjectNode());

        ArgumentCaptor<AiTaskExecutionFacade.TaskExecution> captor =
            ArgumentCaptor.forClass(AiTaskExecutionFacade.TaskExecution.class);
        verify(facade).generate(captor.capture());
        assertThat(captor.getValue().taskType()).isEqualTo("multi_agent_test_design");
        assertThat(captor.getValue().projectId()).isEqualTo(7L);
        assertThat(success.status()).isEqualTo("succeeded");
        assertThat(success.resultOrigin()).isEqualTo("MODEL_GENERATED");
        assertThat(success.output().path("draftPoints").isArray()).isTrue();
        assertThat(success.output().path("acceptanceState").asText()).isEqualTo("PENDING_HUMAN_CONFIRMATION");
        // T09 bullet 7：模型结果携带原生 call id。
        assertThat(success.output().path("providerCallId").asText()).isEqualTo("req-1");

        when(facade.generate(any())).thenReturn(new AiGenerationResult(
            false, "", "", 0, 0, "无可用通道", false, "credential_unresolved", 0));
        AiAgentNodeExecutor.NodeExecution failure = executorWith().execute(
            agent("test-design", "test_design", null), 7L, "run-1",
            objectMapper.createObjectNode(), objectMapper.createObjectNode());

        // 失败 = 失败：绝无假草稿点，origin/acceptance 均为 BLOCKED。
        assertThat(failure.status()).isEqualTo("failed");
        assertThat(failure.resultOrigin()).isEqualTo("BLOCKED");
        assertThat(failure.error()).contains("Model node failed").contains("credential_unresolved");
        assertThat(failure.output().path("draftPoints").isMissingNode()).isTrue();
    }

    @Test
    void toolNodeRunsRealToolAndRespectsAllowedTools() throws Exception {
        AiTool coverageTool = new StubTool("check-coverage-gaps",
            objectMapper.readTree("{\"gaps\":[\"boundary\"],\"resultOrigin\":\"RULE_COMPUTED\"}"));

        AiAgentNodeExecutor.NodeExecution allowed = executorWith(coverageTool).execute(
            agent("automation-suggestion", "automation_suggestion",
                objectMapper.readTree("[\"check-coverage-gaps\"]")),
            7L, "run-1", objectMapper.createObjectNode(), objectMapper.createObjectNode());
        assertThat(allowed.status()).isEqualTo("succeeded");
        assertThat(allowed.resultOrigin()).isEqualTo("RULE_COMPUTED");
        assertThat(allowed.output().path("coverage").path("gaps").get(0).asText()).isEqualTo("boundary");
        // 工具结果回传必须带 call id（本地工具生成并回执）。
        assertThat(allowed.output().path("callId").asText()).startsWith("tool-call-");

        AiAgentNodeExecutor.NodeExecution blocked = executorWith(coverageTool).execute(
            agent("automation-suggestion", "automation_suggestion", objectMapper.readTree("[\"other-tool\"]")),
            7L, "run-1", objectMapper.createObjectNode(), objectMapper.createObjectNode());
        assertThat(blocked.status()).isEqualTo("failed");
        assertThat(blocked.error()).contains("not allowed");

        AiAgentNodeExecutor.NodeExecution missing = executorWith().execute(
            agent("automation-suggestion", "automation_suggestion", null),
            7L, "run-1", objectMapper.createObjectNode(), objectMapper.createObjectNode());
        assertThat(missing.status()).isEqualTo("failed");
        assertThat(missing.error()).contains("not registered");
    }

    @Test
    void humanGateWaitsForHumanAndNeverAutoCompletes() {
        AiAgentNodeExecutor.NodeExecution execution = executorWith().execute(
            agent("human-gate", "human_gate", null), 7L, "run-1",
            objectMapper.createObjectNode(), objectMapper.createObjectNode());

        assertThat(execution.status()).isEqualTo("waiting_human");
        assertThat(execution.resultOrigin()).isEqualTo("BLOCKED");
        assertThat(execution.acceptanceState()).isEqualTo("PENDING_HUMAN_CONFIRMATION");
        assertThat(execution.output().path("status").asText()).isEqualTo("waiting_human");
        assertThat(execution.output().path("summary").isMissingNode()).isTrue();
    }

    @Test
    void modelRoundLimitBlocksFurtherCallsPerRun() {
        when(jdbcTemplate.queryForObject(any(String.class), org.mockito.ArgumentMatchers.eq(Integer.class), any()))
            .thenReturn(10);
        AiAgentNodeExecutor.NodeExecution execution = executorWith().execute(
            agent("test-design", "test_design", null), 7L, "run-capped",
            objectMapper.createObjectNode(), objectMapper.createObjectNode());
        assertThat(execution.status()).isEqualTo("failed");
        assertThat(execution.error()).contains("Model round limit exceeded");
    }

    @Test
    void unknownAgentsFailClosed() {
        AiAgentNodeExecutor.NodeExecution execution = executorWith().execute(
            agent("mystery-agent", "mystery", null), 7L, "run-1",
            objectMapper.createObjectNode(), objectMapper.createObjectNode());
        assertThat(execution.status()).isEqualTo("failed");
        assertThat(execution.error()).contains("No executor registered");
    }

    private static final class StubTool implements AiTool {
        private final String toolId;
        private final JsonNode dryRunResult;

        private StubTool(String toolId, JsonNode dryRunResult) {
            this.toolId = toolId;
            this.dryRunResult = dryRunResult;
        }

        @Override public String toolId() { return toolId; }
        @Override public String name() { return toolId; }
        @Override public String description() { return toolId; }
        @Override public boolean readOnly() { return true; }
        @Override public JsonNode inputSchema() { return null; }
        @Override public JsonNode outputSchema() { return null; }
        @Override public JsonNode dryRun(JsonNode input) { return dryRunResult; }
    }
}
