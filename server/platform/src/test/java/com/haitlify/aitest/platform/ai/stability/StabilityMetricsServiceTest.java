package com.haitlify.aitest.platform.ai.stability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.haitlify.aitest.platform.auth.ProjectAccessService;

class StabilityMetricsServiceTest {

    @Test
    void aggregatesStabilityMetricsFromExistingTablesWithoutGuessing() {
        JdbcTemplate jdbc = newStore("stability-metrics");
        jdbc.update("insert into ai_web_case_agent_runs(run_id, project_id, status) values ('run-1', 1, 'completed')");
        jdbc.update("insert into ai_web_case_agent_runs(run_id, project_id, status) values ('run-2', 1, 'failed')");
        jdbc.update("insert into ai_web_case_agent_runs(run_id, project_id, status) values ('run-3', 1, 'cancelled')");
        jdbc.update("insert into ai_web_case_agent_runs(run_id, project_id, status) values ('run-9', 9, 'failed')");

        jdbc.update("insert into ai_web_case_agent_artifacts(artifact_id, run_id, validation_errors_json, accepted, acceptance_state) values ('a1', 'run-1', '[]', true, 'ACCEPTED')");
        jdbc.update("insert into ai_web_case_agent_artifacts(artifact_id, run_id, validation_errors_json, accepted, acceptance_state) values ('a2', 'run-1', '', false, 'PENDING_HUMAN_CONFIRMATION')");
        jdbc.update("insert into ai_web_case_agent_artifacts(artifact_id, run_id, validation_errors_json, accepted, acceptance_state) values ('a3', 'run-2', '[\"invalid_action_ir\"]', true, 'PENDING_HUMAN_CONFIRMATION')");
        jdbc.update("insert into ai_web_case_agent_artifacts(artifact_id, run_id, validation_errors_json, accepted, acceptance_state) values ('a9', 'run-9', '[]', true, 'ACCEPTED')");

        jdbc.update("insert into ai_web_case_agent_events(event_id, run_id, severity, error_code) values ('e1', 'run-2', 'error', 'login_failed')");
        jdbc.update("insert into ai_web_case_agent_events(event_id, run_id, severity, error_code) values ('e2', 'run-2', 'error', 'login_failed')");
        jdbc.update("insert into ai_web_case_agent_events(event_id, run_id, severity, error_code) values ('e3', 'run-2', 'warn', 'pagination_not_changed')");
        jdbc.update("insert into ai_web_case_agent_events(event_id, run_id, severity, error_code) values ('e4', 'run-1', 'info', null)");
        jdbc.update("insert into ai_web_case_agent_events(event_id, run_id, severity, error_code) values ('e9', 'run-9', 'error', 'login_failed')");

        jdbc.update("insert into ai_locator_knowledge_entries(entry_id, project_id, status, execution_success_count, execution_failure_count) values ('l1', 1, 'active', 8, 2)");
        jdbc.update("insert into ai_locator_knowledge_entries(entry_id, project_id, status, execution_success_count, execution_failure_count) values ('l2', 1, 'retired', 0, 0)");
        jdbc.update("insert into ai_locator_knowledge_entries(entry_id, project_id, status, execution_success_count, execution_failure_count) values ('l9', 9, 'active', 5, 5)");

        jdbc.update("insert into ai_locator_repair_proposals(proposal_id, project_id, status) values ('p1', 1, 'accepted')");
        jdbc.update("insert into ai_locator_repair_proposals(proposal_id, project_id, status) values ('p2', 1, 'accepted')");
        jdbc.update("insert into ai_locator_repair_proposals(proposal_id, project_id, status) values ('p3', 1, 'accepted')");
        jdbc.update("insert into ai_locator_repair_proposals(proposal_id, project_id, status) values ('p4', 1, 'rejected')");
        jdbc.update("insert into ai_locator_repair_proposals(proposal_id, project_id, status) values ('p5', 1, 'pending_review')");
        jdbc.update("insert into ai_locator_repair_proposals(proposal_id, project_id, status) values ('p9', 9, 'rejected')");

        jdbc.update("insert into ai_web_automation_run_reports(run_id, project_id, status, total_cases, total_case_duration_ms) values ('wr-1', 1, 'completed', 2, 3000)");
        jdbc.update("insert into ai_web_automation_run_reports(run_id, project_id, status, total_cases, total_case_duration_ms) values ('wr-2', 1, 'failed', 1, 1000)");
        jdbc.update("insert into ai_web_automation_run_reports(run_id, project_id, status, total_cases, total_case_duration_ms) values ('wr-3', 1, 'stopped', 0, 0)");
        jdbc.update("insert into ai_web_automation_run_reports(run_id, project_id, status, total_cases, total_case_duration_ms) values ('wr-4', 1, 'running', 5, 9999)");
        jdbc.update("insert into ai_web_automation_run_reports(run_id, project_id, status, total_cases, total_case_duration_ms) values ('wr-9', 9, 'completed', 7, 7000)");

        var service = new StabilityMetricsService(jdbc, mock(ProjectAccessService.class));
        var response = service.metrics(1L);

        assertThat(response.runs().total()).isEqualTo(3);
        assertThat(response.runs().byStatus()).containsEntry("completed", 1L).containsEntry("failed", 1L).containsEntry("cancelled", 1L);
        assertThat(response.runs().failureCount()).isEqualTo(1);

        assertThat(response.compile().artifactsTotal()).isEqualTo(3);
        assertThat(response.compile().artifactsErrorFree()).isEqualTo(2);
        assertThat(response.compile().artifactsAccepted()).isEqualTo(1);
        assertThat(response.compile().compileSuccessRate()).isEqualTo(0.6667);
        assertThat(response.compile().acceptanceRate()).isEqualTo(0.3333);

        assertThat(response.events().total()).isEqualTo(4);
        assertThat(response.events().errorCount()).isEqualTo(2);
        assertThat(response.events().errorRate()).isEqualTo(0.5);
        assertThat(response.events().failureReasons().get(0)).containsEntry("errorCode", "login_failed").containsEntry("occurrences", 2L);

        assertThat(response.locator().entriesTotal()).isEqualTo(2);
        assertThat(response.locator().byStatus()).containsEntry("active", 1L).containsEntry("retired", 1L);
        assertThat(response.locator().executionSuccessCount()).isEqualTo(8);
        assertThat(response.locator().executionFailureCount()).isEqualTo(2);
        assertThat(response.locator().locatorFailureRate()).isEqualTo(0.2);

        assertThat(response.repair().acceptedCount()).isEqualTo(3);
        assertThat(response.repair().rejectedCount()).isEqualTo(1);
        assertThat(response.repair().repairSuccessRate()).isEqualTo(0.75);
        assertThat(response.repair().proposalsByStatus()).containsEntry("pending_review", 1L);

        // 执行结果（V37）：终态=completed/failed/stopped；running 行不得进入任何聚合。
        assertThat(response.execution().terminalRuns()).isEqualTo(3);
        assertThat(response.execution().completedRuns()).isEqualTo(1);
        assertThat(response.execution().executionSuccessRate()).isEqualTo(0.3333);
        assertThat(response.execution().casesTotal()).isEqualTo(3);
        assertThat(response.execution().totalCaseDurationMs()).isEqualTo(4000);
        assertThat(response.execution().averageCaseDurationMs()).isEqualTo(1333.0);

        assertThat(response.deferred()).hasSize(1);
    }

    @Test
    void zeroDenominatorsReturnNullInsteadOfZeroPercent() {
        JdbcTemplate jdbc = newStore("stability-empty");
        var service = new StabilityMetricsService(jdbc, mock(ProjectAccessService.class));

        var response = service.metrics(42L);

        assertThat(response.compile().compileSuccessRate()).isNull();
        assertThat(response.compile().acceptanceRate()).isNull();
        assertThat(response.events().errorRate()).isNull();
        assertThat(response.locator().locatorFailureRate()).isNull();
        assertThat(response.repair().repairSuccessRate()).isNull();
        assertThat(response.runs().total()).isZero();
        assertThat(response.execution().executionSuccessRate()).isNull();
        assertThat(response.execution().averageCaseDurationMs()).isNull();
        assertThat(response.deferred()).hasSize(1);
    }

    private JdbcTemplate newStore(String name) {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            "jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("create table ai_web_case_agent_runs (run_id varchar(128) primary key, project_id bigint not null, status varchar(32) not null)");
        jdbc.execute("create table ai_web_case_agent_artifacts (artifact_id varchar(128) primary key, run_id varchar(128) not null, validation_errors_json text, accepted boolean not null, acceptance_state varchar(64) not null)");
        jdbc.execute("create table ai_web_case_agent_events (event_id varchar(128) primary key, run_id varchar(128) not null, severity varchar(16) not null, error_code varchar(128))");
        jdbc.execute("create table ai_locator_knowledge_entries (entry_id varchar(128) primary key, project_id bigint not null, status varchar(32) not null, execution_success_count integer not null default 0, execution_failure_count integer not null default 0)");
        jdbc.execute("create table ai_locator_repair_proposals (proposal_id varchar(128) primary key, project_id bigint not null, status varchar(32) not null)");
        jdbc.execute("create table ai_web_automation_run_reports (run_id varchar(128) primary key, project_id bigint null, status varchar(32) not null, total_cases integer not null default 0, total_case_duration_ms bigint not null default 0)");
        return jdbc;
    }
}
