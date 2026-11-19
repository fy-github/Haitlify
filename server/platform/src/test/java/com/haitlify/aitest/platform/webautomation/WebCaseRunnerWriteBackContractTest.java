package com.haitlify.aitest.platform.webautomation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;



/**
 * T10 bullet 6 合同（旧 ai.agentic 流程与其测试已在 A3 退役，断言独立保留）：执行回写只能来自
 * Runner 快照——失败快照如实落 failed 与 0 passed，AI 生成成功与评审接受都不会、
 * 也无权把它改写成 PASS。
 */
class WebCaseRunnerWriteBackContractTest {

    @Test
    void runnerSnapshotFailureLandsAsFailedWithoutAnyPass() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:runner-writeback;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
            create table if not exists ai_web_automation_run_reports(
              run_id varchar(128) primary key, project_id bigint, status varchar(32), mode varchar(32),
              selected_browser varchar(64), total_cases int, passed_cases int, failed_cases int, error_cases int,
              total_case_duration_ms bigint, session_id varchar(128), created_at timestamp, updated_at timestamp)
            """);

        PlaywrightRunClient client = mock(PlaywrightRunClient.class);
        var service = new WebAutomationRunService(client, jdbc);
        var results = List.of(
            new WebAutomationRunResultResponse("case-1", "src-1", "case", "module", "page", "http://example/",
                "chromium", "failed", 900, "断言失败", null, null, null, List.of(), List.of(), List.of(), List.of()));
        var snapshot = new WebAutomationRunSnapshotResponse(
            "run-fail", "failed", "batch", "chromium", "2026-10-10T10:00:00Z", "2026-10-10T10:00:01Z",
            "2026-10-10T10:00:05Z", "2026-10-10T10:00:05Z", null, null, null,
            null, null, null, "sess-1", null, new WebAutomationRunSummaryResponse(1, 0, 1, 0),
            List.of(), results, List.of());

        service.persistSnapshot(snapshot, 1L);

        var row = jdbc.queryForMap("select * from ai_web_automation_run_reports where run_id = 'run-fail'");
        assertThat(row.get("STATUS")).isEqualTo("failed");
        assertThat(((Number) row.get("PASSED_CASES")).intValue()).isZero();
        assertThat(((Number) row.get("FAILED_CASES")).intValue()).isEqualTo(1);
    }
}
