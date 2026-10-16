package com.haitlify.aitest.platform.ai.agentic.locator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import tools.jackson.databind.ObjectMapper;

import com.haitlify.aitest.platform.webautomation.WebAutomationLocatorDiscoveryService;

class LocatorRepairWorkflowTest {

    @org.junit.jupiter.api.BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("reviewer", "n/a", List.of()));
    }

    @Test
    void rejectsRepairProposalForFailureCategoryOutsideStableAllowlist() {
        LocatorKnowledgeService service = new LocatorKnowledgeService(newStore("repair-category"), mock(WebAutomationLocatorDiscoveryService.class), new ObjectMapper(), mock(com.haitlify.aitest.platform.auth.ProjectAccessService.class));

        assertThatThrownBy(() -> service.createRepairProposal(new CreateLocatorRepairProposalRequest(
            "entry-1", "button=搜索", "business_assertion_failed", "fp-old", "fp-new", 90, "wrong target", true
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("failure category");
    }

    @Test
    void forgedLegacyBooleanCannotCreateProposalWithoutServerVerification() {
        JdbcTemplate jdbc = newStore("repair-forged-bool");
        insertEntry(jdbc, "entry-1", "button=查询", "fp-old");
        LocatorKnowledgeService service = new LocatorKnowledgeService(jdbc, mock(WebAutomationLocatorDiscoveryService.class), new ObjectMapper(), mock(com.haitlify.aitest.platform.auth.ProjectAccessService.class));

        assertThatThrownBy(() -> service.createRepairProposal(new CreateLocatorRepairProposalRequest(
            "entry-1", "button=搜索", "locator_not_found", "fp-old", "fp-new", 90, "forged", true
        ))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("verificationId");
    }

    @Test
    void expiredVerificationCannotCreateProposal() {
        JdbcTemplate jdbc = newStore("repair-expired");
        insertEntry(jdbc, "entry-1", "button=查询", "fp-old");
        insertVerification(jdbc, "entry-1", "button=搜索", "verification-expired");
        jdbc.update("update ai_locator_repair_verifications set expires_at = ? where verification_id = ?", Timestamp.from(Instant.now().minusSeconds(1)), "verification-expired");
        LocatorKnowledgeService service = new LocatorKnowledgeService(jdbc, mock(WebAutomationLocatorDiscoveryService.class), new ObjectMapper(), mock(com.haitlify.aitest.platform.auth.ProjectAccessService.class));

        assertThatThrownBy(() -> service.createRepairProposal(new CreateLocatorRepairProposalRequest(
            "entry-1", "button=搜索", "locator_not_found", "fp-old", "fp-new", 90, "expired", true, "verification-expired", null, null, null
        ))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("invalid");
    }

    @Test
    void usedVerificationCannotCreateAnotherProposal() {
        JdbcTemplate jdbc = newStore("repair-used");
        insertEntry(jdbc, "entry-1", "button=查询", "fp-old");
        insertVerification(jdbc, "entry-1", "button=搜索", "verification-used");
        jdbc.update("update ai_locator_repair_verifications set used_at = ? where verification_id = ?", Timestamp.from(Instant.now()), "verification-used");
        LocatorKnowledgeService service = new LocatorKnowledgeService(jdbc, mock(WebAutomationLocatorDiscoveryService.class), new ObjectMapper(), mock(com.haitlify.aitest.platform.auth.ProjectAccessService.class));

        assertThatThrownBy(() -> service.createRepairProposal(new CreateLocatorRepairProposalRequest(
            "entry-1", "button=搜索", "locator_not_found", "fp-old", "fp-new", 90, "used", true, "verification-used", null, null, null
        ))).isInstanceOf(org.springframework.dao.EmptyResultDataAccessException.class);
    }

    @Test
    void reviewClosureIsConditionallySingleWinner() {
        JdbcTemplate jdbc = newStore("repair-conditional-close");
        insertEntry(jdbc, "entry-1", "button=查询", "fp-old");
        insertVerification(jdbc, "entry-1", "button=搜索", "verification-close");
        LocatorKnowledgeService service = new LocatorKnowledgeService(jdbc, mock(WebAutomationLocatorDiscoveryService.class), new ObjectMapper(), mock(com.haitlify.aitest.platform.auth.ProjectAccessService.class));
        var proposal = service.createRepairProposal(new CreateLocatorRepairProposalRequest(
        "entry-1", "button=搜索", "locator_not_found", "fp-old", "fp-new", 90, "close once", true, "verification-close", null, null, null
        ));

        service.rejectRepairProposal(proposal.proposalId(), new ReviewLocatorRepairProposalRequest("first"));
        assertThatThrownBy(() -> service.acceptRepairProposal(proposal.proposalId(), new ReviewLocatorRepairProposalRequest("second")))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("pending_review");
        assertThat(jdbc.queryForObject("select count(*) from ai_locator_repair_audit where proposal_id = ?", Integer.class, proposal.proposalId())).isEqualTo(1);
        }

    @Test
    void persistsPendingProposalAndOnlyAcceptUpdatesKnowledgeWithRevisionAndAudit() {
        JdbcTemplate jdbc = newStore("repair-accept");
        insertEntry(jdbc, "entry-1", "button=查询", "fp-old");
        insertVerification(jdbc, "entry-1", "button=搜索", "verification-1");
        LocatorKnowledgeService service = new LocatorKnowledgeService(jdbc, mock(WebAutomationLocatorDiscoveryService.class), new ObjectMapper(), mock(com.haitlify.aitest.platform.auth.ProjectAccessService.class));

        LocatorKnowledgeService.LocatorRevisionProposal proposal = service.createRepairProposal(new CreateLocatorRepairProposalRequest(
            "entry-1", "button=搜索", "locator_not_found", "fp-old", "fp-new", 92, "旧定位器失效", true, "verification-1", null, null, null
        ));

        assertThat(proposal.status()).isEqualTo("pending_review");
        assertThat(proposal.revision()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select status from ai_locator_repair_proposals where proposal_id = ?", String.class, proposal.proposalId()))
            .isEqualTo("pending_review");
        assertThat(jdbc.queryForObject("select locator from ai_locator_knowledge_entries where entry_id = 'entry-1'", String.class))
            .isEqualTo("button=查询");

        LocatorKnowledgeService.LocatorRevisionProposal accepted = service.acceptRepairProposal(
            proposal.proposalId(), new ReviewLocatorRepairProposalRequest("reviewer-1", "Reviewer", "确认候选")
        );

        assertThat(accepted.status()).isEqualTo("accepted");
        assertThat(accepted.revision()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select locator from ai_locator_knowledge_entries where entry_id = 'entry-1'", String.class))
            .isEqualTo("button=搜索");
        assertThat(jdbc.queryForObject("select evidence_version from ai_locator_knowledge_entries where entry_id = 'entry-1'", Integer.class))
            .isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from ai_locator_knowledge_history where entry_id = 'entry-1' and changed_reason like 'repair_accept%'", Integer.class))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from ai_locator_repair_audit where proposal_id = ? and action_type = 'accept'", Integer.class, proposal.proposalId()))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject("select reviewed_by from ai_locator_repair_proposals where proposal_id = ?", String.class, proposal.proposalId()))
            .isEqualTo("reviewer");
        assertThat(jdbc.queryForObject("select reviewed_name from ai_locator_repair_proposals where proposal_id = ?", String.class, proposal.proposalId()))
            .isEqualTo("reviewer");

        assertThatThrownBy(() -> service.acceptRepairProposal(proposal.proposalId(), new ReviewLocatorRepairProposalRequest("attacker", "Attacker", "retry")))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("pending_review");
    }

    @Test
    void verifiesCandidateAgainstRealBrowserEvidenceBeforeProposal() {
        JdbcTemplate jdbc = newStore("repair-verification");
        insertEntry(jdbc, "entry-1", "button=查询", "fp-old");
        WebAutomationLocatorDiscoveryService discovery = mock(WebAutomationLocatorDiscoveryService.class);
        when(discovery.discover(any())).thenReturn(new WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse(
            "https://test.example.com/#/log",
            List.of(new WebAutomationLocatorDiscoveryService.PageLocatorEvidence(
                "candidate", "button=搜索", "搜索", "button", "button", "submit", "browser", true, true, true, 1))
        ));
        LocatorKnowledgeService service = new LocatorKnowledgeService(jdbc, discovery, new ObjectMapper(), mock(com.haitlify.aitest.platform.auth.ProjectAccessService.class));

        LocatorKnowledgeService.LocatorRepairCandidateVerification verification = service.verifyRepairCandidate(
            "entry-1", "button=搜索", new WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryRequest(
                "https://test.example.com/#/log", "https://test.example.com", "chromium", null, true,
                10_000, "domcontentloaded", null, null, null, null, List.of()
            )
        );

        assertThat(verification.verified()).isTrue();
        assertThat(verification.pageFingerprintAfter()).startsWith("sha256:");
        assertThat(verification.failureCategory()).isNull();
    }

    @Test
    void candidateVerificationRejectsWrongPageAndNonActionableEvidence() {
        JdbcTemplate jdbc = newStore("repair-page-state");
        insertEntry(jdbc, "entry-1", "button=查询", "fp-old");
        WebAutomationLocatorDiscoveryService discovery = mock(WebAutomationLocatorDiscoveryService.class);
        when(discovery.discover(any())).thenReturn(new WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse(
            "https://test.example.com/#/other",
            List.of(new WebAutomationLocatorDiscoveryService.PageLocatorEvidence(
                "candidate", "button=搜索", "搜索", "button", "button", "submit", "browser", true, false, true, 1))
        ));
        LocatorKnowledgeService service = new LocatorKnowledgeService(jdbc, discovery, new ObjectMapper(), mock(com.haitlify.aitest.platform.auth.ProjectAccessService.class));

        var verification = service.verifyRepairCandidate("entry-1", "button=搜索", "point-1", "https://test.example.com/#/log",
            new WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryRequest(
                "https://test.example.com/#/other", "https://test.example.com", "chromium", null, true, 10_000, "domcontentloaded", null, null, null, null, null, List.of(), null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));

        assertThat(verification.verified()).isFalse();
        assertThat(verification.failureCategory()).isEqualTo("locator_not_actionable");
        assertThat(jdbc.queryForObject("select case when correct_page then 1 else 0 end from ai_locator_repair_verifications where verification_id = ?", Integer.class, verification.verificationId())).isZero();
    }

    @Test
    void proposalRejectsEnvironmentMismatch() {
        JdbcTemplate jdbc = newStore("repair-source-mismatch");
        insertEntry(jdbc, "entry-1", "button=查询", "fp-old");
        insertVerification(jdbc, "entry-1", "button=搜索", "verification-environment");
        LocatorKnowledgeService service = new LocatorKnowledgeService(jdbc, mock(WebAutomationLocatorDiscoveryService.class), new ObjectMapper(), mock(com.haitlify.aitest.platform.auth.ProjectAccessService.class));

        assertThatThrownBy(() -> service.createRepairProposal(new CreateLocatorRepairProposalRequest(
            "entry-1", "button=搜索", "locator_not_found", "fp-old", "fp-new", 90, "environment", true,
            "verification-environment", null, null, "environment-2")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("invalid");
    }

    @Test
    void ambiguousMatchCountVerificationCannotCreateProposal() {
        JdbcTemplate jdbc = newStore("repair-ambiguous-create");
        insertEntry(jdbc, "entry-1", "button=查询", "fp-old");
        insertVerification(jdbc, "entry-1", "button=搜索", "verification-ambiguous");
        jdbc.update("update ai_locator_repair_verifications set matched_element_count = 2 where verification_id = ?", "verification-ambiguous");
        LocatorKnowledgeService service = new LocatorKnowledgeService(jdbc, mock(WebAutomationLocatorDiscoveryService.class), new ObjectMapper(), mock(com.haitlify.aitest.platform.auth.ProjectAccessService.class));

        // 复核方第十九轮 A2.21：多元素命中（matchedElementCount≠1）时四个布尔列仍是 true，
        // 唯一性必须由落库的计数维度拦截 —— 失败复核不得创建提案。
        assertThatThrownBy(() -> service.createRepairProposal(new CreateLocatorRepairProposalRequest(
            "entry-1", "button=搜索", "locator_not_found", "fp-old", "fp-new", 90, "ambiguous", true, "verification-ambiguous", null, null, null
        ))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("invalid");
    }

    @Test
    void ambiguousMatchCountVerificationCannotAcceptProposalOrRewriteEntry() {
        JdbcTemplate jdbc = newStore("repair-ambiguous-accept");
        insertEntry(jdbc, "entry-1", "button=查询", "fp-old");
        insertVerification(jdbc, "entry-1", "button=搜索", "verification-late-ambiguous");
        LocatorKnowledgeService service = new LocatorKnowledgeService(jdbc, mock(WebAutomationLocatorDiscoveryService.class), new ObjectMapper(), mock(com.haitlify.aitest.platform.auth.ProjectAccessService.class));
        var proposal = service.createRepairProposal(new CreateLocatorRepairProposalRequest(
            "entry-1", "button=搜索", "locator_not_found", "fp-old", "fp-new", 90, "created while unique", true, "verification-late-ambiguous", null, null, null
        ));

        // 复核后唯一性失效（这是评审轮实测的破口形状）：接受必须被拒绝，知识库条目不得被改写。
        jdbc.update("update ai_locator_repair_verifications set matched_element_count = 2 where verification_id = ?", "verification-late-ambiguous");
        assertThatThrownBy(() -> service.acceptRepairProposal(proposal.proposalId(), new ReviewLocatorRepairProposalRequest("reviewer-1", "Reviewer", "晚到确认")))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("no longer valid");
        assertThat(jdbc.queryForObject("select locator from ai_locator_knowledge_entries where entry_id = 'entry-1'", String.class))
            .isEqualTo("button=查询");
    }

    @Test
    void lateHumanConfirmationAfterStoredVerificationWindowSucceeds() {
        JdbcTemplate jdbc = newStore("repair-late-accept");
        insertEntry(jdbc, "entry-1", "button=查询", "fp-old");
        insertVerification(jdbc, "entry-1", "button=搜索", "verification-late");
        LocatorKnowledgeService service = new LocatorKnowledgeService(jdbc, mock(WebAutomationLocatorDiscoveryService.class), new ObjectMapper(), mock(com.haitlify.aitest.platform.auth.ProjectAccessService.class));
        var proposal = service.createRepairProposal(new CreateLocatorRepairProposalRequest(
            "entry-1", "button=搜索", "locator_not_found", "fp-old", "fp-new", 92, "窗口内创建", true, "verification-late", null, null, null
        ));

        // 复核方第十九轮 A4.17：过期时间只约束提案创建；人工确认可能晚于 300s 窗口到达，accept 不得复检 TTL。
        jdbc.update("update ai_locator_repair_verifications set expires_at = ? where verification_id = ?", Timestamp.from(Instant.now().minusSeconds(60)), "verification-late");
        var accepted = service.acceptRepairProposal(proposal.proposalId(), new ReviewLocatorRepairProposalRequest("reviewer-1", "Reviewer", "晚到的人工确认"));

        assertThat(accepted.status()).isEqualTo("accepted");
        assertThat(jdbc.queryForObject("select locator from ai_locator_knowledge_entries where entry_id = 'entry-1'", String.class))
            .isEqualTo("button=搜索");
    }

    private JdbcTemplate newStore(String name) {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            "jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("""
            create table ai_locator_knowledge_entries (
              entry_id varchar(128) primary key, project_id bigint not null,
              environment_id varchar(128) not null default '', module_name varchar(255) not null default '',
              page_name varchar(255) not null default '', page_url varchar(2048) not null default '',
              page_fingerprint varchar(128) not null default '', natural_name varchar(512) not null default '',
              locator varchar(1024) not null, role_name varchar(128) not null default '',
              accessible_name varchar(512) not null default '', element_type varchar(128) not null default '',
              source varchar(64) not null default 'browser', status varchar(32) not null default 'active',
              evidence_version integer not null default 1, collected_at timestamp not null,
              last_verified_at timestamp null, metadata_json text, created_at timestamp not null,
              updated_at timestamp not null, hit_count integer not null default 0,
              execution_success_count integer not null default 0, execution_failure_count integer not null default 0,
              confidence_score integer not null default 35, confidence_locked boolean not null default false,
              last_hit_at timestamp null
            )
        """);
        jdbc.execute("create table projects (id bigint primary key, active boolean not null)");
        jdbc.execute("create table platform_users (username varchar(64) primary key, role varchar(32) not null, enabled boolean not null)");
        jdbc.execute("create table project_members (id bigint generated by default as identity primary key, project_id bigint not null, username varchar(64) not null, created_at timestamp not null)");
        jdbc.update("insert into projects(id, active) values (1, true)");
        jdbc.update("insert into platform_users(username, role, enabled) values ('reviewer', 'EDITOR', true)");
        jdbc.update("insert into project_members(project_id, username, created_at) values (1, 'reviewer', current_timestamp())");
        jdbc.execute("create table ai_locator_knowledge_history (history_id varchar(128) primary key, entry_id varchar(128) not null, snapshot_json text not null, changed_reason varchar(255) not null, revision integer not null default 1, created_at timestamp not null)");
        jdbc.execute("create table ai_locator_repair_verifications (verification_id varchar(128) primary key, entry_id varchar(128) not null, project_id bigint not null, environment_id varchar(128) not null, source_point_id varchar(128) not null, page_key varchar(255) not null, page_fingerprint varchar(128) not null, page_url varchar(2048) not null, candidate_locator varchar(1024) not null, owner_id varchar(128) not null, expires_at timestamp not null, verified_at timestamp not null, used_at timestamp null, visible boolean not null, enabled boolean not null, receives_events boolean not null, correct_page boolean not null, matched_element_count integer not null default 1, created_at timestamp not null)");
        jdbc.execute("create table ai_locator_repair_proposals (proposal_id varchar(128) primary key, entry_id varchar(128) not null, project_id bigint not null, failure_category varchar(64) not null, old_locator varchar(1024) not null, new_locator varchar(1024) not null, page_fingerprint_before varchar(128) not null, page_fingerprint_after varchar(128) not null, confidence integer not null, reason varchar(1024) not null, verification_json text not null, revision integer not null, status varchar(32) not null, verification_id varchar(128), reviewed_by varchar(128), reviewed_name varchar(255), review_comment varchar(1024), reviewed_at timestamp, created_at timestamp not null, updated_at timestamp not null)");
        jdbc.execute("create table ai_locator_repair_audit (audit_id varchar(128) primary key, proposal_id varchar(128) not null, action_type varchar(32) not null, before_json text, after_json text, actor_id varchar(128) not null, actor_name varchar(255) not null, comment varchar(1024) not null, created_at timestamp not null)");
        return jdbc;
    }

    private void insertVerification(JdbcTemplate jdbc, String entryId, String locator, String verificationId) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into ai_locator_repair_verifications values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", verificationId, entryId, 1L, "", "", "", "fp-new", "https://test.example.com/#/log", locator, "reviewer", Timestamp.from(Instant.now().plusSeconds(300)), now, null, true, true, true, true, 1, now);
    }

    private void insertEntry(JdbcTemplate jdbc, String id, String locator, String fingerprint) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into ai_locator_knowledge_entries(entry_id, project_id, page_url, page_fingerprint, locator, natural_name, status, evidence_version, collected_at, created_at, updated_at) values (?, 1, ?, ?, ?, '查询', 'active', 1, ?, ?, ?)", id, "https://test.example.com/#/log", fingerprint, locator, now, now, now);
        jdbc.update("insert into ai_locator_knowledge_history(history_id, entry_id, snapshot_json, changed_reason, revision, created_at) values (?, ?, ?, 'manual_create', 1, ?)", "history-" + id, id, "{}", now);
    }
}
