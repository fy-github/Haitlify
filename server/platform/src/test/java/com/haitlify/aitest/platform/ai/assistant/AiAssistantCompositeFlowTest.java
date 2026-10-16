package com.haitlify.aitest.platform.ai.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.haitlify.aitest.platform.ai.evidence.AiEvidenceManifestService;
import com.haitlify.aitest.platform.ai.event.AiRunEventService;
import com.haitlify.aitest.platform.ai.gateway.AiGenerationResult;
import com.haitlify.aitest.platform.ai.knowledge.AiContextBuilder;
import com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * T12 批次 3：Bug→日志→回归组合流——共享 evidence manifest、依赖未满足不得完成、
 * 人工门逐节点推进、拒绝阻止下游、每个节点独立产物。
 */
class AiAssistantCompositeFlowTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JdbcTemplate jdbcTemplate;
    private AiTaskExecutionFacade facade;
    private AiEvidenceManifestService manifestService;
    private AiAssistantCompositeService compositeService;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:composite-test;DB_CLOSE_DELAY=-1", "sa", "");
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
            create table if not exists ai_assistant_composite_runs(
              composite_id varchar(128) primary key, project_id bigint not null, actor_id varchar(128) default '',
              template varchar(64) not null, status varchar(32) not null, manifest_json clob, inputs_json clob,
              created_at timestamp, updated_at timestamp)
            """);
        jdbcTemplate.execute("""
            create table if not exists ai_assistant_composite_nodes(
              node_id varchar(200) primary key, composite_id varchar(128) not null, position int not null,
              task_type varchar(64) not null, depends_on_json varchar(512) default '[]', status varchar(32) not null,
              run_id varchar(128) default '', artifact_id varchar(200) default '', skip_reason varchar(512) default '',
              created_at timestamp, updated_at timestamp)
            """);
        jdbcTemplate.execute("delete from ai_assistant_artifacts");
        jdbcTemplate.execute("delete from ai_assistant_composite_nodes");
        jdbcTemplate.execute("delete from ai_assistant_composite_runs");

        facade = mock(AiTaskExecutionFacade.class);
        manifestService = mock(AiEvidenceManifestService.class);
        when(manifestService.manifestForProject(any())).thenReturn(
            new AiEvidenceManifestService.EvidenceManifest(1L, List.of(), 0, 0, 0, List.of("无文档")));
        AiContextBuilder contextBuilder = mock(AiContextBuilder.class);
        when(contextBuilder.build(any(), any(), any(Integer.class))).thenReturn(
            new AiContextBuilder.BuiltContext(List.of(), 0, 0, 4000, List.of(), AiContextBuilder.ESTIMATE_VERSION));
        AiRunEventService eventService = mock(AiRunEventService.class);
        AiAssistantService assistantService = new AiAssistantService(
            jdbcTemplate, objectMapper, facade, manifestService, contextBuilder, eventService);
        compositeService = new AiAssistantCompositeService(
            jdbcTemplate, objectMapper, assistantService, manifestService);
    }

    private JsonNode inputs() {
        return objectMapper.readTree("""
            {"symptom": "支付成功后订单状态未同步", "logExcerpt": "stack trace...",
             "timeRange": "2026-10-10 10:00~10:05", "changeSummary": "订单同步改动"}
            """);
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("qa-user", null, List.of()));
    }

    /** 按节点逐个给 facade 排定有效输出（bug/log/regression 的 schema 各自满足）。 */
    private void stubFacadeForAllNodes() {
        // 组合按 DAG 顺序执行 bug → log → regression；下游提示词会携带上游输出，
        // 因此按“每次生成调用取下一个脚本输出”响应，而不是按提示词子串（子串会被上游上下文污染）。
        java.util.Queue<AiGenerationResult> scripted = new java.util.ArrayDeque<>(java.util.List.of(
            new AiGenerationResult(true, """
                {"taskType": "bug_analysis", "summary": "订单状态未同步",
                 "hypotheses": [{"claim": "消费链路延迟", "evidenceIds": ["ev-1"], "status": "needs_confirmation"}],
                 "humanReviewRequired": true}
                """, "req-bug", 10, 20, null, false, "", 0),
            new AiGenerationResult(true, """
                {"taskType": "log_triage", "timezone": "Asia/Shanghai",
                 "timeline": [{"description": "消费开始", "lineIds": ["L102"]}]}
                """, "req-log", 10, 20, null, false, "", 0),
            new AiGenerationResult(true, """
                {"taskType": "regression_planning",
                 "requiredItems": [{"caseId": "CASE-101", "title": "订单同步回归"}]}
                """, "req-reg", 10, 20, null, false, "", 0)));
        when(facade.generate(any())).thenAnswer(invocation ->
            scripted.isEmpty()
                ? new AiGenerationResult(true, """
                    {"taskType": "regression_planning",
                     "requiredItems": [{"caseId": "CASE-101", "title": "订单同步回归"}]}
                    """, "req-reg", 10, 20, null, false, "", 0)
                : scripted.poll());
    }

    @Test
    void compositeSharesOneEvidenceManifestAcrossAllNodes() {
        stubFacadeForAllNodes();
        authenticate();
        try {
            AiAssistantCompositeService.CompositeRun run = compositeService.start(
                "bug_log_regression", 1L, inputs(), "tester");
            assertThat(run.nodes()).hasSize(3);
            // 首个节点执行后即等待人审——此时下游未执行但清单已经构建。
            assertThat(run.status()).isEqualTo("waiting_review");
            verify(manifestService, times(1)).manifestForProject(eq(1L));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void humanGateAdvancesTheDagNodeByNodeAndUnmetDepsNeverComplete() {
        stubFacadeForAllNodes();
        authenticate();
        try {
            AiAssistantCompositeService.CompositeRun run = compositeService.start(
                "bug_log_regression", 1L, inputs(), "tester");
            String compositeId = run.compositeId();
            assertThat(statusOf(run, "bug")).isEqualTo("waiting_review");
            assertThat(statusOf(run, "log")).isEqualTo("pending");
            assertThat(statusOf(run, "regression")).isEqualTo("pending");

            run = compositeService.reviewNode(compositeId, "bug", "approve", 1, null);
            assertThat(statusOf(run, "bug")).isEqualTo("accepted");
            assertThat(statusOf(run, "log")).isEqualTo("waiting_review");
            assertThat(statusOf(run, "regression")).isEqualTo("pending");
            assertThat(run.status()).isEqualTo("waiting_review");

            run = compositeService.reviewNode(compositeId, "log", "approve", 1, null);
            assertThat(statusOf(run, "log")).isEqualTo("accepted");
            assertThat(statusOf(run, "regression")).isEqualTo("waiting_review");

            run = compositeService.reviewNode(compositeId, "regression", "approve", 1, null);
            assertThat(statusOf(run, "regression")).isEqualTo("accepted");
            assertThat(run.status()).isEqualTo("succeeded");

            // 组合内所有节点仍共享同一份清单且没有二次取。
            verify(manifestService, times(1)).manifestForProject(eq(1L));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void rejectingANodeBlocksItsDownstreamWithAnExplicitReason() {
        stubFacadeForAllNodes();
        authenticate();
        try {
            AiAssistantCompositeService.CompositeRun run = compositeService.start(
                "bug_log_regression", 1L, inputs(), "tester");
            String compositeId = run.compositeId();
            int callsBefore = org.mockito.Mockito.mockingDetails(facade).getInvocations().size();

            run = compositeService.reviewNode(compositeId, "bug", "reject", 1, null);
            assertThat(statusOf(run, "bug")).isEqualTo("rejected");
            assertThat(statusOf(run, "log")).isEqualTo("skipped");
            assertThat(statusOf(run, "regression")).isEqualTo("skipped");
            assertThat(run.nodes()).anySatisfy(node ->
                assertThat(node.skipReason()).contains("依赖被驳回"));
            assertThat(run.status()).isEqualTo("partial");
            // 下游没有被执行：拒绝后不再发生模型调用。
            assertThat(org.mockito.Mockito.mockingDetails(facade).getInvocations().size()).isEqualTo(callsBefore);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void aBlockedNodePreventsDownstreamCompletion() {
        when(facade.generate(any())).thenReturn(
            new AiGenerationResult(false, null, "", 0, 0, "路由不可用", false, "no_route", 0));
        authenticate();
        try {
            AiAssistantCompositeService.CompositeRun run = compositeService.start(
                "bug_log_regression", 1L, inputs(), "tester");
            assertThat(statusOf(run, "bug")).isEqualTo("blocked");
            assertThat(statusOf(run, "log")).isEqualTo("skipped");
            assertThat(run.status()).isEqualTo("blocked");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void nodeReviewEnforcesWaitingStateActorAndRevision() {
        stubFacadeForAllNodes();
        try {
            AiAssistantCompositeService.CompositeRun run = compositeService.start(
                "bug_log_regression", 1L, inputs(), "tester");
            String compositeId = run.compositeId();
            // 未认证 actor 不得评审。
            assertThatThrownBy(() -> compositeService.reviewNode(compositeId, "bug", "approve", 1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("actor required");
            // 未处于 waiting_review 的节点不得评审。
            assertThatThrownBy(() -> compositeService.reviewNode(compositeId, "log", "approve", 1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("node_not_waiting_review");
            authenticate();
            try {
                // 过期 revision 冲突。
                assertThatThrownBy(() -> compositeService.reviewNode(compositeId, "bug", "approve", 99, null))
                    .isInstanceOf(com.haitlify.aitest.platform.ai.agentic.StaleArtifactRevisionException.class);
            } finally {
                SecurityContextHolder.clearContext();
            }
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void unknownTemplateAndMissingRootInputsFailAtTheEntrance() {
        assertThatThrownBy(() -> compositeService.start("mystery", 1L, inputs(), "tester"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unknown_template");
        assertThatThrownBy(() -> compositeService.start(
            "bug_log_regression", 1L, objectMapper.createObjectNode(), "tester"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("missing_inputs");
    }

    private String statusOf(AiAssistantCompositeService.CompositeRun run, String nodeId) {
        return run.nodes().stream().filter(node -> node.nodeId().equals(nodeId))
            .findFirst().orElseThrow().status();
    }
}
