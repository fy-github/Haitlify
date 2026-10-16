package com.haitlify.aitest.platform.ai.analysis;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 调度运营指标（T-Review-4 契约）：queue wait、lease age、attempt duration、TTFB、
 * blocked count、recovery count。全部为只读聚合，计算口径与告警阈值见
 * docs/ai-test-platform/job-scheduler-contract.md；无数据时返回 null，绝不伪装成 0。
 */
@Service
class AiAnalysisSchedulerMetricsService {

    private static final int RECENT_LIMIT = 100;

    private final JdbcTemplate jdbcTemplate;
    private final AiSchedulerPolicy policy;
    private final Clock clock;

    AiAnalysisSchedulerMetricsService(JdbcTemplate jdbcTemplate, AiSchedulerPolicy policy, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.policy = policy;
        this.clock = clock;
    }

    Map<String, Object> metrics(Long projectId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("queue", queueMetrics(projectId));
        result.put("leases", leaseMetrics());
        result.put("attempts", attemptMetrics(projectId));
        result.put("variability", variabilityMetrics(projectId));
        Map<String, Object> policyBlock = new LinkedHashMap<>();
        policyBlock.put("leaseTtlSeconds", policy.leaseTtl().toSeconds());
        policyBlock.put("renewIntervalSeconds", policy.renewInterval().toSeconds());
        policyBlock.put("recoveryIntervalSeconds", policy.recoveryInterval().toSeconds());
        policyBlock.put("gracefulShutdownSeconds", policy.gracefulShutdownDeadline().toSeconds());
        policyBlock.put("maxActiveRunsPerProject", policy.maxActiveRunsPerProject());
        result.put("policy", policyBlock);
        return result;
    }

    private Map<String, Object> queueMetrics(Long projectId) {
        List<Map<String, Object>> rows = projectId == null
            ? jdbcTemplate.queryForList(
                "select created_at, started_at from ai_analysis_runs where started_at is not null order by id desc limit " + RECENT_LIMIT)
            : jdbcTemplate.queryForList(
                "select created_at, started_at from ai_analysis_runs where started_at is not null and project_id = ? order by id desc limit " + RECENT_LIMIT,
                projectId);
        long total = 0;
        long max = 0;
        int count = 0;
        for (Map<String, Object> row : rows) {
            Timestamp created = (Timestamp) row.get("CREATED_AT");
            Timestamp started = (Timestamp) row.get("STARTED_AT");
            if (created == null || started == null) {
                continue;
            }
            long waitMillis = Math.max(0, started.getTime() - created.getTime());
            total += waitMillis;
            max = Math.max(max, waitMillis);
            count++;
        }
        Map<String, Object> queue = new LinkedHashMap<>();
        queue.put("sampledRuns", count);
        queue.put("averageQueueWaitMillis", count == 0 ? null : total / count);
        queue.put("maxQueueWaitMillis", count == 0 ? null : max);
        return queue;
    }

    private Map<String, Object> leaseMetrics() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "select updated_at, lease_until from ai_analysis_worker_leases");
        Instant now = clock.instant();
        long ageTotal = 0;
        long ageMax = 0;
        long remainingMin = Long.MAX_VALUE;
        int count = 0;
        for (Map<String, Object> row : rows) {
            Timestamp updated = (Timestamp) row.get("UPDATED_AT");
            Timestamp until = (Timestamp) row.get("LEASE_UNTIL");
            if (updated == null || until == null) {
                continue;
            }
            long ageMillis = Math.max(0, now.toEpochMilli() - updated.getTime());
            ageTotal += ageMillis;
            ageMax = Math.max(ageMax, ageMillis);
            remainingMin = Math.min(remainingMin, until.getTime() - now.toEpochMilli());
            count++;
        }
        Map<String, Object> leases = new LinkedHashMap<>();
        leases.put("activeLeases", count);
        leases.put("averageLeaseAgeMillis", count == 0 ? null : ageTotal / count);
        leases.put("maxLeaseAgeMillis", count == 0 ? null : ageMax);
        leases.put("minRemainingLeaseMillis", count == 0 ? null : remainingMin);
        return leases;
    }

    private Map<String, Object> attemptMetrics(Long projectId) {
        List<Long> durations = projectId == null
            ? jdbcTemplate.queryForList(
                "select duration_ms from ai_call_records where duration_ms is not null order by id desc limit " + RECENT_LIMIT, Long.class)
            : jdbcTemplate.queryForList(
                "select duration_ms from ai_call_records where duration_ms is not null and project_id = ? order by id desc limit " + RECENT_LIMIT,
                Long.class, projectId);
        List<Long> ttfbs = projectId == null
            ? jdbcTemplate.queryForList(
                "select ttfb_ms from ai_runtime_vertical_attempts where ttfb_ms is not null order by id desc limit " + RECENT_LIMIT, Long.class)
            : jdbcTemplate.queryForList("""
                select a.ttfb_ms from ai_runtime_vertical_attempts a
                join ai_runtime_vertical_runs r on r.run_id = a.run_id
                where a.ttfb_ms is not null and r.project_id = ? order by a.id desc limit %d
                """.formatted(RECENT_LIMIT), Long.class, projectId);
        Map<String, Object> attempts = new LinkedHashMap<>();
        attempts.put("sampledAttempts", durations.size());
        attempts.put("averageAttemptDurationMillis", average(durations));
        attempts.put("maxAttemptDurationMillis", max(durations));
        attempts.put("sampledRuntimeAttempts", ttfbs.size());
        attempts.put("averageTtfbMillis", average(ttfbs));
        attempts.put("maxTtfbMillis", max(ttfbs));
        return attempts;
    }

    private Map<String, Object> variabilityMetrics(Long projectId) {
        int blocked = projectId == null
            ? jdbcTemplate.queryForObject("select count(*) from ai_analysis_runs where result_origin = 'BLOCKED'", Integer.class)
            : jdbcTemplate.queryForObject(
                "select count(*) from ai_analysis_runs where result_origin = 'BLOCKED' and project_id = ?", Integer.class, projectId);
        int recovered = projectId == null
            ? jdbcTemplate.queryForObject(
                "select count(*) from ai_run_events where stage = 'lease_recovered'", Integer.class)
            : jdbcTemplate.queryForObject("""
                select count(*) from ai_run_events e where e.stage = 'lease_recovered'
                  and exists (select 1 from ai_analysis_runs r where r.run_id = e.run_id and r.project_id = ?)
                """, Integer.class, projectId);
        Map<String, Object> variability = new LinkedHashMap<>();
        variability.put("blockedRuns", blocked);
        variability.put("recoveredRuns", recovered);
        variability.put("blockedWarnThreshold", 1);
        variability.put("recoveryWarnThreshold", 1);
        return variability;
    }

    private Long average(List<Long> values) {
        if (values.isEmpty()) {
            return null;
        }
        long total = 0;
        for (Long value : values) {
            total += value;
        }
        return total / values.size();
    }

    private Long max(List<Long> values) {
        long max = 0;
        boolean any = false;
        for (Long value : values) {
            max = Math.max(max, value);
            any = true;
        }
        return any ? max : null;
    }
}
