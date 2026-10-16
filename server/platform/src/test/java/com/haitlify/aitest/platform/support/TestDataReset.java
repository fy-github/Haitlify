package com.haitlify.aitest.platform.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * FK-safe resets for the shared test schema.
 *
 * <p>The schema declares real foreign keys, so a bare parent delete fails as soon as a sibling test
 * class left child rows behind:
 *
 * <ul>
 *   <li>{@code fk_project_members_user}: {@code project_members(username)} -&gt;
 *       {@code platform_users(username)} (V21_project_membership_authorization)
 *   <li>{@code fk_ai_coverage_summary_run}: {@code ai_coverage_summaries(run_id)} -&gt;
 *       {@code ai_analysis_runs(id)} (V4_ai_agent_tool_calling)
 * </ul>
 *
 * <p>A bare delete therefore passes or fails depending on which classes ran first, which is why the
 * same commit could be green locally and red in CI. Use these helpers instead of inlining the deletes
 * so the child-before-parent order stays correct.
 */
public final class TestDataReset {

    private TestDataReset() {
    }

    /** Clears every platform user, removing the membership rows that reference them first. */
    public static void clearPlatformUsers(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update("delete from project_members");
        jdbcTemplate.update("delete from platform_users");
    }

    /** Clears analysis runs, removing the coverage summaries that reference them first. */
    public static void clearAiAnalysisRuns(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update("delete from ai_coverage_summaries");
        jdbcTemplate.update("delete from ai_analysis_runs");
    }
}
