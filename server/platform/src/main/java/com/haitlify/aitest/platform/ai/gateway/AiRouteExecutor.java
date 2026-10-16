package com.haitlify.aitest.platform.ai.gateway;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.haitlify.aitest.platform.ai.event.AiRunEvent;
import com.haitlify.aitest.platform.ai.event.AiRunEventService;
import com.haitlify.aitest.platform.ai.runtime.AiCancellationRegistry;
import com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade;
import com.haitlify.aitest.platform.ai.runtime.AiExecutionLedger;
import com.haitlify.aitest.platform.ai.runtime.AiRetryPolicy;

/**
 * Bounded-fallback engine for a single chunk (deliverable 2). Walks a strategy's ordered routes
 * (primary first, then fallbacks) within the retry budget, classifying each attempt and stopping
 * immediately on a non-retryable failure. Composes — never replaces — {@link AiGateway}, so every
 * other feature depending on the gateway is unaffected.
 *
 * <p>The executor owns attempt-level events ({@code attempt_started}, {@code attempt_failed},
 * {@code fallback_switched}); the worker owns run- and chunk-level events.
 *
 * <p>T05：执行受 {@link AiCancellationRegistry} 与 {@link AiExecutionLedger} 控制——每次调用前、
 * 返回后、退避后都检查取消；每次调用的有效超时收窄为「最小剩余预算」（min(per-call, 距整体截止剩余)），
 * 剩余 &lt;= 0 时不再发起任何调用。INTERRUPTED/CANCELLED 是与网络错误分离的终态原因。
 */
@Component
public class AiRouteExecutor {

    private static final long RETRY_BACKOFF_BASE_MS = 1000L;

    private final AiTaskExecutionFacade taskFacade;
    private final AiRunEventService eventService;
    private final AiCancellationRegistry cancellationRegistry;
    private final AiExecutionLedger executionLedger;
    private final Clock clock;
    private final AiRetryPolicy retryPolicy;

    @org.springframework.beans.factory.annotation.Autowired
    public AiRouteExecutor(com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade taskFacade,
                           AiRunEventService eventService,
                           AiCancellationRegistry cancellationRegistry, AiExecutionLedger executionLedger,
                           Clock clock, AiRetryPolicy retryPolicy,
                           @org.springframework.beans.factory.annotation.Value(
                               "${app.ai.runtime.disabled-task-types:}") String disabledTaskTypes,
                           @org.springframework.beans.factory.annotation.Value(
                               "${app.ai.runtime.disabled-projects:}") String disabledProjects) {
        this.taskFacade = taskFacade;
        this.eventService = eventService;
        this.cancellationRegistry = cancellationRegistry;
        this.executionLedger = executionLedger;
        this.clock = clock;
        this.retryPolicy = retryPolicy;
        this.runtimeSwitches = com.haitlify.aitest.platform.ai.runtime.AiRuntimeSwitches
            .parse(disabledTaskTypes, disabledProjects);
    }

    /**
     * 兼容构造器（既有单测）：把裸网关包进最小 facade（仅台账上限；无预算服务/attempt 仓储）。
     * 生产路径经 @Autowired 注入完整 Runtime bean。
     */
    public AiRouteExecutor(AiGateway gateway, AiRunEventService eventService,
                           AiCancellationRegistry cancellationRegistry, AiExecutionLedger executionLedger,
                           Clock clock, AiRetryPolicy retryPolicy) {
        this(com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade.minimal(gateway, executionLedger, clock),
            eventService, cancellationRegistry, executionLedger, clock, retryPolicy, "", "");
    }

    private final com.haitlify.aitest.platform.ai.runtime.AiRuntimeSwitches.Switches runtimeSwitches;

    /** T05 测试友好重载：策略默认（真实休眠、20% 抖动）。 */
    public AiRouteExecutor(AiGateway gateway, AiRunEventService eventService,
                           AiCancellationRegistry cancellationRegistry, AiExecutionLedger executionLedger,
                           Clock clock) {
        this(gateway, eventService, cancellationRegistry, executionLedger, clock,
            new AiRetryPolicy(Thread::sleep, clock, 1_000L, 30_000L, 0.2, () -> (int) (Math.random() * 2_001) - 1_000));
    }

    /** 兼容构造器（既有单测）：无取消/台账约束、系统时钟——行为与 T05 之前一致。 */
    public AiRouteExecutor(AiGateway gateway, AiRunEventService eventService) {
        this(gateway, eventService, new AiCancellationRegistry(), new AiExecutionLedger(), Clock.systemUTC());
    }

    public AiRouteExecutionResult execute(AiRoutingStrategy strategy, Object prompt, AiRouteExecutionContext ctx) {
        // T15-R5：发布开关覆盖 legacy analysis 管道——在发出任何模型请求前阻断。
        // 取值统一：ctx.taskType 优先（更贴近实际请求），strategy 兜底；两处语义必须一致。
        String effectiveTaskType = ctx.taskType() == null || ctx.taskType().isBlank()
            ? strategy.taskType() : ctx.taskType();
        String gate = runtimeSwitches.reasonFor(effectiveTaskType, ctx.projectId());
        if (gate != null) {
            emit(ctx, "attempt_failed", "error", "该任务/项目尚未启用统一 Runtime: " + gate, 0, null, 0,
                AiRetryReason.RUNTIME_DISABLED.name());
            return new AiRouteExecutionResult(false, null, null, List.of(), AiRetryReason.RUNTIME_DISABLED);
        }
        List<AiRoute> routes = strategy.routes();
        if (routes == null || routes.isEmpty()) {
            emit(ctx, "attempt_failed", "error", "未配置可用路由，无法执行分析", 0, null, 0, AiRetryReason.NO_ROUTE.name());
            return new AiRouteExecutionResult(false, null, null, List.of(), AiRetryReason.NO_ROUTE);
        }
        int maxAttempts = routes.size() == 1
            ? Math.max(1, strategy.retryBudget() + 1)
            : Math.min(routes.size(), Math.max(1, strategy.retryBudget() + 1));
        List<AiAttemptRecord> attempts = new ArrayList<>();
        AiGenerationResult lastResult = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (cancellationRegistry.isCancelled(ctx.runId())) {
                emit(ctx, "attempt_cancelled", "warn", "运行已取消，未再发起模型调用",
                    attempt, null, 0, AiRetryReason.CANCELLED.name());
                return new AiRouteExecutionResult(false, lastResult, null, attempts, AiRetryReason.CANCELLED);
            }
            AiRoute route = routes.size() == 1 ? routes.getFirst() : routes.get(attempt);
            long effectiveTimeoutMs = executionLedger.remainingBudgetMs(ctx.runId(), route.requestTimeoutMs(), clock);
            if (effectiveTimeoutMs <= 0) {
                emit(ctx, "attempt_failed", "error", "整体运行时限已到，剩余预算不足以发起调用",
                    attempt, null, 0, AiRetryReason.RUN_DEADLINE_EXCEEDED.name());
                return new AiRouteExecutionResult(false, lastResult, null, attempts, AiRetryReason.RUN_DEADLINE_EXCEEDED);
            }
            AiRoute effectiveRoute = effectiveTimeoutMs == route.requestTimeoutMs()
                ? route
                : new AiRoute(route.connectionId(), route.providerName(), route.providerType(), route.baseUrl(),
                    route.model(), route.apiKey(), (int) effectiveTimeoutMs);
            executionLedger.tryStartAttempt(ctx.runId());
            emit(ctx, "attempt_started", "info",
                "调用模型 " + modelLabel(effectiveRoute) + "（提供方 " + providerLabel(effectiveRoute) + "）",
                attempt, effectiveRoute, 0, "");
            long started = System.currentTimeMillis();
            // T07 收敛：每一次 provider 请求都经统一 Runtime（台账/attempt/预算/失败分类与 facade.generate 同权）。
            AiGenerationResult result = taskFacade.generateForRoute(
                new com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade.RouteTaskExecution(
                    effectiveTaskType, ctx.projectId(), "", ctx.runId(), prompt, null),
                effectiveRoute.toGatewayConfig());
            long durationMs = System.currentTimeMillis() - started;
            lastResult = result;
            if (result.success()) {
                attempts.add(new AiAttemptRecord(attempt, effectiveRoute, result, durationMs, null));
                return new AiRouteExecutionResult(true, result, effectiveRoute, attempts, null);
            }
            if (cancellationRegistry.isCancelled(ctx.runId())) {
                // 返回后检查：调用期间被取消 ⇒ 不重试、不切换，按取消退出（结果交回调用方处理）。
                attempts.add(new AiAttemptRecord(attempt, effectiveRoute, result, durationMs, AiRetryReason.CANCELLED));
                emit(ctx, "attempt_cancelled", "warn", "调用返回后发现运行已取消，停止后续重试与切换",
                    attempt, effectiveRoute, durationMs, AiRetryReason.CANCELLED.name());
                return new AiRouteExecutionResult(false, result, null, attempts, AiRetryReason.CANCELLED);
            }
            AiRetryReason reason = AiRetryReason.fromCode(result.retryReason());
            attempts.add(new AiAttemptRecord(attempt, effectiveRoute, result, durationMs, reason));
            String severity = reason.retryable() ? "warn" : "error";
            boolean canTryNextRoute = attempt < maxAttempts - 1;
            boolean hasFallbackRoute = routes.size() > 1 && attempt + 1 < routes.size();
            emit(ctx, "attempt_failed", severity,
                describeFailure(effectiveRoute, reason, canTryNextRoute, hasFallbackRoute),
                attempt, effectiveRoute, durationMs, reason.name());
            if (!reason.retryable()) {
                return new AiRouteExecutionResult(false, result, null, attempts, reason);
            }
            if (canTryNextRoute) {
                AiRoute next = routes.size() == 1 ? effectiveRoute : routes.get(attempt + 1);
                long backoffMs = retryPolicy.waitMs(attempt, result.retryAfterMs());
                String summary = hasFallbackRoute
                    ? "将在 " + backoffMs + "ms 后切换到备用模型 " + modelLabel(next) + "（提供方 " + providerLabel(next) + "）"
                    : "将在 " + backoffMs + "ms 后重试当前模型 " + modelLabel(effectiveRoute) + "（第 " + (attempt + 2) + "/" + maxAttempts + " 次）";
                emit(ctx, hasFallbackRoute ? "fallback_switched" : "retry_scheduled", "warn",
                    summary, attempt + 1, next, 0, reason.name());
                if (!retryPolicy.waitBeforeRetry(attempt, result.retryAfterMs())) {
                    emit(ctx, "attempt_failed", "error", "等待重试期间线程被中断，停止调用",
                        attempt, effectiveRoute, 0, AiRetryReason.INTERRUPTED.name());
                    return new AiRouteExecutionResult(false, result, null, attempts, AiRetryReason.INTERRUPTED);
                }
            } else if (routes.size() == 1) {
                return new AiRouteExecutionResult(false, result, null, attempts, reason);
            }
        }
        return new AiRouteExecutionResult(false, lastResult, null, attempts, AiRetryReason.BUDGET_EXHAUSTED);
    }

    private void emit(AiRouteExecutionContext ctx, String stage, String severity, String summary,
                      int attemptIndex, AiRoute route, long durationMs, String retryReason) {
        eventService.emit(new AiRunEvent(
            ctx.runId(),
            Instant.now(),
            stage,
            severity,
            summary,
            ctx.chunkIndex(),
            ctx.chunkTotal(),
            attemptIndex,
            route == null ? "" : route.connectionId(),
            route == null ? "" : route.providerType(),
            route == null ? "" : route.model(),
            durationMs,
            retryReason,
            ctx.strategySnapshotId(),
            0L
        ));
    }

    private String describeFailure(AiRoute route, AiRetryReason reason, boolean canTryNextRoute, boolean hasFallbackRoute) {
        String base = switch (reason) {
            case TIMEOUT -> "请求超时";
            case RATE_LIMITED -> "被限流";
            case UPSTREAM_ERROR -> "上游服务错误";
            case TRANSIENT_NETWORK -> "网络异常";
            case AUTH_DENIED -> "鉴权失败";
            case MODEL_OR_ENDPOINT_NOT_FOUND -> "模型或地址不存在";
            case INVALID_REQUEST -> "请求无效";
            case OUTPUT_VALIDATION -> "结构化输出校验失败";
            case CANCELLED -> "运行已取消";
            case INTERRUPTED -> "调用被中断";
            case RUN_DEADLINE_EXCEEDED -> "整体运行时限已到";
            case BUDGET_EXHAUSTED -> "重试预算耗尽";
            case NO_ROUTE -> "无可用路由";
            default -> "调用失败";
        
            case RUNTIME_DISABLED -> "灰度开关：该任务/项目尚未启用统一 Runtime";};
        String tail = !reason.retryable()
            ? "，不可重试"
            : canTryNextRoute
                ? hasFallbackRoute ? "，准备切换备用路由" : "，准备重试当前模型"
                : "，已达到当前策略重试上限";
        return modelLabel(route) + " 调用失败（" + base + "）" + tail;
    }

    private String modelLabel(AiRoute route) {
        return route == null || route.model() == null || route.model().isBlank() ? "未指定模型" : route.model();
    }

    private String providerLabel(AiRoute route) {
        if (route == null) {
            return "默认提供方";
        }
        if (route.providerName() != null && !route.providerName().isBlank()) {
            return route.providerName();
        }
        return route.providerType() == null || route.providerType().isBlank() ? "默认提供方" : route.providerType();
    }
}
