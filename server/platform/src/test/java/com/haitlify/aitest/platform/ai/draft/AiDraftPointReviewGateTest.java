package com.haitlify.aitest.platform.ai.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

/**
 * 全链路人工确认 Exit Gate（draft test point）：真实认证 actor、项目权限（无权与不存在同形 404）、
 * expectedRevision（过期 409）、允许的前置状态、重复确认幂等、并发单写者、修改保留 parent 与双审计。
 * 本类以 securityEnabled=true + 真实成员表驱动真实 ProjectAccessService（非 mock）。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "app.security.enabled=true")
class AiDraftPointReviewGateTest {

    @Autowired
    private AiDraftTestPointService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long otherProjectId;
    private static final String POINT_ID = "gate-point-1";

    @BeforeEach
    void seed() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("gate-editor", "n/a", List.of()));
        jdbcTemplate.update("delete from ai_review_actions");
        jdbcTemplate.update("delete from ai_draft_test_points");
        jdbcTemplate.update("delete from ai_analysis_runs where run_id like 'gate-run%'");
        jdbcTemplate.update("delete from project_members where username = 'gate-editor'");
        jdbcTemplate.update("delete from platform_users where username = 'gate-editor'");
        jdbcTemplate.update("delete from projects where code in ('GATE_P1', 'GATE_P2')");

        Instant now = Instant.now();
        jdbcTemplate.update("insert into projects(code, name, description, active, created_at, updated_at)"
            + " values ('GATE_P1', '门禁一', '', true, ?, ?)", Timestamp.from(now), Timestamp.from(now));
        jdbcTemplate.update("insert into projects(code, name, description, active, created_at, updated_at)"
            + " values ('GATE_P2', '门禁二', '', true, ?, ?)", Timestamp.from(now), Timestamp.from(now));
        projectId = jdbcTemplate.queryForObject("select id from projects where code = 'GATE_P1'", Long.class);
        otherProjectId = jdbcTemplate.queryForObject("select id from projects where code = 'GATE_P2'", Long.class);
        jdbcTemplate.update("insert into platform_users(username, password_hash, role, enabled, created_at, updated_at)"
            + " values ('gate-editor', 'x', 'EDITOR', true, ?, ?)", Timestamp.from(now), Timestamp.from(now));
        jdbcTemplate.update("insert into project_members(project_id, username, created_at) values (?, 'gate-editor', ?)",
            projectId, Timestamp.from(now));

        jdbcTemplate.update("insert into ai_analysis_runs(run_id, project_id, created_at, updated_at)"
            + " values ('gate-run-1', ?, ?, ?)", projectId, Timestamp.from(now), Timestamp.from(now));
        long runPk = jdbcTemplate.queryForObject(
            "select id from ai_analysis_runs where run_id = 'gate-run-1'", Long.class);
        jdbcTemplate.update("insert into ai_draft_test_points(point_id, run_id, project_id, title, created_at, updated_at)"
            + " values (?, ?, ?, '门禁点', ?, ?)", POINT_ID, runPk, projectId,
            Timestamp.from(now), Timestamp.from(now));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private int auditCount(String actionType) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from ai_review_actions where action_type = ?", Integer.class, actionType);
        return count == null ? 0 : count;
    }

    private String statusOf(String pointId) {
        return jdbcTemplate.queryForObject(
            "select review_status from ai_draft_test_points where point_id = ?", String.class, pointId);
    }

    @Test
    void anonymousConfirmationIsRejected() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> service.confirm(POINT_ID, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("actor required");
        // 匿名令牌（过滤器实际注入的形态）同样必须被拒绝
        SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.AnonymousAuthenticationToken(
                "key", "anonymousUser", org.springframework.security.core.authority.AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        assertThatThrownBy(() -> service.confirm(POINT_ID, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("actor required");
        assertThat(statusOf(POINT_ID)).isEqualTo("ai_generated");
        assertThat(auditCount("confirm")).isZero();
    }

    @Test
    void crossProjectAndUnknownIdsAnswerTheSameUniformNotFound() {
        // editor 不是 GATE_P2 成员：把点挪到 P2 ⇒ 拒绝必须与“不存在”同形（404 not_found）
        jdbcTemplate.update("update ai_draft_test_points set project_id = ? where point_id = ?",
            otherProjectId, POINT_ID);
        assertThatThrownBy(() -> service.confirm(POINT_ID, null))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("not_found");
        assertThatThrownBy(() -> service.confirm("gate-point-guess", null))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("not_found");
        assertThat(statusOf(POINT_ID)).isEqualTo("ai_generated");
    }

    @Test
    void staleRevisionIsA409ConflictAndEditProducesANewParentedRevision() {
        assertThatThrownBy(() -> service.editConfirm(POINT_ID,
                new EditDraftPointRequest("改", null, null, null, "r99", 99)))
            .isInstanceOf(com.haitlify.aitest.platform.ai.agentic.StaleArtifactRevisionException.class)
            .hasMessageContaining("stale");

        AiDraftTestPointResponse edited = service.editConfirm(POINT_ID,
            new EditDraftPointRequest("门禁点（修订）", null, null, null, "首轮修订", 1));

        assertThat(edited.pointId()).isEqualTo(POINT_ID + "-r2");
        assertThat(edited.revision()).isEqualTo(2);
        assertThat(edited.parentPointId()).isEqualTo(POINT_ID);
        assertThat(edited.reviewStatus()).isEqualTo("modified");
        assertThat(edited.title()).isEqualTo("门禁点（修订）");
        // 旧版不可变并标记 superseded；两笔审计（supersede + edit_confirm）
        assertThat(statusOf(POINT_ID)).isEqualTo("superseded");
        assertThat(auditCount("supersede")).isEqualTo(1);
        assertThat(auditCount("edit_confirm")).isEqualTo(1);
    }

    @Test
    void confirmIsIdempotentAndRejectBlocksConfirmation() {
        AiDraftTestPointResponse confirmed = service.confirm(POINT_ID, new ReviewDraftPointRequest("通过", 1));
        assertThat(confirmed.reviewStatus()).isEqualTo("confirmed");
        assertThat(confirmed.acceptanceState()).isEqualTo("ACCEPTED");
        assertThat(auditCount("confirm")).isEqualTo(1);

        // 重复确认：幂等返回，不再写审计
        AiDraftTestPointResponse again = service.confirm(POINT_ID, new ReviewDraftPointRequest("又点一次", 1));
        assertThat(again.reviewStatus()).isEqualTo("confirmed");
        assertThat(auditCount("confirm")).isEqualTo(1);

        // 已确认的点不可驳回（拒绝必须阻止下游、也不得悄悄撤回已接受）
        assertThatThrownBy(() -> service.reject(POINT_ID, new ReviewDraftPointRequest("反悔", 1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("review_state_conflict");
    }

    @Test
    void rejectThenConfirmIsABlockingConflictAndRejectIsIdempotent() {
        service.reject(POINT_ID, new ReviewDraftPointRequest("证据不足", 1));
        assertThat(statusOf(POINT_ID)).isEqualTo("rejected");
        assertThat(auditCount("reject")).isEqualTo(1);

        service.reject(POINT_ID, new ReviewDraftPointRequest("再来一次", 1));
        assertThat(auditCount("reject")).isEqualTo(1);

        assertThatThrownBy(() -> service.confirm(POINT_ID, new ReviewDraftPointRequest("仍然确认", 1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("review_state_conflict");
    }

    @Test
    void concurrentConfirmProducesExactlyOneWriter() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = pool.submit(() -> {
                SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("gate-editor", "n/a", List.of()));
                try {
                    start.await();
                    return service.confirm(POINT_ID, new ReviewDraftPointRequest("并发一", 1));
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            Future<Object> second = pool.submit(() -> {
                SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("gate-editor", "n/a", List.of()));
                try {
                    start.await();
                    return service.confirm(POINT_ID, new ReviewDraftPointRequest("并发二", 1));
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            start.countDown();
            Object resultA = null;
            Object resultB = null;
            try {
                resultA = first.get();
            } catch (Exception ignored) {
            }
            try {
                resultB = second.get();
            } catch (Exception ignored) {
            }
            // 至少一方成功（另一方幂等返回或按并发后状态裁决），最终恰一笔确认审计、终态 confirmed
            assertThat(resultA != null || resultB != null).isTrue();
            assertThat(statusOf(POINT_ID)).isEqualTo("confirmed");
            assertThat(auditCount("confirm")).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
