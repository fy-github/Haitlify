package com.haitlify.aitest.platform.assistant.hermes;

import tools.jackson.databind.JsonNode;

/**
 * Hermes 执行单元的接入面（计划 §5.1：首选 TUI Gateway JSON-RPC）。
 *
 * <p>暴露建会话、提交一轮提示、中断，以及可选的会话事件消费。返回值保留原始 JSON，
 * 具体字段（session_id / stored_session_id / turn 引用）由调用方按版本契约解析——
 * 平台不假设 Hermes 的 wire 字段名，绑定关系落在 attempt 账本里。
 */
public interface HermesGateway {

    record Session(String sessionId, String rawJson) {}

    record Turn(String turnRef, String rawJson) {}

    /**
     * 一次 {@code session.resume} 的可解析结果。
     *
     * <p>{@code liveSessionId} 是 pin 新铸的运行时 id（会话级 RPC 用它）；{@code storedSessionId}
     * 是网关回传的「被恢复的持久 id」，必须与请求的 {@code requestedStoredSessionId} 相同——
     * 不同即视为命中别的会话，直接失败，不允许静默替换身份。
     * {@code messageCount} 是 pin 的 {@code message_count}（只读快照）。
     */
    record Resume(String requestedStoredSessionId, String liveSessionId, String storedSessionId,
                  String status, boolean running, int messageCount, String rawJson) {}

    /** 一次 {@code session.history} 的可解析结果：{@code count} 与原始消息节点（不裁剪、不改写）。 */
    record History(String sessionId, int count, java.util.List<JsonNode> messages, String rawJson) {}

    /** 执行单元是否可用（未配置或进程无法启动时为 false；调用方必须 fail-closed）。 */
    boolean available();

    /**
     * 本连接的「服务端请求」交互能力是否已被网关确认（pin 的 {@code client.capabilities}）。
     *
     * <p>只有网关明确回包、且回包宣告了 {@code approval} 与 {@code clarify} 时才是 true；
     * 默认实现返回 false（未宣告的传输在 pin 里根本收不到 approval/clarify，声明支持就是谎报）。
     * 调用方在依赖审批/澄清前必须检查，未确认为 true 时按 fail-closed 处理。
     */
    default boolean interactionAvailable() {
        return false;
    }

    /** 交互能力状态的明确说明（未宣告的原因），用于日志与对账；不参与判定。 */
    default String interactionDetail() {
        return "该传输未宣告服务端请求能力";
    }

    Session createSession(String cwd);

    Turn submitPrompt(String sessionId, String prompt);

    /**
     * Atomically consume buffered JSON-RPC event envelopes for the exact live session_id.
     * Not stored_session_id, not a turn id. Empty means no buffered event, never completion.
     * Implementations must report lost terminal events/transport failure explicitly.
     */
    default java.util.List<tools.jackson.databind.JsonNode> drainEvents(String sessionId) {
        throw new UnsupportedOperationException("Hermes event consumption is not supported by this transport");
    }

    /** 取消已受理的轮次；无回执即视为未受理（不推断已撤销副作用）。 */
    boolean interrupt(String sessionId);

    /**
     * 上游结构化拒绝：只暴露 JSON-RPC 的<b>顶层</b>整数 {@code error.code}（拿不到就是 null）。
     *
     * <p>为什么要专门一个类型：会话回收（4001）是可以安全 resume 重投的唯一情况，而错误正文
     * 里同时可能夹带 {@code error.data.code}、上游原文提及的数字等。按文本位置取「第一个 code」
     * 会把嵌套码/无关数字误判成 4001，从而对一条其实不该恢复的会话做 resume 重投。因此这里
     * 把判定所需的最小结构化事实（顶层整数码）与正文分开：正文只用于排障，绝不参与分类。
     *
     * <p>以下形状一律记 null（不可判定，调用方必须 fail-closed）：error 不是对象、code 缺失、
     * code 不是整数（字符串/浮点）、code 超出 int 范围。嵌套的 {@code error.data.code} 永远不算顶层码。
     */
    final class UpstreamRpcError extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private final String method;
        private final Integer topLevelCode;

        public UpstreamRpcError(String method, Integer topLevelCode, String errorJson) {
            super("Hermes 拒绝 " + method
                + (topLevelCode == null ? "（上游未给出顶层整数 code）" : "（code=" + topLevelCode + "）")
                + "：" + errorJson);
            this.method = method;
            this.topLevelCode = topLevelCode;
        }

        /** 被拒绝的 JSON-RPC 方法名（只读，参与排障文案）。 */
        public String method() {
            return method;
        }

        /** 顶层 {@code error.code} 的整数值；null 表示无法结构化判定（绝不等于任何具体错误码）。 */
        public Integer topLevelCode() {
            return topLevelCode;
        }
    }

    /**
     * 按持久会话 id（stored_session_id）恢复会话，用于宿主重启/重连后的对账。
     *
     * <p>失败必须显式：非法 id、网关拒绝（未知 id）、回执缺字段、回执命中的是另一个会话，都要抛异常，
     * 不允许返回「空成功」。不支持 resume 的传输实现保持 {@link UnsupportedOperationException}。
     */
    default Resume resumeSession(String storedSessionId) {
        throw new UnsupportedOperationException("Hermes session resume is not supported by this transport");
    }

    /**
     * 读取会话历史（pin 的 {@code session.history}，参数是<b>运行时</b> session id）。
     *
     * <p>未知/已回收的运行时 id 由网关拒绝（4001），这里必须抛异常而不是返回空列表；
     * 「没有历史」只能表现为成功的 {@code count=0, messages=[]}。
     */
    default History history(String sessionId) {
        throw new UnsupportedOperationException("Hermes history read is not supported by this transport");
    }
}
