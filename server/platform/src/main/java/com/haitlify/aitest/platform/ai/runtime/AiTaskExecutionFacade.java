package com.haitlify.aitest.platform.ai.runtime;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.haitlify.aitest.platform.ai.gateway.AiGateway;
import com.haitlify.aitest.platform.ai.gateway.AiGatewayConfig;
import com.haitlify.aitest.platform.ai.gateway.AiGatewaySettingsService;
import com.haitlify.aitest.platform.ai.gateway.AiGenerationResult;
import com.haitlify.aitest.platform.ai.gateway.ResolvedAiGatewaySettings;

/**
 * T07：统一模型调用的同步 facade——业务层唯一允许的模型调用入口（架构白名单成员）。
 *
 * <p>职责边界：业务层不得再直接读取默认 key（统一经 {@link AiGatewaySettingsService}）、
 * 不得手写重试/退避（重试策略只存在于 gateway 内部）。facade 为每次调用提供：
 * T05 台账预算（min(per-call, 距整体截止剩余)；剩余 ≤0 不发请求）、T06 两阶段 attempt
 * 审计（请求前落库、返回后终态，业务回滚不抹除）、失败分类（{@link AiFailureClassifier}）。
 * 实际生成仍委托 {@link AiGateway}（schema/repair 能力不变）。
 */
@Service
public class AiTaskExecutionFacade {

    /**
     * 任务执行描述。{@code runId} 用于与既有 run 的台账/事件/审计对齐（无则按调用键控）；
     * {@code timeoutMsOverride} 供消费者保留既有的子预算上限（如语义扩展的较短子预算）。
     */
    public record TaskExecution(String taskType, Long projectId, String actorId, String runId,
                                tools.jackson.databind.JsonNode prompt, Long timeoutMsOverride) {}

    private final AiGatewaySettingsService settingsService;
    private final AiGateway gateway;
    private final AiExecutionLedger ledger;
    private final AiAttemptRepository attempts;
    private final com.haitlify.aitest.platform.ai.quota.AiBudgetService budgetService;
    private final long budgetDailyTokens;
    private final int budgetDailyCalls;
    private Clock clock = Clock.systemUTC();

    public AiTaskExecutionFacade(AiGatewaySettingsService settingsService, AiGateway gateway,
                                 AiExecutionLedger ledger, AiAttemptRepository attempts) {
        this(settingsService, gateway, ledger, attempts, null, 0, 0, "", "");
    }

    public AiTaskExecutionFacade(AiGatewaySettingsService settingsService, AiGateway gateway,
                                 AiExecutionLedger ledger, AiAttemptRepository attempts,
                                 com.haitlify.aitest.platform.ai.quota.AiBudgetService budgetService,
                                 long budgetDailyTokens, int budgetDailyCalls) {
        this(settingsService, gateway, ledger, attempts, budgetService, budgetDailyTokens, budgetDailyCalls,
            "", "");
    }

    private final AiRuntimeSwitches.Switches runtimeSwitches;

    /** 无时钟兼容构造（既有单测）：标准 UTC 时钟。 */
    public AiTaskExecutionFacade(AiGatewaySettingsService settingsService, AiGateway gateway,
                                 AiExecutionLedger ledger, AiAttemptRepository attempts,
                                 com.haitlify.aitest.platform.ai.quota.AiBudgetService budgetService,
                                 long budgetDailyTokens, int budgetDailyCalls,
                                 String disabledTaskTypes, String disabledProjects) {
        this(settingsService, gateway, ledger, attempts, budgetService, budgetDailyTokens, budgetDailyCalls,
            disabledTaskTypes, disabledProjects, Clock.systemUTC());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AiTaskExecutionFacade(AiGatewaySettingsService settingsService, AiGateway gateway,
                                 AiExecutionLedger ledger, AiAttemptRepository attempts,
                                 com.haitlify.aitest.platform.ai.quota.AiBudgetService budgetService,
                                 @org.springframework.beans.factory.annotation.Value(
                                     "${app.ai.budget.daily-token-limit:20000000}") long budgetDailyTokens,
                                 @org.springframework.beans.factory.annotation.Value(
                                     "${app.ai.budget.daily-call-limit:20000}") int budgetDailyCalls,
                                 @org.springframework.beans.factory.annotation.Value(
                                     "${app.ai.runtime.disabled-task-types:}") String disabledTaskTypes,
                                 @org.springframework.beans.factory.annotation.Value(
                                     "${app.ai.runtime.disabled-projects:}") String disabledProjects,
                                 Clock clock) {
        this.settingsService = settingsService;
        this.gateway = gateway;
        this.ledger = ledger;
        this.attempts = attempts;
        this.budgetService = budgetService;
        this.budgetDailyTokens = budgetDailyTokens;
        this.budgetDailyCalls = budgetDailyCalls;
        this.runtimeSwitches = AiRuntimeSwitches.parse(disabledTaskTypes, disabledProjects);
        // 与 worker/ledger 使用同一时钟 bean（测试里被替换成固定时钟时，台账剩余必须同步）。
        this.clock = clock;
    }

    /**
     * T15 发布开关：按任务类型/项目灰度启用新 Runtime。被禁用的范围一律阻断（带明确原因），
     * 绝不做「旁路直连」或「双份真实付费请求」；空配置 = 全部启用。
     */
    private String runtimeGate(String taskType, Long projectId) {
        return runtimeSwitches.reasonFor(taskType, projectId);
    }

    /**
     * T13：reserve 各 scope（global→project→task，任一层不足即 fail-closed 阻断，
     * 已成功的预留原样释放）；返回 null 表示未启用预算（仅测试/无预算部署）。
     */
    private List<com.haitlify.aitest.platform.ai.quota.AiBudgetService.Reservation> reserveBudget(Long projectId,
                                                                                                  String taskType,
                                                                                                  long tokensUpperBound,
                                                                                                  String attemptRef) {
        if (budgetService == null || budgetDailyTokens <= 0) {
            return null;
        }
        LocalDate day = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        var limit = new com.haitlify.aitest.platform.ai.quota.AiBudgetService.BudgetLimit(
            budgetDailyTokens, budgetDailyCalls);
        List<String> scopes = new ArrayList<>();
        scopes.add(com.haitlify.aitest.platform.ai.quota.AiBudgetService.globalScope());
        if (projectId != null) {
            scopes.add(com.haitlify.aitest.platform.ai.quota.AiBudgetService.projectScope(projectId));
        }
        if (taskType != null && !taskType.isBlank()) {
            scopes.add(com.haitlify.aitest.platform.ai.quota.AiBudgetService.taskScope(taskType));
        }
        List<com.haitlify.aitest.platform.ai.quota.AiBudgetService.Reservation> granted = new ArrayList<>();
        for (String scope : scopes) {
            try {
                granted.add(budgetService.reserve(scope, day, limit, tokensUpperBound, 1, attemptRef));
            } catch (com.haitlify.aitest.platform.ai.quota.AiBudgetService.BudgetExceededException exceeded) {
                for (var reservation : granted) {
                    budgetService.release(reservation.reservationId());
                }
                throw exceeded;
            }
        }
        return granted;
    }

    /** 结算：usage 未知或价格未知一律按预留额（未知绝不等于免费）。 */
    private void settleBudget(List<com.haitlify.aitest.platform.ai.quota.AiBudgetService.Reservation> reservations,
                              long actualTokens, boolean usageKnown) {
        if (reservations == null) {
            return;
        }
        for (var reservation : reservations) {
            budgetService.settle(reservation.reservationId(), actualTokens, 1, usageKnown,
                com.haitlify.aitest.platform.ai.quota.AiBudgetService.PricingMode.UNKNOWN_PRICE_CONSERVATIVE);
        }
    }

    /** 释放（调用未发生时）。 */
    private void releaseBudget(List<com.haitlify.aitest.platform.ai.quota.AiBudgetService.Reservation> reservations) {
        if (reservations == null) {
            return;
        }
        for (var reservation : reservations) {
            budgetService.release(reservation.reservationId());
        }
    }

    void useClock(Clock testClock) {
        this.clock = testClock;
    }

    public AiGenerationResult generate(TaskExecution task) {
        if (task == null || task.taskType() == null || task.taskType().isBlank()
                || task.prompt() == null) {
            throw new IllegalArgumentException("task execution fields required");
        }
        ResolvedAiGatewaySettings settings = settingsService.loadDefaultTextSettings();
        long timeoutMs = task.timeoutMsOverride() != null ? task.timeoutMsOverride() : settings.timeoutMs();
        AiGatewayConfig baseConfig = new AiGatewayConfig(settings.providerType(), settings.baseUrl(),
            settings.model(), settings.apiKey(), 0);
        return invokeWithConfig(task.taskType(), task.projectId(), task.runId(), task.prompt(),
            baseConfig, timeoutMs, true);
    }

    /**
     * 路由任务描述：prompt 保持原始对象类型（legacy 路由执行器历史上允许 String/JsonNode，
     * 透传语义必须逐字保持）。
     */
    public record RouteTaskExecution(String taskType, Long projectId, String actorId, String runId,
                                     Object prompt, Long timeoutMsOverride) {}

    /**
     * T07/T13 收敛：显式路由配置的调用入口——legacy 路由执行器（{@link AiRouteExecutor}）与结构化
     * 输出修复（{@link AiStructuredOutputRepairService}）经本方法发起每一次 provider 请求，
     * 从而与 {@link #generate} 获得完全相同的台账时间上限（T05）、两阶段 attempt 落库（T06）、
     * token 预算 reserve/settle（T13）、失败分类与 usage 记账；路由选择/重试/取消编排仍由调用方
     * 负责（facade 不重复实现编排）。不带 runId 的调用按无台账执行（仅受 per-call 超时约束）。
     */
    public AiGenerationResult generateForRoute(RouteTaskExecution task, AiGatewayConfig routeConfig) {
        if (task == null || task.taskType() == null || task.taskType().isBlank()
                || task.prompt() == null || routeConfig == null) {
            throw new IllegalArgumentException("route task execution fields required");
        }
        long timeoutMs = task.timeoutMsOverride() != null
            ? task.timeoutMsOverride() : Math.max(1L, routeConfig.requestTimeoutMs());
        return invokeWithConfig(task.taskType(), task.projectId(), task.runId(), task.prompt(),
            routeConfig, timeoutMs, false);
    }

    /**
     * 兼容/测试工厂：把裸网关包装为最小 facade（无 settings、无预算服务、无 attempt 仓储；
     * 仅保留台账时间上限）。生产路径一律注入完整 bean。
     */
    public static AiTaskExecutionFacade minimal(AiGateway gateway, AiExecutionLedger ledger) {
        return minimal(gateway, ledger, Clock.systemUTC());
    }

    /** 兼容/测试工厂（显式时钟）：台账剩余的计算必须与调用方使用同一时钟。 */
    public static AiTaskExecutionFacade minimal(AiGateway gateway, AiExecutionLedger ledger, Clock clock) {
        AiTaskExecutionFacade facade = new AiTaskExecutionFacade(null, gateway, ledger, null, null, 0, 0, "", "");
        facade.useClock(clock);
        return facade;
    }

    /**
     * 共享内核：台账（开启语义仅 runId 支持的调用）、attempt 两阶段、发布开关、token 预算
     * reserve/settle、失败分类。openLedger=false 时只读取既有台账（路由路径的 run 期限由
     * run 持有者开启，facade 不替调用方重开窗口）。
     */
    private AiGenerationResult invokeWithConfig(String taskType, Long projectId, String runId,
                                                Object prompt, AiGatewayConfig config,
                                                long timeoutMs, boolean openLedger) {
        boolean ephemeralBudget = runId == null || runId.isBlank();
        String budgetKey = ephemeralBudget
            ? "task-" + taskType + "-" + UUID.randomUUID() : runId;
        try {
            if (!ephemeralBudget) {
                if (openLedger) {
                    ledger.openIfAbsent(budgetKey, clock.instant().plusMillis(Math.max(timeoutMs, 1)));
                }
                long remaining = ledger.remainingBudgetMs(budgetKey, Math.max(timeoutMs, 1), clock);
                String attemptId = attempts == null ? "" : attempts.begin("", taskType, budgetKey,
                    "task=" + taskType + " project=" + projectId
                        + " promptChars=" + String.valueOf(prompt).length(),
                    "model=" + (config.model() == null ? "" : config.model()), "");
                if (remaining <= 0) {
                    finalizeAttempt(attemptId, false, "", "RUN_DEADLINE_EXCEEDED", false, 0, 0);
                    return new AiGenerationResult(false, null, "", 0, 0, "已超过整体运行时限，未发出模型请求", true,
                        "RUN_DEADLINE_EXCEEDED");
                }
                return guardedCall(taskType, projectId, budgetKey, prompt, config, remaining, attemptId);
            }
            // 无 runId：仅受 per-call 超时约束，不污染 run 台账；attempt 审计照常记录（与 generate 一致）。
            long remaining = ledger.remainingBudgetMs(null, Math.max(timeoutMs, 1), clock);
            String attemptId = attempts == null ? "" : attempts.begin("", taskType, budgetKey,
                "task=" + taskType + " project=" + projectId
                    + " promptChars=" + String.valueOf(prompt).length(),
                "model=" + (config.model() == null ? "" : config.model()), "");
            return guardedCall(taskType, projectId, budgetKey, prompt, config, remaining, attemptId);
        } finally {
            // 单次调用专用的临时台账随调用回收，绝不随进程累积（T07 复核 C2）；
            // run 级台账由 run 的持有者（worker/调用方）负责关闭。
            if (ephemeralBudget) {
                ledger.close(budgetKey);
            }
        }
    }

    private AiGenerationResult guardedCall(String taskType, Long projectId, String budgetKey,
                                           Object prompt, AiGatewayConfig config, long remaining,
                                           String attemptId) {
        // T15：发布开关——禁用的任务/项目在发出任何请求前阻断（不并行发双份付费请求）。
        String gate = runtimeGate(taskType, projectId);
        if (gate != null) {
            finalizeAttempt(attemptId, false, "", "RUNTIME_DISABLED", false, 0, 0);
            return new AiGenerationResult(false, null, "", 0, 0,
                "该任务/项目尚未启用统一 Runtime: " + gate, false, "runtime_disabled", -1);
        }
        // T13：预算护栏——保守上界（1 token/字符 + 输出预留），不足即阻断，绝不放行无预算调用。
        long tokensUpperBound = (long) String.valueOf(prompt).length() + 4096L;
        List<com.haitlify.aitest.platform.ai.quota.AiBudgetService.Reservation> reservations;
        try {
            reservations = reserveBudget(projectId, taskType, tokensUpperBound, attemptId);
        } catch (com.haitlify.aitest.platform.ai.quota.AiBudgetService.BudgetExceededException exceeded) {
            finalizeAttempt(attemptId, false, "", "BUDGET_EXCEEDED", false, 0, 0);
            return new AiGenerationResult(false, null, "", 0, 0,
                "预算不足，未发出模型请求: " + blankSafe(exceeded.getMessage()), false, "budget_exceeded", -1);
        }
        boolean budgetUsageKnown = false;
        long budgetActualTokens = 0;
        try {
            long capped = Math.min(remaining, (long) Integer.MAX_VALUE);
            int perCall = (int) (config.requestTimeoutMs() > 0
                ? Math.min(capped, config.requestTimeoutMs()) : capped);
            if (perCall <= 0) {
                perCall = 1;
            }
            AiGatewayConfig effectiveConfig = perCall == config.requestTimeoutMs()
                ? config
                : new AiGatewayConfig(config.providerType(), config.baseUrl(), config.model(),
                    config.visionModel(), config.apiKey(), perCall);
            try {
                AiGenerationResult result = gateway.structuredGenerate(effectiveConfig, prompt);
                boolean usageKnown = result != null && result.success()
                    && (result.inputTokens() > 0 || result.outputTokens() > 0);
                finalizeAttempt(attemptId, result != null && result.success(),
                    result == null ? "" : (result.requestId() == null ? "" : result.requestId()),
                    result == null || result.success() ? "" : blankSafe(result.retryReason()),
                    usageKnown,
                    usageKnown ? result.inputTokens() : 0,
                    usageKnown ? result.outputTokens() : 0);
                budgetUsageKnown = usageKnown;
                budgetActualTokens = usageKnown ? result.inputTokens() + result.outputTokens() : 0;
                return result;
            } catch (RuntimeException failure) {
                AiFailureClassifier.Classification classification = AiFailureClassifier.classify(0, failure);
                finalizeAttempt(attemptId, false, "", classification.reason().name(), false, 0, 0);
                throw failure;
            }
        } finally {
            // 失败/异常/未知 usage 一律保守结算（未知绝不免费）。
            settleBudget(reservations, budgetActualTokens, budgetUsageKnown);
        }
    }

    /** attempt 终态（attemptId 为空 = 未启用记录，直接跳过）。 */
    private void finalizeAttempt(String attemptId, boolean succeeded, String requestId,
                                 String errorCategory, boolean usageKnown, int inputTokens, int outputTokens) {
        if (attempts == null || attemptId == null || attemptId.isEmpty()) {
            return;
        }
        attempts.finalize(attemptId, new AiAttemptRepository.AttemptOutcome(
            succeeded ? AiAttemptRepository.STATUS_SUCCEEDED : AiAttemptRepository.STATUS_FAILED,
            null, requestId, errorCategory, errorCategory, usageKnown,
            usageKnown ? inputTokens : null, usageKnown ? outputTokens : null, null, null));
    }

    /**
     * 视觉任务描述（与文本任务同一套台账/审计；携带图片与视觉参数）。
     */
    public record VisionTaskExecution(String taskType, Long projectId, String actorId, String runId,
                                      String prompt, String contentType, byte[] imageBytes,
                                      Integer maxTokens, Long timeoutMsOverride) {}

    /** T07：视觉通道的 sanction 入口——与 {@link #generate} 同一预算/审计语义。 */
    public com.haitlify.aitest.platform.ai.gateway.AiVisionResponse generateVision(VisionTaskExecution task) {
        if (task == null || task.taskType() == null || task.taskType().isBlank()
                || task.prompt() == null || task.imageBytes() == null) {
            throw new IllegalArgumentException("vision task execution fields required");
        }
        com.haitlify.aitest.platform.ai.gateway.ResolvedAiVisionGatewaySettings settings =
            settingsService.loadDefaultVisionSettings();
        long timeoutMs = task.timeoutMsOverride() != null ? task.timeoutMsOverride() : settings.timeoutMs();
        boolean ephemeralBudget = task.runId() == null || task.runId().isBlank();
        String budgetKey = ephemeralBudget
            ? "task-" + task.taskType() + "-" + UUID.randomUUID() : task.runId();
        try {
            ledger.openIfAbsent(budgetKey, clock.instant().plusMillis(Math.max(timeoutMs, 1)));
            long remaining = ledger.remainingBudgetMs(budgetKey, Math.max(timeoutMs, 1), clock);
            String attemptId = attempts.begin("", task.taskType(), budgetKey,
                "task=" + task.taskType() + " project=" + task.projectId()
                    + " promptChars=" + task.prompt().length() + " imageBytes=" + task.imageBytes().length,
                "visionModel=" + settings.visionModel(), "");
            if (remaining <= 0) {
                attempts.finalize(attemptId, new AiAttemptRepository.AttemptOutcome(
                    AiAttemptRepository.STATUS_FAILED, null, "", "RUN_DEADLINE_EXCEEDED", "RUN_DEADLINE_EXCEEDED",
                    false, null, null, null, null));
                return new com.haitlify.aitest.platform.ai.gateway.AiVisionResponse(false, null, "",
                    0, 0, "已超过整体运行时限，未发出模型请求");
            }
            // T15：发布开关（视觉通道同权）。
            String gate = runtimeGate(task.taskType(), task.projectId());
            if (gate != null) {
                attempts.finalize(attemptId, new AiAttemptRepository.AttemptOutcome(
                    AiAttemptRepository.STATUS_FAILED, null, "", "RUNTIME_DISABLED", "RUNTIME_DISABLED",
                    false, null, null, null, null));
                return new com.haitlify.aitest.platform.ai.gateway.AiVisionResponse(false, null, "",
                    0, 0, "该任务/项目尚未启用统一 Runtime: " + gate);
            }
            // T13：视觉调用同样计入预算（图片体积按 1 token/4 字节的保守上界折算）。
            long tokensUpperBound = (long) task.prompt().length() + (long) task.imageBytes().length / 4L + 4096L;
            List<com.haitlify.aitest.platform.ai.quota.AiBudgetService.Reservation> reservations;
            try {
                reservations = reserveBudget(task.projectId(), task.taskType(), tokensUpperBound, attemptId);
            } catch (com.haitlify.aitest.platform.ai.quota.AiBudgetService.BudgetExceededException exceeded) {
                attempts.finalize(attemptId, new AiAttemptRepository.AttemptOutcome(
                    AiAttemptRepository.STATUS_FAILED, null, "", "BUDGET_EXCEEDED", "BUDGET_EXCEEDED",
                    false, null, null, null, null));
                return new com.haitlify.aitest.platform.ai.gateway.AiVisionResponse(false, null, "",
                    0, 0, "预算不足，未发出模型请求: " + blankSafe(exceeded.getMessage()));
            }
            boolean budgetUsageKnown = false;
            long budgetActualTokens = 0;
            try {
                com.haitlify.aitest.platform.ai.gateway.AiGatewayConfig config = new com.haitlify.aitest.platform.ai.gateway.AiGatewayConfig(
                    settings.providerType(), settings.baseUrl(), settings.textModel(), settings.visionModel(),
                    settings.apiKey(), (int) Math.min(remaining, Integer.MAX_VALUE));
                try {
                    com.haitlify.aitest.platform.ai.gateway.AiVisionResponse result = gateway.visionAnalyze(config,
                        new com.haitlify.aitest.platform.ai.gateway.AiVisionRequest(task.prompt(), task.contentType(),
                            task.imageBytes(), task.maxTokens() == null ? 2048 : task.maxTokens()));
                    boolean usageKnown = result != null && result.success()
                        && (result.inputTokens() > 0 || result.outputTokens() > 0);
                    attempts.finalize(attemptId, new AiAttemptRepository.AttemptOutcome(
                        result != null && result.success() ? AiAttemptRepository.STATUS_SUCCEEDED : AiAttemptRepository.STATUS_FAILED,
                        null,
                        result == null ? "" : (result.requestId() == null ? "" : result.requestId()),
                        "",
                        "",
                        usageKnown,
                        usageKnown ? result.inputTokens() : null,
                        usageKnown ? result.outputTokens() : null,
                        null, null));
                    budgetUsageKnown = usageKnown;
                    budgetActualTokens = usageKnown ? result.inputTokens() + result.outputTokens() : 0;
                    return result;
                } catch (RuntimeException failure) {
                    AiFailureClassifier.Classification classification = AiFailureClassifier.classify(0, failure);
                    attempts.finalize(attemptId, new AiAttemptRepository.AttemptOutcome(
                        AiAttemptRepository.STATUS_FAILED, null, "", classification.reason().name(),
                        classification.reason().name(), false, null, null, null, null));
                    throw failure;
                }
            } finally {
                settleBudget(reservations, budgetActualTokens, budgetUsageKnown);
            }
        } finally {
            if (ephemeralBudget) {
                ledger.close(budgetKey);
            }
        }
    }

    private static String blankSafe(String value) {
        return value == null ? "" : value;
    }
}
