package com.haitlify.aitest.platform.assistant.ledger;

/**
 * 业务状态机冲突（例如不在等待态却要求恢复）。
 *
 * <p>单独成型是为了让 HTTP 层只把「状态冲突」映射成 409：控制器级的
 * {@code IllegalStateException} 还包括密钥不可用、SHA-256 缺失这类基础设施故障，
 * 把它们一起改成 409 会误导客户端重试与告警。
 */
public class AssistantStateConflictException extends IllegalStateException {

    public AssistantStateConflictException(String message) {
        super(message);
    }
}
