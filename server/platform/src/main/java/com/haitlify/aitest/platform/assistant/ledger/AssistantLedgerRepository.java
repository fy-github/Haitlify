package com.haitlify.aitest.platform.assistant.ledger;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 受理账本的读写（A0 §5.7 五表）。所有写入都按「先落库再派发」的顺序，且状态迁移只走
 * {@link #transitionExecution(String, String, String)} 的 CAS 更新——不允许把终态改回去。
 */
@Repository
public class AssistantLedgerRepository {

    public record RequestRecord(long tenantId, long actorId, Long projectId, String requestId, String inputDigest,
                                String sessionBinding, String admissionState, String operationId, Instant createdAt) {}

    public record OperationRecord(String operationId, long tenantId, Long projectId, String actionCode, String targetRef,
                                  String targetRevision, String inputDigest, String idempotencyKey,
                                  String executionState, String reportState, int dispatchGeneration) {}

    public record ResourceRecord(String resourceId, String operationId, String resourceKind, long ownerActorId,
                                 String cleanupExecutor, String cleanupState, Instant deadlineAt, int attempts,
                                 Long cleanedBy, Instant cleanedAt) {}

    /** 状态迁移的合法边（A0 §5.5）；终态不在表里 ⇒ 任何迁移都被拒绝。 */
    static final List<String[]> ALLOWED_TRANSITIONS = List.of(
        new String[] {"PENDING", "DISPATCHED"},
        new String[] {"DISPATCHED", "RUNNING"},
        // 已占用但提交结果不可判定：允许从 DISPATCHED 冻结。
        new String[] {"DISPATCHED", "UNKNOWN"},
        new String[] {"RUNNING", "SUCCEEDED"},
        new String[] {"RUNNING", "FAILED"},
        new String[] {"RUNNING", "CANCELLED"},
        new String[] {"RUNNING", "UNKNOWN"},
        // 派发提交阶段就不可判定（连接失败/5xx）：operation 还停在 PENDING，也必须能冻结。
        new String[] {"PENDING", "UNKNOWN"},
        // 从未派发：只能判失败/取消，绝不能判成功（没有执行证据）。
        new String[] {"PENDING", "FAILED"},
        new String[] {"PENDING", "CANCELLED"},
        // 等待用户（MFA/人工登录/缺输入）：非终态，不能生成报告，也不能被回执推进。
        new String[] {"PENDING", "WAITING_USER"},
        new String[] {"DISPATCHED", "WAITING_USER"},
        new String[] {"RUNNING", "WAITING_USER"},
        // 用户补齐后重新回到 PENDING：必须由新的受权派发推进，不直接续跑旧代次。
        new String[] {"WAITING_USER", "PENDING"},
        // 用户放弃或等待超时：记 CANCELLED（从未证明执行过）。
        new String[] {"WAITING_USER", "CANCELLED"},
        // 等待超时但仍有未收尾的 Runner run：不能写「已取消」，冻结为 UNKNOWN 交对账/人工回读。
        new String[] {"WAITING_USER", "UNKNOWN"},
        // 停止请求送达前 run 已自然结束：如实记真实结果，不把成功写成取消。
        new String[] {"WAITING_USER", "SUCCEEDED"},
        new String[] {"WAITING_USER", "FAILED"},
        new String[] {"UNKNOWN", "SUCCEEDED"},
        new String[] {"UNKNOWN", "FAILED"},
        new String[] {"UNKNOWN", "CANCELLED"}
    );

    private final JdbcTemplate jdbcTemplate;

    public AssistantLedgerRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ── 受理记录 ────────────────────────────────────────────────────────────────

    /** operation → requestId 反查（崩溃窗口对账用）。 */
    public Optional<String> findRequestIdByOperation(String operationId) {
        java.util.List<String> rows = jdbcTemplate.query(
            "select request_id from assistant_request_ledger where operation_id = ? order by id desc limit 1",
            (rs, i) -> rs.getString("request_id"), operationId);
        return rows.stream().findFirst();
    }

    public Optional<RequestRecord> findRequest(long tenantId, String requestId) {
        List<RequestRecord> rows = jdbcTemplate.query(
            "select tenant_id, actor_id, project_id, request_id, input_digest, session_binding,"
                + " admission_state, operation_id, created_at from assistant_request_ledger"
                + " where tenant_id = ? and request_id = ?",
            (rs, i) -> mapRequest(rs), tenantId, requestId);
        return rows.stream().findFirst();
    }

    /** 插入受理记录；唯一约束冲突时返回已有行，由调用方比较输入 digest。 */
    public InsertOutcome insertRequestIfAbsent(long tenantId, long actorId, Long projectId, String requestId,
                                               String inputDigest, String sessionBinding, String operationId) {
        try {
            jdbcTemplate.update(
                "insert into assistant_request_ledger(tenant_id, actor_id, project_id, request_id, input_digest,"
                    + " session_binding, admission_state, operation_id, created_at, updated_at)"
                    + " values (?, ?, ?, ?, ?, ?, 'ADMITTED', ?, ?, ?)",
                tenantId, actorId, projectId, requestId, inputDigest, sessionBinding, operationId,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
            return new InsertOutcome(true, findRequest(tenantId, requestId).orElseThrow());
        } catch (DuplicateKeyException duplicate) {
            return new InsertOutcome(false, findRequest(tenantId, requestId).orElseThrow());
        }
    }

    /** CAS：只有 ADMITTED 能进入 DISPATCHED；已 UNKNOWN/CLOSED 的请求不得被覆盖。 */
    public boolean markRequestDispatched(long tenantId, String requestId, String operationId) {
        return jdbcTemplate.update(
            "update assistant_request_ledger set admission_state = 'DISPATCHED', operation_id = ?, updated_at = ?"
                + " where tenant_id = ? and request_id = ? and admission_state = 'ADMITTED'",
            operationId, Timestamp.from(Instant.now()), tenantId, requestId) == 1;
    }

    /** CAS：CLOSED 是终态，不能被 UNKNOWN 覆盖。 */
    public boolean markRequestUnknown(long tenantId, String requestId) {
        return jdbcTemplate.update(
            "update assistant_request_ledger set admission_state = 'UNKNOWN', updated_at = ?"
                + " where tenant_id = ? and request_id = ? and admission_state in ('ADMITTED', 'DISPATCHED')",
            Timestamp.from(Instant.now()), tenantId, requestId) == 1;
    }

    /** 权威回读把 UNKNOWN 解开后，请求轴必须跟着回到可收尾状态，否则永远停在 UNKNOWN。 */
    public boolean resolveRequestUnknown(String operationId) {
        return jdbcTemplate.update(
            "update assistant_request_ledger set admission_state = 'DISPATCHED', updated_at = ?"
                + " where operation_id = ? and admission_state = 'UNKNOWN'",
            Timestamp.from(Instant.now()), operationId) == 1;
    }

    /** 行锁：decide/generate 必须串行化，否则跨快照 check-then-act 仍能接受过期修订。 */
    public java.util.Optional<OperationRecord> findOperationForUpdate(String operationId) {
        java.util.List<OperationRecord> rows = jdbcTemplate.query(
            "select * from assistant_operation_ledger where operation_id = ? for update",
            (rs, i) -> mapOperation(rs), operationId);
        return rows.stream().findFirst();
    }


    /** 全部资源 CLEANED 才算完整闭环；存在 UNRECOVERABLE 只能记「闭环但有未结事项」。 */
    public boolean closeRequest(long tenantId, String requestId, String terminalState) {
        return jdbcTemplate.update(
            "update assistant_request_ledger set admission_state = ?, updated_at = ?"
                + " where tenant_id = ? and request_id = ?"
                + " and admission_state not in ('CLOSED', 'CLOSED_WITH_UNRESOLVED')",
            terminalState, Timestamp.from(Instant.now()), tenantId, requestId) == 1;
    }

    public record InsertOutcome(boolean created, RequestRecord record) {}

    // ── 业务操作 ────────────────────────────────────────────────────────────────

    public Optional<OperationRecord> findOperation(String operationId, long tenantId) {
        List<OperationRecord> rows = jdbcTemplate.query(
            "select operation_id, tenant_id, project_id, action_code, target_ref, target_revision, input_digest,"
                + " idempotency_key, execution_state, report_state, dispatch_generation"
                + " from assistant_operation_ledger where operation_id = ? and tenant_id = ?",
            (rs, i) -> mapOperation(rs), operationId, tenantId);
        return rows.stream().findFirst();
    }

    public Optional<OperationRecord> findOperation(String operationId) {
        List<OperationRecord> rows = jdbcTemplate.query(
            "select operation_id, tenant_id, project_id, action_code, target_ref, target_revision, input_digest,"
                + " idempotency_key, execution_state, report_state, dispatch_generation"
                + " from assistant_operation_ledger where operation_id = ?",
            (rs, i) -> mapOperation(rs), operationId);
        return rows.stream().findFirst();
    }

    public Optional<OperationRecord> findOperationByIdempotencyKey(long tenantId, String idempotencyKey) {
        List<OperationRecord> rows = jdbcTemplate.query(
            "select operation_id, tenant_id, project_id, action_code, target_ref, target_revision, input_digest,"
                + " idempotency_key, execution_state, report_state, dispatch_generation"
                + " from assistant_operation_ledger where tenant_id = ? and idempotency_key = ?",
            (rs, i) -> mapOperation(rs), tenantId, idempotencyKey);
        return rows.stream().findFirst();
    }

    /** 建 operation；键冲突说明同一授权动作已被受理过，返回既有一行（不新建代次）。 */
    public InsertOperationOutcome createOperation(String operationId, long tenantId, Long projectId, String actionCode,
                                                  String targetRef, String targetRevision, String inputDigest,
                                                  String idempotencyKey) {
        try {
            insertOperationRow(operationId, tenantId, projectId, actionCode, targetRef, targetRevision, inputDigest,
                idempotencyKey);
            return new InsertOperationOutcome(true, findOperation(operationId).orElseThrow());
        } catch (DuplicateKeyException duplicate) {
            Optional<OperationRecord> byKey = findOperationByIdempotencyKey(tenantId, idempotencyKey);
            if (byKey.isPresent()) {
                return new InsertOperationOutcome(false, byKey.get());
            }
            // operation_id 冲突（同 ID 复用）：返回既有行，由调用方判定是否同授权。
            return new InsertOperationOutcome(false, findOperation(operationId).orElseThrow());
        }
    }

    /** 记录 operation 执行的确认草稿（审计追溯 + 判断草稿是否已被执行）。 */
    public boolean bindOperationDraft(String operationId, String draftId) {
        return jdbcTemplate.update(
            "update assistant_operation_ledger set draft_id = ? where operation_id = ?",
            draftId, operationId) == 1;
    }

    /** 报告要回溯「这次执行对应哪份草稿、第几版修订」。 */
    public java.util.Optional<String> findDraftIdForOperation(String operationId) {
        java.util.List<String> ids = jdbcTemplate.queryForList(
            "select draft_id from assistant_operation_ledger where operation_id = ? and draft_id is not null",
            String.class, operationId);
        return ids.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(ids.get(0));
    }

    public boolean hasOperationForDraft(String draftId) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from assistant_operation_ledger where draft_id = ?", Integer.class, draftId);
        return count != null && count > 0;
    }

    /** 包内可见：允许测试构造「同一幂等键 + 不同 operation_id」的绕过尝试。 */
    void insertOperationRow(String operationId, long tenantId, Long projectId, String actionCode, String targetRef,
                            String targetRevision, String inputDigest, String idempotencyKey) {
        jdbcTemplate.update(
            "insert into assistant_operation_ledger(operation_id, tenant_id, project_id, action_code, target_ref,"
                + " target_revision, input_digest, idempotency_key, execution_state, report_state, dispatch_generation,"
                + " created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 'NONE', 0, ?, ?)",
            operationId, tenantId, projectId, actionCode, targetRef, targetRevision, inputDigest, idempotencyKey,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
    }

    public record InsertOperationOutcome(boolean created, OperationRecord record) {}

    /**
     * CAS 状态迁移：只有 {@code from → to} 在允许边上且当前状态确为 {@code from} 时才更新成功。
     * 终态（SUCCEEDED/FAILED/CANCELLED）没有任何出边 ⇒ 不会被后续回执覆盖。
     */
    public boolean transitionExecution(String operationId, String from, String to) {
        if (ALLOWED_TRANSITIONS.stream().noneMatch(edge -> edge[0].equals(from) && edge[1].equals(to))) {
            return false;
        }
        // 真正派发（PENDING→DISPATCHED）即消费掉「等待重新派发」标记。
        boolean dispatching = "PENDING".equals(from) && "DISPATCHED".equals(to);
        // 终态不再携带「等待中」的时间信息（否则报告/列表会出现「已取消但仍显示截止时间」）；
        // 但保留 waiting_reason：审计要看得出「当时在等什么」，`describe` 也只在终态隐藏时间。
        boolean terminal = "SUCCEEDED".equals(to) || "FAILED".equals(to) || "CANCELLED".equals(to);
        int updated = jdbcTemplate.update(
            "update assistant_operation_ledger set execution_state = ?, updated_at = ?"
                + (dispatching ? ", awaiting_redispatch = FALSE" : "")
                + (terminal ? ", waiting_requested_at = NULL, waiting_deadline_at = NULL" : "")
                + " where operation_id = ? and execution_state = ?",
            to, Timestamp.from(Instant.now()), operationId, from);
        return updated == 1;
    }

    /** 读 updated_at，用于识别「占用了但从未落 attempt」的崩溃窗口。 */
    public java.util.Optional<java.time.Instant> operationUpdatedAt(String operationId) {
        java.util.List<java.time.Instant> rows = jdbcTemplate.query(
            "select updated_at from assistant_operation_ledger where operation_id = ?",
            (rs, i) -> rs.getTimestamp("updated_at").toInstant(), operationId);
        return rows.stream().findFirst();
    }

    public record WaitingInfo(String reason, Instant requestedAt, Instant deadlineAt) {}

    /** 进入等待：只有 allowed edge 且当前状态匹配才生效，同时写入原因与到期时间。 */
    public boolean markWaitingUser(String operationId, String from, String reason,
                                   Instant requestedAt, Instant deadlineAt) {
        if (ALLOWED_TRANSITIONS.stream().noneMatch(edge -> edge[0].equals(from) && edge[1].equals("WAITING_USER"))) {
            return false;
        }
        return jdbcTemplate.update(
            "update assistant_operation_ledger set execution_state = 'WAITING_USER', waiting_reason = ?,"
                + " waiting_requested_at = ?, waiting_deadline_at = ?, updated_at = ?"
                + " where operation_id = ? and execution_state = ?",
            reason, Timestamp.from(requestedAt), Timestamp.from(deadlineAt), Timestamp.from(Instant.now()),
            operationId, from) == 1;
    }

    public Optional<WaitingInfo> waitingInfo(String operationId) {
        List<WaitingInfo> rows = jdbcTemplate.query(
            "select waiting_reason, waiting_requested_at, waiting_deadline_at from assistant_operation_ledger"
                + " where operation_id = ?",
            (rs, i) -> new WaitingInfo(rs.getString("waiting_reason"),
                rs.getTimestamp("waiting_requested_at") == null ? null
                    : rs.getTimestamp("waiting_requested_at").toInstant(),
                rs.getTimestamp("waiting_deadline_at") == null ? null
                    : rs.getTimestamp("waiting_deadline_at").toInstant()),
            operationId);
        return rows.stream().findFirst();
    }

    /**
     * 用户主动取消等待：转 CANCELLED 并保留原因与请求时间（审计要看得出「等过、为什么放弃」）。
     * 到期时间保留原值，便于对账时区分「提前放弃」与「超时」。
     */
    public boolean cancelWaitingUser(String operationId, String reason) {
        return jdbcTemplate.update(
            "update assistant_operation_ledger set execution_state = 'CANCELLED', waiting_reason = ?,"
                + " updated_at = ? where operation_id = ? and execution_state = 'WAITING_USER'",
            reason, Timestamp.from(Instant.now()), operationId) == 1;
    }

    /** 离开等待：清空等待字段并回到目标状态（CAS：仍处于 WAITING_USER 才生效）。 */
    public boolean leaveWaitingUser(String operationId, String to) {
        // 只允许「用户补齐（PENDING）」与「用户放弃（CANCELLED）」两条显式路径；
        // UNKNOWN 由到期清扫内部直接写，不对调用方开放，避免误把等待当成「不可判定」。
        if (!"PENDING".equals(to) && !"CANCELLED".equals(to)) {
            return false;
        }
        // 回到 PENDING 时必须留下「等待重新派发」的事实标记：等待字段已清空，
        // 否则 /sync 会拿旧 attempt 的 runId 把 PENDING 推回执行（复核 D9）。
        boolean backToPending = "PENDING".equals(to);
        return jdbcTemplate.update(
            "update assistant_operation_ledger set execution_state = ?, waiting_reason = NULL,"
                + " waiting_requested_at = NULL, waiting_deadline_at = NULL,"
                + " awaiting_redispatch = ?, updated_at = ?"
                + " where operation_id = ? and execution_state = 'WAITING_USER'",
            to, backToPending, Timestamp.from(Instant.now()), operationId) == 1;
    }

    /** 该 operation 是否处于「等待重新派发」：只有真正派发才会清掉。 */
    public boolean awaitingRedispatch(String operationId) {
        List<Boolean> rows = jdbcTemplate.query(
            "select awaiting_redispatch from assistant_operation_ledger where operation_id = ?",
            (rs, i) -> rs.getBoolean("awaiting_redispatch"), operationId);
        return rows.stream().findFirst().orElse(false);
    }

    /**
     * 到期等待一律转 CANCELLED，并保留原因（不删除证据，便于对账与报告说明）。
     * 拼接在 Java 侧完成：`||` 在 MySQL 与 H2-MySQL 模式下是逻辑或，跨库不可移植。
     */
    public List<String> expireOverdueWaitingUser(Instant now) {
        return expireOverdueWaitingUser(now, null);
    }

    /**
     * 到期清扫。tenantId 为 null 时覆盖所有租户（定时任务用）；显式传租户时只清该租户（HTTP 入口用）。
     * 单行失败不中断整轮：一条坏数据不该让其它过期等待继续悬挂。
     */
    public List<String> expireOverdueWaitingUser(Instant now, Long tenantId) {
        StringBuilder sql = new StringBuilder(
            "select operation_id, waiting_reason from assistant_operation_ledger"
                + " where execution_state = 'WAITING_USER' and waiting_deadline_at is not null"
                + " and waiting_deadline_at < ?");
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(Timestamp.from(now));
        if (tenantId != null) {
            sql.append(" and tenant_id = ?");
            args.add(tenantId);
        }
        List<String[]> rows = jdbcTemplate.query(sql.toString(),
            (rs, i) -> new String[] {rs.getString("operation_id"), rs.getString("waiting_reason")},
            args.toArray());
        List<String> expired = new java.util.ArrayList<>();
        for (String[] row : rows) {
            String reason = timeoutReason(row[1]);
            // 与 D3 同一纪律：还有未收尾 run 就不能写「已取消」，冻结 UNKNOWN 交对账/人工回读。
            String target = hasUnfinishedRun(row[0]) ? "UNKNOWN" : "CANCELLED";
            try {
                int updated = jdbcTemplate.update(
                    "update assistant_operation_ledger set execution_state = ?,"
                        + " waiting_reason = ?, updated_at = ?"
                        + " where operation_id = ? and execution_state = 'WAITING_USER'",
                    target, reason, Timestamp.from(now), row[0]);
                if (updated == 1) {
                    expired.add(row[0]);
                }
            } catch (org.springframework.dao.DataAccessException ex) {
                // 不能因为一条写失败就跳过后续行；下一轮清扫会重试这一条。
                java.util.logging.Logger.getLogger(AssistantLedgerRepository.class.getName())
                    .warning("到期等待清扫失败：" + row[0] + " -> " + ex.getMessage());
            }
        }
        return expired;
    }

    /** 是否存在「有 runId 且未收尾」的 attempt：决定超时后写 CANCELLED 还是 UNKNOWN。 */
    boolean hasUnfinishedRun(String operationId) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from assistant_attempt_ledger"
                + " where operation_id = ? and runner_run_id is not null and ended_at is null",
            Integer.class, operationId);
        return count != null && count > 0;
    }

    /** `waiting_reason` 是 VARCHAR(500)：拼接后缀时必须截断原文，不能让它超长抛错。 */
    static String timeoutReason(String base) {
        String suffix = " [waiting_timeout]";
        if (base == null || base.isBlank()) {
            return "waiting_timeout";
        }
        int max = 500 - suffix.length();
        String trimmed = base.length() > max ? base.substring(0, max) : base;
        return trimmed + suffix;
    }

    public void bumpDispatchGeneration(String operationId) {
        jdbcTemplate.update(
            "update assistant_operation_ledger set dispatch_generation = dispatch_generation + 1, updated_at = ?"
                + " where operation_id = ?",
            Timestamp.from(Instant.now()), operationId);
    }

    /** 新修订生成后重新打开决策：任何状态 → AWAITING（必须重新人工确认）。 */
    public boolean reopenReportState(String operationId) {
        return jdbcTemplate.update(
            "update assistant_operation_ledger set report_state = 'AWAITING', updated_at = ?"
                + " where operation_id = ? and report_state in ('NONE', 'AWAITING', 'REJECTED')",
            Timestamp.from(Instant.now()), operationId) == 1;
    }

    /** CAS：只有 NONE/AWAITING 能被接受或驳回；已决策的不能反复翻转。 */
    public boolean setReportState(String operationId, String reportState) {
        return jdbcTemplate.update(
            "update assistant_operation_ledger set report_state = ?, updated_at = ?"
                + " where operation_id = ? and report_state in ('NONE', 'AWAITING')",
            reportState, Timestamp.from(Instant.now()), operationId) == 1;
    }

    /** UNKNOWN 期间冻结副作用派发：调用方据此拒绝新派发（A0 §5.5）。 */
    public boolean isDispatchFrozen(String operationId) {
        return findOperation(operationId).map(op -> "UNKNOWN".equals(op.executionState())).orElse(true);
    }

    // ── 资源账本 ────────────────────────────────────────────────────────────────

    public void recordResource(String resourceId, String operationId, String resourceKind, long ownerActorId,
                               String cleanupExecutor, Instant deadlineAt) {
        jdbcTemplate.update(
            "insert into assistant_resource_ledger(resource_id, operation_id, resource_kind, owner_actor_id,"
                + " cleanup_executor, cleanup_state, deadline_at, attempts) values (?, ?, ?, ?, ?, 'ALLOCATED', ?, 0)",
            resourceId, operationId, resourceKind, ownerActorId, cleanupExecutor, Timestamp.from(deadlineAt));
    }

    /**
     * 只能从「还在手上」的状态迁到 CLEANED：已判失败/需人工的资源不能靠补一条 CLEANED 抹掉。
     * 返回 false 表示当前状态不允许这次迁移（调用方必须据此报错，而不是静默成功）。
     */
    public boolean markResourceCleaned(String resourceId, String evidenceJson, Long actorId) {
        return jdbcTemplate.update(
            "update assistant_resource_ledger set cleanup_state = 'CLEANED', evidence_json = ?,"
                + " cleanup_executor = 'USER', cleaned_by = ?, cleaned_at = ?"
                + " where resource_id = ? and cleanup_state in ('ALLOCATED', 'CLEANING', 'MANUAL_REQUIRED')",
            evidenceJson, actorId, Timestamp.from(Instant.now()), resourceId) == 1;
    }

    /** 人工判定不可恢复：只从 MANUAL_REQUIRED/CLEANUP_FAILED 迁出，并留原因。 */
    public boolean markResourceUnrecoverable(String resourceId, String reason, Long actorId) {
        return jdbcTemplate.update(
            "update assistant_resource_ledger set cleanup_state = 'UNRECOVERABLE', cleanup_executor = 'MANUAL',"
                + " cleaned_by = ?, cleaned_at = ?,"
                + " evidence_json = concat(coalesce(evidence_json, ''), ' | UNRECOVERABLE: ', ?)"
                + " where resource_id = ? and cleanup_state in ('MANUAL_REQUIRED', 'CLEANUP_FAILED')",
            actorId, Timestamp.from(Instant.now()), reason, resourceId) == 1;
    }

    public void markResourceCleanupFailed(String resourceId, String evidenceJson) {
        jdbcTemplate.update(
            "update assistant_resource_ledger set cleanup_state = 'CLEANUP_FAILED', attempts = attempts + 1,"
                + " evidence_json = ? where resource_id = ? and cleanup_state in ('ALLOCATED', 'CLEANING')",
            evidenceJson, resourceId);
    }

    /**
     * 执行结束后由平台侧收尾：只把「尚未尝试清理」的资源标记 CLEANED。
     * CLEANUP_FAILED / MANUAL_REQUIRED / UNRECOVERABLE 一律不动——清理失败不能被终态洗成闭环成功。
     */
    public int markResourcesCleaned(String operationId, String evidenceJson) {
        return jdbcTemplate.update(
            "update assistant_resource_ledger set cleanup_state = 'CLEANED', evidence_json = ?"
                + " where operation_id = ? and cleanup_state in ('ALLOCATED', 'CLEANING')",
            evidenceJson, operationId);
    }

    public List<ResourceRecord> listResources(String operationId) {
        return jdbcTemplate.query(
            "select resource_id, operation_id, resource_kind, owner_actor_id, cleanup_executor, cleanup_state,"
                + " deadline_at, attempts, cleaned_by, cleaned_at from assistant_resource_ledger"
                + " where operation_id = ? order by id asc",
            (rs, i) -> new ResourceRecord(rs.getString("resource_id"), rs.getString("operation_id"),
                rs.getString("resource_kind"), rs.getLong("owner_actor_id"), rs.getString("cleanup_executor"),
                rs.getString("cleanup_state"), rs.getTimestamp("deadline_at").toInstant(), rs.getInt("attempts"),
                (Long) rs.getObject("cleaned_by"),
                rs.getTimestamp("cleaned_at") == null ? null : rs.getTimestamp("cleaned_at").toInstant()),
            operationId);
    }

    /** 到期仍未回收：升级为「需人工处理」，不再假装系统会兜底。 */
    public boolean markResourceManualRequired(String resourceId, String evidenceJson) {
        return jdbcTemplate.update(
            "update assistant_resource_ledger set cleanup_state = 'MANUAL_REQUIRED', attempts = attempts + 1,"
                + " evidence_json = ? where resource_id = ? and cleanup_state in ('ALLOCATED', 'CLEANING', 'CLEANUP_FAILED')",
            evidenceJson, resourceId) == 1;
    }

    /** 可收尾的请求：operation 已终态、报告已接受、且没有未回收资源。 */
    public List<RequestRecord> findRequestsByState(String state) {
        return jdbcTemplate.query(
            "select tenant_id, actor_id, project_id, request_id, input_digest, session_binding,"
                + " admission_state, operation_id, created_at from assistant_request_ledger where admission_state = ?"
                + " order by created_at asc limit 1000",
            (rs, i) -> new RequestRecord(rs.getLong("tenant_id"), rs.getLong("actor_id"),
                (Long) rs.getObject("project_id"), rs.getString("request_id"), rs.getString("input_digest"),
                rs.getString("session_binding"), rs.getString("admission_state"), rs.getString("operation_id"),
                rs.getTimestamp("created_at").toInstant()),
            state);
    }

    /** 返回 [tenantId, requestId, terminalState]：有 UNRECOVERABLE 的请求只能 CLOSED_WITH_UNRESOLVED。 */
    public List<String[]> findRequestsReadyToClose(int limit) {
        return jdbcTemplate.query(
            "select r.tenant_id, r.request_id,"
                + " case when exists (select 1 from assistant_resource_ledger u"
                + "   where u.operation_id = o.operation_id and u.cleanup_state = 'UNRECOVERABLE')"
                + "   or not exists (select 1 from assistant_attempt_ledger a"
                + "     where a.operation_id = o.operation_id)"
                + "   then 'CLOSED_WITH_UNRESOLVED' else 'CLOSED' end as terminal_state"
                + " from assistant_request_ledger r"
                + " join assistant_operation_ledger o on o.operation_id = r.operation_id"
                + " where r.admission_state in ('ADMITTED', 'DISPATCHED')"
                + " and o.execution_state in ('SUCCEEDED', 'FAILED', 'CANCELLED')"
                + " and o.report_state = 'ACCEPTED'"
                + " and not exists (select 1 from assistant_resource_ledger s"
                + "   where s.operation_id = o.operation_id and s.cleanup_state not in ('CLEANED', 'UNRECOVERABLE'))"
                + " limit ?",
            (rs, i) -> new String[] {rs.getString("tenant_id"), rs.getString("request_id"),
                rs.getString("terminal_state")}, limit);
    }

    public List<ResourceRecord> findUnreclaimed(Instant now) {
        return jdbcTemplate.query(
            "select resource_id, operation_id, resource_kind, owner_actor_id, cleanup_executor, cleanup_state,"
                + " deadline_at, attempts, cleaned_by, cleaned_at from assistant_resource_ledger"
                + " where cleanup_state not in ('CLEANED', 'UNRECOVERABLE') and deadline_at <= ?",
            (rs, i) -> new ResourceRecord(rs.getString("resource_id"), rs.getString("operation_id"),
                rs.getString("resource_kind"), rs.getLong("owner_actor_id"), rs.getString("cleanup_executor"),
                rs.getString("cleanup_state"), rs.getTimestamp("deadline_at").toInstant(), rs.getInt("attempts"),
                (Long) rs.getObject("cleaned_by"),
                rs.getTimestamp("cleaned_at") == null ? null : rs.getTimestamp("cleaned_at").toInstant()),
            Timestamp.from(now));
    }

    // ── 派发尝试 ────────────────────────────────────────────────────────────────

    public record AttemptRecord(String attemptId, String operationId, int generation, String hermesStoredSessionId,
                                String hermesTurnRef, String runnerRunId, Instant dispatchedAt, Instant endedAt,
                                String outcome, Integer runTotal, Integer runPassed, Integer runFailed,
                                Integer runError) {}

    public void insertAttempt(String attemptId, String operationId, int generation, Instant dispatchedAt) {
        jdbcTemplate.update(
            "insert into assistant_attempt_ledger(attempt_id, operation_id, generation, dispatched_at, outcome)"
                + " values (?, ?, ?, ?, 'RUNNING')",
            attemptId, operationId, generation, Timestamp.from(dispatchedAt));
    }

    public void bindAttemptRun(String attemptId, String runnerRunId) {
        jdbcTemplate.update("update assistant_attempt_ledger set runner_run_id = ? where attempt_id = ?",
            runnerRunId, attemptId);
    }

    public void bindAttemptHermes(String attemptId, String storedSessionId, String turnRef) {
        jdbcTemplate.update(
            "update assistant_attempt_ledger set hermes_stored_session_id = ?, hermes_turn_ref = ? where attempt_id = ?",
            storedSessionId, turnRef, attemptId);
    }

    /** 把 Runner 的 summary 落到 attempt：报告要能自证「跑了几条、过了几条」。 */
    public boolean recordRunSummary(String attemptId, int total, int passed, int failed, int error) {
        return jdbcTemplate.update(
            "update assistant_attempt_ledger set run_total = ?, run_passed = ?, run_failed = ?, run_error = ?"
                + " where attempt_id = ?",
            total, passed, failed, error, attemptId) == 1;
    }

    public void finishAttempt(String attemptId, String outcome) {
        jdbcTemplate.update("update assistant_attempt_ledger set outcome = ?, ended_at = ? where attempt_id = ?",
            outcome, Timestamp.from(Instant.now()), attemptId);
    }

    public Optional<AttemptRecord> findAttempt(String attemptId) {
        List<AttemptRecord> rows = jdbcTemplate.query(
            "select attempt_id, operation_id, generation, hermes_stored_session_id, hermes_turn_ref, runner_run_id,"
                + " dispatched_at, ended_at, outcome from assistant_attempt_ledger where attempt_id = ?",
            (rs, i) -> mapAttempt(rs), attemptId);
        return rows.stream().findFirst();
    }

    public List<AttemptRecord> listAttempts(String operationId) {
        return jdbcTemplate.query(
            "select attempt_id, operation_id, generation, hermes_stored_session_id, hermes_turn_ref, runner_run_id,"
                + " dispatched_at, ended_at, outcome, run_total, run_passed, run_failed, run_error"
                + " from assistant_attempt_ledger where operation_id = ?"
                + " order by generation asc",
            (rs, i) -> mapAttempt(rs), operationId);
    }

    // ── 对账事件（只追加） ───────────────────────────────────────────────────────

    public void appendReconciliation(String operationId, String attemptId, String eventType, Long actorId,
                                     String detailJson) {
        jdbcTemplate.update(
            "insert into assistant_reconciliation_event(operation_id, attempt_id, event_type, actor_id, detail_json,"
                + " created_at) values (?, ?, ?, ?, ?, ?)",
            operationId, attemptId, eventType, actorId, detailJson, Timestamp.from(Instant.now()));
    }

    /** 读对账事件原文（审计与测试用）：detail 必须是可解析 JSON，调用方负责解析。 */
    public List<String> listReconciliationDetails(String operationId, String eventType) {
        return jdbcTemplate.queryForList(
            "select detail_json from assistant_reconciliation_event"
                + " where operation_id = ? and event_type = ? order by created_at asc",
            String.class, operationId, eventType);
    }

    public int countReconciliation(String operationId, String eventType) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from assistant_reconciliation_event where operation_id = ? and event_type = ?",
            Integer.class, operationId, eventType);
        return count == null ? 0 : count;
    }

    // ── 映射 ────────────────────────────────────────────────────────────────────

    private static RequestRecord mapRequest(ResultSet rs) throws SQLException {
        long project = rs.getLong("project_id");
        Long projectId = rs.wasNull() ? null : project;
        return new RequestRecord(rs.getLong("tenant_id"), rs.getLong("actor_id"), projectId,
            rs.getString("request_id"), rs.getString("input_digest"), rs.getString("session_binding"),
            rs.getString("admission_state"), rs.getString("operation_id"),
            rs.getTimestamp("created_at").toInstant());
    }

    private static OperationRecord mapOperation(ResultSet rs) throws SQLException {
        long project = rs.getLong("project_id");
        Long projectId = rs.wasNull() ? null : project;
        return new OperationRecord(rs.getString("operation_id"), rs.getLong("tenant_id"), projectId,
            rs.getString("action_code"),
            rs.getString("target_ref"), rs.getString("target_revision"), rs.getString("input_digest"),
            rs.getString("idempotency_key"), rs.getString("execution_state"), rs.getString("report_state"),
            rs.getInt("dispatch_generation"));
    }

    private static AttemptRecord mapAttempt(ResultSet rs) throws SQLException {
        Timestamp dispatched = rs.getTimestamp("dispatched_at");
        Timestamp ended = rs.getTimestamp("ended_at");
        return new AttemptRecord(rs.getString("attempt_id"), rs.getString("operation_id"), rs.getInt("generation"),
            rs.getString("hermes_stored_session_id"), rs.getString("hermes_turn_ref"), rs.getString("runner_run_id"),
            dispatched == null ? null : dispatched.toInstant(), ended == null ? null : ended.toInstant(),
            rs.getString("outcome"), (Integer) rs.getObject("run_total"), (Integer) rs.getObject("run_passed"),
            (Integer) rs.getObject("run_failed"), (Integer) rs.getObject("run_error"));
    }
}
