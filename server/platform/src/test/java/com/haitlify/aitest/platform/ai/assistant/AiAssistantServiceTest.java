package com.haitlify.aitest.platform.ai.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.haitlify.aitest.platform.ai.agentic.StaleArtifactRevisionException;
import com.haitlify.aitest.platform.ai.evidence.AiEvidenceManifestService;
import com.haitlify.aitest.platform.ai.event.AiRunEventService;
import com.haitlify.aitest.platform.ai.gateway.AiGenerationResult;
import com.haitlify.aitest.platform.ai.knowledge.AiContextBuilder;
import com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * T12 批次 2：助手执行服务流——入口缺项、真实失败 BLOCKED、输出证据违规随产物入审、
 * 评审 actor/revision 门、拒绝阻断下游、修改产生新 revision（旧版不可变）。
 */
class AiAssistantServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JdbcTemplate jdbcTemplate;
    private AiTaskExecutionFacade facade;
    private AiRunEventService eventService;
    private AiAssistantService service;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:assistant-test;DB_CLOSE_DELAY=-1", "sa", "");
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
        jdbcTemplate.execute("delete from ai_assistant_artifacts");
        jdbcTemplate.execute("""
            create table if not exists ai_draft_test_points(
              id bigint auto_increment primary key, point_id varchar(128) not null,
              project_id bigint not null, title varchar(512) not null, module_name varchar(255) default '',
              case_kind varchar(64) default '', review_status varchar(32) not null default 'ai_generated',
              updated_at timestamp)
            """);
        jdbcTemplate.execute("delete from ai_draft_test_points");

        facade = mock(AiTaskExecutionFacade.class);
        AiEvidenceManifestService manifestService = mock(AiEvidenceManifestService.class);
        when(manifestService.manifestForProject(any())).thenReturn(
            new AiEvidenceManifestService.EvidenceManifest(1L, List.of(), 0, 0, 0, List.of("无文档")));
        AiContextBuilder contextBuilder = mock(AiContextBuilder.class);
        when(contextBuilder.build(any(), any(), any(Integer.class))).thenReturn(
            new AiContextBuilder.BuiltContext(List.of(), 0, 0, 4000, List.of(), AiContextBuilder.ESTIMATE_VERSION));
        eventService = mock(AiRunEventService.class);
        service = new AiAssistantService(jdbcTemplate, objectMapper, facade, manifestService, contextBuilder, eventService);
    }

    @org.junit.jupiter.api.AfterEach
    void clearAuthContext() {
        SecurityContextHolder.clearContext();
    }

    private JsonNode output(String json) {
        return objectMapper.readTree(json);
    }

    @Test
    void missingInputsAndUnknownTypesFailAtTheEntrance() {
        assertThatThrownBy(() -> service.startRun("log_triage", 1L, output("{\"timeRange\": \"x\"}"), "tester"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("missing_inputs")
            .hasMessageContaining("logExcerpt");
        assertThatThrownBy(() -> service.startRun("mystery", 1L, output("{}"), "tester"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unknown_task_type");
    }

    @Test
    void realModelFailureLandsAsBlockedAndNeverFabricatesACompletion() {
        when(facade.generate(any())).thenReturn(
            new AiGenerationResult(false, null, "", 0, 0, "连接不可用", false, "auth_denied", 0));
        AiAssistantService.AssistantRun run = service.startRun(
            "bug_analysis", 1L, output("{\"symptom\": \"支付成功后订单状态未同步\"}"), "tester");
        assertThat(run.status()).isEqualTo("blocked");
        assertThat(run.blockReason()).contains("连接不可用");
        assertThat(run.artifactId()).isNull();
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from ai_assistant_artifacts", Integer.class)).isZero();
    }

    @Test
    void evidenceViolationsArePersistedWithTheArtifactInsteadOfBeingSilentlyDropped() {
        when(facade.generate(any())).thenReturn(new AiGenerationResult(true, """
            {
              "taskType": "bug_analysis",
              "summary": "支付成功后订单状态未同步",
              "hypotheses": [{"claim": "消费链路延迟", "evidenceIds": [], "status": "identified"}],
              "humanReviewRequired": true
            }
            """, "req-1", 10, 20, null, false, "", 0));
        AiAssistantService.AssistantRun run = service.startRun(
            "bug_analysis", 1L, output("{\"symptom\": \"支付成功后订单状态未同步\"}"), "tester");

        assertThat(run.status()).isEqualTo("waiting_review");
        assertThat(run.violations()).anySatisfy(violation ->
            assertThat(violation).contains("没有证据不得给「已定位根因」"));
        assertThat(run.acceptanceState()).isEqualTo(AiAssistantService.STATE_PENDING);
        assertThat(jdbcTemplate.queryForObject(
            "select result_origin from ai_assistant_artifacts where artifact_id = ?",
            String.class, run.artifactId())).isEqualTo("MODEL_GENERATED");
    }

    @Test
    void reviewRequiresAnAuthenticatedActor() {
        when(facade.generate(any())).thenReturn(new AiGenerationResult(true, """
            {"taskType": "bug_analysis", "summary": "s", "hypotheses": [
              {"claim": "c", "evidenceIds": ["e1"], "status": "needs_confirmation"}], "humanReviewRequired": true}
            """, "req-2", 10, 20, null, false, "", 0));
        AiAssistantService.AssistantRun run = service.startRun(
            "bug_analysis", 1L, output("{\"symptom\": \"s\"}"), "tester");

        assertThatThrownBy(() -> service.review(run.artifactId(), "approve", 1, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("actor required");

        // 匿名令牌（过滤器实际注入的形态）同样不得视为操作人
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.AnonymousAuthenticationToken(
                "key", "anonymousUser",
                org.springframework.security.core.authority.AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        try {
            assertThatThrownBy(() -> service.review(run.artifactId(), "approve", 1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("actor required");
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void reviewEnforcesRevisionConcurrencyAndBlocksDownstreamAfterRejection() {
        when(facade.generate(any())).thenReturn(new AiGenerationResult(true, """
            {"taskType": "bug_analysis", "summary": "s", "hypotheses": [
              {"claim": "c", "evidenceIds": ["e1"], "status": "needs_confirmation"}], "humanReviewRequired": true}
            """, "req-3", 10, 20, null, false, "", 0));
        AiAssistantService.AssistantRun run = service.startRun(
            "bug_analysis", 1L, output("{\"symptom\": \"s\"}"), "tester");

        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("qa-user", null, List.of()));
        try {
            assertThatThrownBy(() -> service.review(run.artifactId(), "approve", 99, null))
                .isInstanceOf(StaleArtifactRevisionException.class)
                .hasMessageContaining("stale");

            AiAssistantService.AssistantRun rejected = service.review(run.artifactId(), "reject", 1, null);
            assertThat(rejected.acceptanceState()).isEqualTo(AiAssistantService.STATE_REJECTED);
            // 拒绝阻止下游：不得再次评审同一产物。
            assertThatThrownBy(() -> service.review(run.artifactId(), "approve", 1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("artifact_not_pending");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void changeCreatesANewImmutableRevision() {
        when(facade.generate(any())).thenReturn(new AiGenerationResult(true, """
            {"taskType": "bug_analysis", "summary": "原稿", "hypotheses": [
              {"claim": "c", "evidenceIds": ["e1"], "status": "needs_confirmation"}], "humanReviewRequired": true}
            """, "req-4", 10, 20, null, false, "", 0));
        AiAssistantService.AssistantRun run = service.startRun(
            "bug_analysis", 1L, output("{\"symptom\": \"s\"}"), "tester");

        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("qa-user", null, List.of()));
        try {
            JsonNode revised = output("""
                {"taskType": "bug_analysis", "summary": "人工修订稿", "hypotheses": [
                  {"claim": "c2", "evidenceIds": ["e1", "e2"], "status": "needs_confirmation"}], "humanReviewRequired": true}
                """);
            AiAssistantService.AssistantRun changed = service.review(run.artifactId(), "change", 1, revised);
            assertThat(changed.revision()).isEqualTo(2);
            assertThat(changed.acceptanceState()).isEqualTo(AiAssistantService.STATE_PENDING);

            // 旧 revision 状态不可变（仅标记被替换），payload 保留原稿。
            String originalPayload = jdbcTemplate.queryForObject(
                "select payload_json from ai_assistant_artifacts where artifact_id = ?",
                String.class, run.artifactId());
            assertThat(originalPayload).contains("原稿");
            assertThat(jdbcTemplate.queryForObject(
                "select acceptance_state from ai_assistant_artifacts where artifact_id = ?",
                String.class, run.artifactId())).isEqualTo(AiAssistantService.STATE_REJECTED);

            AiAssistantService.AssistantRun fetched = service.getRun(run.runId());
            assertThat(fetched.revision()).isEqualTo(2);
            assertThat(fetched.acceptanceState()).isEqualTo(AiAssistantService.STATE_PENDING);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void approveMarksTheArtifactAcceptedWithHumanOrigin() {
        when(facade.generate(any())).thenReturn(new AiGenerationResult(true, """
            {"taskType": "bug_analysis", "summary": "s", "hypotheses": [
              {"claim": "c", "evidenceIds": ["e1"], "status": "needs_confirmation"}], "humanReviewRequired": true}
            """, "req-5", 10, 20, null, false, "", 0));
        AiAssistantService.AssistantRun run = service.startRun(
            "bug_analysis", 1L, output("{\"symptom\": \"s\"}"), "tester");

        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("qa-user", null, List.of()));
        try {
            AiAssistantService.AssistantRun approved = service.review(run.artifactId(), "approve", 1, null);
            assertThat(approved.acceptanceState()).isEqualTo(AiAssistantService.STATE_ACCEPTED);
            assertThat(jdbcTemplate.queryForObject(
                "select result_origin from ai_assistant_artifacts where artifact_id = ?",
                String.class, run.artifactId())).isEqualTo("HUMAN_EDITED");
            assertThat(jdbcTemplate.queryForObject(
                "select reviewed_by from ai_assistant_artifacts where artifact_id = ?",
                String.class, run.artifactId())).isEqualTo("qa-user");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void approveIsBlockedWhileViolationsRemainAndRecoversAfterAFixedChange() {
        // 违规产物：模型输出无证据却缺 status 要求的假设字段（bug_analysis 缺 hypotheses）
        seedArtifact("artifact-violating", AiAssistantService.STATE_PENDING, 1, "",
            "{\"summary\": \"缺证据\"}");
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("qa-user", null, List.of()));
        try {
            assertThatThrownBy(() -> service.review("artifact-violating", "approve", 1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unresolved_violations");
            // 状态未被放行；违规记录被保留在产物上
            assertThat(jdbcTemplate.queryForObject(
                "select acceptance_state from ai_assistant_artifacts where artifact_id = ?",
                String.class, "artifact-violating")).isEqualTo(AiAssistantService.STATE_PENDING);
            assertThat(jdbcTemplate.queryForObject(
                "select violations_json from ai_assistant_artifacts where artifact_id = ?",
                String.class, "artifact-violating")).contains("hypotheses");

            // 人工修订为合规内容 ⇒ 新 revision 无违规 ⇒ 批准通过
            tools.jackson.databind.JsonNode fixed = output(VALID_BUG_ANALYSIS_PAYLOAD);
            AiAssistantService.AssistantRun revised = service.review("artifact-violating", "change", 1, fixed);
            assertThat(revised.violations()).isEmpty();
            AiAssistantService.AssistantRun approved =
                service.review(revised.artifactId(), "approve", revised.revision(), null);
            assertThat(approved.acceptanceState()).isEqualTo(AiAssistantService.STATE_ACCEPTED);
        } catch (Exception unexpected) {
            throw new AssertionError(unexpected);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void changeRecordsCandidateViolationsOnTheNewRevision() {
        seedArtifact("artifact-change-violating", AiAssistantService.STATE_PENDING, 1, "");
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("qa-user", null, List.of()));
        try {
            tools.jackson.databind.JsonNode bad = output("{\"summary\": \"仍然缺证据\"}");
            AiAssistantService.AssistantRun revised =
                service.review("artifact-change-violating", "change", 1, bad);
            assertThat(revised.violations()).isNotEmpty();
            assertThat(jdbcTemplate.queryForObject(
                "select violations_json from ai_assistant_artifacts where artifact_id = ?",
                String.class, revised.artifactId())).contains("hypotheses");
        } catch (Exception unexpected) {
            throw new AssertionError(unexpected);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void exportRecheckBlocksLegacyAcceptedArtifactsThatCarryViolations() {
        // 旧规则下被接受的产物：payload 违规但 violations_json 为空 ⇒ 导出复检必须 fail-closed 并留痕
        seedArtifact("artifact-legacy-accepted", AiAssistantService.STATE_ACCEPTED, 1, "legacy",
            "{\"summary\": \"历史产物\"}");
        assertThatThrownBy(() -> service.exportArtifact("artifact-legacy-accepted", 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unresolved_violations");
        assertThat(jdbcTemplate.queryForObject(
            "select violations_json from ai_assistant_artifacts where artifact_id = ?",
            String.class, "artifact-legacy-accepted")).contains("hypotheses");
    }

    private static final String VALID_BUG_ANALYSIS_PAYLOAD = "{\"summary\": \"导出内容\", \"hypotheses\":"
        + " [{\"claim\": \"c\", \"evidenceIds\": [\"e1\"], \"status\": \"needs_confirmation\"}],"
        + " \"humanReviewRequired\": true}";

    private void seedArtifact(String artifactId, String state, int revision, String reviewedBy) {
        seedArtifact(artifactId, state, revision, reviewedBy, VALID_BUG_ANALYSIS_PAYLOAD);
    }

    private void seedArtifact(String artifactId, String state, int revision, String reviewedBy, String payloadJson) {
        seedArtifactFull(artifactId, state, revision, reviewedBy, payloadJson, null, "bug_analysis");
    }

    private void seedArtifactFull(String artifactId, String state, int revision, String reviewedBy,
                                  String payloadJson, String factsJson, String taskType) {
        jdbcTemplate.update("""
            insert into ai_assistant_artifacts(artifact_id, run_id, task_type, project_id, revision,
              acceptance_state, result_origin, payload_json, violations_json, source_manifest_json,
              created_by, reviewed_by, created_at, updated_at, deterministic_facts_json)
            values (?, ?, ?, 1, ?, ?, 'MODEL_GENERATED', ?, '[]',
              '{\"documents\": []}', 'tester', ?, current_timestamp, current_timestamp, ?)
            """, artifactId, "run-" + artifactId, taskType, revision, state, payloadJson, reviewedBy, factsJson);
    }

    @Test
    void exportReleasesAcceptedPayloadsAndRefusesPendingOnes() {
        seedArtifact("artifact-accepted", AiAssistantService.STATE_ACCEPTED, 2, "qa-user");
        Map<String, Object> exported = service.exportArtifact("artifact-accepted", 2);

        assertThat(exported.get("artifactId")).isEqualTo("artifact-accepted");
        assertThat(exported.get("acceptanceState")).isEqualTo(AiAssistantService.STATE_ACCEPTED);
        assertThat(exported.get("revision")).isEqualTo(2);
        assertThat(exported.get("reviewedBy")).isEqualTo("qa-user");
        assertThat(String.valueOf(exported.get("output"))).contains("导出内容");
        assertThat(exported).containsKeys("sourceManifest", "violations", "exportedAt");

        seedArtifact("artifact-pending", AiAssistantService.STATE_PENDING, 1, "");
        assertThatThrownBy(() -> service.exportArtifact("artifact-pending", 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("artifact_not_accepted");
    }

    @Test
    void exportRejectsStaleRevisionsAndUnknownArtifactsUniformly() {
        seedArtifact("artifact-rev", AiAssistantService.STATE_ACCEPTED, 3, "qa-user");
        assertThatThrownBy(() -> service.exportArtifact("artifact-rev", 2))
            .isInstanceOf(StaleArtifactRevisionException.class)
            .hasMessageContaining("stale");
        assertThatThrownBy(() -> service.exportArtifact("artifact-missing", null))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("not_found");
    }

    @Test
    void artifactListIsScopedToTheProjectNewestFirstAndCapped() {
        seedArtifact("artifact-a", AiAssistantService.STATE_ACCEPTED, 1, "qa-user");
        jdbcTemplate.update("update ai_assistant_artifacts set created_at = dateadd('MINUTE', -30, current_timestamp) where artifact_id = 'artifact-a'");
        seedArtifact("artifact-b", AiAssistantService.STATE_PENDING, 1, "");
        jdbcTemplate.update("update ai_assistant_artifacts set created_at = dateadd('MINUTE', -10, current_timestamp) where artifact_id = 'artifact-b'");
        seedArtifact("artifact-c", "REJECTED", 1, "qa-user");
        jdbcTemplate.update("update ai_assistant_artifacts set created_at = dateadd('MINUTE', -20, current_timestamp) where artifact_id = 'artifact-c'");

        Map<String, Object> listed = service.listArtifacts(1L, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) listed.get("artifacts");
        assertThat(items).hasSize(3);
        // 严格按 created_at desc：b(-10) → c(-20) → a(-30)
        assertThat(items).extracting(item -> item.get("artifactId"))
            .containsExactly("artifact-b", "artifact-c", "artifact-a");
        assertThat(items).extracting(item -> item.get("status"))
            .containsExactly("waiting_review", "rejected", "accepted");

        // limit 上限 50 收紧
        seedArtifact("artifact-d", AiAssistantService.STATE_PENDING, 1, "");
        Map<String, Object> capped = service.listArtifacts(1L, 999);
        assertThat(capped.get("limit")).isEqualTo(50);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cappedItems = (List<Map<String, Object>>) capped.get("artifacts");
        assertThat(cappedItems).hasSize(4);

        // 另一项目空列表（隔离）
        Map<String, Object> other = service.listArtifacts(2L, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> otherItems = (List<Map<String, Object>>) other.get("artifacts");
        assertThat(otherItems).isEmpty();
    }

    @Test
    void draftPointDirectoryListsOnlyUsablePointsScopedToTheProject() {
        jdbcTemplate.update("""
            insert into ai_draft_test_points(point_id, project_id, title, module_name, case_kind, review_status, updated_at)
            values ('point-confirmed', 1, '登录成功跳转首页', '认证', 'interface', 'confirmed', dateadd('MINUTE', -10, current_timestamp))
            """);
        jdbcTemplate.update("""
            insert into ai_draft_test_points(point_id, project_id, title, module_name, case_kind, review_status, updated_at)
            values ('point-modified', 1, '改过的点', '认证', 'interface', 'modified', dateadd('MINUTE', -5, current_timestamp))
            """);
        jdbcTemplate.update("""
            insert into ai_draft_test_points(point_id, project_id, title, module_name, case_kind, review_status, updated_at)
            values ('point-raw', 1, '未确认点', '认证', 'interface', 'ai_generated', current_timestamp)
            """);
        jdbcTemplate.update("""
            insert into ai_draft_test_points(point_id, project_id, title, module_name, case_kind, review_status, updated_at)
            values ('point-deleted', 1, '已删除点', '认证', 'interface', 'deleted', current_timestamp)
            """);
        jdbcTemplate.update("""
            insert into ai_draft_test_points(point_id, project_id, title, module_name, case_kind, review_status, updated_at)
            values ('point-other-project', 2, '别项目点', 'x', 'interface', 'confirmed', current_timestamp)
            """);

        Map<String, Object> listed = service.listDraftPoints(1L, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) listed.get("items");
        // 仅 confirmed/modified（与 generateCases 准入一致）、新→旧、跨项目隔离
        assertThat(items).extracting(item -> item.get("pointId"))
            .containsExactly("point-modified", "point-confirmed");
        assertThat(items.get(0).get("reviewStatus")).isEqualTo("modified");
        assertThat(items.get(0).get("title")).isEqualTo("改过的点");

        // limit 上限 50 收紧
        Map<String, Object> capped = service.listDraftPoints(1L, 999);
        assertThat(capped.get("limit")).isEqualTo(50);
    }

    @Test
    void violationsSurviveTheRoundTripAndLegacyJavaFormatRowsStayReadable() {
        // 1) 真实链路：带违规的运行落库后经 getRun 回读可见（此前落库用 Java toString，回读解析失败被吞）
        when(facade.generate(any())).thenReturn(new AiGenerationResult(true, """
            {
              "taskType": "bug_analysis",
              "summary": "支付成功后订单状态未同步",
              "hypotheses": [{"claim": "消费链路延迟", "evidenceIds": [], "status": "identified"}],
              "humanReviewRequired": true
            }
            """, "req-v", 10, 20, null, false, "", 0));
        AiAssistantService.AssistantRun run = service.startRun(
            "bug_analysis", 1L, output("{\"symptom\": \"支付成功后订单状态未同步\"}"), "tester");
        assertThat(run.violations()).isNotEmpty();
        assertThat(service.getRun(run.runId()).violations()).isNotEmpty();

        // 2) 修复前的历史行（Java List.toString 形态）不回写、读取端容错
        jdbcTemplate.update("""
            insert into ai_assistant_artifacts(artifact_id, run_id, task_type, project_id, revision,
              acceptance_state, result_origin, payload_json, violations_json, source_manifest_json,
              created_by, reviewed_by, created_at, updated_at)
            values ('artifact-legacy', 'run-legacy', 'bug_analysis', 1, 1, 'PENDING_HUMAN_CONFIRMATION',
              'MODEL_GENERATED', '{}', '[必须给出假设, 证据不足]', '{}', 'tester', '', current_timestamp, current_timestamp)
            """);
        assertThat(service.getRun("run-legacy").violations())
            .containsExactly("必须给出假设", "证据不足");
    }

    // ---- 项 4：评审写路径 CAS —— 并发双评审 / 重复请求幂等 / 状态翻转 ----

    private void authenticate(String username) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(username, null, List.of()));
    }

    private String stateOf(String artifactId) {
        return jdbcTemplate.queryForObject(
            "select acceptance_state from ai_assistant_artifacts where artifact_id = ?",
            String.class, artifactId);
    }

    private long eventCount(String stage) {
        var captor = org.mockito.ArgumentCaptor.forClass(com.haitlify.aitest.platform.ai.event.AiRunEvent.class);
        org.mockito.Mockito.verify(eventService, org.mockito.Mockito.atLeast(0)).emit(captor.capture());
        return captor.getAllValues().stream().filter(event -> stage.equals(event.stage())).count();
    }

    @Test
    void concurrentApproveAndRejectLeaveExactlyOneWinner() throws Exception {
        seedArtifact("artifact-race", AiAssistantService.STATE_PENDING, 1, "");
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var approve = pool.submit(() -> {
                authenticate("qa-user");
                try {
                    start.await();
                    return (Object) service.review("artifact-race", "approve", 1, null);
                } catch (RuntimeException failure) {
                    return (Object) failure;
                }
            });
            var reject = pool.submit(() -> {
                authenticate("qa-user");
                try {
                    start.await();
                    return (Object) service.review("artifact-race", "reject", 1, null);
                } catch (RuntimeException failure) {
                    return (Object) failure;
                }
            });
            start.countDown();
            Object approveOutcome = approve.get();
            Object rejectOutcome = reject.get();
            long successes = java.util.stream.Stream.of(approveOutcome, rejectOutcome)
                .filter(outcome -> !(outcome instanceof RuntimeException)).count();
            assertThat(successes).isEqualTo(1);
            assertThat(stateOf("artifact-race"))
                .isIn(AiAssistantService.STATE_ACCEPTED, AiAssistantService.STATE_REJECTED);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void repeatedApproveAndRejectAreIdempotentWithoutDuplicateEvents() {
        seedArtifact("artifact-dup-approve", AiAssistantService.STATE_PENDING, 1, "");
        seedArtifact("artifact-dup-reject", AiAssistantService.STATE_PENDING, 1, "");
        authenticate("qa-user");

        service.review("artifact-dup-approve", "approve", 1, null);
        var twiceApproved = service.review("artifact-dup-approve", "approve", 1, null);
        assertThat(twiceApproved.acceptanceState()).isEqualTo(AiAssistantService.STATE_ACCEPTED);
        assertThat(eventCount("human_approved")).isEqualTo(1);

        service.review("artifact-dup-reject", "reject", 1, null);
        var twiceRejected = service.review("artifact-dup-reject", "reject", 1, null);
        assertThat(twiceRejected.acceptanceState()).isEqualTo(AiAssistantService.STATE_REJECTED);
        assertThat(eventCount("human_rejected")).isEqualTo(1);
    }

    @Test
    void stateFlipsAreRefusedAfterTerminalReview() {
        seedArtifact("artifact-flip", AiAssistantService.STATE_PENDING, 1, "");
        authenticate("qa-user");
        service.review("artifact-flip", "approve", 1, null);

        assertThatThrownBy(() -> service.review("artifact-flip", "reject", 1, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("artifact_not_pending");
        assertThatThrownBy(() -> service.review("artifact-flip", "change", 1, output(VALID_BUG_ANALYSIS_PAYLOAD)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("artifact_not_pending");
        assertThat(stateOf("artifact-flip")).isEqualTo(AiAssistantService.STATE_ACCEPTED);
    }

    @Test
    void concurrentChangeLeavesASingleRevisionChain() throws Exception {
        seedArtifact("artifact-change-race", AiAssistantService.STATE_PENDING, 1, "");
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var futures = java.util.stream.IntStream.range(0, 2).mapToObj(index -> pool.submit(() -> {
                authenticate("qa-user");
                try {
                    start.await();
                    return (Object) service.review("artifact-change-race", "change", 1,
                        output(VALID_BUG_ANALYSIS_PAYLOAD));
                } catch (RuntimeException failure) {
                    return (Object) failure;
                }
            })).toList();
            start.countDown();
            long successes = futures.stream()
                .map(future -> {
                    try {
                        return future.get();
                    } catch (Exception impossible) {
                        throw new AssertionError(impossible);
                    }
                })
                .filter(outcome -> !(outcome instanceof RuntimeException)).count();
            assertThat(successes).isEqualTo(1);
            Integer rows = jdbcTemplate.queryForObject(
                "select count(*) from ai_assistant_artifacts where artifact_id like 'artifact-change-race%'",
                Integer.class);
            assertThat(rows).isEqualTo(2); // 原版 + 恰好一个 r2
            assertThat(stateOf("artifact-change-race")).isEqualTo(AiAssistantService.STATE_REJECTED);
        } finally {
            pool.shutdownNow();
        }
    }


    // ---- 项 2：report_draft 确定性事实的可追溯与全路径复检 ----

    private static final String REPORT_FACTS = "{\"passRate\": 87.5, \"totalItems\": 8, \"sourceRunIds\": [\"run-1\"]}";
    private static final String REPORT_TAMPERED = "{\"passRate\": 12.0, \"sourceRunIds\": [\"run-1\"], \"approvalStatus\": \"draft\"}";
    private static final String REPORT_MATCHING = "{\"passRate\": 87.5, \"sourceRunIds\": [\"run-1\"], \"approvalStatus\": \"draft\"}";

    @Test
    void tamperedPassRateIsCaughtOnApproveChangeAndExport() {
        seedArtifactFull("artifact-tamper", AiAssistantService.STATE_PENDING, 1, "",
            REPORT_TAMPERED, REPORT_FACTS, "report_draft");
        authenticate("qa-user");

        assertThatThrownBy(() -> service.review("artifact-tamper", "approve", 1, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("pass rate");
        assertThat(stateOf("artifact-tamper")).isEqualTo(AiAssistantService.STATE_PENDING);

        var revised = service.review("artifact-tamper", "change", 1, output(REPORT_TAMPERED));
        assertThat(revised.violations()).anyMatch(violation -> violation.contains("pass rate"));
        assertThat(jdbcTemplate.queryForObject(
            "select deterministic_facts_json from ai_assistant_artifacts where artifact_id = ?",
            String.class, revised.artifactId())).contains("87.5");

        seedArtifactFull("artifact-tamper-accepted", AiAssistantService.STATE_ACCEPTED, 1, "legacy",
            REPORT_TAMPERED, REPORT_FACTS, "report_draft");
        assertThatThrownBy(() -> service.exportArtifact("artifact-tamper-accepted", 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("pass rate");
    }

    @Test
    void matchingPassRatePassesAndFactsTravelWithTheExport() {
        seedArtifactFull("artifact-facts-ok", AiAssistantService.STATE_PENDING, 1, "",
            REPORT_MATCHING, REPORT_FACTS, "report_draft");
        authenticate("qa-user");

        var approved = service.review("artifact-facts-ok", "approve", 1, null);
        assertThat(approved.acceptanceState()).isEqualTo(AiAssistantService.STATE_ACCEPTED);

        var exported = service.exportArtifact("artifact-facts-ok", 1);
        assertThat(String.valueOf(exported.get("deterministicFacts"))).contains("87.5");
    }

}
