package com.haitlify.aitest.platform.ai.agentic.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.haitlify.aitest.platform.ai.agentic.WebCaseAgentModels.AgentMemoryResponse;
import com.haitlify.aitest.platform.ai.agentic.WebCaseAgentModels.AgentMemoryStatus;
import com.haitlify.aitest.platform.ai.agentic.WebCaseAgentModels.AgentMemoryScopeType;

@SpringBootTest
@ActiveProfiles("test")
class AgentMemoryServiceTest {

    @Autowired
    private AgentMemoryService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;

    @BeforeEach
    void setUp() {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "memory-reviewer", "n/a", List.of()));
        String projectCode = "AGENT_MEMORY_" + UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update(
            "insert into projects(code, name, description, active, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
            projectCode, "Agent Memory", "", true, now, now
        );
        projectId = jdbcTemplate.queryForObject(
            "select id from projects where code = ?", Long.class, projectCode
        );

        insert("global", "global", "[]", "global-memory");
        insert("project", "project", "[]", "project-memory");
        insert("set", "project_set", "[  " + projectId + ", 42 ]", "project-set-memory");
        insert("other", "project_set", "[" + (projectId + 10) + "]", "other-project-memory");
    }

    @Test
    void promptContextReadsApplicableMemoriesWithoutPrefixMatchingOtherProjects() {
        List<String> context = service.promptContext(projectId);

        assertTrue(context.stream().anyMatch(value -> value.contains("global-memory")));
        assertTrue(context.stream().anyMatch(value -> value.contains("project-memory")));
        assertTrue(context.stream().anyMatch(value -> value.contains("project-set-memory")));
        assertFalse(context.stream().anyMatch(value -> value.contains("other-project-memory")));
    }

    private void insert(String suffix, String scopeType, String scopeProjectIdsJson, String content) {
        Instant now = Instant.now();
        jdbcTemplate.update(
            "insert into ai_agent_memories(memory_id, project_id, module_name, stage, category, content, status, scope_type, scope_project_ids_json, created_by, created_at, updated_at) values (?, ?, '', 'delivery', 'generation_delivery', ?, 'active', ?, ?, 'test', ?, ?)",
            "memory-" + suffix + "-" + UUID.randomUUID(), projectId, content, scopeType, scopeProjectIdsJson, now, now
        );
    }

    // ---- 经验记忆生命周期:创建 / 命中 / 质量 / 确认 / 变更 ----

    @Test
    void createCandidateRejectsMissingProjectOrContent() {
        assertThrows(IllegalArgumentException.class, () -> service.createCandidate(
            new CreateMemoryRequest(null, "run-1", "module", "category", "内容", 1)));
        assertThrows(IllegalArgumentException.class, () -> service.createCandidate(
            new CreateMemoryRequest(projectId, "run-1", "module", "category", "  ", 1)));
    }

    @Test
    void createCandidatePersistsPendingProjectMemory() {
        AgentMemoryResponse created = service.createCandidate(
            new CreateMemoryRequest(projectId, "run-1", "module", "category", "  经验内容  ", 1));

        Map<String, Object> row = row(created.memoryId());
        assertEquals("pending", row.get("status"));
        assertEquals("project", row.get("scope_type"));
        assertEquals("delivery", row.get("stage"));
        assertEquals("category", row.get("category"));
        assertEquals("经验内容", row.get("content"));
        assertEquals(1, row.get("rules_version"));
    }

    @Test
    void createCandidateTruncatesContentBeyondFiveHundredChars() {
        AgentMemoryResponse created = service.createCandidate(
            new CreateMemoryRequest(projectId, "run-1", "", "category", "x".repeat(600), 1));

        assertEquals(500, ((String) row(created.memoryId()).get("content")).length());
    }

    @Test
    void recordHitIncrementsHitCountAndConfidence() {
        AgentMemoryResponse created = service.createCandidate(
            new CreateMemoryRequest(projectId, "run-1", "", "category", "内容", 1));

        service.recordHit(created.memoryId());

        Map<String, Object> row = row(created.memoryId());
        assertEquals(1, row.get("hit_count"));
        // confidence(1 hit, 0/0 执行) = 35 + min(35, 5) = 40
        assertEquals(40, row.get("confidence_score"));
        assertEquals(false, row.get("confidence_locked"));
    }

    @Test
    void recordPromptUsageTracksUsageAndIncrementsHitOnce() {
        AgentMemoryResponse created = service.createCandidate(
            new CreateMemoryRequest(projectId, "run-1", "", "category", "内容", 1));
        service.confirm(created.memoryId(), new ConfirmMemoryRequest(AgentMemoryScopeType.PROJECT, null));

        service.recordPromptUsage("run-1", "point-1", projectId);
        service.recordPromptUsage("run-1", "point-1", projectId);

        Integer usageCount = jdbcTemplate.queryForObject(
            "select count(*) from ai_agent_asset_usage where run_id = ? and point_id = ? and asset_type = 'memory' and asset_id = ?",
            Integer.class, "run-1", "point-1", created.memoryId());
        assertEquals(1, usageCount);
        // 幂等:重复调用不再重复计命中
        assertEquals(1, row(created.memoryId()).get("hit_count"));
    }

    @Test
    void recordExecutionOutcomeUpdatesUsageAndQuality() {
        AgentMemoryResponse created = service.createCandidate(
            new CreateMemoryRequest(projectId, "run-1", "", "category", "内容", 1));
        service.confirm(created.memoryId(), new ConfirmMemoryRequest(AgentMemoryScopeType.PROJECT, null));
        service.recordPromptUsage("run-1", "point-1", projectId);

        service.recordExecutionOutcome("run-1", "point-1", true);

        String outcome = jdbcTemplate.queryForObject(
            "select outcome from ai_agent_asset_usage where run_id = ? and point_id = ? and asset_type = 'memory' and asset_id = ?",
            String.class, "run-1", "point-1", created.memoryId());
        assertEquals("passed", outcome);
        Map<String, Object> row = row(created.memoryId());
        assertEquals(1, row.get("execution_success_count"));
        assertEquals(0, row.get("execution_failure_count"));
        // confidence(1 hit, 1/0 执行) = min(100, 35 + 5 + 30) = 70
        assertEquals(70, row.get("confidence_score"));
    }

    @Test
    void createDeliveryCandidateReturnsExistingForSameRun() {
        AgentMemoryResponse first = service.createDeliveryCandidate("run-1", projectId, 3, 1);

        AgentMemoryResponse second = service.createDeliveryCandidate("run-1", projectId, 5, 1);

        assertEquals(first.memoryId(), second.memoryId());
        assertTrue(first.content().contains("3 条"));
    }

    @Test
    void confirmActivatesMemoryAndWritesAudit() {
        AgentMemoryResponse created = service.createCandidate(
            new CreateMemoryRequest(projectId, "run-1", "", "category", "内容", 1));

        AgentMemoryResponse confirmed = service.confirm(
            created.memoryId(), new ConfirmMemoryRequest(AgentMemoryScopeType.PROJECT, null));

        assertEquals("active", confirmed.status().name().toLowerCase());
        assertEquals("memory-reviewer", confirmed.confirmedBy());
        assertEquals(1, auditCount(created.memoryId(), "confirm"));
        // 确认后进入 active,可被 promptContext 检索
        assertTrue(service.promptContext(projectId).stream().anyMatch(line -> line.contains("内容")));
    }

    @Test
    void confirmRequiresProjectSetMembers() {
        AgentMemoryResponse created = service.createCandidate(
            new CreateMemoryRequest(projectId, "run-1", "", "category", "内容", 1));

        assertThrows(IllegalArgumentException.class, () -> service.confirm(
            created.memoryId(), new ConfirmMemoryRequest(AgentMemoryScopeType.PROJECT_SET, List.of())));
    }

    @Test
    void disableAndDeleteTransitionStatus() {
        AgentMemoryResponse created = service.createCandidate(
            new CreateMemoryRequest(projectId, "run-1", "", "category", "内容", 1));
        AgentMemoryResponse created2 = service.createCandidate(
            new CreateMemoryRequest(projectId, "run-2", "", "category", "内容2", 1));

        // 契约更新：停用只允许对已确认（active）经验；删除对未确认候选可用。
        service.confirm(created.memoryId(), new ConfirmMemoryRequest(AgentMemoryScopeType.PROJECT, null));
        service.disable(created.memoryId());
        service.delete(created2.memoryId());

        assertEquals("disabled", row(created.memoryId()).get("status"));
        assertEquals("deleted", row(created2.memoryId()).get("status"));
        assertEquals(1, auditCount(created.memoryId(), "disable"));
        assertEquals(1, auditCount(created2.memoryId(), "delete"));
    }

    @Test
    void updateChangesContentAndAudits() {
        AgentMemoryResponse created = service.createCandidate(
            new CreateMemoryRequest(projectId, "run-1", "old-module", "old-category", "旧内容", 1));

        AgentMemoryResponse updated = service.update(created.memoryId(),
            new UpdateMemoryRequest("new-module", "new-category", "新内容", AgentMemoryScopeType.PROJECT, null));

        assertEquals("新内容", updated.content());
        assertEquals("new-module", updated.moduleName());
        assertEquals("new-category", updated.category());
        assertEquals(1, auditCount(created.memoryId(), "update"));
    }

    @Test
    void updateRejectsBlankContent() {
        AgentMemoryResponse created = service.createCandidate(
            new CreateMemoryRequest(projectId, "run-1", "", "category", "内容", 1));

        assertThrows(IllegalArgumentException.class, () -> service.update(
            created.memoryId(), new UpdateMemoryRequest(null, null, "  ", null, null)));
    }

    @Test
    void updateRequiresProjectSetMembers() {
        AgentMemoryResponse created = service.createCandidate(
            new CreateMemoryRequest(projectId, "run-1", "", "category", "内容", 1));

        assertThrows(IllegalArgumentException.class, () -> service.update(
            created.memoryId(), new UpdateMemoryRequest(null, null, "新内容", AgentMemoryScopeType.PROJECT_SET, List.of())));
    }

    @Test
    void updatePreservesFieldsNotProvided() {
        AgentMemoryResponse created = service.createCandidate(
            new CreateMemoryRequest(projectId, "run-1", "module", "category", "内容", 1));

        AgentMemoryResponse updated = service.update(created.memoryId(),
            new UpdateMemoryRequest(null, null, "仅改内容", null, null));

        assertEquals("仅改内容", updated.content());
        assertEquals("module", updated.moduleName());
        assertEquals("category", updated.category());
        assertEquals(AgentMemoryScopeType.PROJECT, updated.scopeType());
    }

    private Map<String, Object> row(String memoryId) {
        return jdbcTemplate.queryForMap("select * from ai_agent_memories where memory_id = ?", memoryId);
    }

    private int auditCount(String memoryId, String action) {
        return jdbcTemplate.queryForObject(
            "select count(*) from ai_agent_memory_audit where memory_id = ? and action_type = ?", Integer.class, memoryId, action);
    }

    @org.junit.jupiter.api.AfterEach
    void clearAuth() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    // ---- 全链路人工确认契约（Exit Gate）：actor / revision / 状态机 / 幂等 / 审计 ----

    @Test
    void confirmationIsIdempotentAndAuditsOnceWithTheRealActor() {
        AgentMemoryResponse created = service.createCandidate(
            new CreateMemoryRequest(projectId, "run-1", "", "category", "内容", 1));

        AgentMemoryResponse confirmed = service.confirm(created.memoryId(),
            new ConfirmMemoryRequest(AgentMemoryScopeType.PROJECT, null));
        assertEquals(AgentMemoryStatus.ACTIVE, confirmed.status());
        Map<String, Object> row = row(created.memoryId());
        assertEquals("memory-reviewer", row.get("confirmed_by"));

        service.confirm(created.memoryId(), new ConfirmMemoryRequest(AgentMemoryScopeType.PROJECT, null));
        Integer audits = jdbcTemplate.queryForObject(
            "select count(*) from ai_agent_memory_audit where memory_id = ? and action_type = 'confirm'",
            Integer.class, created.memoryId());
        assertEquals(1, audits);
        assertEquals("memory-reviewer", jdbcTemplate.queryForObject(
            "select actor_id from ai_agent_memory_audit where memory_id = ? and action_type = 'confirm'",
            String.class, created.memoryId()));
    }

    @Test
    void disableRequiresAnActiveMemoryAndRepeatsAreIdempotent() {
        AgentMemoryResponse pending = service.createCandidate(
            new CreateMemoryRequest(projectId, "run-1", "", "category", "未确认", 1));
        assertThrows(IllegalArgumentException.class, () -> service.disable(pending.memoryId()));

        service.confirm(pending.memoryId(), new ConfirmMemoryRequest(AgentMemoryScopeType.PROJECT, null));
        service.disable(pending.memoryId());
        assertEquals("disabled", row(pending.memoryId()).get("status"));

        service.disable(pending.memoryId());
        Integer disableAudits = jdbcTemplate.queryForObject(
            "select count(*) from ai_agent_memory_audit where memory_id = ? and action_type = 'disable'",
            Integer.class, pending.memoryId());
        assertEquals(1, disableAudits);
    }

    @Test
    void updateHonoursExpectedRevisionAndBumpsItOnce() {
        AgentMemoryResponse created = service.createCandidate(
            new CreateMemoryRequest(projectId, "run-1", "", "category", "初稿", 1));
        service.confirm(created.memoryId(), new ConfirmMemoryRequest(AgentMemoryScopeType.PROJECT, null));

        assertThrows(com.haitlify.aitest.platform.ai.agentic.StaleArtifactRevisionException.class,
            () -> service.update(created.memoryId(),
                new UpdateMemoryRequest(null, null, "改稿", null, null, 99)));

        AgentMemoryResponse updated = service.update(created.memoryId(),
            new UpdateMemoryRequest(null, null, "改稿", null, null, 1));
        assertEquals(2, updated.revision());
        assertEquals(2, row(created.memoryId()).get("revision"));
    }

    @Test
    void anonymousOrSystemActorsCannotReviewMemories() {
        AgentMemoryResponse created = service.createCandidate(
            new CreateMemoryRequest(projectId, "run-1", "", "category", "内容", 1));

        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        assertThrows(IllegalArgumentException.class, () -> service.confirm(created.memoryId(),
            new ConfirmMemoryRequest(AgentMemoryScopeType.PROJECT, null)));

        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.AnonymousAuthenticationToken(
                "key", "anonymousUser",
                org.springframework.security.core.authority.AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        assertThrows(IllegalArgumentException.class, () -> service.disable(created.memoryId()));
        assertEquals("pending", row(created.memoryId()).get("status"));
    }

}
