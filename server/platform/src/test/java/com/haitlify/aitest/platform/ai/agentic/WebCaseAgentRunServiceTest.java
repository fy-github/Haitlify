package com.haitlify.aitest.platform.ai.agentic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import com.haitlify.aitest.platform.ai.agentic.rule.AgentRuleService;
import com.haitlify.aitest.platform.ai.draft.AgentWebCaseGenerationFacade;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class WebCaseAgentRunServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void savesNaturalLanguageDesignFieldsIntoArtifacts() throws Exception {
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate();
        WebCaseAgentRunService service = new WebCaseAgentRunService(
            jdbcTemplate,
            objectMapper,
            mock(AgentRuleService.class),
            mock(WebCaseAgentEventService.class),
            null
        );

        service.saveArtifacts("run-1", List.of(new AgentWebCaseGenerationFacade.GeneratedWebCase(
            "point-log-permission",
            "有日志权限角色可查看日志管理菜单及日志范围",
            "日志管理",
            "web_automation",
            "日志管理",
            "https://app-dev.example.test/",
            "chromium",
            "1440x900",
            "已存在有日志权限账号",
            "验证有日志权限角色可查看日志管理菜单及日志范围",
            "goto https://app-dev.example.test/\nassertVisible text=日志管理",
            "assertVisible text=日志管理",
            "展示日志管理菜单和可查看日志范围",
            "进入已登录首页\n查看日志管理菜单\n进入日志管理页面并查看日志范围",
            "日志管理菜单可见，且日志列表/范围信息与权限一致",
            "requirements.txt"
        )), List.of("locator-log-tab"));

        JsonNode candidate = objectMapper.readTree(jdbcTemplate.candidateJson);
        assertThat(candidate.path("naturalSteps").asText()).contains("查看日志管理菜单");
        assertThat(candidate.path("naturalExpectedResult").asText()).contains("日志管理菜单可见");
        // 证据闭环:已交付用例必须绑定其命中的页面证据 ID,而非空数组。
        assertThat(jdbcTemplate.evidenceIdsJson).contains("locator-log-tab");
    }

    @Test
    void savesConfirmedPlanActionsAndExecutionContextWithoutDroppingThem() throws Exception {
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate();
        WebCaseAgentRunService service = new WebCaseAgentRunService(
            jdbcTemplate, objectMapper, mock(AgentRuleService.class), mock(WebCaseAgentEventService.class), null);
        JsonNode actions = objectMapper.createArrayNode().add(objectMapper.createObjectNode()
            .put("id", "open").put("type", "navigate"));

        service.saveNaturalPlanArtifacts("run-plan", List.of(new AgentWebCaseGenerationFacade.NaturalWebCasePlan(
            "point-1", "列表", "用户", "用户列表", "/users", List.of("打开列表"), "列表可见", "验证列表", actions,
            "env-1", "https://example.test", "webkit", "390x844", "reuse_authenticated_context")), "plan-req-1");

        JsonNode candidate = objectMapper.readTree(jdbcTemplate.candidateJson);
        assertThat(candidate.path("actions")).isEqualTo(actions);
        assertThat(candidate.path("environmentId").asText()).isEqualTo("env-1");
        assertThat(candidate.path("baseUrl").asText()).isEqualTo("https://example.test");
        assertThat(candidate.path("browser").asText()).isEqualTo("webkit");
        assertThat(candidate.path("viewport").asText()).isEqualTo("390x844");
        assertThat(candidate.path("authenticationMode").asText()).isEqualTo("reuse_authenticated_context");
        assertThat(candidate.path("resultOrigin").asText()).isEqualTo("MODEL_GENERATED");
        assertThat(candidate.path("providerRequestId").asText()).isEqualTo("plan-req-1");
    }

    @Test
    void planArtifactsAreBlockedWhenTheProviderRequestIdIsMissing() {
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate();
        WebCaseAgentRunService service = new WebCaseAgentRunService(
            jdbcTemplate, objectMapper, mock(AgentRuleService.class), mock(WebCaseAgentEventService.class), null);

        service.saveNaturalPlanArtifacts("run-plan", List.of(new AgentWebCaseGenerationFacade.NaturalWebCasePlan(
            "point-1", "列表", "用户", "用户列表", "/users", List.of("打开列表"), "列表可见", "验证列表")), "");

        assertThat(jdbcTemplate.candidateJson).contains("\"resultOrigin\":\"BLOCKED\"");
        assertThat(jdbcTemplate.candidateJson).doesNotContain("MODEL_PROPOSED");
        assertThat(jdbcTemplate.candidateJson).contains("\"acceptanceState\":\"BLOCKED\"");
    }

    @Test
    void compiledCaseArtifactsNeverClaimAModelOrigin() {
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate();
        WebCaseAgentRunService service = new WebCaseAgentRunService(
            jdbcTemplate, objectMapper, mock(AgentRuleService.class), mock(WebCaseAgentEventService.class), null);

        service.saveArtifacts("run-compile", List.of(new AgentWebCaseGenerationFacade.GeneratedWebCase(
            "point-1", "列表", "用户", "web_automation", "用户列表", "/users", "chromium", "1440x900",
            "已登录", "验证列表", "goto /users", "assertVisible text=用户列表", "列表可见", "打开列表", "列表可见",
            "requirements.txt", "fp-1", "pk-1", List.of(), List.of("locator-log-tab"), null,
            "env-1", "https://example.test", "reuse_authenticated_context"
        )), List.of("locator-log-tab"));

        assertThat(jdbcTemplate.candidateJson).contains("\"resultOrigin\":\"RULE_EXTRACTED\"");
        assertThat(jdbcTemplate.candidateJson).contains("\"TOOL_OBSERVED\"");
        assertThat(jdbcTemplate.candidateJson).doesNotContain("MODEL_PROPOSED");
        assertThat(jdbcTemplate.evidenceIdsJson).contains("locator-log-tab");
    }

    @Test
    void planUpdateMergesEditedFieldsInsteadOfDroppingActionsAndExecutionContext() throws Exception {
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate();
        jdbcTemplate.currentAcceptanceState = "PENDING_HUMAN_CONFIRMATION";
        jdbcTemplate.currentCandidate = """
            {"sourcePointId":"point-1","title":"旧标题","actions":[{"id":"open"}],
             "environmentId":"env-1","baseUrl":"https://example.test","browser":"webkit",
             "viewport":"390x844","authenticationMode":"reuse_authenticated_context"}
            """;
        WebCaseAgentRunService service = new WebCaseAgentRunService(
            jdbcTemplate, objectMapper, mock(AgentRuleService.class), mock(WebCaseAgentEventService.class), null);

        service.updatePlan("artifact-1", objectMapper.createObjectNode().put("title", "新标题"));

        JsonNode merged = objectMapper.readTree(jdbcTemplate.candidateJson);
        assertThat(merged.path("title").asText()).isEqualTo("新标题");
        assertThat(merged.path("actions")).hasSize(1);
        assertThat(merged.path("environmentId").asText()).isEqualTo("env-1");
        assertThat(merged.path("baseUrl").asText()).isEqualTo("https://example.test");
        assertThat(merged.path("browser").asText()).isEqualTo("webkit");
        assertThat(merged.path("viewport").asText()).isEqualTo("390x844");
        assertThat(merged.path("authenticationMode").asText()).isEqualTo("reuse_authenticated_context");
    }

    @Test
    void updatingPlanCreatesNextRevisionAndKeepsParentArtifactIdentity() throws Exception {
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate();
        jdbcTemplate.currentAcceptanceState = "PENDING_HUMAN_CONFIRMATION";
        jdbcTemplate.currentCandidate = """
            {"sourcePointId":"point-1","title":"旧标题","revision":1,"idempotencyKey":"run-plan:point-1"}
            """;
        WebCaseAgentRunService service = new WebCaseAgentRunService(
            jdbcTemplate, objectMapper, mock(AgentRuleService.class), mock(WebCaseAgentEventService.class), null);

        service.updatePlan("artifact-1", objectMapper.createObjectNode().put("title", "新标题"));

        assertThat(jdbcTemplate.updateSql).contains("revision = ?", "parent_artifact_id = ?", "idempotency_key = ?");
        assertThat(jdbcTemplate.updateArgs).contains(2, "artifact-1", "run-plan:point-1");
    }
    @Test
    void rejectsAutomationCompileWithoutReviewedNaturalLanguagePlan() {
        WebCaseAgentRunService service = new WebCaseAgentRunService(
            new CapturingJdbcTemplate(),
            objectMapper,
            mock(AgentRuleService.class),
            mock(WebCaseAgentEventService.class),
            null
        );

        assertThatThrownBy(() -> service.create(new WebCaseAgentModels.WebCaseAgentStartRequest(
            1L, "analysis-1", List.of("point-1"), "env-1", "https://app-dev.example.test/", "chromium", "1440x900", null, null,
            WebCaseAgentModels.WebCaseAgentMode.COMPILE, ""
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("自动化编译必须选择已评审的自然语言计划");
    }


    @Test
    void confirmsPlanAndPersistsAcceptedGateAudit() {
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate();
        jdbcTemplate.currentAcceptanceState = "PENDING_HUMAN_CONFIRMATION";
        WebCaseAgentRunService service = new WebCaseAgentRunService(
            jdbcTemplate, objectMapper, mock(AgentRuleService.class), mock(WebCaseAgentEventService.class), null);

        withAuthenticatedActor("fyli", () -> service.confirmPlan("artifact-1", true));

        assertThat(jdbcTemplate.gateAuditSql).isNotBlank();
        assertThat(jdbcTemplate.gateAuditArgs).contains("artifact-1", "run-1", 1L, "HUMAN_EDITED", "ACCEPTED", 2, "parent-1", "idem-1", "fyli");
    }

    @Test
    void rejectsPlanConfirmationWithoutAnAuthenticatedActor() {
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate();
        jdbcTemplate.currentAcceptanceState = "PENDING_HUMAN_CONFIRMATION";
        WebCaseAgentRunService service = new WebCaseAgentRunService(
            jdbcTemplate, objectMapper, mock(AgentRuleService.class), mock(WebCaseAgentEventService.class), null);

        assertThatThrownBy(() -> service.confirmPlan("artifact-1", true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("人工确认必须来自已认证的操作人");
        assertThat(jdbcTemplate.gateAuditSql).isBlank();
    }

    @Test
    void confirmAllPlansPersistsAcceptedGateAuditForEveryPlan() {
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate();
        WebCaseAgentRunService service = new WebCaseAgentRunService(
            jdbcTemplate, objectMapper, mock(AgentRuleService.class), mock(WebCaseAgentEventService.class), null);

        withAuthenticatedActor("fyli", () -> service.confirmAllPlans("run-plan"));

        assertThat(jdbcTemplate.gateAuditSql).isNotBlank();
        assertThat(jdbcTemplate.gateAuditArgs).contains("artifact-1", "run-plan", 1L, "HUMAN_EDITED", "ACCEPTED", "fyli");
    }

    private static void withAuthenticatedActor(String username, Runnable action) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
        SecurityContextHolder.setContext(context);
        try {
            action.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void bindsAgentRunModeAndTimestampsToTheirMatchingColumns() {
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate();
        AgentRuleService ruleService = mock(AgentRuleService.class);
        when(ruleService.snapshot(1L)).thenReturn(new WebCaseAgentModels.AgentRuleSnapshot(
            "rules-1", "PROJECT", 1L, 3, "hash-1", Map.of("preflight", "检查输入")
        ));
        ObjectProvider<WebCaseAgentDispatcher> dispatcherProvider = mock(ObjectProvider.class);
        when(dispatcherProvider.getObject()).thenReturn(mock(WebCaseAgentDispatcher.class));
        WebCaseAgentRunService service = new WebCaseAgentRunService(
            jdbcTemplate,
            objectMapper,
            ruleService,
            mock(WebCaseAgentEventService.class),
            dispatcherProvider
        );

        service.create(new WebCaseAgentModels.WebCaseAgentStartRequest(
            1L, "analysis-1", List.of("point-1"), "env-1", "https://example.test", "chromium", "1440x900", null, null,
            WebCaseAgentModels.WebCaseAgentMode.PLAN, "plan-1"
        ));

        assertThat(jdbcTemplate.createArgs[6]).isInstanceOf(java.sql.Timestamp.class);
        assertThat(jdbcTemplate.createArgs[7]).isEqualTo("PLAN");
        assertThat(jdbcTemplate.createArgs[8]).isEqualTo("plan-1");
        assertThat(jdbcTemplate.createArgs[9]).isInstanceOf(java.sql.Timestamp.class);
        assertThat(jdbcTemplate.createArgs[10]).isInstanceOf(java.sql.Timestamp.class);
    }

    @Test
    void markPointBlockedRecordsThePointWithoutPrematurelyBlockingTheBatch() {
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate();
        WebCaseAgentRunService service = new WebCaseAgentRunService(
            jdbcTemplate, objectMapper, mock(AgentRuleService.class), mock(WebCaseAgentEventService.class), null);

        service.markPointBlocked("run-blocked-point", "point-1");

        assertThat(jdbcTemplate.runUpdateSql).contains("blocked_point_ids_json = ?");
        assertThat(jdbcTemplate.runUpdateSql).doesNotContain("status = 'blocked'");
        assertThat(String.valueOf(jdbcTemplate.runUpdateArgs[0])).contains("point-1");
    }

    @Test
    void partialSummarySeparatesSuccessesFromBlockedPoints() {
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate();
        jdbcTemplate.acceptedPointIds = List.of("point-a", "point-b");
        WebCaseAgentRunService service = new WebCaseAgentRunService(
            jdbcTemplate, objectMapper, mock(AgentRuleService.class), mock(WebCaseAgentEventService.class), null);

        service.partial("run-partial", 2, 3);

        assertThat(jdbcTemplate.runUpdateSql).contains("status = 'partial'");
        // 成功数与阻塞数分别落库：generatedCount>0 不再等于整批成功。
        String summary = (String) jdbcTemplate.runUpdateArgs[1];
        assertThat(summary).contains("\"status\":\"partial\"");
        assertThat(summary).contains("\"generatedCaseCount\":2");
        assertThat(summary).contains("\"blockedPointCount\":3");
        assertThat((String) jdbcTemplate.runUpdateArgs[2]).contains("阻塞 3 个测试点");
        assertThat(String.valueOf(jdbcTemplate.runUpdateArgs[0])).contains("point-a");
    }

    @Test
    void preflightBlocksTemplateAndCaseKindConflictsBeforeGeneration() {
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate();
        WebCaseAgentRunService service = new WebCaseAgentRunService(
            jdbcTemplate, objectMapper, mock(AgentRuleService.class), mock(WebCaseAgentEventService.class), null);
        // 预置运行状态（requested = p-unknown + p-conflict）
        jdbcTemplate.runRow = Map.of("requested_point_ids_json", "[\"p-unknown\",\"p-conflict\"]");
        jdbcTemplate.templateKinds = List.of("web_automation");
        jdbcTemplate.pointKinds = Map.of("p-conflict", "legacy_kind");

        boolean blocked = service.preflightBlocked("run-preflight");

        assertThat(blocked).isTrue();
        assertThat(jdbcTemplate.runUpdateSql).contains("status = 'failed'");
        assertThat((String) jdbcTemplate.runUpdateArgs[0]).contains("预检拦截");
        assertThat((String) jdbcTemplate.runUpdateArgs[0]).contains("p-unknown:unknown_point");
        assertThat((String) jdbcTemplate.runUpdateArgs[0]).contains("p-conflict:template_kind_conflict:legacy_kind");
    }

    @Test
    void preflightPassesWhenPointsExistAndKindsAreBacked() {
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate();
        WebCaseAgentRunService service = new WebCaseAgentRunService(
            jdbcTemplate, objectMapper, mock(AgentRuleService.class), mock(WebCaseAgentEventService.class), null);
        jdbcTemplate.runRow = Map.of("requested_point_ids_json", "[\"p-ok\"]");
        jdbcTemplate.templateKinds = List.of("web_automation");
        jdbcTemplate.pointKinds = Map.of("p-ok", "web_automation");

        assertThat(service.preflightBlocked("run-preflight-ok")).isFalse();
        // 全内建 kind 即便项目没有模板也不冲突
        jdbcTemplate.templateKinds = List.of();
        jdbcTemplate.pointKinds = Map.of("p-ok", "functional");
        assertThat(service.preflightBlocked("run-preflight-builtin")).isFalse();
    }

    @Test
    void staleRevisionConfirmationsReturnConflictInsteadOfOverwriting() {
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate();
        jdbcTemplate.currentAcceptanceState = "PENDING_HUMAN_CONFIRMATION";
        WebCaseAgentRunService service = new WebCaseAgentRunService(
            jdbcTemplate, objectMapper, mock(AgentRuleService.class), mock(WebCaseAgentEventService.class), null);

        // 人工确认必须来自已认证操作人（gate：不得伪造操作者）。
        try {
            var authentication = new UsernamePasswordAuthenticationToken("qa-user", null, java.util.List.of());
            SecurityContextHolder.getContext().setAuthentication(authentication);
        // 过期 revision（当前为 2）⇒ 冲突，绝不覆盖。
        assertThatThrownBy(() -> service.confirmPlan("artifact-1", true, 1))
            .isInstanceOf(StaleArtifactRevisionException.class)
            .hasMessageContaining("stale");
        assertThatThrownBy(() -> service.updatePlan("artifact-1", objectMapper.createObjectNode(), 1))
            .isInstanceOf(StaleArtifactRevisionException.class);

        // 匹配 revision 与旧调用（null）继续可用。
        service.confirmPlan("artifact-1", true, 2);
        service.confirmPlan("artifact-1", false, null);
        service.updatePlan("artifact-1", objectMapper.createObjectNode().put("title", "x"), 2);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void repeatedConfirmationAndRejectionAreIdempotentWithoutDuplicateAudit() {
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate();
        jdbcTemplate.currentAcceptanceState = "ACCEPTED";
        WebCaseAgentRunService service = new WebCaseAgentRunService(
            jdbcTemplate, objectMapper, mock(AgentRuleService.class), mock(WebCaseAgentEventService.class), null);
        withAuthenticatedActor("fyli", () -> service.confirmPlan("artifact-1", true));
        assertThat(jdbcTemplate.gateAuditSql).isBlank();
        assertThat(jdbcTemplate.updateSql).isNull();

        CapturingJdbcTemplate rejectTemplate = new CapturingJdbcTemplate();
        rejectTemplate.currentAcceptanceState = "BLOCKED";
        WebCaseAgentRunService rejectService = new WebCaseAgentRunService(
            rejectTemplate, objectMapper, mock(AgentRuleService.class), mock(WebCaseAgentEventService.class), null);
        withAuthenticatedActor("fyli", () -> rejectService.confirmPlan("artifact-1", false));
        assertThat(rejectTemplate.gateAuditSql).isBlank();
        assertThat(rejectTemplate.updateSql).isNull();
    }

    @Test
    void rejectingAnAcceptedPlanAndUpdatingOneAreRefusedFailClosed() {
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate();
        jdbcTemplate.currentAcceptanceState = "ACCEPTED";
        WebCaseAgentRunService service = new WebCaseAgentRunService(
            jdbcTemplate, objectMapper, mock(AgentRuleService.class), mock(WebCaseAgentEventService.class), null);

        withAuthenticatedActor("fyli", () -> {
            assertThatThrownBy(() -> service.confirmPlan("artifact-1", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("review_state_conflict");
            assertThatThrownBy(() -> service.updatePlan("artifact-1", objectMapper.createObjectNode(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("review_state_conflict");
        });
        assertThat(jdbcTemplate.gateAuditSql).isBlank();
        assertThat(jdbcTemplate.updateSql).isNull();
    }

    @Test
    void rejectingAPendingPlanMovesThroughTheAcceptanceStateMachine() {
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate();
        jdbcTemplate.currentAcceptanceState = "PENDING_HUMAN_CONFIRMATION";
        WebCaseAgentRunService service = new WebCaseAgentRunService(
            jdbcTemplate, objectMapper, mock(AgentRuleService.class), mock(WebCaseAgentEventService.class), null);

        withAuthenticatedActor("fyli", () -> service.confirmPlan("artifact-1", false));

        assertThat(jdbcTemplate.updateArgs).contains("BLOCKED");
        assertThat(jdbcTemplate.gateAuditSql).isBlank(); // 驳回不写「已接受」Gate 审计
    }

    @Test
    void confirmAllPlansIsIdempotentWhenEverythingIsAlreadyAccepted() {
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate();
        jdbcTemplate.planRows = List.of();
        jdbcTemplate.totalPlans = 2;
        jdbcTemplate.notAcceptedPlans = 0;
        WebCaseAgentRunService service = new WebCaseAgentRunService(
            jdbcTemplate, objectMapper, mock(AgentRuleService.class), mock(WebCaseAgentEventService.class), null);

        withAuthenticatedActor("fyli", () -> service.confirmAllPlans("run-plan"));

        assertThat(jdbcTemplate.gateAuditSql).isBlank();
        assertThat(jdbcTemplate.updateSql).isNull();
    }

    private static class CapturingJdbcTemplate extends JdbcTemplate {
        private String candidateJson;
        private String evidenceIdsJson;
        private Object[] createArgs;
        private String currentCandidate;
        private Object[] updateArgs;
        private String updateSql;
        private String currentAcceptanceState;
        private String runId = "run-1";
        private String gateAuditSql;
        private Object[] gateAuditArgs;
        private String runUpdateSql;
        private Object[] runUpdateArgs;
        private List<String> acceptedPointIds = List.of();
        private Integer totalPlans;
        private Integer notAcceptedPlans;
        private Map<String, Object> runRow = Map.of("requested_point_ids_json", "[]");
        private List<Map<String, Object>> planRows = List.of(Map.of(
            "candidate_json", "{\"sourcePointId\":\"point-1\",\"title\":\"列表\",\"module\":\"用户\",\"pageName\":\"用户列表\",\"startUrl\":\"/users\",\"naturalSteps\":[\"打开列表\"],\"naturalExpectedResult\":\"列表可见\",\"objective\":\"验证列表\",\"environmentId\":\"env-1\",\"baseUrl\":\"https://example.test\",\"browser\":\"webkit\",\"viewport\":\"390x844\",\"authenticationMode\":\"reuse_authenticated_context\"}",
            "artifact_id", "artifact-1", "run_id", "run-plan", "point_id", "point-1", "revision", 1, "parent_artifact_id", "", "idempotency_key", "idem-1", "evidence_ids_json", "[]"
        ));
        public int update(String sql, Object... args) {
            if (sql.startsWith("insert into ai_web_case_agent_artifacts")) {
                candidateJson = (String) args[3];
                if (sql.contains("'web_case'")) evidenceIdsJson = (String) args[4];
            }
            if (sql.startsWith("update ai_web_case_agent_artifacts")) {
                updateSql = sql;
                updateArgs = args;
                if (args.length > 0 && args[0] instanceof String) candidateJson = (String) args[0];
            }
            if (sql.startsWith("insert into ai_artifact_gate_audits")) {
                gateAuditSql = sql;
                gateAuditArgs = args;
            }
            if (sql.startsWith("insert into ai_web_case_agent_runs")) {
                createArgs = args;
            }
            if (sql.startsWith("update ai_web_case_agent_runs")) {
                runUpdateSql = sql;
                runUpdateArgs = args;
            }
            return 1;
        }

        private List<String> templateKinds = List.of();
        private Map<String, String> pointKinds = Map.of();

        @Override
        public <T> T queryForObject(String sql, RowMapper<T> rowMapper, Object... args) {
            if (sql.contains("from ai_web_case_agent_runs")) {
                try {
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getString("run_id")).thenReturn(String.valueOf(runRow.getOrDefault("run_id", "run-preflight")));
                    when(rs.getLong("project_id")).thenReturn(1L);
                    when(rs.getString("analysis_run_id")).thenReturn("");
                    when(rs.getString("status")).thenReturn(String.valueOf(runRow.getOrDefault("status", "queued")));
                    when(rs.getString("current_stage")).thenReturn(String.valueOf(runRow.getOrDefault("current_stage", "preflight")));
                    when(rs.getString("requested_point_ids_json")).thenReturn(String.valueOf(runRow.getOrDefault("requested_point_ids_json", "[]")));
                    when(rs.getString("completed_point_ids_json")).thenReturn("[]");
                    when(rs.getString("blocked_point_ids_json")).thenReturn("[]");
                    when(rs.getInt("rule_version")).thenReturn(1);
                    when(rs.getTimestamp("started_at")).thenReturn(null);
                    when(rs.getTimestamp("updated_at")).thenReturn(java.sql.Timestamp.from(java.time.Instant.now()));
                    when(rs.getString("error_summary")).thenReturn("");
                    when(rs.getString("mode")).thenReturn("plan");
                    when(rs.getString("plan_run_id")).thenReturn("");
                    return rowMapper.mapRow(rs, 0);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }
            throw new UnsupportedOperationException("queryForObject not stubbed: " + sql);
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (sql.contains("select case_kind from ai_draft_test_points")) {
                String pointId = args.length > 1 ? String.valueOf(args[1]) : "";
                if (pointKinds.containsKey(pointId)) {
                    return List.of(Map.of("case_kind", pointKinds.get(pointId)));
                }
                return List.of();
            }
            return List.of();
        }

        @Override
        public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
            if (sql.contains("select distinct point_id from ai_web_case_agent_artifacts")) {
                @SuppressWarnings("unchecked")
                List<T> result = (List<T>) acceptedPointIds;
                return result;
            }
            if (sql.contains("select distinct kind from case_template_configs")) {
                @SuppressWarnings("unchecked")
                List<T> result = (List<T>) templateKinds;
                return result;
            }
            if (sql.contains("select case_kind from ai_draft_test_points")) {
                String pointId = args.length > 1 ? String.valueOf(args[1]) : "";
                if (pointKinds.containsKey(pointId)) {
                    @SuppressWarnings("unchecked")
                    List<T> result = (List<T>) List.of(Map.of("case_kind", pointKinds.get(pointId)));
                    return result;
                }
                return List.of();
            }
            return List.of();
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            if (sql.startsWith("select artifact_id, run_id, revision")) {
                return planRows.stream().map(row -> {
                    try {
                        java.sql.ResultSet rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
                        when(rs.getString("artifact_id")).thenReturn((String) row.get("artifact_id"));
                        when(rs.getString("run_id")).thenReturn((String) row.get("run_id"));
                        when(rs.getInt("revision")).thenReturn((Integer) row.get("revision"));
                        when(rs.getString("parent_artifact_id")).thenReturn((String) row.get("parent_artifact_id"));
                        when(rs.getString("idempotency_key")).thenReturn((String) row.get("idempotency_key"));
                        when(rs.getString("evidence_ids_json")).thenReturn((String) row.get("evidence_ids_json"));
                        return rowMapper.mapRow(rs, 0);
                    } catch (Exception exception) { throw new RuntimeException(exception); }
                }).toList();
            }
            if (sql.startsWith("select candidate_json from ai_web_case_agent_artifacts")) {
                return planRows.stream().map(row -> {
                    try {
                        java.sql.ResultSet rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
                        when(rs.getString("candidate_json")).thenReturn((String) row.get("candidate_json"));
                        when(rs.getString("artifact_id")).thenReturn((String) row.get("artifact_id"));
                        when(rs.getString("run_id")).thenReturn((String) row.get("run_id"));
                        when(rs.getString("point_id")).thenReturn((String) row.get("point_id"));
                        when(rs.getInt("revision")).thenReturn((Integer) row.get("revision"));
                        when(rs.getString("parent_artifact_id")).thenReturn((String) row.get("parent_artifact_id"));
                        when(rs.getString("idempotency_key")).thenReturn((String) row.get("idempotency_key"));
                        return rowMapper.mapRow(rs, 0);
                    } catch (Exception exception) { throw new RuntimeException(exception); }
                }).toList();
            }
            return List.of();
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            if (sql.startsWith("select project_id from ai_web_case_agent_runs")) return requiredType.cast(1L);
            if (sql.startsWith("select r.project_id")) return requiredType.cast(1L);
            if (sql.startsWith("select revision")) return requiredType.cast(2);
            if (sql.startsWith("select parent_artifact_id")) return requiredType.cast("parent-1");
            if (sql.startsWith("select idempotency_key")) return requiredType.cast("idem-1");
            if (sql.startsWith("select run_id from ai_web_case_agent_artifacts")) return requiredType.cast(runId);
            if (sql.startsWith("select acceptance_state from ai_web_case_agent_artifacts")) return requiredType.cast(currentAcceptanceState);
            if (sql.startsWith("select candidate_json")) return requiredType.cast(currentCandidate);
            if (sql.startsWith("select count(*) from ai_web_case_agent_artifacts")) {
                if (sql.contains("acceptance_state <> 'ACCEPTED'")) return requiredType.cast(notAcceptedPlans);
                return requiredType.cast(totalPlans);
            }
            return null;
        }
    }
}
