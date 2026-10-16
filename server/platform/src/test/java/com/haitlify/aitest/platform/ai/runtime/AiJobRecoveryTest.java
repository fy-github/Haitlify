package com.haitlify.aitest.platform.ai.runtime;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T06 状态转移测试：幂等提交、CAS 领取冲突（含双线程竞态）、租约接管与过期
 * 围栏拒写、晚到取消不损坏终态、运行中取消的围栏写回、恢复时已完成节点跳过
 * 与未知副作用节点 BLOCKED。业务成功路径（双 worker MySQL integration）见
 * {@link com.haitlify.aitest.platform.ai.runtime.AiJobMysqlIntegrationTest}。
 */
@SpringBootTest
@ActiveProfiles("test")
class AiJobRecoveryTest {

    private static final long PROJECT_ID = 987001L;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AiJobService jobServiceBean;

    private MutableClock clock;

    private AiJobService jobs;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-09T02:00:00Z"));
        jobs = new AiJobService(jdbcTemplate, objectMapper, clock);
        jdbcTemplate.update("delete from ai_runtime_job_nodes");
        jdbcTemplate.update("delete from ai_runtime_jobs");
        jdbcTemplate.update("delete from projects where id = ?", PROJECT_ID);
        jdbcTemplate.update(
            "insert into projects(id, code, name, description, active, created_at, updated_at)"
                + " values (?, ?, ?, '', true, ?, ?)",
            PROJECT_ID, "JOBIT-" + PROJECT_ID, "job-recovery-test", new java.sql.Timestamp(System.currentTimeMillis()),
            new java.sql.Timestamp(System.currentTimeMillis()));
        jdbcTemplate.update("delete from ai_runtime_job_nodes where job_id not in (select job_id from ai_runtime_jobs)");
    }

    @Test
    void beanWiringIsPresentInTheContext() {
        assertThat(jobServiceBean).isNotNull();
    }

    @Test
    void duplicateSubmitReturnsTheSameJobAndConflictsFailClosed() {
        String first = jobs.submit("requirement_analysis", PROJECT_ID, "actor-1", "idem-x", "hash-1",
            List.of("generate"), "rev-1");
        String second = jobs.submit("requirement_analysis", PROJECT_ID, "actor-1", "idem-x", "hash-1",
            List.of("generate"), "rev-1");
        assertThat(second).isEqualTo(first);
        assertThat(jobs.find(first)).isPresent();

        // 同一幂等键换输入/类型 ⇒ fail-closed，绝不静默返回旧单。
        assertThatThrownBy(() -> jobs.submit("requirement_analysis", PROJECT_ID, "actor-1", "idem-x", "hash-2",
            List.of("generate"), "rev-1")).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("idempotency_conflict");
        assertThatThrownBy(() -> jobs.submit("other_type", PROJECT_ID, "actor-1", "idem-x", "hash-1",
            List.of("generate"), "rev-1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void twoWorkersCannotClaimTheSameJob() {
        String jobId = submitOne();
        Optional<AiJobService.AiJob> first = jobs.claim("worker-a", Duration.ofSeconds(30));
        assertThat(first).isPresent();
        assertThat(first.get().jobId()).isEqualTo(jobId);
        assertThat(first.get().status()).isEqualTo(AiJobService.STATUS_RUNNING);
        assertThat(first.get().leaseOwner()).isEqualTo("worker-a");

        Optional<AiJobService.AiJob> second = jobs.claim("worker-b", Duration.ofSeconds(30));
        assertThat(second).isEmpty();
    }

    @Test
    void leaseTakeoverBumpsFencingAndStaleWritesAreRejected() {
        String jobId = submitOne();
        AiJobService.AiJob claimA = jobs.claim("worker-a", Duration.ofSeconds(30)).orElseThrow();
        long fenceA = claimA.fencingToken();

        clock.advance(Duration.ofSeconds(31));
        List<String> reclaimed = jobs.reclaimExpired();
        assertThat(reclaimed).containsExactly(jobId);
        assertThat(jobs.find(jobId).orElseThrow().status()).isEqualTo(AiJobService.STATUS_QUEUED);

        // 过期持有者：续租/写回全拒绝（围栏已变）。
        assertThat(jobs.heartbeat(jobId, "worker-a", fenceA, Duration.ofSeconds(30))).isFalse();
        assertThat(jobs.complete(jobId, fenceA, "stale")).isFalse();
        assertThat(jobs.fail(jobId, fenceA, "stale")).isFalse();

        // 新持有者拿到单调递增的围栏并成功收尾。
        AiJobService.AiJob claimB = jobs.claim("worker-b", Duration.ofSeconds(30)).orElseThrow();
        assertThat(claimB.fencingToken()).isGreaterThan(fenceA);
        assertThat(jobs.complete(jobId, claimB.fencingToken(), "ok")).isTrue();
        assertThat(jobs.find(jobId).orElseThrow().status()).isEqualTo(AiJobService.STATUS_SUCCEEDED);
    }

    @Test
    void staleOwnerCannotWriteNodeCheckpointsAfterTakeover() {
        // C-3 锁测：接管后过期持有者不能 complete/fail 任何节点（节点写带围栏）。
        String jobId = submitOne();
        AiJobService.AiJob claimA = jobs.claim("worker-a", Duration.ofSeconds(30)).orElseThrow();
        long fenceA = claimA.fencingToken();
        assertThat(jobs.startNode(jobId, "step-1", "attempt-a", AiJobService.SIDE_EFFECT_NONE, fenceA)).isTrue();

        clock.advance(Duration.ofSeconds(31));
        jobs.reclaimExpired();
        jobs.reconcileNodesAfterRecovery(jobId);
        AiJobService.AiJob claimB = jobs.claim("worker-b", Duration.ofSeconds(30)).orElseThrow();
        long fenceB = claimB.fencingToken();
        assertThat(fenceB).isGreaterThan(fenceA);

        // 过期持有者写不进任何节点终态；新持有者可以。
        assertThat(jobs.completeNode(jobId, "step-1", "attempt-A-stale", fenceA)).isFalse();
        assertThat(jobs.failNode(jobId, "step-1", "stale", fenceA)).isFalse();
        assertThat(jobs.startNode(jobId, "step-2", "attempt-b", AiJobService.SIDE_EFFECT_NONE, fenceB)).isTrue();
        assertThat(jobs.completeNode(jobId, "step-2", "attempt-B", fenceB)).isTrue();
        assertThat(jobs.nodes(jobId)).extracting(node -> node.nodeKey() + ":" + node.status())
            .contains("step-2:SUCCEEDED");
        // step-1 被恢复对账回 PENDING（可重放），绝非过期持有者伪写的终态。
        assertThat(jobs.nodes(jobId).get(0).status()).isEqualTo(AiJobService.NODE_PENDING);
    }

    @Test
    void cancelIntentSurvivesLeaseRecovery() {
        // C-4 锁测：运行中取消后持有者崩溃（租约过期），恢复必须尊重取消意图，绝不复活。
        String jobId = submitOne();
        jobs.claim("worker-a", Duration.ofSeconds(30)).orElseThrow();
        assertThat(jobs.requestCancel(jobId, "actor-1")).isEqualTo(AiJobService.CancelOutcome.CANCELLED);
        clock.advance(Duration.ofSeconds(31));

        assertThat(jobs.reclaimExpired()).containsExactly(jobId);
        assertThat(jobs.find(jobId).orElseThrow().status()).isEqualTo(AiJobService.STATUS_CANCELLED);
        assertThat(jobs.claim("worker-b", Duration.ofSeconds(30))).isEmpty();
    }

    @Test
    void claimRejectsOutOfBoundsLeases() {
        submitOne();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jobs.claim("w", Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jobs.claim("w", Duration.ofSeconds(-1)))
            .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jobs.claim("w", Duration.ofSeconds(3601)))
            .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jobs.claim(" ", Duration.ofSeconds(10)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lateCancelAfterFinishPreservesTerminalState() {
        String jobId = submitOne();
        AiJobService.AiJob claim = jobs.claim("worker-a", Duration.ofSeconds(30)).orElseThrow();
        assertThat(jobs.complete(jobId, claim.fencingToken(), "ok")).isTrue();

        assertThat(jobs.requestCancel(jobId, "actor-1")).isEqualTo(AiJobService.CancelOutcome.ALREADY_FINISHED);
        assertThat(jobs.find(jobId).orElseThrow().status()).isEqualTo(AiJobService.STATUS_SUCCEEDED);
        assertThat(jobs.requestCancel("job-missing", "actor-1")).isEqualTo(AiJobService.CancelOutcome.NOT_FOUND);
    }

    @Test
    void queuedCancelIsImmediateAndRunningCancelIsObservedByTheHolder() {
        String queued = submitOne();
        assertThat(jobs.requestCancel(queued, "actor-1")).isEqualTo(AiJobService.CancelOutcome.CANCELLED);
        assertThat(jobs.find(queued).orElseThrow().status()).isEqualTo(AiJobService.STATUS_CANCELLED);

        String running = submitOne();
        AiJobService.AiJob claim = jobs.claim("worker-a", Duration.ofSeconds(30)).orElseThrow();
        assertThat(jobs.requestCancel(running, "actor-1")).isEqualTo(AiJobService.CancelOutcome.CANCELLED);
        assertThat(jobs.isCancelRequested(running)).isTrue();
        // 持有者按围栏写回取消；错误围栏被拒。
        assertThat(jobs.cancel(running, claim.fencingToken() + 7)).isFalse();
        assertThat(jobs.cancel(running, claim.fencingToken())).isTrue();
        assertThat(jobs.find(running).orElseThrow().status()).isEqualTo(AiJobService.STATUS_CANCELLED);
    }

    @Test
    void recoverySkipsCompletedNodesAndBlocksUnknownSideEffects() {
        // job-1：节点 1 已完成、节点 2 运行中且副作用未知 ⇒ 节点 2 BLOCKED，job BLOCKED。
        String blocked = submitOne();
        long fenceBlocked = jobs.claim("worker-a", Duration.ofSeconds(30)).orElseThrow().fencingToken();
        jobs.startNode(blocked, "step-1", "attempt-1", AiJobService.SIDE_EFFECT_NONE, fenceBlocked);
        jobs.completeNode(blocked, "step-1", "out-1", fenceBlocked);
        jobs.startNode(blocked, "step-2", "attempt-2", AiJobService.SIDE_EFFECT_UNKNOWN, fenceBlocked);
        clock.advance(Duration.ofSeconds(31));
        assertThat(jobs.reclaimExpired()).containsExactly(blocked);
        assertThat(jobs.reconcileNodesAfterRecovery(blocked)).containsExactly("step-2:BLOCKED");

        var nodes = jobs.nodes(blocked);
        assertThat(nodes).extracting(AiJobService.AiJobNode::nodeKey, AiJobService.AiJobNode::status)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("step-1", AiJobService.NODE_SUCCEEDED),
                org.assertj.core.groups.Tuple.tuple("step-2", AiJobService.NODE_BLOCKED));
        assertThat(jobs.find(blocked).orElseThrow().status()).isEqualTo(AiJobService.STATUS_BLOCKED);

        // job-2：运行中节点无副作用 ⇒ 回到 PENDING 可重放（已完成节点不动）。
        String retryable = submitOne();
        long fenceRetryable = jobs.claim("worker-b", Duration.ofSeconds(30)).orElseThrow().fencingToken();
        jobs.startNode(retryable, "step-1", "attempt-1", AiJobService.SIDE_EFFECT_NONE, fenceRetryable);
        clock.advance(Duration.ofSeconds(31));
        assertThat(jobs.reclaimExpired()).containsExactly(retryable);
        assertThat(jobs.reconcileNodesAfterRecovery(retryable)).containsExactly("step-1:PENDING");
        assertThat(jobs.nodes(retryable)).extracting(node -> node.nodeKey() + ":" + node.status())
            .containsExactly("step-1:PENDING", "step-2:PENDING");
        assertThat(jobs.find(retryable).orElseThrow().status()).isEqualTo(AiJobService.STATUS_QUEUED);
    }

    @Test
    void dualThreadClaimRaceHasExactlyOneWinner() throws Exception {
        String jobId = submitOne();
        var winners = new ConcurrentLinkedQueue<String>();
        int threads = 2;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            String owner = "racer-" + t;
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int attempt = 0; attempt < 5; attempt++) {
                    jobs.claim(owner, Duration.ofSeconds(30))
                        .ifPresent(claimed -> winners.add(claimed.jobId() + "@" + owner));
                }
            });
            thread.start();
        }
        ready.await();
        go.countDown();
        while (winners.isEmpty()) {
            Thread.sleep(20);
        }
        Thread.sleep(300);
        assertThat(winners).hasSize(1);
        assertThat(winners.peek()).startsWith(jobId + "@");
        assertThat(jobs.find(jobId).orElseThrow().status()).isEqualTo(AiJobService.STATUS_RUNNING);
    }

    // ------------------------------------------------------------------ helpers

    private String submitOne() {
        return jobs.submit("requirement_analysis", PROJECT_ID, "actor-1",
            "idem-" + java.util.UUID.randomUUID(), "hash-" + java.util.UUID.randomUUID(),
            List.of("step-1", "step-2"), "rev-1");
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration by) {
            instant = instant.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
