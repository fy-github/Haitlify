package com.haitlify.aitest.platform.assistant.report;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.haitlify.aitest.platform.assistant.ledger.AssistantLedgerRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * A1 报告生成（计划 §6.6）：报告是「某一时刻的执行事实快照」，不是执行事实本身。
 *
 * <p>三轴分开写：执行事实（operation/attempt）、资源清理、报告接受。生成报告不改执行状态；
 * 接受/驳回只改 {@code report_state}。每次生成追加一个 revision，旧修订保留可追溯。
 */
@Service
public class AssistantReportService {

    public record ReportView(String reportId, String operationId, int revision, String bodyJson, Instant createdAt,
                             Long generatedBy) {}

    private final AssistantLedgerRepository ledger;
    private final AssistantReportRepository reports;
    private final com.haitlify.aitest.platform.assistant.draft.AssistantDraftRepository drafts;
    private final ObjectMapper objectMapper;

    public AssistantReportService(AssistantLedgerRepository ledger, AssistantReportRepository reports,
                                  com.haitlify.aitest.platform.assistant.draft.AssistantDraftRepository drafts,
                                  ObjectMapper objectMapper) {
        this.ledger = ledger;
        this.reports = reports;
        this.drafts = drafts;
        this.objectMapper = objectMapper;
    }

    /** 生成一份新修订：只允许在终态生成（报告是执行事实的快照，不能是 PENDING 空壳）。 */
    @org.springframework.transaction.annotation.Transactional
    public ReportView generate(String operationId, Long generatedBy) {
        // 先锁 operation 行：与 decideReport 串行化，避免「接受旧修订 + 同时生成新修订」。
        AssistantLedgerRepository.OperationRecord operation = ledger.findOperationForUpdate(operationId)
            .orElseThrow(() -> new IllegalArgumentException("operation 不存在：" + operationId));
        if (!com.haitlify.aitest.platform.assistant.ledger.AssistantAdmissionService
                .isTerminal(operation.executionState())) {
            throw new com.haitlify.aitest.platform.assistant.ledger.AssistantStateConflictException(
                "执行尚未到终态（" + operation.executionState() + "），不能生成报告：" + operationId);
        }
        if ("ACCEPTED".equals(operation.reportState())) {
            // 已接受是终局：重新生成会把 ACCEPTED 打回 AWAITING，等于绕过已完成的验收。
            throw new com.haitlify.aitest.platform.assistant.ledger.AssistantStateConflictException(
                "报告已接受，不能再生成新修订；如需更正请新起一次执行");
        }

        List<Map<String, Object>> attempts = new ArrayList<>();
        for (AssistantLedgerRepository.AttemptRecord attempt : ledger.listAttempts(operationId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("generation", attempt.generation());
            row.put("runId", attempt.runnerRunId());
            row.put("outcome", attempt.outcome());
            row.put("hermesSessionId", attempt.hermesStoredSessionId());
            row.put("hermesTurnRef", attempt.hermesTurnRef());
            row.put("dispatchedAt", attempt.dispatchedAt() == null ? null : attempt.dispatchedAt().toString());
            row.put("endedAt", attempt.endedAt() == null ? null : attempt.endedAt().toString());
            row.put("summary", attempt.runTotal() == null ? null : Map.of(
                "total", attempt.runTotal(), "passed", attempt.runPassed(),
                "failed", attempt.runFailed(), "error", attempt.runError()));
            attempts.add(row);
        }

        List<Map<String, Object>> resources = new ArrayList<>();
        for (AssistantLedgerRepository.ResourceRecord resource : ledger.listResources(operationId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", resource.resourceKind());
            row.put("cleanupState", resource.cleanupState());
            row.put("executor", resource.cleanupExecutor());
            row.put("deadlineAt", resource.deadlineAt().toString());
            resources.add(row);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operationId", operation.operationId());
        body.put("actionCode", operation.actionCode());
        body.put("targetRef", operation.targetRef());
        body.put("targetRevision", operation.targetRevision());
        body.put("inputDigest", operation.inputDigest());
        body.put("executionState", operation.executionState());
        // 不写决策态：报告是执行事实快照，决策在 operation/report 轴，避免正文出现「已接受但写着 REJECTED」。

        body.put("dispatchGeneration", operation.dispatchGeneration());
        body.put("attempts", attempts);
        body.put("coverage", buildCoverage(operation.operationId(), operation.tenantId()));
        body.put("diagnostics", buildDiagnostics(operation.executionState(), ledger.listAttempts(operationId)));
        body.put("resources", resources);
        body.put("generatedAt", Instant.now().toString());

        String json = objectMapper.writeValueAsString(body);
        AssistantReportRepository.ReportRow row = reports.append(operationId, json, generatedBy);
        // 新修订 = 未审阅：只允许从 NONE/AWAITING 回到 AWAITING（CAS，不覆盖已决策）。
        if (!ledger.reopenReportState(operationId)) {
            throw new com.haitlify.aitest.platform.assistant.ledger.AssistantStateConflictException(
                "报告状态不允许重新打开（已决策）：" + operationId);
        }
        return new ReportView(row.reportId(), row.operationId(), row.revision(), row.bodyJson(), row.createdAt(),
            row.generatedBy());
    }

    public ReportView latest(String operationId) {
        return reports.latest(operationId)
            .map(row -> new ReportView(row.reportId(), row.operationId(), row.revision(), row.bodyJson(),
                row.createdAt(), row.generatedBy()))
            .orElseThrow(() -> new IllegalArgumentException("该 operation 尚无报告：" + operationId));
    }

    /**
     * 异常诊断：只陈述可核验的事实（哪一代失败、Runner 报了几条失败/错误、执行终态），
     * 不做「可能是 XX 原因」的推断——推断属于 Hermes 分析，必须标注来源并与实测事实分开。
     */
    static Map<String, Object> buildDiagnostics(String executionState,
                                                List<AssistantLedgerRepository.AttemptRecord> attempts) {
        List<Map<String, Object>> failed = new ArrayList<>();
        int totalPassed = 0;
        int totalFailed = 0;
        int totalError = 0;
        for (AssistantLedgerRepository.AttemptRecord attempt : attempts) {
            if (attempt.runPassed() != null) totalPassed += attempt.runPassed();
            if (attempt.runFailed() != null) totalFailed += attempt.runFailed();
            if (attempt.runError() != null) totalError += attempt.runError();
            boolean bad = attempt.runFailed() != null
                && (attempt.runFailed() > 0 || (attempt.runError() != null && attempt.runError() > 0));
            if (bad || "FAILED".equals(attempt.outcome())) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("generation", attempt.generation());
                row.put("runId", attempt.runnerRunId());
                row.put("outcome", attempt.outcome());
                row.put("failed", attempt.runFailed());
                row.put("error", attempt.runError());
                failed.add(row);
            }
        }
        // UNKNOWN = run 丢失或未确认终态：既不是成功也不是失败，必须单列，否则诊断只看 failedAttempts 会读成「全绿」。
        List<Map<String, Object>> unresolved = new ArrayList<>();
        for (AssistantLedgerRepository.AttemptRecord attempt : attempts) {
            if ("UNKNOWN".equals(attempt.outcome())) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("generation", attempt.generation());
                row.put("runId", attempt.runnerRunId());
                unresolved.add(row);
            }
        }
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("failedAttempts", failed);
        diagnostics.put("unknownAttempts", unresolved);
        diagnostics.put("stepTotals", Map.of("passed", totalPassed, "failed", totalFailed, "error", totalError));
        diagnostics.put("executionState", executionState);
        diagnostics.put("note", !unresolved.isEmpty()
            ? "有代次结果无法确认（UNKNOWN，见 unknownAttempts），不能读作全部通过"
            : failed.isEmpty() ? "无失败步骤" : "失败步骤见 failedAttempts；未执行项见 attempts");
        return diagnostics;
    }

    /**
     * 覆盖/修订量：把「这次执行用的是哪版草稿、结构长什么样」写死在报告里。
     * 只统计草稿自身的结构（步骤数、动作分布、断言数）与修订代数，不推断覆盖了哪些需求。
     */
    Map<String, Object> buildCoverage(String operationId, long tenantId) {
        java.util.Optional<String> draftId = ledger.findDraftIdForOperation(operationId);
        if (draftId.isEmpty()) {
            Map<String, Object> coverage = new LinkedHashMap<>();
            coverage.put("draftId", null);
            coverage.put("note", "该执行未绑定草稿（历史数据或人工派发）");
            return coverage;
        }
        return coverageForDraft(draftId.get(), tenantId);
    }

    /** 草稿维度的覆盖统计（执行绑定与否都成立，便于在确认页/报告中复用同一套口径）。 */
    Map<String, Object> coverageForDraft(String draftId, long tenantId) {
        Map<String, Object> coverage = new LinkedHashMap<>();
        var record = drafts.find(draftId, tenantId).orElse(null);
        if (record == null) {
            coverage.put("draftId", draftId);
            coverage.put("note", "草稿已不可读（跨租户或已删除）");
            return coverage;
        }
        int steps = 0;
        java.util.Map<String, Integer> actions = new java.util.TreeMap<>();
        int assertions = 0;
        try {
            var spec = objectMapper.readTree(record.draftJson());
            for (var step : spec.path("steps")) {
                steps++;
                String action = step.path("action").asText("");
                actions.merge(action, 1, Integer::sum);
                if (action.startsWith("assert")) assertions++;
            }
        } catch (Exception ex) {
            coverage.put("note", "草稿结构不可解析：" + ex.getMessage());
        }
        coverage.put("draftId", record.draftId());
        coverage.put("revision", record.revision());
        coverage.put("steps", steps);
        coverage.put("actions", actions);
        coverage.put("assertions", assertions);
        List<com.haitlify.aitest.platform.assistant.draft.AssistantDraftRepository.DraftRecord> revisions =
            drafts.listRevisions(record.draftId(), tenantId);
        coverage.put("revisionCount", revisions.size());
        // 人工修订量：修订链长度 - 1 就是人改过几轮；每轮的步骤/断言增减按结构差集算，不靠模型描述。
        coverage.put("manualRevisionCount", Math.max(0, revisions.size() - 1));
        coverage.put("revisionDelta", buildRevisionDelta(revisions));
        // 需求图文/表格规模：创建草稿时固化的确定性摘要，不事后重算。
        coverage.put("requirement", readRequirementSummary(record.requirementSummaryJson()));
        return coverage;
    }

    /**
     * 需求侧规模：行数、字符数、表格数、图片数与告警。原文不落库，所以这是唯一可核验的来源。
     */
    private Map<String, Object> readRequirementSummary(String json) {
        if (json == null || json.isBlank()) {
            return Map.of("note", "该草稿创建于需求摘要功能上线前，未记录需求规模");
        }
        try {
            return objectMapper.readValue(json, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            return Map.of("note", "需求摘要不可解析：" + ex.getMessage());
        }
    }

    /**
     * 修订结构差：只比较步骤签名（action + target）与断言数，回答「人改了多少」，
     * 不评价改得对不对——那属于评审结论，不是执行事实。
     */
    Map<String, Object> buildRevisionDelta(
            List<com.haitlify.aitest.platform.assistant.draft.AssistantDraftRepository.DraftRecord> revisions) {
        Map<String, Object> delta = new LinkedHashMap<>();
        if (revisions == null || revisions.size() < 2) {
            // 形状与多修订分支保持一致：调用方不必为「只有一版」写特例。
            delta.put("stepsAdded", 0);
            delta.put("stepsRemoved", 0);
            delta.put("stepsChanged", 0);
            delta.put("assertionsDelta", 0);
            delta.put("addedSteps", List.of());
            delta.put("removedSteps", List.of());
            if (revisions != null && revisions.size() == 1) {
                delta.put("fromRevision", revisions.get(0).revision());
                delta.put("toRevision", revisions.get(0).revision());
            }
            delta.put("note", "仅一版草稿，无人工修订");
            return delta;
        }
        var first = revisions.get(0);
        var last = revisions.get(revisions.size() - 1);
        java.util.Set<String> firstSteps = signaturesOf(first.draftJson());
        java.util.Set<String> lastSteps = signaturesOf(last.draftJson());
        java.util.Set<String> added = new java.util.LinkedHashSet<>(lastSteps);
        added.removeAll(firstSteps);
        java.util.Set<String> removed = new java.util.LinkedHashSet<>(firstSteps);
        removed.removeAll(lastSteps);
        delta.put("fromRevision", first.revision());
        delta.put("toRevision", last.revision());
        delta.put("stepsAdded", added.size());
        delta.put("stepsRemoved", removed.size());
        delta.put("assertionsDelta", assertionsOf(last.draftJson()) - assertionsOf(first.draftJson()));
        // 只改 value 的修订签名不变：单独计「改了值但没增删步骤」，否则会被读成「没改」。
        delta.put("stepsChanged", changedValues(first.draftJson(), last.draftJson()));
        delta.put("addedSteps", List.copyOf(added));
        delta.put("removedSteps", List.copyOf(removed));
        return delta;
    }

    /** 同一 action|target 签名下、value 发生变化的步骤数（结构差集看不到的部分）。 */
    private int changedValues(String beforeJson, String afterJson) {
        java.util.Map<String, String> before = signatureValues(beforeJson);
        java.util.Map<String, String> after = signatureValues(afterJson);
        int changed = 0;
        for (var entry : after.entrySet()) {
            if (before.containsKey(entry.getKey()) && !before.get(entry.getKey()).equals(entry.getValue())) {
                changed++;
            }
        }
        return changed;
    }

    private java.util.Map<String, String> signatureValues(String draftJson) {
        java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
        try {
            for (var step : objectMapper.readTree(draftJson).path("steps")) {
                values.put(step.path("action").asText("") + "|" + step.path("target").asText(""),
                    step.path("value").isMissingNode() || step.path("value").isNull()
                        ? "" : step.path("value").asText(""));
            }
        } catch (Exception ignored) {
            return values;
        }
        return values;
    }

    private java.util.Set<String> signaturesOf(String draftJson) {
        java.util.Set<String> signatures = new java.util.LinkedHashSet<>();
        try {
            for (var step : objectMapper.readTree(draftJson).path("steps")) {
                signatures.add(step.path("action").asText("") + "|" + step.path("target").asText(""));
            }
        } catch (Exception ex) {
            signatures.add("<unparsable>");
        }
        return signatures;
    }

    private int assertionsOf(String draftJson) {
        int count = 0;
        try {
            for (var step : objectMapper.readTree(draftJson).path("steps")) {
                if (step.path("action").asText("").startsWith("assert")) count++;
            }
        } catch (Exception ignored) {
            return 0;
        }
        return count;
    }
}
