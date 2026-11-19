package com.haitlify.aitest.platform.assistant.runtime;

import org.springframework.stereotype.Service;

import com.haitlify.aitest.platform.assistant.hermes.HermesGateway;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 平台 Runtime 的薄适配层：只委托 Hermes，不选模型、不读模型密钥、不重试、不生成业务终态。
 * 授权、幂等、UNKNOWN 对账仍属于调用方的业务账本；SUBMITTED 仅表示协议受理。
 */
@Service
public final class HermesAssistantRuntime {
    public enum State { SUBMITTED }

    /**
     * pin 的 {@code PromptSubmitResult.status}（{@code tui_gateway/contracts/prompt_voice.py}）：
     * 忙会话不拒绝输入，而是排入队列 / 并入正在跑的轮次 / 改向。四者都是「协议已受理」，
     * 但只有 {@link #ACCEPTED_NOW} 表示这一轮马上开始跑；{@code queued} 绝不是完成。
     */
    public enum Acceptance { ACCEPTED_NOW, QUEUED, STEERED, REDIRECTED }

    /**
     * 一次 prompt.submit 的受理回执。
     *
     * <p>{@code state} 固定为 {@link State#SUBMITTED}（协议受理，不等于模型完成）；
     * {@code acceptance} 给出受理方式。{@code turnRef} 只在传输真的回了 {@code turn_id} 时非空，
     * pin 的 prompt.submit 不回该字段，因此这里保持 null 而不伪造。
     */
    public record Submission(String sessionId, String storedSessionId, String turnRef, State state,
                             Acceptance acceptance) {
        /** 老调用方的四参构造：受理方式未知时按「立即受理」处理（只有显式 queued/steered/redirected 才不同）。 */
        public Submission(String sessionId, String storedSessionId, String turnRef, State state) {
            this(sessionId, storedSessionId, turnRef, state, Acceptance.ACCEPTED_NOW);
        }
    }

    private final HermesGateway gateway;
    private final ObjectMapper mapper;

    public HermesAssistantRuntime(HermesGateway gateway, ObjectMapper mapper) {
        this.gateway = gateway;
        this.mapper = mapper;
    }

    public boolean available() {
        return gateway.available();
    }

    /** 一次 {@code session.create} 的绑定信息：运行时会话 id 与（可能缺失的）持久会话 id，不含任何 payload。 */
    public record Bound(String liveSessionId, String storedSessionId) {}

    public Submission submit(String cwd, String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Hermes prompt 不能为空");
        }
        Bound bound = open(cwd);
        return submitTo(bound.liveSessionId(), bound.storedSessionId(), prompt);
    }

    /**
     * 只建会话、不提交：把建会话与提交拆开，调用方（会话级服务）才能在提交失败后仍然持有
     * 「这一轮打到哪个会话」的绑定，而不是把刚建好的会话 id 丢掉冒充「什么都没发生」。
     */
    public Bound open(String cwd) {
        if (!available()) {
            throw new IllegalStateException("Hermes 执行单元不可用，拒绝提交");
        }
        HermesGateway.Session session = gateway.createSession(cwd);
        JsonNode created = result(session.rawJson());
        String liveId = text(created, "session_id");
        if (liveId == null || !liveId.equals(session.sessionId())) {
            throw new IllegalStateException("Hermes session.create 缺少或返回不一致的会话标识");
        }
        // 绝不把短会话 ID 冒充持久 ID，也不从 RPC 请求序号拼出 turn ID。
        return new Bound(liveId, text(created, "stored_session_id"));
    }

    /**
     * 在<b>已有</b> live 会话上提交一轮：不新建会话、不改写调用方持有的绑定，也不做隐式恢复。
     *
     * <p>会话级复用与 4001→resume 的恢复策略属于 {@link HermesConversationService}（会话级服务的职责），
     * 本方法只保证「把这一轮送到指定会话并按协议受理分类」。{@code storedSessionId} 只用于回执透传，
     * 缺失时保持 null（绝不从小段 live id 推导）。
     */
    public Submission submitTo(String liveSessionId, String storedSessionId, String prompt) {
        if (liveSessionId == null || liveSessionId.isBlank()) {
            throw new IllegalArgumentException("Hermes live session id 必填");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Hermes prompt 不能为空");
        }
        if (!available()) {
            throw new IllegalStateException("Hermes 执行单元不可用，拒绝提交");
        }
        JsonNode submitted = result(gateway.submitPrompt(liveSessionId, prompt).rawJson());
        // 字段一旦出现就必须严格有效；显式拒绝/未知状态不能被另一个成功字段覆盖。
        Acceptance acceptance = acceptance(submitted);
        return new Submission(liveSessionId, storedSessionId, turnRef(submitted), State.SUBMITTED, acceptance);
    }

    /**
     * 把 {@code prompt.submit} 回执分类成受理方式；任何未知/矛盾/失败/非布尔都抛异常（fail-closed）。
     *
     * <p>pin 只会产出 {@code streaming/queued/steered/redirected}（status 仅在 typed-stop-phrase
     * 回执 {@code voice_stopped} 时缺失）。{@code accepted} 是更老传输的布尔受理位，两个字段
     * 各自只在自己的语义上生效：{@code accepted:true} 不能把 queued 改写成「立即受理」，
     * {@code status} 也不能替一个错误的 {@code accepted} 洗白。
     */
    private static Acceptance acceptance(JsonNode submitted) {
        if (submitted.has("voice_stopped")) {
            JsonNode stopped = submitted.path("voice_stopped");
            if (!stopped.isBoolean()) {
                throw new IllegalStateException("Hermes prompt.submit 的 voice_stopped 不是布尔：" + submitted);
            }
            if (stopped.booleanValue()) {
                // 说话人是在叫停，而不是提交一轮输入。
                throw new IllegalStateException("Hermes prompt.submit 是停止指令回执（voice_stopped），未受理输入");
            }
        }
        boolean hasStatus = submitted.has("status");
        boolean hasAccepted = submitted.has("accepted");
        Acceptance acceptance = null;
        if (hasStatus) {
            JsonNode status = submitted.path("status");
            if (!status.isTextual()) {
                throw new IllegalStateException("Hermes prompt.submit 的 status 不是字符串：" + submitted);
            }
            acceptance = switch (status.asString()) {
                case "streaming", "accepted" -> Acceptance.ACCEPTED_NOW;
                case "queued" -> Acceptance.QUEUED;
                case "steered" -> Acceptance.STEERED;
                case "redirected" -> Acceptance.REDIRECTED;
                default -> null;
            };
            if (acceptance == null) {
                throw new IllegalStateException("Hermes prompt.submit 返回未知状态：" + submitted);
            }
        }
        if (hasAccepted) {
            JsonNode accepted = submitted.path("accepted");
            if (!accepted.isBoolean() || !accepted.booleanValue()) {
                throw new IllegalStateException("Hermes prompt.submit 的 accepted 不是 true：" + submitted);
            }
            if (acceptance == null) {
                acceptance = Acceptance.ACCEPTED_NOW;
            }
        }
        if (acceptance == null) {
            throw new IllegalStateException("Hermes prompt.submit 未明确受理或回执矛盾");
        }
        return acceptance;
    }

    /** 只有真的出现了文本 turn_id 才记录；缺失或类型不对都保持 null，绝不拼接/伪造引用。 */
    private static String turnRef(JsonNode submitted) {
        if (!submitted.has("turn_id")) {
            return null;
        }
        JsonNode turnId = submitted.path("turn_id");
        if (!turnId.isTextual() || turnId.asString().isBlank()) {
            throw new IllegalStateException("Hermes prompt.submit 的 turn_id 非法：" + submitted);
        }
        return turnId.asString();
    }

    /**
     * Consume only an authoritative complete reply for this fresh submission.
     * This is protocol waiting, not an agent loop: no retry, no tools, no execution.
     * Timeout/disconnection/interruption never promotes SUBMITTED to success.
     */
    public String awaitFinalText(Submission submission, java.time.Duration timeout) {
        if (submission == null || submission.sessionId() == null || submission.sessionId().isBlank()
                || timeout == null || timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(java.time.Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("有效会话及不超过5分钟的等待期限必填");
        }
        long started = System.nanoTime();
        long budget = timeout.toNanos();
        do {
            requireUninterrupted();
            String completed = null;
            for (JsonNode envelope : gateway.drainEvents(submission.sessionId())) {
                JsonNode params = envelope.path("params");
                if (!"event".equals(text(envelope, "method"))
                        || !submission.sessionId().equals(text(params, "session_id"))) continue;
                String type = text(params, "type");
                if ("error".equals(type)) {
                    throw new IllegalStateException("Hermes 返回错误事件，不能认定模型成功");
                }
                if (!"message.complete".equals(type)) continue;
                JsonNode payload = params.path("payload");
                String reply = text(payload, "text");
                if (!"complete".equals(text(payload, "status")) || reply == null) {
                    throw new IllegalStateException("Hermes 终态失败、中断或无有效回复");
                }
                if (completed != null) {
                    throw new IllegalStateException("Hermes 多个终态回复无法唯一关联，需对账");
                }
                completed = reply;
            }
            requireUninterrupted();
            if (completed != null) return completed;
            long remaining = budget - (System.nanoTime() - started);
            if (remaining <= 0) break;
            try {
                java.util.concurrent.TimeUnit.NANOSECONDS.sleep(Math.min(remaining, 20_000_000L));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待 Hermes 终态被中断，结果仍需对账", ex);
            }
        } while (System.nanoTime() - started < budget);
        throw new IllegalStateException("未收到 Hermes 终态：提交受理不等于模型完成");
    }

    private static void requireUninterrupted() {
        // isInterrupted observes without clearing the caller's cancellation signal.
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("等待 Hermes 终态被中断，结果仍需对账");
        }
    }

    private JsonNode result(String raw) {
        JsonNode envelope;
        try {
            envelope = mapper.readTree(raw);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Hermes 返回无效 JSON");
        }
        if (envelope == null || !envelope.isObject() || envelope.hasNonNull("error")) {
            throw new IllegalStateException("Hermes 返回错误或无效回执");
        }
        JsonNode result = envelope.has("result") ? envelope.get("result") : envelope;
        if (result == null || !result.isObject() || result.hasNonNull("error")) {
            throw new IllegalStateException("Hermes 返回错误或无效回执");
        }
        return result;
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.path(key);
        return value.isTextual() && !value.asString().isBlank() ? value.asString() : null;
    }
}
