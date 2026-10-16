package com.haitlify.aitest.platform.ai.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import com.haitlify.aitest.platform.ai.security.AiInvocationAuthorization;
import com.haitlify.aitest.platform.auth.ProjectAccessService;

import tools.jackson.databind.ObjectMapper;

/**
 * T-R5 轮 2 回归（复核发现 1）：job API 与 document API 的项目隔离——
 * 跨项目读取/取消与「不存在」必须同形 404；跨项目取消不得改变任何状态。
 */
class AiResourceIsolationRound2Test {

    private JdbcTemplate jdbc;
    private AiInvocationAuthorization authorization;
    private AiJobController controller;
    private AiJobService jobs;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
            "jdbc:h2:mem:tr5-isolation;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("create table if not exists projects (id bigint primary key, active boolean not null)");
        jdbc.execute("create table if not exists platform_users (username varchar(64) primary key,"
            + " role varchar(32) not null, enabled boolean not null)");
        jdbc.execute("create table if not exists project_members (id bigint auto_increment primary key,"
            + " project_id bigint not null, username varchar(64) not null, created_at timestamp not null)");
        jdbc.execute("""
            create table if not exists ai_runtime_jobs(
              id bigint auto_increment primary key, job_id varchar(128) not null, job_type varchar(64) not null,
              project_id bigint not null, actor_id varchar(128) not null, idempotency_key varchar(191) not null,
              input_hash varchar(128) not null, status varchar(32) not null, lease_owner varchar(128) default '',
              lease_until timestamp, fencing_token bigint default 0, attempt_count int default 0,
              deployment_revision varchar(128) default '', legacy_run_ref varchar(128), node_plan_json text not null,
              error_summary varchar(1024) default '', queued_at timestamp not null, started_at timestamp,
              first_token_at timestamp, finished_at timestamp, created_at timestamp not null, updated_at timestamp not null)
            """);
        jdbc.execute("""
            create table if not exists ai_runtime_job_nodes(
              id bigint auto_increment primary key, job_id varchar(128) not null, node_key varchar(191) not null,
              node_order int not null, status varchar(32) not null, side_effect varchar(32) default 'none',
              attempt_ref varchar(128) default '', output_ref varchar(255) default '',
              error_summary varchar(1024) default '', started_at timestamp, finished_at timestamp,
              created_at timestamp not null, updated_at timestamp not null)
            """);
        jdbc.execute("delete from ai_runtime_job_nodes");
        jdbc.execute("delete from ai_runtime_jobs");
        jdbc.execute("delete from project_members");
        jdbc.execute("delete from platform_users");
        jdbc.execute("delete from projects");

        jdbc.update("insert into projects(id, active) values (1, true), (2, true)");
        jdbc.update("insert into platform_users(username, role, enabled) values"
            + " ('editor1', 'EDITOR', true), ('editor2', 'EDITOR', true)");
        jdbc.update("insert into project_members(project_id, username, created_at) values (1, 'editor1', ?)",
            Timestamp.from(Instant.now()));
        jdbc.update("insert into project_members(project_id, username, created_at) values (2, 'editor2', ?)",
            Timestamp.from(Instant.now()));

        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
            insert into ai_runtime_jobs(job_id, job_type, project_id, actor_id, idempotency_key, input_hash,
              status, node_plan_json, queued_at, created_at, updated_at)
            values ('job-p2', 'requirement_analysis', 2, 'editor2', 'idem-p2', 'h', 'queued', '[]', ?, ?, ?)
            """, now, now, now);

        authorization = new AiInvocationAuthorization(jdbc, new ProjectAccessService(jdbc, true), true);
        jobs = new AiJobService(jdbc, new ObjectMapper(),
            Clock.fixed(Instant.parse("2026-10-12T02:00:00Z"), ZoneOffset.UTC));
        AiRunAuditService audit = mock(AiRunAuditService.class);
        when(audit.jobAudit(any(), any())).thenReturn(new AiRunAuditService.JobAudit(
            jobs.find("job-p2").orElseThrow(), List.of(), List.of(), List.of()));
        controller = new AiJobController(jobs, audit, authorization);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String username) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            username, "n/a", List.of(new SimpleGrantedAuthority("ROLE_EDITOR"))));
    }

    private String messageOf(Runnable call) {
        try {
            call.run();
            return "NO-ERROR";
        } catch (ResponseStatusException failure) {
            return failure.getStatusCode().value() + " " + failure.getReason();
        } catch (IllegalArgumentException failure) {
            return "IAE: " + failure.getMessage();
        }
    }

    @Test
    void crossProjectJobReadsAndCancelsAreUniform404sAndChangeNothing() {
        authenticate("editor1");
        // 项目1成员读项目2的 job 与猜测 jobId：逐字相同。
        String forbidden = messageOf(() -> controller.job("job-p2", null));
        String missing = messageOf(() -> controller.job("job-guess-nonexistent", null));
        assertThat(forbidden).isEqualTo("404 not_found");
        assertThat(missing).isEqualTo(forbidden);

        // 跨项目取消同样 404，且目标 job 状态不得改变。
        String cancelForbidden = messageOf(() -> controller.cancel("job-p2", "editor1"));
        assertThat(cancelForbidden).isEqualTo("404 not_found");
        assertThat(jdbc.queryForObject(
            "select status from ai_runtime_jobs where job_id = 'job-p2'", String.class)).isEqualTo("queued");

        // 事件面同样不可见。
        assertThat(messageOf(() -> controller.events("job-p2", null))).isEqualTo("404 not_found");
    }

    @Test
    void crossProjectJobSubmissionIsRefusedAndCreatesNothing() {
        authenticate("editor1");
        AiJobController.SubmitRequest request = new AiJobController.SubmitRequest(
            "requirement_analysis", 2L, "editor1", "idem-cross", "hash-x", java.util.List.of("n1"), "rev-1",
            null);
        String message = messageOf(() -> controller.submit(request));
        assertThat(message).isEqualTo("404 not_found");
        assertThat(jdbc.queryForObject(
            "select count(*) from ai_runtime_jobs where idempotency_key = 'idem-cross'", Integer.class)).isZero();

        // 本人项目可提交（对照）。
        AiJobController.SubmitRequest own = new AiJobController.SubmitRequest(
            "requirement_analysis", 1L, "editor1", "idem-own", "hash-y", java.util.List.of("n1"), "rev-1",
            null);
        assertThatNoException().isThrownBy(() -> controller.submit(own));
    }

    @Test
    void jobTypeVariantsAreNormalizedOnSubmitSoTheSwitchMatchesAtRuntime() {
        authenticate("editor1");
        AiJobController.SubmitRequest request = new AiJobController.SubmitRequest(
            "requirement_analysis\u00a0", 1L, "editor1", "idem-nbsp", "hash-z", java.util.List.of("n1"),
            "rev-1", null);
        controlSubmitSuccess(request);
        assertThat(jdbc.queryForObject(
            "select job_type from ai_runtime_jobs where idempotency_key = 'idem-nbsp'", String.class))
            .isEqualTo("requirement_analysis");
    }

    private void controlSubmitSuccess(AiJobController.SubmitRequest request) {
        try {
            controller.submit(request);
        } catch (IllegalArgumentException unexpectedlyRejected) {
            throw new AssertionError("归一化后的 jobType 应可提交: " + unexpectedlyRejected.getMessage());
        }
    }

    @Test
    void invisibleMarksAreStrippedAndUnknownTypesAreRejectedOnSubmit() {
        authenticate("editor1");
        // U+034F 组合标记：归一到真实类型后入库。
        AiJobController.SubmitRequest marked = new AiJobController.SubmitRequest(
            "requirement_analysis\u034f", 1L, "editor1", "idem-cgj", "hash-c", java.util.List.of("n1"),
            "rev-1", null);
        controlSubmitSuccess(marked);
        assertThat(jdbc.queryForObject(
            "select job_type from ai_runtime_jobs where idempotency_key = 'idem-cgj'", String.class))
            .isEqualTo("requirement_analysis");
        // 'ʰ' 折叠后是未知类型 ⇒ 400 且零落库。
        AiJobController.SubmitRequest confusable = new AiJobController.SubmitRequest(
            "requirement_analysis\u02b0", 1L, "editor1", "idem-confuse", "hash-d", java.util.List.of("n1"),
            "rev-1", null);
        String message = messageOf(() -> controller.submit(confusable));
        assertThat(message).contains("unknown_task_type");
        assertThat(jdbc.queryForObject(
            "select count(*) from ai_runtime_jobs where idempotency_key = 'idem-confuse'", Integer.class))
            .isZero();
    }

    @Test
    void uppercaseFoldingVariantsAreStoredAsTheCatalogCanonicalValue() {
        authenticate("editor1");
        AiJobController.SubmitRequest bold = new AiJobController.SubmitRequest(
            "bug_\ud835\udc00nalysis", 1L, "editor1", "idem-bold", "hash-e", java.util.List.of("n1"),
            "rev-1", null);
        controlSubmitSuccess(bold);
        assertThat(jdbc.queryForObject(
            "select job_type from ai_runtime_jobs where idempotency_key = 'idem-bold'", String.class))
            .isEqualTo("bug_analysis");
    }

    @Test
    void theOwningProjectsMemberStillHasFullAccess() {
        authenticate("editor2");
        assertThatCode(() -> controller.job("job-p2", null)).doesNotThrowAnyException();
        assertThatCode(() -> controller.events("job-p2", null)).doesNotThrowAnyException();
        AiJobController.CancelResponse response = controller.cancel("job-p2", "editor2");
        assertThat(response.outcome()).isNotEqualTo("NOT_FOUND");
        // 注意：queued 状态的取消按 job 语义可能先记为取消请求（由调度器收敛）；
        // 本测试的关键不变量是「归属项目成员可用、跨项目一律 404 且零状态变化」——
        // 后者已由 crossProjectJobReadsAndCancelsAreUniform404sAndChangeNothing 断言。
    }
}
