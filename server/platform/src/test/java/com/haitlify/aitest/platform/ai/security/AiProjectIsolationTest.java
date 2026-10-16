package com.haitlify.aitest.platform.ai.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.sql.Timestamp;
import java.time.Instant;
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

import com.haitlify.aitest.platform.ai.assistant.AiAssistantService;
import com.haitlify.aitest.platform.ai.evidence.AiEvidenceManifestService;
import com.haitlify.aitest.platform.ai.event.AiRunEventService;
import com.haitlify.aitest.platform.ai.knowledge.AiContextBuilder;
import com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade;
import com.haitlify.aitest.platform.auth.ProjectAccessService;

import tools.jackson.databind.ObjectMapper;

/**
 * T14 批次 1：三个角色 × 两个项目 × 猜测 id 的权限负例。
 * 关键不变量：不存在与无权对外同一形状（404 not_found），攻击者无法探测对象存在；
 * 后台按 actor 复核成员关系，撤权在下一个节点前即生效（无需 HTTP session）。
 */
class AiProjectIsolationTest {

    private JdbcTemplate jdbc;
    private AiInvocationAuthorization authorization;
    private AiAssistantService assistantService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
            "jdbc:h2:mem:ai-isolation;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("create table if not exists projects (id bigint primary key, active boolean not null)");
        jdbc.execute("create table if not exists platform_users (username varchar(64) primary key,"
            + " role varchar(32) not null, enabled boolean not null)");
        jdbc.execute("create table if not exists project_members (id bigint auto_increment primary key,"
            + " project_id bigint not null, username varchar(64) not null, created_at timestamp not null)");
        jdbc.execute("""
            create table if not exists ai_assistant_artifacts(
              artifact_id varchar(160) primary key, run_id varchar(160) not null, task_type varchar(64) not null,
              project_id bigint not null, revision int not null, acceptance_state varchar(32) not null,
              result_origin varchar(32) not null, payload_json clob, violations_json clob,
              source_manifest_json clob, created_by varchar(128) default '', reviewed_by varchar(128) default '',
              created_at timestamp, updated_at timestamp, deterministic_facts_json clob)
            """);
        jdbc.execute("alter table ai_assistant_artifacts"
            + " add column if not exists deterministic_facts_json clob");
        jdbc.execute("delete from ai_assistant_artifacts");
        jdbc.execute("delete from project_members");
        jdbc.execute("delete from platform_users");
        jdbc.execute("delete from projects");

        jdbc.update("insert into projects(id, active) values (1, true), (2, true)");
        jdbc.update("insert into platform_users(username, role, enabled) values"
            + " ('admin', 'ADMIN', true), ('editor1', 'EDITOR', true), ('editor2', 'EDITOR', true)");
        jdbc.update("insert into project_members(project_id, username, created_at) values (1, 'editor1', ?)",
            Timestamp.from(Instant.now()));
        jdbc.update("insert into project_members(project_id, username, created_at) values (2, 'editor2', ?)",
            Timestamp.from(Instant.now()));
        // 两个项目各有一条 run 产物（project 1 的由 editor1 使用）。
        jdbc.update("""
            insert into ai_assistant_artifacts(artifact_id, run_id, task_type, project_id, revision,
              acceptance_state, result_origin, payload_json, violations_json, source_manifest_json,
              created_by, reviewed_by, created_at, updated_at)
            values ('artifact-p1', 'run-p1', 'bug_analysis', 1, 1, 'PENDING_HUMAN_CONFIRMATION',
              'MODEL_GENERATED', '{"summary":"p1"}', '[]', '{}', 'editor1', '', ?, ?)
            """, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        jdbc.update("""
            insert into ai_assistant_artifacts(artifact_id, run_id, task_type, project_id, revision,
              acceptance_state, result_origin, payload_json, violations_json, source_manifest_json,
              created_by, reviewed_by, created_at, updated_at)
            values ('artifact-p2', 'run-p2', 'bug_analysis', 2, 1, 'PENDING_HUMAN_CONFIRMATION',
              'MODEL_GENERATED', '{"summary":"p2"}', '[]', '{}', 'editor2', '', ?, ?)
            """, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));

        authorization = new AiInvocationAuthorization(jdbc, new ProjectAccessService(jdbc), true);
        assistantService = new AiAssistantService(jdbc, objectMapper,
            mock(AiTaskExecutionFacade.class), mock(AiEvidenceManifestService.class),
            mock(AiContextBuilder.class), mock(AiRunEventService.class), authorization);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            username, "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private String messageOf(Runnable call) {
        try {
            call.run();
            return "NO-ERROR";
        } catch (ResponseStatusException failure) {
            return failure.getStatusCode().value() + " " + failure.getReason();
        }
    }

    @Test
    void membersAndAdminsFollowProjectBoundaries() {
        authenticate("editor1", "EDITOR");
        assertThatCode(() -> authorization.requireProjectAccess(1L)).doesNotThrowAnyException();
        assertThat(assistantService.getRun("run-p1").runId()).isEqualTo("run-p1");

        authenticate("admin", "ADMIN");
        assertThatCode(() -> authorization.requireProjectAccess(1L)).doesNotThrowAnyException();
        assertThatCode(() -> authorization.requireProjectAccess(2L)).doesNotThrowAnyException();
        assertThat(assistantService.getRun("run-p2").runId()).isEqualTo("run-p2");
    }

    @Test
    void aMissingRunAndAnotherProjectsRunAreIndistinguishable404s() {
        authenticate("editor2", "EDITOR");
        // 真实存在但属于项目 1 的 run。
        String forbidden = messageOf(() -> assistantService.getRun("run-p1"));
        // 完全猜测的 runId。
        String missing = messageOf(() -> assistantService.getRun("run-guess-does-not-exist"));
        // 两者形状完全一致：探测无法区分「存在但无权」与「不存在」。
        assertThat(forbidden).isEqualTo("404 not_found");
        assertThat(missing).isEqualTo(forbidden);
    }

    @Test
    void crossProjectArtifactReviewIsRejectedBeforeAnyStateChange() {
        authenticate("editor2", "EDITOR");
        String rejected = messageOf(() -> assistantService.review("artifact-p1", "reject", 1, null));
        assertThat(rejected).isEqualTo("404 not_found");
        // 状态未被改动（仍然是待人工确认）。
        assertThat(jdbc.queryForObject(
            "select acceptance_state from ai_assistant_artifacts where artifact_id = 'artifact-p1'",
            String.class)).isEqualTo("PENDING_HUMAN_CONFIRMATION");

        // 本项目的工件可以正常评审（对照正例）。
        authenticate("editor1", "EDITOR");
        AiAssistantService.AssistantRun reviewed =
            assistantService.review("artifact-p1", "reject", 1, null);
        assertThat(reviewed.acceptanceState()).isEqualTo(AiAssistantService.STATE_REJECTED);
    }

    @Test
    void revocationTakesEffectBeforeTheNextNodeEvenWithoutHttpSession() {
        // 模拟后台节点：没有 SecurityContext，按 run 的 actor 显式复核。
        SecurityContextHolder.clearContext();
        assertThatCode(() -> authorization.requireProjectAccessAs("editor1", 1L)).doesNotThrowAnyException();

        // 撤销成员关系（撤权），下一个节点执行前复核必须失败——不需要 HTTP session。
        jdbc.update("delete from project_members where project_id = 1 and username = 'editor1'");
        assertThatThrownBy(() -> authorization.requireProjectAccessAs("editor1", 1L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("404 NOT_FOUND");
        assertThat(authorization.isProjectMember("editor1", 1L)).isFalse();

        // 停用账号同样立即生效。
        jdbc.update("insert into project_members(project_id, username, created_at) values (1, 'editor1', ?)",
            Timestamp.from(Instant.now()));
        jdbc.update("update platform_users set enabled = false where username = 'editor1'");
        assertThatThrownBy(() -> authorization.requireProjectAccessAs("editor1", 1L))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void anonymousAccessAndUnknownActorsNeverLeakAndAreRejected() {
        SecurityContextHolder.clearContext();
        // 未登录：HTTP 路径拒绝且不泄漏。
        assertThatThrownBy(() -> authorization.requireProjectAccess(1L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("404 NOT_FOUND");
        // 未知 actor：与不存在同形。
        assertThatThrownBy(() -> authorization.requireProjectAccessAs("ghost", 1L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("404 NOT_FOUND");
        assertThat(authorization.describe()).containsEntry("uniformShape", "404 not_found");
    }
}
