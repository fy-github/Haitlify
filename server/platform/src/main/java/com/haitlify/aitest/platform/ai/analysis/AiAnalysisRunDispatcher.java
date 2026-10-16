package com.haitlify.aitest.platform.ai.analysis;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
class AiAnalysisRunDispatcher {

    private final AiAnalysisWorker aiAnalysisWorker;
    private final AiAnalysisJobLeaseService leaseService;
    private final AiSchedulerPolicy policy;
    private final com.haitlify.aitest.platform.ai.runtime.AiCancellationRegistry cancellationRegistry;
    private final Map<String, Future<?>> activeRuns = new ConcurrentHashMap<>();
    /** 每次派发一个「任务是否已开始执行」标记，供 cancel 区分未启动/运行中（第三十轮补审 8 C-1）。 */
    private final Map<String, AtomicBoolean> startedFlags = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ai-analysis-worker");
        thread.setDaemon(true);
        return thread;
    });

    @org.springframework.beans.factory.annotation.Autowired
    AiAnalysisRunDispatcher(AiAnalysisWorker aiAnalysisWorker, AiAnalysisJobLeaseService leaseService, AiSchedulerPolicy policy,
                            com.haitlify.aitest.platform.ai.runtime.AiCancellationRegistry cancellationRegistry) {
        this.aiAnalysisWorker = aiAnalysisWorker;
        this.leaseService = leaseService;
        this.policy = policy;
        this.cancellationRegistry = cancellationRegistry;
    }

    /** 兼容构造器（既有单测）：取消旗标与执行器一样在本实例内隔离。 */
    AiAnalysisRunDispatcher(AiAnalysisWorker aiAnalysisWorker, AiAnalysisJobLeaseService leaseService, AiSchedulerPolicy policy) {
        this(aiAnalysisWorker, leaseService, policy, new com.haitlify.aitest.platform.ai.runtime.AiCancellationRegistry());
    }

    void dispatchAfterCommit(String runId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch(runId);
                }
            });
            return;
        }
        dispatch(runId);
    }

    void cancelAfterCommit(String runId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cancel(runId);
                }
            });
            return;
        }
        cancel(runId);
    }

    /**
     * 派发（第三十轮补审 6/8/9 收敛后的最终语义）：
     * ① 在飞去重——同一 run 已有条目时不再入队；
     * ② 先放「本次派发唯一的占位」再提交，提交被拒或任务瞬时完成都不残留；
     * ③ 回填只认自己的占位；若占位已被取消（取消落在占位窗口）则不回填，留空供后续派发重新入队；
     * ④ 任务收尾按身份清理（自己有 self 比 self，self 未回填时比占位），绝不误删窗口内新条目、也不回流幽灵条目。
     */
    private void dispatch(String runId) {
        if (leaseService.isStopping()) {
            return;
        }
        CompletableFuture<Object> placeholder = new CompletableFuture<>();
        synchronized (activeRuns) {
            if (activeRuns.containsKey(runId)) {
                return;
            }
            activeRuns.put(runId, placeholder);
        }
        AtomicBoolean started = new AtomicBoolean(false);
        startedFlags.put(runId, started);
        AtomicReference<Future<?>> selfRef = new AtomicReference<>();
        Future<?> future;
        try {
            future = executor.submit(() -> {
                started.set(true);
                try {
                    aiAnalysisWorker.processRun(runId);
                } finally {
                    // 身份校验清理（第三十轮补审 8/10 C-1）：self 读取与比较全部在锁内，
                    // 关闭「读 self → 派发线程抢先回填」的纳秒级幽灵条目窗口（30j C-1）。
                    synchronized (activeRuns) {
                        Future<?> self = selfRef.get();
                        Future<?> current = activeRuns.get(runId);
                        if (current == placeholder || current == self) {
                            activeRuns.remove(runId);
                        }
                    }
                    startedFlags.remove(runId, started);
                }
            });
        } catch (RejectedExecutionException rejected) {
            // 停机窗口内 executor 已关闭：不派发；任务留在 queued，由恢复扫描/重启接管（第三十轮 C6）。
            synchronized (activeRuns) {
                activeRuns.remove(runId, placeholder);
            }
            startedFlags.remove(runId, started);
            return;
        }
        selfRef.set(future);
        synchronized (activeRuns) {
            if (activeRuns.get(runId) == placeholder) {
                if (future.isCancelled()) {
                    // 取消已落在「占位窗口」（第三十轮补审 9 C-4）：不回填 cancelled future，留空供重新入队。
                    activeRuns.remove(runId, placeholder);
                } else {
                    activeRuns.put(runId, future);
                }
            }
        }
    }

    private void cancel(String runId) {
        // T05：先置取消旗标（与在飞 future 是否存在无关）——执行器/修复路径据此在下一个检查点停止。
        cancellationRegistry.cancel(runId);
        Future<?> future = activeRuns.get(runId);
        if (future == null) {
            return;
        }
        // 第三十轮补审 8 C-1：cancel(true) 对运行中任务同样可能返回 true，故用 started 标记区分。
        // 未启动：同步清理条目与标记（被取消任务不执行 ⇒ finally 不会兜底，第三十轮补审 9 C-2 泄漏修复）。
        // 运行中：条目交 finally 身份清理；「已开始未置标记」的理论窗口（9 C-3）由终态守卫兜底。
        boolean cancelled = future.cancel(true);
        AtomicBoolean started = startedFlags.get(runId);
        if (cancelled && (started == null || !started.get())) {
            synchronized (activeRuns) {
                activeRuns.remove(runId, future);
            }
            if (started != null) {
                startedFlags.remove(runId, started);
            }
        }
    }

    /**
     * 优雅停机：先停止领取新任务，再等待在飞任务至多 gracefulShutdownDeadline；
     * 超时后强制中断（租约过期后由恢复扫描接管未完成任务）。
     */
    void gracefulShutdown() {
        leaseService.stopClaiming();
        long deadline = System.currentTimeMillis() + policy.gracefulShutdownDeadline().toMillis();
        while (!activeRuns.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        executor.shutdownNow();
        activeRuns.clear();
        startedFlags.clear();
    }

    /** 恢复扫描重排后的自动派发入口（第三十轮补审 C4：恢复重排必须闭环到执行，不只留事件）。 */
    void dispatchRecovered(String runId) {
        dispatch(runId);
    }

    @PreDestroy
    void shutdown() {
        gracefulShutdown();
    }
}
