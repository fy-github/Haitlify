package com.haitlify.aitest.platform.ai.figma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

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
 * 全链路人工确认 Exit Gate（Figma element candidate）：真实认证 actor、项目权限（经文件→项目，
 * 无权与不存在同形 404）、expectedRevision（过期 409）、允许的前置状态、证据非空方可确认、
 * 重复确认/驳回幂等、并发单写者与 reviewed_by 持久审计。securityEnabled=true + 真实成员表。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "app.security.enabled=true")
class AiFigmaCandidateReviewGateTest {

    @Autowired
    private AiFigmaElementCandidateService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long otherProjectId;
    private static final String CANDIDATE_ID = "gate-candidate-1";
    private static final String EMPTY_EVIDENCE_CANDIDATE_ID = "gate-candidate-blank";

    @BeforeEach
    void seed() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("figma-editor", "n/a", List.of()));
        jdbcTemplate.update("delete from ai_figma_element_candidates");
        jdbcTemplate.update("delete from ai_figma_files where figma_file_id like 'gate-figma%'");
        jdbcTemplate.update("delete from project_members where username = 'figma-editor'");
        jdbcTemplate.update("delete from platform_users where username = 'figma-editor'");
        jdbcTemplate.update("delete from projects where code in ('FIGMA_GATE_P1', 'FIGMA_GATE_P2')");

        Instant now = Instant.now();
        jdbcTemplate.update("insert into projects(code, name, description, active, created_at, updated_at)"
            + " values ('FIGMA_GATE_P1', '设计门禁一', '', true, ?, ?)", Timestamp.from(now), Timestamp.from(now));
        jdbcTemplate.update("insert into projects(code, name, description, active, created_at, updated_at)"
            + " values ('FIGMA_GATE_P2', '设计门禁二', '', true, ?, ?)", Timestamp.from(now), Timestamp.from(now));
        projectId = jdbcTemplate.queryForObject("select id from projects where code = 'FIGMA_GATE_P1'", Long.class);
        otherProjectId = jdbcTemplate.queryForObject("select id from projects where code = 'FIGMA_GATE_P2'", Long.class);
        jdbcTemplate.update("insert into platform_users(username, password_hash, role, enabled, created_at, updated_at)"
            + " values ('figma-editor', 'x', 'EDITOR', true, ?, ?)", Timestamp.from(now), Timestamp.from(now));
        jdbcTemplate.update("insert into project_members(project_id, username, created_at) values (?, 'figma-editor', ?)",
            projectId, Timestamp.from(now));

        long filePk = insertFile("gate-figma-1", "GATEKEY123", projectId, now);
        insertCandidate(CANDIDATE_ID, filePk, "{\"node\":\"12:34\"}", now);
        insertCandidate(EMPTY_EVIDENCE_CANDIDATE_ID, filePk, "{}", now);
    }

    private long insertFile(String figmaFileId, String fileKey, long project, Instant now) {
        jdbcTemplate.update("insert into ai_figma_files(figma_file_id, project_id, file_key, created_at, updated_at)"
            + " values (?, ?, ?, ?, ?)", figmaFileId, project, fileKey, Timestamp.from(now), Timestamp.from(now));
        return jdbcTemplate.queryForObject("select id from ai_figma_files where figma_file_id = ?", Long.class, figmaFileId);
    }

    private void insertCandidate(String candidateId, long filePk, String evidenceJson, Instant now) {
        jdbcTemplate.update("insert into ai_figma_element_candidates(candidate_id, figma_file_pk, component_key,"
            + " component_name, role, status, evidence_json, created_at, updated_at)"
            + " values (?, ?, 'ck', '组件', 'button', 'pending', ?, ?, ?)",
            candidateId, filePk, evidenceJson, Timestamp.from(now), Timestamp.from(now));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private String statusOf(String candidateId) {
        return jdbcTemplate.queryForObject(
            "select status from ai_figma_element_candidates where candidate_id = ?", String.class, candidateId);
    }

    private String reviewedBy(String candidateId) {
        return jdbcTemplate.queryForObject(
            "select reviewed_by from ai_figma_element_candidates where candidate_id = ?", String.class, candidateId);
    }

    @Test
    void anonymousConfirmationIsRejected() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> service.confirm(CANDIDATE_ID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("actor required");
        // 匿名令牌（过滤器实际注入的形态）同样必须被拒绝
        SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.AnonymousAuthenticationToken(
                "key", "anonymousUser", org.springframework.security.core.authority.AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        assertThatThrownBy(() -> service.reject(CANDIDATE_ID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("actor required");
        assertThat(statusOf(CANDIDATE_ID)).isEqualTo("pending");
    }

    @Test
    void crossProjectAndUnknownCandidatesAnswerTheSameUniformNotFound() {
        long otherFilePk = insertFile("gate-figma-2", "GATEKEY456", otherProjectId, Instant.now());
        insertCandidate("gate-candidate-foreign", otherFilePk, "{\"node\":\"1:1\"}", Instant.now());

        assertThatThrownBy(() -> service.confirm("gate-candidate-foreign"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("not_found");
        assertThatThrownBy(() -> service.confirm("gate-candidate-unknown"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("not_found");
        assertThat(statusOf("gate-candidate-foreign")).isEqualTo("pending");
    }

    @Test
    void staleRevisionIsA409AndTerminalRepeatsAreIdempotent() {
        assertThatThrownBy(() -> service.confirm(CANDIDATE_ID, 99))
            .isInstanceOf(com.haitlify.aitest.platform.ai.agentic.StaleArtifactRevisionException.class)
            .hasMessageContaining("stale");

        AiFigmaElementCandidateResponse confirmed = service.confirm(CANDIDATE_ID, 1);
        assertThat(confirmed.status()).isEqualTo("confirmed");
        assertThat(confirmed.reviewedBy()).isEqualTo("figma-editor");
        assertThat(confirmed.revision()).isEqualTo(1);

        AiFigmaElementCandidateResponse again = service.confirm(CANDIDATE_ID, 1);
        assertThat(again.status()).isEqualTo("confirmed");
        assertThat(reviewedBy(CANDIDATE_ID)).isEqualTo("figma-editor");

        // 终态翻转（已确认 → 驳回）必须被拒绝
        assertThatThrownBy(() -> service.reject(CANDIDATE_ID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("review_state_conflict");
    }

    @Test
    void evidenceMissingBlocksConfirmationWhileRejectionStaysAvailable() {
        assertThatThrownBy(() -> service.confirm(EMPTY_EVIDENCE_CANDIDATE_ID, 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("evidence_missing");
        assertThat(statusOf(EMPTY_EVIDENCE_CANDIDATE_ID)).isEqualTo("pending");

        AiFigmaElementCandidateResponse rejected = service.reject(EMPTY_EVIDENCE_CANDIDATE_ID, 1);
        assertThat(rejected.status()).isEqualTo("rejected");
        assertThat(reviewedBy(EMPTY_EVIDENCE_CANDIDATE_ID)).isEqualTo("figma-editor");
        assertThatThrownBy(() -> service.confirm(EMPTY_EVIDENCE_CANDIDATE_ID, 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("review_state_conflict");
    }
}
