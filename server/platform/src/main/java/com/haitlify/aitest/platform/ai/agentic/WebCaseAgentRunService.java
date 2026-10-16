package com.haitlify.aitest.platform.ai.agentic;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import com.haitlify.aitest.platform.ai.agentic.WebCaseAgentModels.WebCaseAgentRunResponse;
import com.haitlify.aitest.platform.ai.agentic.WebCaseAgentModels.WebCaseAgentRunStatus;
import com.haitlify.aitest.platform.ai.agentic.WebCaseAgentModels.WebCaseAgentStage;
import com.haitlify.aitest.platform.ai.agentic.rule.AgentRuleService;
import com.haitlify.aitest.platform.ai.draft.AgentWebCaseGenerationFacade;
import com.haitlify.aitest.platform.ai.runtime.artifact.AiArtifactAcceptanceService;
import com.haitlify.aitest.platform.ai.runtime.artifact.AiArtifactAcceptanceState;
import com.haitlify.aitest.platform.ai.runtime.artifact.AiArtifactGateAuditService;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional
public class WebCaseAgentRunService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AgentRuleService ruleService;
    private final WebCaseAgentEventService events;
    private final ObjectProvider<WebCaseAgentDispatcher> dispatcher;
    private final AiArtifactAcceptanceService acceptanceService;
    private final AiArtifactGateAuditService gateAuditService;

    @Autowired
    WebCaseAgentRunService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, AgentRuleService ruleService, WebCaseAgentEventService events, ObjectProvider<WebCaseAgentDispatcher> dispatcher) {
        this(jdbcTemplate, objectMapper, ruleService, events, dispatcher, new AiArtifactAcceptanceService(), new AiArtifactGateAuditService(jdbcTemplate));
    }

    WebCaseAgentRunService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, AgentRuleService ruleService, WebCaseAgentEventService events, ObjectProvider<WebCaseAgentDispatcher> dispatcher, AiArtifactAcceptanceService acceptanceService) {
        this(jdbcTemplate, objectMapper, ruleService, events, dispatcher, acceptanceService, new AiArtifactGateAuditService(jdbcTemplate));
    }

    WebCaseAgentRunService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, AgentRuleService ruleService, WebCaseAgentEventService events, ObjectProvider<WebCaseAgentDispatcher> dispatcher, AiArtifactAcceptanceService acceptanceService, AiArtifactGateAuditService gateAuditService) {
        this.jdbcTemplate = jdbcTemplate; this.objectMapper = objectMapper; this.ruleService = ruleService; this.events = events; this.dispatcher = dispatcher; this.acceptanceService = acceptanceService; this.gateAuditService = gateAuditService;
    }

    public WebCaseAgentRunResponse create(WebCaseAgentModels.WebCaseAgentStartRequest request) {
        if (request.projectId() == null || request.pointIds().isEmpty()) throw new IllegalArgumentException("项目和测试点不能为空");
        if (request.mode() == WebCaseAgentModels.WebCaseAgentMode.COMPILE && request.planRunId().isBlank()) {
            throw new IllegalArgumentException("自动化编译必须选择已评审的自然语言计划");
        }
        String runId = "web-agent-" + UUID.randomUUID(); Instant now = Instant.now();
        var snapshot = ruleService.snapshot(request.projectId());
        jdbcTemplate.update("insert into ai_web_case_agent_runs(run_id, project_id, analysis_run_id, status, current_stage, requested_point_ids_json, completed_point_ids_json, blocked_point_ids_json, rules_snapshot_json, input_summary_json, output_summary_json, last_heartbeat_at, cancel_requested, error_summary, mode, plan_run_id, created_at, updated_at, started_at, finished_at) values (?, ?, ?, 'queued', 'preflight', ?, '[]', '[]', ?, ?, '{}', ?, false, '', ?, ?, ?, ?, null, null)",
            runId, request.projectId(), request.analysisRunId() == null ? "" : request.analysisRunId(), write(request.pointIds()), write(snapshot), write(java.util.Map.of("pointCount", request.pointIds().size(), "environmentId", request.environmentId() == null ? "" : request.environmentId(), "baseUrl", request.baseUrl() == null ? "" : request.baseUrl(), "browser", request.browser() == null ? "" : request.browser(), "viewport", request.viewport() == null ? "" : request.viewport(), "authenticationMode", request.authenticationMode())), Timestamp.from(now), request.mode().name(), request.planRunId(), Timestamp.from(now), Timestamp.from(now));
        events.append(runId, WebCaseAgentStage.PREFLIGHT, com.haitlify.aitest.platform.ai.agentic.WebCaseAgentModels.WebCaseAgentEventType.STATUS, "info", "Agent 任务已创建", null, null, 0, snapshot.version(), null, null);
        dispatchAfterCommit(runId, request); return get(runId);
    }

    public WebCaseAgentRunResponse get(String runId) { return jdbcTemplate.queryForObject("select * from ai_web_case_agent_runs where run_id = ?", (rs, rowNum) -> map(rs), runId); }
    public List<WebCaseAgentModels.GeneratedCaseArtifact> artifacts(String runId) {
        return jdbcTemplate.query("select * from ai_web_case_agent_artifacts where run_id = ? order by created_at, id", (rs, rowNum) -> new WebCaseAgentModels.GeneratedCaseArtifact(
            rs.getString("artifact_id"), rs.getString("run_id"), rs.getString("point_id"), rs.getString("artifact_type"),
            readJson(rs.getString("candidate_json")), read(rs.getString("evidence_ids_json")), read(rs.getString("validation_errors_json")),
            rs.getBoolean("accepted"), rs.getInt("repair_round"), rs.getString("result_origin"), rs.getString("acceptance_state"), rs.getInt("revision"), rs.getString("parent_artifact_id"), rs.getString("idempotency_key"), readModelProfileVersion(rs.getString("candidate_json")), readFieldOrigins(rs.getString("candidate_json")),
            rs.getTimestamp("created_at").toInstant().toString(), rs.getTimestamp("updated_at").toInstant().toString()
        ), runId);
    }
    public List<WebCaseAgentModels.GeneratedCaseArtifact> acceptedArtifacts(String runId) {
        return artifacts(runId).stream()
            .filter(WebCaseAgentModels.GeneratedCaseArtifact::accepted)
            .filter(artifact -> "ACCEPTED".equals(artifact.acceptanceState()))
            .toList();
    }
    public WebCaseAgentModels.GeneratedCaseArtifact acceptedArtifactRevision(String runId, String artifactId, int revision) {
        return acceptedArtifacts(runId).stream()
            .filter(artifact -> artifactId.equals(artifact.artifactId()) && revision == artifact.revision())
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("artifact revision is not accepted for export"));
    }
    WebCaseAgentModels.WebCaseAgentArtifactRevisionResponse revision(String runId, String artifactId) {
        return jdbcTemplate.queryForObject("select artifact_id, run_id, point_id, revision, parent_artifact_id, idempotency_key, result_origin, acceptance_state, candidate_json from ai_web_case_agent_artifacts where run_id = ? and artifact_id = ?", (rs, rowNum) -> new WebCaseAgentModels.WebCaseAgentArtifactRevisionResponse(rs.getString("artifact_id"), rs.getString("run_id"), rs.getString("point_id"), rs.getInt("revision"), rs.getString("parent_artifact_id"), rs.getString("idempotency_key"), rs.getString("result_origin"), rs.getString("acceptance_state"), readModelProfileVersion(rs.getString("candidate_json")), readFieldOrigins(rs.getString("candidate_json"))), runId, artifactId);
    }


    int ruleVersion(String runId) { return get(runId).ruleVersion(); }

    Long projectId(String runId) {
        try { Long projectId = jdbcTemplate.queryForObject("select project_id from ai_web_case_agent_runs where run_id = ?", Long.class, runId); return projectId == null ? 0L : projectId; }
        catch (Exception ignored) { return 0L; }
    }

    WebCaseAgentModels.AgentRuleSnapshot ruleSnapshot(String runId) {
        String json = jdbcTemplate.queryForObject("select rules_snapshot_json from ai_web_case_agent_runs where run_id = ?", String.class, runId);
        try { return objectMapper.readValue(json, WebCaseAgentModels.AgentRuleSnapshot.class); }
        catch (Exception exception) { throw new IllegalStateException("Agent 规则快照损坏", exception); }
    }
    List<String> requestedPointIds(String runId) { return get(runId).requestedPointIds(); }
    List<String> completedPointIds(String runId) { return get(runId).completedPointIds(); }
    WebCaseAgentModels.WebCaseAgentMode mode(String runId) { return get(runId).mode(); }
    String planRunId(String runId) { return get(runId).planRunId(); }
    void markRunning(String runId) { jdbcTemplate.update("update ai_web_case_agent_runs set status = 'running', started_at = coalesce(started_at, ?), last_heartbeat_at = ?, updated_at = ? where run_id = ? and status = 'queued'", Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), runId); }
    boolean preflightBlocked(String runId) {
        WebCaseAgentRunResponse run = get(runId);
        if (run.requestedPointIds().isEmpty()) {
            fail(runId, "没有可生成的测试点");
            return true;
        }
        // T10 bullet 1：预检拦截模板选择与 caseKind 冲突——未知测试点、缺 caseKind、
        // 或项目已配置模板但该 kind 不在模板里（且非内建 kind）时，逐点阻塞并终止本次运行。
        var conflicts = new java.util.ArrayList<String>();
        var templateKinds = jdbcTemplate.queryForList(
            "select distinct kind from case_template_configs where project_id = ?", String.class, run.projectId());
        for (String pointId : run.requestedPointIds()) {
            var rows = jdbcTemplate.queryForList(
                "select case_kind from ai_draft_test_points where project_id = ? and point_id = ?",
                run.projectId(), pointId);
            if (rows.isEmpty()) {
                conflicts.add(pointId + ":unknown_point");
                continue;
            }
            Object kindValue = rows.get(0).get("case_kind");
            String caseKind = kindValue == null ? "" : kindValue.toString().trim();
            if (caseKind.isBlank()) {
                conflicts.add(pointId + ":missing_case_kind");
                continue;
            }
            if (!templateKinds.isEmpty() && !templateKinds.contains(caseKind)
                    && !BUILT_IN_CASE_KINDS.contains(caseKind)) {
                conflicts.add(pointId + ":template_kind_conflict:" + caseKind);
            }
        }
        if (!conflicts.isEmpty()) {
            for (String conflict : conflicts) {
                markPointBlocked(runId, conflict.split(":", 2)[0]);
            }
            fail(runId, "预检拦截模板/caseKind 冲突: " + String.join(", ", conflicts));
            return true;
        }
        return false;
    }

    private static final java.util.Set<String> BUILT_IN_CASE_KINDS =
        java.util.Set.of("functional", "web_automation", "interface", "api");
    void markStage(String runId, WebCaseAgentStage stage) { jdbcTemplate.update("update ai_web_case_agent_runs set current_stage = ?, last_heartbeat_at = ?, updated_at = ? where run_id = ?", stage.name().toLowerCase(), Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), runId); }
    void requestCancel(String runId) { jdbcTemplate.update("update ai_web_case_agent_runs set cancel_requested = true, status = 'cancelled', error_summary = '用户取消', finished_at = ?, updated_at = ? where run_id = ? and status not in ('succeeded','failed','cancelled')", Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), runId); }
    boolean isCancelled(String runId) { return Boolean.TRUE.equals(jdbcTemplate.queryForObject("select cancel_requested from ai_web_case_agent_runs where run_id = ?", Boolean.class, runId)); }
    void ensureNotCancelled(String runId) { if (isCancelled(runId)) throw new IllegalStateException("Agent 已被用户终止"); }
    void fail(String runId, String message) { jdbcTemplate.update("update ai_web_case_agent_runs set status = 'failed', error_summary = ?, finished_at = ?, updated_at = ? where run_id = ? and status <> 'cancelled'", message, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), runId); }

    /**
     * T10 bullet 4：部分成功——批次里既有成功也有 blocked/failed 时，运行汇总为 partial，
     * 绝不把 generatedCount&gt;0 当作整批成功；成功数与阻塞数分别落库。
     */
    void partial(String runId, int generatedCount, int blockedCount) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
            update ai_web_case_agent_runs
            set status = 'partial', completed_point_ids_json = ?, output_summary_json = ?, error_summary = ?, finished_at = ?,
                last_heartbeat_at = ?, updated_at = ?
            where run_id = ? and cancel_requested = false
            """,
            write(artifactPointIds(runId)),
            write(java.util.Map.of(
                "status", "partial",
                "generatedCaseCount", Math.max(0, generatedCount),
                "blockedPointCount", Math.max(0, blockedCount),
                "pendingHumanConfirmationCount", Math.max(0, generatedCount))),
            "部分测试点未产出：成功 " + Math.max(0, generatedCount) + " 条，阻塞 " + Math.max(0, blockedCount) + " 个测试点",
            Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), runId);
    }
    void succeed(String runId, int generatedCount) {
        Instant now = Instant.now();
        int acceptedCount = artifactCount(runId);
        String status = acceptedCount > 0 ? "succeeded" : "blocked";
        String error = acceptedCount > 0 ? "" : "等待人工确认生成 artifact";
        jdbcTemplate.update("update ai_web_case_agent_runs set status = ?, completed_point_ids_json = ?, output_summary_json = ?, error_summary = ?, finished_at = ?, last_heartbeat_at = ?, updated_at = ? where run_id = ? and cancel_requested = false", status, write(artifactPointIds(runId)), write(java.util.Map.of("generatedCaseCount", generatedCount, "pendingHumanConfirmationCount", Math.max(0, generatedCount - acceptedCount))), error, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), runId);
    }
    void writeOutputSummary(String runId, tools.jackson.databind.JsonNode summary) {
        jdbcTemplate.update("update ai_web_case_agent_runs set output_summary_json = ?, updated_at = ? where run_id = ?", write(summary == null ? java.util.Map.of() : summary), Timestamp.from(Instant.now()), runId);
    }
    void markPointCompleted(String runId, String pointId) {
        List<String> completed = new java.util.ArrayList<>(get(runId).completedPointIds());
        if (!completed.contains(pointId)) completed.add(pointId);
        jdbcTemplate.update("update ai_web_case_agent_runs set completed_point_ids_json = ?, last_heartbeat_at = ?, updated_at = ? where run_id = ?", write(completed), Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), runId);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void markPointBlocked(String runId, String pointId) {
        List<String> blocked = new java.util.ArrayList<>(get(runId).blockedPointIds());
        if (!blocked.contains(pointId)) blocked.add(pointId);
        // 单点阻塞不能提前把整个批次置为 blocked：调度器还要继续处理剩余点，并写入 GENERATION/DELIVERY 错误事件。
        // 批次最终状态由 partial()/block() 在 DELIVERY 统一聚合。
        jdbcTemplate.update("update ai_web_case_agent_runs set blocked_point_ids_json = ?, last_heartbeat_at = ?, updated_at = ? where run_id = ? and status <> 'cancelled'", write(blocked), Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), runId);
    }
    void saveNaturalPlanArtifacts(String runId, List<AgentWebCaseGenerationFacade.NaturalWebCasePlan> plans, String providerRequestId) {
        Instant now = Instant.now();
        boolean runtimeEvidence = providerRequestId != null && !providerRequestId.isBlank();
        String resultOrigin = runtimeEvidence ? "MODEL_GENERATED" : "BLOCKED";
        String acceptanceState = runtimeEvidence ? "PENDING_HUMAN_CONFIRMATION" : "BLOCKED";
        List<String> fieldOrigins = runtimeEvidence ? List.of("MODEL_PROPOSED") : List.of();
        for (AgentWebCaseGenerationFacade.NaturalWebCasePlan plan : plans) {
            String artifactId = "artifact-" + UUID.randomUUID();
            var candidate = objectMapper.createObjectNode()
                .put("sourcePointId", plan.sourcePointId()).put("title", plan.title()).put("module", plan.module())
                .put("pageName", plan.pageName()).put("startUrl", plan.startUrl()).put("objective", plan.objective())
                .put("naturalSteps", String.join("\\n", plan.naturalSteps()))
                .put("naturalExpectedResult", plan.naturalExpectedResult()).put("environmentId", plan.environmentId())
                .put("baseUrl", plan.baseUrl()).put("browser", plan.browser()).put("viewport", plan.viewport())
                .put("authenticationMode", plan.authenticationMode())
                .put("modelProfileVersion", "")
                .put("resultOrigin", resultOrigin)
                .set("fieldOrigins", objectMapper.valueToTree(fieldOrigins))
                .put("acceptanceState", acceptanceState)
                .put("providerRequestId", providerRequestId == null ? "" : providerRequestId)
                .put("revision", 1)
                .put("idempotencyKey", artifactId);
            if (plan.actions() != null) candidate.set("actions", plan.actions());
            jdbcTemplate.update("insert into ai_web_case_agent_artifacts(artifact_id, run_id, point_id, artifact_type, candidate_json, evidence_ids_json, validation_errors_json, accepted, repair_round, revision, parent_artifact_id, idempotency_key, result_origin, acceptance_state, created_at, updated_at) values (?, ?, ?, 'natural_web_case_plan', ?, '[]', '[]', false, 0, 1, '', ?, ?, ?, ?, ?)", artifactId, runId, plan.sourcePointId(), write(candidate), artifactId, resultOrigin, acceptanceState, Timestamp.from(now), Timestamp.from(now));
            gateAuditService.record(new AiArtifactGateAuditService.GateRecord(artifactId, runId, projectId(runId), resultOrigin, acceptanceState, 1, "", artifactId, List.of(), List.of(), com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystem()));
        }
    }
    AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch confirmedPlanBatch(String planRunId) {
        List<AgentWebCaseGenerationFacade.NaturalWebCasePlan> plans = confirmedPlans(planRunId);
        if (plans.isEmpty()) throw new IllegalArgumentException("计划 run 尚未确认任何自然语言用例");
        var cases = objectMapper.createArrayNode();
        for (var plan : plans) {
            var item = objectMapper.createObjectNode()
                .put("sourcePointId", plan.sourcePointId()).put("title", plan.title()).put("module", plan.module())
                .put("pageName", plan.pageName()).put("startUrl", plan.startUrl()).put("objective", plan.objective())
                .put("naturalExpectedResult", plan.naturalExpectedResult()).put("environmentId", plan.environmentId())
                .put("baseUrl", plan.baseUrl()).put("browser", plan.browser()).put("viewport", plan.viewport())
                .put("authenticationMode", plan.authenticationMode());
            var steps = objectMapper.createArrayNode();
            plan.naturalSteps().forEach(steps::add);
            item.set("naturalSteps", steps);
            if (plan.actions() != null) item.set("actions", plan.actions());
            cases.add(item);
        }
        return new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(plans, objectMapper.createObjectNode().set("cases", cases));
    }
    void completePlan(String runId, List<AgentWebCaseGenerationFacade.NaturalWebCasePlan> plans) {
        Instant now = Instant.now();
        jdbcTemplate.update("update ai_web_case_agent_runs set status = 'blocked', completed_point_ids_json = '[]', output_summary_json = ?, error_summary = '等待人工确认自然语言计划', finished_at = ?, last_heartbeat_at = ?, updated_at = ? where run_id = ? and cancel_requested = false", write(java.util.Map.of("naturalPlanCount", plans.size(), "pendingHumanConfirmationCount", plans.size())), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), runId);
    }
    List<AgentWebCaseGenerationFacade.NaturalWebCasePlan> confirmedPlans(String planRunId) {
        return jdbcTemplate.query("select candidate_json from ai_web_case_agent_artifacts where run_id = ? and artifact_type = 'natural_web_case_plan' and accepted = true and acceptance_state = 'ACCEPTED' order by created_at, id", (rs, rowNum) -> {
            try {
                var node = objectMapper.readTree(rs.getString("candidate_json"));
                return new AgentWebCaseGenerationFacade.NaturalWebCasePlan(
                    node.path("sourcePointId").asText(), node.path("title").asText(), node.path("module").asText(),
                    node.path("pageName").asText(), node.path("startUrl").asText(), readNaturalSteps(node.path("naturalSteps")),
                    node.path("naturalExpectedResult").asText(), node.path("objective").asText(), node.path("actions").isArray() ? node.path("actions") : null,
                    node.path("environmentId").asText(), node.path("baseUrl").asText(), node.path("browser").asText(),
                    node.path("viewport").asText(), node.path("authenticationMode").asText()
                );
            } catch (Exception exception) { throw new IllegalStateException("自然语言用例计划损坏", exception); }
        }, planRunId);
    }
    void confirmPlan(String artifactId, boolean accepted) {
        confirmPlan(artifactId, accepted, null);
    }

    /** T10/T11 gate：带预期的确认——过期 revision 返回冲突，绝不覆盖。 */
    void confirmPlan(String artifactId, boolean accepted, Integer expectedRevision) {
        requireCurrentRevision(artifactId, expectedRevision);
        String currentState = jdbcTemplate.queryForObject("select acceptance_state from ai_web_case_agent_artifacts where artifact_id = ? and artifact_type = 'natural_web_case_plan'", String.class, artifactId);
        if (currentState == null) throw new IllegalArgumentException("自然语言用例计划不存在或类型不正确");
        // 幂等：重复确认/驳回在此直接返回，不重复写审计；非法翻转（已接受 → 驳回）显式拒绝。
        if (accepted && "ACCEPTED".equals(currentState)) return;
        if (!accepted && "BLOCKED".equals(currentState)) return;
        if (!accepted && "ACCEPTED".equals(currentState)) {
            throw new IllegalArgumentException("review_state_conflict: 已接受的计划不可驳回");
        }
        acceptanceService.transition(AiArtifactAcceptanceState.valueOf(currentState),
            accepted ? AiArtifactAcceptanceState.ACCEPTED : AiArtifactAcceptanceState.BLOCKED);
        int changed = jdbcTemplate.update("update ai_web_case_agent_artifacts set accepted = ?, acceptance_state = ?, result_origin = ?, updated_at = ? where artifact_id = ? and artifact_type = 'natural_web_case_plan'", accepted, accepted ? "ACCEPTED" : "BLOCKED", accepted ? "HUMAN_EDITED" : "BLOCKED", Timestamp.from(Instant.now()), artifactId);
        if (changed == 0) throw new IllegalArgumentException("自然语言用例计划不存在或类型不正确");
        if (accepted) {
            gateAuditService.record(new AiArtifactGateAuditService.GateRecord(
                artifactId, runIdForArtifact(artifactId), projectIdForArtifact(artifactId), "HUMAN_EDITED", "ACCEPTED", revisionForArtifact(artifactId), parentForArtifact(artifactId), idempotencyForArtifact(artifactId), List.of(), List.of(), currentHumanActor()));
        }
    }
    void confirmAllPlans(String planRunId) {
        List<PlanGateIdentity> pending = jdbcTemplate.query(
            "select artifact_id, run_id, revision, parent_artifact_id, idempotency_key, evidence_ids_json from ai_web_case_agent_artifacts where run_id = ? and artifact_type = 'natural_web_case_plan' and acceptance_state = 'PENDING_HUMAN_CONFIRMATION'",
            (rs, rowNum) -> new PlanGateIdentity(
                rs.getString("artifact_id"), rs.getString("run_id"), rs.getInt("revision"),
                rs.getString("parent_artifact_id"), rs.getString("idempotency_key"), read(rs.getString("evidence_ids_json"))),
            planRunId);
        if (pending.isEmpty()) {
            Integer total = jdbcTemplate.queryForObject(
                "select count(*) from ai_web_case_agent_artifacts where run_id = ? and artifact_type = 'natural_web_case_plan'",
                Integer.class, planRunId);
            Integer notAccepted = jdbcTemplate.queryForObject(
                "select count(*) from ai_web_case_agent_artifacts where run_id = ? and artifact_type = 'natural_web_case_plan' and acceptance_state <> 'ACCEPTED'",
                Integer.class, planRunId);
            if (total != null && total > 0 && (notAccepted == null || notAccepted == 0)) {
                return; // 全部已接受：重复全量确认幂等
            }
            throw new IllegalArgumentException("计划 run 尚未生成待确认的自然语言用例");
        }
        int changed = jdbcTemplate.update("update ai_web_case_agent_artifacts set accepted = true, acceptance_state = 'ACCEPTED', result_origin = 'HUMAN_EDITED', updated_at = ? where run_id = ? and artifact_type = 'natural_web_case_plan' and acceptance_state = 'PENDING_HUMAN_CONFIRMATION'", Timestamp.from(Instant.now()), planRunId);
        if (changed != pending.size()) throw new IllegalStateException("确认计划数量与 Gate 审计数量不一致");
        for (PlanGateIdentity plan : pending) {
            gateAuditService.record(new AiArtifactGateAuditService.GateRecord(
                plan.artifactId(), plan.runId(), projectIdForArtifact(plan.artifactId()), "HUMAN_EDITED", "ACCEPTED",
                plan.revision(), plan.parentArtifactId(), plan.idempotencyKey(), plan.evidenceIds(), List.of(), currentHumanActor()));
        }
    }

    /**
     * 人工确认/拒绝必须落到真实操作人：Gate 审计的 actor 取自认证上下文，禁止写成固定系统账号，
     * 否则「谁确认了这个交付物」无法审计。没有已认证操作人时 fail-closed，不写人类决策记录。
     */
    private String currentHumanActor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
            || authentication instanceof AnonymousAuthenticationToken) {
            throw new IllegalStateException("人工确认必须来自已认证的操作人");
        }
        String actor = authentication.getName() == null ? "" : authentication.getName().trim();
        if (actor.isEmpty() || "anonymousUser".equals(actor)) {
            throw new IllegalStateException("人工确认必须来自已认证的操作人");
        }
        return actor;
    }

    private record PlanGateIdentity(String artifactId, String runId, int revision, String parentArtifactId, String idempotencyKey, List<String> evidenceIds) {}
    void updatePlan(String artifactId, tools.jackson.databind.JsonNode candidate) {
        updatePlan(artifactId, candidate, null);
    }

    /** T10/T11 gate：带预期的修改——过期 revision 返回冲突，绝不覆盖。 */
    void updatePlan(String artifactId, tools.jackson.databind.JsonNode candidate, Integer expectedRevision) {
        requireCurrentRevision(artifactId, expectedRevision);
        String currentState = jdbcTemplate.queryForObject("select acceptance_state from ai_web_case_agent_artifacts where artifact_id = ? and artifact_type = 'natural_web_case_plan'", String.class, artifactId);
        if (currentState == null) throw new IllegalArgumentException("自然语言用例计划不存在或类型不正确");
        if ("ACCEPTED".equals(currentState) || "EXECUTED".equals(currentState)) {
            throw new IllegalArgumentException("review_state_conflict: 已接受/已执行的计划不可再修改，请基于新 revision 重新生成");
        }
        String existingJson = jdbcTemplate.queryForObject("select candidate_json from ai_web_case_agent_artifacts where artifact_id = ? and artifact_type = 'natural_web_case_plan'", String.class, artifactId);
        tools.jackson.databind.JsonNode merged = mergePlanCandidate(existingJson, candidate, artifactId);
        int changed = jdbcTemplate.update("update ai_web_case_agent_artifacts set candidate_json = ?, accepted = false, acceptance_state = 'PENDING_HUMAN_CONFIRMATION', result_origin = 'HUMAN_EDITED', revision = ?, parent_artifact_id = ?, idempotency_key = ?, updated_at = ? where artifact_id = ? and artifact_type = 'natural_web_case_plan'", write(merged), merged.path("revision").asInt(1), merged.path("parentArtifactId").asText(artifactId), merged.path("idempotencyKey").asText(artifactId), Timestamp.from(Instant.now()), artifactId);
        if (changed == 0) throw new IllegalArgumentException("自然语言用例计划不存在或类型不正确");
    }

    private void requireCurrentRevision(String artifactId, Integer expectedRevision) {
        if (expectedRevision == null) {
            return;
        }
        int current = revisionForArtifact(artifactId);
        if (current != expectedRevision) {
            throw new StaleArtifactRevisionException(
                "artifact revision is stale: expected " + expectedRevision + " but current is " + current);
        }
    }

    private tools.jackson.databind.JsonNode mergePlanCandidate(String existingJson, tools.jackson.databind.JsonNode candidate, String artifactId) {
        try {
            tools.jackson.databind.JsonNode existing = objectMapper.readTree(existingJson == null ? "{}" : existingJson);
            if (!(existing instanceof tools.jackson.databind.node.ObjectNode existingObject)
                || !(candidate instanceof tools.jackson.databind.node.ObjectNode candidateObject)) {
                throw new IllegalArgumentException("自然语言用例计划必须是 JSON 对象");
            }
            existingObject.setAll(candidateObject);
            int currentRevision = existingObject.path("revision").asInt(1);
            int nextRevision = currentRevision + 1;
            String idempotencyKey = existingObject.path("idempotencyKey").asText(artifactId);
            existingObject.put("revision", nextRevision)
                .put("parentArtifactId", artifactId)
                .put("idempotencyKey", idempotencyKey);
            return existingObject;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("自然语言用例计划不是合法 JSON", exception);
        }
    }
    void saveArtifacts(String runId, List<AgentWebCaseGenerationFacade.GeneratedWebCase> generated, List<String> evidenceIds) {
        Instant now = Instant.now();
        // Compiled cases are produced by the deterministic compiler from a human-confirmed plan plus
        // browser-observed evidence: that is rule extraction over tool observation, never a model claim.
        List<String> artifactEvidence = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        String resultOrigin = "RULE_EXTRACTED";
        List<String> fieldOrigins = artifactEvidence.isEmpty()
            ? List.of("RULE_COMPUTED")
            : List.of("RULE_COMPUTED", "TOOL_OBSERVED");
        for (AgentWebCaseGenerationFacade.GeneratedWebCase testCase : generated) {
            String artifactId = "artifact-" + UUID.randomUUID();
            var candidate = objectMapper.createObjectNode()
                .put("resultOrigin", resultOrigin)
                .put("acceptanceState", "PENDING_HUMAN_CONFIRMATION")
                .put("revision", 1)
                .put("idempotencyKey", artifactId).put("sourcePointId", testCase.sourcePointId()).put("title", testCase.title()).put("module", testCase.module())
                .put("caseKind", testCase.caseKind()).put("pageName", testCase.pageName()).put("startUrl", testCase.startUrl())
                .put("browser", testCase.browser()).put("viewport", testCase.viewport()).put("precondition", testCase.precondition())
                .put("objective", testCase.objective()).put("steps", testCase.steps()).put("assertions", testCase.assertions())
                .put("expectedResult", testCase.expectedResult()).put("naturalSteps", testCase.naturalSteps())
                .put("naturalExpectedResult", testCase.naturalExpectedResult()).put("sourceFile", testCase.sourceFile())
                .put("pageFingerprint", testCase.pageFingerprint()).put("pageKey", testCase.pageKey())
                .put("environmentId", testCase.environmentId()).put("baseUrl", testCase.baseUrl())
                .put("authenticationMode", testCase.authenticationMode())
                .put("modelProfileVersion", "")
                .set("fieldOrigins", objectMapper.valueToTree(fieldOrigins));
            candidate.set("stepTraces", objectMapper.valueToTree(testCase.stepTraces()));
            candidate.set("expectedResultEvidenceIds", objectMapper.valueToTree(testCase.expectedResultEvidenceIds()));
            if (testCase.actions() != null) candidate.set("actions", testCase.actions());
            // 证据闭环:每个已交付用例绑定其生成时命中的页面证据(locator 素材 entry_id),使「用例 → 页面证据」可追溯。
            jdbcTemplate.update("insert into ai_web_case_agent_artifacts(artifact_id, run_id, point_id, artifact_type, candidate_json, evidence_ids_json, validation_errors_json, accepted, repair_round, revision, parent_artifact_id, idempotency_key, result_origin, acceptance_state, created_at, updated_at) values (?, ?, ?, 'web_case', ?, ?, '[]', false, 0, 1, '', ?, ?, 'PENDING_HUMAN_CONFIRMATION', ?, ?)", artifactId, runId, testCase.sourcePointId(), write(candidate), write(artifactEvidence), artifactId, resultOrigin, Timestamp.from(now), Timestamp.from(now));
            gateAuditService.record(new AiArtifactGateAuditService.GateRecord(
                artifactId, runId, projectId(runId), resultOrigin, "PENDING_HUMAN_CONFIRMATION", 1, "", artifactId,
                artifactEvidence, List.of(), com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystem()));

        }
    }
    public WebCaseAgentRunResponse retry(String runId) { jdbcTemplate.update("update ai_web_case_agent_runs set status = 'queued', cancel_requested = false, error_summary = '', updated_at = ? where run_id = ? and status in ('failed','blocked','cancelled')", Timestamp.from(Instant.now()), runId); dispatchAfterCommit(runId, null); return get(runId); }
    public WebCaseAgentRunResponse resume(String runId) { return retry(runId); }

    private WebCaseAgentRunResponse map(java.sql.ResultSet rs) throws java.sql.SQLException {
        String snapshot = rs.getString("rules_snapshot_json");
        int ruleVersion;
        try { ruleVersion = objectMapper.readTree(snapshot).path("version").asInt(1); } catch (Exception ignored) { ruleVersion = 1; }
        return new WebCaseAgentRunResponse(rs.getString("run_id"), rs.getLong("project_id"), rs.getString("analysis_run_id"), WebCaseAgentRunStatus.valueOf(rs.getString("status").toUpperCase()), WebCaseAgentStage.valueOf(rs.getString("current_stage").toUpperCase()), read(rs.getString("requested_point_ids_json")), read(rs.getString("completed_point_ids_json")), read(rs.getString("blocked_point_ids_json")), ruleVersion, rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant().toString(), rs.getTimestamp("updated_at").toInstant().toString(), rs.getString("error_summary"), WebCaseAgentModels.WebCaseAgentMode.valueOf(rs.getString("mode").toUpperCase()), rs.getString("plan_run_id"));
    }
    private String write(Object value) { try { return objectMapper.writeValueAsString(value); } catch (Exception exception) { throw new IllegalStateException(exception); } }
    private List<String> read(String value) { try { return objectMapper.readValue(value == null ? "[]" : value, new TypeReference<>() {}); } catch (Exception exception) { return List.of(); } }
    private String readModelProfileVersion(String value) { return readJson(value).path("modelProfileVersion").asText(""); }
    private List<String> readFieldOrigins(String value) { return read(readJson(value).path("fieldOrigins").toString()); }
    private String runIdForArtifact(String artifactId) { return jdbcTemplate.queryForObject("select run_id from ai_web_case_agent_artifacts where artifact_id = ?", String.class, artifactId); }
    private Long projectIdForArtifact(String artifactId) { return jdbcTemplate.queryForObject("select r.project_id from ai_web_case_agent_artifacts a join ai_web_case_agent_runs r on r.run_id = a.run_id where a.artifact_id = ?", Long.class, artifactId); }
    private int revisionForArtifact(String artifactId) { Integer value = jdbcTemplate.queryForObject("select revision from ai_web_case_agent_artifacts where artifact_id = ?", Integer.class, artifactId); return value == null ? 0 : value; }
    private String parentForArtifact(String artifactId) { return jdbcTemplate.queryForObject("select parent_artifact_id from ai_web_case_agent_artifacts where artifact_id = ?", String.class, artifactId); }
    private String idempotencyForArtifact(String artifactId) { return jdbcTemplate.queryForObject("select idempotency_key from ai_web_case_agent_artifacts where artifact_id = ?", String.class, artifactId); }
    private List<String> readNaturalSteps(tools.jackson.databind.JsonNode value) {
        if (value == null || value.isNull()) return List.of();
        if (value.isArray()) { List<String> values = new java.util.ArrayList<>(); value.forEach(item -> { if (!item.asText("").isBlank()) values.add(item.asText()); }); return List.copyOf(values); }
        return java.util.Arrays.stream(value.asText("").split("\\R")).map(String::strip).filter(item -> !item.isBlank()).toList();
    }
    private tools.jackson.databind.JsonNode readJson(String value) { try { return objectMapper.readTree(value); } catch (Exception exception) { return objectMapper.createObjectNode(); } }
    private List<String> artifactPointIds(String runId) { return jdbcTemplate.queryForList("select distinct point_id from ai_web_case_agent_artifacts where run_id = ? and accepted = true and acceptance_state = 'ACCEPTED' order by point_id", String.class, runId); }
    int artifactCount(String runId) { Integer count = jdbcTemplate.queryForObject("select count(*) from ai_web_case_agent_artifacts where run_id = ? and accepted = true and acceptance_state = 'ACCEPTED'", Integer.class, runId); return count == null ? 0 : count; }
    void block(String runId, String message) { jdbcTemplate.update("update ai_web_case_agent_runs set status = 'blocked', error_summary = ?, finished_at = ?, updated_at = ? where run_id = ? and status <> 'cancelled'", message, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), runId); }
    private void dispatchAfterCommit(String runId, WebCaseAgentModels.WebCaseAgentStartRequest request) {
        // afterCommit and the agent executor both run outside the request
        // thread; capture the authenticated actor before either boundary.
        SecurityContext actorContext = SecurityContextHolder.createEmptyContext();
        actorContext.setAuthentication(SecurityContextHolder.getContext().getAuthentication());
        Runnable task = () -> {
            dispatcher.getObject().dispatchWithContext(runId, request, actorContext);
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            task.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { task.run(); }
        });
    }
}
