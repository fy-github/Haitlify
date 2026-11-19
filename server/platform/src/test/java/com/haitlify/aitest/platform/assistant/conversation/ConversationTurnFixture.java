package com.haitlify.aitest.platform.assistant.conversation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.haitlify.aitest.platform.assistant.execution.ExecutionBindingService;
import com.haitlify.aitest.platform.security.SecretCipher;

import tools.jackson.databind.ObjectMapper;

/**
 * 对话链路用例的公共数据准备：一个租户/项目背景 + 某一轮的 generation/ACTIVE binding。
 *
 * <p>只做真实数据（真实迁移、真实 {@link ExecutionBindingService}），不 mock 任何被测服务；
 * 目的只是把「这一轮的身份、以及这一轮有没有 ACTIVE binding」这两个前提从用例里抽出来，
 * 让「武装时机」和「配置器接线」的断言更短。
 */
final class ConversationTurnFixture {

    private ConversationTurnFixture() {}

    /** 一个可提交对话的主体及其项目。 */
    record Tenant(String username, long actorId, long tenantId, long projectId) {}

    /** 一轮对应的 generation 与它唯一的 ACTIVE binding。 */
    record Turn(String generationId, String bindingId) {}

    static Tenant seedTenant(JdbcTemplate jdbc, String prefix) {
        String username = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();
        jdbc.update("insert into platform_users(username,password_hash,role,enabled,created_at,updated_at)"
            + " values (?,'unused','ADMIN',true,?,?)", username, now, now);
        long actorId = jdbc.queryForObject("select id from platform_users where username=?", Long.class, username);
        String tenantCode = "tenant-" + username;
        jdbc.update("insert into tenants(code,name,active,created_at,updated_at) values (?,'Mcp',true,?,?)",
            tenantCode, now, now);
        long tenantId = jdbc.queryForObject("select id from tenants where code=?", Long.class, tenantCode);
        String projectCode = "project-" + username;
        jdbc.update("insert into projects(code,name,description,active,tenant_id,created_at,updated_at)"
            + " values (?,'Mcp','',true,?,?,?)", projectCode, tenantId, now, now);
        long projectId = jdbc.queryForObject("select id from projects where code=?", Long.class, projectCode);
        return new Tenant(username, actorId, tenantId, projectId);
    }

    /**
     * 在<b>已存在的租户/项目</b>下再开一个主体（actor）。
     *
     * <p>只插入 {@code platform_users} 一行：租户与项目复用 {@code existing} 里的那两个 id，因此
     * 两个主体共享同一个 {@code (tenantId, projectId)}，差别只在 {@code actorId}——这正是
     * 「执行单元键里的 actorId 是否真的生效」要在真实模型下验证的那个形状。两个主体都是 ADMIN，
     * 因此不需要 {@code tenant_members}/{@code project_members} 行（访问校验对 ADMIN 不看成员表）。
     */
    static Tenant addActor(JdbcTemplate jdbc, Tenant existing, String prefix) {
        String username = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();
        jdbc.update("insert into platform_users(username,password_hash,role,enabled,created_at,updated_at)"
            + " values (?,'unused','ADMIN',true,?,?)", username, now, now);
        long actorId = jdbc.queryForObject("select id from platform_users where username=?", Long.class, username);
        return new Tenant(username, actorId, existing.tenantId(), existing.projectId());
    }

    /** 为 {@code requestId} 造一轮：generation（WAITING_WORKER）+ 一个 ACTIVE 且未过期的 binding。 */
    static Turn seedTurn(JdbcTemplate jdbc, ObjectMapper json, SecretCipher cipher,
                         ExecutionBindingService execution, Tenant tenant, String requestId) throws Exception {
        String generationId = UUID.randomUUID().toString();
        String snapshot = json.writeValueAsString(new TreeMap<>(Map.of(
            "requestId", requestId,
            "projectId", tenant.projectId(),
            "requirementRef", "requirement:conversation",
            "requirementText", "对话里产生的需求",
            "targetUrl", "https://example.com/app",
            "targetRevision", "v1")));
        jdbc.update("insert into assistant_generations(id,actor_id,project_id,tenant_id,request_id,payload_digest,"
                + "snapshot_ciphertext,state,created_at) values (?,?,?,?,?,?,?,'WAITING_WORKER',?)",
            generationId, tenant.actorId(), tenant.projectId(), tenant.tenantId(), requestId, sha256(snapshot),
            cipher.encrypt(snapshot), Instant.now());
        String unit = "unit-" + UUID.randomUUID();
        execution.confirmUnitCleaned(execution.registerUnit(
            new ExecutionBindingService.WorkerUnit(unit, "sha256:" + "b".repeat(64), "policy-v1")));
        String bindingId = execution.allocate(
            new ExecutionBindingService.AllocationRequest(generationId, unit, Duration.ofMinutes(5))).bindingId();
        return new Turn(generationId, bindingId);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
