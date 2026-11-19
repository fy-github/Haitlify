package com.haitlify.aitest.platform.assistant.dispatch;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.haitlify.aitest.platform.assistant.compile.AssistantScriptCompiler;
import com.haitlify.aitest.platform.assistant.draft.AssistantDraftService;
import com.haitlify.aitest.platform.assistant.explore.AssistantExplorationService;
import com.haitlify.aitest.platform.assistant.ledger.AssistantAdmissionService;
import com.haitlify.aitest.platform.assistant.ledger.AssistantLedgerRepository;
import com.haitlify.aitest.platform.assistant.report.AssistantReportService;
import com.haitlify.aitest.platform.assistant.security.AssistantActorResolver;
import com.haitlify.aitest.platform.assistant.waiting.AssistantWaitingUserService;
import com.haitlify.aitest.platform.auth.ProjectAccessService;
import com.haitlify.aitest.platform.tenant.TenantAccessService;

/**
 * A1 链路的 HTTP 入口：草稿 → 确认 → 执行 → 报告。
 *
 * <p><b>没有「直接派发任意 payload」端点</b>：执行只能走 {@code POST /tasks}，由服务端用
 * 「已确认草稿 + 确定性编译」产生脚本。这样确认门、白名单与 digest 绑定才不可能被绕过。
 *
 * <p>这一层刻意做薄：所有判定（幂等、冻结、终态）都在 {@link AssistantAdmissionService} /
 * {@link AssistantDispatchService} 里，控制器只做参数与状态码映射。
 */
@RestController
@RequestMapping("/api/assistant")
public class AssistantChainController {

    public record AdmissionRequest(long tenantId, long actorId, Long projectId, String requestId, String inputDigest,
                                   String actionCode, String targetRef, String targetRevision, String sessionBinding) {}

    public record TaskRequestBody(long tenantId, long actorId, Long projectId, String requestId, String draftId,
                                  String actionCode, String targetRef, String targetRevision,
                                  AssistantScriptCompiler.CaseSpec spec, Map<String, String> inputs,
                                  Long waitSeconds) {}

    public record DraftRequestBody(long tenantId, Long projectId, long createdBy, String requirementRef,
                                   String requirementText, AssistantScriptCompiler.CaseSpec spec,
                                   String actionCode, String targetRef, String targetRevision,
                                   Map<String, String> inputs) {}

    public record ConfirmBody(long tenantId, long actorId) {}

    public record ReviseBody(long tenantId, Long actorId,
                             com.haitlify.aitest.platform.assistant.compile.AssistantScriptCompiler.CaseSpec spec,
                             Map<String, String> inputs) {}

    public record ExplorationBody(String url, Long projectId, Long actorId) {}

    public record ResourceCleanBody(String evidence, Long actorId) {}

    public record ResourceUnrecoverableBody(String reason, Long actorId) {}

    public record ResolveBody(String outcome, String reason, Long actorId) {}

    public record StopBody(String reason, Long actorId) {}
    public record WaitingBody(String reason, Long timeoutMinutes) {}
    public record WaitingCancelBody(String reason) {}
    public record ExpireBody(Long actorId) {}

    public record ReportDecision(boolean accepted, String reportId) {}

    public record ReportGenerateBody(Long generatedBy) {}

    public record OperationView(String operationId, String executionState, String reportState, int dispatchGeneration,
                                List<AssistantLedgerRepository.AttemptRecord> attempts,
                                List<AssistantLedgerRepository.ResourceRecord> resources) {}

    private final AssistantAdmissionService admission;
    private final AssistantDispatchService dispatch;
    private final AssistantLedgerRepository repository;
    private final AssistantTaskService tasks;
    private final AssistantDraftService drafts;
    private final AssistantExplorationService exploration;
    private final AssistantReportService reports;
    private final ProjectAccessService projectAccess;
    private final TenantAccessService tenants;
    private final AssistantActorResolver actors;
    private final AssistantWaitingUserService waiting;

    public AssistantChainController(AssistantAdmissionService admission, AssistantDispatchService dispatch,
                                    AssistantLedgerRepository repository, AssistantTaskService tasks,
                                    AssistantDraftService drafts, AssistantExplorationService exploration,
                                    AssistantReportService reports, ProjectAccessService projectAccess,
                                    AssistantActorResolver actors, AssistantWaitingUserService waiting,
                                    TenantAccessService tenants) {
        this.admission = admission;
        this.dispatch = dispatch;
        this.repository = repository;
        this.tasks = tasks;
        this.drafts = drafts;
        this.exploration = exploration;
        this.reports = reports;
        this.projectAccess = projectAccess;
        this.tenants = tenants;
        this.actors = actors;
        this.waiting = waiting;
    }

    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.CREATED)
    public AssistantAdmissionService.AdmissionResult admit(@RequestBody AdmissionRequest request) {
        if (request.projectId() == null) {
            throw new IllegalArgumentException("projectId 必填：受理必须绑定项目");
        }
        // 租户必须是真实存在且当前用户可用的租户；项目已绑定租户时两者必须一致。
        tenants.requireTenantProject(request.tenantId(), request.projectId());
        Long actor = actors.resolveActor(request.actorId());
        return admission.admit(new AssistantAdmissionService.AdmissionCommand(request.tenantId(), actor,
            request.projectId(), request.requestId(), request.inputDigest(), request.actionCode(),
            request.targetRef(), request.targetRevision(), request.sessionBinding()));
    }

    /** 唯一执行入口：草稿必须已确认；脚本由服务端编译；不接受调用方提供的 payload。 */
    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AssistantTaskService.TaskResult runTask(@RequestBody TaskRequestBody request) {
        tenants.requireTenantProject(request.tenantId(), request.projectId());
        Long actor = actors.resolveActor(request.actorId());
        AssistantTaskService.TaskResult result = tasks.run(new AssistantTaskService.TaskRequest(
            request.tenantId(), actor, request.projectId(), request.requestId(), request.draftId(),
            request.actionCode(), request.targetRef(), request.targetRevision(), request.spec(), request.inputs()));
        long wait = request.waitSeconds() == null ? 0 : Math.max(0, Math.min(120, request.waitSeconds()));
        if (wait > 0 && result.created()) {
            AssistantDispatchService.SyncResult synced = tasks.await(result.operationId(), Duration.ofSeconds(wait));
            return new AssistantTaskService.TaskResult(result.requestId(), result.operationId(), synced.executionState(),
                result.runId(), result.digest(), result.stepCount(), true, result.hermesSessionId(),
                result.hermesTurnRef());
        }
        return result;
    }

    @PostMapping("/explorations")
    public AssistantExplorationService.ExplorationResult explore(@RequestBody ExplorationBody body) {
        projectAccess.requireAccess(body.projectId());
        actors.resolveActor(body.actorId());
        return exploration.discover(body.url());
    }

    @PostMapping("/drafts")
    @ResponseStatus(HttpStatus.CREATED)
    public AssistantDraftService.DraftView createDraft(@RequestBody DraftRequestBody request) {
        tenants.requireTenantProject(request.tenantId(), request.projectId());
        Long actor = actors.resolveActor(request.createdBy());
        return drafts.create(new AssistantDraftService.CreateDraftCommand(request.tenantId(), request.projectId(),
            actor, request.requirementRef(), request.requirementText(), request.spec(),
            request.actionCode(), request.targetRef(), request.targetRevision(), request.inputs()));
    }

    @GetMapping("/drafts")
    public List<AssistantDraftService.DraftView> listDrafts(@RequestParam long tenantId,
                                                            @RequestParam long projectId,
                                                            @RequestParam(required = false) String state,
                                                            @RequestParam(defaultValue = "50") int limit,
                                                            @RequestParam(defaultValue = "0") int offset) {
        tenants.requireTenantProject(tenantId, projectId);
        return drafts.list(tenantId, projectId, state, limit, offset);
    }

    @GetMapping("/drafts/{draftId}/revisions")
    public List<AssistantDraftService.DraftView> draftRevisions(@PathVariable String draftId,
                                                                @RequestParam long tenantId) {
        tenants.requireTenantOnly(tenantId);
        return drafts.revisions(draftId, tenantId);
    }

    @PostMapping("/drafts/{draftId}/revise")
    @ResponseStatus(HttpStatus.CREATED)
    public AssistantDraftService.DraftView reviseDraft(@PathVariable String draftId,
                                                       @RequestBody ReviseBody body) {
        tenants.requireTenantOnly(body.tenantId());
        Long actor = actors.resolveActor(body.actorId());
        return drafts.revise(draftId, body.tenantId(), actor, body.spec(), body.inputs());
    }

    @PostMapping("/drafts/{draftId}/confirm")
    public AssistantDraftService.DraftView confirmDraft(@PathVariable String draftId, @RequestBody ConfirmBody body) {
        tenants.requireTenantOnly(body.tenantId());
        return drafts.confirm(draftId, body.tenantId(), actors.resolveActor(body.actorId()));
    }

    @PostMapping("/drafts/{draftId}/reject")
    public AssistantDraftService.DraftView rejectDraft(@PathVariable String draftId, @RequestBody ConfirmBody body) {
        tenants.requireTenantOnly(body.tenantId());
        return drafts.reject(draftId, body.tenantId(), actors.resolveActor(body.actorId()));
    }

    @GetMapping("/drafts/{draftId}")
    public AssistantDraftService.DraftView getDraft(@PathVariable String draftId,
                                                    @org.springframework.web.bind.annotation.RequestParam long tenantId) {
        tenants.requireTenantOnly(tenantId);
        return drafts.get(draftId, tenantId);
    }

    @PostMapping("/operations/{operationId}/report/generate")
    @ResponseStatus(HttpStatus.CREATED)
    public AssistantReportService.ReportView generateReport(@PathVariable String operationId,
                                                            @RequestParam long tenantId,
                                                            @RequestBody(required = false) ReportGenerateBody body) {
        requireOperation(operationId, tenantId);
        Long actor = actors.resolveActor(body == null ? null : body.generatedBy());
        return reports.generate(operationId, actor);
    }

    @GetMapping("/operations/{operationId}/report")
    public AssistantReportService.ReportView latestReport(@PathVariable String operationId,
                                                          @RequestParam long tenantId) {
        requireOperation(operationId, tenantId);
        return reports.latest(operationId);
    }

    @PostMapping("/operations/{operationId}/report")
    public Map<String, String> decideReport(@PathVariable String operationId, @RequestParam long tenantId,
                                            @RequestBody ReportDecision decision) {
        requireOperation(operationId, tenantId);
        Long actor = actors.resolveActor(null);
        tasks.decideReport(operationId, decision.reportId(), decision.accepted(), actor);
        String state = repository.findOperation(operationId)
            .orElseThrow(() -> new IllegalArgumentException("operation 不存在：" + operationId)).reportState();
        return Map.of("operationId", operationId, "reportState", state);
    }

    @PostMapping("/operations/{operationId}/resolve")
    public Map<String, String> resolveStalled(@PathVariable String operationId, @RequestParam long tenantId,
                                              @RequestBody ResolveBody body) {
        requireOperation(operationId, tenantId);
        Long actor = actors.resolveActor(body.actorId());
        String outcome = tasks.resolveStalled(operationId, body.outcome(), body.reason(), actor);
        return Map.of("operationId", operationId, "executionState", outcome);
    }

    @PostMapping("/operations/{operationId}/stop")
    public Map<String, String> stopRun(@PathVariable String operationId, @RequestParam long tenantId,
                                       @RequestBody StopBody body) {
        requireOperation(operationId, tenantId);
        Long actor = actors.resolveActor(body.actorId());
        String state = dispatch.stopAndResolve(operationId, body.reason(), actor);
        return Map.of("operationId", operationId, "executionState", state);
    }

    @PostMapping("/operations/{operationId}/resources/{resourceId}/clean")
    public Map<String, String> cleanResource(@PathVariable String operationId, @PathVariable String resourceId,
                                             @RequestParam long tenantId,
                                             @RequestBody ResourceCleanBody body) {
        requireOperation(operationId, tenantId);
        Long actor = actors.resolveActor(body.actorId());
        tasks.markResourceCleaned(operationId, resourceId, body.evidence(), actor);
        return Map.of("operationId", operationId, "resourceId", resourceId, "cleanupState", "CLEANED");
    }

    @PostMapping("/operations/{operationId}/resources/{resourceId}/unrecoverable")
    public Map<String, String> markResourceUnrecoverable(@PathVariable String operationId,
                                                         @PathVariable String resourceId,
                                                         @RequestParam long tenantId,
                                                         @RequestBody ResourceUnrecoverableBody body) {
        requireOperation(operationId, tenantId);
        Long actor = actors.resolveActor(body.actorId());
        tasks.markResourceUnrecoverable(operationId, resourceId, body.reason(), actor);
        return Map.of("operationId", operationId, "resourceId", resourceId, "cleanupState", "UNRECOVERABLE");
    }

    @GetMapping("/operations/{operationId}")
    public OperationView get(@PathVariable String operationId, @RequestParam long tenantId) {
        AssistantLedgerRepository.OperationRecord operation = requireOperation(operationId, tenantId);
        return new OperationView(operation.operationId(), operation.executionState(), operation.reportState(),
            operation.dispatchGeneration(), repository.listAttempts(operationId),
            repository.listResources(operationId));
    }

    @PostMapping("/operations/{operationId}/sync")
    public AssistantDispatchService.SyncResult sync(@PathVariable String operationId, @RequestParam long tenantId) {
        requireOperation(operationId, tenantId);
        return dispatch.synchronize(operationId);
    }

    /**
     * 等待用户：MFA / 人工登录 / 缺输入时显式进入 WAITING_USER。
     * 该状态非终态，报告不能生成，也不会被 Runner 回执推进。
     */
    @PostMapping("/operations/{operationId}/waiting-user")
    public AssistantWaitingUserService.WaitingView requireWaitingUser(@PathVariable String operationId,
                                                                     @RequestParam long tenantId,
                                                                     @RequestBody WaitingBody body) {
        requireOperation(operationId, tenantId);
        Duration timeout = body.timeoutMinutes() == null ? Duration.ofMinutes(30)
            : Duration.ofMinutes(body.timeoutMinutes());
        return waiting.requireUser(operationId, body.reason(), timeout);
    }

    /** 用户补齐后回到 PENDING：必须用新的 requestId 重新派发，不续跑旧代次。 */
    @PostMapping("/operations/{operationId}/waiting-user/resume")
    public AssistantWaitingUserService.WaitingView resumeWaitingUser(@PathVariable String operationId,
                                                                     @RequestParam long tenantId) {
        requireOperation(operationId, tenantId);
        return waiting.resume(operationId);
    }

    /** 用户放弃等待：转 CANCELLED 并保留原因。 */
    @PostMapping("/operations/{operationId}/waiting-user/cancel")
    public AssistantWaitingUserService.WaitingView cancelWaitingUser(@PathVariable String operationId,
                                                                     @RequestParam long tenantId,
                                                                     @RequestBody WaitingCancelBody body) {
        requireOperation(operationId, tenantId);
        return waiting.cancel(operationId, body.reason());
    }

    /**
     * 确定性到期清扫：超过 deadline 的等待一律 CANCELLED。
     * 按租户隔离（与其它 operation 端点一致），并记录操作人；不提供跨租户清扫入口——那属于定时任务。
     */
    @PostMapping("/operations/waiting-user/expire-overdue")
    public Map<String, Object> expireOverdueWaitingUser(@RequestParam long tenantId,
                                                        @RequestBody(required = false) ExpireBody body) {
        tenants.requireTenantOnly(tenantId);
        Long actor = actors.resolveActor(body == null ? null : body.actorId());
        List<String> expired = waiting.expireOverdue(tenantId, actor);
        return Map.of("expired", expired);
    }

    @GetMapping("/operations/{operationId}/waiting-user")
    public AssistantWaitingUserService.WaitingView describeWaitingUser(@PathVariable String operationId,
                                                                       @RequestParam long tenantId) {
        requireOperation(operationId, tenantId);
        return waiting.describe(operationId);
    }

    /** operation 的所有读写都带租户维度：拿到 operationId 不等于拿到跨租户权限。 */
    private AssistantLedgerRepository.OperationRecord requireOperation(String operationId, long tenantId) {
        // 先判租户本身：未知/停用租户统一 403，不因「先查 operation」而退化成 400，避免两套口径。
        tenants.requireTenantOnly(tenantId);
        AssistantLedgerRepository.OperationRecord operation = repository.findOperation(operationId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("operation 不存在：" + operationId));
        // 租户匹配只是第一道；真正的主体授权按「租户成员 + 项目成员」判（security 关闭时直接放行）。
        projectAccess.requireAccess(operation.projectId());
        return operation;
    }

    @ExceptionHandler(AssistantActorResolver.ActorResolutionException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String, String> actorRejected(AssistantActorResolver.ActorResolutionException ex) {
        return Map.of("error", "actor_required", "message", ex.getMessage());
    }

    @ExceptionHandler(AssistantAdmissionService.IdempotencyConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflict(AssistantAdmissionService.IdempotencyConflictException ex) {
        return Map.of("error", "idempotency_conflict", "message", ex.getMessage());
    }

    @ExceptionHandler(AssistantAdmissionService.DispatchFrozenException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> frozen(AssistantAdmissionService.DispatchFrozenException ex) {
        return Map.of("error", "dispatch_frozen", "message", ex.getMessage());
    }

    @ExceptionHandler(AssistantDraftService.DraftNotConfirmedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> draftNotConfirmed(AssistantDraftService.DraftNotConfirmedException ex) {
        return Map.of("error", "draft_not_confirmed", "message", ex.getMessage());
    }

    @ExceptionHandler(AssistantTaskService.ReportDecisionException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> reportDecision(AssistantTaskService.ReportDecisionException ex) {
        return Map.of("error", "report_decision_rejected", "message", ex.getMessage());
    }

    /**
     * 业务状态冲突（例如不在等待态却要恢复）按 409 返回。
     * 只认专用异常：控制器级 IllegalStateException 还包含密钥/SHA 等基础设施故障，
     * 一起映射成 409 会误导客户端重试与告警。
     */
    @ExceptionHandler(com.haitlify.aitest.platform.assistant.ledger.AssistantStateConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> stateConflict(
            com.haitlify.aitest.platform.assistant.ledger.AssistantStateConflictException ex) {
        return Map.of("error", "state_conflict", "message", ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException ex) {
        return Map.of("error", "invalid_request", "message", ex.getMessage());
    }
}
