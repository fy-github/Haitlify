package com.haitlify.aitest.platform.ai.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.haitlify.aitest.platform.ai.event.AiRunEventService;
import com.haitlify.aitest.platform.ai.gateway.AiGateway;
import com.haitlify.aitest.platform.ai.gateway.AiGatewayConfig;
import com.haitlify.aitest.platform.ai.gateway.AiGenerationResult;

/**
 * T-Review-4 验收的持久调度契约集成测试，运行在真实 MySQL（见 application-mysql-it.yml，
 * docker compose 的 mysql 服务、专用 ai_scheduler_it 库）上，覆盖：
 * 双 worker 竞争、owner 崩溃后的租约接管、旧 fencing token 写入被拒、已完成分片不重复、
 * 恢复扫描与事件重放、停机停止领取、取消阻止新尝试、以及「领取期间无数据库长事务」。
 *
 * CI 安全（2026-10-09）：无 MySQL 的环境（如不带 DB service 的 CI）在**上下文加载之前**由
 * @EnabledIf 整类跳过（skip 而非 error）——assumption 若写在测试方法内，类级上下文会先因
 * 数据源不可达而失败（同类教训见 e166c40 对五个 live 门类的修复）。本地/门禁环境有 MySQL 时照常真跑。
 */
@SpringBootTest
@ActiveProfiles("mysql-it")
@org.junit.jupiter.api.condition.EnabledIf("mysqlItAvailable")
class AiJobSchedulerIntegrationTest {

    /** 条件在 Spring 上下文加载前求值；仅做 TCP 可达性判断（不建立 JDBC 连接）。 */
    static boolean mysqlItAvailable() {
        String url = System.getenv().getOrDefault("AI_SCHED_IT_DB_URL", "jdbc:mysql://127.0.0.1:3306/ai_scheduler_it");
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
            socket.connect(new java.net.InetSocketAddress(host, port), 1000);
            return true;
        } catch (Exception unavailable) {
            return false;
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AiAnalysisWorker worker;

    @Autowired
    private AiAnalysisRunDispatcher dispatcher;

    @Autowired
    private AiRunEventService eventService;

    @Autowired
    private AiSchedulerPolicy policy;

    @Autowired
    private Clock clock;

    @MockitoBean
    private AiGateway aiGateway;

    private final List<String> seededRunIds = new ArrayList<>();
    private final List<String> seededProjectCodes = new ArrayList<>();

    @BeforeEach
    void cleanSlate() {
        jdbcTemplate.update("delete from ai_analysis_worker_leases");
        jdbcTemplate.update("delete from ai_run_events");
        jdbcTemplate.update("delete from ai_run_policies");
        jdbcTemplate.update("delete from ai_unclear_questions");
        jdbcTemplate.update("delete from ai_coverage_summaries");
        jdbcTemplate.update("delete from ai_review_actions");
        jdbcTemplate.update("delete from ai_draft_test_points");
        jdbcTemplate.update("delete from ai_call_records");
        jdbcTemplate.update("delete from ai_analysis_chunks");
        jdbcTemplate.update("delete from ai_analysis_runs");
        jdbcTemplate.update("delete from projects where code like 'AI_SCHED_%'");
        jdbcTemplate.update("delete from ai_system_configs");
        Instant now = Instant.now();
        jdbcTemplate.update("""
            insert into ai_system_configs(
              config_key, enabled, provider_type, base_url, text_model, vision_model, embedding_model,
              api_key_ciphertext, credential_configured, request_timeout_ms, max_input_length, max_output_length,
              retry_count, request_logging_enabled, external_document_send_allowed, provider_config_json,
              masked_config_json, actor_id, actor_name, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "default", true, "openai-compatible", "https://api.example.com/v1", "gpt-test", "", "",
            "sk-test", true, 30000, 0, 0, 0, false, true, null,
            "****test", "system-dev", "Development User", Timestamp.from(now), Timestamp.from(now));
    }

    @AfterEach
    void cleanUp() {
        // 专用集成库（ai_scheduler_it），整表重置与 cleanSlate 同口径，避免对表列做假设。
        jdbcTemplate.update("delete from ai_analysis_worker_leases");
        jdbcTemplate.update("delete from ai_run_events");
        jdbcTemplate.update("delete from ai_run_policies");
        jdbcTemplate.update("delete from ai_unclear_questions");
        jdbcTemplate.update("delete from ai_coverage_summaries");
        jdbcTemplate.update("delete from ai_review_actions");
        jdbcTemplate.update("delete from ai_draft_test_points");
        jdbcTemplate.update("delete from ai_call_records");
        jdbcTemplate.update("delete from ai_analysis_chunks");
        jdbcTemplate.update("delete from ai_analysis_runs");
        jdbcTemplate.update("delete from projects where code like 'AI_SCHED_%'");
        jdbcTemplate.update("delete from ai_system_configs");
        seededRunIds.clear();
        seededProjectCodes.clear();
    }

    // ------------------------------------------------------------------ 双 worker 竞争

    @Test
    void contentionGivesEveryRunExactlyOneOwner() throws Exception {
        List<String> runIds = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            long projectId = insertProject("AI_SCHED_CONTEND_" + index);
            runIds.add(insertQueuedRun("it-contend-" + index, projectId));
        }
        AiAnalysisJobLeaseService serviceA = newLeaseService("it-worker-a");
        AiAnalysisJobLeaseService serviceB = newLeaseService("it-worker-b");

        Map<String, String> owners = new ConcurrentHashMap<>();
        List<String> doubleClaims = new CopyOnWriteArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        Thread threadA = claimLoopThread(serviceA, owners, doubleClaims, start, done);
        Thread threadB = claimLoopThread(serviceB, owners, doubleClaims, start, done);
        threadA.start();
        threadB.start();
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "两个 claim 循环应在 60s 内结束");

        assertThat(doubleClaims).as("同一个 run 不得被两个 owner 同时领取").isEmpty();
        assertThat(owners.keySet()).as("所有候选 run 都应被领取").containsExactlyInAnyOrderElementsOf(runIds);
        Integer leaseRows = jdbcTemplate.queryForObject(
            "select count(*) from ai_analysis_worker_leases", Integer.class);
        assertEquals(6, leaseRows, "每个被领取的 run 恰有一条租约行");
        List<String> distinctOwners = jdbcTemplate.queryForList(
            "select distinct owner_id from ai_analysis_worker_leases", String.class);
        assertThat(distinctOwners).allMatch(owner -> owner.equals("it-worker-a") || owner.equals("it-worker-b"));
    }

    private Thread claimLoopThread(
        AiAnalysisJobLeaseService service,
        Map<String, String> owners,
        List<String> doubleClaims,
        CountDownLatch start,
        CountDownLatch done
    ) {
        return new Thread(() -> {
            try {
                start.await(10, TimeUnit.SECONDS);
                for (int i = 0; i < 30; i++) {
                    AiAnalysisJobLeaseService.ClaimResult result = service.claimNextJob();
                    if (result.outcome() != AiAnalysisJobLeaseService.ClaimOutcome.CLAIMED) {
                        if (result.outcome() == AiAnalysisJobLeaseService.ClaimOutcome.IDLE) break;
                        continue;
                    }
                    String previous = owners.putIfAbsent(result.lease().runId(), result.lease().ownerId());
                    if (previous != null) {
                        doubleClaims.add(result.lease().runId());
                    }
                }
            } catch (Exception ignored) {
            } finally {
                done.countDown();
            }
        });
    }

    // ------------------------------------------------------------------ 恢复扫描与重放

    @Test
    void recoveryRequeuesOnlyExpiredLeasesAndEmitsReplayableEvents() {
        long projectId = insertProject("AI_SCHED_RECOVER");
        String staleRun = insertQueuedRun("it-recover-stale", projectId);
        markRunRunning(staleRun, "c1");
        jdbcTemplate.update(
            "update ai_analysis_runs set current_chunk_id = 'c1' where run_id = ?", staleRun);
        insertLease(staleRun, "dead-worker", Instant.now().minusSeconds(5), 7L);
        String healthyRun = insertQueuedRun("it-recover-healthy", projectId);
        markRunRunning(healthyRun, null);
        insertLease(healthyRun, "alive-worker", Instant.now().plusSeconds(120), 3L);

        AiAnalysisJobLeaseService service = newLeaseService("it-worker-recover");
        List<String> recovered = service.recoverExpiredRuns();

        assertThat(recovered).containsExactly(staleRun);
        Map<String, Object> stale = jdbcTemplate.queryForMap(
            "select status, worker_status, resume_from_chunk_id from ai_analysis_runs where run_id = ?", staleRun);
        assertEquals("queued", stale.get("STATUS"));
        assertEquals("idle", stale.get("WORKER_STATUS"));
        assertEquals("c1", stale.get("RESUME_FROM_CHUNK_ID"), "恢复必须保留续跑位置");
        Integer recoveredEvents = jdbcTemplate.queryForObject(
            "select count(*) from ai_run_events where run_id = ? and stage = 'lease_recovered'", Integer.class, staleRun);
        assertEquals(1, recoveredEvents, "恢复必须留下可重放事件");
        Map<String, Object> healthy = jdbcTemplate.queryForMap(
            "select status, worker_status from ai_analysis_runs where run_id = ?", healthyRun);
        assertEquals("running", healthy.get("STATUS"), "持有有效租约的运行不得被恢复");
        assertEquals("running", healthy.get("WORKER_STATUS"));
        List<Long> eventIds = jdbcTemplate.queryForList(
            "select id from ai_run_events where run_id = ? order by id asc", Long.class, staleRun);
        assertThat(eventIds).isSorted();
        assertThat(eventService.listByRun(staleRun)).isNotNull();
    }

    // ------------------------------------------------------------------ 崩溃接管 + 围栏拒绝 + 不重复

    @Test
    void crashTakeoverRejectsStaleOwnerWritesAndNeverReprocessesCompletedChunks() throws Exception {
        long projectId = insertProject("AI_SCHED_TAKEOVER");
        String runId = insertQueuedRun("it-takeover", projectId);
        insertChunk(runId, "c1", "登录", "用户输入正确账号密码后进入工作台", "planned");
        insertChunk(runId, "c2", "下单", "用户选择商品后提交订单", "planned");

        List<String> modelCalls = new CopyOnWriteArrayList<>();
        AtomicInteger callIndex = new AtomicInteger();
        CountDownLatch secondCallEntered = new CountDownLatch(1);
        CountDownLatch secondCallRelease = new CountDownLatch(1);
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any())).thenAnswer(invocation -> {
            int index = callIndex.incrementAndGet();
            modelCalls.add("call-" + index);
            if (index == 1) {
                return new AiGenerationResult(true, draftPointsJson("c1", "登录成功"), "req-c1", 20, 10, null);
            }
            if (index == 2) {
                secondCallEntered.countDown();
                secondCallRelease.await(30, TimeUnit.SECONDS);
                return new AiGenerationResult(true, draftPointsJson("c2", "下单成功A"), "req-c2-a", 20, 10, null);
            }
            return new AiGenerationResult(true, draftPointsJson("c2", "下单成功B"), "req-c2-b", 20, 10, null);
        });

        Thread ownerA = new Thread(() -> worker.processRun(runId));
        ownerA.start();
        assertTrue(secondCallEntered.await(60, TimeUnit.SECONDS), "owner A 应已进入第二个分片的模型调用");
        Long ownerAToken = jdbcTemplate.queryForObject(
            "select fencing_token from ai_analysis_worker_leases where run_id = ?", Long.class, runId);
        assertNotNull(ownerAToken);
        assertEquals(1, jdbcTemplate.queryForObject(
            "select count(*) from ai_draft_test_points where run_id = (select id from ai_analysis_runs where run_id = ?)",
            Integer.class, runId), "崩溃前 c1 的合并结果应已落库");

        // 模拟 owner A 心跳死亡：强制过期租约 → 恢复扫描 → 新 owner B 接管（fencing+1）。
        jdbcTemplate.update(
            "update ai_analysis_worker_leases set lease_until = ? where run_id = ?",
            Timestamp.from(Instant.now().minusSeconds(5)), runId);
        AiAnalysisJobLeaseService serviceB = newLeaseService("it-worker-b");
        assertThat(serviceB.recoverExpiredRuns()).containsExactly(runId);
        AiAnalysisJobLeaseService.Lease leaseB = serviceB.claim(runId);
        assertNotNull(leaseB, "过期租约必须可被新 owner 接管");
        assertEquals(ownerAToken + 1, leaseB.fencingToken(), "接管必须递增 fencing token");

        secondCallRelease.countDown();
        ownerA.join(60_000);
        assertFalse(ownerA.isAlive(), "owner A 应在围栏拒绝后退出");

        // 旧 owner 的写入必须被拒绝：运行状态不得被 A 标成 failed（此时已被恢复为 queued 等待续跑），
        // 也不得出现 A 的失败事件。
        assertEquals("queued", jdbcTemplate.queryForObject(
            "select status from ai_analysis_runs where run_id = ?", String.class, runId),
            "旧 fencing token 的失败写入必须被拒绝");
        assertEquals("idle", jdbcTemplate.queryForObject(
            "select worker_status from ai_analysis_runs where run_id = ?", String.class, runId));
        assertEquals(0, jdbcTemplate.queryForObject(
            "select count(*) from ai_run_events where run_id = ? and stage = 'run_failed'", Integer.class, runId),
            "被围栏拒绝的 owner 不得留下失败事件");
        assertEquals(1, jdbcTemplate.queryForObject(
            "select count(*) from ai_draft_test_points where run_id = (select id from ai_analysis_runs where run_id = ?)",
            Integer.class, runId), "c2 的结果不得被旧 owner 合并");

        // 由 B 释放后，恢复后的运行继续处理剩余分片；c1 不得被再次调用模型。
        serviceB.release(leaseB);
        AiAnalysisWorkerResult finalResult = worker.processNextQueuedRun();
        assertEquals("succeeded", finalResult.status());
        assertEquals(2, jdbcTemplate.queryForObject(
            "select count(*) from ai_draft_test_points where run_id = (select id from ai_analysis_runs where run_id = ?)",
            Integer.class, runId), "c1 与 c2 的合并结果都在");
        assertEquals(3, modelCalls.size(), "c2 被重试一次（A 的调用被围栏作废 + B 完成），c1 只调用一次");
        assertEquals("succeeded", jdbcTemplate.queryForObject(
            "select status from ai_analysis_runs where run_id = ?", String.class, runId));
    }

    // ------------------------------------------------------------------ 已完成分片不重复

    @Test
    void processableChunksSkipSucceededAndKeepFailedForRetry() {
        long projectId = insertProject("AI_SCHED_CHUNKS");
        String runId = insertQueuedRun("it-chunks", projectId);
        insertChunk(runId, "c1", "登录", "内容一", "succeeded");
        insertChunk(runId, "c2", "下单", "内容二", "planned");
        insertChunk(runId, "c3", "支付", "内容三", "failed");
        Long runPk = jdbcTemplate.queryForObject("select id from ai_analysis_runs where run_id = ?", Long.class, runId);

        AiAnalysisJobLeaseService service = newLeaseService("it-worker-chunks");
        List<Map<String, Object>> processable = service.processableChunks(runPk, null);
        List<String> plannedIds = processable.stream().map(row -> (String) row.get("PLANNED_CHUNK_ID")).toList();
        assertThat(plannedIds).containsExactly("c2", "c3");
    }

    // ------------------------------------------------------------------ 停机停止领取 + 取消阻止新尝试

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void stoppingWorkerStopsClaimingAndCancelledRunsAreNeverClaimed() {
        long projectId = insertProject("AI_SCHED_STOP");
        String queuedRun = insertQueuedRun("it-stop-queued", projectId);
        AiAnalysisJobLeaseService serviceA = newLeaseService("it-worker-stop-a");
        assertEquals(AiAnalysisJobLeaseService.ClaimOutcome.STOPPING, stoppingOutcomeAfterStop(serviceA));
        assertEquals(0, jdbcTemplate.queryForObject(
            "select count(*) from ai_analysis_worker_leases", Integer.class), "停机后不得留下新租约");

        // 取消的运行不得被领取；恢复扫描也不得把它重新排队。
        jdbcTemplate.update("update ai_analysis_runs set status = 'cancelled' where run_id = ?", queuedRun);
        AiAnalysisJobLeaseService serviceB = newLeaseService("it-worker-stop-b");
        assertEquals(AiAnalysisJobLeaseService.ClaimOutcome.IDLE, serviceB.claimNextJob().outcome());
        assertThat(serviceB.recoverExpiredRuns()).doesNotContain(queuedRun);
        assertEquals("cancelled", jdbcTemplate.queryForObject(
            "select status from ai_analysis_runs where run_id = ?", String.class, queuedRun));
    }

    private AiAnalysisJobLeaseService.ClaimOutcome stoppingOutcomeAfterStop(AiAnalysisJobLeaseService service) {
        service.stopClaiming();
        assertTrue(service.isStopping());
        return service.claimNextJob().outcome();
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void dispatcherGracefulShutdownStopsTheSharedLeaseService() {
        dispatcher.gracefulShutdown();
        assertEquals(AiAnalysisJobLeaseService.ClaimOutcome.STOPPING, worker.leaseService().claimNextJob().outcome());
    }

    // ------------------------------------------------------------------ 项目级并发门

    @Test
    void perProjectGateSerializesRunsOfTheSameProject() {
        long busyProject = insertProject("AI_SCHED_GATE_BUSY");
        String runningRun = insertQueuedRun("it-gate-running", busyProject);
        markRunRunning(runningRun, null);
        insertLease(runningRun, "busy-worker", Instant.now().plusSeconds(300), 2L);
        String queuedSameProject = insertQueuedRun("it-gate-queued-same", busyProject);
        long otherProject = insertProject("AI_SCHED_GATE_OTHER");
        String queuedOtherProject = insertQueuedRun("it-gate-queued-other", otherProject);

        AiAnalysisJobLeaseService service = newLeaseService("it-worker-gate");
        AiAnalysisJobLeaseService.ClaimResult result = service.claimNextJob();
        assertEquals(AiAnalysisJobLeaseService.ClaimOutcome.CLAIMED, result.outcome());
        assertEquals(queuedOtherProject, result.lease().runId(), "同项目有运行中的任务时不得领取该项目的新任务");
        assertThat(jdbcTemplate.queryForList(
            "select owner_id from ai_analysis_worker_leases where run_id = ?", String.class, queuedSameProject)).isEmpty();
    }

    // ------------------------------------------------------------------ 第三十轮（C1/C2/C6）修复的锁测

    @Test
    void cancelledRunIsRefusedByEveryClaimingPath() {
        long projectId = insertProject("AI_SCHED_CANCEL");
        String runId = insertQueuedRun("it-cancel-path", projectId);
        jdbcTemplate.update("update ai_analysis_runs set status = 'cancelled' where run_id = ?", runId);

        AiAnalysisJobLeaseService service = newLeaseService("it-worker-cancel");
        // 生产派发路径（dispatcher → worker.processRun(runId)）与操作员 resume 路径都必须拒绝；
        AiAnalysisWorkerResult dispatched = worker.processRun(runId);
        assertEquals("cancelled", dispatched.status());
        AiAnalysisWorkerResult resumed = worker.resumeRun(runId);
        assertEquals("cancelled", resumed.status());
        // 轮询领取路径同样不可见；
        assertEquals(AiAnalysisJobLeaseService.ClaimOutcome.IDLE, service.claimNextJob().outcome());
        // 任何路径都不得留下租约，也不得改写状态（不允许「复活」）。
        assertEquals(0, jdbcTemplate.queryForObject(
            "select count(*) from ai_analysis_worker_leases", Integer.class));
        Map<String, Object> cancelRow = jdbcTemplate.queryForMap(
            "select status, started_at from ai_analysis_runs where run_id = ?", runId);
        assertEquals("cancelled", cancelRow.get("STATUS"));
        assertNull(cancelRow.get("STARTED_AT"), "取消的 run 不得被启动（started_at 必须为 NULL）");
        // 事件级断言（第三十轮补审 2 C6 残余）：取消的 run 不得出现启动/成功事件。
        assertEquals(0, jdbcTemplate.queryForObject(
            "select count(*) from ai_run_events where run_id = ? and stage = 'run_started'", Integer.class, runId));
        assertEquals(0, jdbcTemplate.queryForObject(
            "select count(*) from ai_run_events where run_id = ? and stage = 'run_succeeded'", Integer.class, runId));
    }

    @Test
    void stoppingRefusesOperatorResumeAndDirectClaim() {
        long projectId = insertProject("AI_SCHED_STOP_RESUME");
        String runId = insertQueuedRun("it-stop-resume", projectId);
        AiAnalysisJobLeaseService stopping = newLeaseService("it-worker-stopping");
        stopping.stopClaiming();
        assertNull(stopping.claim(runId), "停机后 direct claim 必须拒绝");
        assertEquals(AiAnalysisJobLeaseService.ClaimOutcome.STOPPING, stopping.claimNextJob().outcome());
        assertEquals(0, jdbcTemplate.queryForObject(
            "select count(*) from ai_analysis_worker_leases", Integer.class));
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void stoppingRefusesTheSharedResumePath() {
        long projectId = insertProject("AI_SCHED_STOP_SHARED");
        String runId = insertQueuedRun("it-stop-shared-resume", projectId);
        dispatcher.gracefulShutdown();
        AiAnalysisWorkerResult resumed = worker.resumeRun(runId);
        assertEquals("stopping", resumed.status(), "停机后 /resume 路径必须拒绝");
        assertEquals(0, jdbcTemplate.queryForObject(
            "select count(*) from ai_analysis_worker_leases", Integer.class));
    }

    @Test
    void concurrentSameProjectClaimsGrantAtMostOneLease() throws Exception {
        long projectId = insertProject("AI_SCHED_CONCURRENT_GATE");
        String first = insertQueuedRun("it-concurrent-first", projectId);
        String second = insertQueuedRun("it-concurrent-second", projectId);
        AiAnalysisJobLeaseService serviceA = newLeaseService("it-worker-cg-a");
        AiAnalysisJobLeaseService serviceB = newLeaseService("it-worker-cg-b");
        CountDownLatch start = new CountDownLatch(1);
        List<AiAnalysisJobLeaseService.Lease> granted = new CopyOnWriteArrayList<>();
        Thread threadA = new Thread(() -> {
            try {
                start.await(10, TimeUnit.SECONDS);
                AiAnalysisJobLeaseService.Lease lease = serviceA.claim(first);
                if (lease != null) granted.add(lease);
            } catch (Exception ignored) {
            }
        });
        Thread threadB = new Thread(() -> {
            try {
                start.await(10, TimeUnit.SECONDS);
                AiAnalysisJobLeaseService.Lease lease = serviceB.claim(second);
                if (lease != null) granted.add(lease);
            } catch (Exception ignored) {
            }
        });
        threadA.start();
        threadB.start();
        start.countDown();
        threadA.join(30_000);
        threadB.join(30_000);
        // 第三十轮补审 C1：门判定与 CAS 在项目行锁内原子——并发同项目至多发放一条租约。
        assertThat(granted).as("同项目并发领取至多一个胜者").hasSizeLessThanOrEqualTo(1);
        assertEquals(1, jdbcTemplate.queryForObject(
            "select count(*) from ai_analysis_worker_leases", Integer.class), "项目内最多一条租约");
    }

    @Test
    void failedRunIsRefusedForAutomaticClaimButAllowedForManualResume() {
        long projectId = insertProject("AI_SCHED_FAILED_GUARD");
        String runId = insertQueuedRun("it-failed-guard", projectId);
        jdbcTemplate.update("update ai_analysis_runs set status = 'failed' where run_id = ?", runId);
        AiAnalysisJobLeaseService service = newLeaseService("it-worker-failed-guard");
        // 第三十轮补审 7 C-1：自动路径（默认 claim）拒绝 failed；人工 /resume 语义（allowFailed）放行。
        assertNull(service.claim(runId), "自动路径不得领取 failed run");
        AiAnalysisJobLeaseService.Lease manual = service.claim(runId, true);
        assertNotNull(manual, "人工重跑通道必须可用");
        service.release(manual);
    }

    @Test
    void recoveredRunsStrandedByTheProjectGateAreRedispatchedLater() {
        long projectId = insertProject("AI_SCHED_STRANDED");
        String blocker = insertQueuedRun("it-stranded-blocker", projectId);
        AiAnalysisJobLeaseService blockerService = newLeaseService("it-worker-stranded-blocker");
        assertNotNull(blockerService.claim(blocker), "先占住项目门");
        // 之后才让 crash 的 run 呈现「running 且无租约」的崩溃态（若在其前，running 态会先占住门）。
        String crashed = insertQueuedRun("it-stranded-crashed", projectId);
        jdbcTemplate.update(
            "update ai_analysis_runs set status = 'running', worker_status = 'running' where run_id = ?", crashed);
        // 第一次恢复：crash 的 run 被重排（派发会被项目门拒绝——模拟 30c E1 的 stranded 场景）。
        assertThat(newLeaseService("it-worker-stranded-a").recoverExpiredRuns()).contains(crashed);
        assertEquals("queued", jdbcTemplate.queryForObject(
            "select status from ai_analysis_runs where run_id = ?", String.class, crashed));
        // 门未释放时再次恢复：孤儿仍在（说明无人领取时会持续重派），但不重复发事件。
        assertThat(newLeaseService("it-worker-stranded-b").recoverExpiredRuns()).contains(crashed);
        assertEquals(1, jdbcTemplate.queryForObject(
            "select count(*) from ai_run_events where run_id = ? and stage = 'lease_recovered'", Integer.class, crashed));
        // 释放门（直接删拦截者租约即可；claim 会自增围栏，不经 release 路径）。
        jdbcTemplate.update("delete from ai_analysis_worker_leases where run_id = ?", blocker);
        List<String> redispatched = newLeaseService("it-worker-stranded-c").recoverExpiredRuns();
        assertThat(redispatched).contains(crashed);
        // 第三十轮补审 4 C-1：残留「过期租约行」的常见崩溃态同样必须持续重派（谓词按「无新鲜租约」判定）。
        String crashedWithStaleLease = insertQueuedRun("it-stranded-stale", projectId);
        jdbcTemplate.update(
            "update ai_analysis_runs set status = 'running', worker_status = 'running' where run_id = ?", crashedWithStaleLease);
        jdbcTemplate.update("""
            insert into ai_analysis_worker_leases(run_id, owner_id, lease_until, fencing_token, updated_at)
            values (?, 'it-dead-worker', ?, 4, ?)
            """, crashedWithStaleLease, Timestamp.from(Instant.now().minusSeconds(3600)), Timestamp.from(Instant.now().minusSeconds(3600)));
        assertThat(newLeaseService("it-worker-stranded-d").recoverExpiredRuns()).contains(crashedWithStaleLease);
        assertEquals("queued", jdbcTemplate.queryForObject(
            "select status from ai_analysis_runs where run_id = ?", String.class, crashedWithStaleLease));
        assertThat(newLeaseService("it-worker-stranded-e").recoverExpiredRuns()).contains(crashedWithStaleLease);
        // 第三十轮补审 5 C-1：无事件、但首派发被门静默拒绝且超过一个恢复周期的 run 也要回到派发清单。
        String refusedAtCreation = insertQueuedRun("it-stranded-refused", projectId);
        jdbcTemplate.update("update ai_analysis_runs set created_at = ? where run_id = ?",
            Timestamp.from(Instant.now().minusSeconds(3600)), refusedAtCreation);
        assertThat(newLeaseService("it-worker-stranded-f").recoverExpiredRuns()).contains(refusedAtCreation);
    }

    @Test
    void projectGateHoldsBetweenClaimAndMarkRunning() {
        long projectId = insertProject("AI_SCHED_WINDOW");
        String first = insertQueuedRun("it-window-first", projectId);
        String second = insertQueuedRun("it-window-second", projectId);
        AiAnalysisJobLeaseService service = newLeaseService("it-worker-window");
        assertNotNull(service.claim(first), "第一个任务可领取");
        // 此刻第一个任务仍是 queued/idle（尚未 markRunRunning）——仅凭行状态会被绕过；
        // 修复后以「新鲜租约视为占用」判定，第二个同项目任务必须被拒（第三十轮 C2 窗口）。
        assertNull(service.claim(second), "已领取未标记 running 的窗口内，同项目第二个任务不得领取");
        // 候选存在但被门拒绝 ⇒ RUN_BUSY（而非 IDLE）。
        assertEquals(AiAnalysisJobLeaseService.ClaimOutcome.RUN_BUSY, service.claimNextJob().outcome());
        assertEquals(1, jdbcTemplate.queryForObject(
            "select count(*) from ai_analysis_worker_leases", Integer.class), "窗口内不得产生第二条同项目租约");
    }

    // ------------------------------------------------------------------ 无数据库长事务

    @Test
    void holdingAClaimNeverKeepsALongRunningDatabaseTransaction() throws Exception {
        long projectId = insertProject("AI_SCHED_NOTX");
        String runId = insertQueuedRun("it-notx", projectId);
        AiAnalysisJobLeaseService service = newLeaseService("it-worker-notx");
        assertNotNull(service.claim(runId));

        Thread.sleep(2500);
        Integer longQueries = jdbcTemplate.queryForObject(
            "select count(*) from information_schema.processlist where db = database() and command = 'Query' and time > 2",
            Integer.class);
        assertEquals(0, longQueries, "持有租约等待上游期间不得存在数据库长事务");
        assertEquals(1, jdbcTemplate.queryForObject(
            "select count(*) from ai_analysis_worker_leases where run_id = ?", Integer.class, runId));
    }

    // ------------------------------------------------------------------ 帮助方法

    private AiAnalysisJobLeaseService newLeaseService(String workerId) {
        return new AiAnalysisJobLeaseService(jdbcTemplate, eventService, policy, clock, workerId);
    }

    private long insertProject(String code) {
        Instant now = Instant.now();
        jdbcTemplate.update(
            "insert into projects(code, name, description, active, created_at, updated_at) values (?, ?, '', true, ?, ?)",
            code, "调度契约测试项目 " + code, Timestamp.from(now), Timestamp.from(now));
        seededProjectCodes.add(code);
        return jdbcTemplate.queryForObject("select id from projects where code = ?", Long.class, code);
    }

    private String insertQueuedRun(String runId, long projectId) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
            insert into ai_analysis_runs(
              run_id, project_id, target_case_kind, template_id, analysis_strategy, execution_mode,
              status, worker_status, coverage_status, total_chunks, completed_chunks, current_chunk_id,
              resume_from_chunk_id, error_summary, config_snapshot_json, rule_snapshot_json,
              skill_snapshot_json, schema_snapshot_json, input_summary_json, output_summary_json,
              actor_id, actor_name, started_at, finished_at, created_at, updated_at
            ) values (?, ?, 'interface', '', 'balanced', 'multi_pass',
              'queued', 'idle', 'planned', 0, 0, '', '', '', '{}', '{}', '{}', '{}', '{"documentIds":[]}', null,
              'system-dev', 'Development User', null, null, ?, ?)
            """, runId, projectId, Timestamp.from(now), Timestamp.from(now));
        seededRunIds.add(runId);
        return runId;
    }

    private void markRunRunning(String runId, String chunkId) {
        jdbcTemplate.update("""
            update ai_analysis_runs set status = 'running', worker_status = 'running',
              current_chunk_id = ?, started_at = ?, updated_at = ? where run_id = ?
            """,
            chunkId == null ? "" : chunkId, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), runId);
    }

    private void insertChunk(String runId, String plannedChunkId, String section, String content, String status) {
        Long runPk = jdbcTemplate.queryForObject("select id from ai_analysis_runs where run_id = ?", Long.class, runId);
        Instant now = Instant.now();
        jdbcTemplate.update("""
            insert into ai_analysis_chunks(
              planned_chunk_id, run_id, source_chunk_ids_json, section_heading, token_estimate,
              status, skip_reason, content_text, evidence_metadata_json, created_at, updated_at
            ) values (?, ?, '[]', ?, 20, ?, '', ?, '{}', ?, ?)
            """, plannedChunkId, runPk, section, status, content, Timestamp.from(now), Timestamp.from(now));
        jdbcTemplate.update(
            "update ai_analysis_runs set total_chunks = (select count(*) from ai_analysis_chunks where run_id = ?) where id = ?",
            runPk, runPk);
    }

    private void insertLease(String runId, String ownerId, Instant leaseUntil, long fencingToken) {
        jdbcTemplate.update(
            "insert into ai_analysis_worker_leases(run_id, owner_id, lease_until, fencing_token, updated_at) values (?, ?, ?, ?, ?)",
            runId, ownerId, Timestamp.from(leaseUntil), fencingToken, Timestamp.from(Instant.now()));
    }

    private Long runPkOrNull(String runId) {
        List<Long> ids = jdbcTemplate.queryForList(
            "select id from ai_analysis_runs where run_id = ?", Long.class, runId);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private String draftPointsJson(String sourceChunkId, String title) {
        return """
            {
              "draftTestPoints": [
                {
                  "title": "%s",
                  "module": "模块",
                  "expectedResult": "结果可见",
                  "sourceChunkId": "%s"
                }
              ]
            }
            """.formatted(title, sourceChunkId);
    }
}
