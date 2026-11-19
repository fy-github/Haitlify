package com.haitlify.aitest.platform.assistant.conversation;

/**
 * 执行单元的身份：{@code (tenantId, projectId, actorId)}。
 *
 * <p><b>三个分量缺一不可</b>，这是隔离的判据而不是「顺手多加一个维度」：
 * <ul>
 *   <li>{@code tenantId}：租户边界。跨租户复用同一个网关进程，等于把两个租户的 MCP 条目、模型配置
 *       与会话历史放进同一个进程；</li>
 *   <li>{@code projectId}：项目边界。同一租户下不同项目的需求/草稿互不可见，网关配置里只允许出现
 *       本项目的租约 URL；</li>
 *   <li>{@code actorId}：用户边界。同一项目里的两个用户也必须各自独立——只按 (tenant, project) 建单元
 *       会让两个用户共用一个配置文件，先到的那一轮把条目写进去，后到的那一轮读到的就是别人的能力来源。</li>
 * </ul>
 *
 * <p>键只含服务端派生的数字 id：没有任何可猜的字符串、没有 requestId、没有会话 id。{@link #toString()}
 * 也只打印这三个 id，因此它可以直接进日志。
 */
record ExecutionUnitKey(long tenantId, long projectId, long actorId) {

    ExecutionUnitKey {
        if (tenantId <= 0 || projectId <= 0 || actorId <= 0) {
            throw new IllegalArgumentException("执行单元键必须由正的 tenantId/projectId/actorId 组成");
        }
    }

    static ExecutionUnitKey of(long tenantId, long projectId, long actorId) {
        return new ExecutionUnitKey(tenantId, projectId, actorId);
    }

    @Override
    public String toString() {
        return "ExecutionUnitKey[tenant=" + tenantId + ", project=" + projectId + ", actor=" + actorId + "]";
    }
}
