package com.haitlify.aitest.platform.ai.agentic;

import java.time.Instant;
import java.time.Clock;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.List;
import java.util.function.LongSupplier;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import com.haitlify.aitest.platform.ai.agentic.WebCaseAgentModels.WebCaseAgentEventType;
import com.haitlify.aitest.platform.ai.agentic.WebCaseAgentModels.WebCaseAgentRunStatus;
import com.haitlify.aitest.platform.ai.agentic.WebCaseAgentModels.WebCaseAgentStage;
import com.haitlify.aitest.platform.ai.draft.AgentWebCaseGenerationFacade;
import com.haitlify.aitest.platform.ai.draft.WebExplorationBlockedException;
import com.haitlify.aitest.platform.ai.agentic.memory.AgentMemoryService;
import com.haitlify.aitest.platform.ai.agentic.locator.LocatorKnowledgeService;
import com.haitlify.aitest.platform.ai.agentic.locator.LocatorKnowledgePersistenceFailure;
import com.haitlify.aitest.platform.ai.quality.WebCaseQualityFeedbackService;
import com.haitlify.aitest.platform.webautomation.WebAutomationLocatorDiscoveryService;
import com.haitlify.aitest.platform.webautomation.WebAutomationEvidenceException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
class WebCaseAgentDispatcher {
    private static final long DEFAULT_BATCH_TOTAL_DURATION_MS = 900_000L;
    private static final long MAX_POINT_BUDGET_MS = 90_000L;
    private static final Logger log = LoggerFactory.getLogger(WebCaseAgentDispatcher.class);
    private final WebCaseAgentRunService runService;
    private final WebCaseAgentEventService events;
    private final AgentWebCaseGenerationFacade generator;
    private final AgentMemoryService memories;
    private final LocatorKnowledgeService locatorKnowledge;
    private final WebCaseQualityFeedbackService qualityFeedback;
    private final WebAutomationLocatorDiscoveryService browserDiscovery;
    private final Clock clock;
    private final LongSupplier nanoTimeSource;
    private final Map<String, Future<?>> active = new ConcurrentHashMap<>();
    private final Map<String, WebCaseAgentModels.WebCaseAgentStartRequest> runtimeRequests = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(
        Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())),
        runnable -> { Thread thread = new Thread(runnable, "web-case-agent"); thread.setDaemon(true); return thread; }
    );

    @Autowired
    WebCaseAgentDispatcher(WebCaseAgentRunService runService, WebCaseAgentEventService events, AgentWebCaseGenerationFacade generator, AgentMemoryService memories, LocatorKnowledgeService locatorKnowledge, WebCaseQualityFeedbackService qualityFeedback, WebAutomationLocatorDiscoveryService browserDiscovery) {
        this(runService, events, generator, memories, locatorKnowledge, qualityFeedback, browserDiscovery, Clock.systemUTC(), System::nanoTime);
    }

    WebCaseAgentDispatcher(WebCaseAgentRunService runService, WebCaseAgentEventService events, AgentWebCaseGenerationFacade generator, AgentMemoryService memories, LocatorKnowledgeService locatorKnowledge, WebCaseQualityFeedbackService qualityFeedback, WebAutomationLocatorDiscoveryService browserDiscovery, Clock clock, LongSupplier nanoTimeSource) {
        this.runService = runService;
        this.events = events;
        this.generator = generator;
        this.memories = memories;
        this.locatorKnowledge = locatorKnowledge;
        this.qualityFeedback = qualityFeedback;
        this.browserDiscovery = browserDiscovery;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.nanoTimeSource = nanoTimeSource == null ? System::nanoTime : nanoTimeSource;
    }

    void dispatch(String runId, WebCaseAgentModels.WebCaseAgentStartRequest request) {
        runtimeRequests.put(runId, request);
        dispatch(runId, snapshotSecurityContext());
    }

    void dispatchWithContext(String runId, WebCaseAgentModels.WebCaseAgentStartRequest request, SecurityContext actorContext) {
        if (request != null) runtimeRequests.put(runId, request);
        dispatch(runId, actorContext == null ? snapshotSecurityContext() : actorContext);
    }

    void dispatch(String runId) {
        dispatch(runId, snapshotSecurityContext());
    }

    /**
     * The agent runs after the HTTP request has completed, on a pooled worker
     * thread.  SecurityContextHolder is thread-local, so capture the actor at
     * submission time and install it only for this task.  Do not use the
     * mutable caller context directly: authentication must not leak between
     * requests or pooled tasks.
     */
    private void dispatch(String runId, SecurityContext actorContext) {
        Future<?> future = executor.submit(() -> {
            SecurityContext previousContext = SecurityContextHolder.getContext();
            try {
                SecurityContextHolder.setContext(actorContext == null ? SecurityContextHolder.createEmptyContext() : actorContext);
                BatchBudgetLedger batchBudgetLedger = null;
                List<ObjectNode> batchSuccesses = new ArrayList<>();
                List<ObjectNode> batchBlocked = new ArrayList<>();
                runService.markRunning(runId);
                events.append(runId, WebCaseAgentStage.PREFLIGHT, WebCaseAgentEventType.ACTION, "info", "Agent 开始执行环境预检", null, "preflight", 0, runService.ruleVersion(runId), null, null);
                if (runService.preflightBlocked(runId)) return;
                WebCaseAgentModels.WebCaseAgentStartRequest request = runtimeRequests.get(runId);
                if (request == null) {
                    runService.fail(runId, "该 Agent 任务缺少本次浏览器会话配置；请从生成页重新启动任务");
                    return;
                }
                batchBudgetLedger = BatchBudgetLedger.start(request.batchTotalDurationMs(), clock, nanoTimeSource);
                Long projectId = runService.projectId(runId);
                var memoryContext = new java.util.ArrayList<>(memories.promptContext(projectId));
                List<String> qualityHints = List.of();
                try {
                    qualityHints = qualityFeedback.feedbackContext(projectId, runId);
                } catch (Exception feedbackFailure) {
                    // 质量反馈是可选增强:计算失败只影响提示增益,绝不能阻断主生成流程。
                    events.append(runId, WebCaseAgentStage.PLANNING, WebCaseAgentEventType.ACTION, "warn", "质量反馈计算失败,本次跳过注入", null, "quality-feedback", 0, runService.ruleVersion(runId), null, null);
                }
                memoryContext.addAll(qualityHints);
                if (!qualityHints.isEmpty()) {
                    events.append(runId, WebCaseAgentStage.PLANNING, WebCaseAgentEventType.ACTION, "info", "已注入 " + qualityHints.size() + " 条质量反馈(基于历史评分弱维与丢失用例)", null, "quality-feedback", 0, runService.ruleVersion(runId), null, null);
                }
                var pointIds = runService.requestedPointIds(runId);
                var completedPointIds = runService.completedPointIds(runId);
                var skippedPointIds = pointIds.stream().filter(completedPointIds::contains).toList();
                var pendingPointIds = pointIds.stream().filter(id -> !completedPointIds.contains(id)).toList();
                runService.markStage(runId, WebCaseAgentStage.PLANNING);
                events.append(runId, WebCaseAgentStage.PLANNING, WebCaseAgentEventType.ACTION, "info", "正在依据项目规则、经验记忆和需求测试点生成自然语言测试设计", null, "case-planning", 0, runService.ruleVersion(runId), null, null);
                if (!skippedPointIds.isEmpty()) {
                    events.append(runId, WebCaseAgentStage.PLANNING, WebCaseAgentEventType.STATUS, "info", "续跑跳过 " + skippedPointIds.size() + " 个已完成测试点，仅处理 " + pendingPointIds.size() + " 个待处理点", null, "resume-skip-completed", 0, runService.ruleVersion(runId), skippedPointIds, null);
                }
                AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch plan;
                if (runService.mode(runId) == WebCaseAgentModels.WebCaseAgentMode.PLAN) {
                    plan = generator.plan(pointIds, discoveryOptions(request), runService.ruleSnapshot(runId), memoryContext);
                    runService.saveNaturalPlanArtifacts(runId, plan.plans(), plan.providerRequestId());
                    runService.completePlan(runId, plan.plans());
                    events.append(runId, WebCaseAgentStage.DELIVERY, WebCaseAgentEventType.OUTPUT_SUMMARY, "info", "已保存 " + plan.plans().size() + " 条自然语言用例，等待质量评审", null, "plan-delivery", 0, runService.ruleVersion(runId), pointIds, null);
                    return;
                }
                if (runService.planRunId(runId).isBlank()) throw new IllegalArgumentException("自动化编译缺少已评审的自然语言计划");
                plan = runService.confirmedPlanBatch(runService.planRunId(runId));
                events.append(runId, WebCaseAgentStage.PLANNING, WebCaseAgentEventType.OUTPUT_SUMMARY, "info", "已读取评审确认的自然语言用例，禁止重新规划", null, "reviewed-plan", 0, runService.ruleVersion(runId), plan.plans().stream().map(AgentWebCaseGenerationFacade.NaturalWebCasePlan::sourcePointId).toList(), null);
                for (int index = 0; index < pendingPointIds.size(); index++) {
                    String pointId = pendingPointIds.get(index);
                    long pointStartedNanos = batchBudgetLedger.nowNanos();
                    long pointBudgetMs = batchBudgetLedger.pointBudgetMs();
                    runService.ensureNotCancelled(runId);
                    if (pointBudgetMs <= 0) {
                        runService.markPointBlocked(runId, pointId);
                        batchBlocked.add(batchDurationBlocked(pointId, "duration_exhausted", 0L, pointStartedNanos, null, null, batchBudgetLedger));
                        events.append(runId, WebCaseAgentStage.EVIDENCE, WebCaseAgentEventType.STATUS, "warn", "批次预算已耗尽，当前测试点直接阻塞", pointId, "batch-budget", 0, runService.ruleVersion(runId), null, "duration_exhausted");
                        continue;
                    }
                    runService.markStage(runId, WebCaseAgentStage.EVIDENCE);
                    events.append(runId, WebCaseAgentStage.EVIDENCE, WebCaseAgentEventType.ACTION, "info", "优先加载历史素材候选，再通过浏览器 UI 实际复核页面元素", pointId, "browser-discovery", 0, runService.ruleVersion(runId), null, null);
                    AgentWebCaseGenerationFacade.BrowserEvidenceBundle evidenceBundle;
                    try {
                        evidenceBundle = generator.resolveEvidence(List.of(pointId), plan, discoveryOptions(request), List.of(), pointBudgetMs);
                    } catch (WebExplorationBlockedException evidenceFailure) {
                        var report = evidenceFailure.report();
                        String structured = explorationFailureSummary("浏览器探索未命中目标", report);
                        runService.markPointBlocked(runId, pointId);
                        batchBlocked.add(batchDurationBlocked(pointId, "browser_evidence_unmatched", pointBudgetMs, pointStartedNanos, report, null, batchBudgetLedger));
                        emitExplorationEvents(runId, pointId, report);
                        events.appendDetailed(runId, WebCaseAgentStage.EVIDENCE, WebCaseAgentEventType.ERROR, "error", structured, pointId, "browser-discovery", report == null ? 0 : report.durationMs(), runService.ruleVersion(runId), null, "browser_evidence_unmatched", explorationFailureDetails(report, evidenceFailure.failureReason()));
                        continue;
                    } catch (WebAutomationEvidenceException evidenceFailure) {
                        runService.markPointBlocked(runId, pointId);
                        String category = evidenceFailure.category().isBlank() ? "browser_evidence_blocked" : evidenceFailure.category();
                        batchBlocked.add(batchDurationBlocked(pointId, category, pointBudgetMs, pointStartedNanos, null, evidenceFailure.details(), batchBudgetLedger));
                        String message = "浏览器证据采集失败：" + category;
                        String networkHint = networkBlockedHint(evidenceFailure.details());
                        if (!networkHint.isBlank()) message = message + "；" + networkHint;
                        events.appendDetailed(runId, WebCaseAgentStage.EVIDENCE, WebCaseAgentEventType.ERROR, "error", message, pointId, "browser-discovery", 0, runService.ruleVersion(runId), null, category, evidenceFailure.details());
                        continue;
                    } catch (DataAccessException evidenceFailure) {
                        if (evidenceFailure instanceof LocatorKnowledgePersistenceFailure) {
                            handleLocatorKnowledgePersistenceFailure(runId, pointId, evidenceFailure);
                            batchBlocked.add(batchDurationBlocked(pointId, "locator_knowledge_persistence_failed", pointBudgetMs, pointStartedNanos, null, null, batchBudgetLedger));
                        } else {
                            handleEvidenceReadFailure(runId, pointId, evidenceFailure);
                            batchBlocked.add(batchDurationBlocked(pointId, "browser_evidence_read_failed", pointBudgetMs, pointStartedNanos, null, null, batchBudgetLedger));
                        }
                        continue;
                    } catch (Exception evidenceFailure) {
                        String message = safeAgentFailureMessage(evidenceFailure, "浏览器证据采集失败");
                        runService.markPointBlocked(runId, pointId);
                        batchBlocked.add(batchDurationBlocked(pointId, "browser_evidence_blocked", pointBudgetMs, pointStartedNanos, null, null, batchBudgetLedger));
                        events.append(runId, WebCaseAgentStage.EVIDENCE, WebCaseAgentEventType.ERROR, "error", message, pointId, "browser-discovery", 0, runService.ruleVersion(runId), null, "browser_evidence_blocked");
                        continue;
                    }
                    emitExplorationEvents(runId, pointId, evidenceBundle);
                    if (batchBudgetLedger.remainingMs() <= 0) {
                        runService.markPointBlocked(runId, pointId);
                        batchBlocked.add(batchDurationBlocked(pointId, "duration_exhausted", pointBudgetMs, pointStartedNanos, evidenceBundle == null ? null : evidenceBundle.explorationReport(), null, batchBudgetLedger));
                        events.append(runId, WebCaseAgentStage.EVIDENCE, WebCaseAgentEventType.STATUS, "warn", "批次预算已耗尽，当前测试点无法继续编译", pointId, "batch-budget", 0, runService.ruleVersion(runId), evidenceBundle.knowledgeHitIds(), "duration_exhausted");
                        continue;
                    }
                    if (evidenceBundle.sessionId() != null && !evidenceBundle.sessionId().isBlank() && request.browserDiscovery() != null) {
                        request = withBrowserSession(request, evidenceBundle.sessionId());
                        runtimeRequests.put(runId, request);
                    }
                    try {
                        captureLocatorKnowledge(runId, request, evidenceBundle);
                    } catch (DataAccessException persistenceFailure) {
                        handleLocatorKnowledgePersistenceFailure(runId, pointId, persistenceFailure);
                        batchBlocked.add(batchDurationBlocked(pointId, "locator_knowledge_persistence_failed", pointBudgetMs, pointStartedNanos, evidenceBundle == null ? null : evidenceBundle.explorationReport(), null, batchBudgetLedger));
                        continue;
                    }
                    String evidenceSummary = evidenceBundle.usedBrowserDiscovery()
                        ? "素材库缺少当前测试目标的可用元素，已登录目标页面采集 " + evidenceBundle.elementCount() + " 个元素并写入素材库"
                        : "已从素材库取得 " + evidenceBundle.knowledgeHitIds().size() + " 条可用元素定位";
                    events.append(runId, WebCaseAgentStage.EVIDENCE, WebCaseAgentEventType.TOOL_RESULT, "info", evidenceSummary, pointId, "locator-knowledge", 0, runService.ruleVersion(runId), evidenceBundle.knowledgeHitIds(), null);
                    List<String> knowledgeHitIds = evidenceBundle.knowledgeHitIds();
                    if (!safeRecordLocatorKnowledge(runId, pointId, () -> locatorKnowledge.recordHit(runId, pointId, knowledgeHitIds))) {
                        batchBlocked.add(batchDurationBlocked(pointId, "locator_knowledge_persistence_failed", pointBudgetMs, pointStartedNanos, evidenceBundle == null ? null : evidenceBundle.explorationReport(), null, batchBudgetLedger));
                        continue;
                    }
                    memories.recordPromptUsage(runId, pointId, runService.projectId(runId));
                    runService.ensureNotCancelled(runId);
                    runService.markStage(runId, WebCaseAgentStage.GENERATION);
                    events.append(runId, WebCaseAgentStage.GENERATION, WebCaseAgentEventType.ACTION, "info", "正在编译可执行 Web 用例", pointId, "case-generation", 0, runService.ruleVersion(runId), null, null);
                    long started = System.currentTimeMillis();
                    try {
                        var generated = generator.compile(List.of(pointId), plan, evidenceBundle, discoveryOptions(request), runService.ruleSnapshot(runId), memoryContext);
                        runService.ensureNotCancelled(runId);
                        runService.markStage(runId, WebCaseAgentStage.VALIDATION);
                        events.append(runId, WebCaseAgentStage.VALIDATION, WebCaseAgentEventType.VALIDATION, "info", "浏览器证据与 Web DSL 断言校验已通过", pointId, "web-dsl-validation", System.currentTimeMillis() - started, runService.ruleVersion(runId), evidenceBundle.knowledgeHitIds(), null);
                        runService.saveArtifacts(runId, generated, evidenceBundle.knowledgeHitIds());
                        runService.markPointCompleted(runId, pointId);
                        batchSuccesses.add(batchSuccess(pointId, pointBudgetMs, pointStartedNanos, evidenceBundle, batchBudgetLedger));
                    } catch (Exception compilationFailure) {
                        String message = compilationFailure.getMessage() == null ? "Web 用例自动化编译失败" : compilationFailure.getMessage();
                        String safeMessage = safeAgentFailureMessage(compilationFailure, "Web 用例自动化编译失败");
                        boolean semanticMismatch = isRetryableSemanticMismatch(message);
                        long retryBudgetMs = batchBudgetLedger.pointBudgetMs();
                        if (semanticMismatch && retryBudgetMs > 5_000L) {
                            try {
                                events.append(runId, WebCaseAgentStage.EVIDENCE, WebCaseAgentEventType.ACTION, "info", "编译语义断言未命中，执行一次受限浏览器重探", pointId, "browser-discovery-retry", 0, runService.ruleVersion(runId), null, "browser_evidence_retry");
                                var retriedEvidence = generator.resolveEvidence(List.of(pointId), plan, discoveryOptions(request), List.of(safeRetryFailureMessage(message)), retryBudgetMs);
                                evidenceBundle = retriedEvidence;
                                emitExplorationEvents(runId, pointId, evidenceBundle);
                                try {
                                    captureLocatorKnowledge(runId, request, evidenceBundle);
                                } catch (DataAccessException persistenceFailure) {
                                    handleLocatorKnowledgePersistenceFailure(runId, pointId, persistenceFailure);
                                    batchBlocked.add(batchDurationBlocked(pointId, "locator_knowledge_persistence_failed", pointBudgetMs, pointStartedNanos, evidenceBundle == null ? null : evidenceBundle.explorationReport(), null, batchBudgetLedger));
                                    continue;
                                }
                                if (batchBudgetLedger.remainingMs() <= 0) {
                                    runService.markPointBlocked(runId, pointId);
                                    batchBlocked.add(batchDurationBlocked(pointId, "duration_exhausted", pointBudgetMs, pointStartedNanos, evidenceBundle == null ? null : evidenceBundle.explorationReport(), null, batchBudgetLedger));
                                    events.append(runId, WebCaseAgentStage.EVIDENCE, WebCaseAgentEventType.STATUS, "warn", "批次预算已耗尽，当前测试点无法继续编译", pointId, "batch-budget", 0, runService.ruleVersion(runId), evidenceBundle.knowledgeHitIds(), "duration_exhausted");
                                    continue;
                                }
                                var retriedGenerated = generator.compile(List.of(pointId), plan, evidenceBundle, discoveryOptions(request), runService.ruleSnapshot(runId), memoryContext);
                                runService.ensureNotCancelled(runId);
                                runService.markStage(runId, WebCaseAgentStage.VALIDATION);
                                events.append(runId, WebCaseAgentStage.VALIDATION, WebCaseAgentEventType.VALIDATION, "info", "浏览器重探后证据与 Web DSL 断言校验已通过", pointId, "web-dsl-validation", System.currentTimeMillis() - started, runService.ruleVersion(runId), evidenceBundle.knowledgeHitIds(), null);
                                runService.saveArtifacts(runId, retriedGenerated, evidenceBundle.knowledgeHitIds());
                                runService.markPointCompleted(runId, pointId);
                                batchSuccesses.add(batchSuccess(pointId, pointBudgetMs, pointStartedNanos, evidenceBundle, batchBudgetLedger));
                                continue;
                            } catch (WebExplorationBlockedException retryEvidenceFailure) {
                                if (!safeRecordLocatorKnowledge(runId, pointId, () -> locatorKnowledge.recordExecutionOutcome(runId, pointId, false))) {
                                    batchBlocked.add(batchDurationBlocked(pointId, "locator_knowledge_persistence_failed", pointBudgetMs, pointStartedNanos, retryEvidenceFailure.report(), null, batchBudgetLedger));
                                    continue;
                                }
                                runService.markPointBlocked(runId, pointId);
                                batchBlocked.add(batchDurationBlocked(pointId, "browser_evidence_unmatched", pointBudgetMs, pointStartedNanos, retryEvidenceFailure.report(), null, batchBudgetLedger));
                                emitExplorationEvents(runId, pointId, retryEvidenceFailure.report());
                                events.appendDetailed(runId, WebCaseAgentStage.EVIDENCE, WebCaseAgentEventType.ERROR, "error", explorationFailureSummary("重探未命中目标", retryEvidenceFailure.report()), pointId, "browser-discovery-retry", System.currentTimeMillis() - started, runService.ruleVersion(runId), evidenceBundle.knowledgeHitIds(), "browser_evidence_unmatched", explorationFailureDetails(retryEvidenceFailure.report(), retryEvidenceFailure.failureReason()));
                                continue;
                            } catch (Exception retryFailure) {
                                message = retryFailure.getMessage() == null ? message : retryFailure.getMessage();
                                safeMessage = safeAgentFailureMessage(retryFailure, safeMessage);
                            }
                        }
                        if (!safeRecordLocatorKnowledge(runId, pointId, () -> locatorKnowledge.recordExecutionOutcome(runId, pointId, false))) {
                            batchBlocked.add(batchDurationBlocked(pointId, "locator_knowledge_persistence_failed", pointBudgetMs, pointStartedNanos, evidenceBundle == null ? null : evidenceBundle.explorationReport(), null, batchBudgetLedger));
                            continue;
                        }
                        runService.markPointBlocked(runId, pointId);
                        batchBlocked.add(batchDurationBlocked(pointId, semanticMismatch ? "element_not_found" : "unsupported_dom", pointBudgetMs, pointStartedNanos, evidenceBundle == null ? null : evidenceBundle.explorationReport(), null, batchBudgetLedger));
                        events.append(runId, WebCaseAgentStage.GENERATION, WebCaseAgentEventType.ERROR, "error", safeMessage, pointId, "case-generation", System.currentTimeMillis() - started, runService.ruleVersion(runId), evidenceBundle == null ? null : evidenceBundle.knowledgeHitIds(), "web_case_compile_blocked");
                        continue;
                    }
                    if (index + 1 < pendingPointIds.size()) runService.markStage(runId, WebCaseAgentStage.PLANNING);
                }
                int generatedCount = batchSuccesses.size();
                runService.markStage(runId, WebCaseAgentStage.DELIVERY);
                runService.ensureNotCancelled(runId);
                JsonNode batchSummaryNode = batchSummary(batchBudgetLedger, batchSuccesses, batchBlocked, generatedCount, pendingPointIds.size());
                if (generatedCount > 0 && batchBlocked.isEmpty()) {
                    runService.succeed(runId, generatedCount);
                    memories.createDeliveryCandidate(runId, runService.projectId(runId), generatedCount, runService.ruleVersion(runId));
                    events.append(runId, WebCaseAgentStage.DELIVERY, WebCaseAgentEventType.OUTPUT_SUMMARY, "info", "已追加 " + generatedCount + " 条可评审 Web 用例", null, "artifact-delivery", 0, runService.ruleVersion(runId), null, null);
                } else if (generatedCount > 0) {
                    // T10 bullet 4：部分成功 ⇒ partial，绝不把 generatedCount>0 当整批成功。
                    runService.partial(runId, generatedCount, batchBlocked.size());
                    memories.createDeliveryCandidate(runId, runService.projectId(runId), generatedCount, runService.ruleVersion(runId));
                    events.append(runId, WebCaseAgentStage.DELIVERY, WebCaseAgentEventType.OUTPUT_SUMMARY, "warn",
                        "部分成功：已追加 " + generatedCount + " 条可评审 Web 用例，" + batchBlocked.size() + " 个测试点因证据/支持缺失被阻塞",
                        null, "artifact-delivery", 0, runService.ruleVersion(runId), null, "partial_success");
                } else {
                    runService.block(runId, "所有测试点都未产出可执行 Web 用例");
                    events.append(runId, WebCaseAgentStage.DELIVERY, WebCaseAgentEventType.STATUS, "warn", "所有测试点都被证据阻塞，未生成可评审 Web 用例", null, "artifact-delivery", 0, runService.ruleVersion(runId), null, "all_points_blocked");
                }
                runService.writeOutputSummary(runId, batchSummaryNode);
            } catch (Exception exception) {
                String message = safeAgentFailureMessage(exception, "Agent 执行失败");
                if (runService.isCancelled(runId)) return;
                runService.fail(runId, message);
                events.append(runId, WebCaseAgentStage.GENERATION, WebCaseAgentEventType.ERROR, "error", message, null, "agent-run", 0, runService.ruleVersion(runId), null, "agent_failed");
            } finally {
                try {
                    var finalStatus = runService.get(runId).status();
                    if (finalStatus == WebCaseAgentRunStatus.SUCCEEDED || finalStatus == WebCaseAgentRunStatus.CANCELLED) {
                        runtimeRequests.remove(runId);
                    }
                } catch (Exception ignored) {
                    runtimeRequests.remove(runId);
                }
                SecurityContextHolder.setContext(previousContext);
                active.remove(runId);
            }
        });
        active.put(runId, future);
    }

    private SecurityContext snapshotSecurityContext() {
        SecurityContext snapshot = SecurityContextHolder.createEmptyContext();
        snapshot.setAuthentication(SecurityContextHolder.getContext().getAuthentication());
        return snapshot;
    }

    void cancel(String runId) { runService.requestCancel(runId); Future<?> future = active.get(runId); if (future != null) future.cancel(true); }

    private CompilationFailureKind classifyCompilationFailure(String message) {
        if (message == null || message.isBlank()) return CompilationFailureKind.NON_RETRYABLE;
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("认证") || lower.contains("登录失败") || lower.contains("网络") || lower.contains("network")
            || lower.contains("provider") || lower.contains("供应商") || lower.contains("取消") || lower.contains("cancel")
            || lower.contains("json")) return CompilationFailureKind.NON_RETRYABLE;
        if (message.contains("元素断言未命中") || message.contains("不能只使用 assertUrl")
            || message.contains("locator 未由本批浏览器采集")
            || message.contains("断言与预期结果均退化为 DSL")
            || message.contains("测试目标涉及页面元素/展示/顺序")) {
            return CompilationFailureKind.RETRYABLE_SEMANTIC;
        }
        return CompilationFailureKind.NON_RETRYABLE;
    }

    boolean isRetryableSemanticMismatch(String message) {
        return classifyCompilationFailure(message) == CompilationFailureKind.RETRYABLE_SEMANTIC;
    }

    private enum CompilationFailureKind { RETRYABLE_SEMANTIC, NON_RETRYABLE }

    private ObjectNode batchSuccess(String pointId, long pointBudgetMs, long pointStartedNanos, AgentWebCaseGenerationFacade.BrowserEvidenceBundle bundle, BatchBudgetLedger ledger) {
        JsonNode report = bundle == null ? null : bundle.explorationReport();
        JsonNode network = report == null || report.isNull() ? null : report.path("network");
        JsonNode repairTrail = report == null || report.isNull() ? null : report.path("repairTrail");
        int rounds = report == null || report.isNull() ? (bundle == null ? 0 : bundle.explorationRounds()) : report.path("rounds").isArray() ? report.path("rounds").size() : bundle == null ? 0 : bundle.explorationRounds();
        String status = report == null || report.isNull() ? (bundle == null ? "success" : bundle.explorationStatus()) : report.path("status").asText(bundle == null ? "success" : bundle.explorationStatus());
        return batchOutcome(pointId, "success", pointBudgetMs, ledger == null ? 0L : ledger.elapsedMsSince(pointStartedNanos), rounds, status, network, repairTrail);
    }

    private ObjectNode batchDurationBlocked(String pointId, String category, long pointBudgetMs, long pointStartedNanos, Object reportLike, JsonNode fallbackReport, BatchBudgetLedger ledger) {
        JsonNode network = null;
        JsonNode repairTrail = null;
        int rounds = 0;
        String status = "";
        long durationMs = ledger == null ? 0L : ledger.elapsedMsSince(pointStartedNanos);
        if (reportLike instanceof WebAutomationLocatorDiscoveryService.ExplorationReport report) {
            network = report.network();
            repairTrail = report.repairTrail();
            rounds = report.rounds() == null ? 0 : report.rounds().size();
            status = report.status();
            durationMs = report.durationMs() > 0 ? report.durationMs() : durationMs;
        } else if (fallbackReport != null && !fallbackReport.isNull()) {
            network = fallbackReport.path("network");
            repairTrail = fallbackReport.path("repairTrail");
            rounds = fallbackReport.path("rounds").isArray() ? fallbackReport.path("rounds").size() : 0;
            status = fallbackReport.path("status").asText("");
            durationMs = fallbackReport.path("durationMs").asLong(durationMs);
        }
        return batchOutcome(pointId, category, pointBudgetMs, durationMs, rounds, status, network, repairTrail);
    }

    private ObjectNode batchOutcome(String pointId, String category, long pointBudgetMs, long durationMs, int rounds, String status, JsonNode network, JsonNode repairTrail) {
        ObjectNode outcome = JsonNodeFactory.instance.objectNode()
            .put("sourcePointId", pointId)
            .put("category", safe(category))
            .put("rounds", Math.max(0, rounds))
            .put("durationMs", Math.max(0L, durationMs))
            .put("pointBudgetMs", Math.max(0L, pointBudgetMs))
            .put("status", safe(status));
        outcome.set("network", batchNetworkSummary(network));
        outcome.set("repairTrail", repairTrail == null || repairTrail.isNull() ? JsonNodeFactory.instance.arrayNode() : repairTrail);
        return outcome;
    }

    private ObjectNode batchNetworkSummary(JsonNode network) {
        ObjectNode summary = JsonNodeFactory.instance.objectNode();
        int blockedCount = blockedResourceCount(network);
        summary.put("blockedResourceCount", blockedCount);
        summary.set("blockedResources", blockedResourceDetails(network));
        return summary;
    }

    private JsonNode batchSummary(BatchBudgetLedger ledger, List<ObjectNode> successes, List<ObjectNode> blocked, int generatedCount, int pointCount) {
        ObjectNode summary = JsonNodeFactory.instance.objectNode();
        summary.put("status", batchStatus(successes, blocked));
        summary.put("batchTotalDurationMs", ledger == null ? DEFAULT_BATCH_TOTAL_DURATION_MS : ledger.totalDurationMs());
        summary.put("batchStartedAt", ledger == null ? clock.instant().toString() : ledger.startedAt().toString());
        summary.put("batchRemainingDurationMs", ledger == null ? 0L : ledger.remainingMs());
        summary.put("batchElapsedDurationMs", ledger == null ? 0L : ledger.elapsedMs());
        summary.put("pointCount", Math.max(0, pointCount));
        summary.put("generatedCaseCount", Math.max(0, generatedCount));
        summary.put("successCount", successes.size());
        summary.put("blockedCount", blocked.size());
        ArrayNode successArray = JsonNodeFactory.instance.arrayNode();
        successes.forEach(successArray::add);
        ArrayNode blockedArray = JsonNodeFactory.instance.arrayNode();
        blocked.forEach(blockedArray::add);
        summary.set("successes", successArray);
        summary.set("blocked", blockedArray);
        return summary;
    }

    private String batchStatus(List<ObjectNode> successes, List<ObjectNode> blocked) {
        if (successes.isEmpty() && blocked.isEmpty()) return "empty";
        if (successes.isEmpty()) return "blocked";
        if (blocked.isEmpty()) return "success";
        return "partial_success";
    }

    static final class BatchBudgetLedger {
        private final Clock clock;
        private final LongSupplier nanoTimeSource;
        private final long startedNanos;
        private final long totalDurationMs;
        private final Instant startedAt;

        private BatchBudgetLedger(Clock clock, LongSupplier nanoTimeSource, long totalDurationMs) {
            this.clock = clock;
            this.nanoTimeSource = nanoTimeSource;
            this.totalDurationMs = sanitizeBatchDuration(totalDurationMs);
            this.startedNanos = nanoTimeSource.getAsLong();
            this.startedAt = clock.instant();
        }

        static BatchBudgetLedger start(Long totalDurationMs, Clock clock, LongSupplier nanoTimeSource) {
            return new BatchBudgetLedger(clock, nanoTimeSource, totalDurationMs == null ? DEFAULT_BATCH_TOTAL_DURATION_MS : totalDurationMs);
        }

        long totalDurationMs() {
            return totalDurationMs;
        }

        Instant startedAt() {
            return startedAt;
        }

        long elapsedMs() {
            return elapsedMsSince(startedNanos);
        }

        long remainingMs() {
            return Math.max(0L, totalDurationMs - elapsedMs());
        }

        long pointBudgetMs() {
            return Math.min(MAX_POINT_BUDGET_MS, remainingMs());
        }

        long nowNanos() {
            return nanoTimeSource.getAsLong();
        }

        long elapsedMsSince(long startedAtNanos) {
            return Math.max(0L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(nowNanos() - startedAtNanos));
        }

        private static long sanitizeBatchDuration(long totalDurationMs) {
            if (totalDurationMs <= 0L) return DEFAULT_BATCH_TOTAL_DURATION_MS;
            return Math.min(totalDurationMs, DEFAULT_BATCH_TOTAL_DURATION_MS);
        }
    }

    private String explorationFailureSummary(String prefix, WebAutomationLocatorDiscoveryService.ExplorationReport report) {
        return prefix + blockedResourceSummary(report == null ? null : report.network());
    }

    private String blockedResourceSummary(JsonNode network) {
        int blockedCount = blockedResourceCount(network);
        return blockedCount <= 0 ? "" : "（blockedResources=" + blockedCount + "）";
    }

    private int blockedResourceCount(JsonNode network) {
        if (network == null || !network.isObject()) return 0;
        int count = 0;
        for (String bucket : java.util.List.of("criticalBlocked", "unknownBlocked", "optionalBlocked")) {
            JsonNode entries = network.path(bucket);
            if (entries.isArray()) count += entries.size();
        }
        return count;
    }

    private ObjectNode explorationFailureDetails(WebAutomationLocatorDiscoveryService.ExplorationReport report, String failureReason) {
        ObjectNode details = JsonNodeFactory.instance.objectNode();
        JsonNode network = report == null ? null : report.network();
        details.put("status", safe(report == null ? "" : report.status()));
        details.put("rounds", report == null || report.rounds() == null ? 0 : report.rounds().size());
        details.put("blockedResourceCount", blockedResourceCount(network));
        details.put("failureReason", safeDiagnosticMessage(failureReason));
        if (report != null && report.scores() != null) {
            details.set("scores", JsonNodeFactory.instance.objectNode()
                .put("pageScore", report.scores().pageScore())
                .put("elementScore", report.scores().elementScore())
                .put("stateScore", report.scores().stateScore())
                .put("totalScore", report.scores().totalScore()));
        }
        if (network != null && network.isObject()) details.set("blockedResources", blockedResourceDetails(network));
        return details;
    }

    private tools.jackson.databind.node.ArrayNode blockedResourceDetails(JsonNode network) {
        tools.jackson.databind.node.ArrayNode blockedResources = JsonNodeFactory.instance.arrayNode();
        if (network == null || !network.isObject()) return blockedResources;
        for (String bucket : java.util.List.of("criticalBlocked", "unknownBlocked", "optionalBlocked")) {
            JsonNode entries = network.path(bucket);
            if (!entries.isArray()) continue;
            for (JsonNode entry : entries) {
                ObjectNode item = JsonNodeFactory.instance.objectNode().put("bucket", bucket);
                String origin = safeOrigin(entry.path("origin").asText(""));
                String path = safePath(entry.path("path").asText(""));
                String resourceType = safeDiagnosticText(entry.path("resourceType").asText(""));
                if (!origin.isBlank()) item.put("origin", origin);
                if (!path.isBlank()) item.put("path", path);
                if (!resourceType.isBlank()) item.put("resourceType", resourceType);
                blockedResources.add(item);
                if (blockedResources.size() >= 12) return blockedResources;
            }
        }
        return blockedResources;
    }

    private void emitExplorationEvents(String runId, String pointId, AgentWebCaseGenerationFacade.BrowserEvidenceBundle bundle) {
        if (bundle == null || bundle.rawEvidence() == null || !bundle.rawEvidence().isArray()) return;
        for (JsonNode page : bundle.rawEvidence()) {
            if (!"browserDiscovery".equals(page.path("source").asText(""))) continue;
            JsonNode report = page.path("exploration");
            if (!report.isObject() || !report.path("rounds").isArray()) continue;
            int maxRounds = report.path("maxRounds").asInt(report.path("rounds").size());
            for (JsonNode round : report.path("rounds")) emitExplorationEvent(runId, pointId, round, maxRounds, report, page);
        }
    }

    private void emitExplorationEvents(String runId, String pointId, WebAutomationLocatorDiscoveryService.ExplorationReport report) {
        if (report == null || report.rounds() == null) return;
        int maxRounds = report.rounds().size();
        for (var round : report.rounds()) {
            var scores = round.scores();
            int candidateCount = scores == null || scores.relevantElements() == null || !scores.relevantElements().isArray() ? 0 : scores.relevantElements().size();
            int matchedLabelsCount = matchedLabelsCount(scores == null ? null : scores.relevantElements());
            String failureReason = round.actionFailure() == null ? "" : round.actionFailure().path("failureReason").asText("");
            if (failureReason.isBlank()) failureReason = safe(report.failureReason());
            ObjectNode details = explorationDetails(round.round(), maxRounds, round.pageUrl(), round.fingerprint(), round.actionType(), round.actionTarget(), round.observation(), scores == null ? 0 : scores.pageScore(), scores == null ? 0 : scores.elementScore(), scores == null ? 0 : scores.stateScore(), scores == null ? 0 : scores.totalScore(), candidateCount, matchedLabelsCount, failureReason, report.status(), report.durationMs(), round.actionFailure() == null ? "" : round.actionFailure().path("failureType").asText(""), round.actionFailure() != null && round.actionFailure().path("retryable").asBoolean(false));
            if (report.network() != null) details.set("network", report.network());
            if (report.readiness() != null) details.set("readiness", report.readiness());
            if (report.repairTrail() != null) details.set("repairTrail", report.repairTrail());
            if (report.network() != null || report.readiness() != null || report.repairTrail() != null || (report.rounds() != null && report.rounds().stream().anyMatch(item -> item.action() != null))) {
                attachExplorationMetrics(details, report, report.failureReason(), null);
            }
            events.appendDetailed(runId, WebCaseAgentStage.EVIDENCE, WebCaseAgentEventType.ACTION, "info", explorationSummary(round.round(), maxRounds, scores, round.actionType(), round.actionTarget()), pointId, "browser-discovery", 0, runService.ruleVersion(runId), null, null, details);
        }
    }

    private void emitExplorationEvent(String runId, String pointId, JsonNode round, int maxRounds, JsonNode report, JsonNode page) {
        JsonNode scores = round.path("scores");
        int candidateCount = round.path("candidateCount").asInt(round.path("candidates").isArray() ? round.path("candidates").size() : round.path("observation").path("candidates").size());
        String failureReason = round.path("actionFailure").path("failureReason").asText(report.path("failureReason").asText(""));
        if (candidateCount == 0 && scores.path("relevantElements").isArray()) candidateCount = scores.path("relevantElements").size();
        String pageUrl = round.path("pageUrl").asText(round.path("observation").path("url").asText(""));
        String actionType = round.path("actionType").asText(round.path("action").path("type").asText(""));
        String actionTarget = round.path("actionTarget").asText(round.path("action").path("target").asText(round.path("action").path("locator").asText("")));
        int matchedLabelsCount = matchedLabelsCount(scores.path("relevantElements"));
        ObjectNode details = explorationDetails(round.path("round").asInt(), maxRounds, pageUrl, round.path("fingerprint").asText(round.path("observation").path("fingerprint").asText("")), actionType, actionTarget, round.path("observation").isObject() ? round.path("observation") : page, scores.path("pageScore").asInt(), scores.path("elementScore").asInt(), scores.path("stateScore").asInt(), scores.path("totalScore").asInt(), candidateCount, matchedLabelsCount, failureReason, report.path("status").asText(""), report.path("durationMs").asLong(0), round.path("actionFailure").path("failureType").asText(""), round.path("actionFailure").path("retryable").asBoolean(false));
        if (report.path("network").isObject()) details.set("network", report.path("network"));
        if (report.path("readiness").isObject()) details.set("readiness", report.path("readiness"));
        if (report.path("repairTrail").isArray()) details.set("repairTrail", report.path("repairTrail"));
        if (page.path("screenshotUrl").isTextual() && !page.path("screenshotUrl").asText("").isBlank()) details.put("screenshotUrl", safePageUrl(page.path("screenshotUrl").asText("")));
        if (report.path("network").isObject() || report.path("readiness").isObject() || report.path("repairTrail").isArray() || hasHistoricalLocatorEvidence(page)) {
            attachExplorationMetrics(details, report, report.path("failureReason").asText(""), page);
        }
        events.appendDetailed(runId, WebCaseAgentStage.EVIDENCE, WebCaseAgentEventType.ACTION, "info", explorationSummary(round.path("round").asInt(), maxRounds, scores, actionType, actionTarget), pointId, "browser-discovery", 0, runService.ruleVersion(runId), null, null, details);
    }

    private ObjectNode explorationDetails(int round, int maxRounds, String pageUrl, String fingerprint, String actionType, String actionTarget, JsonNode observation, int pageScore, int elementScore, int stateScore, int totalScore, int candidateCount, int matchedLabelsCount, String failureReason, String status, long durationMs, String failureCategory, boolean retryable) {
        JsonNode candidate = observedCandidate(observation, actionTarget);
        String targetKind = actionTargetKind(actionTarget);
        String accessibleName = candidate == null ? "" : firstNonBlank(candidate.path("accessibleName").asText(""), candidate.path("label").asText(""));
        String safeTarget = isUrlAction(actionType) ? safePageUrl(actionTarget) : safeLocatorReference(actionTarget, accessibleName);
        String effectiveFailureCategory = normalizeFailureCategory(failureCategory);
        if (effectiveFailureCategory.isBlank() && candidate == null && isLocatorTarget(actionTarget)) effectiveFailureCategory = "ambiguous_locator";
        ObjectNode details = JsonNodeFactory.instance.objectNode()
            .put("round", round).put("maxRounds", maxRounds).put("pageUrl", safePageUrl(pageUrl))
            .put("fingerprint", shortFingerprint(fingerprint))
            .put("pageScore", pageScore).put("elementScore", elementScore).put("stateScore", stateScore).put("totalScore", totalScore)
            .put("actionType", safe(actionType)).put("actionTarget", safeTarget).put("targetKind", targetKind)
            .put("accessName", safeDiagnosticText(accessibleName)).put("accessibleName", safeDiagnosticText(accessibleName)).put("locatorFingerprint", locatorFingerprint(actionTarget)).put("candidateCount", candidateCount)
            .put("failureReason", safe(failureReason)).put("status", safe(status)).put("durationMs", durationMs);
        details.set("candidate", JsonNodeFactory.instance.objectNode().put("candidateCount", candidateCount).put("matchedLabelsCount", matchedLabelsCount).put("hasTarget", !safeTarget.isBlank()));
        details.set("action", JsonNodeFactory.instance.objectNode().put("actionType", safe(actionType)).put("safe", isSafeAction(actionType)).put("targetKind", targetKind));
        details.set("failure", JsonNodeFactory.instance.objectNode().put("failureReason", safe(failureReason)).put("retryable", retryable).put("category", safe(effectiveFailureCategory)));
        return details;
    }

    private void attachExplorationMetrics(ObjectNode details, WebAutomationLocatorDiscoveryService.ExplorationReport report, String failureCategory, JsonNode page) {
        ObjectNode metrics = JsonNodeFactory.instance.objectNode()
            .put("rounds", report.rounds() == null ? 0 : report.rounds().size())
            .put("durationMs", Math.max(0, report.durationMs()))
            .put("status", safe(report.status()))
            .put("failureCategory", safe(failureCategory));
        JsonNode network = report.network();
        metrics.put("criticalBlockedCount", network == null ? 0 : network.path("criticalBlocked").size());
        metrics.put("optionalBlockedCount", network == null ? 0 : network.path("optionalBlocked").size());
        metrics.put("unknownBlockedCount", network == null ? 0 : network.path("unknownBlocked").size());
        int repairActions = 0;
        int forbiddenActions = 0;
        int matchedElements = report.scores() == null || report.scores().relevantElements() == null ? 0 : report.scores().relevantElements().size();
        if (report.rounds() != null) {
            for (WebAutomationLocatorDiscoveryService.ExplorationRound round : report.rounds()) {
                if (round.action() != null && !round.action().isNull()) {
                    repairActions++;
                    if (!isSafeAction(round.actionType())) forbiddenActions++;
                }
            }
        }
        metrics.put("repairActionCount", repairActions)
            .put("matchedElementCount", matchedElements)
            .put("forbiddenActionCount", forbiddenActions);
        appendHistoricalMetrics(metrics, page, report);
        details.set("metrics", metrics);
    }

    private String normalizeFailureCategory(String value) {
        String category = safe(value).trim();
        if (category.isBlank()) return "";
        return switch (category) {
            case "network_blocked", "network_blocked_optional" -> "network_blocked_unknown";
            // navigation_failed / unsafe_action / locator_persistence 是 runner 的稳定分类，
            // 必须在平台侧原样保留（折叠成 wrong_page/unsupported_dom 会让诊断失真，
            // 并触发错误的「需要人工接管」建议）；只折叠不在稳定表里的历史词。
            case "page_error", "no_safe_action", "unsafe_repair_category" -> "unsupported_dom";
            case "manual_required" -> "manual_review_required";
            case "round_budget_exhausted", "same_failure_limit", "observation_stagnated" -> "duration_exhausted";
            default -> category;
        };
    }

    private void attachExplorationMetrics(ObjectNode details, JsonNode report, String failureCategory, JsonNode page) {
        JsonNode rounds = report.path("rounds");
        JsonNode scores = report.path("scores");
        JsonNode network = report.path("network");
        ObjectNode metrics = JsonNodeFactory.instance.objectNode()
            .put("rounds", rounds.isArray() ? rounds.size() : 0)
            .put("durationMs", Math.max(0, report.path("durationMs").asLong(0)))
            .put("status", report.path("status").asText(""))
            .put("failureCategory", safe(failureCategory))
            .put("criticalBlockedCount", network.path("criticalBlocked").size())
            .put("optionalBlockedCount", network.path("optionalBlocked").size())
            .put("unknownBlockedCount", network.path("unknownBlocked").size())
            .put("matchedElementCount", scores.path("relevantElements").isArray() ? scores.path("relevantElements").size() : 0);
        int repairActions = 0;
        int forbiddenActions = 0;
        if (rounds.isArray()) for (JsonNode round : rounds) {
            if (round.path("action").isObject()) {
                repairActions++;
                if (!isSafeAction(round.path("actionType").asText(round.path("action").path("type").asText("")))) forbiddenActions++;
            }
        }
        metrics.put("repairActionCount", repairActions).put("forbiddenActionCount", forbiddenActions);
        appendHistoricalMetrics(metrics, page, report);
        details.set("metrics", metrics);
    }

    private void appendHistoricalMetrics(ObjectNode metrics, JsonNode pageEvidence, JsonNode report) {
        int matched = countArray(pageEvidence, "historicalMatchedIds");
        int rejected = countArray(pageEvidence, "historicalRejectedIds");
        if (matched == 0 && rejected == 0 && hasHistoricalLocatorEvidence(report)) {
            matched = countArray(report, "historicalMatchedIds");
            rejected = countArray(report, "historicalRejectedIds");
        }
        if (matched == 0 && rejected == 0) return;
        metrics.put("historicalCandidateCount", matched + rejected);
        metrics.put("historicalMatchedCount", matched);
        metrics.put("historicalRejectedCount", rejected);
    }

    private void appendHistoricalMetrics(ObjectNode metrics, JsonNode pageEvidence, WebAutomationLocatorDiscoveryService.ExplorationReport report) {
        int matched = countArray(pageEvidence, "historicalMatchedIds");
        int rejected = countArray(pageEvidence, "historicalRejectedIds");
        if (matched == 0 && rejected == 0 && report != null) {
            matched = report.historicalMatchedIds() == null ? 0 : report.historicalMatchedIds().size();
            rejected = report.historicalRejectedIds() == null ? 0 : report.historicalRejectedIds().size();
        }
        if (matched == 0 && rejected == 0) return;
        metrics.put("historicalCandidateCount", matched + rejected);
        metrics.put("historicalMatchedCount", matched);
        metrics.put("historicalRejectedCount", rejected);
    }

    private boolean hasHistoricalLocatorEvidence(JsonNode page) {
        return page != null && (page.has("historicalMatchedIds") || page.has("historicalRejectedIds"));
    }

    private int countArray(JsonNode node, String field) {
        return node != null && node.path(field).isArray() ? node.path(field).size() : 0;
    }

    private String explorationSummary(int round, int maxRounds, JsonNode scores, String actionType, String actionTarget) {
        String action = safe(actionType);
        if (action.isBlank()) action = safe(actionTarget);
        if (action.isBlank()) action = "探索";
        action = actionLabel(action);
        return "第" + round + "/" + maxRounds + "轮 · 页面" + (scores == null ? 0 : scores.path("pageScore").asInt()) + " · 元素" + (scores == null ? 0 : scores.path("elementScore").asInt()) + " · 总分" + (scores == null ? 0 : scores.path("totalScore").asInt()) + " · " + action;
    }

    private String explorationSummary(int round, int maxRounds, WebAutomationLocatorDiscoveryService.ExplorationScores scores, String actionType, String actionTarget) {
        String action = safe(actionType);
        if (action.isBlank()) action = safe(actionTarget);
        if (action.isBlank()) action = "探索";
        action = actionLabel(action);
        return "第" + round + "/" + maxRounds + "轮 · 页面" + (scores == null ? 0 : scores.pageScore()) + " · 元素" + (scores == null ? 0 : scores.elementScore()) + " · 总分" + (scores == null ? 0 : scores.totalScore()) + " · " + action;
    }

    private String actionLabel(String action) {
        return switch (action) {
            case "menu-search" -> "菜单搜索";
            case "click" -> "点击";
            case "goto", "navigate" -> "页面导航";
            case "wait" -> "等待";
            case "scroll" -> "滚动";
            default -> action;
        };
    }

    private String safe(String value) { return value == null ? "" : value; }

    private String safeActionTarget(String value) {
        return safe(value).matches("(?i)^[a-z][a-z0-9+.-]*://.*") ? safePageUrl(value) : safeLocatorReference(value, "");
    }

    private String safeLocatorReference(String locator, String accessibleName) {
        String target = safe(locator).trim();
        if (target.isBlank()) return "";
        String kind = actionTargetKind(target);
        String name = safeDiagnosticText(accessibleName);
        if (name.isBlank()) return kind;
        return kind + "[" + name.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]") + "]";
    }

    private String safeDiagnosticText(String value) {
        String text = redactDiagnosticText(value);
        return text.length() > 64 ? text.substring(0, 64) : text;
    }

    private String safeDiagnosticMessage(String value) {
        String text = redactDiagnosticText(value);
        text = text.replaceAll("(?i)https?://[^\\s,;]+", "[url]");
        return text.length() > 160 ? text.substring(0, 160) + "..." : text;
    }

    private String safeOrigin(String value) {
        try {
            URI uri = URI.create(safe(value));
            if (uri.getScheme() == null || uri.getHost() == null) return safe(value).split("[?#]", 2)[0];
            StringBuilder normalized = new StringBuilder(uri.getScheme()).append("://").append(uri.getHost());
            if (uri.getPort() > 0) normalized.append(':').append(uri.getPort());
            return normalized.toString();
        } catch (Exception ignored) {
            return safe(value).split("[?#]", 2)[0];
        }
    }

    private String safePath(String value) {
        String path = safe(value).split("[?#]", 2)[0].trim();
        return path.length() > 128 ? path.substring(0, 128) : path;
    }

    private String locatorFingerprint(String locator) {
        String target = safe(locator).trim();
        if (target.isBlank()) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(target.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int index = 0; index < 8; index++) hex.append(String.format("%02x", digest[index]));
            return hex.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private JsonNode observedCandidate(JsonNode observation, String actionTarget) {
        if (observation == null || !observation.isObject() || safe(actionTarget).isBlank()) return null;
        JsonNode elements = observation.path("elements");
        if (!elements.isArray()) elements = observation.path("allElements");
        if (!elements.isArray()) elements = observation.path("tabs");
        if (!elements.isArray()) return null;
        String target = safe(actionTarget).trim();
        JsonNode match = null;
        int matches = 0;
        for (JsonNode element : elements) {
            String locator = element.path("locator").asText("").trim();
            String comparableTarget = target.split("\\?", 2)[0];
            String comparableLocator = locator.split("\\?", 2)[0];
            if (!target.equals(locator) && !comparableTarget.equals(comparableLocator)) continue;
            match = element;
            matches++;
        }
        if (matches == 0) {
            JsonNode tabs = observation.path("tabs");
            if (tabs.isArray()) {
                for (JsonNode tab : tabs) {
                    String locator = tab.path("locator").asText("").trim();
                    String comparableTarget = target.split("\\?", 2)[0];
                    String comparableLocator = locator.split("\\?", 2)[0];
                    if (!target.equals(locator) && !comparableTarget.equals(comparableLocator)) continue;
                    match = tab;
                    matches++;
                }
            }
        }
        return matches == 1 ? match : null;
    }

    private boolean isUrlAction(String actionType) {
        return switch (safe(actionType).toLowerCase(java.util.Locale.ROOT)) {
            case "goto", "navigate", "direct-navigation" -> true;
            default -> false;
        };
    }

    private boolean isLocatorTarget(String value) {
        String target = safe(value).trim();
        return target.matches("(?i)^(role|css|xpath|text|testid|testId)=.*");
    }

    private int matchedLabelsCount(JsonNode elements) {
        if (elements == null || !elements.isArray()) return 0;
        int count = 0;
        for (JsonNode element : elements) {
            if (!firstNonBlank(element.path("label").asText(""), element.path("accessibleName").asText(""), element.path("name").asText(""), element.path("text").asText("")).isBlank()) count++;
        }
        return count;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private String actionTargetKind(String value) {
        String target = safe(value).trim();
        if (target.isBlank()) return "";
        if (target.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) return "url";
        int equals = target.indexOf('=');
        return (equals > 0 ? target.substring(0, equals) : target).toLowerCase(java.util.Locale.ROOT);
    }

    private boolean isSafeAction(String value) {
        return switch (safe(value).toLowerCase(java.util.Locale.ROOT)) {
            case "click", "menu-search", "menu_search", "goto", "navigate", "direct-navigation", "same_origin_navigation", "wait", "wait-ready", "wait_ready", "scroll", "scroll-page", "scroll-container", "scroll_container", "dismiss-overlay", "dismiss_safe_overlay", "switch-tab", "switch_readonly_tab", "paginate", "reobserve", "reauthenticate" -> true;
            default -> false;
        };
    }

    private String shortFingerprint(String value) {
        String fingerprint = safe(value).replaceAll("[^A-Za-z0-9_-]", "");
        return fingerprint.length() > 64 ? fingerprint.substring(0, 64) : fingerprint;
    }

    private String safePageUrl(String value) {
        try {
            URI uri = URI.create(safe(value));
            if (uri.getScheme() == null || uri.getHost() == null) return safe(value).split("[?#]", 2)[0];
            StringBuilder normalized = new StringBuilder(uri.getScheme()).append("://").append(uri.getHost());
            if (uri.getPort() > 0) normalized.append(':').append(uri.getPort());
            String path = uri.getPath();
            normalized.append(path == null || path.isBlank() ? "/" : path);
            String fragment = safeFragment(uri.getFragment());
            if (!fragment.isBlank()) normalized.append('#').append(fragment);
            return normalized.toString();
        } catch (Exception ignored) {
            return safe(value).split("[?#]", 2)[0];
        }
    }

    private String safeFragment(String value) {
        String fragment = safe(value).replaceFirst("[?#].*$", "");
        java.util.regex.Matcher sensitive = java.util.regex.Pattern.compile("(?i)(auth[_-]?token|access[_-]?token|api[_-]?key|secret[_-]?key|password|cookie)").matcher(fragment);
        if (sensitive.find()) fragment = fragment.substring(0, sensitive.start()).replaceAll("[/_-]+$", "");
        return fragment.replaceAll("[^A-Za-z0-9/_-]", "");
    }

    private AgentWebCaseGenerationFacade.BrowserDiscoveryOptions discoveryOptions(WebCaseAgentModels.WebCaseAgentStartRequest request) {
        WebCaseAgentModels.BrowserDiscoveryRequest discovery = request.browserDiscovery();
        WebCaseAgentModels.BrowserLoginRequest login = discovery == null ? null : discovery.login();
        return new AgentWebCaseGenerationFacade.BrowserDiscoveryOptions(
            request.baseUrl(), request.environmentId(), request.browser(), request.viewport(), discovery == null || !Boolean.FALSE.equals(discovery.headless()),
            discovery == null ? null : discovery.timeoutMs(), discovery == null ? null : discovery.waitUntil(),
            discovery == null ? null : discovery.authToken(), discovery == null ? null : discovery.extraHeaders(),
            discovery == null ? null : discovery.cookieOrigins(), discovery == null ? null : discovery.allowedRequestOrigins(), discovery == null ? null : discovery.sessionId(), discovery == null ? null : discovery.reuseSession(), login == null ? null : new AgentWebCaseGenerationFacade.BrowserLogin(
                login.enabled(), login.url(), login.usernameLocator(), login.passwordLocator(), login.submitLocator(),
                login.username(), login.password(), login.successType(), login.successTarget()
            ), discovery == null ? null : discovery.diagnosticMode(), discovery == null ? null : discovery.tracingEnabled(), request.authenticationMode(),
            discovery == null ? null : discovery.networkPolicyMode()
        );
    }

    private WebCaseAgentModels.WebCaseAgentStartRequest withBrowserSession(WebCaseAgentModels.WebCaseAgentStartRequest request, String sessionId) {
        var current = request.browserDiscovery();
        var updatedDiscovery = new WebCaseAgentModels.BrowserDiscoveryRequest(
            current.headless(), current.timeoutMs(), current.waitUntil(), current.authToken(), current.extraHeaders(),
            current.cookieOrigins(), current.allowedRequestOrigins(), sessionId, true, current.login(), current.diagnosticMode(), current.tracingEnabled(), "",
            current.networkPolicyMode()
        );
        return new WebCaseAgentModels.WebCaseAgentStartRequest(
            request.projectId(), request.analysisRunId(), request.pointIds(), request.environmentId(), request.baseUrl(),
            request.browser(), request.viewport(), request.loginDiscovery(), updatedDiscovery, request.batchTotalDurationMs(), request.mode(), request.planRunId()
            , request.authenticationMode()
        );
    }

    private void captureLocatorKnowledge(String runId, WebCaseAgentModels.WebCaseAgentStartRequest request, AgentWebCaseGenerationFacade.BrowserEvidenceBundle evidenceBundle) {
        if (evidenceBundle == null || !evidenceBundle.usedBrowserDiscovery() || evidenceBundle.rawEvidence() == null || !evidenceBundle.rawEvidence().isArray()) return;
        for (tools.jackson.databind.JsonNode page : evidenceBundle.rawEvidence()) {
            if (!"browserDiscovery".equals(page.path("source").asText(""))) continue;
            java.util.List<WebAutomationLocatorDiscoveryService.PageLocatorEvidence> elements = new java.util.ArrayList<>();
            for (tools.jackson.databind.JsonNode element : page.path("elements")) {
                String locator = element.path("locator").asText("").strip();
                if (locator.isBlank()) continue;
                elements.add(new WebAutomationLocatorDiscoveryService.PageLocatorEvidence(
                    element.path("id").asText(""),
                    locator,
                    firstText(element, "label", "text", "name", "accessibleName", "innerText"),
                    element.path("role").asText(""),
                    element.path("tagName").asText(""),
                    element.path("type").asText(""),
                    element.path("strategy").asText("")
                ));
            }
            if (elements.isEmpty()) continue;
            String pointId = page.path("sourcePointId").asText(null);
            locatorKnowledge.recordAgentEvidence(
                runService.projectId(runId), request.environmentId(), page.path("moduleName").asText(""), page.path("pageName").asText(""), page.path("pageUrl").asText(""), elements,
                explorationKnowledge(page, evidenceBundle)
            );
            try {
                events.append(runId, WebCaseAgentStage.EVIDENCE, WebCaseAgentEventType.TOOL_RESULT, "info", "已沉淀 " + elements.size() + " 个页面元素", pointId, "locator-knowledge", 0, runService.ruleVersion(runId), null, null);
            } catch (Exception eventFailure) {
                log.warn("无法记录素材沉淀结果事件 runId={} pointId={}", runId, pointId, eventFailure);
            }
        }
    }

    private void handleLocatorKnowledgePersistenceFailure(String runId, String pointId, DataAccessException failure) {
        runService.markPointBlocked(runId, pointId);
        try {
            events.append(runId, WebCaseAgentStage.EVIDENCE, WebCaseAgentEventType.ERROR, "error", "素材库持久化失败，已阻塞当前测试点", pointId, "locator-knowledge", 0, runService.ruleVersion(runId), null, "locator_knowledge_persistence_failed");
        } catch (Exception eventFailure) {
            // Event persistence must not recurse into the same failure path.
            log.warn("无法记录素材库持久化失败事件 runId={} pointId={} rootCause={}", runId, pointId, safeFailureSummary(eventFailure));
        }
    }

    private boolean safeRecordLocatorKnowledge(String runId, String pointId, Runnable persistenceAction) {
        try {
            persistenceAction.run();
            return true;
        } catch (DataAccessException persistenceFailure) {
            handleLocatorKnowledgePersistenceFailure(runId, pointId, persistenceFailure);
            return false;
        }
    }

    static String safeFailureSummary(Throwable failure) {
        String type = failure == null ? "Unknown" : failure.getClass().getSimpleName();
        String message = redactDiagnosticText(failure == null || failure.getMessage() == null ? "" : failure.getMessage());
        if (message.length() > 160) message = message.substring(0, 157) + "...";
        return type + (message.isBlank() ? "" : ": " + message);
    }

    static String safeAgentFailureMessage(Throwable failure, String fallback) {
        String summary = safeFailureSummary(failure);
        if (summary.isBlank() || summary.equals(failure == null ? "Unknown" : failure.getClass().getSimpleName())) {
            return fallback;
        }
        summary = redactDiagnosticText(summary);
        summary = summary.replaceAll("(?i)https?://[^\\s,;]+", "[url]");
        return summary.length() > 240 ? summary.substring(0, 237) + "..." : summary;
    }

    /**
     * 失败信息里点名被网络策略拦截的来源，并给出可执行的放行建议。
     * 用户实测教训：取证只显示「验证码拦截」时，真因（登录接口/验证码脚本被白名单 abort）不可见，
     * 只能靠翻 details_json 排查。details 内的 origin/path 均为 runner 已脱敏形态。
     */
    private static String networkBlockedHint(JsonNode details) {
        if (details == null) return "";
        JsonNode network = details.path("network");
        java.util.LinkedHashSet<String> blocked = new java.util.LinkedHashSet<>();
        for (String bucket : List.of("criticalBlocked", "unknownBlocked", "optionalBlocked")) {
            JsonNode entries = network.path(bucket);
            if (!entries.isArray()) continue;
            for (JsonNode entry : entries) {
                String origin = entry.path("origin").asText("").strip();
                String path = entry.path("path").asText("").strip();
                String label = origin.isBlank() ? path
                    : (path.isBlank() || "/".equals(path) ? origin : origin + path);
                if (!label.isBlank()) blocked.add(label);
                if (blocked.size() >= 3) break;
            }
            if (blocked.size() >= 3) break;
        }
        if (blocked.isEmpty()) return "";
        String suffix = blocked.size() >= 3 ? " 等" : "";
        return "被网络白名单拦截：" + String.join("、", blocked) + suffix
            + "（如属登录或接口域名，请将对应 Origin 或带路径的完整 URL 加入环境「认证与放行白名单」后重试）";
    }

    private static String safeRetryFailureMessage(String message) {
        if (message == null || message.isBlank()) return "浏览器证据采集失败";
        String safe = redactDiagnosticText(message);
        safe = safe.replaceAll("(?i)https?://[^\\s,;]+", "[url]");
        return safe.length() > 240 ? safe.substring(0, 237) + "..." : safe;
    }

    private void handleEvidenceReadFailure(String runId, String pointId, DataAccessException failure) {
        runService.markPointBlocked(runId, pointId);
        try {
            String detail = safeFailureSummary(failure);
            events.append(runId, WebCaseAgentStage.EVIDENCE, WebCaseAgentEventType.ERROR, "error", "浏览器证据读取失败，已阻塞当前测试点" + (detail.isBlank() ? "" : "：" + detail), pointId, "locator-knowledge", 0, runService.ruleVersion(runId), null, "browser_evidence_read_failed");
        } catch (Exception eventFailure) {
            log.warn("无法记录浏览器证据读取失败事件 runId={} pointId={} rootCause={}", runId, pointId, safeFailureSummary(eventFailure));
        }
    }

    private static String redactDiagnosticText(String value) {
        String text = value == null ? "" : value.replaceAll("[\\p{Cntrl}]", "").replaceAll("\\s+", " ").trim();
        text = text.replaceAll("(?i)\\b(query|search)\\s*[:=]\\s*(?:\"[^\"]*\"|'[^']*'|\\{[^}]*\\}|\\[[^\\]]*\\]|[^\\s,;]+)", "$1=[redacted]");
        text = text.replaceAll("(?i)(access[_-]?token|auth[_-]?token|api[_-]?key|secret[_-]?key|password|cookie|authorization|token|selector|locator|role|text|css|xpath|testid|button)\\s*[:=]\\s*(?:\"[^\"]*\"|'[^']*'|\\{[^}]*\\}|\\[[^\\]]*\\]|[^\\s,;]+)", "$1=[redacted]");
        text = text.replaceAll("(?is)\\b(select|insert|update|delete)\\b.*", "[query redacted]");
        return text;
    }

    private LocatorKnowledgeService.ExplorationKnowledge explorationKnowledge(tools.jackson.databind.JsonNode page, AgentWebCaseGenerationFacade.BrowserEvidenceBundle evidenceBundle) {
        tools.jackson.databind.JsonNode report = page.path("exploration");
        if (report == null || report.isNull() || report.isMissingNode()) return null;
        java.util.List<String> trail = new java.util.ArrayList<>();
        for (tools.jackson.databind.JsonNode item : report.path("navigationTrail")) {
            String value = item.asText("").strip();
            if (!value.isBlank()) trail.add(value);
        }
        tools.jackson.databind.JsonNode scores = report.path("scores");
        int totalScore = scores.path("totalScore").asInt(evidenceBundle.totalScore());
        int rounds = report.path("rounds").isArray() ? report.path("rounds").size() : evidenceBundle.explorationRounds();
        String status = report.path("status").asText(evidenceBundle.explorationStatus());
        return new LocatorKnowledgeService.ExplorationKnowledge(trail, totalScore, rounds, status);
    }

    private String firstText(tools.jackson.databind.JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("").strip();
            if (!value.isBlank()) return value;
        }
        return "";
    }

    @PreDestroy
    void shutdown() { executor.shutdownNow(); active.clear(); runtimeRequests.clear(); }
}
