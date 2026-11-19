package com.haitlify.aitest.platform.assistant.conversation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;

import com.haitlify.aitest.platform.assistant.execution.ExecutionBindingService;
import com.haitlify.aitest.platform.assistant.mcp.McpLeaseRegistry;

/**
 * 对话入口的 MCP 租约接线（{@code app.assistant.conversation.mcp-lease.enabled=true} 才装配）。
 *
 * <p>为什么需要它：真实模型经 Hermes 会话调用平台私有 MCP 时，网关配置里只能出现一个 URL，
 * 不能出现 grant token。所以平台在<b>派发一轮对话之前</b>自己铸一枚 grant，把 token 留在
 * {@link McpLeaseRegistry} 的内存里，只把 {@code <base>/mcp-lease/<leaseId>} 交给未来的网关配置。
 *
 * <p>纪律：
 * <ul>
 *   <li>lease id 不写库、不写日志、不进任何 HTTP 响应（{@link #endpointFor} 只给进程内的调用方）；</li>
 *   <li>租约的授权面永远不超过它对应的 grant：token 由 {@link ExecutionBindingService#issueGrant} 正常签发，
 *       解析后仍要过与 {@code /mcp} 完全相同的校验；</li>
 *   <li><b>按轮绑定</b>：武装键是「对话 + 本轮 requestId」，因此每一条租约只指向武装它的那一轮的 generation。
 *       一轮的 {@code draft_create} 只能写回本轮的需求，不可能借上一轮的能力写到上一轮去；</li>
 *   <li>找不到「与该轮 requestId 对应、仍活跃」的 generation/binding 时什么都不做（不降级、不伪造），
 *       并且不会把上一轮遗留的租约留给这一轮用。</li>
 * </ul>
 */
final class AssistantConversationLeaseProvisioner {

    /** 与 registry 的容量同阶：超出后淘汰最老的一条并显式撤销，绝不无限增长。 */
    static final int MAX_ARMED = 256;

    private final McpLeaseRegistry registry;
    private final ExecutionBindingService execution;
    private final JdbcTemplate jdbc;
    private final Duration ttl;
    private final String baseUrl;
    private final Map<TurnKey, Armed> armed = Collections.synchronizedMap(new LinkedHashMap<>());

    /**
     * 武装键：必须包含本轮身份。只用 {@code conversationId} 做键会让第二轮的武装命中第一轮的条目，
     * 于是新一轮拿到的是指向上一轮 generation 的租约——模型在新一轮会把草稿写进上一轮的需求。
     */
    private record TurnKey(String conversationId, String requestId) {}

    private record Armed(String leaseId, String endpoint) {}

    AssistantConversationLeaseProvisioner(McpLeaseRegistry registry, ExecutionBindingService execution,
                                          JdbcTemplate jdbc, Duration ttl, String baseUrl) {
        if (registry == null || execution == null || jdbc == null) {
            throw new IllegalArgumentException("registry/execution/jdbc required");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Lease TTL must be between 1 second and 5 minutes");
        }
        if (baseUrl == null || !(baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))) {
            throw new IllegalArgumentException("Lease base URL must be an absolute http(s) URL");
        }
        this.registry = registry;
        this.execution = execution;
        this.jdbc = jdbc;
        this.ttl = ttl;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * 为这一轮准备租约。找不到活跃 binding / 容量耗尽时抛异常，由调用方按「本轮无 MCP 端点」处理；
     * 任何异常都不得改变对话轮次的成败。
     *
     * <p>键是 {@code (conversationId, requestId)}，所以不同轮各自查自己的 generation/binding，
     * 各自铸自己的 grant（{@code uq_assistant_execution_grant_binding} 保证一个 binding 只有一张）。
     *
     * <p><b>只有真正的同一轮重试</b>（同 conversationId + 同 requestId，且上一条租约仍活跃）才走轮换：
     * 用同一张能力换一个新的不透明 id、撤销旧 id，并且绝不延长原来的到期时刻。这样「每轮都换 URL」与
     * 「一个 binding 一张能力」两条约束可以同时成立，也不会因为重试而攒出多张可复用的能力。
     *
     * <p>换到新一轮时，上一轮的租约立刻作废：本轮若没有自己的 generation/binding，就没有 MCP 端点，
     * 绝不把上一轮的 URL 借给这一轮（否则模型会拿陈旧能力写回上一轮的需求）。
     */
    void arm(String conversationId, long actorId, long projectId, String requestId) {
        if (conversationId == null || requestId == null) return;
        TurnKey key = new TurnKey(conversationId, requestId);
        Armed previous = armed.get(key);
        if (previous != null) {
            var current = registry.resolve(previous.leaseId()).orElse(null);
            if (current != null) {
                // 同一轮重试：只轮换 opaque id。deadline 取 min(now + ttl, 原 deadline)，永不延长。
                String rotated = registry.issue(current.token(), current.expiresAt(), ttl,
                    current.generationId(), current.bindingId(), current.actorId());
                keep(key, rotated);
                return;
            }
            armed.remove(key, previous);
        } else {
            // 新一轮：先作废上一轮遗留在本对话上的租约，本轮拿不到绑定就只意味着「本轮无端点」。
            forgetConversation(conversationId);
        }
        String generationId = first("select id from assistant_generations where actor_id=? and project_id=? and request_id=? for update",
            actorId, projectId, requestId);
        if (generationId == null) return;
        String bindingId = first("select id from assistant_execution_bindings where generation_id=? and active_generation_key is not null"
            + " and state='ACTIVE' and expires_at>? for update", generationId, Instant.now());
        if (bindingId == null) return;
        var issued = execution.issueGrant(new ExecutionBindingService.IssueRequest(bindingId, actorId));
        // Revealed exactly once and handed straight to the in-memory registry; it never becomes a field.
        String leaseId = registry.issue(issued.rawToken().reveal(), issued.expiresAt(), ttl, generationId, bindingId, actorId);
        keep(key, leaseId);
    }

    /** 记下新的 lease id 并撤销同一轮的旧 id；容量满时淘汰最老的一条并显式撤销。 */
    private void keep(TurnKey key, String leaseId) {
        String endpoint = baseUrl + McpLeaseRegistry.PATH + "/" + leaseId;
        Armed previous;
        synchronized (armed) {
            previous = armed.put(key, new Armed(leaseId, endpoint));
            while (armed.size() > MAX_ARMED) {
                var iterator = armed.entrySet().iterator();
                var oldest = iterator.next();
                iterator.remove();
                registry.revoke(oldest.getValue().leaseId());
            }
        }
        if (previous != null && !previous.leaseId().equals(leaseId)) registry.revoke(previous.leaseId());
    }

    /** 撤销并遗忘该对话所有轮次的租约（有新的一轮接手时，旧的 URL 不得继续可用）。 */
    private void forgetConversation(String conversationId) {
        List<Armed> dropped = new ArrayList<>();
        synchronized (armed) {
            var iterator = armed.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (entry.getKey().conversationId().equals(conversationId)) {
                    dropped.add(entry.getValue());
                    iterator.remove();
                }
            }
        }
        for (Armed stale : dropped) registry.revoke(stale.leaseId());
    }

    /**
     * 进程内读取本对话<b>当前轮</b>的端点；绝不进入 HTTP 响应体。
     *
     * <p>武装一个新轮次时会作废同一对话的旧轮租约，因此同一对话最多只有一条已武装租约，这里不会有歧义。
     */
    Optional<String> endpointFor(String conversationId) {
        if (conversationId == null) return Optional.empty();
        synchronized (armed) {
            for (Map.Entry<TurnKey, Armed> entry : armed.entrySet()) {
                if (entry.getKey().conversationId().equals(conversationId)) {
                    return Optional.of(entry.getValue().endpoint());
                }
            }
        }
        return Optional.empty();
    }

    /** 撤销并遗忘：租约一旦撤销，再持有 URL 也只是 401。 */
    void disarm(String conversationId) {
        forgetConversation(conversationId);
    }

    int armedCount() { return armed.size(); }

    private String first(String sql, Object... args) {
        return jdbc.query(sql, (rs, n) -> rs.getString(1), args).stream().findFirst().orElse(null);
    }
}
