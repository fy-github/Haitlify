package com.haitlify.aitest.platform.ai.agentic.rule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.haitlify.aitest.platform.ai.agentic.WebCaseAgentModels.AgentRuleSnapshot;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional
public class AgentRuleService {

    static final String RULE_SET_ID = "web-case-agent";
    private static final String GLOBAL_SCOPE = "global";
    private static final String ACTOR_NAME = com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystemName();
    private static final List<String> PHASE_FILES = List.of(
        "01-preflight", "02-evidence", "03-planning", "04-generation", "05-validation", "06-delivery"
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AgentRuleValidator validator;

    AgentRuleService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, AgentRuleValidator validator) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    public AgentRuleVersionResponse current(Long projectId) {
        ensureSeeded();
        return latestPublished(projectId).orElseGet(() -> latestPublished(null).orElseThrow());
    }

    public AgentRuleSnapshot snapshot(Long projectId) { return current(projectId).snapshot(); }

    public List<AgentRuleVersionResponse> versions(Long projectId) {
        ensureSeeded();
        String scope = scope(projectId);
        return jdbcTemplate.query(
            "select * from ai_agent_rule_versions where rule_set_id = ? and scope = ? and ((project_id is null and ? is null) or project_id = ?) order by version desc",
            (rs, rowNum) -> map(rs), RULE_SET_ID, scope, projectId, projectId
        );
    }

    public AgentRuleVersionResponse saveDraft(Long projectId, SaveAgentRuleRequest request) {
        validator.validate(request.contentText(), request.phaseRules());
        ensureSeeded();
        String scope = scope(projectId);
        int nextVersion = jdbcTemplate.queryForObject(
            "select coalesce(max(version), 0) + 1 from ai_agent_rule_versions where rule_set_id = ? and scope = ? and ((project_id is null and ? is null) or project_id = ?)",
            Integer.class, RULE_SET_ID, scope, projectId, projectId
        );
        Instant now = Instant.now();
        jdbcTemplate.update(
            "insert into ai_agent_rule_versions(rule_set_id, scope, project_id, version, status, content_text, phase_rules_json, content_hash, actor_id, actor_name, created_at, updated_at) values (?, ?, ?, ?, 'draft', ?, ?, ?, ?, ?, ?, ?)",
            RULE_SET_ID, scope, projectId, nextVersion, request.contentText(), write(request.phaseRules()), sha256(request.contentText()),
            com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystem(), ACTOR_NAME, Timestamp.from(now), Timestamp.from(now)
        );
        return findVersion(nextVersion, projectId);
    }

    public AgentRuleVersionResponse publish(Long projectId, PublishAgentRuleRequest request) {
        ensureSeeded();
        AgentRuleVersionResponse draft = findVersion(request.version(), projectId);
        validator.validate(draft.contentText(), draft.phaseRules());
        Instant now = Instant.now();
        String scope = scope(projectId);
        jdbcTemplate.update("update ai_agent_rule_versions set status = 'archived', updated_at = ? where rule_set_id = ? and scope = ? and ((project_id is null and ? is null) or project_id = ?) and status = 'published'", Timestamp.from(now), RULE_SET_ID, scope, projectId, projectId);
        jdbcTemplate.update("update ai_agent_rule_versions set status = 'published', published_at = ?, updated_at = ? where rule_set_id = ? and scope = ? and version = ? and ((project_id is null and ? is null) or project_id = ?)", Timestamp.from(now), Timestamp.from(now), RULE_SET_ID, scope, draft.version(), projectId, projectId);
        return findVersion(draft.version(), projectId);
    }

    public AgentRuleVersionResponse rollback(Long projectId, RollbackAgentRuleRequest request) {
        AgentRuleVersionResponse source = findVersion(request.version(), projectId);
        AgentRuleVersionResponse draft = saveDraft(projectId, new SaveAgentRuleRequest(source.contentText(), source.phaseRules()));
        return publish(projectId, new PublishAgentRuleRequest(draft.version()));
    }

    public AgentRuleDiffResponse diff(int fromVersion, int toVersion) {
        return new AgentRuleDiffResponse(RULE_SET_ID, fromVersion, toVersion, findVersion(fromVersion, null).contentText(), findVersion(toVersion, null).contentText());
    }

    private java.util.Optional<AgentRuleVersionResponse> latestPublished(Long projectId) {
        String scope = scope(projectId);
        List<AgentRuleVersionResponse> rows = jdbcTemplate.query(
            "select * from ai_agent_rule_versions where rule_set_id = ? and scope = ? and ((project_id is null and ? is null) or project_id = ?) and status = 'published' order by version desc limit 1",
            (rs, rowNum) -> map(rs), RULE_SET_ID, scope, projectId, projectId
        );
        return rows.stream().findFirst();
    }

    private AgentRuleVersionResponse findVersion(int version, Long projectId) {
        String scope = scope(projectId);
        return jdbcTemplate.queryForObject(
            "select * from ai_agent_rule_versions where rule_set_id = ? and scope = ? and version = ? and ((project_id is null and ? is null) or project_id = ?)",
            (rs, rowNum) -> map(rs), RULE_SET_ID, scope, version, projectId, projectId
        );
    }

    private String scope(Long projectId) { return projectId == null ? GLOBAL_SCOPE : "project"; }

    private void ensureSeeded() {
        Integer count = jdbcTemplate.queryForObject("select count(*) from ai_agent_rule_versions where rule_set_id = ?", Integer.class, RULE_SET_ID);
        if (count != null && count > 0) return;
        try {
            String content = new String(new ClassPathResource("AGENT.md").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> phaseRules = new LinkedHashMap<>();
            for (String phase : PHASE_FILES) {
                String path = "ai/agent/phases/" + phase + ".md";
                phaseRules.put(phase, new String(new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            }
            Instant now = Instant.now();
            jdbcTemplate.update(
                "insert into ai_agent_rule_versions(rule_set_id, scope, project_id, version, status, content_text, phase_rules_json, content_hash, published_at, actor_id, actor_name, created_at, updated_at) values (?, ?, ?, 1, 'published', ?, ?, ?, ?, ?, ?, ?, ?)",
                RULE_SET_ID, GLOBAL_SCOPE, null, content, write(phaseRules), sha256(content), Timestamp.from(now), com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystem(), ACTOR_NAME, Timestamp.from(now), Timestamp.from(now)
            );
        } catch (Exception exception) {
            throw new IllegalStateException("无法加载 Agent 初始规则", exception);
        }
    }

    private AgentRuleVersionResponse map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AgentRuleVersionResponse(
            rs.getString("rule_set_id"), rs.getString("scope"), (Long) rs.getObject("project_id"), rs.getInt("version"),
            rs.getString("status"), rs.getString("content_text"), read(rs.getString("phase_rules_json")), rs.getString("content_hash"),
            rs.getTimestamp("published_at") == null ? null : rs.getTimestamp("published_at").toInstant(), rs.getTimestamp("updated_at").toInstant()
        );
    }

    private Map<String, String> read(String json) {
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception exception) { throw new IllegalStateException("Agent 阶段规则 JSON 损坏", exception); }
    }

    private String write(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalArgumentException("Agent 规则格式无效", exception); }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception exception) { throw new IllegalStateException("无法计算 Agent 规则摘要", exception); }
    }
}
