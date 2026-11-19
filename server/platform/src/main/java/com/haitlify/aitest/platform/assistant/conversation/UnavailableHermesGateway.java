package com.haitlify.aitest.platform.assistant.conversation;

import com.haitlify.aitest.platform.assistant.hermes.HermesGateway;

/**
 * 没有可用的 Hermes 传输时使用的执行单元：<b>永远不可用</b>，任何操作都显式失败。
 *
 * <p>存在的理由：对话入口打开但底层传输没配好时，唯一诚实的表现是「不可用 + 需对账」，
 * 而不是退回旧 AI 链路、也不是静默走一条没人验证过的通道。所有失败都发生在本地，
 * 没有任何副作用，因此调用方可以安全地把它记成 FAILED。
 */
final class UnavailableHermesGateway implements HermesGateway {

    private static final String DETAIL = "助手对话已启用，但没有可用的 Hermes 传输（需要 app.assistant.hermes.transport=tcp）";

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String interactionDetail() {
        return DETAIL;
    }

    @Override
    public Session createSession(String cwd) {
        throw new IllegalStateException(DETAIL);
    }

    @Override
    public Turn submitPrompt(String sessionId, String prompt) {
        throw new IllegalStateException(DETAIL);
    }

    @Override
    public boolean interrupt(String sessionId) {
        return false;
    }
}
