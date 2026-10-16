package com.haitlify.aitest.platform.ai.agentic.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.haitlify.aitest.platform.ai.agentic.WebCaseAgentModels.AgentRuleSnapshot;
import tools.jackson.databind.ObjectMapper;

class AgentRuleServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JdbcTemplate jdbcTemplate;
    private AgentRuleService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:agent-rule;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("drop table if exists ai_agent_rule_versions");
        jdbcTemplate.execute("""
            create table ai_agent_rule_versions (
              id bigint primary key auto_increment,
              rule_set_id varchar(128) not null,
              scope varchar(32) not null default 'global',
              project_id bigint,
              version int not null,
              status varchar(32) not null default 'draft',
              content_text text not null,
              phase_rules_json text not null,
              content_hash varchar(128) not null default '',
              published_at timestamp null,
              actor_id varchar(128) not null default 'system-dev',
              actor_name varchar(255) not null default 'Development User',
              created_at timestamp not null,
              updated_at timestamp not null,
              constraint uk_rule_version_scope unique (rule_set_id, scope, project_id, version)
            )
            """);
        service = new AgentRuleService(jdbcTemplate, objectMapper, new AgentRuleValidator());
    }

    // ---- 规则内容校验(AgentRuleValidator) ----

    @Test
    void acceptsRulesThatDescribeForbiddenSecretsWithoutPersistingThem() {
        new AgentRuleValidator().validate(content(), phases());
    }

    @Test
    void rejectsRulesThatInstructTheAgentToPersistSecrets() {
        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> new AgentRuleValidator().validate(content() + " Save password before generation.", phases())
        );
        assertEquals(true, error.getMessage().contains("不可发布"));
    }

    @Test
    void rejectsMissingPhaseRules() {
        Map<String, String> incomplete = new LinkedHashMap<>(phases());
        incomplete.remove("06-delivery");
        assertThrows(IllegalArgumentException.class, () -> new AgentRuleValidator().validate(content(), incomplete));
    }

    // ---- AgentRuleService:种子规则与查询 ----

    @Test
    void currentSeedsGlobalRulesOnFirstAccess() throws Exception {
        AgentRuleVersionResponse current = service.current(null);

        assertThat(current.version()).isEqualTo(1);
        assertThat(current.status()).isEqualTo("published");
        assertThat(current.scope()).isEqualTo("global");
        assertThat(current.phaseRules()).containsKeys("01-preflight", "02-evidence", "03-planning", "04-generation", "05-validation", "06-delivery");
        String seedText = new String(new ClassPathResource("AGENT.md").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(current.contentText()).isEqualTo(seedText);
        assertThat(current.contentHash()).isEqualTo(sha256(seedText));
    }

    @Test
    void currentFallsBackToGlobalWhenProjectHasNothingPublished() {
        AgentRuleVersionResponse current = service.current(1L);

        assertThat(current.scope()).isEqualTo("global");
        assertThat(current.version()).isEqualTo(1);
    }

    @Test
    void snapshotMirrorsCurrentRules() {
        AgentRuleSnapshot snapshot = service.snapshot(1L);

        assertThat(snapshot.ruleSetId()).isEqualTo("web-case-agent");
        assertThat(snapshot.phaseRules()).hasSize(6);
        assertThat(snapshot.version()).isEqualTo(1);
    }

    @Test
    void versionsAreScopedPerProjectAndExcludeGlobalSeed() {
        service.saveDraft(1L, draft("project-local content"));

        List<AgentRuleVersionResponse> versions = service.versions(1L);

        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).scope()).isEqualTo("project");
        assertThat(versions.get(0).projectId()).isEqualTo(1L);
        // versions(null) 查询 global scope,含种子规则,不受项目草稿影响
        assertThat(service.versions(null)).extracting(AgentRuleVersionResponse::scope)
            .containsExactly("global");
    }

    // ---- AgentRuleService:草稿、发布、回滚 ----

    @Test
    void saveDraftPersistsNextVersionInDraftStatus() {
        AgentRuleVersionResponse draft = service.saveDraft(1L, draft("draft-note"));

        assertThat(draft.version()).isEqualTo(1);
        assertThat(draft.status()).isEqualTo("draft");
        assertThat(draft.scope()).isEqualTo("project");
        assertThat(draft.projectId()).isEqualTo(1L);
        assertThat(draft.contentHash()).isEqualTo(sha256(draft.contentText()));
    }

    @Test
    void saveDraftIncrementsVersionWithinScopeAndKeepsSeedUntouched() {
        AgentRuleVersionResponse first = service.saveDraft(1L, draft("first"));
        AgentRuleVersionResponse second = service.saveDraft(1L, draft("second"));

        assertThat(first.version()).isEqualTo(1);
        assertThat(second.version()).isEqualTo(2);
        // 种子(global v1)不受项目草稿影响
        assertThat(service.current(2L).scope()).isEqualTo("global");
        assertThat(service.current(2L).version()).isEqualTo(1);
    }

    @Test
    void publishActivatesDraftAndCurrentPrefersProjectScope() {
        AgentRuleVersionResponse draft = service.saveDraft(1L, draft("project-published"));
        AgentRuleVersionResponse published = service.publish(1L, new PublishAgentRuleRequest(draft.version()));

        assertThat(published.status()).isEqualTo("published");
        AgentRuleVersionResponse current = service.current(1L);
        assertThat(current.scope()).isEqualTo("project");
        assertThat(current.version()).isEqualTo(1);
        assertThat(current.contentText()).contains("project-published");
    }

    @Test
    void publishArchivesPreviouslyPublishedVersionInSameScope() {
        AgentRuleVersionResponse v1 = service.saveDraft(1L, draft("v1"));
        service.publish(1L, new PublishAgentRuleRequest(v1.version()));
        AgentRuleVersionResponse v2 = service.saveDraft(1L, draft("v2"));
        service.publish(1L, new PublishAgentRuleRequest(v2.version()));

        List<AgentRuleVersionResponse> versions = service.versions(1L);
        assertThat(versions).extracting(AgentRuleVersionResponse::status)
            .containsExactly("published", "archived");
        assertThat(service.current(1L).contentText()).contains("v2");
    }

    @Test
    void rollbackRestoresArchivedContentAsNewPublishedVersion() {
        AgentRuleVersionResponse v1 = service.saveDraft(1L, draft("original"));
        service.publish(1L, new PublishAgentRuleRequest(v1.version()));
        AgentRuleVersionResponse v2 = service.saveDraft(1L, draft("changed"));
        service.publish(1L, new PublishAgentRuleRequest(v2.version()));

        AgentRuleVersionResponse rolledBack = service.rollback(1L, new RollbackAgentRuleRequest(v1.version()));

        assertThat(rolledBack.status()).isEqualTo("published");
        assertThat(rolledBack.contentText()).contains("original");
        assertThat(service.current(1L).contentText()).contains("original");
    }

    @Test
    void diffExposesGlobalVersionContents() {
        AgentRuleVersionResponse seed = service.current(null);

        AgentRuleDiffResponse diff = service.diff(seed.version(), seed.version());

        assertThat(diff.ruleSetId()).isEqualTo("web-case-agent");
        assertThat(diff.fromVersion()).isEqualTo(1);
        assertThat(diff.toVersion()).isEqualTo(1);
        assertThat(diff.fromContent()).isEqualTo(seed.contentText());
        assertThat(diff.toContent()).isEqualTo(seed.contentText());
    }

    @Test
    void saveDraftRejectsInvalidContentBeforeWriting() {
        assertThrows(IllegalArgumentException.class,
            () -> service.saveDraft(1L, draft(content() + " Save password before generation.")));

        assertThat(service.versions(1L)).isEmpty();
    }

    private SaveAgentRuleRequest draft(String extra) {
        return new SaveAgentRuleRequest(content() + "\n\n## Note\n\n" + extra, phases());
    }

    private String content() {
        return "# Agent\n\n## Purpose\ncreate cases\n\n## Inputs\npoints\n\n## Allowed tools\nrunner\n\n## Forbidden actions\nnever persist passwords or cookies\n\n## Pass conditions\nassertions\n\n## Failure state\nblocked\n\n## Visible event summary\nsummary";
    }

    private Map<String, String> phases() {
        Map<String, String> values = new LinkedHashMap<>();
        for (String phase : new String[] {"01-preflight", "02-evidence", "03-planning", "04-generation", "05-validation", "06-delivery"}) {
            values.put(phase, "phase rules");
        }
        return values;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("测试摘要计算失败", exception);
        }
    }
}
