package com.haitlify.aitest.platform.ai.agentic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.haitlify.aitest.platform.ai.agentic.rule.AgentRuleService;

import tools.jackson.databind.ObjectMapper;

/**
 * T10 bullet 2/6 跨层合同：交付链路上的两个分离不变量——
 *  · AI 工件确认/修改/交付路径绝不写执行回写表（执行结果只能来自 Runner 快照）；
 *  · 编译只消费已确认（ACCEPTED）的自然语言计划，未确认的计划进不了编译；
 *  · 执行失败如实落 failed/0-passed，评审接受（HUMAN_EDITED/ACCEPTED）不会覆盖执行事实。
 */
class WebCaseAiRuntimeFlowTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ---------- 记录所有 AI 侧 SQL 的最小捕获模板 ----------

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        final List<String> executedSql = new ArrayList<>();
        final List<Object[]> updateArgs = new ArrayList<>();

        @Override
        public int update(String sql, Object... args) {
            executedSql.add(sql);
            updateArgs.add(args);
            return 1;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            executedSql.add(sql);
            if (sql.startsWith("select revision")) return requiredType.cast(2);
            if (sql.startsWith("select acceptance_state")) return requiredType.cast("PENDING_HUMAN_CONFIRMATION");
            if (sql.startsWith("select candidate_json")) return requiredType.cast("{\"title\":\"x\"}");
            if (sql.startsWith("select project_id") || sql.startsWith("select r.project_id")) return requiredType.cast(1L);
            if (sql.startsWith("select run_id from ai_web_case_agent_artifacts")) return requiredType.cast("run-1");
            if (sql.startsWith("select parent_artifact_id")) return requiredType.cast("parent-1");
            if (sql.startsWith("select idempotency_key")) return requiredType.cast("idem-1");
            return null;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            executedSql.add(sql);
            return List.of();
        }

        @Override
        public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
            executedSql.add(sql);
            return List.of();
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            executedSql.add(sql);
            return List.of();
        }
    }

    @Test
    void aiAcceptanceFlowNeverWritesRunnerExecutionReports() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        WebCaseAgentRunService service = new WebCaseAgentRunService(
            jdbc, objectMapper, mock(AgentRuleService.class), mock(WebCaseAgentEventService.class), null);

        try {
            SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("qa-user", null, List.of()));
            service.confirmPlan("artifact-1", true, 2);
            service.updatePlan("artifact-1", objectMapper.createObjectNode().put("title", "调整"), 2);
            service.acceptedArtifacts("run-1");
            // 未 ACCEPTED 的工件绝不能出现在导出/正式用例库面。
            assertThatThrownBy(() -> service.acceptedArtifactRevision("run-1", "artifact-1", 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not accepted for export");
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertThat(jdbc.executedSql).isNotEmpty();
        assertThat(jdbc.executedSql)
            .noneMatch(sql -> sql.contains("ai_web_automation_run_reports"));
        // 人工接受在 AI 工件表内落 HUMAN_EDITED/ACCEPTED，与执行回写彻底分离。
        assertThat(jdbc.updateArgs).anySatisfy(args ->
            assertThat(java.util.Arrays.asList(args)).contains("HUMAN_EDITED"));
        assertThat(jdbc.updateArgs).anySatisfy(args ->
            assertThat(java.util.Arrays.asList(args)).contains("ACCEPTED"));
    }

    @Test
    void compileConsumesOnlyAcceptedPlansAndRefusesEmptyConfirmation() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        WebCaseAgentRunService service = new WebCaseAgentRunService(
            jdbc, objectMapper, mock(AgentRuleService.class), mock(WebCaseAgentEventService.class), null);

        // 捕获不到任何 ACCEPTED 计划 ⇒ 编译前置拒绝，绝不重新规划。
        assertThatThrownBy(() -> service.confirmedPlanBatch("plan-run-1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("尚未确认");
    }
}
