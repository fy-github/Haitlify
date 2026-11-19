package com.haitlify.aitest.platform.assistant.conversation;

/**
 * 网关 reload 失败。<b>脱敏</b>：消息只含固定的原因码，不带 URL、lease id、token，也不链接底层异常
 * （上游消息里可能打印被重载的条目 URL）。
 *
 * <p>为什么 {@link Code#CONFIRM_REQUIRED} 是一个<b>失败</b>码而不是「没关系」：pin 的
 * {@code reload.mcp} 在不带 {@code confirm=true} 且确认策略开启时，会<b>正常</b>返回
 * {@code {status: "confirm_required", message}}——JSON-RPC 成功，但一个 MCP server 都没重新发现。
 * 把它当成功，平台就会宣称「本轮 MCP 已配置」而网关仍按旧条目（或空条目）建会话：能力错配，
 * 而且没有任何日志提示。
 */
final class GatewayReloadException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 失败原因。调用方据此判断「本轮未生效」还是「传输坏了需要重连/对账」。 */
    enum Code {
        /**
         * 网关要求确认（{@code status == "confirm_required"}）：RPC 成功，但<b>没有重载</b>。
         * 本实现总是带 {@code confirm=true}，所以这通常意味着网关侧的确认策略是强制的、或参数被丢弃。
         */
        CONFIRM_REQUIRED,
        /** 网关回了 JSON-RPC {@code error} 对象：明确拒绝。 */
        RPC_ERROR,
        /** 回包不是对象、没有 {@code result} 对象、或 {@code status} 缺失/未知。 */
        UNEXPECTED_STATUS,
        /** {@code status == "reloaded"} 但没有 {@code loaded_rev}：无法证明加载的是哪一版。 */
        MISSING_LOADED_REV,
        /** 连接/超时/读线程退出：传输坏了，reload 结果未知。 */
        TRANSPORT_FAILED
    }

    private final Code code;

    GatewayReloadException(Code code) {
        super("gateway MCP reload failed (" + code + ")");
        this.code = code;
    }

    Code code() {
        return code;
    }
}
