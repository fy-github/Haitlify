package com.haitlify.aitest.platform.assistant.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.haitlify.aitest.platform.assistant.ledger.AssistantAdmissionService;
import com.haitlify.aitest.platform.assistant.ledger.AssistantLedgerRepository;

/** 对账：到期资源升级人工、满足三条件的请求收尾 CLOSED。 */
@SpringBootTest(properties = "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=")
@ActiveProfiles("test")
class AssistantReconciliationServiceTest {

    @Autowired
    private AssistantReconciliationService reconciliation;

    @Autowired
    private AssistantLedgerRepository repository;

    @Autowired
    private AssistantAdmissionService admission;

    private long tenant() {
        return Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1_000_000L;
    }

    @Test
    void expiredResourcesBecomeManualRequiredWithEvidence() {
        long tenant = tenant();
        String operationId = UUID.randomUUID().toString();
        String resourceId = UUID.randomUUID().toString();
        repository.recordResource(resourceId, operationId, "TEST_DATA", tenant, "HAITLIFY_WORKER",
            Instant.now().minusSeconds(5));

        int expired = reconciliation.expireResources();

        assertThat(expired).isGreaterThanOrEqualTo(1);
        AssistantLedgerRepository.ResourceRecord resource = repository.listResources(operationId).get(0);
        assertThat(resource.cleanupState()).isEqualTo("MANUAL_REQUIRED");
        assertThat(repository.countReconciliation(operationId, "MANUAL_DISPOSITION")).isEqualTo(1);
    }

    @Test
    void settledRequestIsClosedOnlyWhenResourcesAreDone() {
        long tenant = tenant();
        String requestId = UUID.randomUUID().toString();
        var admitted = new com.haitlify.aitest.platform.assistant.ledger.AssistantAdmissionService.AdmissionCommand(
            tenant, 5L, 1L, requestId, "a".repeat(64), "web.case.execute", "project:1/x", "rev-1", null);
        // 直接构造账本行：受理 → 终态 → 报告接受
        String operationId = UUID.randomUUID().toString();
        repository.insertRequestIfAbsent(tenant, 5L, 1L, requestId, "a".repeat(64), null, operationId);
        repository.createOperation(operationId, tenant, 1L, "web.case.execute", "project:1/x", "rev-1",
            "a".repeat(64), UUID.randomUUID().toString());
        repository.transitionExecution(operationId, "PENDING", "DISPATCHED");
        repository.transitionExecution(operationId, "DISPATCHED", "RUNNING");
        repository.transitionExecution(operationId, "RUNNING", "SUCCEEDED");
        repository.setReportState(operationId, "ACCEPTED");
        // 有 attempt 才叫「真跑过」：零 attempt 的请求只能 CLOSED_WITH_UNRESOLVED。
        String attemptId = UUID.randomUUID().toString();
        repository.insertAttempt(attemptId, operationId, 1, Instant.now());
        repository.finishAttempt(attemptId, "SUCCEEDED");
        String resourceId = UUID.randomUUID().toString();
        repository.recordResource(resourceId, operationId, "BROWSER", tenant, "RUNNER_FINALLY",
            Instant.now().plusSeconds(3600));

        // 还有未回收资源 ⇒ 不收尾
        reconciliation.closeSettledRequests();
        assertThat(repository.findRequest(tenant, requestId).orElseThrow().admissionState()).isNotEqualTo("CLOSED");

        repository.markResourceCleaned(resourceId, "{\"readback\":\"closed\"}", 1L);
        reconciliation.closeSettledRequests();
        assertThat(repository.findRequest(tenant, requestId).orElseThrow().admissionState()).isEqualTo("CLOSED");
    }

    @Test
    void unrecoverableResourceClosesWithUnresolvedInsteadOfCleanClosure() {
        long tenant = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1_000_000L;
        AssistantAdmissionService.AdmissionResult admitted = admission.admit(
            new AssistantAdmissionService.AdmissionCommand(tenant, 5L, 1L, UUID.randomUUID().toString(),
                "a".repeat(64), "web.case.execute", "project:1/case-a1", "rev-1", null));
        repository.recordResource(UUID.randomUUID().toString(), admitted.operationId(), "BROWSER", 5L,
            "RUNNER_FINALLY", Instant.now().plusSeconds(60));
        repository.markResourceManualRequired(
            repository.listResources(admitted.operationId()).get(0).resourceId(), "{}");
        repository.markResourceUnrecoverable(
            repository.listResources(admitted.operationId()).get(0).resourceId(), "会话丢失", 5L);
        repository.transitionExecution(admitted.operationId(), "PENDING", "FAILED");
        ledgerReportAccepted(admitted.operationId());

        reconciliation.closeSettledRequests();
        assertThat(repository.findRequest(tenant, admitted.requestId()).orElseThrow().admissionState())
            .isEqualTo("CLOSED_WITH_UNRESOLVED");
    }

    /** 只有「执行终态 + 报告已接受」的请求才进入收尾候选；这里直接把报告轴置为 ACCEPTED。 */
    private void ledgerReportAccepted(String operationId) {
        repository.setReportState(operationId, "ACCEPTED");
    }
}
