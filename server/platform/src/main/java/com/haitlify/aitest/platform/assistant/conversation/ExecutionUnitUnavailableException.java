package com.haitlify.aitest.platform.assistant.conversation;

/**
 * 本轮<b>拿不到自己的执行单元</b>：这是一条确定性的失败，调用方必须 fail-closed。
 *
 * <p>为什么必须是显式的异常而不是返回 {@code null} / 复用别的单元：
 * <ol>
 *   <li><b>复用别人的单元 = 能力错配</b>。单元的配置文件里放的是该单元唯一身份那一轮的租约 URL；
 *       把另一个 {@code (tenant, project, actor)} 的轮次塞进别人的单元，等于让那一轮的会话读到别人的
 *       MCP 条目（正是 {@code mcp/README.md} 里那条「绑定而不是查询」的错误方向）；</li>
 *   <li><b>静默降级会把容量问题藏起来</b>。池满时如果能悄悄共享，运维永远看不到「上限」这件事，
 *       直到某一天两个用户同时看到对方的条目。</li>
 * </ol>
 *
 * <p>异常只带原因码与固定文案：不链接底层异常（底层消息可能含路径/URL），也不含任何租约 id。
 */
final class ExecutionUnitUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 拿不到单元的原因。全部是「本轮未触达 wire」的确定性原因。 */
    enum Reason {
        /** 池已到容量上限，且没有该键的既有单元（不驱逐、不共享）。 */
        CAPACITY_EXHAUSTED,
        /** 基准 profile 缺少必需的 {@code config.yaml}/{@code .env}，或文件不可读/是符号链接。 */
        BASE_PROFILE_UNAVAILABLE,
        /** 复制基准 profile、建临时目录或启动子进程失败。 */
        START_FAILED,
        /** 池已被关闭（上下文关闭 / 测试收尾）。 */
        CLOSED
    }

    private final Reason reason;

    ExecutionUnitUnavailableException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    Reason reason() {
        return reason;
    }
}
