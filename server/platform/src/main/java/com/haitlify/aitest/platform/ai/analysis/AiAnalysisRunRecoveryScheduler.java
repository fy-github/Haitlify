package com.haitlify.aitest.platform.ai.analysis;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 恢复作业（T-Review-4 契约）：应用就绪时执行一次过期租约恢复扫描，随后按策略间隔周期执行。
 * 恢复只处理「运行中但租约已过期/缺失」的任务；持有有效租约的任务不受影响。
 * 第三十轮补审 2：测试上下文（共享内存库 + mock/缺失时钟，多个上下文调度器互相看不见对方的时钟）
 * 必须整体关闭本调度器——否则会误重排进行中的任务，配合自动派发会造成跨测试的真实竞争。
 */
@Component
@ConditionalOnProperty(prefix = "app.ai.scheduler", name = "recovery-enabled", havingValue = "true", matchIfMissing = true)
class AiAnalysisRunRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(AiAnalysisRunRecoveryScheduler.class);

    private final AiAnalysisJobLeaseService leaseService;
    private final AiSchedulerPolicy policy;
    private final AiAnalysisRunDispatcher dispatcher;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ai-analysis-recovery");
        thread.setDaemon(true);
        return thread;
    });

    AiAnalysisRunRecoveryScheduler(AiAnalysisJobLeaseService leaseService, AiSchedulerPolicy policy, AiAnalysisRunDispatcher dispatcher) {
        this.leaseService = leaseService;
        this.policy = policy;
        this.dispatcher = dispatcher;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        runRecovery();
        long intervalSeconds = Math.max(1, policy.recoveryInterval().toSeconds());
        executor.scheduleAtFixedRate(this::runRecovery, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    private void runRecovery() {
        try {
            List<String> recovered = leaseService.recoverExpiredRuns();
            if (!recovered.isEmpty()) {
                log.warn("AI 调度恢复扫描：{} 个任务待恢复派发（重排重跑或孤儿重派）：{}", recovered.size(), recovered);
                for (String runId : recovered) {
                    dispatcher.dispatchRecovered(runId);
                }
            }
        } catch (Exception exception) {
            log.error("AI 调度恢复扫描失败（下个周期将继续尝试）", exception);
        }
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }
}
