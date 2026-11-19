package com.haitlify.aitest.platform.assistant.dispatch;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.haitlify.aitest.platform.assistant.ledger.AssistantAdmissionService;
import com.haitlify.aitest.platform.assistant.ledger.AssistantLedgerRepository;

/**
 * A1 链路的「派发 → Runner → 状态回填」段（计划 §9.2）。
 *
 * <p>派发是**一次性占用**：先 CAS {@code PENDING→DISPATCHED}，只有抢到这次迁移的调用才能提交 Runner；
 * 已派发/运行中的 operation 再次派发一律拒绝（重复执行必须新建 operation + 新授权）。
 *
 * <p>提交结果不可判定（连接失败、5xx、超时、任何运行时异常）⇒ attempt 记 UNKNOWN、operation 冻结；
 * 绝不把「没收到响应」当成功。终态只写一次（CAS），迟到回执不覆盖。
 */
@Service
public class AssistantDispatchService {

    private static final Logger log = LoggerFactory.getLogger(AssistantDispatchService.class);
    private static final Set<String> RUNNING_STATES = Set.of("queued", "running", "pending", "starting");
    private static final Set<String> SUCCEEDED_STATES = Set.of("completed", "succeeded", "passed");
    private static final Set<String> FAILED_STATES = Set.of("failed", "error");
    private static final Set<String> CANCELLED_STATES = Set.of("stopped", "cancelled", "canceled");

    public record DispatchCommand(long tenantId, long actorId, String requestId, String operationId,
                                  String payloadJson, Duration resourceDeadline) {}

    public record DispatchResult(String operationId, String attemptId, String runId, String executionState,
                                 boolean unknown) {}

    public record SyncResult(String operationId, String runnerStatus, String executionState) {}

    private final AssistantLedgerRepository repository;
    private final AssistantAdmissionService admission;
    private final AssistantRunnerGateway gateway;

    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    private final AssistantRunnerLogin runnerLogin;

    public AssistantDispatchService(AssistantLedgerRepository repository, AssistantAdmissionService admission,
                                    AssistantRunnerGateway gateway,
                                    org.springframework.transaction.PlatformTransactionManager transactionManager,
                                    AssistantRunnerLogin runnerLogin) {
        this.repository = repository;
        this.admission = admission;
        this.gateway = gateway;
        this.transactionTemplate = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.runnerLogin = runnerLogin;
    }

    public DispatchResult dispatch(DispatchCommand command) {
        if (command.payloadJson() == null || command.payloadJson().isBlank()) {
            throw new IllegalArgumentException("payload 不能为空：派发必须携带已编译脚本");
        }
        AssistantLedgerRepository.OperationRecord operation = admission.assertDispatchAllowed(command.operationId());

        // 一次性占用：只有 PENDING→DISPATCHED 成功的那次调用才能提交 Runner。
        if (!repository.transitionExecution(command.operationId(), "PENDING", "DISPATCHED")) {
            throw new AssistantAdmissionService.DispatchFrozenException(
                "operation 已派发或已冻结（state != PENDING），拒绝重复派发：执行必须新建 operation 与新授权");
        }
        int generation = operation.dispatchGeneration() + 1;
        repository.bumpDispatchGeneration(command.operationId());

        String attemptId = UUID.randomUUID().toString();
        repository.insertAttempt(attemptId, command.operationId(), generation, Instant.now());

        // 先登记资源再创建 run：调用方一旦失败，资源仍在账上可被清理/对账，不会出现「有 run 没账」。
        Duration deadline = command.resourceDeadline() == null ? Duration.ofHours(2) : command.resourceDeadline();
        repository.recordResource(UUID.randomUUID().toString(), command.operationId(), "BROWSER",
            command.actorId(), "RUNNER_FINALLY", Instant.now().plus(deadline));
        repository.recordResource(UUID.randomUUID().toString(), command.operationId(), "TEST_DATA",
            command.actorId(), "HAITLIFY_WORKER", Instant.now().plus(deadline));

        AssistantRunnerGateway.RunCreated created;
        try {
            created = gateway.createRun(runnerLogin.withLoginRuntime(command.payloadJson()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return markUnknown(command, attemptId, "派发被中断: " + ex.getMessage());
        } catch (IOException | RuntimeException ex) {
            return markUnknown(command, attemptId, "派发结果不可判定: " + ex);
        }

        try {
            repository.bindAttemptRun(attemptId, created.runId());
        } catch (RuntimeException ex) {
            // run 已经创建：账本写不进去时不能装没事。先尽力停止 run，再冻结。
            stopQuietly(created.runId());
            return markUnknown(command, attemptId, "run 已创建但账本写入失败: " + ex.getMessage()
                + "（runId=" + created.runId() + "）");
        }
        try {
            if (!admission.markDispatched(command.tenantId(), command.requestId(), command.operationId())) {
                // 请求轴不是 ADMITTED（并发处置过）：不覆盖，只留痕；operation 仍按已派发推进。
                repository.appendReconciliation(command.operationId(), attemptId, "MANUAL_DISPOSITION",
                    command.actorId(), "{\"reason\":\"request-not-admitted-on-dispatch\"}");
            }
        } catch (RuntimeException ex) {
            repository.appendReconciliation(command.operationId(), attemptId, "MANUAL_DISPOSITION", command.actorId(),
                "{\"reason\":\"request-axis-write-failed\",\"runId\":\""
                    + AssistantTaskService.jsonQuote(created.runId()) + "\"}");
        }
        return new DispatchResult(command.operationId(), attemptId, created.runId(), "DISPATCHED", false);
    }

    /** 崩溃窗口对账需要 requestId：operation → request 反查（同一 operation 可能有多条请求记录，取最新一条）。 */
    private String findRequestId(String operationId) {
        return repository.findRequestIdByOperation(operationId).orElse("");
    }

    /**
     * 执行事实的判定：Runner 的 completed 只代表「跑完了」。必须看 summary：
     * 有 failed/error ⇒ FAILED；一条都没跑（total=0）⇒ FAILED（fail-closed）；只有 passed>0 且无失败才算 SUCCEEDED。
     */
    static String terminalState(String status, AssistantRunnerGateway.RunSnapshot snapshot) {
        if (CANCELLED_STATES.contains(status)) {
            return "CANCELLED";
        }
        if (FAILED_STATES.contains(status)) {
            return "FAILED";
        }
        if (!SUCCEEDED_STATES.contains(status)) {
            return null;
        }
        if (snapshot.failed() > 0 || snapshot.error() > 0) {
            return "FAILED";
        }
        if (snapshot.total() <= 0 || snapshot.passed() <= 0) {
            return "FAILED";
        }
        return "SUCCEEDED";
    }

    /**
     * 停止请求是异步的：Runner 返回 200 只代表「收到停止请求」，run 可能还在跑最后一步。
     * 所以必须回读确认终态；没停下来就不再写 CANCELLED（宁可报错，不做账面取消）。
     */
    public String stopAndResolve(String operationId, String reason, Long actorId) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("停止运行必须给出原因");
        }
        // 不持有行锁做最长 120s 的外部调用：先读、再停、最后在短事务里落状态。
        AssistantLedgerRepository.OperationRecord operation = repository.findOperation(operationId)
            .orElseThrow(() -> new IllegalArgumentException("operation 不存在：" + operationId));
        // WAITING_USER 且仍有未收尾 run 时也必须能停：否则「先停执行再取消等待」的提示没有可达动作。
        if (!"UNKNOWN".equals(operation.executionState()) && !"RUNNING".equals(operation.executionState())
            && !"WAITING_USER".equals(operation.executionState())) {
            throw new AssistantTaskService.ReportDecisionException(
                "只有 UNKNOWN/RUNNING/WAITING_USER 可以停止（当前 " + operation.executionState() + "）");
        }
        // 可能有多代次未收尾 run（等待用户 → resume → 重派发）：只停最新一条会把旧 run 留在跑，
        // 却把它的 attempt 记成同一终态。这里把所有未收尾 run 都列为停止目标。
        java.util.List<String> liveRunIds = repository.listAttempts(operationId).stream()
            .filter(attempt -> attempt.runnerRunId() != null && attempt.endedAt() == null)
            .map(AssistantLedgerRepository.AttemptRecord::runnerRunId)
            .distinct()
            .toList();
        String runId = liveRunIds.isEmpty()
            ? repository.listAttempts(operationId).stream()
                .map(AssistantLedgerRepository.AttemptRecord::runnerRunId)
                .filter(java.util.Objects::nonNull)
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssistantTaskService.ReportDecisionException(
                    "该 operation 没有可停止的 runId，请走人工处置"))
            : liveRunIds.get(liveRunIds.size() - 1);

        // 逐代次处理：已自然结束的不再发 stop（否则 404 会把「成功」记成「取消」），
        // 仍在跑的都停；每一代各自回读自己的终态，旧代被停掉不能记成最新代的结论。
        boolean runLost = false;
        java.util.List<String> lostRunIds = new java.util.ArrayList<>();
        java.util.Map<String, AssistantRunnerGateway.RunSnapshot> snapshots = new java.util.LinkedHashMap<>();
        java.util.List<String> pending = new java.util.ArrayList<>();
        java.util.List<String> targets = liveRunIds.isEmpty() ? java.util.List.of(runId) : liveRunIds;
        for (String target : targets) {
            boolean isNewest = target.equals(runId);
            try {
                AssistantRunnerGateway.RunSnapshot snapshot = gateway.fetchRun(target);
                if (terminalState(snapshot.status(), snapshot) != null) {
                    snapshots.put(target, snapshot);
                    continue;
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssistantTaskService.ReportDecisionException("回读 run 被中断：" + target);
            } catch (Exception ex) {
                log.warn("停止前回读失败，按执行中处理（runId={}）：{}", target, ex.getMessage());
            }

            try {
                gateway.stopRun(target);
                pending.add(target);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssistantTaskService.ReportDecisionException("停止 run 被中断：" + target);
            } catch (AssistantRunnerGateway.RunNotFoundException ex) {
                // Runner 已回收/重启：run 不存在，没有继续执行的副作用（账上用事件区分「丢失」与「停掉」）。
                lostRunIds.add(target);
                if (isNewest) {
                    runLost = true;
                }
            } catch (Exception ex) {
                // fail-closed：任何一个 run 停不掉都不在账面取消。
                throw new AssistantTaskService.ReportDecisionException(
                    "停止 run 失败，拒绝在账面取消：" + ex.getMessage());
            }
        }
        // 统一轮询：**轮询窗口** 5s（不含预停前回读的 N 次 GET），没有确认到终态的 run 一律 fail-closed。
        // 单次 GET 的耗时另由网关超时 T 约束：整次上界 ≈ 5s + 3N×T（预读 N + 轮询内 N + 末轮在途 N），
        // 因此不宣称「整次 ≤5s」——要收紧得给网关设更短的读超时。
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (!pending.isEmpty() && System.nanoTime() < deadline) {
            for (java.util.Iterator<String> it = pending.iterator(); it.hasNext(); ) {
                String target = it.next();
                try {
                    AssistantRunnerGateway.RunSnapshot snapshot = gateway.fetchRun(target);
                    if (terminalState(snapshot.status(), snapshot) != null) {
                        snapshots.put(target, snapshot);
                        it.remove();
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new AssistantTaskService.ReportDecisionException("停止后回读被中断：" + target);
                } catch (Exception ex) {
                    log.warn("停止后回读失败（runId={}）：{}", target, ex.getMessage());
                }
            }
            if (!pending.isEmpty() && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (!pending.isEmpty()) {
            // 停止已送达但确认不了终态：不在账面写任何终态，也不把某一代的结论套到别的代上。
            throw new AssistantTaskService.ReportDecisionException(
                "停止请求已送达，但这些 run 仍未确认终态，拒绝落账：" + pending
                    + (lostRunIds.isEmpty() ? "" : "（另有 run 已丢失：" + lostRunIds + "）"));
        }
        AssistantRunnerGateway.RunSnapshot newest = snapshots.get(runId);
        String terminal = runLost ? "CANCELLED"
            : newest == null ? null : terminalState(newest.status(), newest);
        if (terminal == null) {
            throw new AssistantTaskService.ReportDecisionException(
                "停止请求已送达，但 run 仍在执行；未在账面取消，请稍后重试或等待回读：" + runId);
        }
        String previous = operation.executionState();
        boolean runWasLost = runLost;
        // 等待原因在进入终态时会被清空：先记下来，事件里要能看出「当时在等什么」。
        String waitingReason = repository.waitingInfo(operationId)
            .map(AssistantLedgerRepository.WaitingInfo::reason).orElse(null);
        // 写状态放进短事务：避免「状态已取消、attempt 还 RUNNING、证据缺失」的半截账。
        transactionTemplate.executeWithoutResult(status -> {
            if (!repository.transitionExecution(operationId, previous, terminal)) {
                throw new AssistantTaskService.ReportDecisionException(
                    "状态迁移被拒绝：" + previous + " → " + terminal);
            }
            repository.listAttempts(operationId).stream()
                .filter(attempt -> attempt.runnerRunId() != null && attempt.endedAt() == null)
                .forEach(attempt -> {
                    AssistantRunnerGateway.RunSnapshot observed = snapshots.get(attempt.runnerRunId());
                    if (observed == null) {
                        // run 在 Runner 侧已不存在（404）：确认不了它跑到哪一步，不能套用别的代次结论。
                        repository.finishAttempt(attempt.attemptId(), "UNKNOWN");
                        repository.appendReconciliation(operationId, attempt.attemptId(), "MANUAL_DISPOSITION",
                            actorId, "{\"reason\":\"run_lost\",\"runId\":\""
                                + AssistantTaskService.jsonQuote(attempt.runnerRunId()) + "\"}");
                        return;
                    }
                    repository.finishAttempt(attempt.attemptId(), terminalState(observed.status(), observed));
                    // 每一代各自的 run 汇总：只写真实回读到的计数，不编造 0。
                    repository.recordRunSummary(attempt.attemptId(), observed.total(),
                        observed.passed(), observed.failed(), observed.error());
                });
            repository.appendReconciliation(operationId, null, "MANUAL_RESOLUTION", actorId,
                "{\"outcome\":\"" + terminal + "\",\"reason\":\"" + AssistantTaskService.jsonQuote(reason)
                    + "\",\"runId\":\"" + AssistantTaskService.jsonQuote(runId) + "\",\"runLost\":"
                    + runWasLost + ",\"waitingReason\":"
                    + (waitingReason == null ? "null" : "\"" + AssistantTaskService.jsonQuote(waitingReason) + "\"")
                    + ",\"lostRunIds\":[" + lostRunIds.stream()
                        .map(id -> "\"" + AssistantTaskService.jsonQuote(id) + "\"")
                        .collect(java.util.stream.Collectors.joining(",")) + "]}");
            repository.resolveRequestUnknown(operationId);
        });
        return terminal;
    }

    /** 尽力停止 Runner run：失败只记日志，由 UNKNOWN 与人工处置兜底。 */
    private void stopQuietly(String runId) {
        try {
            gateway.stopRun(runId);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("停止 run 被中断（runId={}）", runId);
        } catch (Exception ex) {
            log.warn("停止 run 失败（runId={}）：{}", runId, ex.getMessage());
        }
    }

    private DispatchResult markUnknown(DispatchCommand command, String attemptId, String reason) {
        log.warn("Runner 派发结果不可判定，冻结为 UNKNOWN（operation={}）：{}", command.operationId(), reason);
        repository.finishAttempt(attemptId, "UNKNOWN");
        admission.raiseUnknown(command.tenantId(), command.requestId(), command.operationId(), reason);
        return new DispatchResult(command.operationId(), attemptId, null, "UNKNOWN", true);
    }

    /** 读一次 Runner 状态并落库；重复调用幂等（终态不会被覆盖）。 */
    public SyncResult synchronize(String operationId) {
        AssistantLedgerRepository.OperationRecord operation = repository.findOperation(operationId)
            .orElseThrow(() -> new IllegalArgumentException("operation 不存在：" + operationId));
        Optional<AssistantLedgerRepository.AttemptRecord> attempt = repository.listAttempts(operationId).stream()
            .reduce((first, second) -> second);
        if (attempt.isEmpty() || attempt.get().runnerRunId() == null) {
            // 崩溃窗口：CAS 到 DISPATCHED 后、insertAttempt/createRun 前中断 ⇒ 永远没有 attempt。
            // 给 60s 宽限（正常派发在秒级完成），仍为空则冻结为 UNKNOWN，避免永久悬挂。
            boolean stale = repository.operationUpdatedAt(operationId)
                .map(at -> at.isBefore(Instant.now().minusSeconds(60)))
                .orElse(false);
            if (stale && "DISPATCHED".equals(operation.executionState())) {
                // 两轴一起冻结：请求轴不能停在 DISPATCHED 而 operation 说 UNKNOWN。
                admission.raiseUnknown(operation.tenantId(), findRequestId(operationId), operationId,
                    "dispatch-without-attempt");
                return new SyncResult(operationId, "NO_RUN", "UNKNOWN");
            }
            return new SyncResult(operationId, "NO_RUN", operation.executionState());
        }
        String runnerRunId = attempt.get().runnerRunId();
        AssistantRunnerGateway.RunSnapshot snapshot;
        try {
            snapshot = gateway.fetchRun(runnerRunId);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new SyncResult(operationId, "QUERY_FAILED", operation.executionState());
        } catch (IOException | RuntimeException ex) {
            return new SyncResult(operationId, "QUERY_FAILED", operation.executionState());
        }
        String status = snapshot.status() == null ? "" : snapshot.status().toLowerCase(Locale.ROOT);
        String current = operation.executionState();

        // 从等待用户恢复的 operation 停在 PENDING，语义是「等待重新派发」：
        // 不能拿旧 attempt 的 runId 走崩溃修复把它推回 RUNNING，否则「不续跑旧代次」被绕过。
        if ("PENDING".equals(current) && repository.awaitingRedispatch(operationId)) {
            return new SyncResult(operationId, "AWAITING_REDISPATCH", current);
        }

        // 崩溃修复：createRun 成功后、markDispatched 前中断 ⇒ operation 仍是 PENDING，但 attempt 已有 runId。
        if ("PENDING".equals(current)) {
            repository.transitionExecution(operationId, "PENDING", "DISPATCHED");
            current = "DISPATCHED";
        }

        if (RUNNING_STATES.contains(status)) {
            if ("DISPATCHED".equals(current)) {
                repository.transitionExecution(operationId, "DISPATCHED", "RUNNING");
                return new SyncResult(operationId, status, "RUNNING");
            }
            return new SyncResult(operationId, status, current);
        }

        String terminal = terminalState(status, snapshot);
        if (terminal == null) {
            return new SyncResult(operationId, status, current);
        }
        if (AssistantAdmissionService.isTerminal(current)) {
            // 幂等修复：进程崩在 transition 与 resolve 之间时，后续 sync 也要把请求轴解冻。
            repository.resolveRequestUnknown(operationId);
            // 也补 attempt 收尾：否则会出现 executionState=SUCCEEDED 但 attempt 仍 RUNNING。
            String terminalNow = current;
            repository.listAttempts(operationId).stream()
                .filter(item -> item.endedAt() == null)
                .forEach(item -> repository.finishAttempt(item.attemptId(), terminalNow));
            return new SyncResult(operationId, status, current);
        }
        if ("DISPATCHED".equals(current)) {
            repository.transitionExecution(operationId, "DISPATCHED", "RUNNING");
        }
        boolean moved = repository.transitionExecution(operationId, "RUNNING", terminal);
        if (!moved && "UNKNOWN".equals(current)) {
            // 权威回读解除冻结：UNKNOWN→终态必须留 UNKNOWN_RESOLVED 事件。
            moved = repository.transitionExecution(operationId, "UNKNOWN", terminal);
            if (moved) {
                repository.appendReconciliation(operationId, attempt.get().attemptId(), "UNKNOWN_RESOLVED", null,
                    "{\"outcome\":\"" + terminal + "\",\"runnerRunId\":\""
                        + AssistantTaskService.jsonQuote(runnerRunId) + "\"}");
                // 请求轴也要解冻：否则请求永远停在 UNKNOWN，无法进入收尾。
                repository.resolveRequestUnknown(operationId);
            }
        }
        if (moved) {
            repository.recordRunSummary(attempt.get().attemptId(), snapshot.total(), snapshot.passed(),
                snapshot.failed(), snapshot.error());
            repository.finishAttempt(attempt.get().attemptId(), terminal);
            // 平台没有真正的清理 worker：不把「未尝试清理」写成 CLEANED，只登记待回收事实。
            repository.appendReconciliation(operationId, attempt.get().attemptId(), "MANUAL_DISPOSITION", null,
                "{\"reason\":\"resources-pending-cleanup\",\"outcome\":\"" + terminal + "\",\"runId\":\""
                    + AssistantTaskService.jsonQuote(runnerRunId) + "\"}");
        }
        return new SyncResult(operationId, status, moved ? terminal : current);
    }
}
