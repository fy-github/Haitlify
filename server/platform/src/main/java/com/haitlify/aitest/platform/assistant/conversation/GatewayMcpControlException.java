package com.haitlify.aitest.platform.assistant.conversation;

/**
 * 网关 MCP 配置失败。<b>脱敏</b>：消息只含固定的原因码，不带 URL、lease id、token。
 *
 * <p>为什么不链接底层异常：{@code reloader} 与回执都是外部实现，它们的消息可能直接打印被重载的 URL
 * （「reload failed for https://.../mcp-lease/xxx」）。一旦作为 cause 链上来，日志、堆栈与
 * {@code toString()} 都会泄漏租约 id——那是一个仍然有效的凭据。诊断信息由原因码 + 平台侧痕迹
 * （{@code MCP_CONFIGURE_FAILED}）承担，代价是放弃底层堆栈，这个取舍是刻意的。
 */
final class GatewayMcpControlException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 失败原因。调用方据此判断「可以安全当成本轮无端点」还是「必须对账」。 */
    enum Reason {
        /** 配置文件不存在、是符号链接、不是普通文件或不可读。绝不自动创建。 */
        CONFIG_UNAVAILABLE,
        /** 配置不是合法 YAML，或没有可定位的 mcp_servers 段、条目结构不符合预期。 */
        CONFIG_INVALID,
        /** 临时文件/fsync/原子 rename 失败，或目标文件不可写。 */
        WRITE_FAILED,
        /** 读到的内容在写入前被别的进程改过：拒绝覆盖，交回调用方重试。 */
        ENTRY_CHANGED,
        /** 写入成功、reload 失败；已经回滚到上一条目并重新 reload 成功。 */
        RELOAD_FAILED,
        /** reload 成功但回读（文件或网关回执）与本轮 URL 不一致；已经回滚并重新 reload 成功。 */
        READBACK_MISMATCH,
        /** 回滚本身失败（恢复写入或第二次 reload 失败）：现状未知，必须由调用方撤销租约并对账。 */
        ROLLBACK_FAILED,
        /** 同一控制面的两轮写入/reload 发生交叉：拒绝执行，绝不 reload 别人写的条目。 */
        CROSSED_RELOAD,
        /** 没有先写入就调用 reload：顺序协议被破坏。 */
        NO_WRITE_PENDING
    }

    private final Reason reason;

    GatewayMcpControlException(Reason reason) {
        super("gateway MCP configuration failed (" + reason + ")");
        this.reason = reason;
    }

    Reason reason() {
        return reason;
    }
}
