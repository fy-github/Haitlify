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
 * T06 MySQL 双 worker integration（独立 `ai_runtime_it` 库，不指向业务库）：
 * 真实 InnoDB 语义下的领取冲突、lease 接管、过期 fence 拒写、重复 submit 与
 * 取消晚响应，以及 MySQL 上事件序列的单调性（1093 修复的真实验证）。
 * 无 MySQL 环境时整类跳过（类级 @EnabledIf，上下文加载前求值）。
 */
@SpringBootTest(properties = {
    "spring.datasource.url=${AI_JOB_IT_DB_URL:jdbc:mysql://127.0.0.1:3306/ai_runtime_it"
        + "?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai}",
    "spring.datasource.username=${AI_JOB_IT_DB_USER:root}",
    "spring.datasource.password=${AI_JOB_IT_DB_PASSWORD:root}",
    "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver"
})
@ActiveProfiles("mysql-it")
@org.junit.jupiter.api.condition.EnabledIf("mysqlItAvailable")
class AiJobMysqlIntegrationTest {

    private static final long PROJECT_ID = 987005L;

    static boolean mysqlItAvailable() {
        String url = System.getenv().getOrDefault("AI_JOB_IT_DB_URL",
            "jdbc:mysql://127.0.0.1:3306/ai_runtime_it");
        String host = "127.0.0.1";
        int port = 3306;
        try {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("jdbc:mysql://([^/:]+):(\\d+)").matcher(url);
            if (matcher.find()) {
                host = matcher.group(1);
                port = Integer.parseInt(matcher.group(2));
            }
        } catch (RuntimeException ignored) {
            // 保持默认值
        }
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), 800);
            return true;
        } catch (Exception unavailable) {
            return false;
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private tools.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    /** 真实 Spring 代理 bean：@Transactional 语义必须经代理才生效（round-2 F1 教训）。 */
    @Autowired
    private AiJobService jobServiceBean;

    private MutableClock clock;

    private AiJobService jobs;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-09T05:00:00Z"));
        jobs = new AiJobService(jdbcTemplate, objectMapper, clock);
        jdbcTemplate.update("delete from ai_runtime_attempts");
        jdbcTemplate.update("delete from ai_runtime_job_nodes");
        jdbcTemplate.update("delete from ai_runtime_jobs");
        jdbcTemplate.update("delete from projects where id = ?", PROJECT_ID);
        jdbcTemplate.update(
            "insert into projects(id, code, name, description, active, created_at, updated_at)"
                + " values (?, ?, ?, '', true, ?, ?)",
            PROJECT_ID, "MYIT-" + PROJECT_ID, "mysql-it-test", new java.sql.Timestamp(System.currentTimeMillis()),
            new java.sql.Timestamp(System.currentTimeMillis()));
    }

    @Test
    void dualWorkersRaceForTheSameJobAndExactlyOneWins() throws Exception {
        String jobId = submitOne();
        var winners = new ConcurrentLinkedQueue<String>();
        int threads = 4;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            String owner = "mysql-racer-" + t;
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int attempt = 0; attempt < 5; attempt++) {
                    jobs.claim(owner, Duration.ofSeconds(60))
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
        Thread.sleep(400);
        assertThat(winners).hasSize(1);
        assertThat(winners.peek()).startsWith(jobId + "@");
    }

    @Test
    void leaseTakeoverBumpsFencingAndStaleWritesAreRejectedOnMysql() {
        String jobId = submitOne();
        AiJobService.AiJob claimA = jobs.claim("mysql-worker-a", Duration.ofSeconds(30)).orElseThrow();

        clock.advance(Duration.ofSeconds(31));
        assertThat(jobs.reclaimExpired()).containsExactly(jobId);
        assertThat(jobs.heartbeat(jobId, "mysql-worker-a", claimA.fencingToken(), Duration.ofSeconds(30))).isFalse();
        assertThat(jobs.complete(jobId, claimA.fencingToken(), "stale")).isFalse();

        AiJobService.AiJob claimB = jobs.claim("mysql-worker-b", Duration.ofSeconds(30)).orElseThrow();
        assertThat(claimB.fencingToken()).isGreaterThan(claimA.fencingToken());
        assertThat(jobs.complete(jobId, claimB.fencingToken(), "ok")).isTrue();
        assertThat(jobs.find(jobId).orElseThrow().status()).isEqualTo(AiJobService.STATUS_SUCCEEDED);
    }

    @Test
    void duplicateSubmitReturnsSameIdAndConflictsFailClosedOnMysql() {
        String first = submitOne();
        Optional<AiJobService.AiJob> job = jobs.find(first);
        assertThat(job).isPresent();
        String same = jobs.submit("requirement_analysis", PROJECT_ID, "actor-1", job.get().idempotencyKey(),
            job.get().inputHash(), List.of("step-1"), "rev-1");
        assertThat(same).isEqualTo(first);
        assertThatThrownBy(() -> jobs.submit("requirement_analysis", PROJECT_ID, "actor-1",
            job.get().idempotencyKey(), "different-hash", List.of("step-1"), "rev-1"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void lateCancelPreservesTerminalStateOnMysql() {
        String jobId = submitOne();
        AiJobService.AiJob claim = jobs.claim("mysql-worker-c", Duration.ofSeconds(30)).orElseThrow();
        assertThat(jobs.complete(jobId, claim.fencingToken(), "ok")).isTrue();
        assertThat(jobs.requestCancel(jobId, "actor-1")).isEqualTo(AiJobService.CancelOutcome.ALREADY_FINISHED);
        assertThat(jobs.find(jobId).orElseThrow().status()).isEqualTo(AiJobService.STATUS_SUCCEEDED);
    }

    @Test
    void eventSequenceIsMonotonicPerRunOnMysql() throws Exception {
        String runId = "mysql-run-events-" + java.util.UUID.randomUUID();
        var emitFailures = new ConcurrentLinkedQueue<Throwable>();
        int threads = 3;
        CountDownLatch go = new CountDownLatch(1);
        var service = new com.haitlify.aitest.platform.ai.event.AiRunEventService(jdbcTemplate);
        for (int t = 0; t < threads; t++) {
            int threadIndex = t;
            new Thread(() -> {
                try {
                    go.await();
                    for (int i = 0; i < 4; i++) {
                        service.emit(new com.haitlify.aitest.platform.ai.event.AiRunEvent(runId, Instant.now(),
                            "concurrent_" + threadIndex + "_" + i, "info", "并发事件", null, 0, null,
                            "", "", "", 0L, "", "", 0L));
                    }
                } catch (Throwable failure) {
                    emitFailures.add(failure);
                }
            }).start();
        }
        go.countDown();
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            Integer count = jdbcTemplate.queryForObject(
                "select count(*) from ai_run_events where run_id = ?", Integer.class, runId);
            if (count != null && count >= threads * 4) {
                break;
            }
            Thread.sleep(50);
        }
        assertThat(emitFailures).isEmpty();
        List<Long> sequences = jdbcTemplate.queryForList(
            "select event_seq from ai_run_events where run_id = ? order by event_seq asc", Long.class, runId);
        assertThat(sequences).hasSize(threads * 4);
        assertThat(sequences).isSorted();
        assertThat(sequences).doesNotHaveDuplicates();

        var tail = service.listByRun(runId, sequences.get(9));
        assertThat(tail.items()).hasSize(2);
        assertThat(tail.items().get(0).eventSeq()).isEqualTo(sequences.get(10));
    }

    @Test
    void crossInstanceEmitRetrySurvivesRepeatableReadOnMysql() throws Exception {
        // C-1 锁测：4 个独立服务实例（各带进程级监视器=独立进程模拟）并发 emit 同一 run；
        // 每次重试在全新事务里重取 seq，唯一索引收口 ⇒ 零失败、序列唯一且单调。
        String runId = "mysql-cross-" + java.util.UUID.randomUUID();
        var failures = new ConcurrentLinkedQueue<Throwable>();
        int threads = 4;
        int perThread = 3;
        CountDownLatch go = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            int threadIndex = t;
            new Thread(() -> {
                try {
                    var service = new com.haitlify.aitest.platform.ai.event.AiRunEventService(
                        jdbcTemplate, transactionManager);
                    go.await();
                    for (int i = 0; i < perThread; i++) {
                        service.emit(new com.haitlify.aitest.platform.ai.event.AiRunEvent(runId, Instant.now(),
                            "xproc_" + threadIndex + "_" + i, "info", "跨实例事件", null, 0, null,
                            "", "", "", 0L, "", "", 0L));
                    }
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            }).start();
        }
        go.countDown();
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            Integer count = jdbcTemplate.queryForObject(
                "select count(*) from ai_run_events where run_id = ?", Integer.class, runId);
            if (count != null && count >= threads * perThread) {
                break;
            }
            Thread.sleep(50);
        }
        assertThat(failures).isEmpty();
        List<Long> sequences = jdbcTemplate.queryForList(
            "select event_seq from ai_run_events where run_id = ? order by event_seq asc", Long.class, runId);
        assertThat(sequences).hasSize(threads * perThread);
        assertThat(sequences).doesNotHaveDuplicates();
        assertThat(sequences).isSorted();
    }

    @Test
    void concurrentSameIdempotencyKeySubmitIsIdempotentOnMysql() throws Exception {
        // C-2 锁测（round-2 修正：必须走真实 Spring 代理 bean——手工构造的实例没有 AOP，
        // @Transactional 失效，会让本测试假绿；异 hash 同 key 并发仍须 fail-closed）。
        for (int round = 0; round < 6; round++) {
            String key = "race-" + java.util.UUID.randomUUID();
            var failures = new ConcurrentLinkedQueue<Throwable>();
            var returned = new ConcurrentLinkedQueue<String>();
            int threads = 2;
            CountDownLatch go = new CountDownLatch(1);
            for (int t = 0; t < threads; t++) {
                new Thread(() -> {
                    try {
                        go.await();
                        returned.add(jobServiceBean.submit("requirement_analysis", PROJECT_ID, "actor-1", key,
                            "hash-race", List.of("step-1"), "rev-1"));
                    } catch (Throwable failure) {
                        failures.add(failure);
                    }
                }).start();
            }
            go.countDown();
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline && returned.size() + failures.size() < threads) {
                Thread.sleep(20);
            }
            assertThat(failures).describedAs("round " + round).isEmpty();
            assertThat(returned).hasSize(threads);
            assertThat(new java.util.HashSet<>(returned)).hasSize(1);
            Integer rows = jdbcTemplate.queryForObject(
                "select count(*) from ai_runtime_jobs where idempotency_key = ?", Integer.class, key);
            assertThat(rows).isEqualTo(1);
        }

        // 异 hash 同 key：幂等冲突必须可识别（IllegalState → API 409），不得 500。
        String conflictKey = "race-mismatch-" + java.util.UUID.randomUUID();
        jobServiceBean.submit("requirement_analysis", PROJECT_ID, "actor-1", conflictKey, "hash-a",
            List.of("step-1"), "rev-1");
        assertThatThrownBy(() -> jobServiceBean.submit("requirement_analysis", PROJECT_ID, "actor-1", conflictKey,
            "hash-b", List.of("step-1"), "rev-1")).isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------ helpers

    private String submitOne() {
        return jobs.submit("requirement_analysis", PROJECT_ID, "actor-1",
            "idem-" + java.util.UUID.randomUUID(), "hash-" + java.util.UUID.randomUUID(),
            List.of("step-1"), "rev-1");
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
