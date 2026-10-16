package com.haitlify.aitest.platform.ai.agentic.locator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.haitlify.aitest.platform.webautomation.WebAutomationLocatorDiscoveryService;

class LocatorKnowledgeServiceTest {

    @org.junit.jupiter.api.BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("reviewer", "n/a", List.of()));
    }

    @Test
    void deniesLocatorReadToAuthenticatedNonMember() {
        JdbcTemplate jdbcTemplate = newKnowledgeStore("locator-knowledge-membership-denied");
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("outsider", "n/a", List.of()));
        LocatorKnowledgeService tested = new LocatorKnowledgeService(jdbcTemplate, mock(WebAutomationLocatorDiscoveryService.class));

        assertThatThrownBy(() -> tested.searchCandidates(1L, "env-1", "", "", "", null))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("403 FORBIDDEN");
    }
    @Test
    void rejectsUpdateThatTransfersEntryToAnotherProject() {
        JdbcTemplate jdbcTemplate = newKnowledgeStore("locator-knowledge-project-transfer");
        java.sql.Timestamp now = java.sql.Timestamp.from(java.time.Instant.now());
        jdbcTemplate.update("insert into projects(id, active) values (2, true)");
        jdbcTemplate.update("insert into ai_locator_knowledge_entries(entry_id, project_id, locator, status, evidence_version, collected_at, created_at, updated_at) values ('entry-transfer', 1, 'button=old', 'active', 1, ?, ?, ?)", now, now, now);
        LocatorKnowledgeService tested = new LocatorKnowledgeService(jdbcTemplate, mock(WebAutomationLocatorDiscoveryService.class), new ObjectMapper(), mock(com.haitlify.aitest.platform.auth.ProjectAccessService.class));
        SaveLocatorKnowledgeRequest request = new SaveLocatorKnowledgeRequest(2L, "", "", "", "", "", "button=new", "", "", "");

        assertThatThrownBy(() -> tested.update("entry-transfer", request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("转移项目");
        assertThat(jdbcTemplate.queryForObject("select project_id from ai_locator_knowledge_entries where entry_id = 'entry-transfer'", Long.class)).isEqualTo(1L);
    }

    @Test
    void recordAgentEvidenceSurfacesSafePersistenceFailureWithPageUrlAndCause() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        WebAutomationLocatorDiscoveryService discoveryService = mock(WebAutomationLocatorDiscoveryService.class);
        DataAccessResourceFailureException cause = new DataAccessResourceFailureException("database unavailable");
        when(jdbcTemplate.queryForObject(any(String.class), any(Class.class), any(), any(), any(), any(), any()))
            .thenThrow(cause);
        LocatorKnowledgeService tested = new LocatorKnowledgeService(jdbcTemplate, discoveryService, new ObjectMapper(), mock(com.haitlify.aitest.platform.auth.ProjectAccessService.class));
        var element = new WebAutomationLocatorDiscoveryService.PageLocatorEvidence(
            "e1", "role=button[name=查询]", "查询", "button", "button", "", "browser");

        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() -> tested.recordAgentEvidence(
            1L, "env-1", "日志管理", "日志页面",
            "https://test.example.com/logs?access_token=secret", List.of(element)));
        assertThat(failure)
            .isInstanceOf(WebLocatorKnowledgePersistenceException.class)
            .hasMessage("素材库持久化失败")
            .hasCause(cause);
        assertThat(((WebLocatorKnowledgePersistenceException) failure).pageUrl())
            .isEqualTo("https://test.example.com/logs")
            .doesNotContain("access_token", "secret");
    }

    @Test
    void markStaleSurfacesPersistenceMarker() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DataAccessResourceFailureException cause = new DataAccessResourceFailureException("stale write unavailable");
        org.mockito.Mockito.doThrow(cause).when(jdbcTemplate).update(any(String.class), any(Object[].class));
        LocatorKnowledgeService tested = new LocatorKnowledgeService(jdbcTemplate, mock(WebAutomationLocatorDiscoveryService.class), new ObjectMapper(), mock(com.haitlify.aitest.platform.auth.ProjectAccessService.class));

        assertThatThrownBy(() -> tested.markStale("entry-1"))
            .isInstanceOf(LocatorKnowledgePersistenceFailure.class)
            .hasCause(cause);
    }

    @Test
    void searchesSamePageFamilyWithoutExactPageNameKeyword() {
        WebAutomationLocatorDiscoveryService discoveryService = mock(WebAutomationLocatorDiscoveryService.class);
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:locator-knowledge-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("create table projects (id bigint primary key, active boolean not null)");
        jdbcTemplate.execute("create table platform_users (username varchar(64) primary key, role varchar(32) not null, enabled boolean not null)");
        jdbcTemplate.execute("create table project_members (id bigint generated by default as identity primary key, project_id bigint not null, username varchar(64) not null, created_at timestamp not null)");
        jdbcTemplate.update("insert into projects(id, active) values (1, true)");
        jdbcTemplate.update("insert into platform_users(username, role, enabled) values ('reviewer', 'EDITOR', true), ('outsider', 'EDITOR', true)");
        jdbcTemplate.update("insert into project_members(project_id, username, created_at) values (1, 'reviewer', current_timestamp())");
        jdbcTemplate.execute("""
            create table ai_locator_knowledge_entries (
              entry_id varchar(128) primary key,
              project_id bigint not null,
              environment_id varchar(128) not null default '',
              module_name varchar(255) not null default '',
              page_name varchar(255) not null default '',
              page_url varchar(1024) not null default '',
              page_fingerprint varchar(255) not null default '',
              natural_name varchar(512) not null default '',
              locator varchar(1024) not null,
              role_name varchar(128) not null default '',
              accessible_name varchar(512) not null default '',
              element_type varchar(128) not null default '',
              source varchar(64) not null default 'browser',
              status varchar(32) not null default 'active',
              evidence_version integer not null default 1,
              collected_at timestamp not null,
              last_verified_at timestamp null,
              metadata_json text not null default '{}',
              created_at timestamp not null,
              updated_at timestamp not null,
              confidence_score integer not null default 35
            )
            """);
        jdbcTemplate.update("""
            insert into ai_locator_knowledge_entries(
              entry_id, project_id, environment_id, module_name, page_name, page_url, page_fingerprint,
              natural_name, locator, role_name, accessible_name, element_type, source, status,
              evidence_version, collected_at, last_verified_at, metadata_json, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "locator-1", 1L, "env-1", "日志管理", "日志管理", "https://app-dev.example.test/#/log", "",
            "全部日志", "text=全部日志", "text", "全部日志", "text", "browser", "active",
            1, java.sql.Timestamp.from(java.time.Instant.now()), java.sql.Timestamp.from(java.time.Instant.now()),
            "{}", java.sql.Timestamp.from(java.time.Instant.now()), java.sql.Timestamp.from(java.time.Instant.now())
        );

        LocatorKnowledgeService tested = new LocatorKnowledgeService(jdbcTemplate, discoveryService, new ObjectMapper(), mock(com.haitlify.aitest.platform.auth.ProjectAccessService.class));

        List<WebAutomationLocatorDiscoveryService.PageLocatorEvidence> result =
            tested.searchEvidence(1L, "env-1", "日志管理", "日志管理页面", "");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).locator()).isEqualTo("text=全部日志");
    }

    @Test
    void recordAgentEvidenceStoresDeterministicStructureFingerprint() {
        WebAutomationLocatorDiscoveryService discoveryService = mock(WebAutomationLocatorDiscoveryService.class);
        JdbcTemplate jdbcTemplate = newKnowledgeStore("locator-knowledge-fingerprint");
        LocatorKnowledgeService tested = new LocatorKnowledgeService(jdbcTemplate, discoveryService, new ObjectMapper(), mock(com.haitlify.aitest.platform.auth.ProjectAccessService.class));

        List<WebAutomationLocatorDiscoveryService.PageLocatorEvidence> elements = List.of(
            new WebAutomationLocatorDiscoveryService.PageLocatorEvidence("e1", "text=全部日志", "全部日志", "text", "span", "text", "browser"),
            new WebAutomationLocatorDiscoveryService.PageLocatorEvidence("e2", "button=查询", "查询", "button", "button", "submit", "browser")
        );

        tested.recordAgentEvidence(1L, "env-1", "日志管理", "日志管理", "https://app-dev.example.test/#/log", elements);

        List<String> fingerprints = jdbcTemplate.queryForList(
            "select page_fingerprint from ai_locator_knowledge_entries where project_id = 1", String.class);
        assertThat(fingerprints).hasSize(2).allSatisfy(fp -> {
            assertThat(fp).startsWith("sha256:");
            assertThat(fp).hasSize("sha256:".length() + 64);
        });
        // 同页多个元素共享同一个页面指纹
        assertThat(fingerprints.get(0)).isEqualTo(fingerprints.get(1));
    }

    @Test
    void recordAgentEvidencePersistsAndUpdatesSafeExplorationKnowledge() throws Exception {
        WebAutomationLocatorDiscoveryService discoveryService = mock(WebAutomationLocatorDiscoveryService.class);
        JdbcTemplate jdbcTemplate = newKnowledgeStore("locator-knowledge-exploration-metadata");
        LocatorKnowledgeService tested = new LocatorKnowledgeService(jdbcTemplate, discoveryService, new ObjectMapper(), mock(com.haitlify.aitest.platform.auth.ProjectAccessService.class));
        WebAutomationLocatorDiscoveryService.PageLocatorEvidence element =
            new WebAutomationLocatorDiscoveryService.PageLocatorEvidence("e1", "text=查询", "查询", "button", "button", "", "browser");

        LocatorKnowledgeService.ExplorationKnowledge first = new LocatorKnowledgeService.ExplorationKnowledge(
            List.of("https://test.example.com/#/log", "#/log?tab=all", "click button=查询", "authToken=auth-secret", "access_token=access-secret", "apiKey=api-secret", "secretKey=secret-secret", "https://evil.example.com/steal?token=secret"),
            88, 2, "matched", null, "https://test.example.com/#/log?token=secret"
        );
        tested.recordAgentEvidence(1L, "env-1", "日志管理", "日志管理", "https://test.example.com/#/log", first, List.of(element));

        String raw = jdbcTemplate.queryForObject("select metadata_json from ai_locator_knowledge_entries", String.class);
        JsonNode metadata = new ObjectMapper().readTree(raw);
        assertThat(metadata.path("navigationTrail").toString()).contains("#/log", "click button=查询");
        assertThat(metadata.toString()).doesNotContain("evil.example.com", "token=secret", "auth-secret", "access-secret", "api-secret", "secret-secret", "password", "cookie", "headers");
        assertThat(metadata.path("navigationTrail").toString()).contains("#/log").doesNotContain("tab=all");
        assertThat(metadata.path("totalScore").asInt()).isEqualTo(88);
        assertThat(metadata.path("rounds").asInt()).isEqualTo(2);

        LocatorKnowledgeService.ExplorationKnowledge second = new LocatorKnowledgeService.ExplorationKnowledge(
            List.of("https://test.example.com/#/log", "click button=新增"), 95, 3, "matched", "sha256:fp2", null
        );
        tested.recordAgentEvidence(1L, "env-1", "日志管理", "日志管理", "https://test.example.com/#/log", List.of(element), second);
        String updated = jdbcTemplate.queryForObject("select metadata_json from ai_locator_knowledge_entries", String.class);
        JsonNode updatedMetadata = new ObjectMapper().readTree(updated);
        assertThat(updatedMetadata.path("totalScore").asInt()).isEqualTo(95);
        assertThat(updatedMetadata.path("rounds").asInt()).isEqualTo(3);
        assertThat(updatedMetadata.path("pageFingerprint").asText()).isEqualTo("sha256:fp2");
    }

    @Test
    void searchEvidenceOrdersEnvironmentVerificationAndConfidence() {
        WebAutomationLocatorDiscoveryService discoveryService = mock(WebAutomationLocatorDiscoveryService.class);
        JdbcTemplate jdbcTemplate = newKnowledgeStore("locator-knowledge-ranking");
        LocatorKnowledgeService tested = new LocatorKnowledgeService(jdbcTemplate, discoveryService, new ObjectMapper(), mock(com.haitlify.aitest.platform.auth.ProjectAccessService.class));
        java.sql.Timestamp now = java.sql.Timestamp.from(java.time.Instant.now());
        insertKnowledgeRow(jdbcTemplate, "low", "env-1", 20, now);
        insertKnowledgeRow(jdbcTemplate, "same-env-high", "env-1", 90, now);
        insertKnowledgeRow(jdbcTemplate, "other-env-higher", "env-2", 99, now);

        List<WebAutomationLocatorDiscoveryService.PageLocatorEvidence> result =
            tested.searchEvidence(1L, "env-1", "日志管理", "日志管理", "查询");

        assertThat(result).extracting(WebAutomationLocatorDiscoveryService.PageLocatorEvidence::id)
            .containsExactly("same-env-high", "low");
    }

    @Test
    void browserValidationAdjustsConfidenceOnlyAfterEligibleUiObservation() {
        WebAutomationLocatorDiscoveryService discoveryService = mock(WebAutomationLocatorDiscoveryService.class);
        JdbcTemplate jdbcTemplate = newKnowledgeStore("locator-knowledge-browser-validation");
        LocatorKnowledgeService tested = new LocatorKnowledgeService(jdbcTemplate, discoveryService, new ObjectMapper(), mock(com.haitlify.aitest.platform.auth.ProjectAccessService.class));
        java.sql.Timestamp now = java.sql.Timestamp.from(java.time.Instant.now());
        jdbcTemplate.update("insert into ai_locator_knowledge_entries(entry_id, project_id, locator, natural_name, status, evidence_version, collected_at, created_at, updated_at, confidence_score) values (?, 1, ?, ?, 'active', 1, ?, ?, ?, ?)", "matched", "testId=current", "当前按钮", now, now, now, 80);
        jdbcTemplate.update("insert into ai_locator_knowledge_entries(entry_id, project_id, locator, natural_name, status, evidence_version, collected_at, created_at, updated_at, confidence_score) values (?, 1, ?, ?, 'active', 1, ?, ?, ?, ?)", "rejected", "testId=missing", "旧按钮", now, now, now, 80);

        tested.recordBrowserValidation(List.of("matched", "rejected"), List.of("matched"), true);

        assertThat(jdbcTemplate.queryForObject("select execution_success_count from ai_locator_knowledge_entries where entry_id = 'matched'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select execution_failure_count from ai_locator_knowledge_entries where entry_id = 'rejected'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select confidence_score from ai_locator_knowledge_entries where entry_id = 'matched'", Integer.class)).isGreaterThan(80);
        assertThat(jdbcTemplate.queryForObject("select confidence_score from ai_locator_knowledge_entries where entry_id = 'rejected'", Integer.class)).isLessThan(80);

        tested.recordBrowserValidation(List.of("rejected"), List.of(), false);
        assertThat(jdbcTemplate.queryForObject("select execution_failure_count from ai_locator_knowledge_entries where entry_id = 'rejected'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select execution_success_count from ai_locator_knowledge_entries where entry_id = 'matched'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select execution_failure_count from ai_locator_knowledge_entries where entry_id = 'matched'", Integer.class)).isEqualTo(0);
        assertThat(jdbcTemplate.queryForObject("select confidence_score from ai_locator_knowledge_entries where entry_id = 'matched'", Integer.class)).isGreaterThan(80);
        assertThat(jdbcTemplate.queryForObject("select confidence_score from ai_locator_knowledge_entries where entry_id = 'rejected'", Integer.class)).isLessThan(80);
        assertThat(jdbcTemplate.queryForObject("select status from ai_locator_knowledge_entries where entry_id = 'matched'", String.class)).isEqualTo("active");
        assertThat(jdbcTemplate.queryForObject("select status from ai_locator_knowledge_entries where entry_id = 'rejected'", String.class)).isEqualTo("active");
    }

    @Test
    void browserValidationNotEligibleLeavesCandidateStateUntouched() {
        WebAutomationLocatorDiscoveryService discoveryService = mock(WebAutomationLocatorDiscoveryService.class);
        JdbcTemplate jdbcTemplate = newKnowledgeStore("locator-knowledge-validation-state");
        LocatorKnowledgeService tested = new LocatorKnowledgeService(jdbcTemplate, discoveryService, new ObjectMapper(), mock(com.haitlify.aitest.platform.auth.ProjectAccessService.class));
        java.sql.Timestamp now = java.sql.Timestamp.from(java.time.Instant.now());
        jdbcTemplate.update("insert into ai_locator_knowledge_entries(entry_id, project_id, locator, natural_name, status, evidence_version, collected_at, created_at, updated_at, execution_failure_count, confidence_score) values (?, 1, ?, ?, 'active', 1, ?, ?, ?, 2, 80)",
            "not-eligible", "testId=maybe-current", "候选按钮", now, now, now);

        tested.recordBrowserValidation(List.of("not-eligible"), List.of(), false);

        assertThat(jdbcTemplate.queryForObject("select execution_failure_count from ai_locator_knowledge_entries where entry_id = 'not-eligible'", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select confidence_score from ai_locator_knowledge_entries where entry_id = 'not-eligible'", Integer.class)).isEqualTo(80);
        assertThat(jdbcTemplate.queryForObject("select status from ai_locator_knowledge_entries where entry_id = 'not-eligible'", String.class)).isEqualTo("active");
    }

    @Test
    void candidateSearchDoesNotRetireUsingPlanFingerprint() {
        WebAutomationLocatorDiscoveryService discoveryService = mock(WebAutomationLocatorDiscoveryService.class);
        JdbcTemplate jdbcTemplate = newKnowledgeStore("locator-knowledge-candidate-fingerprint");
        LocatorKnowledgeService tested = new LocatorKnowledgeService(jdbcTemplate, discoveryService, new ObjectMapper(), mock(com.haitlify.aitest.platform.auth.ProjectAccessService.class));
        java.sql.Timestamp now = java.sql.Timestamp.from(java.time.Instant.now());
        jdbcTemplate.update("insert into ai_locator_knowledge_entries(entry_id, project_id, environment_id, module_name, page_name, page_fingerprint, natural_name, locator, status, evidence_version, collected_at, created_at, updated_at, confidence_score) values (?, 1, 'env-1', '日志管理', '日志管理', 'sha256:old', '查询', 'button=查询', 'active', 1, ?, ?, ?, 95)", "candidate-fp", now, now, now);
        jdbcTemplate.update("update ai_locator_knowledge_entries set page_url = ? where entry_id = ?", "https://test.example.com/#/log", "candidate-fp");

        var candidates = tested.searchCandidates(1L, "env-1", "日志管理", "日志管理", "查询", "sha256:new");

        assertThat(candidates).extracting(LocatorKnowledgeService.LocatorKnowledgeCandidate::entryId).containsExactly("candidate-fp");
        assertThat(candidates.get(0).pageUrl()).isEqualTo("https://test.example.com/#/log");
        assertThat(candidates.get(0).pageFingerprint()).isEqualTo("sha256:old");
        assertThat(candidates.get(0).projectId()).isEqualTo(1L);
        assertThat(candidates.get(0).environmentId()).isEqualTo("env-1");
        assertThat(jdbcTemplate.queryForObject("select status from ai_locator_knowledge_entries where entry_id = 'candidate-fp'", String.class)).isEqualTo("active");
    }

    @Test
    void candidateSearchOrdersByEnvironmentVerificationConfidenceAndCollectedTime() {
        WebAutomationLocatorDiscoveryService discoveryService = mock(WebAutomationLocatorDiscoveryService.class);
        JdbcTemplate jdbcTemplate = newKnowledgeStore("locator-knowledge-candidate-ranking");
        LocatorKnowledgeService tested = new LocatorKnowledgeService(jdbcTemplate, discoveryService, new ObjectMapper(), mock(com.haitlify.aitest.platform.auth.ProjectAccessService.class));
        java.sql.Timestamp collected1 = java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(7200));
        java.sql.Timestamp verified1 = java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(3600));
        java.sql.Timestamp collected2 = java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(5400));
        java.sql.Timestamp verified2 = java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1800));
        java.sql.Timestamp collected3 = java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(3600));
        java.sql.Timestamp verified3 = java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(900));
        insertKnowledgeRow(jdbcTemplate, "env-1-latest", "env-1", "https://test.example.com/#/log-a", "sha256:a", 80, collected2, verified2);
        insertKnowledgeRow(jdbcTemplate, "env-1-earlier", "env-1", "https://test.example.com/#/log-b", "sha256:b", 95, collected1, verified1);
        insertKnowledgeRow(jdbcTemplate, "env-2-top", "env-2", "https://test.example.com/#/log-c", "sha256:c", 99, collected3, verified3);

        var candidates = tested.searchCandidates(1L, "env-1", "日志管理", "日志管理", "查询", "sha256:new");

        assertThat(candidates).extracting(LocatorKnowledgeService.LocatorKnowledgeCandidate::entryId)
            .containsExactly("env-1-latest", "env-1-earlier");
        assertThat(candidates.get(0).pageUrl()).isEqualTo("https://test.example.com/#/log-a");
        assertThat(candidates.get(0).pageFingerprint()).isEqualTo("sha256:a");
    }

    @Test
    void searchEvidenceUsesModuleAndPageAsStableTieBreakers() {
        WebAutomationLocatorDiscoveryService discoveryService = mock(WebAutomationLocatorDiscoveryService.class);
        JdbcTemplate jdbcTemplate = newKnowledgeStore("locator-knowledge-ranking-ties");
        LocatorKnowledgeService tested = new LocatorKnowledgeService(jdbcTemplate, discoveryService, new ObjectMapper(), mock(com.haitlify.aitest.platform.auth.ProjectAccessService.class));
        java.sql.Timestamp now = java.sql.Timestamp.from(java.time.Instant.now());
        insertKnowledgeRow(jdbcTemplate, "tie-b", "env-1", 80, now);
        insertKnowledgeRow(jdbcTemplate, "tie-a", "env-1", 80, now);
        jdbcTemplate.update("update ai_locator_knowledge_entries set module_name = ?, page_name = ? where entry_id = ?", "模块B", "页面A", "tie-b");
        jdbcTemplate.update("update ai_locator_knowledge_entries set module_name = ?, page_name = ? where entry_id = ?", "模块A", "页面Z", "tie-a");

        List<WebAutomationLocatorDiscoveryService.PageLocatorEvidence> result =
            tested.searchEvidence(1L, "env-1", "", "", "查询");

        assertThat(result).extracting(WebAutomationLocatorDiscoveryService.PageLocatorEvidence::id)
            .containsExactly("tie-a", "tie-b");
    }

    @Test
    void searchEvidenceTreatsLikeWildcardsInModuleAndPageNamesAsLiterals() {
        WebAutomationLocatorDiscoveryService discoveryService = mock(WebAutomationLocatorDiscoveryService.class);
        JdbcTemplate jdbcTemplate = newKnowledgeStore("locator-knowledge-ranking-like-escape");
        LocatorKnowledgeService tested = new LocatorKnowledgeService(jdbcTemplate, discoveryService, new ObjectMapper(), mock(com.haitlify.aitest.platform.auth.ProjectAccessService.class));
        java.sql.Timestamp now = java.sql.Timestamp.from(java.time.Instant.now());
        insertKnowledgeRow(jdbcTemplate, "literal", "env-1", 80, now);
        insertKnowledgeRow(jdbcTemplate, "wildcard-match", "env-1", 80, now);
        jdbcTemplate.update("update ai_locator_knowledge_entries set module_name = ?, page_name = ? where entry_id = ?", "模块%Alpha", "屏幕_Beta", "literal");
        jdbcTemplate.update("update ai_locator_knowledge_entries set module_name = ?, page_name = ? where entry_id = ?", "模块XAlpha", "屏幕ZBeta", "wildcard-match");

        List<WebAutomationLocatorDiscoveryService.PageLocatorEvidence> result =
            tested.searchEvidence(1L, "env-1", "模块%Alpha", "屏幕_Beta", "查询");

        assertThat(result).extracting(WebAutomationLocatorDiscoveryService.PageLocatorEvidence::id)
            .containsExactly("literal");
    }

    @Test
    void highConfidenceEvidenceWithChangedFingerprintIsRetiredBeforeReuse() {
        WebAutomationLocatorDiscoveryService discoveryService = mock(WebAutomationLocatorDiscoveryService.class);
        JdbcTemplate jdbcTemplate = newKnowledgeStore("locator-knowledge-search-fingerprint");
        LocatorKnowledgeService tested = new LocatorKnowledgeService(jdbcTemplate, discoveryService, new ObjectMapper(), mock(com.haitlify.aitest.platform.auth.ProjectAccessService.class));
        java.sql.Timestamp now = java.sql.Timestamp.from(java.time.Instant.now());
        jdbcTemplate.update("""
            insert into ai_locator_knowledge_entries(
              entry_id, project_id, environment_id, module_name, page_name, page_url, page_fingerprint,
              natural_name, locator, role_name, accessible_name, element_type, source, status,
              evidence_version, collected_at, last_verified_at, metadata_json, created_at, updated_at,
              confidence_score
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'active', 1, ?, ?, ?, ?, ?, ?)
            """, "high-fp", 1L, "env-1", "日志管理", "日志管理", "https://test.example.com/#/log", "sha256:old",
            "查询", "button=查询", "button", "查询", "button", "agent_browser", now, now,
            "{\"status\":\"matched\",\"navigationTrail\":[\"https://test.example.com/#/log\"],\"totalScore\":90}", now, now, 90);

        List<WebAutomationLocatorDiscoveryService.PageLocatorEvidence> result =
            tested.searchEvidence(1L, "env-1", "日志管理", "日志管理", "查询", "sha256:new");

        assertThat(result).isEmpty();
        assertThat(jdbcTemplate.queryForObject("select status from ai_locator_knowledge_entries where entry_id = 'high-fp'", String.class))
            .isEqualTo("stale");
    }

    @Test
    void legacyEvidenceWithoutExplorationReportIsNotRetiredByFingerprintCheck() {
        WebAutomationLocatorDiscoveryService discoveryService = mock(WebAutomationLocatorDiscoveryService.class);
        JdbcTemplate jdbcTemplate = newKnowledgeStore("locator-knowledge-search-legacy");
        LocatorKnowledgeService tested = new LocatorKnowledgeService(jdbcTemplate, discoveryService, new ObjectMapper(), mock(com.haitlify.aitest.platform.auth.ProjectAccessService.class));
        java.sql.Timestamp now = java.sql.Timestamp.from(java.time.Instant.now());
        jdbcTemplate.update("""
            insert into ai_locator_knowledge_entries(
              entry_id, project_id, environment_id, module_name, page_name, page_url, page_fingerprint,
              natural_name, locator, role_name, accessible_name, element_type, source, status,
              evidence_version, collected_at, last_verified_at, metadata_json, created_at, updated_at,
              confidence_score
            ) values (?, 1, 'env-1', '日志管理', '日志管理', 'https://test.example.com/#/log', 'sha256:old',
              '查询', 'button=查询', 'button', '查询', 'button', 'browser', 'active', 1, ?, ?, '{}', ?, ?, 95)
            """, "legacy-fp", now, now, now, now);

        List<WebAutomationLocatorDiscoveryService.PageLocatorEvidence> result =
            tested.searchEvidence(1L, "env-1", "日志管理", "日志管理", "查询", "sha256:new");

        assertThat(result).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("select status from ai_locator_knowledge_entries where entry_id = 'legacy-fp'", String.class))
            .isEqualTo("active");
    }

    @Test
    void pageFingerprintIsStableAcrossElementOrderButSensitiveToStructure() {
        WebAutomationLocatorDiscoveryService discoveryService = mock(WebAutomationLocatorDiscoveryService.class);
        JdbcTemplate jdbcTemplate = newKnowledgeStore("locator-knowledge-fingerprint-order");
        LocatorKnowledgeService tested = new LocatorKnowledgeService(jdbcTemplate, discoveryService, new ObjectMapper(), mock(com.haitlify.aitest.platform.auth.ProjectAccessService.class));

        WebAutomationLocatorDiscoveryService.PageLocatorEvidence a = new WebAutomationLocatorDiscoveryService.PageLocatorEvidence("e1", "text=全部日志", "全部日志", "text", "span", "text", "browser");
        WebAutomationLocatorDiscoveryService.PageLocatorEvidence b = new WebAutomationLocatorDiscoveryService.PageLocatorEvidence("e2", "button=查询", "查询", "button", "button", "submit", "browser");
        WebAutomationLocatorDiscoveryService.PageLocatorEvidence c = new WebAutomationLocatorDiscoveryService.PageLocatorEvidence("e3", "text=新增日志", "新增日志", "text", "span", "text", "browser");

        tested.recordAgentEvidence(1L, "env-1", "日志管理", "页面A", "https://x/#/log", List.of(a, b));
        tested.recordAgentEvidence(1L, "env-1", "日志管理", "页面B", "https://x/#/log", List.of(b, a));
        tested.recordAgentEvidence(1L, "env-1", "日志管理", "页面C", "https://x/#/log", List.of(a, b, c));

        String fpA = jdbcTemplate.queryForObject("select distinct page_fingerprint from ai_locator_knowledge_entries where page_name = '页面A'", String.class);
        String fpB = jdbcTemplate.queryForObject("select distinct page_fingerprint from ai_locator_knowledge_entries where page_name = '页面B'", String.class);
        String fpC = jdbcTemplate.queryForObject("select distinct page_fingerprint from ai_locator_knowledge_entries where page_name = '页面C'", String.class);

        assertThat(fpA).isEqualTo(fpB);
        assertThat(fpC).isNotEqualTo(fpA);
    }

    @Test
    void revalidateRefreshesFingerprintWhenLocatorStillResolves() {
        WebAutomationLocatorDiscoveryService discoveryService = mock(WebAutomationLocatorDiscoveryService.class);
        JdbcTemplate jdbcTemplate = newKnowledgeStore("locator-knowledge-revalidate");
        LocatorKnowledgeService tested = new LocatorKnowledgeService(jdbcTemplate, discoveryService, new ObjectMapper(), mock(com.haitlify.aitest.platform.auth.ProjectAccessService.class));

        String oldFp = "sha256:old";
        java.sql.Timestamp now = java.sql.Timestamp.from(java.time.Instant.now());
        jdbcTemplate.update("insert into ai_locator_knowledge_entries(entry_id, project_id, page_url, page_fingerprint, locator, natural_name, status, evidence_version, collected_at, created_at, updated_at) values (?, ?, ?, ?, ?, ?, 'active', 1, ?, ?, ?)",
            "locator-r1", 1L, "https://x/#/log", oldFp, "button=查询", "查询", now, now, now);

        WebAutomationLocatorDiscoveryService.PageLocatorEvidence found = new WebAutomationLocatorDiscoveryService.PageLocatorEvidence("e1", "button=查询", "查询", "button", "button", "submit", "browser");
        when(discoveryService.discover(any())).thenReturn(new WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse("https://x/#/log", List.of(found)));

        tested.revalidate("locator-r1", null);

        String refreshed = jdbcTemplate.queryForObject("select page_fingerprint from ai_locator_knowledge_entries where entry_id = 'locator-r1'", String.class);
        assertThat(refreshed).startsWith("sha256:").isNotEqualTo(oldFp);
    }

    @Test
    void recordExecutionOutcomeKeepsSingleFailureActive() {
        WebAutomationLocatorDiscoveryService discoveryService = mock(WebAutomationLocatorDiscoveryService.class);
        JdbcTemplate jdbcTemplate = newKnowledgeStoreWithUsage("locator-knowledge-outcome-single");
        LocatorKnowledgeService tested = new LocatorKnowledgeService(jdbcTemplate, discoveryService, new ObjectMapper(), mock(com.haitlify.aitest.platform.auth.ProjectAccessService.class));

        java.sql.Timestamp now = java.sql.Timestamp.from(java.time.Instant.now());
        jdbcTemplate.update("insert into ai_locator_knowledge_entries(entry_id, project_id, locator, status, evidence_version, collected_at, created_at, updated_at) values (?, ?, ?, 'active', 1, ?, ?, ?)",
            "locator-outcome-1", 1L, "button=查询", now, now, now);
        jdbcTemplate.update("insert into ai_agent_asset_usage(usage_id, run_id, point_id, asset_type, asset_id, outcome, created_at, updated_at) values (?, ?, ?, 'locator', ?, 'pending', ?, ?)",
            "usage-1", "run-1", "point-1", "locator-outcome-1", now, now);

        tested.recordExecutionOutcome("run-1", "point-1", false);

        assertThat(jdbcTemplate.queryForObject("select status from ai_locator_knowledge_entries where entry_id = 'locator-outcome-1'", String.class)).isEqualTo("active");
        assertThat(jdbcTemplate.queryForObject("select execution_failure_count from ai_locator_knowledge_entries where entry_id = 'locator-outcome-1'", Integer.class)).isEqualTo(1);
    }

    @Test
    void recordExecutionOutcomeMarksRepeatedlyFailingLocatorStale() {
        WebAutomationLocatorDiscoveryService discoveryService = mock(WebAutomationLocatorDiscoveryService.class);
        JdbcTemplate jdbcTemplate = newKnowledgeStoreWithUsage("locator-knowledge-outcome-stale");
        LocatorKnowledgeService tested = new LocatorKnowledgeService(jdbcTemplate, discoveryService, new ObjectMapper(), mock(com.haitlify.aitest.platform.auth.ProjectAccessService.class));

        java.sql.Timestamp now = java.sql.Timestamp.from(java.time.Instant.now());
        jdbcTemplate.update("insert into ai_locator_knowledge_entries(entry_id, project_id, locator, status, evidence_version, collected_at, created_at, updated_at) values (?, ?, ?, 'active', 1, ?, ?, ?)",
            "locator-outcome-2", 1L, "button=查询", now, now, now);
        jdbcTemplate.update("insert into ai_agent_asset_usage(usage_id, run_id, point_id, asset_type, asset_id, outcome, created_at, updated_at) values (?, ?, ?, 'locator', ?, 'pending', ?, ?)",
            "usage-a", "run-a", "point-a", "locator-outcome-2", now, now);
        jdbcTemplate.update("insert into ai_agent_asset_usage(usage_id, run_id, point_id, asset_type, asset_id, outcome, created_at, updated_at) values (?, ?, ?, 'locator', ?, 'pending', ?, ?)",
            "usage-b", "run-b", "point-b", "locator-outcome-2", now, now);

        tested.recordExecutionOutcome("run-a", "point-a", false);
        assertThat(jdbcTemplate.queryForObject("select status from ai_locator_knowledge_entries where entry_id = 'locator-outcome-2'", String.class)).isEqualTo("active");

        tested.recordExecutionOutcome("run-b", "point-b", false);
        assertThat(jdbcTemplate.queryForObject("select status from ai_locator_knowledge_entries where entry_id = 'locator-outcome-2'", String.class)).isEqualTo("stale");
    }

    @Test
    void recordExecutionOutcomeSuccessDoesNotRetireLocator() {
        WebAutomationLocatorDiscoveryService discoveryService = mock(WebAutomationLocatorDiscoveryService.class);
        JdbcTemplate jdbcTemplate = newKnowledgeStoreWithUsage("locator-knowledge-outcome-success");
        LocatorKnowledgeService tested = new LocatorKnowledgeService(jdbcTemplate, discoveryService, new ObjectMapper(), mock(com.haitlify.aitest.platform.auth.ProjectAccessService.class));

        java.sql.Timestamp now = java.sql.Timestamp.from(java.time.Instant.now());
        jdbcTemplate.update("insert into ai_locator_knowledge_entries(entry_id, project_id, locator, status, evidence_version, collected_at, created_at, updated_at) values (?, ?, ?, 'active', 1, ?, ?, ?)",
            "locator-outcome-3", 1L, "button=查询", now, now, now);
        jdbcTemplate.update("insert into ai_agent_asset_usage(usage_id, run_id, point_id, asset_type, asset_id, outcome, created_at, updated_at) values (?, ?, ?, 'locator', ?, 'pending', ?, ?)",
            "usage-1", "run-1", "point-1", "locator-outcome-3", now, now);

        tested.recordExecutionOutcome("run-1", "point-1", true);

        assertThat(jdbcTemplate.queryForObject("select status from ai_locator_knowledge_entries where entry_id = 'locator-outcome-3'", String.class)).isEqualTo("active");
        assertThat(jdbcTemplate.queryForObject("select execution_success_count from ai_locator_knowledge_entries where entry_id = 'locator-outcome-3'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select execution_failure_count from ai_locator_knowledge_entries where entry_id = 'locator-outcome-3'", Integer.class)).isEqualTo(0);
    }

    private JdbcTemplate newKnowledgeStoreWithUsage(String dbName) {
        JdbcTemplate jdbcTemplate = newKnowledgeStore(dbName);
        jdbcTemplate.execute("""
            create table ai_agent_asset_usage (
              usage_id varchar(128) not null,
              run_id varchar(128) not null,
              point_id varchar(128),
              asset_type varchar(32) not null,
              asset_id varchar(128) not null,
              outcome varchar(32) not null default 'pending',
              created_at timestamp not null,
              updated_at timestamp not null,
              constraint uk_ai_agent_asset_usage unique (run_id, point_id, asset_type, asset_id)
            )
        """);
        return jdbcTemplate;
    }

    private void insertKnowledgeRow(JdbcTemplate jdbcTemplate, String id, String environment, String pageUrl, String pageFingerprint, int confidence, java.sql.Timestamp collectedAt, java.sql.Timestamp verifiedAt) {
        jdbcTemplate.update("""
            insert into ai_locator_knowledge_entries(
              entry_id, project_id, environment_id, module_name, page_name, page_url, page_fingerprint,
              natural_name, locator, role_name, accessible_name, element_type, source, status,
              evidence_version, collected_at, last_verified_at, metadata_json, created_at, updated_at,
              confidence_score
            ) values (?, 1, ?, '日志管理', '日志管理', ?, ?, '查询', ?, 'button', '查询', 'button', 'browser', 'active', 1, ?, ?, '{}', ?, ?, ?)
            """, id, environment, pageUrl, pageFingerprint, "button=" + id, collectedAt, verifiedAt, collectedAt, verifiedAt, confidence);
    }

    private JdbcTemplate newKnowledgeStore(String dbName) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("create table projects (id bigint primary key, active boolean not null)");
        jdbcTemplate.execute("create table platform_users (username varchar(64) primary key, role varchar(32) not null, enabled boolean not null)");
        jdbcTemplate.execute("create table project_members (id bigint generated by default as identity primary key, project_id bigint not null, username varchar(64) not null, created_at timestamp not null)");
        jdbcTemplate.update("insert into projects(id, active) values (1, true)");
        jdbcTemplate.update("insert into platform_users(username, role, enabled) values ('reviewer', 'EDITOR', true), ('outsider', 'EDITOR', true)");
        jdbcTemplate.update("insert into project_members(project_id, username, created_at) values (1, 'reviewer', current_timestamp())");
        jdbcTemplate.execute("""
            create table ai_locator_knowledge_entries (
              entry_id varchar(128) primary key,
              project_id bigint not null,
              environment_id varchar(128) not null default '',
              module_name varchar(255) not null default '',
              page_name varchar(255) not null default '',
              page_url varchar(1024) not null default '',
              page_fingerprint varchar(255) not null default '',
              natural_name varchar(512) not null default '',
              locator varchar(1024) not null,
              role_name varchar(128) not null default '',
              accessible_name varchar(512) not null default '',
              element_type varchar(128) not null default '',
              source varchar(64) not null default 'browser',
              status varchar(32) not null default 'active',
              evidence_version integer not null default 1,
              collected_at timestamp not null,
              last_verified_at timestamp null,
              metadata_json text not null default '{}',
              created_at timestamp not null,
              updated_at timestamp not null,
              hit_count integer not null default 0,
              execution_success_count integer not null default 0,
              execution_failure_count integer not null default 0,
              confidence_score integer not null default 35,
              confidence_locked boolean not null default false,
              last_hit_at timestamp null
            )
            """);
        return jdbcTemplate;
    }

    private void insertKnowledgeRow(JdbcTemplate jdbcTemplate, String id, String environment, int confidence, java.sql.Timestamp now) {
        jdbcTemplate.update("""
            insert into ai_locator_knowledge_entries(
              entry_id, project_id, environment_id, module_name, page_name, page_url, page_fingerprint,
              natural_name, locator, role_name, accessible_name, element_type, source, status,
              evidence_version, collected_at, last_verified_at, metadata_json, created_at, updated_at,
              confidence_score
            ) values (?, 1, ?, '日志管理', '日志管理', 'https://test.example.com/#/log', '', '查询', ?, 'button', '查询', 'button', 'agent_browser', 'active', 1, ?, ?, '{}', ?, ?, ?)
            """, id, environment, "button=" + id, now, now, now, now, confidence);
    }
}
