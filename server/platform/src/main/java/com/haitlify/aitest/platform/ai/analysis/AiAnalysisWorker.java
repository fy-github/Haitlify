package com.haitlify.aitest.platform.ai.analysis;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.haitlify.aitest.platform.ai.event.AiRunEvent;
import com.haitlify.aitest.platform.ai.event.AiRunEventService;
import com.haitlify.aitest.platform.ai.call.AiCallRecordPayloads;
import com.haitlify.aitest.platform.ai.gateway.AiAttemptRecord;
import com.haitlify.aitest.platform.ai.gateway.AiGatewayConfig;
import com.haitlify.aitest.platform.ai.gateway.AiGenerationResult;
import com.haitlify.aitest.platform.ai.gateway.AiRetryReason;
import com.haitlify.aitest.platform.ai.gateway.AiRoute;
import com.haitlify.aitest.platform.ai.gateway.AiRouteExecutionResult;
import com.haitlify.aitest.platform.ai.gateway.AiRouteExecutionContext;
import com.haitlify.aitest.platform.ai.gateway.AiRouteExecutor;
import com.haitlify.aitest.platform.ai.gateway.AiRoutingStrategy;
import com.haitlify.aitest.platform.ai.gateway.AiStructuredOutputRepairService;
import com.haitlify.aitest.platform.ai.runtime.output.AiOutputContractService;
import com.haitlify.aitest.platform.ai.runtime.AiCancellationRegistry;
import com.haitlify.aitest.platform.ai.runtime.AiExecutionLedger;
import com.haitlify.aitest.platform.ai.rule.AiRuleService;
import com.haitlify.aitest.platform.ai.skill.AiSkillPromptContextService;
import com.haitlify.aitest.platform.security.SecretCipher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
class AiAnalysisWorker {

    private static final int MIN_ANALYSIS_TIMEOUT_MS = 120000;
    private static final long DEFAULT_WHOLE_RUN_DEADLINE_MS = 900000L;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AiAnalysisJobLeaseService leaseService;
    private final AiDraftMergeService draftMergeService;
    private final AiCoverageScanService coverageScanService;
    private final AiRuleService aiRuleService;
    private final AiSkillPromptContextService aiSkillPromptContextService;
    private final AiRouteExecutor aiRouteExecutor;
    private final AiStructuredOutputRepairService structuredOutputRepairService;
    private final AiOutputContractService outputContractService;
    private final AiCancellationRegistry cancellationRegistry;
    private final AiExecutionLedger executionLedger;
    private final com.haitlify.aitest.platform.ai.runtime.AiFallbackRouteSelector fallbackRouteSelector;
    private final AiRunEventService eventService;
    private final AiRunSummaryService runSummaryService;
    private final AiRunOriginSyncService runOriginSyncService;
    private final SecretCipher secretCipher;
    private final Clock clock;

    AiAnalysisWorker(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        AiAnalysisJobLeaseService leaseService,
        AiDraftMergeService draftMergeService,
        AiCoverageScanService coverageScanService,
        AiRuleService aiRuleService,
        AiSkillPromptContextService aiSkillPromptContextService,
        AiRouteExecutor aiRouteExecutor,
        AiStructuredOutputRepairService structuredOutputRepairService,
        AiOutputContractService outputContractService,
        AiCancellationRegistry cancellationRegistry,
        AiExecutionLedger executionLedger,
        com.haitlify.aitest.platform.ai.runtime.AiFallbackRouteSelector fallbackRouteSelector,
        AiRunEventService eventService,
        AiRunSummaryService runSummaryService,
        AiRunOriginSyncService runOriginSyncService,
        SecretCipher secretCipher,
        Clock clock
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.leaseService = leaseService;
        this.draftMergeService = draftMergeService;
        this.coverageScanService = coverageScanService;
        this.aiRuleService = aiRuleService;
        this.aiSkillPromptContextService = aiSkillPromptContextService;
        this.aiRouteExecutor = aiRouteExecutor;
        this.structuredOutputRepairService = structuredOutputRepairService;
        this.outputContractService = outputContractService;
        this.cancellationRegistry = cancellationRegistry;
        this.executionLedger = executionLedger;
        this.fallbackRouteSelector = fallbackRouteSelector;
        this.eventService = eventService;
        this.runSummaryService = runSummaryService;
        this.runOriginSyncService = runOriginSyncService;
        this.secretCipher = secretCipher;
        this.clock = clock;
    }

    AiAnalysisWorkerResult processNextQueuedRun() {
        AiAnalysisJobLeaseService.ClaimResult claim = leaseService.claimNextJob();
        if (claim.outcome() != AiAnalysisJobLeaseService.ClaimOutcome.CLAIMED) {
            String status = switch (claim.outcome()) {
                case RUN_BUSY -> "busy";
                case STOPPING -> "stopping";
                default -> "idle";
            };
            return new AiAnalysisWorkerResult(false, null, status, 0, "");
        }
        return processRun(claim.run(), null, claim.lease());
    }

    AiAnalysisWorkerResult resumeRun(String runId) {
        Map<String, Object> run = findRunOrNull(runId);
        if (run == null) {
            return new AiAnalysisWorkerResult(false, runId, "missing", 0, "run not found");
        }
        AiAnalysisJobLeaseService.Lease lease = leaseService.claim(runId, true);
        if (lease == null) {
            if (isRunCancelled(runId)) return new AiAnalysisWorkerResult(false, runId, "cancelled", completedChunks(runPk(run)), "用户取消");
            String refusal = leaseService.isStopping() ? "stopping" : "busy";
            return new AiAnalysisWorkerResult(false, runId, refusal, completedChunks(runPk(run)), "");
        }
        String resumeFromChunkId = blankToDefault((String) run.get("RESUME_FROM_CHUNK_ID"), (String) run.get("CURRENT_CHUNK_ID"));
        if (resumeFromChunkId.isBlank()) {
            releaseLease(lease);
            return new AiAnalysisWorkerResult(false, runId, "no_resume_point", completedChunks(runPk(run)), "");
        }
        return processRun(run, resumeFromChunkId, lease);
    }

    AiAnalysisWorkerResult processRun(String runId) {
        Map<String, Object> run = findRunOrNull(runId);
        if (run == null) {
            // 第三十轮补审 C5：不存在的 run 返回结构化结果（而非 EmptyResultDataAccessException→500）。
            return new AiAnalysisWorkerResult(false, runId, "missing", 0, "run not found");
        }
        // 第三十轮补审 6 C-1：自动派发路径不得重跑已失败的 run——失败后的再执行是人工动作（走 /resume），
        // 否则残留的重复派发任务会把 failed run 自动重跑（重复 run_failed 事件与模型调用）。
        if ("failed".equals(currentRunStatus(runId))) {
            return new AiAnalysisWorkerResult(false, runId, "failed", completedChunks(runPk(run)), "run already failed; retry via resume");
        }
        return processRun(run, null, leaseService.claim(runId));
    }

    AiAnalysisJobLeaseService leaseService() {
        return leaseService;
    }

    private AiAnalysisWorkerResult processRun(Map<String, Object> run, String resumeFromChunkId, AiAnalysisJobLeaseService.Lease lease) {
        if (lease == null) {
            String claimedRunId = (String) run.get("RUN_ID");
            if (isRunCancelled(claimedRunId)) {
                return new AiAnalysisWorkerResult(false, claimedRunId, "cancelled", completedChunks(runPk(run)), "用户取消");
            }
            String refusal = leaseService.isStopping() ? "stopping" : "busy";
            return new AiAnalysisWorkerResult(false, claimedRunId, refusal, completedChunks(runPk(run)), "");
        }
        String runId = (String) run.get("RUN_ID");
        Long runPk = runPk(run);
        Long projectId = ((Number) run.get("PROJECT_ID")).longValue();
        int chunkTotal = ((Number) run.get("TOTAL_CHUNKS")).intValue();
        if (!markRunRunning(runId, lease)) {
            // 第三十轮补审 C2：claim 与标记 running 之间落下的取消/接管必须在此被拦截，不得静默吞掉。
            String current = currentRunStatus(runId);
            leaseService.release(lease);
            String status = "cancelled".equals(current) ? "cancelled" : "preempted";
            return new AiAnalysisWorkerResult(false, runId, status, completedChunks(runPk), "run is not claimable anymore");
        }
        String strategySnapshotId = "default";
        ScheduledFuture<?> heartbeatTask = null;
        AtomicBoolean leaseLost = new AtomicBoolean(false);
        try {
            AiRoutingStrategy strategy = loadStrategy(run);
            strategySnapshotId = strategy.strategySnapshotId();
            heartbeatTask = leaseService.startHeartbeat(runId, strategySnapshotId, chunkTotal, lease, leaseLost);
            emit(strategySnapshotId, runId, "run_started", "info",
                chunkTotal > 0 ? "分析任务已启动，共 " + chunkTotal + " 个分片" : "分析任务已启动",
                null, chunkTotal, "");
            Instant deadline = strategy.wholeRunDeadlineMs() > 0
                ? clock.instant().plusMillis(strategy.wholeRunDeadlineMs())
                : null;
            // T05：同一 run 的台账（截止时刻=run 级 deadline；null 表示不设整体约束）。
            executionLedger.open(runId, deadline);
            List<Map<String, Object>> chunks = loadProcessableChunks(runPk, resumeFromChunkId);
            int completedBefore = completedChunks(runPk);

            for (int index = 0; index < chunks.size(); index++) {
                if (leaseLost.get()) {
                    throw new IllegalStateException("AI analysis lease lost; active worker must stop");
                }
                Map<String, Object> chunk = chunks.get(index);
                String plannedChunkId = (String) chunk.get("PLANNED_CHUNK_ID");
                int chunkIndex = completedBefore + index + 1;
                int displayTotal = chunkTotal > 0 ? chunkTotal : chunks.size();

                if (deadline != null && clock.instant().isAfter(deadline)) {
                    if (!leaseService.isLeaseHeld(lease)) {
                        return new AiAnalysisWorkerResult(true, runId, "preempted", completedChunks(runPk),
                            "deadline exceeded while the lease was lost to a newer owner");
                    }
                    emit(strategySnapshotId, runId, "run_deadline_exceeded", "error",
                        "已超过整体运行时限，剩余分片已停止", chunkIndex, chunkTotal, AiRetryReason.RUN_DEADLINE_EXCEEDED.name());
                    runSummaryService.writeSummary(runId, AiRetryReason.RUN_DEADLINE_EXCEEDED);
                    markRunFailed(runId, "已超过整体运行时限，剩余分片已停止", completedChunks(runPk), lease);
                    return new AiAnalysisWorkerResult(true, runId, "failed", completedChunks(runPk), "已超过整体运行时限");
                }

                markCurrentChunk(runId, plannedChunkId, lease);
                emit(strategySnapshotId, runId, "chunk_started", "info",
                    "开始处理分片 " + chunkIndex + "/" + displayTotal + "：" + sectionLabel(chunk),
                    chunkIndex, chunkTotal, "");
                ObjectNode prompt = buildPrompt(run, chunk);
                AiRouteExecutionContext ctx = new AiRouteExecutionContext(runId, strategySnapshotId, chunkIndex, chunkTotal, plannedChunkId, strategy.taskType(), projectId);

                AiRouteExecutionResult execResult;
                try {
                    execResult = aiRouteExecutor.execute(strategy, prompt, ctx);
                } catch (Exception exception) {
                    insertCallRecord(runPk, projectId, null, null, 0, prompt, exception.getMessage(), strategySnapshotId);
                    execResult = new AiRouteExecutionResult(false, null, null, List.of(), AiRetryReason.TRANSIENT_NETWORK);
                }

                if (leaseLost.get()) {
                    throw new IllegalStateException("AI analysis lease lost; active worker must stop");
                }
                ensureLeaseHeld(lease, leaseLost);
                for (AiAttemptRecord attempt : execResult.attempts()) {
                    insertCallRecord(runPk, projectId, attempt.route(), attempt.result(), attempt.durationMs(), prompt, null, strategySnapshotId);
                }

                if (cancellationRegistry.isCancelled(runId) || isRunCancelled(runId)) {
                    emit(strategySnapshotId, runId, "run_cancelled", "warn", "分析任务已取消，已停止后续处理", chunkIndex, chunkTotal, "");
                    return new AiAnalysisWorkerResult(true, runId, "cancelled", completedChunks(runPk), "用户取消");
                }

                if (execResult.success()) {
                    ArrayNode draftPoints;
                    try {
                        draftPoints = requireDraftPoints(execResult.finalResult().content());
                    } catch (IllegalArgumentException parseFailure) {
                        if (!isTruncatedJson(parseFailure) && !isDraftPointShapeFailure(parseFailure)) {
                            throw parseFailure;
                        }
                        emit(strategySnapshotId, runId, "output_repair_started", "warn",
                            "分片 " + chunkIndex + "/" + displayTotal + " 的模型输出未通过 JSON/schema 校验，正在尝试修复",
                            chunkIndex, chunkTotal, AiRetryReason.OUTPUT_VALIDATION.name());
                        // C1：修复验收必须使用注册契约（requirement-test-points/v1 含 minItems: 1），
                        // 截断输出被“修复”为空数组不得成为成功；注册契约缺失时 fail-closed。
                        // T05：修复与普通调用共用同一台账——取消后不发起修复；修复超时收窄为最小剩余预算。
                        long repairBudgetMs = executionLedger.remainingBudgetMs(
                            runId, execResult.winningRoute().requestTimeoutMs(), clock);
                        if (cancellationRegistry.isCancelled(runId) || repairBudgetMs <= 0) {
                            boolean cancelled = cancellationRegistry.isCancelled(runId);
                            emit(strategySnapshotId, runId, "output_repair_skipped", "warn",
                                cancelled ? "运行已取消，跳过输出修复" : "整体运行时限已到，跳过输出修复",
                                chunkIndex, chunkTotal,
                                cancelled ? AiRetryReason.CANCELLED.name() : AiRetryReason.RUN_DEADLINE_EXCEEDED.name());
                            if (cancelled) {
                                return new AiAnalysisWorkerResult(true, runId, "cancelled", completedChunks(runPk), "用户取消");
                            }
                            // L5：预算已尽按 deadline 语义收尾（与循环内 deadline 分支一致），不伪装成“用户取消”。
                            runSummaryService.writeSummary(runId, AiRetryReason.RUN_DEADLINE_EXCEEDED);
                            markRunFailed(runId, "已超过整体运行时限，跳过输出修复", completedChunks(runPk), lease);
                            return new AiAnalysisWorkerResult(true, runId, "failed", completedChunks(runPk), "已超过整体运行时限");
                        }
                        tools.jackson.databind.JsonNode registeredContract =
                            outputContractService.schemaFor("requirement-test-points", "v1");
                        AiGatewayConfig baseRepairConfig = execResult.winningRoute().toGatewayConfig();
                        AiGatewayConfig repairConfig = repairBudgetMs == baseRepairConfig.requestTimeoutMs()
                            ? baseRepairConfig
                            : new AiGatewayConfig(baseRepairConfig.providerType(), baseRepairConfig.baseUrl(),
                                baseRepairConfig.model(), baseRepairConfig.visionModel(), baseRepairConfig.apiKey(),
                                (int) repairBudgetMs);
                        AiStructuredOutputRepairService.RepairResult repair = registeredContract == null
                            ? null
                            : structuredOutputRepairService.repairRequirementAnalysisOutput(
                                repairConfig,
                                execResult.finalResult().content(),
                                registeredContract,
                                runPk,
                                projectId,
                                runId
                            );
                        if (cancellationRegistry.isCancelled(runId)) {
                            emit(strategySnapshotId, runId, "run_cancelled", "warn", "修复返回后发现运行已取消，停止后续处理",
                                chunkIndex, chunkTotal, AiRetryReason.CANCELLED.name());
                            return new AiAnalysisWorkerResult(true, runId, "cancelled", completedChunks(runPk), "用户取消");
                        }
                        if (repair == null || !repair.success()) {
                            String repairError = "AI 输出修复失败：" + (repair == null
                                ? "registered output contract requirement-test-points/v1 is missing"
                                : blankToDefault(repair.errorMessage(), parseFailure.getMessage()));
                            emit(strategySnapshotId, runId, "output_repair_failed", "error", repairError,
                                chunkIndex, chunkTotal, AiRetryReason.OUTPUT_VALIDATION.name());
                            markChunkFailed(runId, runPk, plannedChunkId, repairError, lease);
                            runSummaryService.writeSummary(runId, AiRetryReason.OUTPUT_VALIDATION);
                            return new AiAnalysisWorkerResult(true, runId, "failed", completedChunks(runPk), repairError);
                        }
                        draftPoints = requireDraftPoints(repair.output().toString());
                        emit(strategySnapshotId, runId, "output_repair_succeeded", "info",
                            "分片 " + chunkIndex + "/" + displayTotal + " 的模型输出已修复并通过 JSON 校验",
                            chunkIndex, chunkTotal, AiRetryReason.OUTPUT_VALIDATION.name());
                    }
                    ensureLeaseHeld(lease, leaseLost);
                    String providerRequestId = execResult.finalResult() == null ? "" : blankToDefault(execResult.finalResult().requestId(), "");
                    draftMergeService.mergeDraftPoints(runId, projectId, toCandidates(draftPoints, plannedChunkId), providerRequestId);
                    markChunkSucceeded(runId, runPk, plannedChunkId, lease);
                    emit(strategySnapshotId, runId, "chunk_completed", "info",
                        "分片 " + chunkIndex + "/" + displayTotal + " 完成，生成 " + draftPoints.size() + " 个测试点",
                        chunkIndex, chunkTotal, "");
                    continue;
                }

                String retryReason = execResult.terminalReason() == null ? "" : execResult.terminalReason().name();
                emit(strategySnapshotId, runId, "chunk_failed", "error",
                    "分片 " + chunkIndex + " 失败，已记录续跑位置", chunkIndex, chunkTotal, retryReason);
                markChunkFailed(runId, runPk, plannedChunkId, execResult.errorMessage(), lease);
                runSummaryService.writeSummary(runId, execResult.terminalReason());
                return new AiAnalysisWorkerResult(true, runId, "failed", completedChunks(runPk), blankToDefault(execResult.errorMessage(), "AI chunk analysis failed"));
            }

            if (isRunCancelled(runId)) {
                emit(strategySnapshotId, runId, "run_cancelled", "warn", "分析任务已取消，已停止后续处理", null, chunkTotal, "");
                return new AiAnalysisWorkerResult(true, runId, "cancelled", completedChunks(runPk), "用户取消");
            }

            boolean committed = markRunSucceeded(runId, runPk, totalCompletedChunks(runPk), lease);
            if (committed) {
                // 第三十轮补审 C3：coverage 扫描属收尾派生数据，必须晚于围栏提交，stale owner 不得写入。
                coverageScanService.scan(runId);
                refreshRunOrigin(runPk);
                runSummaryService.writeSummary(runId, null);
                emit(strategySnapshotId, runId, "run_succeeded", "info",
                    "分析任务完成，共生成 " + draftPointCount(runPk) + " 个测试点", null, chunkTotal, "");
            }
            // 第三十轮补审 2 C4-1：收尾窗口内落下的取消不得被覆盖——提交失败时先看真实状态再定返回值。
            String terminal = committed ? "succeeded" : ("cancelled".equals(currentRunStatus(runId)) ? "cancelled" : "preempted");
            return new AiAnalysisWorkerResult(true, runId, terminal, totalCompletedChunks(runPk), "");
        } catch (Exception exception) {
            if (cancellationRegistry.isCancelled(runId) || isRunCancelled(runId)) {
                return new AiAnalysisWorkerResult(true, runId, "cancelled", completedChunks(runPk), "用户取消");
            }
            if (lease != null && !leaseService.isLeaseHeld(lease)) {
                // 围栏拒绝：租约已被更新的 owner 接管，本 worker 的迟到写入一律放弃（含事件与终态）。
                return new AiAnalysisWorkerResult(true, runId, "preempted", completedChunks(runPk),
                    "lease lost to a newer owner; stale writes were discarded");
            }
            String failureDetail = clampDiagnostic(blankToDefault(exception.getMessage(), "未知错误"));
            emit(strategySnapshotId, runId, "run_failed", "error",
                "分析任务失败：" + failureDetail, null, chunkTotal, "");
            markRunFailed(runId, failureDetail, completedChunks(runPk), lease);
            runSummaryService.writeSummary(runId, AiRetryReason.INVALID_REQUEST);
            return new AiAnalysisWorkerResult(true, runId, "failed", completedChunks(runPk), failureDetail);
        } finally {
            executionLedger.close(runId);
            cancellationRegistry.clear(runId);
            if (heartbeatTask != null) {
                heartbeatTask.cancel(false);
            }
            releaseLease(lease);
        }
    }

    private void releaseLease(AiAnalysisJobLeaseService.Lease lease) {
        leaseService.release(lease);
    }

    private void ensureLeaseHeld(AiAnalysisJobLeaseService.Lease lease, AtomicBoolean leaseLost) {
        if (leaseLost.get()) {
            throw new IllegalStateException("AI analysis lease lost; active worker must stop");
        }
        if (!leaseService.isLeaseHeld(lease)) {
            leaseLost.set(true);
            throw new IllegalStateException("AI analysis lease lost; active worker must stop");
        }
    }

    private boolean isRunCancelled(String runId) {
        String status = jdbcTemplate.queryForObject(
            "select status from ai_analysis_runs where run_id = ?",
            String.class,
            runId
        );
        return "cancelled".equals(status);
    }

    private AiRoutingStrategy loadStrategy(Map<String, Object> run) {
        AiRoutingStrategy fromSnapshot = parseStrategySnapshot((String) run.get("STRATEGY_SNAPSHOT_JSON"));
        if (fromSnapshot != null && !fromSnapshot.routes().isEmpty()) {
            return fromSnapshot;
        }
        // Backward-compatible single-route strategy derived from the legacy config snapshot. When
        // config is missing/incomplete this throws "AI 配置未完成", which the run loop catches and
        // persists as a graceful failure rather than a server error.
        GatewaySettings settings = loadGatewaySettings(run);
        // T05：优先尝试能力化 fallback 选择（授权项目 + 能力 + 预算；AiFallbackRouteSelector v1）；
        // 无可用档案行时回退旧单路由路径（向后兼容，行为不变）。
        Long projectId = ((Number) run.get("PROJECT_ID")).longValue();
        String runIdForSelection = (String) run.get("RUN_ID");
        var selection = fallbackRouteSelector.select(projectId, List.of("TEXT"),
            DEFAULT_WHOLE_RUN_DEADLINE_MS, settings.timeoutMs());
        if (!selection.routes().isEmpty()) {
            emit("ability-v1", runIdForSelection, "strategy_candidates", "info",
                "能力化路由选择（v1）：入选 " + selection.routes().size() + " 条，拒绝 " + selection.rejected().size()
                    + (selection.rejected().isEmpty() ? "" : "（首因：" + selection.rejected().get(0).rejection() + "）"),
                null, 0, "");
            return new AiRoutingStrategy("ability-v1", "requirement_analysis", selection.routes(),
                Math.max(0, selection.routes().size() - 1), settings.timeoutMs(), DEFAULT_WHOLE_RUN_DEADLINE_MS, null);
        }
        AiRoute route = new AiRoute("default", "default", settings.providerType(), settings.baseUrl(), settings.model(), settings.apiKey(), settings.timeoutMs());
        // R2-5：候选全拒时也要披露拒因（此前静默，排障缺可观测性）。
        String candidateSummary = "能力化路由选择（v1）：入选 " + selection.routes().size() + " 条，拒绝 " + selection.rejected().size()
            + (selection.rejected().isEmpty() ? "" : "（首因：" + selection.rejected().get(0).rejection() + "）");
        emit("default", runIdForSelection, "strategy_candidates", "info", candidateSummary, null, 0, "");
        return new AiRoutingStrategy("default", "requirement_analysis", List.of(route), 0, settings.timeoutMs(), DEFAULT_WHOLE_RUN_DEADLINE_MS, null);
    }

    private AiRoutingStrategy parseStrategySnapshot(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            List<AiRoute> routes = new ArrayList<>();
            for (JsonNode item : root.path("routes")) {
                routes.add(new AiRoute(
                    item.path("connectionId").asText(""),
                    item.path("providerName").asText(""),
                    item.path("providerType").asText("openai-compatible"),
                    item.path("baseUrl").asText(""),
                    item.path("model").asText(""),
                    secretCipher.decrypt(item.path("apiKey").asText("")),
                    item.path("requestTimeoutMs").asInt(MIN_ANALYSIS_TIMEOUT_MS)
                ));
            }
            return new AiRoutingStrategy(
                root.path("strategySnapshotId").asText("default"),
                root.path("taskType").asText("requirement_analysis"),
                routes,
                root.path("retryBudget").asInt(0),
                root.path("perCallTimeoutMs").asLong(MIN_ANALYSIS_TIMEOUT_MS),
                root.path("wholeRunDeadlineMs").asLong(DEFAULT_WHOLE_RUN_DEADLINE_MS),
                null
            );
        } catch (Exception exception) {
            return null;
        }
    }

    private String sectionLabel(Map<String, Object> chunk) {
        return blankToDefault((String) chunk.get("SECTION_HEADING"), "分片");
    }

    private void emit(String strategySnapshotId, String runId, String stage, String severity, String summary, Integer chunkIndex, int chunkTotal, String retryReason) {
        eventService.emit(new AiRunEvent(runId, Instant.now(), stage, severity, summary, chunkIndex, chunkTotal, null, "", "", "", 0L, retryReason, strategySnapshotId, 0L));
    }

    private List<Map<String, Object>> loadProcessableChunks(Long runPk, String resumeFromChunkId) {
        return leaseService.processableChunks(runPk, resumeFromChunkId);
    }

    private ObjectNode buildPrompt(Map<String, Object> run, Map<String, Object> chunk) {
        ObjectNode prompt = objectMapper.createObjectNode();
        prompt.put("skillId", "requirement-test-point-analysis");
        prompt.set("skill", aiSkillPromptContextService.resolve("requirement-test-point-analysis"));
        prompt.set("effectiveRules", objectMapper.valueToTree(aiRuleService.resolveEffectiveRules(
            ((Number) run.get("PROJECT_ID")).longValue()
        )));
        prompt.put("forcedHumanReview", true);
        prompt.put("runId", (String) run.get("RUN_ID"));
        prompt.put("plannedChunkId", (String) chunk.get("PLANNED_CHUNK_ID"));
        prompt.put("targetCaseKind", blankToDefault((String) run.get("TARGET_CASE_KIND"), "interface"));
        prompt.put("templateId", blankToDefault((String) run.get("TEMPLATE_ID"), ""));
        prompt.put("sectionHeading", (String) chunk.get("SECTION_HEADING"));
        prompt.put("contentText", (String) chunk.get("CONTENT_TEXT"));
        prompt.set("inputSummary", parse((String) run.get("INPUT_SUMMARY_JSON")));
        prompt.set("sourceChunkIds", parseArray((String) chunk.get("SOURCE_CHUNK_IDS_JSON")));
        prompt.set("targetTemplate", targetTemplateContext(
            blankToDefault((String) run.get("TARGET_CASE_KIND"), "interface"),
            blankToDefault((String) run.get("TEMPLATE_ID"), "")
        ));
        return prompt;
    }

    private JsonNode targetTemplateContext(String targetCaseKind, String templateId) {
        ObjectNode template = objectMapper.createObjectNode();
        template.put("templateId", templateId);
        template.put("targetCaseKind", targetCaseKind);
        template.put("reviewGate", "human_confirmation_required");
        template.put("requiredOutputRoot", "draftTestPoints");
        template.set("draftPointFields", objectMapper.valueToTree(List.of(
            "title",
            "module",
            "pageOrFeature",
            "caseKind",
            "testType",
            "priority",
            "precondition",
            "objective",
            "steps",
            "expectedResult",
            "sourceChunkId",
            "confidence",
            "inferred",
            "templateId"
        )));
        if ("web_automation".equals(targetCaseKind)) {
            template.put("generatedCaseGoal", "prefer directly usable web automation case content");
            template.set("webCaseHints", objectMapper.valueToTree(List.of(
                "steps should prefer executable action lines like goto/click/input/assertVisible when possible",
                "expectedResult should describe visible assertions or url assertions",
                "pageOrFeature should name the target page or flow"
            )));
        }
        return template;
    }

    private ArrayNode requireDraftPoints(String content) {
        JsonNode output;
        try {
            output = objectMapper.readTree(content);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid AI output JSON: " + exception.getMessage(), exception);
        }
        if (output == null || !output.isObject()) {
            throw new IllegalArgumentException("Invalid AI output JSON: root must be an object");
        }
        JsonNode draftPoints = output.path("draftTestPoints");
        if (!draftPoints.isArray()) {
            throw new IllegalArgumentException("Invalid AI output JSON: draftTestPoints must be an array");
        }
        return (ArrayNode) draftPoints;
    }

    private boolean isTruncatedJson(IllegalArgumentException exception) {
        Throwable cause = exception.getCause();
        String message = cause == null ? exception.getMessage() : cause.getMessage();
        return message != null && message.contains("Unexpected end-of-input");
    }

    private boolean isDraftPointShapeFailure(IllegalArgumentException exception) {
        String message = exception.getMessage();
        return message != null && (message.contains("draftTestPoints must be an array")
            || message.contains("root must be an object"));
    }

    private List<AiDraftMergeCandidate> toCandidates(ArrayNode draftPoints, String plannedChunkId) {
        List<AiDraftMergeCandidate> candidates = new ArrayList<>();
        for (JsonNode point : draftPoints) {
            String sourceChunkId = blankToDefault(point.path("sourceChunkId").asText(""), plannedChunkId);
            candidates.add(new AiDraftMergeCandidate(
                point.path("title").asText("未命名测试点"),
                point.path("module").asText(""),
                point.path("pageOrFeature").asText(""),
                point.path("caseKind").asText(""),
                point.path("testType").asText("功能"),
                point.path("priority").asText("P2"),
                point.path("precondition").asText(""),
                point.path("objective").asText(""),
                point.path("steps").asText(""),
                point.path("expectedResult").asText(""),
                point.path("confidence").asDouble(0.8),
                point.path("inferred").asBoolean(false),
                sourceChunkId,
                sourceChunkPk(sourceChunkId),
                sha256Hex(sourceChunkId + ":" + point.path("expectedResult").asText(""))
            ));
        }
        return candidates;
    }

    /**
     * 证据指纹：源 chunk + 模型给出的预期结果 → SHA-256 十六进制。
     * 该值进入 gate 审计 artifact_id（varchar(128)）与幂等键；模型原文直拼会在长句子上顶爆列宽
     * （用户实测：合并事务被 Data too long 炸掉）。散列后长度恒为 64，且保持确定性去重语义。
     */
    private static String sha256Hex(String raw) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /** 诊断文本钳位：event.summary 与 run.error_summary 均为 varchar(1024)，异常原文可能远超列宽。 */
    private static String clampDiagnostic(String text) {
        if (text == null || text.length() <= 900) return text;
        return text.substring(0, 900) + "…(truncated)";
    }

    private Long sourceChunkPk(String sourceChunkId) {
        List<Long> ids = jdbcTemplate.queryForList(
            "select id from ai_source_chunks where chunk_id = ?",
            Long.class,
            sourceChunkId
        );
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private GatewaySettings loadGatewaySettings(Map<String, Object> run) {
        GatewaySettings snapshotSettings = loadGatewaySettingsFromRunSnapshot(run);
        if (snapshotSettings != null) {
            return snapshotSettings;
        }
        List<GatewaySettings> settings = jdbcTemplate.query(
            "select enabled, provider_type, base_url, text_model, api_key_ciphertext, request_timeout_ms from ai_system_configs where config_key = 'default'",
            (rs, rowNum) -> {
                if (!rs.getBoolean("enabled")) {
                    throw new IllegalStateException("AI 配置未启用，请先在 AI 配置页启用并保存配置");
                }
                String providerType = blankToDefault(rs.getString("provider_type"), "openai-compatible");
                String baseUrl = blankToDefault(rs.getString("base_url"), "");
                String model = blankToDefault(rs.getString("text_model"), "");
                String apiKey = secretCipher.decrypt(blankToDefault(rs.getString("api_key_ciphertext"), ""));
                if (baseUrl.isBlank() || model.isBlank() || apiKey.isBlank()) {
                    throw new IllegalStateException("AI 配置未完成，请先在 AI 配置页保存 Base URL、模型和 API Key");
                }
                return new GatewaySettings(
                    providerType,
                    baseUrl,
                    model,
                    apiKey,
                    Math.max(rs.getInt("request_timeout_ms"), MIN_ANALYSIS_TIMEOUT_MS)
                );
            }
        );
        if (settings.isEmpty()) {
            throw new IllegalStateException("AI 配置未完成，请先在 AI 配置页保存 Base URL、模型和 API Key");
        }
        return settings.get(0);
    }

    private GatewaySettings loadGatewaySettingsFromRunSnapshot(Map<String, Object> run) {
        String snapshot = (String) run.get("CONFIG_SNAPSHOT_JSON");
        if (snapshot == null || snapshot.isBlank() || "{}".equals(snapshot.trim())) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(snapshot);
            String providerType = blankToDefault(root.path("providerType").asText(""), "openai-compatible");
            String baseUrl = blankToDefault(root.path("baseUrl").asText(""), "");
            String model = blankToDefault(root.path("model").asText(""), "");
            String apiKey = secretCipher.decrypt(blankToDefault(root.path("apiKey").asText(""), ""));
            if (baseUrl.isBlank() || model.isBlank() || apiKey.isBlank()) {
                return null;
            }
            return new GatewaySettings(
                providerType,
                baseUrl,
                model,
                apiKey,
                Math.max(root.path("timeoutMs").asInt(MIN_ANALYSIS_TIMEOUT_MS), MIN_ANALYSIS_TIMEOUT_MS)
            );
        } catch (Exception exception) {
            return null;
        }
    }

    private boolean markRunRunning(String runId, AiAnalysisJobLeaseService.Lease lease) {
        Instant now = Instant.now();
        int updated = jdbcTemplate.update("""
            update ai_analysis_runs
            set status = ?, worker_status = ?, started_at = coalesce(started_at, ?), finished_at = null,
                error_summary = ?, heartbeat_at = ?, updated_at = ?
            where run_id = ? and status not in ('cancelled', 'succeeded')
              and exists (select 1 from ai_analysis_worker_leases l where l.run_id = ? and l.owner_id = ? and l.fencing_token = ?)
            """,
            "running",
            "running",
            Timestamp.from(now),
            "",
            Timestamp.from(now),
            Timestamp.from(now),
            runId,
            runId,
            lease.ownerId(),
            lease.fencingToken()
        );
        return updated == 1;
    }

    private String currentRunStatus(String runId) {
        try {
            List<String> statuses = jdbcTemplate.queryForList(
                "select status from ai_analysis_runs where run_id = ?", String.class, runId);
            return statuses.isEmpty() ? null : statuses.getFirst();
        } catch (Exception exception) {
            return null;
        }
    }

    private void markCurrentChunk(String runId, String plannedChunkId, AiAnalysisJobLeaseService.Lease lease) {
        jdbcTemplate.update(
            "update ai_analysis_runs set current_chunk_id = ?, updated_at = ? where run_id = ? and exists (select 1 from ai_analysis_worker_leases l where l.run_id = ? and l.owner_id = ? and l.fencing_token = ?)",
            plannedChunkId,
            Timestamp.from(Instant.now()),
            runId,
            runId,
            lease.ownerId(),
            lease.fencingToken()
        );
    }

    private void markChunkSucceeded(String runId, Long runPk, String plannedChunkId, AiAnalysisJobLeaseService.Lease lease) {
        jdbcTemplate.update(
            "update ai_analysis_chunks set status = ?, skip_reason = ?, updated_at = ? where run_id = ? and planned_chunk_id = ? and exists (select 1 from ai_analysis_worker_leases l where l.run_id = ? and l.owner_id = ? and l.fencing_token = ?)",
            "succeeded",
            "",
            Timestamp.from(Instant.now()),
            runPk,
            plannedChunkId,
            runId,
            lease.ownerId(),
            lease.fencingToken()
        );
        jdbcTemplate.update(
            "update ai_analysis_runs set completed_chunks = ?, updated_at = ? where id = ? and exists (select 1 from ai_analysis_worker_leases l where l.run_id = ? and l.owner_id = ? and l.fencing_token = ?)",
            completedChunks(runPk),
            Timestamp.from(Instant.now()),
            runPk,
            runId,
            lease.ownerId(),
            lease.fencingToken()
        );
    }

    private void markChunkFailed(String runId, Long runPk, String plannedChunkId, String errorSummary, AiAnalysisJobLeaseService.Lease lease) {
        Instant now = Instant.now();
        jdbcTemplate.update(
            "update ai_analysis_chunks set status = ?, updated_at = ? where run_id = ? and planned_chunk_id = ? and exists (select 1 from ai_analysis_worker_leases l where l.run_id = ? and l.owner_id = ? and l.fencing_token = ?)",
            "failed",
            Timestamp.from(now),
            runPk,
            plannedChunkId,
            runId,
            lease.ownerId(),
            lease.fencingToken()
        );
        jdbcTemplate.update("""
            update ai_analysis_runs
            set status = ?, worker_status = ?, coverage_status = ?, completed_chunks = ?, current_chunk_id = ?,
                resume_from_chunk_id = ?, error_summary = ?, finished_at = ?, updated_at = ?
            where run_id = ? and exists (select 1 from ai_analysis_worker_leases l where l.run_id = ? and l.owner_id = ? and l.fencing_token = ?)
            """,
            "failed",
            "idle",
            "failed",
            completedChunks(runPk),
            plannedChunkId,
            plannedChunkId,
            clampDiagnostic(blankToDefault(errorSummary, "AI chunk analysis failed")),
            Timestamp.from(now),
            Timestamp.from(now),
            runId,
            runId,
            lease.ownerId(),
            lease.fencingToken()
        );
    }

    private void markRunFailed(String runId, String errorSummary, int completedChunks, AiAnalysisJobLeaseService.Lease lease) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
            update ai_analysis_runs
            set status = ?, worker_status = ?, coverage_status = ?, completed_chunks = ?, error_summary = ?,
                finished_at = ?, updated_at = ?
            where run_id = ? and exists (select 1 from ai_analysis_worker_leases l where l.run_id = ? and l.owner_id = ? and l.fencing_token = ?)
            """,
            "failed",
            "idle",
            "failed",
            completedChunks,
            clampDiagnostic(blankToDefault(errorSummary, "AI chunk analysis failed")),
            Timestamp.from(now),
            Timestamp.from(now),
            runId,
            runId,
            lease.ownerId(),
            lease.fencingToken()
        );
    }

    private boolean markRunSucceeded(String runId, Long runPk, int completedChunks, AiAnalysisJobLeaseService.Lease lease) {
        Instant now = Instant.now();
        ObjectNode outputSummary = objectMapper.createObjectNode()
            .put("draftTestPointCount", draftPointCount(runPk))
            .put("toolCallCount", callRecordCount(runPk));
        int updated = jdbcTemplate.update("""
            update ai_analysis_runs
            set status = ?, worker_status = ?, completed_chunks = ?, current_chunk_id = ?, resume_from_chunk_id = ?,
                error_summary = ?, output_summary_json = ?, finished_at = ?, updated_at = ?
            where run_id = ? and status <> 'cancelled'
              and exists (select 1 from ai_analysis_worker_leases l where l.run_id = ? and l.owner_id = ? and l.fencing_token = ?)
            """,
            "succeeded",
            "idle",
            completedChunks,
            "",
            "",
            "",
            stringify(outputSummary),
            Timestamp.from(now),
            Timestamp.from(now),
            runId,
            runId,
            lease.ownerId(),
            lease.fencingToken()
        );
        return updated == 1;
    }

    /**
     * Run-level provenance mirrors the artifacts the run actually produced: a run may only claim
     * MODEL_GENERATED while it holds at least one artifact backed by real runtime evidence, otherwise it
     * stays BLOCKED — status=succeeded alone must never be read as real AI success.
     */
    private void refreshRunOrigin(Long runPk) {
        runOriginSyncService.syncFromArtifacts(runPk);
    }

    private void insertCallRecord(
        Long runPk,
        Long projectId,
        AiRoute route,
        AiGenerationResult result,
        long durationMs,
        JsonNode inputSummary,
        String errorMessage,
        String strategySnapshotId
    ) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
            insert into ai_call_records(
              record_id, run_id, project_id, provider_type, model_name, config_snapshot_id, agent_id,
              skill_ids_json, rule_version, modality, request_id, status, error_code, error_message,
              duration_ms, token_usage_input, token_usage_output, estimated_cost, input_summary_json,
              output_summary_json, raw_payload_json, actor_id, actor_name, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "call-" + UUID.randomUUID(),
            runPk,
            projectId,
            route == null ? "" : route.providerType(),
            route == null ? "" : route.model(),
            blankToDefault(strategySnapshotId, "default"),
            "requirement-test-point-analysis",
            "[\"requirement-test-point-analysis\"]",
            inputSummary.path("effectiveRules").path("effectiveVersion").asText("system-v1"),
            "text",
            result == null ? "" : result.requestId(),
            result != null && result.success() ? "succeeded" : "failed",
            "",
            result == null ? errorMessage : blankToDefault(result.errorMessage(), ""),
            durationMs,
            result == null ? 0 : result.inputTokens(),
            result == null ? 0 : result.outputTokens(),
            0,
            AiCallRecordPayloads.compactJson(objectMapper, inputSummary),
            result == null ? null : stringify(objectMapper.createObjectNode().put("contentLength", result.content().length())),
            result == null ? null : AiCallRecordPayloads.compactText(objectMapper, result.content()),
            com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystem(),
            com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystemName(),
            Timestamp.from(now),
            Timestamp.from(now)
        );
    }

    private Map<String, Object> findRun(String runId) {
        return jdbcTemplate.queryForMap("select * from ai_analysis_runs where run_id = ?", runId);
    }

    private Map<String, Object> findRunOrNull(String runId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("select * from ai_analysis_runs where run_id = ?", runId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Long runPk(Map<String, Object> run) {
        return ((Number) run.get("ID")).longValue();
    }

    private int completedChunks(Long runPk) {
        return jdbcTemplate.queryForObject(
            "select count(*) from ai_analysis_chunks where run_id = ? and status in ('succeeded', 'skipped')",
            Integer.class,
            runPk
        );
    }

    private int totalCompletedChunks(Long runPk) {
        return jdbcTemplate.queryForObject(
            "select count(*) from ai_analysis_chunks where run_id = ? and status in ('succeeded', 'skipped')",
            Integer.class,
            runPk
        );
    }

    private int draftPointCount(Long runPk) {
        return jdbcTemplate.queryForObject(
            "select count(*) from ai_draft_test_points where run_id = ?",
            Integer.class,
            runPk
        );
    }

    private int callRecordCount(Long runPk) {
        return jdbcTemplate.queryForObject(
            "select count(*) from ai_call_records where run_id = ?",
            Integer.class,
            runPk
        );
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            return objectMapper.createObjectNode();
        }
    }

    private JsonNode parseArray(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createArrayNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            return objectMapper.createArrayNode();
        }
    }

    private String stringify(JsonNode node) {
        if (node == null) return null;
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid AI worker payload", exception);
        }
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}

record AiAnalysisWorkerResult(
    boolean processed,
    String runId,
    String status,
    int completedChunks,
    String errorMessage
) {
}
