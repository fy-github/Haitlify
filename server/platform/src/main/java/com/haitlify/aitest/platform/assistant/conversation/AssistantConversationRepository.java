package com.haitlify.aitest.platform.assistant.conversation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 会话账本的 JDBC 读写（不引 JPA）。
 *
 * <p>纪律：会话行的 identity（tenant/project/actor）只由服务端写入；{@code stored_session_id}
 * 是重启话的唯一依据，只被服务端读写，<b>从不出现在任何对外视图里</b>。
 * 消息的 {@code state} 是唯一的成功信号：{@code SUBMITTED} 只表示协议已受理，不表示已有回复。
 */
class AssistantConversationRepository {

    record Conversation(String id, long tenantId, long projectId, long actorId, String requestId,
                        String title, String storedSessionId, Instant createdAt) {}

    record Message(String id, String conversationId, long actorId, String requestId, String role, String body,
                   String state, String turnId, String upstreamTurnRef, String failureDetail, Instant createdAt) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    AssistantConversationRepository(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    Optional<Conversation> findConversation(String id) {
        return jdbc.query("select * from assistant_conversations where id = ?",
            (rs, rowNum) -> conversation(rs), id).stream().findFirst();
    }

    Optional<Conversation> findConversationByRequest(long actorId, long projectId, String requestId) {
        return jdbc.query("select * from assistant_conversations where actor_id = ? and project_id = ?"
            + " and request_id = ?", (rs, rowNum) -> conversation(rs), actorId, projectId, requestId)
            .stream().findFirst();
    }

    /**
     * 插入会话行；同一 (actor, project, requestId) 已存在时返回既有行（{@code created=false}）。
     * 靠唯一约束而不是「先查后写」来判重，避免两个并发请求各建一条。
     */
    InsertedConversation insertConversationIfAbsent(Conversation conversation) {
        try {
            jdbc.update("insert into assistant_conversations(id,tenant_id,project_id,actor_id,request_id,title,"
                    + "stored_session_id,created_at,updated_at) values (?,?,?,?,?,?,?,?,?)",
                conversation.id(), conversation.tenantId(), conversation.projectId(), conversation.actorId(),
                conversation.requestId(), conversation.title(), conversation.storedSessionId(),
                conversation.createdAt(), conversation.createdAt());
            return new InsertedConversation(conversation, true);
        } catch (DuplicateKeyException duplicate) {
            Conversation existing = findConversationByRequest(conversation.actorId(), conversation.projectId(),
                conversation.requestId()).orElseThrow(() -> duplicate);
            return new InsertedConversation(existing, false);
        }
    }

    record InsertedConversation(Conversation conversation, boolean created) {}

    /** 只写 bind 用的持久会话 id；空值绝不覆盖已有值（丢句柄等于丢重启能力）。 */
    void rememberStoredSession(String conversationId, String storedSessionId) {
        if (storedSessionId == null || storedSessionId.isBlank()) {
            return;
        }
        jdbc.update("update assistant_conversations set stored_session_id = ?, updated_at = ?"
            + " where id = ? and (stored_session_id is null or stored_session_id <> ?)",
            storedSessionId, Instant.now(), conversationId, storedSessionId);
    }

    Optional<Message> findMessageByRequest(String conversationId, String requestId) {
        return jdbc.query("select * from assistant_conversation_messages where conversation_id = ? and request_id = ?",
            (rs, rowNum) -> message(rs), conversationId, requestId).stream().findFirst();
    }

    Message insertMessage(Message message) {
        jdbc.update("insert into assistant_conversation_messages(id,conversation_id,actor_id,request_id,role,body_text,"
                + "state,turn_id,upstream_turn_ref,failure_detail,created_at,updated_at)"
                + " values (?,?,?,?,?,?,?,?,?,?,?,?)",
            message.id(), message.conversationId(), message.actorId(), message.requestId(), message.role(),
            message.body(), message.state(), message.turnId(), message.upstreamTurnRef(), message.failureDetail(),
            message.createdAt(), message.createdAt());
        return message;
    }

    /**
     * 把「wire 已受理」与「本轮的持久会话句柄」一次性落账（同一事务）。
     *
     * <p>为什么必须是一个事务：两件事分开写就有第二个崩溃窗口——只落了受理、或只落了句柄，
     * 之后既无法证明本轮被受理，也无法用 stored id 恢复会话。{@code accepted_at is null} 的条件让
     * 这次更新不会覆盖第一次受理的证据。
     *
     * <p><b>影响行数为 0 就是失败</b>：那说明这一行已经不在 {@code SUBMITTED}（被陈旧归一化或收尾
     * 推走了），或者受理早已记过。此时如果静默放过，句柄会单独提交、受理证据却没有落账——之后既不能
     * 把本轮当已受理（重放只得 UNKNOWN），也无法解释为什么句柄动了。所以这里抛出，让整个事务回滚，
     * 由调用方按「受理未落账」的本地失败处理（弃轮 + 对账）。
     */
    void markAccepted(String messageId, String conversationId, String upstreamTurnRef, String storedSessionId) {
        Instant now = Instant.now();
        transaction.executeWithoutResult(status -> {
            int recorded = jdbc.update("update assistant_conversation_messages set accepted_at = ?, upstream_turn_ref = ?,"
                + " updated_at = ? where id = ? and state = 'SUBMITTED' and accepted_at is null",
                now, upstreamTurnRef, now, messageId);
            if (recorded == 0) {
                throw new IllegalStateException("受理证据未落账：消息 " + messageId
                    + " 已不在 SUBMITTED（被归一化或收尾推走）或已记录过受理");
            }
            rememberStoredSession(conversationId, storedSessionId);
        });
    }

    /** 这一行是否有「网关已受理」的落账证据；没有证据的 SUBMITTED 只能是结果未知。 */
    boolean accepted(String messageId) {
        Integer total = jdbc.queryForObject("select count(*) from assistant_conversation_messages"
            + " where id = ? and accepted_at is not null", Integer.class, messageId);
        return total != null && total > 0;
    }

    /**
     * 有界陈旧归一化：把「早已超过一轮最长链」的 SUBMITTED 改成 UNKNOWN。
     *
     * <p>SUBMITTED 表示「协议受理、等待终态」，只在一轮之内成立。判据有两条，缺一不可：时间上超过
     * 调用方给出的预算，<b>并且</b>这一行不在 {@code liveMessageIds}（本 JVM 仍在派发/仍在等待的轮次）里。
     * 只看时间会把「正在等一次慢 RPC」的回合判死，也让「时间」这一侧必须覆盖最长链，而不能拍一个
     * {@code 2*replyTimeout} 的近似值。归一化只改状态与原因，不动正文与上游引用。
     *
     * @param liveMessageIds 本 JVM 仍占有的消息 id（调用方给空集合表示不排除任何行）
     */
    void normalizeStaleSubmitted(String conversationId, Instant cutoff, String failureDetail,
                                 Collection<String> liveMessageIds) {
        Collection<String> live = liveMessageIds == null ? List.of() : liveMessageIds;
        String skip = live.isEmpty() ? ""
            : " and id not in (" + String.join(",", Collections.nCopies(live.size(), "?")) + ")";
        Object[] arguments = new Object[live.size() + 4];
        arguments[0] = failureDetail;
        arguments[1] = Instant.now();
        arguments[2] = conversationId;
        arguments[3] = cutoff;
        int index = 4;
        for (String id : live) {
            arguments[index++] = id;
        }
        jdbc.update("update assistant_conversation_messages set state = 'UNKNOWN', failure_detail = ?, updated_at = ?"
            + " where conversation_id = ? and state = 'SUBMITTED' and updated_at < ?" + skip,
            arguments);
    }

    /**
     * 只改「仍处于 SUBMITTED」的行。用于在途失败的收尾：等待线程与关闭清扫可能同时收尾同一行，
     * 谁先写谁算，绝不用 UNKNOWN 覆盖一个已经落地的终态（COMPLETED 不能被降级）。
     */
    void updateMessageStateIfSubmitted(String messageId, String state, String failureDetail) {
        jdbc.update("update assistant_conversation_messages set state = ?, failure_detail = ?, updated_at = ?"
            + " where id = ? and state = 'SUBMITTED'", state, failureDetail, Instant.now(), messageId);
    }

    /**
     * 该对话最近一条用户轮次（可排除本轮自己的行）。
     *
     * <p>排序是确定的全序 {@code (created_at desc, id desc)}：{@code created_at} 并列时按 {@code id} 文本定序，
     * 同一份数据每次都得到同一行。但它<b>不代表写入先后</b>（id 是随机 UUID），所以本方法只用于
     * 「挑一条代表性行」的读取，<b>不能</b>用来证明「上一轮已结束」——那个判定看
     * {@link #countOtherOpenTurns}：只要还有一条别的行停在 SUBMITTED，账本就没有证实上一轮结束，
     * 与「最新一行是谁」无关，因此不受毫秒并列的定序影响。
     */
    Optional<Message> findLastUserMessage(String conversationId, String excludingMessageId) {
        return jdbc.query("select * from assistant_conversation_messages where conversation_id = ? and role = 'USER'"
            + " and id <> ? order by created_at desc, id desc limit 1", (rs, rowNum) -> message(rs),
            conversationId, excludingMessageId == null ? "" : excludingMessageId).stream().findFirst();
    }

    /**
     * 仍停在 {@code SUBMITTED} 的其它用户轮次数（排除本轮自己的行）。
     *
     * <p>大于 0 就说明这个对话里还有一轮「已记账、没有终态」，账本给不出「上一轮已结束」的证据。
     * 判据与 {@code created_at} 的排序无关，因此毫秒并列也不会让它挑错行——它数的是全部开着的行。
     */
    int countOtherOpenTurns(String conversationId, String excludingMessageId) {
        Integer total = jdbc.queryForObject("select count(*) from assistant_conversation_messages"
            + " where conversation_id = ? and role = 'USER' and id <> ? and state = 'SUBMITTED'",
            Integer.class, conversationId, excludingMessageId == null ? "" : excludingMessageId);
        return total == null ? 0 : total;
    }

    /**
     * 可能已经到达 wire 的历史轮次数（排除本轮自己的行）。
     *
     * <p>{@code FAILED} 是「已知零副作用」（网关不可达、参数非法等本地失败），不计数；其余状态
     * （{@code SUBMITTED/UNKNOWN/COMPLETED}）都表示这一对话可能有上一轮真的打到了网关。
     * 调用方用它决定「没有持久会话句柄时能否新建会话」。
     */
    int countTurnsThatMayHaveReachedWire(String conversationId, String excludingMessageId) {
        Integer total = jdbc.queryForObject("select count(*) from assistant_conversation_messages"
            + " where conversation_id = ? and role = 'USER' and id <> ? and state <> 'FAILED'",
            Integer.class, conversationId, excludingMessageId == null ? "" : excludingMessageId);
        return total == null ? 0 : total;
    }

    /** 只改状态与原因，不动正文（正文是用户输入的原样留痕）。 */
    void updateMessageState(String messageId, String state, String failureDetail) {
        jdbc.update("update assistant_conversation_messages set state = ?, failure_detail = ?, updated_at = ?"
            + " where id = ?", state, failureDetail, Instant.now(), messageId);
    }

    /**
     * 一轮真正完成：用户消息转 COMPLETED，并把模型回复作为独立的 ASSISTANT 消息写入。
     * 两步在一个事务里，避免出现「用户消息已完成但没有正文」的假成功。
     */
    void completeTurn(String userMessageId, String conversationId, long actorId, String replyText) {
        Instant now = Instant.now();
        transaction.executeWithoutResult(status -> {
            updateMessageState(userMessageId, "COMPLETED", null);
            insertMessage(new Message(java.util.UUID.randomUUID().toString(), conversationId, actorId, null,
                "ASSISTANT", replyText, "COMPLETED", null, null, null, now));
        });
    }

    List<Message> listMessages(String conversationId, int limit, int offset) {
        return jdbc.query("select * from assistant_conversation_messages where conversation_id = ?"
            + " order by created_at, id limit ? offset ?", (rs, rowNum) -> message(rs), conversationId, limit, offset);
    }

    int countMessages(String conversationId) {
        Integer total = jdbc.queryForObject("select count(*) from assistant_conversation_messages"
            + " where conversation_id = ?", Integer.class, conversationId);
        return total == null ? 0 : total;
    }

    private static Conversation conversation(ResultSet rs) throws SQLException {
        return new Conversation(rs.getString("id"), rs.getLong("tenant_id"), rs.getLong("project_id"),
            rs.getLong("actor_id"), rs.getString("request_id"), rs.getString("title"),
            rs.getString("stored_session_id"), rs.getTimestamp("created_at").toInstant());
    }

    private static Message message(ResultSet rs) throws SQLException {
        return new Message(rs.getString("id"), rs.getString("conversation_id"), rs.getLong("actor_id"),
            rs.getString("request_id"), rs.getString("role"), rs.getString("body_text"), rs.getString("state"),
            rs.getString("turn_id"), rs.getString("upstream_turn_ref"), rs.getString("failure_detail"),
            rs.getTimestamp("created_at").toInstant());
    }
}
