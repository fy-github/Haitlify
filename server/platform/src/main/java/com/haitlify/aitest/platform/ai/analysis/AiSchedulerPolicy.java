package com.haitlify.aitest.platform.ai.analysis;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 持久调度器的运营策略值（T-Review-4 契约，详见 docs/ai-test-platform/job-scheduler-contract.md）。
 * 所有值可由应用配置覆盖，但受平台上限约束；越界即启动失败（fail-closed），不允许静默夹取。
 */
@Component
public class AiSchedulerPolicy {

    /** 租约 TTL 必须覆盖单次心跳间隔且短于任何人工介入时长；上限 300s。 */
    public static final Duration MIN_LEASE_TTL = Duration.ofSeconds(30);
    public static final Duration MAX_LEASE_TTL = Duration.ofSeconds(300);
    /** 续租间隔：TTL 的 1/4 是业界通用起点；必须严格小于 TTL，上限 TTL/2。 */
    public static final Duration MIN_RENEW_INTERVAL = Duration.ofSeconds(5);
    /** 恢复扫描间隔：独立于 TTL，上限 300s。 */
    public static final Duration MIN_RECOVERY_INTERVAL = Duration.ofSeconds(10);
    public static final Duration MAX_RECOVERY_INTERVAL = Duration.ofSeconds(300);
    /** 优雅停机窗口：等待在飞任务的截止时间，上限 120s（须小于租约 TTL 上限 + 复核窗口）。 */
    public static final Duration MIN_GRACEFUL_SHUTDOWN = Duration.ofSeconds(10);
    public static final Duration MAX_GRACEFUL_SHUTDOWN = Duration.ofSeconds(120);
    /** 单项目并发运行上限的默认值与平台上限。 */
    public static final int DEFAULT_MAX_ACTIVE_RUNS_PER_PROJECT = 1;
    public static final int MAX_ACTIVE_RUNS_PER_PROJECT = 4;

    private final Duration leaseTtl;
    private final Duration renewInterval;
    private final Duration recoveryInterval;
    private final Duration gracefulShutdownDeadline;
    private final int maxActiveRunsPerProject;

    public AiSchedulerPolicy(
        @Value("${app.ai.scheduler.lease-ttl-seconds:60}") long leaseTtlSeconds,
        @Value("${app.ai.scheduler.renew-interval-seconds:15}") long renewIntervalSeconds,
        @Value("${app.ai.scheduler.recovery-interval-seconds:30}") long recoveryIntervalSeconds,
        @Value("${app.ai.scheduler.graceful-shutdown-seconds:30}") long gracefulShutdownSeconds,
        @Value("${app.ai.scheduler.max-active-runs-per-project:1}") int maxActiveRunsPerProject
    ) {
        this.leaseTtl = Duration.ofSeconds(leaseTtlSeconds);
        this.renewInterval = Duration.ofSeconds(renewIntervalSeconds);
        this.recoveryInterval = Duration.ofSeconds(recoveryIntervalSeconds);
        this.gracefulShutdownDeadline = Duration.ofSeconds(gracefulShutdownSeconds);
        this.maxActiveRunsPerProject = maxActiveRunsPerProject;
        validate();
    }

    private void validate() {
        requireWithin("lease-ttl-seconds", leaseTtl, MIN_LEASE_TTL, MAX_LEASE_TTL);
        requireWithin("renew-interval-seconds", renewInterval, MIN_RENEW_INTERVAL, half(leaseTtl));
        requireWithin("recovery-interval-seconds", recoveryInterval, MIN_RECOVERY_INTERVAL, MAX_RECOVERY_INTERVAL);
        requireWithin("graceful-shutdown-seconds", gracefulShutdownDeadline, MIN_GRACEFUL_SHUTDOWN, MAX_GRACEFUL_SHUTDOWN);
        if (maxActiveRunsPerProject < 1 || maxActiveRunsPerProject > MAX_ACTIVE_RUNS_PER_PROJECT) {
            throw new IllegalStateException(
                "app.ai.scheduler.max-active-runs-per-project 超出平台允许范围 [1, " + MAX_ACTIVE_RUNS_PER_PROJECT
                    + "]：实际 " + maxActiveRunsPerProject);
        }
    }

    private static Duration half(Duration value) {
        return Duration.ofMillis(Math.max(1, value.toMillis() / 2));
    }

    private static void requireWithin(String property, Duration value, Duration min, Duration max) {
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new IllegalStateException(
                "app.ai.scheduler." + property + " 超出平台允许范围 [" + min.toSeconds() + "s, " + max.toSeconds()
                    + "s]：实际 " + value.toSeconds() + "s");
        }
    }

    public Duration leaseTtl() {
        return leaseTtl;
    }

    public Duration renewInterval() {
        return renewInterval;
    }

    public Duration recoveryInterval() {
        return recoveryInterval;
    }

    public Duration gracefulShutdownDeadline() {
        return gracefulShutdownDeadline;
    }

    public int maxActiveRunsPerProject() {
        return maxActiveRunsPerProject;
    }
}
