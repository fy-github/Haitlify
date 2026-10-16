package com.haitlify.aitest.platform.ai.quota;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.haitlify.aitest.platform.auth.ProjectAccessService;

@Service
@Transactional(readOnly = true)
class AiQuotaService {

    private final JdbcTemplate jdbcTemplate;
    private final ProjectAccessService projectAccessService;

    AiQuotaService(JdbcTemplate jdbcTemplate, ProjectAccessService projectAccessService) {
        this.jdbcTemplate = jdbcTemplate;
        this.projectAccessService = projectAccessService;
    }

    AiQuotaUsageResponse getUsage(Long projectId, Instant from, Instant to) {
        projectAccessService.requireAccess(projectId);
        return jdbcTemplate.queryForObject("""
            select
              count(*) as call_count,
              coalesce(sum(token_usage_input), 0) as input_tokens,
              coalesce(sum(token_usage_output), 0) as output_tokens,
              coalesce(sum(case when estimated_cost is null then 0 else estimated_cost end), 0) as known_cost,
              coalesce(sum(case when estimated_cost is null then 1 else 0 end), 0) as unknown_cost_calls
            from ai_call_records
            where project_id = ? and created_at >= ? and created_at <= ?
            """,
            (rs, rowNum) -> {
                int inputTokens = rs.getInt("input_tokens");
                int outputTokens = rs.getInt("output_tokens");
                int callCount = rs.getInt("call_count");
                int unknownCostCalls = rs.getInt("unknown_cost_calls");
                BigDecimal knownCost = rs.getBigDecimal("known_cost");
                // T13-R5/A14：价格无来源必须显示「未知」——不得被 coalesce 冲成 0（免费语义）。
                String estimatedCost = (callCount > 0 && unknownCostCalls == callCount)
                    ? "UNKNOWN"
                    : (knownCost == null ? BigDecimal.ZERO : knownCost).setScale(4).toPlainString();
                return new AiQuotaUsageResponse(
                    projectId,
                    from.toString(),
                    to.toString(),
                    callCount,
                    inputTokens,
                    outputTokens,
                    inputTokens + outputTokens,
                    estimatedCost,
                    unknownCostCalls
                );
            },
            projectId,
            Timestamp.from(from),
            Timestamp.from(to)
        );
    }
}

record AiQuotaUsageResponse(
    Long projectId,
    String from,
    String to,
    int callCount,
    int inputTokens,
    int outputTokens,
    int totalTokens,
    String estimatedCost,
    int unknownCostCalls
) {
}
