package com.haitlify.aitest.platform.assistant.ledger;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 受理与派发的前置判定（计划 §5.4 / A0 §5.7）。
 *
 * <p>三条不变量：
 * <ol>
 *   <li><b>先落库再派发</b>：{@link #admit(AdmissionCommand)} 在返回前已写入受理记录与 operation；</li>
 *   <li><b>同 ID 同输入幂等、同 ID 不同输入拒绝</b>：由 {@code uk_request} + digest 比较保证；</li>
 *   <li><b>UNKNOWN 冻结派发</b>：{@link #assertDispatchAllowed(String)} 在状态未知时拒绝新派发。</li>
 * </ol>
 */
@Service
public class AssistantAdmissionService {

    private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern ACTION = Pattern.compile("^[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9]*)+$");

    public record AdmissionCommand(long tenantId, long actorId, Long projectId, String requestId, String inputDigest,
                                   String actionCode, String targetRef, String targetRevision, String sessionBinding) {}

    public record AdmissionResult(String requestId, String operationId, String admissionState, String executionState,
                                  boolean created) {}

    /** 同 request ID 换了输入：不是重试，是另一个请求，必须显式拒绝而不是静默覆盖。 */
    public static class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException(String message) {
            super(message);
        }
    }

    /** UNKNOWN 期间的新派发一律拒绝（返回既有记录由调用方读取）。 */
    public static class DispatchFrozenException extends RuntimeException {
        public DispatchFrozenException(String message) {
            super(message);
        }
    }

    private final AssistantLedgerRepository repository;
    private final AssistantIdempotencyKeys keys;
    private final TransactionTemplate transaction;

    public AssistantAdmissionService(AssistantLedgerRepository repository, AssistantIdempotencyKeys keys,
                                     PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.keys = keys;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    public AdmissionResult admit(AdmissionCommand command) {
        validate(command);
        return transaction.execute(status -> admitInTransaction(command));
    }

    private AdmissionResult admitInTransaction(AdmissionCommand command) {
        String operationId = UUID.randomUUID().toString();
        // 键绑定 requestId 而不是随机 operationId：否则两个请求的键必然不同，uk_idem 永远不触发。
        String idempotencyKey = keys.derive(command.tenantId(), command.requestId(), command.targetRevision(),
            command.actionCode(), command.inputDigest());

        AssistantLedgerRepository.InsertOutcome request = repository.insertRequestIfAbsent(
            command.tenantId(), command.actorId(), command.projectId(), command.requestId(),
            command.inputDigest(), command.sessionBinding(), operationId);

        if (!request.created()) {
            AssistantLedgerRepository.RequestRecord existing = request.record();
            if (!existing.inputDigest().equalsIgnoreCase(command.inputDigest())) {
                throw new IdempotencyConflictException(
                    "同一 requestId 的输入摘要不一致：已受理 " + existing.inputDigest() + "，本次 "
                        + command.inputDigest() + "（拒绝覆盖）");
            }
            AssistantLedgerRepository.OperationRecord operation = existing.operationId() == null
                ? null
                : repository.findOperation(existing.operationId()).orElse(null);
            // 幂等重放必须「同一个人、同一授权、同一目标版本」；只比 digest 会把换授权当成重试。
            if (operation != null) {
                boolean sameContext = operation.tenantId() == command.tenantId()
                    && java.util.Objects.equals(operation.projectId(), command.projectId())
                    && operation.actionCode().equals(command.actionCode())
                    && operation.targetRef().equals(command.targetRef())
                    && operation.targetRevision().equals(command.targetRevision())
                    && existing.actorId() == command.actorId();
                if (!sameContext) {
                    throw new IdempotencyConflictException(
                        "同一 requestId 的授权上下文不一致（actor/action/target/revision），拒绝当作重放");
                }
            }
            return new AdmissionResult(existing.requestId(), existing.operationId(), existing.admissionState(),
                operation == null ? null : operation.executionState(), false);
        }

        AssistantLedgerRepository.InsertOperationOutcome created = repository.createOperation(operationId,
            command.tenantId(), command.projectId(), command.actionCode(), command.targetRef(),
            command.targetRevision(), command.inputDigest(), idempotencyKey);
        if (!created.created() && !created.record().operationId().equals(operationId)) {
            // 幂等键撞上了另一个 operation：request 行已写入本 operationId，必须整体回滚而不是留下悬挂行。
            throw new IdempotencyConflictException(
                "幂等键已被 operation " + created.record().operationId() + " 占用，拒绝生成新 operation");
        }
        return new AdmissionResult(command.requestId(), operationId, "ADMITTED", "PENDING", true);
    }

    /** 派发前必须调用：UNKNOWN / 不存在 / 终态都不允许再派发。 */
    public AssistantLedgerRepository.OperationRecord assertDispatchAllowed(String operationId) {
        AssistantLedgerRepository.OperationRecord operation = repository.findOperation(operationId)
            .orElseThrow(() -> new DispatchFrozenException("operation 不存在，拒绝派发：" + operationId));
        if ("UNKNOWN".equals(operation.executionState())) {
            throw new DispatchFrozenException(
                "operation 处于 UNKNOWN，副作用派发已冻结；需先回读或人工处置留痕（operationId=" + operationId + "）");
        }
        if (isTerminal(operation.executionState())) {
            throw new DispatchFrozenException(
                "operation 已是终态 " + operation.executionState() + "，重复执行必须新建授权 operation");
        }
        return operation;
    }

    /** 派发已提交：只把请求轴推到 DISPATCHED（operation 的占用由 dispatch 的 CAS 负责）。 */
    public boolean markDispatched(long tenantId, String requestId, String operationId) {
        return repository.markRequestDispatched(tenantId, requestId, operationId);
    }

    /** 受理结果不可判定：受理记录与 operation 同时冻结，并追加 UNKNOWN_RAISED 事件。 */
    public void raiseUnknown(long tenantId, String requestId, String operationId, String reason) {
        boolean frozen = repository.transitionExecution(operationId, "RUNNING", "UNKNOWN")
            || repository.transitionExecution(operationId, "DISPATCHED", "UNKNOWN")
            || repository.transitionExecution(operationId, "PENDING", "UNKNOWN");
        if (!frozen) {
            AssistantLedgerRepository.OperationRecord current = repository.findOperation(operationId).orElse(null);
            if (current != null && isTerminal(current.executionState())) {
                // 已经落到终态：不回退，只留痕；请求轴也不该被改成 UNKNOWN。
                repository.appendReconciliation(operationId, null, "LATE_RECEIPT", null,
                    "{\"reason\":" + quote(reason) + ",\"state\":\"" + current.executionState() + "\"}");
                return;
            }
        }
        repository.markRequestUnknown(tenantId, requestId);
        repository.appendReconciliation(operationId, null, "UNKNOWN_RAISED", null,
            "{\"reason\":" + quote(reason) + "}");
    }

    public static boolean isTerminal(String state) {
        return "SUCCEEDED".equals(state) || "FAILED".equals(state) || "CANCELLED".equals(state);
    }

    private static void validate(AdmissionCommand command) {
        if (command.tenantId() <= 0 || command.actorId() <= 0) {
            throw new IllegalArgumentException("tenantId/actorId 必须是正数");
        }
        if (command.projectId() != null && command.projectId() <= 0) {
            throw new IllegalArgumentException("projectId 必须是正数或 null");
        }
        requireUuid(command.requestId(), "requestId");
        if (command.sessionBinding() != null) {
            requireUuid(command.sessionBinding(), "sessionBinding");
        }
        if (command.inputDigest() == null || !DIGEST.matcher(command.inputDigest().toLowerCase(Locale.ROOT)).matches()) {
            throw new IllegalArgumentException("inputDigest 必须是 64 位十六进制 sha256");
        }
        if (command.actionCode() == null || !ACTION.matcher(command.actionCode()).matches()) {
            throw new IllegalArgumentException("actionCode 必须形如 web.case.execute（小写点分，至少两段）");
        }
        // targetRef 必须限定在本次项目作用域内，且不能是 URL（A0 开放项）。
        com.haitlify.aitest.platform.assistant.security.AssistantTargetRef
            .validate(command.targetRef(), command.projectId());
        if (command.targetRevision() == null || command.targetRevision().isBlank()) {
            throw new IllegalArgumentException("targetRevision 不能为空：授权必须绑定对象版本");
        }
    }

    private static void requireUuid(String value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(field + " 必须是 UUID 字符串：" + value, ex);
        }
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        // 异常消息里常有换行/制表符：不转义会让 detail_json 直接变成非法 JSON。
        StringBuilder out = new StringBuilder(value.length() + 16).append('"');
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
        return out.append('"').toString();
    }
}
