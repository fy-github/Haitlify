package com.haitlify.aitest.platform.assistant.reconcile;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.haitlify.aitest.platform.assistant.ledger.AssistantAdmissionService;
import com.haitlify.aitest.platform.assistant.ledger.AssistantLedgerRepository;

/**
 * 资源与请求的对账（计划 §6.7 / §229）：执行结束不等于闭环。
 *
 * <p>两件事：
 * <ol>
 *   <li>到期仍未回收的资源 → {@code MANUAL_REQUIRED}（不再假装平台会兜底），并留对账事件；</li>
 *   <li>「执行终态 + 报告已接受 + 无未回收资源」的请求 → {@code CLOSED}（请求轴的终态）。</li>
 * </ol>
 *
 * <p>默认每 60s 跑一次；测试与本地可用 {@code app.assistant.reconcile.enabled=false} 关闭。
 */
@Service
public class AssistantReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(AssistantReconciliationService.class);

    private final AssistantLedgerRepository repository;
    private final boolean enabled;

    public AssistantReconciliationService(AssistantLedgerRepository repository,
                                          @Value("${app.assistant.reconcile.enabled:true}") boolean enabled) {
        this.repository = repository;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.assistant.reconcile.interval-ms:60000}")
    public void reconcile() {
        if (!enabled) {
            return;
        }
        repairUnknownRequests();
        expireWaitingUsers();
        expireResources();
        closeSettledRequests();
    }

    /**
     * 到期等待用户 → CANCELLED（计划 §4.3「到期处理」）。
     * 等待态不是终态：不清理就会永远不能出报告、请求也永不收尾，所以必须由确定性清扫兜底。
     */
    public int expireWaitingUsers() {
        int expired = 0;
        for (String operationId : repository.expireOverdueWaitingUser(Instant.now())) {
            // 实际落的是 CANCELLED 还是 UNKNOWN（仍有活 run）要看真实状态，不能写死。
            String state = repository.findOperation(operationId)
                .map(AssistantLedgerRepository.OperationRecord::executionState).orElse("UNKNOWN");
            repository.appendReconciliation(operationId, null, "MANUAL_DISPOSITION", null,
                "{\"reason\":\"waiting-user-timeout\",\"state\":\"" + state + "\"}");
            expired += 1;
        }
        if (expired > 0) {
            log.warn("有 {} 条等待用户超过期限，已按规则转终态（无活 run → CANCELLED，有活 run → UNKNOWN）", expired);
        }
        return expired;
    }

    /** 到期资源升级为 MANUAL_REQUIRED，并写对账事件。返回处理条数。 */
    public int expireResources() {
        int expired = 0;
        for (AssistantLedgerRepository.ResourceRecord resource : repository.findUnreclaimed(Instant.now())) {
            boolean marked = repository.markResourceManualRequired(resource.resourceId(),
                "{\"reason\":\"cleanup-deadline-exceeded\",\"executor\":\"" + resource.cleanupExecutor() + "\"}");
            if (marked) {
                repository.appendReconciliation(resource.operationId(), null, "MANUAL_DISPOSITION", null,
                    "{\"reason\":\"resource-expired\",\"resourceId\":\"" + resource.resourceId() + "\",\"kind\":\""
                        + resource.resourceKind() + "\"}");
                expired += 1;
            }
        }
        if (expired > 0) {
            log.warn("有 {} 条资源超过清理期限，已标记 MANUAL_REQUIRED", expired);
        }
        return expired;
    }

    /** 修复：operation 已终态但请求仍 UNKNOWN（崩溃窗口）→ 请求轴回到 DISPATCHED。 */
    public int repairUnknownRequests() {
        int repaired = 0;
        for (AssistantLedgerRepository.RequestRecord request : repository.findRequestsByState("UNKNOWN")) {
            boolean terminal = repository.findOperation(request.operationId())
                .map(operation -> AssistantAdmissionService.isTerminal(operation.executionState()))
                .orElse(false);
            if (terminal && repository.resolveRequestUnknown(request.operationId())) {
                repository.appendReconciliation(request.operationId(), null, "UNKNOWN_REPAIRED", null,
                    "{\"requestId\":\"" + request.requestId() + "\"}");
                repaired += 1;
            }
        }
        return repaired;
    }


    /** 满足三条件的请求收尾为 CLOSED。返回收尾条数。 */
    public int closeSettledRequests() {
        int closed = 0;
        for (String[] row : repository.findRequestsReadyToClose(50)) {
            long tenantId = Long.parseLong(row[0]);
            String terminal = row.length > 2 && row[2] != null ? row[2] : "CLOSED";
            if (repository.closeRequest(tenantId, row[1], terminal)) {
                closed += 1;
            }
        }
        return closed;
    }
}
