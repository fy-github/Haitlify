package com.haitlify.aitest.platform.ai.gateway;

/**
 * Classification of why an AI route attempt ended, driving the bounded-fallback policy.
 *
 * <p>{@link #retryable()} means the failure is eligible for falling over to the next configured
 * route (timeout, rate limiting, upstream 5xx, transient network). Non-retryable reasons stop the
 * route chain immediately (auth denied, model/endpoint not found, invalid request, structured-output
 * validation failure). The remaining values are terminal control-flow signals used by the executor
 * and the worker rather than produced by a single call.
 */
public enum AiRetryReason {
    TIMEOUT(true),
    RATE_LIMITED(true),
    UPSTREAM_ERROR(true),
    TRANSIENT_NETWORK(true),
    AUTH_DENIED(false),
    MODEL_OR_ENDPOINT_NOT_FOUND(false),
    INVALID_REQUEST(false),
    OUTPUT_VALIDATION(false),
    NO_ROUTE(false),
    BUDGET_EXHAUSTED(false),
    RUN_DEADLINE_EXCEEDED(false),
    /** T05：run 级取消——与网络错误分离的终态信号。 */
    CANCELLED(false),
    /** T05：等待重试/调用期间线程被中断——同样不是网络错误。 */
    INTERRUPTED(false),
    /** T05 分类（v1）：明确无可用渠道——终态，不因重试/切换而改变。 */
    NO_CHANNEL(false),
    /** T05 分类（v1）：内容策略拒绝——确定性，重试无益。 */
    REFUSAL(false),
    /** T05 分类（v1）：上游返回可用格式但内容为空——值得一次重试。 */
    EMPTY_OUTPUT(true),
    /** T05 分类（v1）：输出被截断（finish_reason=length 且内容不可用）——确定性，重试无益。 */
    INCOMPLETE_OUTPUT(false),
    /** T15：灰度开关禁用该任务/项目——发出任何请求前阻断，确定性终态。 */
    RUNTIME_DISABLED(false);

    private final boolean retryable;

    AiRetryReason(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }

    public static AiRetryReason fromCode(String code) {
        if (code == null || code.isBlank()) {
            return INVALID_REQUEST;
        }
        String normalized = code.trim();
        // 统一 Runtime 的阻断码别名（facade 返回小写码；一律映射为非重试终态，fail-closed）。
        if ("budget_exceeded".equalsIgnoreCase(normalized)) {
            return BUDGET_EXHAUSTED;
        }
        if ("runtime_disabled".equalsIgnoreCase(normalized)) {
            return RUNTIME_DISABLED;
        }
        try {
            return AiRetryReason.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            return INVALID_REQUEST;
        }
    }
}
