package com.haitlify.aitest.platform.ai.runtime;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * T05 取消注册表：runId 维度的取消旗标。执行器在每次调用前、退避后、返回后检查，
 * 取消后绝不再发起新的模型调用；已产生的调用结果由调用方按 cancel 语义处理（不吞、不重试）。
 */
@Component
public class AiCancellationRegistry {

    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

    /** @return true 当本次调用首次把该 run 置为取消 */
    public boolean cancel(String runId) {
        return runId != null && cancelled.add(runId);
    }

    public boolean isCancelled(String runId) {
        return runId != null && cancelled.contains(runId);
    }

    public void clear(String runId) {
        if (runId != null) {
            cancelled.remove(runId);
        }
    }

    /** 泄漏检查用（终态 run 必须 clear）。 */
    public int cancelledCount() {
        return cancelled.size();
    }
}
