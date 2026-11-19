package com.haitlify.aitest.platform.assistant.dispatch;

import java.time.Duration;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.haitlify.aitest.platform.assistant.compile.AssistantInputBindingService;
import com.haitlify.aitest.platform.assistant.compile.AssistantScriptCompiler;
import com.haitlify.aitest.platform.assistant.draft.AssistantDraftRepository;
import com.haitlify.aitest.platform.assistant.draft.AssistantDraftService;
import com.haitlify.aitest.platform.assistant.runtime.HermesAssistantRuntime;
import com.haitlify.aitest.platform.assistant.ledger.AssistantAdmissionService;
import com.haitlify.aitest.platform.assistant.ledger.AssistantLedgerRepository;
import com.haitlify.aitest.platform.auth.ProjectAccessService;

import tools.jackson.databind.ObjectMapper;

/**
 * A1 窄链路的编排：草稿 → 确定性编译 → 受理（幂等）→ [Hermes 规划] → 派发 Runner → 状态回填 → 报告接受。
 *
 * <p>编排层不产生业务语义：编译由 {@link AssistantScriptCompiler} 负责，幂等与冻结由
 * {@link AssistantAdmissionService} 负责，Runner 交互由 {@link AssistantDispatchService} 负责。
 * 这里只按固定顺序串起来，并保证「任何一步不确认就停在 UNKNOWN / PENDING，不往下编造」。
 *
 * <p>两条不可跳过的门：人工确认（只有 CONFIRMED 且结构未被改写的草稿才能执行）与
 * Hermes 接入<b>可开关但不可静默降级</b>——开启后执行单元不可用即拒绝受理，{@code app.assistant.hermes.enabled=true} 而执行单元
 * 而不是「跳过助手、直接跑脚本」——那样 A1 就变成了人工代写脚本。
 */
@Service
public class AssistantTaskService {

    public record TaskRequest(long tenantId, long actorId, Long projectId, String requestId, String draftId,
                              String actionCode, String targetRef, String targetRevision,
                              AssistantScriptCompiler.CaseSpec spec, Map<String, String> inputs) {}

    public record TaskResult(String requestId, String operationId, String executionState, String runId,
                             String digest, int stepCount, boolean created, String hermesSessionId, String hermesTurnRef) {}

    private final AssistantScriptCompiler compiler;
    private final AssistantAdmissionService admission;
    private final AssistantDispatchService dispatch;
    private final AssistantLedgerRepository repository;
    private final HermesAssistantRuntime hermes;
    private final AssistantDraftService drafts;
    private final com.haitlify.aitest.platform.assistant.report.AssistantReportRepository reports;
    private final AssistantInputBindingService binding;
    private final ObjectMapper objectMapper;
    private final ProjectAccessService projectAccess;
    private final boolean hermesEnabled;
    private final String hermesCwd;

    public AssistantTaskService(AssistantScriptCompiler compiler, AssistantAdmissionService admission,
                                AssistantDispatchService dispatch, AssistantLedgerRepository repository,
                                HermesAssistantRuntime hermes, AssistantDraftService drafts,
                                com.haitlify.aitest.platform.assistant.report.AssistantReportRepository reports,
                                AssistantInputBindingService binding, ObjectMapper objectMapper,
                                ProjectAccessService projectAccess,
                                @Value("${app.assistant.hermes.enabled:true}") boolean hermesEnabled,
                                @Value("${app.assistant.hermes.cwd:}") String hermesCwd) {
        this.compiler = compiler;
        this.admission = admission;
        this.dispatch = dispatch;
        this.repository = repository;
        this.hermes = hermes;
        this.drafts = drafts;
        this.reports = reports;
        this.binding = binding;
        this.objectMapper = objectMapper;
        this.projectAccess = projectAccess;
        this.hermesEnabled = hermesEnabled;
        this.hermesCwd = hermesCwd == null || hermesCwd.isBlank() ? null : hermesCwd;
    }

    /** 受理并派发；同一 requestId 重放时不会重复执行（返回既有 operation 的当前状态）。 */
    public TaskResult run(TaskRequest request) {
        // 幂等 ID 必须由客户端提供：自动生成会让「响应丢失后重试」变成第二次执行。
        if (request.requestId() == null || request.requestId().isBlank()) {
            throw new IllegalArgumentException("requestId 必填：执行入口只接受客户端提供的稳定幂等 ID");
        }
        String requestId = request.requestId();
        // 先验人工确认（确认的是含占位符的原结构），再绑定输入，最后编译。
        // 先按项目授权，再看草稿细节：避免「先抛未确认/结构不一致」把草稿状态泄露给非成员。
        projectAccess.requireAccess(request.projectId());
        AssistantDraftRepository.DraftRecord confirmed = drafts.requireConfirmed(request.draftId(),
            request.tenantId(), request.spec(), request.actionCode(), request.targetRef(), request.targetRevision(),
            request.inputs(), objectMapper);
        // 严格执行：草稿与本次执行的 projectId 必须完全相等（null 只等于 null），否则「授权的是 A 项目、执行的是 B 项目」。
        if (!java.util.Objects.equals(confirmed.projectId(), request.projectId())) {
            throw new com.haitlify.aitest.platform.assistant.draft.AssistantDraftService.DraftNotConfirmedException(
                "projectId 与已确认草稿不一致，拒绝执行：" + request.draftId());
        }
        projectAccess.requireAccess(request.projectId());
        AssistantScriptCompiler.CaseSpec bound = request.inputs() == null || request.inputs().isEmpty()
            ? request.spec()
            : binding.bind(request.spec(), request.inputs()).spec();
        AssistantScriptCompiler.CompiledScript compiled = compiler.compile(bound);

        if (hermesEnabled && !hermes.available()) {
            throw new IllegalStateException(
                "Hermes 执行单元不可用（app.assistant.hermes.enabled=true）：拒绝受理，避免退化成人工代写脚本");
        }

        AssistantAdmissionService.AdmissionResult admitted = admission.admit(
            new AssistantAdmissionService.AdmissionCommand(request.tenantId(), request.actorId(), request.projectId(),
                requestId, compiled.digest(), request.actionCode(), request.targetRef(), request.targetRevision(), null));

        if (admitted.created()) {
            repository.bindOperationDraft(admitted.operationId(), request.draftId());
        }
        if (!admitted.created()) {
            AssistantLedgerRepository.AttemptRecord lastAttempt = repository.listAttempts(admitted.operationId()).stream()
                .reduce((first, second) -> second).orElse(null);
            return new TaskResult(requestId, admitted.operationId(), admitted.executionState(),
                lastAttempt == null ? null : lastAttempt.runnerRunId(), compiled.digest(), compiled.stepCount(), false,
                lastAttempt == null ? null : lastAttempt.hermesStoredSessionId(),
                lastAttempt == null ? null : lastAttempt.hermesTurnRef());
        }

        String hermesSessionId = null;
        String hermesTurnRef = null;
        if (hermesEnabled) {
            try {
                HermesAssistantRuntime.Submission receipt = hermes.submit(hermesCwd, planningPrompt(request, compiled));
                hermesSessionId = receipt.storedSessionId();
                hermesTurnRef = receipt.turnRef();
            } catch (RuntimeException ex) {
                // 助手没跑起来 = 这一轮不可判定：冻结 operation + 请求轴，留对账事件，重放能看到 UNKNOWN 而不是假装 PENDING。
                admission.raiseUnknown(request.tenantId(), requestId, admitted.operationId(),
                    "Hermes 不可用/未回执: " + ex.getMessage());
                throw new IllegalStateException(
                    "Hermes 执行单元失败，本轮已冻结为 UNKNOWN（operation=" + admitted.operationId() + "）："
                        + ex.getMessage(), ex);
            }
        }

        AssistantDispatchService.DispatchResult dispatched = dispatch.dispatch(
            new AssistantDispatchService.DispatchCommand(request.tenantId(), request.actorId(), requestId,
                admitted.operationId(), compiled.payloadJson(), Duration.ofHours(2)));
        if (hermesSessionId != null) {
            repository.bindAttemptHermes(dispatched.attemptId(), hermesSessionId, hermesTurnRef);
        }
        return new TaskResult(requestId, admitted.operationId(), dispatched.executionState(), dispatched.runId(),
            compiled.digest(), compiled.stepCount(), true, hermesSessionId, hermesTurnRef);
    }

    /** 拼进提示词之前去掉控制字符并截断：客户端可控文本不能带换行改写指令。 */
    /** 拼 JSON 前必须转义：否则 reason 里一个反斜杠就能让 detail_json 变成非法 JSON。 */
    static String jsonQuote(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }


    static String promptSafe(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replaceAll("[\\p{Cntrl}&&[^\\t]]", " ").replaceAll("\\s+", " ").trim();
        return cleaned.length() > 200 ? cleaned.substring(0, 200) + "…" : cleaned;
    }

    /** 交给助手的是「任务与约束」，不是可执行脚本：脚本来自确定性编译，助手不能改写 payload。 */
    private static String planningPrompt(TaskRequest request, AssistantScriptCompiler.CompiledScript compiled) {
        return "任务：" + promptSafe(request.spec().name()) + "\n"
            + "目标：" + promptSafe(request.targetRef()) + "@" + promptSafe(request.targetRevision()) + "\n"
            + "动作：执行已确认用例 " + request.spec().id() + "（" + compiled.stepCount() + " 步），"
            + "脚本由平台确定性编译（digest=" + compiled.digest() + "），无需生成代码。\n"
            + "约束：只读断言与已授权写入；完成后回读结果；不得修改 payload。";
    }

    /** 轮询到终态或超时（A1 同步等待；正式链路应改为调度器异步推进）。 */
    public AssistantDispatchService.SyncResult await(String operationId, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        AssistantDispatchService.SyncResult last = dispatch.synchronize(operationId);
        while (!AssistantAdmissionService.isTerminal(last.executionState())
            && !"UNKNOWN".equals(last.executionState())
            && System.nanoTime() < deadline) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
            last = dispatch.synchronize(operationId);
        }
        return last;
    }

    /** 资源清理：必须带证据（回读结果），不接受「口头已清理」。 */
    @org.springframework.transaction.annotation.Transactional
    public void markResourceCleaned(String operationId, String resourceId, String evidenceJson, Long actorId) {
        String stateNow = repository.findOperation(operationId)
            .orElseThrow(() -> new IllegalArgumentException("operation 不存在：" + operationId)).executionState();
        if (!AssistantAdmissionService.isTerminal(stateNow)) {
            // 还在跑就宣告「已清理」，等于把清理门变成一次点击。
            throw new ReportDecisionException("执行尚未结束（" + stateNow + "），不能登记资源已清理");
        }
        // 证据要能指向可核验对象，不能只写一句「已清理」（A0 开放项）。
        com.haitlify.aitest.platform.assistant.security.CleanupEvidence.validate(evidenceJson);
        AssistantLedgerRepository.ResourceRecord resource = repository.listResources(operationId).stream()
            .filter(item -> item.resourceId().equals(resourceId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("资源不属于该 operation：" + resourceId));
        if (!repository.markResourceCleaned(resourceId, evidenceJson, actorId)) {
            throw new ReportDecisionException("资源当前状态不可清理（state=" + resource.cleanupState() + "）");
        }
        repository.appendReconciliation(operationId, null, "RESOURCE_CLEANED", actorId,
            "{\"resourceId\":\"" + resourceId + "\",\"kind\":\"" + resource.resourceKind() + "\"}");
    }


    /** 资源判为不可恢复：必须人工给出原因；这是 MANUAL_REQUIRED 之后唯一的出口。 */
    @org.springframework.transaction.annotation.Transactional
    public void markResourceUnrecoverable(String operationId, String resourceId, String reason, Long actorId) {
        String stateNow = repository.findOperation(operationId)
            .orElseThrow(() -> new IllegalArgumentException("operation 不存在：" + operationId)).executionState();
        if (!AssistantAdmissionService.isTerminal(stateNow)) {
            throw new ReportDecisionException("执行尚未结束（" + stateNow + "），不能判定资源不可恢复");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("判不可恢复必须给出原因");
        }
        AssistantLedgerRepository.ResourceRecord resource = repository.listResources(operationId).stream()
            .filter(item -> item.resourceId().equals(resourceId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("资源不属于该 operation：" + resourceId));
        if (!repository.markResourceUnrecoverable(resourceId, reason, actorId)) {
            throw new ReportDecisionException("资源当前状态不可判为不可恢复（state=" + resource.cleanupState() + "）");
        }
        repository.appendReconciliation(operationId, null, "RESOURCE_UNRECOVERABLE", actorId,
            "{\"resourceId\":\"" + resourceId + "\",\"reason\":\"" + jsonQuote(reason) + "\"}");
    }

    /**
     * 人工处置：PENDING 或「无 attempt 的 UNKNOWN」没有可回读的 Runner runId，只能由人给出结论。
     * 只允许这两个状态出边，且必须带原因，落对账事件。
     */
    @org.springframework.transaction.annotation.Transactional
    public String resolveStalled(String operationId, String outcome, String reason, Long actorId) {
        // 没有 Runner 证据的处置只能判失败/取消：人工「判定成功」会凭空造出已接受的报告。
        if (!java.util.Set.of("FAILED", "CANCELLED").contains(outcome)) {
            throw new IllegalArgumentException("人工处置只能判 FAILED 或 CANCELLED：" + outcome);
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("人工处置必须给原因");
        }
        AssistantLedgerRepository.OperationRecord operation = repository.findOperationForUpdate(operationId)
            .orElseThrow(() -> new IllegalArgumentException("operation 不存在：" + operationId));
        boolean movable = "PENDING".equals(operation.executionState())
            || ("UNKNOWN".equals(operation.executionState()) && repository.listAttempts(operationId).stream()
                .noneMatch(attempt -> attempt.runnerRunId() != null));
        if (!movable) {
            throw new ReportDecisionException(
                "只有 PENDING 或无 runId 的 UNKNOWN 可以人工处置（当前 " + operation.executionState() + "）");
        }
        if (!repository.transitionExecution(operationId, operation.executionState(), outcome)) {
            throw new ReportDecisionException("状态迁移被拒绝：" + operation.executionState() + " → " + outcome);
        }
        repository.appendReconciliation(operationId, null, "MANUAL_RESOLUTION", actorId,
            "{\"outcome\":\"" + outcome + "\",\"reason\":\"" + jsonQuote(reason) + "\"}");
        repository.resolveRequestUnknown(operationId);
        return outcome;
    }

    /** 报告正文是生成时的快照：接受时必须与账本一致，否则审计物证会自相矛盾。 */
    private void requireReportMatchesResources(String operationId, String bodyJson) {
        try {
            var body = objectMapper.readTree(bodyJson);
            java.util.Map<String, String> snapshot = new java.util.HashMap<>();
            for (var node : body.path("resources")) {
                snapshot.put(node.path("kind").asString(""), node.path("cleanupState").asString(""));
            }
            for (AssistantLedgerRepository.ResourceRecord resource : repository.listResources(operationId)) {
                String recorded = snapshot.get(resource.resourceKind());
                if (recorded != null && !recorded.equals(resource.cleanupState())) {
                    throw new ReportDecisionException("报告正文的资源状态（" + resource.resourceKind() + "=" + recorded
                        + "）与账本（" + resource.cleanupState() + "）不一致，请重新生成报告");
                }
            }
        } catch (ReportDecisionException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ReportDecisionException("报告正文无法解析，拒绝决策：" + ex.getMessage());
        }
    }

    /** 接受报告前的资源闸：账要齐（BROWSER+TEST_DATA），且每条都收尾。 */
    private void requireSettledResources(String operationId) {
        var resources = repository.listResources(operationId);
        if (!repository.listAttempts(operationId).isEmpty()) {
            java.util.Set<String> kinds = resources.stream()
                .map(AssistantLedgerRepository.ResourceRecord::resourceKind)
                .collect(java.util.stream.Collectors.toSet());
            if (!kinds.contains("BROWSER") || !kinds.contains("TEST_DATA")) {
                throw new ReportDecisionException(
                    "资源账不完整（" + kinds + "），拒绝接受报告：浏览器与测试数据都要有登记：" + operationId);
            }
        }
        boolean unsettled = resources.stream()
            .anyMatch(resource -> !"CLEANED".equals(resource.cleanupState())
                && !"UNRECOVERABLE".equals(resource.cleanupState()));
        if (unsettled) {
            throw new ReportDecisionException(
                "仍有未清理资源（ALLOCATED/MANUAL_REQUIRED），不能接受报告：" + operationId);
        }
    }


    /**
     * 报告接受/驳回：只写 report_state，不影响执行事实（三轴独立）。
     * 没有报告就不能接受；已经是终态决策的不能反复翻转（CAS）。
     */
    @org.springframework.transaction.annotation.Transactional
    public void decideReport(String operationId, String reportId, boolean accepted, Long actorId) {
        AssistantLedgerRepository.OperationRecord operation = repository.findOperationForUpdate(operationId)
            .orElseThrow(() -> new ReportDecisionException("operation 不存在：" + operationId));
        if (!AssistantAdmissionService.isTerminal(operation.executionState())) {
            throw new ReportDecisionException(
                "执行尚未到终态（" + operation.executionState() + "），不能接受/驳回报告：" + operationId);
        }
        if (reportId == null || reportId.isBlank()) {
            throw new ReportDecisionException("reportId 必填：接受/驳回必须绑定具体修订（防止批准未审阅的新版）");
        }
        var latest = reports.latest(operationId)
            .orElseThrow(() -> new ReportDecisionException("该 operation 还没有报告，不能接受/驳回：" + operationId));
        if (!latest.reportId().equals(reportId)) {
            throw new ReportDecisionException(
                "报告已有新修订（latest=" + latest.reportId() + "），拒绝基于过期修订做决策");
        }
        // 被接受的审计物证必须对得上当前执行事实：正文里的 executionState 不能是旧快照。
        try {
            var body = objectMapper.readTree(latest.bodyJson());
            String snapshotState = body.path("executionState").asString("");
            if (!operation.executionState().equals(snapshotState)) {
                throw new ReportDecisionException("报告正文的执行状态（" + snapshotState
                    + "）与当前执行事实（" + operation.executionState() + "）不一致，请重新生成报告");
            }
        } catch (ReportDecisionException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ReportDecisionException("报告正文无法解析，拒绝决策：" + ex.getMessage());
        }
        // 资源门只拦「接受」：驳回是审核意见，不该被资源状态卡住（三轴独立）。
        if (accepted) {
            requireSettledResources(operationId);
            requireReportMatchesResources(operationId, latest.bodyJson());
        }
        String state = accepted ? "ACCEPTED" : "REJECTED";
        // 先给修订落决策（CAS：已决策的修订不能再改），再改 operation 轴；两步在同一事务里。
        if (!reports.markDecision(latest.reportId(), state, actorId)) {
            throw new ReportDecisionException("该报告修订已被决策，拒绝覆盖：" + latest.reportId());
        }
        if (!repository.setReportState(operationId, state)) {
            throw new ReportDecisionException("报告状态已决策或 operation 不存在，拒绝覆盖：" + operationId);
        }
    }

    public static class ReportDecisionException extends RuntimeException {
        public ReportDecisionException(String message) {
            super(message);
        }
    }
}
