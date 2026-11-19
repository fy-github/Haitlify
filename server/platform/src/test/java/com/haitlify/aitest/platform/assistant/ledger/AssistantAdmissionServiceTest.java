package com.haitlify.aitest.platform.assistant.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.ActiveProfiles;

/**
 * 受理账本的四条硬约束：同 ID 同输入幂等、同 ID 不同输入拒绝、UNKNOWN 冻结派发、终态不可覆盖。
 * 另含「模型换随机键绕过」的模拟（同一幂等键 + 不同 operationId ⇒ 唯一约束拒绝）。
 */
@SpringBootTest(properties = "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=")
@ActiveProfiles("test")
class AssistantAdmissionServiceTest {

    private static final String DIGEST_A = "a".repeat(64);
    private static final String DIGEST_B = "b".repeat(64);

    @Autowired
    private AssistantAdmissionService service;

    @Autowired
    private AssistantLedgerRepository repository;

    @Autowired
    private AssistantIdempotencyKeys keys;

    private long nextTenant() {
        return Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1_000_000L;
    }

    private AssistantAdmissionService.AdmissionCommand command(long tenantId, String requestId, String digest) {
        return new AssistantAdmissionService.AdmissionCommand(tenantId, 77L, null, requestId, digest,
            "web.contact.create", "project:100509316/contacts", "rev-1", null);
    }

    @Test
    void admitsOnceAndReplaysSameRequestIdempotently() {
        long tenant = nextTenant();
        String requestId = UUID.randomUUID().toString();

        AssistantAdmissionService.AdmissionResult first = service.admit(command(tenant, requestId, DIGEST_A));
        assertThat(first.created()).isTrue();
        assertThat(first.admissionState()).isEqualTo("ADMITTED");
        assertThat(first.executionState()).isEqualTo("PENDING");

        AssistantAdmissionService.AdmissionResult replay = service.admit(command(tenant, requestId, DIGEST_A));
        assertThat(replay.created()).isFalse();
        assertThat(replay.operationId()).isEqualTo(first.operationId());
    }

    @Test
    void rejectsSameRequestIdWithDifferentInput() {
        long tenant = nextTenant();
        String requestId = UUID.randomUUID().toString();
        service.admit(command(tenant, requestId, DIGEST_A));

        assertThatThrownBy(() -> service.admit(command(tenant, requestId, DIGEST_B)))
            .isInstanceOf(AssistantAdmissionService.IdempotencyConflictException.class)
            .hasMessageContaining("输入摘要不一致");
    }

    @Test
    void markDispatchedMovesOnlyTheRequestAxis() {
        long tenant = nextTenant();
        String requestId = UUID.randomUUID().toString();
        AssistantAdmissionService.AdmissionResult admitted = service.admit(command(tenant, requestId, DIGEST_A));

        // operation 的 PENDING→DISPATCHED 由 dispatch 的一次性 CAS 负责；这里只推进请求轴。
        assertThat(service.markDispatched(tenant, requestId, admitted.operationId())).isTrue();
        assertThat(repository.findRequest(tenant, requestId).orElseThrow().admissionState()).isEqualTo("DISPATCHED");
        assertThat(repository.findOperation(admitted.operationId()).orElseThrow().executionState()).isEqualTo("PENDING");

        // 已 DISPATCHED 的请求不能再被 markDispatched（CAS），也不能被 UNKNOWN 覆盖成回退。
        assertThat(service.markDispatched(tenant, requestId, admitted.operationId())).isFalse();
        assertThat(repository.markRequestUnknown(tenant, requestId)).isTrue();
        assertThat(repository.findRequest(tenant, requestId).orElseThrow().admissionState()).isEqualTo("UNKNOWN");
        assertThat(repository.closeRequest(tenant, requestId, "CLOSED")).isTrue();
        assertThat(repository.markRequestUnknown(tenant, requestId)).isFalse();
    }

    @Test
    void unknownFreezesDispatchUntilResolved() {
        long tenant = nextTenant();
        String requestId = UUID.randomUUID().toString();
        AssistantAdmissionService.AdmissionResult admitted = service.admit(command(tenant, requestId, DIGEST_A));
        String operationId = admitted.operationId();

        repository.transitionExecution(operationId, "PENDING", "DISPATCHED");
        repository.transitionExecution(operationId, "DISPATCHED", "RUNNING");
        service.raiseUnknown(tenant, requestId, operationId, "回执丢失");

        assertThat(repository.findOperation(operationId).orElseThrow().executionState()).isEqualTo("UNKNOWN");
        assertThatThrownBy(() -> service.assertDispatchAllowed(operationId))
            .isInstanceOf(AssistantAdmissionService.DispatchFrozenException.class)
            .hasMessageContaining("冻结");
        assertThat(repository.countReconciliation(operationId, "UNKNOWN_RAISED")).isEqualTo(1);

        // 权威回读后允许从 UNKNOWN 落到终态，但此后不得再迁移。
        assertThat(repository.transitionExecution(operationId, "UNKNOWN", "SUCCEEDED")).isTrue();
        assertThat(repository.transitionExecution(operationId, "SUCCEEDED", "FAILED")).isFalse();
        assertThatThrownBy(() -> service.assertDispatchAllowed(operationId))
            .isInstanceOf(AssistantAdmissionService.DispatchFrozenException.class)
            .hasMessageContaining("终态");
    }

    @Test
    void idempotencyKeyCannotBeReusedWithAnotherOperation() {
        long tenant = nextTenant();
        String operationId = UUID.randomUUID().toString();
        String key = keys.derive(tenant, operationId, "rev-1", "web.contact.create", DIGEST_A);
        repository.insertOperationRow(operationId, tenant, 1L, "web.contact.create", "project:1/contacts", "rev-1",
            DIGEST_A, key);

        // 模拟模型换一个 operationId 但复用同一个键：唯一约束必须拒绝。
        assertThatThrownBy(() -> repository.insertOperationRow(UUID.randomUUID().toString(), tenant, 1L,
            "web.contact.create", "project:1/contacts", "rev-1", DIGEST_A, key))
            .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void rejectsMalformedAdmissionInput() {
        long tenant = nextTenant();
        assertThatThrownBy(() -> service.admit(new AssistantAdmissionService.AdmissionCommand(
            tenant, 1L, null, "not-a-uuid", DIGEST_A, "web.contact.create", "ref", "rev-1", null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("requestId");

        assertThatThrownBy(() -> service.admit(new AssistantAdmissionService.AdmissionCommand(
            tenant, 1L, null, UUID.randomUUID().toString(), "deadbeef", "web.contact.create", "ref", "rev-1", null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("inputDigest");

        assertThatThrownBy(() -> service.admit(new AssistantAdmissionService.AdmissionCommand(
            tenant, 1L, null, UUID.randomUUID().toString(), DIGEST_A, "create", "ref", "rev-1", null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("actionCode");
    }

    @Test
    void resourceLedgerTracksUnreclaimedAllocations() {
        long tenant = nextTenant();
        String resourceId = UUID.randomUUID().toString();
        String operationId = UUID.randomUUID().toString();
        Instant deadline = Instant.parse("2030-01-01T00:00:00Z");

        repository.recordResource(resourceId, operationId, "TEST_DATA", tenant, "HAITLIFY_WORKER", deadline);
        assertThat(repository.findUnreclaimed(Instant.parse("2030-01-02T00:00:00Z")))
            .extracting(AssistantLedgerRepository.ResourceRecord::resourceId).contains(resourceId);

        repository.markResourceCleaned(resourceId, "{\"readback\":\"empty\"}", 1L);
        assertThat(repository.findUnreclaimed(Instant.parse("2030-01-02T00:00:00Z")))
            .extracting(AssistantLedgerRepository.ResourceRecord::resourceId).doesNotContain(resourceId);
    }

    @Test
    void replayInAnotherProjectIsRejected() {
        long tenant = nextTenant();
        String requestId = UUID.randomUUID().toString();
        service.admit(new AssistantAdmissionService.AdmissionCommand(tenant, 77L, 1L, requestId, DIGEST_A,
            "web.contact.create", "project:1/contacts", "rev-1", null));

        // 同一 requestId 在别的项目重放：不能把 A 项目的 operation/runId 交给 B 项目。
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.admit(
                new AssistantAdmissionService.AdmissionCommand(tenant, 77L, 2L, requestId, DIGEST_A,
                    "web.contact.create", "project:2/contacts", "rev-1", null)))
            .isInstanceOf(AssistantAdmissionService.IdempotencyConflictException.class);
    }
}
