package com.haitlify.aitest.platform.ai.stability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.haitlify.aitest.platform.auth.ProjectAccessService;

/**
 * 阶段 7（7.3）：稳定性指标聚合。
 *
 * 全部指标从现有表派生，不做任何推测：
 * - 编译成功率：执行产物中「无校验错误」的比例（ai_web_case_agent_artifacts）；
 * - 交付接受率：accepted=true 且 acceptance_state='ACCEPTED'（与正式交付查询同一口径）；
 * - locator 失效率：知识库条目的执行失败计数占比（V11 的 execution_success/failure_count）；
 * - repair 成功率：提案 accepted/(accepted+rejected)；
 * - 失败原因分布：事件表 error_code 的 Top-N。
 *
 * 尚未埋点的指标（执行成功率 / 平均执行耗时 / session 复用失败率）不伪装成 0：
 * 在 deferred 列表中显式给出原因，等待「执行结果持久化」落地后再纳入。
 */
@Service
@Transactional
public class StabilityMetricsService {

    private static final List<Map<String, String>> DEFERRED = List.of(
        Map.of("metric", "sessionReuseFailureRate", "reason", "runner 会话复用失败目前只出现在单次运行诊断里，未按项目落库")
    );

    private final JdbcTemplate jdbcTemplate;
    private final ProjectAccessService projectAccessService;

    StabilityMetricsService(JdbcTemplate jdbcTemplate, ProjectAccessService projectAccessService) {
        this.jdbcTemplate = jdbcTemplate;
        this.projectAccessService = projectAccessService;
    }

    public StabilityMetricsResponse metrics(Long projectId) {
        if (projectId == null) throw new IllegalArgumentException("项目不能为空");
        projectAccessService.requireAccess(projectId);

        Map<String, Long> runsByStatus = countBy("select status, count(*) from ai_web_case_agent_runs where project_id = ? group by status", projectId);
        long runsTotal = runsByStatus.values().stream().mapToLong(Long::longValue).sum();
        long runFailures = runsByStatus.entrySet().stream()
            .filter(entry -> entry.getKey() != null && (entry.getKey().contains("fail") || entry.getKey().contains("error")))
            .mapToLong(Map.Entry::getValue).sum();

        ArtifactCounts artifacts = jdbcTemplate.queryForObject(
            """
            select count(*) as total,
                   sum(case when (a.validation_errors_json is null or a.validation_errors_json in ('', '[]')) then 1 else 0 end) as error_free,
                   sum(case when a.accepted = true and a.acceptance_state = 'ACCEPTED' then 1 else 0 end) as accepted
            from ai_web_case_agent_artifacts a
            join ai_web_case_agent_runs r on r.run_id = a.run_id
            where r.project_id = ?
            """,
            (rs, rowNum) -> new ArtifactCounts(rs.getLong("total"), rs.getLong("error_free"), rs.getLong("accepted")),
            projectId);
        long artifactTotal = artifacts == null ? 0 : artifacts.total();
        long errorFree = artifacts == null ? 0 : artifacts.errorFree();
        long accepted = artifacts == null ? 0 : artifacts.accepted();

        EventCounts events = jdbcTemplate.queryForObject(
            """
            select count(*) as total,
                   sum(case when e.severity = 'error' then 1 else 0 end) as error_events
            from ai_web_case_agent_events e
            join ai_web_case_agent_runs r on r.run_id = e.run_id
            where r.project_id = ?
            """,
            (rs, rowNum) -> new EventCounts(rs.getLong("total"), rs.getLong("error_events")),
            projectId);
        long eventTotal = events == null ? 0 : events.total();
        long errorEvents = events == null ? 0 : events.errorEvents();

        List<Map<String, Object>> failureReasons = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(
            """
            select e.error_code as errorCode, count(*) as occurrences
            from ai_web_case_agent_events e
            join ai_web_case_agent_runs r on r.run_id = e.run_id
            where r.project_id = ? and e.error_code is not null and e.error_code <> ''
            group by e.error_code
            order by occurrences desc
            limit 10
            """, projectId)) {
            Map<String, Object> reason = new LinkedHashMap<>();
            reason.put("errorCode", row.get("errorCode") == null ? row.get("ERRORCODE") : row.get("errorCode"));
            reason.put("occurrences", row.get("occurrences") == null ? row.get("OCCURRENCES") : row.get("occurrences"));
            failureReasons.add(reason);
        }

        Map<String, Long> locatorByStatus = countBy("select status, count(*) from ai_locator_knowledge_entries where project_id = ? group by status", projectId);
        LocatorCounts locatorCounts = jdbcTemplate.queryForObject(
            "select count(*) as total, coalesce(sum(execution_success_count), 0) as success, coalesce(sum(execution_failure_count), 0) as failure from ai_locator_knowledge_entries where project_id = ?",
            (rs, rowNum) -> new LocatorCounts(rs.getLong("total"), rs.getLong("success"), rs.getLong("failure")),
            projectId);
        long locatorTotal = locatorCounts == null ? 0 : locatorCounts.total();
        long locatorSuccess = locatorCounts == null ? 0 : locatorCounts.success();
        long locatorFailure = locatorCounts == null ? 0 : locatorCounts.failure();

        Map<String, Long> proposalsByStatus = countBy("select status, count(*) from ai_locator_repair_proposals where project_id = ? group by status", projectId);
        long repairAccepted = proposalsByStatus.getOrDefault("accepted", 0L);
        long repairRejected = proposalsByStatus.getOrDefault("rejected", 0L);

        // 执行结果持久化（V37）落地后转正的指标：终态运行（completed/failed/stopped）口径。
        ExecutionCounts executionCounts = jdbcTemplate.queryForObject(
            "select sum(case when status in ('completed', 'failed', 'stopped') then 1 else 0 end) as terminal, sum(case when status = 'completed' then 1 else 0 end) as completed, coalesce(sum(case when status in ('completed', 'failed', 'stopped') then total_cases else 0 end), 0) as cases, coalesce(sum(case when status in ('completed', 'failed', 'stopped') then total_case_duration_ms else 0 end), 0) as duration from ai_web_automation_run_reports where project_id = ?",
            (rs, rowNum) -> new ExecutionCounts(rs.getLong("terminal"), rs.getLong("completed"), rs.getLong("cases"), rs.getLong("duration")),
            projectId);
        long executionTerminal = executionCounts == null ? 0 : executionCounts.terminal();
        long executionCompleted = executionCounts == null ? 0 : executionCounts.completed();
        long executionCases = executionCounts == null ? 0 : executionCounts.cases();
        long executionDuration = executionCounts == null ? 0 : executionCounts.duration();

        return new StabilityMetricsResponse(
            new RunMetrics(runsTotal, runsByStatus, runFailures),
            new CompileMetrics(artifactTotal, errorFree, accepted, rate(errorFree, artifactTotal), rate(accepted, artifactTotal)),
            new EventMetrics(eventTotal, errorEvents, rate(errorEvents, eventTotal), failureReasons),
            new LocatorMetrics(locatorTotal, locatorByStatus, locatorSuccess, locatorFailure, rate(locatorFailure, locatorSuccess + locatorFailure)),
            new RepairMetrics(proposalsByStatus, repairAccepted, repairRejected, rate(repairAccepted, repairAccepted + repairRejected)),
            new ExecutionMetrics(executionTerminal, executionCompleted, executionCases, executionDuration,
                rate(executionCompleted, executionTerminal), executionCases <= 0 ? null : (double) Math.round(executionDuration * 1.0 / executionCases)),
            DEFERRED
        );
    }

    private Map<String, Long> countBy(String sql, Long projectId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql, projectId)) {
            Object key = row.get("status") == null ? row.get("STATUS") : row.get("status");
            Object value = row.values().stream().filter(item -> item instanceof Number).findFirst().orElse(0L);
            if (key != null) counts.put(String.valueOf(key), ((Number) value).longValue());
        }
        return counts;
    }

    /** 零分母返回 null：不把「没有数据」伪装成 0%。 */
    static Double rate(long numerator, long denominator) {
        if (denominator <= 0) return null;
        return Math.round(numerator * 10_000.0 / denominator) / 10_000.0;
    }

    private record ArtifactCounts(long total, long errorFree, long accepted) { }

    private record EventCounts(long total, long errorEvents) { }

    private record LocatorCounts(long total, long success, long failure) { }

    private record ExecutionCounts(long terminal, long completed, long cases, long duration) { }

    public record RunMetrics(long total, Map<String, Long> byStatus, long failureCount) { }

    public record CompileMetrics(long artifactsTotal, long artifactsErrorFree, long artifactsAccepted, Double compileSuccessRate, Double acceptanceRate) { }

    public record EventMetrics(long total, long errorCount, Double errorRate, List<Map<String, Object>> failureReasons) { }

    public record LocatorMetrics(long entriesTotal, Map<String, Long> byStatus, long executionSuccessCount, long executionFailureCount, Double locatorFailureRate) { }

    public record RepairMetrics(Map<String, Long> proposalsByStatus, long acceptedCount, long rejectedCount, Double repairSuccessRate) { }

    /** 执行结果持久化（V37）转正：终态= completed/failed/stopped；平均耗时按用例数摊。 */
    public record ExecutionMetrics(long terminalRuns, long completedRuns, long casesTotal, long totalCaseDurationMs, Double executionSuccessRate, Double averageCaseDurationMs) { }

    public record StabilityMetricsResponse(
        RunMetrics runs,
        CompileMetrics compile,
        EventMetrics events,
        LocatorMetrics locator,
        RepairMetrics repair,
        ExecutionMetrics execution,
        List<Map<String, String>> deferred
    ) { }
}
