package com.haitlify.aitest.platform.ai.agentic.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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

import com.haitlify.aitest.platform.ai.agentic.WebCaseAgentModels.AgentMemoryScopeType;

/**
 * 知识经验授权门禁：来源项目访问（list/create/confirm/update）与「目标范围」授权——
 * 全局范围仅管理员可发布；PROJECT_SET 的每个目标项目都必须有权访问；无权一律与不存在同形。
 * securityEnabled=true + 真实成员表驱动真实 ProjectAccessService。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "app.security.enabled=true")
class AgentMemoryAccessGateTest {

    @Autowired
    private AgentMemoryService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectA;
    private long projectB;

    @BeforeEach
    void seed() {
        authenticate("mem-member");
        jdbcTemplate.update("delete from ai_agent_memory_audit");
        jdbcTemplate.update("delete from ai_agent_memories");
        jdbcTemplate.update("delete from project_members where username in ('mem-member', 'mem-admin')");
        jdbcTemplate.update("delete from platform_users where username in ('mem-member', 'mem-admin')");
        jdbcTemplate.update("delete from projects where code in ('MEM_GATE_A', 'MEM_GATE_B')");

        Instant now = Instant.now();
        jdbcTemplate.update("insert into projects(code, name, description, active, created_at, updated_at)"
            + " values ('MEM_GATE_A', '经验门禁一', '', true, ?, ?)", Timestamp.from(now), Timestamp.from(now));
        jdbcTemplate.update("insert into projects(code, name, description, active, created_at, updated_at)"
            + " values ('MEM_GATE_B', '经验门禁二', '', true, ?, ?)", Timestamp.from(now), Timestamp.from(now));
        projectA = jdbcTemplate.queryForObject("select id from projects where code = 'MEM_GATE_A'", Long.class);
        projectB = jdbcTemplate.queryForObject("select id from projects where code = 'MEM_GATE_B'", Long.class);
        jdbcTemplate.update("insert into platform_users(username, password_hash, role, enabled, created_at, updated_at)"
            + " values ('mem-member', 'x', 'EDITOR', true, ?, ?)", Timestamp.from(now), Timestamp.from(now));
        jdbcTemplate.update("insert into platform_users(username, password_hash, role, enabled, created_at, updated_at)"
            + " values ('mem-admin', 'x', 'ADMIN', true, ?, ?)", Timestamp.from(now), Timestamp.from(now));
        jdbcTemplate.update("insert into project_members(project_id, username, created_at) values (?, 'mem-member', ?)",
            projectA, Timestamp.from(now));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String username) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
    }

    private String pendingMemoryIn(long projectId) {
        String id = "memory-gate-" + UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("insert into ai_agent_memories(memory_id, project_id, module_name, stage, category,"
            + " content, status, scope_type, scope_project_ids_json, created_by, created_at, updated_at)"
            + " values (?, ?, '', 'delivery', 'generation_delivery', ?, 'pending', 'project', ?, 'agent', ?, ?)",
            id, projectId, "门禁经验 " + id, "[" + projectId + "]", Timestamp.from(now), Timestamp.from(now));
        return id;
    }

    private String statusOf(String memoryId) {
        return jdbcTemplate.queryForObject(
            "select status from ai_agent_memories where memory_id = ?", String.class, memoryId);
    }

    private String scopeOf(String memoryId) {
        return jdbcTemplate.queryForObject(
            "select scope_type from ai_agent_memories where memory_id = ?", String.class, memoryId);
    }

    @Test
    void listingRequiresAccessToTheTargetProject() {
        assertThat(service.list(projectA, false)).isNotNull();
        assertThatThrownBy(() -> service.list(projectB, false))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("not_found");
    }

    @Test
    void listingWithoutAuthenticationIsDeniedWithTheUniformShape() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> service.list(projectA, false))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("not_found");
    }

    @Test
    void creatingInAnotherProjectIsDeniedAndTheSourceProjectIsEnforced() {
        assertThatThrownBy(() -> service.createCandidate(
                new CreateMemoryRequest(projectB, "run-gate", "", "category", "越权候选", 1)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("not_found");

        var created = service.createCandidate(
            new CreateMemoryRequest(projectA, "run-gate", "", "category", "合法候选", 1));
        assertThat(statusOf(created.memoryId())).isEqualTo("pending");
    }

    @Test
    void confirmingRequiresAccessToTheSourceProject() {
        String foreign = pendingMemoryIn(projectB);
        assertThatThrownBy(() -> service.confirm(foreign,
                new ConfirmMemoryRequest(AgentMemoryScopeType.PROJECT, null)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("not_found");
        assertThat(statusOf(foreign)).isEqualTo("pending");
    }

    @Test
    void publishingGloballyRequiresAnAdmin() {
        String memory = pendingMemoryIn(projectA);
        assertThatThrownBy(() -> service.confirm(memory,
                new ConfirmMemoryRequest(AgentMemoryScopeType.GLOBAL, null)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("仅管理员");
        assertThat(statusOf(memory)).isEqualTo("pending");
        assertThat(scopeOf(memory)).isEqualTo("project");

        authenticate("mem-admin");
        service.confirm(memory, new ConfirmMemoryRequest(AgentMemoryScopeType.GLOBAL, null));
        assertThat(statusOf(memory)).isEqualTo("active");
        assertThat(scopeOf(memory)).isEqualTo("global");
    }

    @Test
    void publishingToAProjectSetValidatesEveryTargetProject() {
        String memory = pendingMemoryIn(projectA);
        assertThatThrownBy(() -> service.confirm(memory,
                new ConfirmMemoryRequest(AgentMemoryScopeType.PROJECT_SET, List.of(projectA, projectB))))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("not_found");
        assertThat(statusOf(memory)).isEqualTo("pending");

        service.confirm(memory, new ConfirmMemoryRequest(AgentMemoryScopeType.PROJECT_SET, List.of(projectA)));
        assertThat(scopeOf(memory)).isEqualTo("project_set");
    }

    @Test
    void updatingAScopeAlsoValidatesTargets() {
        // 管理员发布为全局后，普通成员不得再编辑全局经验
        String global = pendingMemoryIn(projectA);
        authenticate("mem-admin");
        service.confirm(global, new ConfirmMemoryRequest(AgentMemoryScopeType.GLOBAL, null));

        authenticate("mem-member");
        assertThatThrownBy(() -> service.update(global,
                new UpdateMemoryRequest(null, null, "改内容", null, null, 1)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("仅管理员");

        // 成员把自己项目内已确认经验改为含无权项目的集合 ⇒ 同形 404，且不落库
        String projectSet = pendingMemoryIn(projectA);
        service.confirm(projectSet, new ConfirmMemoryRequest(AgentMemoryScopeType.PROJECT_SET, List.of(projectA)));
        assertThatThrownBy(() -> service.update(projectSet,
                new UpdateMemoryRequest(null, null, "改内容", AgentMemoryScopeType.PROJECT_SET,
                    List.of(projectA, projectB), 1)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("not_found");
        assertThat(jdbcTemplate.queryForObject(
            "select content from ai_agent_memories where memory_id = ?", String.class, projectSet))
            .startsWith("门禁经验");

        // 合法目标集合 ⇒ 修改成功并推进 revision
        var updated = service.update(projectSet,
            new UpdateMemoryRequest(null, null, "改内容", AgentMemoryScopeType.PROJECT_SET,
                List.of(projectA), 1));
        assertThat(updated.revision()).isEqualTo(2);
    }
}
