package com.haitlify.aitest.platform.webautomation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class WebAutomationRunServiceTest {

    @Test
    void createRunPersistsRowWithProjectId() {
        PlaywrightRunClient client = mock(PlaywrightRunClient.class);
        when(client.createRun(any())).thenReturn(snapshot("run-1", "queued", null, null));
        JdbcTemplate jdbc = newStore("run-service-create");
        var service = new WebAutomationRunService(client, jdbc);

        service.createRun(new WebAutomationRunRequest("batch", null, null, null, List.of(), 1L));

        var row = jdbc.queryForMap("select * from ai_web_automation_run_reports where run_id = 'run-1'");
        assertThat(row.get("STATUS")).isEqualTo("queued");
        assertThat(((Number) row.get("PROJECT_ID")).longValue()).isEqualTo(1L);
        assertThat(((Number) row.get("TOTAL_CASES")).longValue()).isZero();
    }

    @Test
    void repollsUpdateTheRowIdempotentlyAndKeepProjectId() {
        PlaywrightRunClient client = mock(PlaywrightRunClient.class);
        when(client.createRun(any())).thenReturn(snapshot("run-2", "queued", null, null));
        when(client.getRun(eq("run-2"))).thenReturn(snapshot("run-2", "completed",
            new WebAutomationRunSummaryResponse(2, 1, 1, 0), List.of(1200, 1800)));
        JdbcTemplate jdbc = newStore("run-service-repoll");
        var service = new WebAutomationRunService(client, jdbc);

        service.createRun(new WebAutomationRunRequest("batch", null, null, null, List.of(), 1L));
        service.getRun("run-2");
        service.getRun("run-2");

        assertThat(jdbc.queryForObject("select count(*) from ai_web_automation_run_reports", Long.class)).isEqualTo(1L);
        var row = jdbc.queryForMap("select * from ai_web_automation_run_reports where run_id = 'run-2'");
        assertThat(row.get("STATUS")).isEqualTo("completed");
        assertThat(((Number) row.get("PROJECT_ID")).longValue()).isEqualTo(1L);
        assertThat(((Number) row.get("PASSED_CASES")).longValue()).isEqualTo(1L);
        assertThat(((Number) row.get("FAILED_CASES")).longValue()).isEqualTo(1L);
        assertThat(((Number) row.get("TOTAL_CASE_DURATION_MS")).longValue()).isEqualTo(3000L);
        assertThat(row.get("SESSION_ID")).isEqualTo("sess-1");
    }

    @Test
    void createWithoutProjectIdKeepsNullProjectAndExcludesNothingFromRetention() {
        PlaywrightRunClient client = mock(PlaywrightRunClient.class);
        when(client.createRun(any())).thenReturn(snapshot("run-3", "queued", null, null));
        JdbcTemplate jdbc = newStore("run-service-noproject");
        var service = new WebAutomationRunService(client, jdbc);

        service.createRun(new WebAutomationRunRequest("batch", null, null, null, List.of(), null));

        var row = jdbc.queryForMap("select * from ai_web_automation_run_reports where run_id = 'run-3'");
        assertThat(row.get("PROJECT_ID")).isNull();
    }

    @Test
    void stopControlAlsoPersistsTerminalState() {
        PlaywrightRunClient client = mock(PlaywrightRunClient.class);
        when(client.createRun(any())).thenReturn(snapshot("run-4", "queued", null, null));
        when(client.controlRun(eq("run-4"), eq("stop"))).thenReturn(snapshot("run-4", "stopped", null, null));
        JdbcTemplate jdbc = newStore("run-service-stop");
        var service = new WebAutomationRunService(client, jdbc);

        service.createRun(new WebAutomationRunRequest("batch", null, null, null, List.of(), 7L));
        service.controlRun("run-4", "stop");

        var row = jdbc.queryForMap("select * from ai_web_automation_run_reports where run_id = 'run-4'");
        assertThat(row.get("STATUS")).isEqualTo("stopped");
        assertThat(((Number) row.get("PROJECT_ID")).longValue()).isEqualTo(7L);
    }

    @Test
    void blankRunIdOrStatusIsIgnoredInsteadOfWritingJunk() {
        PlaywrightRunClient client = mock(PlaywrightRunClient.class);
        JdbcTemplate jdbc = newStore("run-service-guard");
        var service = new WebAutomationRunService(client, jdbc);

        service.persistSnapshot(null, 1L);
        service.persistSnapshot(snapshot("  ", "queued", null, null), 1L);
        service.persistSnapshot(snapshot("run-blank", "  ", null, null), 1L);

        assertThat(jdbc.queryForObject("select count(*) from ai_web_automation_run_reports", Long.class)).isZero();
    }

    @Test
    void listSessionsProxiesToTheRunnerClient() {
        PlaywrightRunClient client = mock(PlaywrightRunClient.class);
        when(client.listSessions()).thenReturn(new WebAutomationSessionListResponse(List.of(
            new WebAutomationSessionView("session-1", 1L, "env-1", "chromium", "READY_FOR_COMPILE", false, "http://example.com/x", 1L, 2L, 3L))));
        JdbcTemplate jdbc = newStore("run-service-sessions");
        var service = new WebAutomationRunService(client, jdbc);

        var response = service.listSessions();

        assertThat(response.sessions()).hasSize(1);
        assertThat(response.sessions().get(0).sessionId()).isEqualTo("session-1");
        assertThat(response.sessions().get(0).leased()).isFalse();
        assertThat(response.sessions().get(0).pageKey()).isEqualTo("http://example.com/x");
    }

    private static WebAutomationRunSnapshotResponse snapshot(String runId, String status, WebAutomationRunSummaryResponse summary, List<Integer> durations) {
        List<WebAutomationRunResultResponse> results = durations == null ? List.of()
            : durations.stream().map(duration -> new WebAutomationRunResultResponse(
                "case-1", "src-1", "case", "module", "page", "http://example/", "chromium",
                "passed", duration, null, null, null, null, List.of(), List.of(), List.of(), List.of())).toList();
        return new WebAutomationRunSnapshotResponse(
            runId, status, "batch", "chromium", "2026-10-08T10:00:00Z", "2026-10-08T10:00:01Z",
            "2026-10-08T10:00:05Z", "2026-10-08T10:00:05Z", null, null, null,
            null, null, null, "sess-1", null, summary, List.of(), results, List.of());
    }

    private JdbcTemplate newStore(String name) {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            "jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("create table ai_web_automation_run_reports ("
            + "id bigint auto_increment primary key, run_id varchar(128) not null, project_id bigint null, "
            + "status varchar(32) not null default 'queued', mode varchar(32) not null default '', "
            + "selected_browser varchar(64) not null default '', total_cases integer not null default 0, "
            + "passed_cases integer not null default 0, failed_cases integer not null default 0, "
            + "error_cases integer not null default 0, total_case_duration_ms bigint not null default 0, "
            + "session_id varchar(128) not null default '', created_at timestamp not null, updated_at timestamp not null, "
            + "constraint uk_ai_web_automation_run_report_run unique (run_id))");
        return jdbc;
    }
}
