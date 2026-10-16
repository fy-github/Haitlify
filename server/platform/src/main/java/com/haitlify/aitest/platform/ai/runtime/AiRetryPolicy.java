package com.haitlify.aitest.platform.ai.runtime;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.function.IntSupplier;

import org.springframework.stereotype.Component;

/**
 * T05 重试策略：退避（指数 + 封顶 + 可注入抖动）与 Retry-After 解析。Sleeper 可注入——
 * 单测用假实现验证抖动/Retry-After/中断语义，零真实等待；运行时用真实休眠。
 *
 * <p>规则（v1）：
 * <ul>
 *   <li>本地退避 = base × 2^min(attempt,6)，封顶 maxBackoffMs；抖动 = ±jitterRatio（默认 0.2，测试可设 0）</li>
 *   <li>Retry-After（秒数优先，其次 HTTP-date；负值/非法忽略）存在时优先于本地退避</li>
 *   <li>waitBeforeRetry 在睡眠期间被中断 ⇒ 返回 false（调用方保留中断标志并按 INTERRUPTED 处理）</li>
 * </ul>
 */
@Component
public class AiRetryPolicy {

    public static final String RULE_VERSION = "v1";

    /** 可注入睡眠（默认 Thread.sleep）；单测用假实现。 */
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final Sleeper sleeper;
    private final Clock clock;
    private final long baseBackoffMs;
    private final long maxBackoffMs;
    private final double jitterRatio;
    private final IntSupplier jitterSource; // 返回 [-1000, 1000] 的有符号千分比，便于注入确定性随机

    @org.springframework.beans.factory.annotation.Autowired
    public AiRetryPolicy(Clock clock) {
        this(Thread::sleep, clock, 1_000L, 30_000L, 0.2, () -> (int) (Math.random() * 2_001) - 1_000);
    }

    public AiRetryPolicy(Sleeper sleeper, Clock clock, long baseBackoffMs, long maxBackoffMs,
                         double jitterRatio, IntSupplier jitterSource) {
        this.sleeper = sleeper;
        this.clock = clock;
        this.baseBackoffMs = Math.max(1, baseBackoffMs);
        this.maxBackoffMs = Math.max(this.baseBackoffMs, maxBackoffMs);
        this.jitterRatio = Math.max(0.0, Math.min(1.0, jitterRatio));
        this.jitterSource = jitterSource;
    }

    /** 本地退避（含抖动），用于日志与无 Retry-After 时的等待。 */
    public long backoffMs(int failedAttempt) {
        long exponential = baseBackoffMs * (1L << Math.min(Math.max(failedAttempt, 0), 6));
        long capped = Math.min(exponential, maxBackoffMs);
        if (jitterRatio <= 0) {
            return capped;
        }
        long jitter = (long) (capped * jitterRatio * jitterSource.getAsInt() / 1_000.0);
        return Math.max(1, capped + jitter);
    }

    /** 解析 Retry-After 头（秒数或 HTTP-date）；无法解析返回 -1。 */
    public long retryAfterMs(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return -1;
        }
        String value = headers.get("retry-after");
        if (value == null) {
            value = headers.get("Retry-After");
        }
        if (value == null || value.isBlank()) {
            return -1;
        }
        String trimmed = value.trim();
        try {
            long seconds = Long.parseLong(trimmed);
            return seconds < 0 ? -1 : Duration.ofSeconds(seconds).toMillis();
        } catch (NumberFormatException notSeconds) {
            // 继续尝试 HTTP-date
        }
        try {
            ZonedDateTime at = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME);
            long delay = Duration.between(clock.instant(), at.toInstant()).toMillis();
            return delay < 0 ? 0 : delay;
        } catch (DateTimeParseException notDate) {
            return -1;
        }
    }

    /** 选择等待时长：Retry-After 优先，否则本地退避。 */
    public long waitMs(int failedAttempt, long retryAfterMsFromResult) {
        return retryAfterMsFromResult >= 0 ? retryAfterMsFromResult : backoffMs(failedAttempt);
    }

    /** 睡眠；被中断返回 false（不吞中断——由调用方重新置位）。 */
    public boolean waitBeforeRetry(int failedAttempt, long retryAfterMsFromResult) {
        long waitMs = waitMs(failedAttempt, retryAfterMsFromResult);
        try {
            sleeper.sleep(waitMs);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
