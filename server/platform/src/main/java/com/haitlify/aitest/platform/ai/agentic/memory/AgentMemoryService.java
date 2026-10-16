package com.haitlify.aitest.platform.ai.agentic.memory;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.haitlify.aitest.platform.ai.agentic.WebCaseAgentModels.AgentMemoryResponse;
import com.haitlify.aitest.platform.ai.agentic.WebCaseAgentModels.AgentMemoryScopeType;
import com.haitlify.aitest.platform.ai.agentic.WebCaseAgentModels.AgentMemoryStatus;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional
public class AgentMemoryService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private com.haitlify.aitest.platform.auth.ProjectAccessService projectAccessService;

    AgentMemoryService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.projectAccessService = null;
    }

    @org.springframework.beans.factory.annotation.Autowired
    AgentMemoryService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
        com.haitlify.aitest.platform.auth.ProjectAccessService projectAccessService) {
        this(jdbcTemplate, objectMapper);
        this.projectAccessService = projectAccessService;
    }

    /**
     * 经验列表（HTTP 面）：必须对目标项目有访问权——无权与不存在同形 404，
     * 防止跨项目读取他人经验（全局经验按设计对所有项目可见）。
     */
    public List<AgentMemoryResponse> list(Long projectId, boolean pendingOnly) {
        requireProjectAccess(projectId);
        return queryMemories(projectId, pendingOnly ? "pending" : "active");
    }

    /** 内部查询（无授权语义）：仅供已授权的调用方使用。 */
    private List<AgentMemoryResponse> queryMemories(Long projectId, String status) {
        return jdbcTemplate.query(
            "select * from ai_agent_memories where status = ? and "
                + "(scope_type = 'global' or (scope_type = 'project' and project_id = ?) or "
                + "(scope_type = 'project_set' and concat(',', replace(replace(replace(coalesce(scope_project_ids_json, '[]'), '[', ''), ']', ''), ' ', ''), ',') like ?)) "
                + "order by updated_at desc",
            (rs, rowNum) -> map(rs),
            status,
            projectId,
            "%," + projectId + ",%"
        );
    }

    /**
     * 创建候选经验（HTTP 面）：来源项目必须有访问权（无权与不存在同形 404）。
     */
    public AgentMemoryResponse createCandidate(CreateMemoryRequest request) {
        if (request.projectId() == null || request.content() == null || request.content().isBlank()) throw new IllegalArgumentException("经验记忆必须有项目和内容");
        requireProjectAccess(request.projectId());
        return insertCandidate(request.projectId(), request.sourceRunId(), request.moduleName(),
            request.category(), request.content(), request.rulesVersion());
    }

    /** 内部写入（无授权语义）：系统生成的候选（派发器交付经验）使用；项目由 run 生命周期保证。 */
    private AgentMemoryResponse insertCandidate(Long projectId, String sourceRunId, String moduleName,
        String category, String content, int rulesVersion) {
        String trimmed = content.trim();
        if (trimmed.length() > 500) trimmed = trimmed.substring(0, 500);
        Instant now = Instant.now(); String id = "memory-" + UUID.randomUUID();
        jdbcTemplate.update("insert into ai_agent_memories(memory_id, source_run_id, project_id, module_name, stage, category, content, status, scope_type, scope_project_ids_json, rules_version, created_by, created_at, updated_at) values (?, ?, ?, ?, 'delivery', ?, ?, 'pending', 'project', ?, ?, 'agent', ?, ?)", id, sourceRunId, projectId, moduleName == null ? "" : moduleName, category, trimmed, "[" + projectId + "]", rulesVersion, Timestamp.from(now), Timestamp.from(now));
        return get(id);
    }

    /**
     * Active, human-confirmed notes only; suitable for a model prompt and never includes hidden reasoning.
     * 系统内部读取（后台 run 线程无 HTTP 身份）：不做交互式授权，仅返回已确认内容；
     * 项目边界由 run 创建时的授权与下方 scope 过滤共同保证。
     */
    public List<String> promptContext(Long projectId) {
        return queryMemories(projectId, "active").stream()
            .map(memory -> "[" + memory.category() + "] " + memory.content())
            .toList();
    }

    public void recordHit(String memoryId) {
        Instant now = Instant.now();
        jdbcTemplate.update("update ai_agent_memories set hit_count = hit_count + 1, last_hit_at = ?, updated_at = ? where memory_id = ?", Timestamp.from(now), Timestamp.from(now), memoryId);
        refreshQuality(memoryId, 0, 0, now);
    }

    public void recordPromptUsage(String runId, String pointId, Long projectId) {
        Instant now = Instant.now();
        for (AgentMemoryResponse memory : queryMemories(projectId, "active")) {
            Integer existing = jdbcTemplate.queryForObject("select count(*) from ai_agent_asset_usage where run_id = ? and point_id = ? and asset_type = 'memory' and asset_id = ?", Integer.class, runId, pointId, memory.memoryId());
            if (existing != null && existing > 0) continue;
            jdbcTemplate.update("insert into ai_agent_asset_usage(usage_id, run_id, point_id, asset_type, asset_id, outcome, created_at, updated_at) values (?, ?, ?, 'memory', ?, 'pending', ?, ?)", "usage-" + UUID.randomUUID(), runId, pointId, memory.memoryId(), Timestamp.from(now), Timestamp.from(now));
            recordHit(memory.memoryId());
        }
    }

    public void recordExecutionOutcome(String runId, String pointId, boolean succeeded) {
        Instant now = Instant.now();
        List<String> ids = jdbcTemplate.queryForList("select asset_id from ai_agent_asset_usage where run_id = ? and point_id = ? and asset_type = 'memory' and outcome = 'pending'", String.class, runId, pointId);
        for (String id : ids) refreshQuality(id, succeeded ? 1 : 0, succeeded ? 0 : 1, now);
        jdbcTemplate.update("update ai_agent_asset_usage set outcome = ?, updated_at = ? where run_id = ? and point_id = ? and asset_type = 'memory' and outcome = 'pending'", succeeded ? "passed" : "failed", Timestamp.from(now), runId, pointId);
    }

    public AgentMemoryResponse createDeliveryCandidate(String runId, Long projectId, int generatedCount, int rulesVersion) {
        Integer existing = jdbcTemplate.queryForObject(
            "select count(*) from ai_agent_memories where source_run_id = ? and category = 'generation_delivery'", Integer.class, runId
        );
        if (existing != null && existing > 0) {
            return jdbcTemplate.queryForObject(
                "select * from ai_agent_memories where source_run_id = ? and category = 'generation_delivery' order by created_at desc limit 1",
                (rs, rowNum) -> map(rs), runId
            );
        }
        return insertCandidate(
            projectId, runId, "", "generation_delivery",
            "本次 Agent 已生成 " + generatedCount + " 条通过浏览器 Locator 与 Web DSL 校验的用例；后续同类场景应继续先取证、再生成，并保留可执行断言。",
            rulesVersion
        );
    }

    /**
     * 全链路人工确认 Exit Gate（知识经验）：真实认证 actor + 项目权限（无权与不存在同形 404）
     * + expectedRevision（过期 409）+ 允许的前置状态 + 重复确认幂等 + 审计携带真实 actor。
     * confirmed_by 记录真实操作人（不再写死 'user'）。
     */
    public AgentMemoryResponse confirm(String id, ConfirmMemoryRequest request) {
        if (request.scopeType() == AgentMemoryScopeType.PROJECT_SET && (request.projectIds() == null || request.projectIds().isEmpty())) throw new IllegalArgumentException("项目集合不能为空");
        String actor = com.haitlify.aitest.platform.ai.security.AiActorResolver.requireHumanActor();
        AgentMemoryResponse before;
        try {
            before = get(id);
        } catch (IllegalArgumentException notFound) {
            throw com.haitlify.aitest.platform.ai.security.AiInvocationAuthorization.notFound();
        }
        requireMemoryAccess(before);
        guardMemoryRevision(before, request.revision());
        if (before.status() == AgentMemoryStatus.ACTIVE) {
            return before; // 重复确认幂等：不再写审计，也不重复目标校验
        }
        if (before.status() != AgentMemoryStatus.PENDING) {
            throw new IllegalArgumentException("review_state_conflict: confirm 不允许，当前状态 " + before.status());
        }
        requireTargetScopeAccess(request.scopeType(), request.projectIds(), before.projectId());
        String ids = request.projectIds() == null ? "[]" : write(request.projectIds()); Instant now = Instant.now();
        int changed = jdbcTemplate.update("update ai_agent_memories set status = 'active', scope_type = ?, scope_project_ids_json = ?, confirmed_by = ?, confirmed_at = ?, updated_at = ? where memory_id = ? and status = 'pending'", request.scopeType().name().toLowerCase(), ids, actor, Timestamp.from(now), Timestamp.from(now), id);
        if (changed == 0) {
            AgentMemoryResponse raced = get(id);
            if (raced.status() == AgentMemoryStatus.ACTIVE) {
                return raced;
            }
            throw new IllegalArgumentException("review_state_conflict: confirm 不允许，当前状态 " + raced.status());
        }
        audit(id, "confirm", actor, before, get(id)); return get(id);
    }

    public void disable(String id) { transition(id, AgentMemoryStatus.DISABLED, "disable"); }
    public void delete(String id) { transition(id, AgentMemoryStatus.DELETED, "delete"); }

    public AgentMemoryResponse update(String id, UpdateMemoryRequest request) {
        if (request == null || request.content() == null || request.content().isBlank()) throw new IllegalArgumentException("经验内容不能为空");
        String actor = com.haitlify.aitest.platform.ai.security.AiActorResolver.requireHumanActor();
        AgentMemoryResponse before;
        try {
            before = get(id);
        } catch (IllegalArgumentException notFound) {
            throw com.haitlify.aitest.platform.ai.security.AiInvocationAuthorization.notFound();
        }
        requireMemoryAccess(before);
        guardMemoryRevision(before, request.revision());
        if (before.status() == AgentMemoryStatus.DELETED) {
            throw new IllegalArgumentException("review_state_conflict: 已删除的经验不可再修改");
        }
        if (before.confidenceLocked()) throw new IllegalStateException("高置信度记忆已锁定，不能直接修改；请先停用并新增替代记忆");
        String content = request.content().trim();
        if (content.length() > 2000) content = content.substring(0, 2000);
        AgentMemoryScopeType scope = request.scopeType() == null ? before.scopeType() : request.scopeType();
        List<Long> projectIds = request.projectIds() == null ? List.of() : request.projectIds();
        if (scope == AgentMemoryScopeType.PROJECT_SET && projectIds.isEmpty()) throw new IllegalArgumentException("项目集合不能为空");
        requireTargetScopeAccess(scope, request.projectIds(), before.projectId());
        String scopeIds = request.projectIds() == null ? readScopeIds(before) : write(projectIds);
        int changed = jdbcTemplate.update("update ai_agent_memories set module_name = ?, category = ?, content = ?, scope_type = ?, scope_project_ids_json = ?, revision = revision + 1, updated_at = ? where memory_id = ? and revision = ?", value(request.moduleName(), before.moduleName()), value(request.category(), before.category()), content, scope.name().toLowerCase(), scopeIds, Timestamp.from(Instant.now()), id, before.revision() == null ? 1 : before.revision());
        if (changed == 0) {
            throw new com.haitlify.aitest.platform.ai.agentic.StaleArtifactRevisionException(
                "memory revision changed concurrently: " + id);
        }
        audit(id, "update", actor, before, get(id));
        return get(id);
    }

    private void transition(String id, AgentMemoryStatus status, String action) {
        String actor = com.haitlify.aitest.platform.ai.security.AiActorResolver.requireHumanActor();
        AgentMemoryResponse before;
        try {
            before = get(id);
        } catch (IllegalArgumentException notFound) {
            throw com.haitlify.aitest.platform.ai.security.AiInvocationAuthorization.notFound();
        }
        requireMemoryAccess(before);
        if (before.status() == status) {
            return; // 重复停用/删除幂等
        }
        if (status == AgentMemoryStatus.DISABLED && before.status() != AgentMemoryStatus.ACTIVE) {
            throw new IllegalArgumentException("review_state_conflict: disable 不允许，当前状态 " + before.status());
        }
        if (status == AgentMemoryStatus.DELETED && before.status() == AgentMemoryStatus.PENDING
                && before.confidenceLocked()) {
            throw new IllegalArgumentException("review_state_conflict: 锁定的经验不可删除");
        }
        int changed = jdbcTemplate.update(
            "update ai_agent_memories set status = ?, updated_at = ? where memory_id = ? and status = ?",
            status.name().toLowerCase(), Timestamp.from(Instant.now()), id, before.status().name().toLowerCase());
        if (changed == 0) {
            AgentMemoryResponse raced = get(id);
            if (raced.status() == status) {
                return;
            }
            throw new IllegalArgumentException("review_state_conflict: " + action + " 不允许，当前状态 " + raced.status());
        }
        audit(id, action, actor, before, get(id));
    }

    /**
     * 目标项目访问（HTTP 面）：必须提供项目且有权访问，无权与不存在同形 404；
     * 兼容构造（projectAccessService == null）跳过，集成级负例走真实 bean。
     */
    private void requireProjectAccess(Long projectId) {
        if (projectAccessService == null) {
            return;
        }
        if (projectId == null) {
            throw com.haitlify.aitest.platform.ai.security.AiInvocationAuthorization.notFound();
        }
        try {
            projectAccessService.requireAccess(projectId);
        } catch (org.springframework.web.server.ResponseStatusException denied) {
            throw com.haitlify.aitest.platform.ai.security.AiInvocationAuthorization.notFound();
        }
    }

    /**
     * 目标范围授权：确认/修改可以把经验发布到更广范围，必须同时对「目标」授权——
     * 全局范围仅管理员可发布（非管理员越权发布 = 知识泄露，显式 403 且不落库）；
     * PROJECT_SET 的每个目标项目都必须有权访问（无权项目与不存在同形 404）。
     * 只校验本次显式给出的目标（沿用既有目标视为已在原确认时校验）。
     */
    private void requireTargetScopeAccess(AgentMemoryScopeType scope, List<Long> targetProjectIds,
        Long sourceProjectId) {
        if (projectAccessService == null || scope == null) {
            return;
        }
        if (scope == AgentMemoryScopeType.GLOBAL) {
            boolean admin;
            try {
                admin = projectAccessService.isAdmin();
            } catch (org.springframework.web.server.ResponseStatusException unauthenticated) {
                throw com.haitlify.aitest.platform.ai.security.AiInvocationAuthorization.notFound();
            }
            if (!admin) {
                throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "仅管理员可以将经验发布为全局范围");
            }
            return;
        }
        if (scope == AgentMemoryScopeType.PROJECT_SET && targetProjectIds != null) {
            for (Long targetProjectId : targetProjectIds) {
                if (targetProjectId != null && targetProjectId.equals(sourceProjectId)) {
                    continue; // 源项目已单独校验
                }
                try {
                    projectAccessService.requireAccess(targetProjectId);
                } catch (org.springframework.web.server.ResponseStatusException denied) {
                    throw com.haitlify.aitest.platform.ai.security.AiInvocationAuthorization.notFound();
                }
            }
        }
    }

    /** 项目访问闸：无权与不存在同形 404（全局记忆 projectId 为空时不适用）。 */
    private void requireMemoryAccess(AgentMemoryResponse memory) {
        if (projectAccessService == null || memory.projectId() == null) {
            return;
        }
        try {
            projectAccessService.requireAccess(memory.projectId());
        } catch (org.springframework.web.server.ResponseStatusException denied) {
            throw com.haitlify.aitest.platform.ai.security.AiInvocationAuthorization.notFound();
        }
    }

    /** expectedRevision：提供且过期 ⇒ 409（省略保持兼容）。 */
    private void guardMemoryRevision(AgentMemoryResponse memory, Integer expectedRevision) {
        if (expectedRevision == null) {
            return;
        }
        int current = memory.revision() == null ? 1 : memory.revision();
        if (expectedRevision != current) {
            throw new com.haitlify.aitest.platform.ai.agentic.StaleArtifactRevisionException(
                "memory revision is stale: expected " + expectedRevision + " but current is " + current);
        }
    }
    private void audit(String id, String action, String actor, AgentMemoryResponse before, AgentMemoryResponse after) { try { jdbcTemplate.update("insert into ai_agent_memory_audit(audit_id, memory_id, action_type, before_json, after_json, actor_id, actor_name, created_at) values (?, ?, ?, ?, ?, ?, ?, ?)", "audit-" + UUID.randomUUID(), id, action, objectMapper.writeValueAsString(before), objectMapper.writeValueAsString(after), actor, actor, Timestamp.from(Instant.now())); } catch (Exception exception) { throw new IllegalStateException(exception); } }
    private AgentMemoryResponse get(String id) { return jdbcTemplate.queryForObject("select * from ai_agent_memories where memory_id = ?", (rs, rowNum) -> map(rs), id); }
    private AgentMemoryResponse map(java.sql.ResultSet rs) throws java.sql.SQLException { return new AgentMemoryResponse(rs.getString("memory_id"), rs.getString("source_run_id"), (Long) rs.getObject("project_id"), rs.getString("module_name"), com.haitlify.aitest.platform.ai.agentic.WebCaseAgentModels.WebCaseAgentStage.valueOf(rs.getString("stage").toUpperCase()), rs.getString("category"), rs.getString("content"), AgentMemoryStatus.valueOf(rs.getString("status").toUpperCase()), AgentMemoryScopeType.valueOf(rs.getString("scope_type").toUpperCase()), List.of(), (Integer) rs.getObject("rules_version"), rs.getString("created_by"), (Integer) rs.getObject("revision"), rs.getString("confirmed_by"), rs.getTimestamp("confirmed_at") == null ? null : rs.getTimestamp("confirmed_at").toInstant().toString(), rs.getTimestamp("created_at").toInstant().toString(), rs.getTimestamp("updated_at").toInstant().toString(), rs.getInt("hit_count"), rs.getInt("execution_success_count"), rs.getInt("execution_failure_count"), rs.getInt("confidence_score"), rs.getBoolean("confidence_locked"), rs.getTimestamp("last_hit_at") == null ? null : rs.getTimestamp("last_hit_at").toInstant().toString()); }
    private String write(Object value) { try { return objectMapper.writeValueAsString(value); } catch (Exception exception) { throw new IllegalStateException(exception); } }
    private String readScopeIds(AgentMemoryResponse memory) { return memory.scopeType() == AgentMemoryScopeType.PROJECT ? "[" + (memory.projectId() == null ? "" : memory.projectId()) + "]" : "[]"; }
    private String value(String input, String fallback) { return input == null ? (fallback == null ? "" : fallback) : input.trim(); }
    private void refreshQuality(String id, int successIncrement, int failureIncrement, Instant now) {
        AgentMemoryResponse current = get(id);
        int successes = current.executionSuccessCount() + successIncrement;
        int failures = current.executionFailureCount() + failureIncrement;
        int confidence = confidence(current.hitCount(), successes, failures);
        jdbcTemplate.update("update ai_agent_memories set execution_success_count = ?, execution_failure_count = ?, confidence_score = ?, confidence_locked = ?, updated_at = ? where memory_id = ?", successes, failures, confidence, confidence >= 85, Timestamp.from(now), id);
    }
    private int confidence(int hits, int successes, int failures) { int executionScore = successes + failures == 0 ? 0 : (int) Math.round(30D * successes / (successes + failures)); return Math.min(100, 35 + Math.min(35, hits * 5) + executionScore); }
}
