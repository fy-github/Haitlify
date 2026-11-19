package com.haitlify.aitest.platform.assistant.runtime;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Set;

import com.haitlify.aitest.platform.assistant.hermes.HermesGateway;
import tools.jackson.databind.ObjectMapper;

/**
 * 会话级薄适配：把「业务会话键 → Hermes 运行时会话」的绑定、恢复与互斥集中到一个地方，
 * 让同一业务会话的多轮提交复用同一个 live 会话，而不是每轮 {@code session.create}。
 *
 * <p>纪律与 {@link HermesAssistantRuntime} 一致：只做协议层的复用/恢复，不选模型、不读密钥、不重试、
 * 不做业务终态。授权、幂等与对账仍属于调用方的业务账本。
 *
 * <p>绑定只存内存（{@code liveSessionId} + {@code storedSessionId}），<b>不是持久化</b>：
 * 进程重启后映射消失，调用方账本里持久的 {@code stored_session_id} 才是恢复依据——重启或绑定被淘汰后，
 * 用 {@link #adopt(String, String, String)} 走 {@code session.resume} 重建 live 会话。
 * 本类不改 DB schema、不注册调度器、不是 Spring Bean（由调用方显式构造）。
 *
 * <p>并发纪律：同一会话同一时刻只允许一个「未终态」提交（受理之后、终态被消费之前）。
 * 第二个提交不会排队，而是立刻收到明确的 {@link IllegalStateException}；这样既不会在 wire 上
 * 制造两轮并存，也不会把 queued 当成完成。终态等待者同样唯一，避免两个消费者抢同一批终态事件。
 *
 * <p><b>终态归属纪律（为什么这里不能「提交成功即等终态」）</b>：pin 的 {@code prompt.submit}
 * 不回 {@code turn_id}，{@code message.complete} 事件只带 {@code session_id}，所以本实现<b>无法</b>
 * 给事件打上轮次标签。归属只能靠三个前提推断：（1）提交前该会话必须是空闲的（否则排进来的本轮只会
 * 拿到 {@code queued}，而先到达的终态属于上一轮）；（2）提交前清空该 live 会话上已缓冲的事件
 * （任何在本轮提交之前到达的终态都属于旧轮次）；（3）{@code prompt.submit} 的受理方式必须是
 * {@code ACCEPTED_NOW}——{@code queued/steered/redirected} 是「提交时并不空闲」的结构化证据，
 * 说明终态归属不可判定，只能 fail-closed 并要求对账。三个前提任一不成立就不提交、或丢弃旧终态，
 * 绝不把「上一轮的 message.complete」当成本轮答案——那是假成功，比失败更危险。
 */
public final class HermesConversationService {

    /** 绑定表默认上限：防止业务会话键无界增长（每个键只是一条 id 绑定，不含任何 payload）。 */
    public static final int DEFAULT_MAX_CONVERSATIONS = 256;

    /** 恢复后等待会话变空闲的默认上限；超过就不提交，交给调用方对账。 */
    public static final Duration DEFAULT_RESUME_IDLE_WAIT = Duration.ofSeconds(10);

    /** 重读恢复状态的最小间隔（resume 是本类唯一能读到 running/status 的只读入口）。 */
    private static final Duration RESUME_POLL_INTERVAL = Duration.ofMillis(25);

    /** pin 的 JSON-RPC 错误码：运行时会话 id 未知/已回收（tui_gateway 对未知 live id 回 4001）。 */
    private static final int SESSION_NOT_FOUND = 4001;

    /**
     * pin 里「这一轮还在跑」的状态。命中任一（或 {@code running=true}）就说明上一个轮次尚未结束，
     * 此时提交只会被排进上一轮的队列，随后到达的终态属于上一轮。
     *
     * <p>{@code waiting} 必须算忙：pin 只在「有一个未回答的 server→client 请求（审批/澄清）」时返回它
     * （{@code server_requests.open_request_count} 明确写 must not be treated as idle），
     * 即上一轮被人工挡住、尚未产生终态；且 {@code running=false + status=waiting} 是合法组合
     * （{@code running} 只反映 session["running"]，与 pending 请求是两套状态），不能靠 running 兜底。
     */
    private static final Set<String> BUSY_STATUSES =
        Set.of("working", "starting", "streaming", "resuming", "waiting");

    private final HermesGateway gateway;
    private final HermesAssistantRuntime runtime;
    private final int maxConversations;
    /** 恢复后等待会话变空闲的上限：超过它就不提交，交给调用方对账（构造参数只为可测性）。 */
    private final Duration resumeIdleWait;
    /** 访问序 LRU：淘汰永远从「最久未用且没有未终态提交」的绑定开始。 */
    private final LinkedHashMap<String, Binding> bindings = new LinkedHashMap<>(16, 0.75f, true);

    public HermesConversationService(HermesGateway gateway, ObjectMapper mapper) {
        this(gateway, mapper, DEFAULT_MAX_CONVERSATIONS);
    }

    public HermesConversationService(HermesGateway gateway, ObjectMapper mapper, int maxConversations) {
        this(gateway, mapper, maxConversations, DEFAULT_RESUME_IDLE_WAIT);
    }

    public HermesConversationService(HermesGateway gateway, ObjectMapper mapper, int maxConversations,
                                     Duration resumeIdleWait) {
        if (gateway == null || mapper == null) {
            throw new IllegalArgumentException("Hermes gateway 与 ObjectMapper 必填");
        }
        if (maxConversations <= 0) {
            throw new IllegalArgumentException("会话表上限必须为正数");
        }
        if (resumeIdleWait == null || resumeIdleWait.isZero() || resumeIdleWait.isNegative()) {
            throw new IllegalArgumentException("等待会话空闲的期限必须为正");
        }
        this.gateway = gateway;
        this.runtime = new HermesAssistantRuntime(gateway, mapper);
        this.maxConversations = maxConversations;
        this.resumeIdleWait = resumeIdleWait;
    }

    /**
     * 在业务会话上提交一轮。
     *
     * <p>首次调用会建会话并记住 live+stored id；之后同一键复用同一 live id。若 live id 已被网关回收
     * （顶层 {@code error.code=4001}），用记住的 stored id 走 {@code session.resume} 拿到新 live id 再提交；
     * 恢复失败显式报错，绝不用 {@code session.create} 假装恢复成功。
     *
     * <p>只有受理方式为 {@link HermesAssistantRuntime.Acceptance#ACCEPTED_NOW} 返回的提交才代表本轮已开始；
     * {@code queued/steered/redirected} 一律 fail-closed（需对账），不进入终态等待、不返回任何文本。
     */
    public HermesAssistantRuntime.Submission submit(String conversationKey, String cwd, String prompt) {
        String key = requireKey(conversationKey);
        requirePrompt(prompt);
        return turn(key, beginTurn(key), cwd, prompt);
    }

    /**
     * 由调用方账本重挂一条已知持久会话（重启、绑定被淘汰或换实例后的恢复入口）：
     * 直接 {@code session.resume(stored)}，不新建会话、不猜测。
     */
    public HermesAssistantRuntime.Submission adopt(String conversationKey, String storedSessionId, String prompt) {
        String key = requireKey(conversationKey);
        requirePrompt(prompt);
        if (storedSessionId == null || storedSessionId.isBlank()) {
            throw new IllegalArgumentException("持久会话 id（stored_session_id）必填");
        }
        Binding binding = beginTurn(key);
        synchronized (binding) {
            // 旧 live id 属于另一次挂载，重挂时必须丢弃，否则会把这一轮送到陈旧会话上。
            binding.liveSessionId = null;
            binding.storedSessionId = storedSessionId;
        }
        return turn(key, binding, null, prompt);
    }

    /**
     * 只接受该 live 会话的 {@code message.complete(status=complete)}；超时、错误事件、多终态、
     * 中断与流中断一律失败（{@link HermesAssistantRuntime#awaitFinalText} 已有有界期限与 fail-closed 语义）。
     *
     * <p>无论成功或失败都会释放该会话的未终态占位，让下一轮可以提交；终态事件在被消费前不会静默丢弃
     * （传输层丢失事件会抛异常，而不是返回成功）。
     */
    public String awaitFinalText(String conversationKey, Duration timeout) {
        String key = requireKey(conversationKey);
        if (timeout == null) {
            throw new IllegalArgumentException("等待期限必填");
        }
        Binding binding;
        HermesAssistantRuntime.Submission submission;
        synchronized (bindings) {
            binding = bindings.get(key);
        }
        if (binding == null) {
            throw new IllegalStateException("会话 " + key + " 没有等待终态的提交");
        }
        synchronized (binding) {
            if (binding.awaiting) {
                throw new IllegalStateException("会话 " + key + " 已有等待者在消费终态，拒绝重复等待");
            }
            if (binding.inFlight == null) {
                throw new IllegalStateException("会话 " + key + " 没有等待终态的提交");
            }
            submission = binding.inFlight;
            binding.awaiting = true;
        }
        try {
            return runtime.awaitFinalText(submission, timeout);
        } finally {
            synchronized (binding) {
                binding.awaiting = false;
                if (binding.inFlight == submission) {
                    binding.inFlight = null;
                }
            }
        }
    }

    /**
     * 清掉「上一次提交结果未知」的占位。<b>调用方必须自己完成对账</b>（读会话历史/中断/确认无副作用）
     * 再调用；本方法只放开互斥，绝不推断成功或失败。
     *
     * <p><b>它同时是「内存闸门」的唯一复位入口</b>：权威在调用方账本里——账本证实「没有任何一轮还开着」
     * 时，这个内存里的占位就只是缓存，必须被清掉。因此 {@code inFlight} 在这里无条件清空（含被漏掉的
     * 残留在途句柄），否则一个残句柄会顶替账本，把对话永远挡在互斥闸门之外。
     *
     * <p>键上没有绑定（从未提交、或已被容量淘汰）时<b>显式报错</b>：静默 return 会让调用方以为闸门
     * 已放开，而实际上它可能与一条仍然存活的会话并行——绑定丢了就必须用 stored id 重新挂载。
     */
    public void reconcile(String conversationKey) {
        String key = requireKey(conversationKey);
        synchronized (bindings) {
            Binding binding = bindings.get(key);
            if (binding == null) {
                throw new IllegalStateException("会话 " + key
                    + " 没有绑定（从未提交或已被容量淘汰），无法对账；"
                    + "绑定已丢失，请用 adopt(会话键, stored_session_id, prompt) 重新挂载后再对账");
            }
            synchronized (binding) {
                binding.needsReconcile = false;
                binding.inFlight = null;
                binding.abandonedReason = null;
            }
        }
    }

    /**
     * <b>放弃一轮</b>：本轮在会话服务里的占位（{@code inFlight}）已经不再有人消费，必须安全释放，
     * 同时明确标记「需要调用方对账」。
     *
     * <p>为什么需要这个操作：受理（{@code ACCEPTED_NOW}）之后，本端仍可能因为<b>本地</b>原因失败
     * ——受理账未落库、回复消费者队列已满。此时上游可能已经在跑这一轮，而本端再也拿不到它的终态。
     * 之前的实现把占位留在绑定上、又因为 {@code inFlight != null} 而让 {@link #needsReconcile(String)}
     * 返回 false，于是 {@link #reconcile(String)} 永远不会被调用，该会话此后每一轮都被互斥闸门挡住，
     * 直到进程重启——一个纯粹的砖化。本方法给出的正是那条缺失的「有主的释放」路径。
     *
     * <p>语义边界（不许越过）：
     * <ul>
     *   <li><b>不归因</b>：不推断、不记录本轮的终态，不把任何上游事件当成本轮答案；只把占位与 live id 丢掉。</li>
     *   <li><b>要求对账</b>：置 {@code needsReconcile=true}（并把 {@code reason} 带进后续闸门文案），
     *       调用方必须先拿到账本证据才能 {@link #reconcile(String)} 放闸。</li>
     *   <li><b>丢弃 live id</b>：这一轮的终态归属已丢失，同一条 live 会话上可能仍有轮次在跑；保留 live id
     *       会让下一轮直接 {@code prompt.submit} 到它上面（两轮并存）。丢掉 live 后下一轮只能经
     *       {@code session.resume} + 空闲判定重新挂载（{@link #resumeIdle}），空闲才提交。
     *       {@link #failTurn} 在 {@code resultKnown=false} 时执行<b>完全相同</b>的处理——「结果未知」
     *       只有一种恢复语义，不允许一条路径丢 live、另一条留着，让下一轮在「live 直投」与
     *       「resume 空闲判定」之间二选一。</li>
     *   <li><b>幂等且不抛</b>：没有绑定、没有占位时是无操作——它总是在错误收尾路径上被调用，
     *       不允许再用一个新异常盖掉最初的失败原因。</li>
     * </ul>
     *
     * @param reason 固定的原因文案（只进闸门提示，不得携带上游回执或 payload）；null 视为未说明
     */
    public void abandon(String conversationKey, String reason) {
        String key = requireKey(conversationKey);
        synchronized (bindings) {
            Binding binding = bindings.get(key);
            if (binding == null) {
                // 没有绑定就没有占位可漏；也不凭空造一个待对账闸门（那会挡住一条本来干净的会话）。
                return;
            }
            synchronized (binding) {
                if (binding.awaiting) {
                    // 有消费者正持有这次提交：释放与归因归它，抢它的状态只会制造第二个真相。
                    return;
                }
                binding.inFlight = null;
                binding.liveSessionId = null;
                binding.needsReconcile = true;
                binding.abandonedReason = reason;
            }
        }
    }

    /** 当前绑定数（有界，便于运维断言；不含任何会话内容）。 */
    public int conversationCount() {
        synchronized (bindings) {
            return bindings.size();
        }
    }

    /**
     * 该业务会话键在本进程内是否已有绑定（<b>只读统计</b>，不改任何状态位、不触碰 wire）。
     *
     * <p>调用方用它区分「本进程首次提交」与「已复用中的会话」：前者在有持久 id 时必须走
     * {@link #adopt}（session.resume）而不是 {@link #submit}（首次会 session.create），
     * 否则重启或绑定被容量淘汰后会新开一条会话、丢掉续聊语义。
     */
    public boolean isBound(String conversationKey) {
        String key = requireKey(conversationKey);
        synchronized (bindings) {
            return bindings.containsKey(key);
        }
    }

    /**
     * 该业务会话是否正处在「上一轮结果未知，禁止再提交」的闸门状态（<b>只读</b>：不改任何状态位、
     * 不触碰 wire、不做推断）。
     *
     * <p>给调用方一个可观测的判定点：它应当知道<b>要不要</b>在下一轮提交前做一次
     * {@link #reconcile(String)}，否则这个闸门只能靠异常文本猜。返回值刻意只在「闸门本身在生效」
     * 时为 true——提交中、等待终态、或根本没有绑定时都返回 false，避免调用方误判成可以放闸。
     *
     * <p><b>它不是闸门的权威</b>：权威是调用方账本里「有没有别的行还开着」。本方法只回答「本进程是否
     * 还留着一个未释放的结果未知状态」，{@code inFlight != null} 这类缓存残留会让它返回 false，
     * 因此任何「先看它再决定是否提交」的用法都会把缓存当成权威。
     */
    public boolean needsReconcile(String conversationKey) {
        String key = requireKey(conversationKey);
        synchronized (bindings) {
            Binding binding = bindings.get(key);
            if (binding == null) {
                return false;
            }
            synchronized (binding) {
                return binding.needsReconcile && !binding.submitting && binding.inFlight == null && !binding.awaiting;
            }
        }
    }

    private HermesAssistantRuntime.Submission turn(String key, Binding binding, String cwd, String prompt) {
        try {
            HermesAssistantRuntime.Submission submission = dispatch(key, binding, cwd, prompt);
            requireAcceptedNow(key, binding, submission);
            synchronized (binding) {
                binding.submitting = false;
                binding.inFlight = submission;
            }
            return submission;
        } catch (TurnFailure failure) {
            failTurn(key, binding, failure.resultKnown);
            throw failure;
        } catch (RuntimeException unexpected) {
            // 非协议路径的异常同样按「结果未知」fail-closed，避免同一会话两轮并存。
            failTurn(key, binding, false);
            throw new TurnFailure(describe("prompt.submit", key, unexpected), false);
        }
    }

    /**
     * 只有 {@link HermesAssistantRuntime.Acceptance#ACCEPTED_NOW} 才能把随后的终态归因于本轮。
     *
     * <p>{@code queued/steered/redirected} 是「提交时该会话并不空闲」的结构化证据：它们意味着本轮
     * 输入被排进上一轮之后、被并进正在跑的那一轮、或被改向，因此接下来到达的 {@code message.complete}
     * 属于上一轮（或某条无法区分的轮次），而不是本轮。pin 的 {@code prompt.submit} 不回 {@code turn_id}、
     * 终态事件只带 {@code session_id}，客户端没有任何字段能补救这个归属——唯一诚实的结论是「归属不可判定」：
     * 不进入等待（不写 {@code inFlight}）、不消费任何终态、绝不把文本返回给调用方，并要求对账。
     *
     * <p>为什么必须 {@code needsReconcile}：这一轮的 prompt 确实已经送到网关（可能稍后才执行），
     * 结果对本端未知；若直接放开互斥，下一次提交可能与那条仍在跑的轮次并存。
     */
    private void requireAcceptedNow(String key, Binding binding, HermesAssistantRuntime.Submission submission) {
        HermesAssistantRuntime.Acceptance acceptance = submission == null ? null : submission.acceptance();
        if (acceptance == HermesAssistantRuntime.Acceptance.ACCEPTED_NOW) {
            return;
        }
        String live;
        synchronized (binding) {
            live = binding.liveSessionId;
        }
        throw new TurnFailure("Hermes prompt.submit 的受理方式是 " + acceptance
            + "（不是 ACCEPTED_NOW）：提交时会话 " + key + " 并不空闲，本轮输入已被排队/并入/改向，"
            + "随后到达的 message.complete 属于上一轮而非本轮，无法归因；"
            + "未进入终态等待，也未返回任何文本（live=" + live + "），结果未知需对账", false);
    }

    /**
     * 把一轮送到正确的会话上：live 可用就直接提交；live 被回收（顶层 4001）就降级到 stored 并 resume；
     * 只有「既无 live 也无 stored」的首次会话才允许 {@code session.create}。
     *
     * <p>分类只认结构化事实（{@link HermesGateway.UpstreamRpcError#topLevelCode()}），不读错误正文：
     * 嵌套码、多个互相矛盾的码、无法解析的码都按「未知失败」处理（需对账），绝不 resume 重投。
     */
    private HermesAssistantRuntime.Submission dispatch(String key, Binding binding, String cwd, String prompt) {
        String live;
        String stored;
        synchronized (binding) {
            live = binding.liveSessionId;
            stored = binding.storedSessionId;
        }
        if (live != null) {
            try {
                return submitOn(live, stored, prompt);
            } catch (RuntimeException ex) {
                if (!sessionRecycled(ex)) {
                    throw new TurnFailure(describe("prompt.submit", key, ex), false);
                }
                // 运行时 id 已被回收：丢弃它，改用持久 id 恢复；不把这次失败当成「已提交」。
                synchronized (binding) {
                    binding.liveSessionId = null;
                }
                if (stored == null) {
                    // 会话确实没了，但没有任何持久 id 能证明「恢复到的是同一条会话」。
                    // 这里的唯一合法结论是失败：绝不 session.create 一条新会话冒充续聊。
                    throw new TurnFailure("Hermes 运行时会话已被回收（顶层 code=" + SESSION_NOT_FOUND
                        + "）且没有持久 session id（会话 " + key + "），无法证明可恢复到同一会话，"
                        + "未自动新建会话，需人工对账", true);
                }
            }
        }
        if (stored != null) {
            String resumed = resumeIdle(key, stored);
            synchronized (binding) {
                binding.liveSessionId = resumed;
            }
            try {
                return submitOn(resumed, stored, prompt);
            } catch (RuntimeException ex) {
                throw new TurnFailure(describe("prompt.submit", key, ex), false);
            }
        }
        // 只有「既无 live 也无 stored」的首次会话才走到这里：建会话后立刻记账，
        // 这样即使随后的 prompt.submit 失败，绑定也不会丢掉刚建好的会话 id。
        HermesAssistantRuntime.Bound opened;
        try {
            opened = runtime.open(cwd);
        } catch (RuntimeException ex) {
            throw new TurnFailure(describe("session.create", key, ex), false);
        }
        synchronized (binding) {
            binding.liveSessionId = opened.liveSessionId();
            binding.storedSessionId = opened.storedSessionId();
        }
        try {
            return submitOn(opened.liveSessionId(), opened.storedSessionId(), prompt);
        } catch (RuntimeException ex) {
            throw new TurnFailure(describe("prompt.submit", key, ex), false);
        }
    }

    /**
     * 提交前先做「旧终态屏障」：清掉该 live 会话上已缓冲的全部事件，再提交。
     *
     * <p>为什么只能这样做：pin 的 {@code prompt.submit} 不回 {@code turn_id}，终态事件只带
     * {@code session_id}，因此实现无法把 {@code message.complete} 关联到具体某一次提交。若不清空，
     * 上一轮遗留的终态会在本轮 {@code awaitFinalText} 里被第一个读到，从而把「上一轮的答案」
     * 当成本轮成功（假成功）。所以归属由两条规则共同保证：提交前会话必空闲（见
     * {@link #resumeIdle}）+ 提交前清空旧事件（本方法）。
     *
     * <p>剩下的窄窗口（屏障之后、prompt.submit 之前的几微秒）无法在客户端消除；出现时表现为
     * 「本轮等不到自己的终态」而失败，而不是伪造成成功——失败的代价是可对账，假成功不可对账。
     */
    private HermesAssistantRuntime.Submission submitOn(String liveSessionId, String storedSessionId, String prompt) {
        discardStaleEvents(liveSessionId);
        return runtime.submitTo(liveSessionId, storedSessionId, prompt);
    }

    /**
     * 丢弃该 live 会话上早于本次提交的缓冲事件（终态与增量都丢：它们属于上一轮）。
     *
     * <p>传输不提供事件消费（{@link UnsupportedOperationException}）时没有可丢弃的东西：这类传输上
     * 也不存在「消费到旧终态」的路径，后续 {@code awaitFinalText} 会按「不支持事件消费」显式失败。
     * 其余异常一律不掩盖（传输已坏就 fail-closed，不允许盲提交）。
     */
    private void discardStaleEvents(String liveSessionId) {
        if (liveSessionId == null || liveSessionId.isBlank()) {
            return;
        }
        try {
            gateway.drainEvents(liveSessionId);
        } catch (UnsupportedOperationException noEventStream) {
            // 见方法注释：该传输没有事件缓冲，也就没有旧终态可误认。
        }
    }

    /**
     * {@code session.resume}：只在会话<b>空闲</b>时返回可提交的 live id。
     *
     * <p>恢复进一条仍在运行（{@code running=true} 或 status 属于 working/starting/streaming/resuming/waiting）
     * 的会话时，任何提交都只会被并入/排到上一轮之后，随后到达的终态属于上一轮——直接提交就是把旧终态
     * 当本轮答案。因此这里在有界期限内重读恢复状态等待其变空闲，达到上限仍非空闲则 fail-closed
     * （需对账），绝不猜测归属。
     *
     * <p>4007（未知持久 id）/ 传输不支持 resume / 回执畸形都只有一个结论：这条会话恢复不了。
     * 此时绑定只保留 stored id（结果已知，不需要 reconcile），下一次仍走 resume，而不是 create。
     *
     * <p>重读用的是同一条持久 id（{@code session.resume} 是唯一能读到 {@code running/status} 的只读入口），
     * 每次重读都可能铸出一个新的运行时 id：只采用最后一次读到的那个，更早的读过即弃、绝不向它提交。
     */
    private String resumeIdle(String key, String storedSessionId) {
        HermesGateway.Resume resume = readResume(key, storedSessionId);
        long started = System.nanoTime();
        long budget = resumeIdleWait.toNanos();
        while (!idle(resume)) {
            long remaining = budget - (System.nanoTime() - started);
            if (remaining <= 0) {
                throw new TurnFailure("Hermes session.resume 恢复出的会话仍在运行（会话 " + key
                    + "，stored=" + storedSessionId + "，" + stateText(resume)
                    + "），提交只会并入上一轮，其终态不属于本轮；拒绝提交，结果未知需对账", false);
            }
            sleepNanos(Math.min(remaining, RESUME_POLL_INTERVAL.toNanos()), key);
            resume = readResume(key, storedSessionId);
        }
        return resume.liveSessionId();
    }

    private HermesGateway.Resume readResume(String key, String storedSessionId) {
        HermesGateway.Resume resume;
        try {
            resume = gateway.resumeSession(storedSessionId);
        } catch (UnsupportedOperationException ex) {
            throw new TurnFailure("Hermes 传输不支持 session.resume，无法恢复会话 " + key
                + "（stored=" + storedSessionId + "），未自动新建会话", true);
        } catch (RuntimeException ex) {
            throw new TurnFailure("Hermes session.resume 失败（会话 " + key + "，stored=" + storedSessionId
                + "，" + codeText(ex) + "），未自动新建会话，需人工对账", true);
        }
        if (resume == null || resume.liveSessionId() == null || resume.liveSessionId().isBlank()) {
            throw new TurnFailure("Hermes session.resume 未返回运行时会话 id（会话 " + key + "），未自动新建会话", true);
        }
        return resume;
    }

    /** 只有 running=false 且状态不在 {@link #BUSY_STATUSES} 时才算空闲。 */
    private static boolean idle(HermesGateway.Resume resume) {
        return !resume.running() && !BUSY_STATUSES.contains(resume.status());
    }

    private static String stateText(HermesGateway.Resume resume) {
        return "status=" + resume.status() + "，running=" + resume.running();
    }

    private static void sleepNanos(long nanos, String key) {
        try {
            java.util.concurrent.TimeUnit.NANOSECONDS.sleep(nanos);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new TurnFailure("等待会话 " + key + " 变空闲被中断，结果未知需对账", false);
        }
    }

    /**
     * 预留一次提交：容量、对账、并发三道闸都在这里，任何一道不通过都不会碰 wire。
     * 返回的绑定在 {@code submitting=true} 期间独占该会话。
     */
    private Binding beginTurn(String key) {
        synchronized (bindings) {
            Binding binding = bindings.get(key);
            if (binding == null) {
                evictForCapacity();
                binding = new Binding();
                bindings.put(key, binding);
            }
            synchronized (binding) {
                if (binding.needsReconcile) {
                    throw new IllegalStateException("会话 " + key + " 上一次提交结果未知"
                        + (binding.abandonedReason == null ? "" : "（" + binding.abandonedReason + "）")
                        + "，需先 reconcile(会话键) 对账后再提交");
                }
                if (binding.submitting || binding.inFlight != null || binding.awaiting) {
                    throw new IllegalStateException("会话 " + key
                        + " 已有未终态提交（提交中或等待终态），拒绝并发提交：同一会话不得同时存在两轮未完成");
                }
                binding.submitting = true;
            }
            return binding;
        }
    }

    /**
     * 容量淘汰：只淘汰「既没有未终态提交、也不需要先对账」的绑定（LRU 最久未用者）。
     *
     * <p>{@code needsReconcile} 的绑定是<b>不可淘汰</b>的：它是「上一轮结果未知，禁止再提交」的闸门，
     * 淘汰它等于把闸门连同会话一起丢掉，下一次提交会新建一条会话，从而与可能仍存活的那一轮并行。
     * 因此全部绑定都不可淘汰时，明确拒绝新会话，而不是静默丢失闸门或在途会话。
     * 被淘汰的会话可凭调用方账本里的 stored id 用 {@link #adopt} 重新挂上。
     */
    private void evictForCapacity() {
        while (bindings.size() >= maxConversations) {
            String victim = null;
            for (var entry : bindings.entrySet()) {
                Binding candidate = entry.getValue();
                synchronized (candidate) {
                    if (!candidate.submitting && candidate.inFlight == null && !candidate.awaiting
                            && !candidate.needsReconcile) {
                        victim = entry.getKey();
                        break;
                    }
                }
            }
            if (victim == null) {
                throw new IllegalStateException("会话表已达上限 " + maxConversations
                    + "，且所有绑定都在途或待对账，拒绝为新会话腾位（不淘汰在途或待对账的会话）");
            }
            bindings.remove(victim);
        }
    }

    private void failTurn(String key, Binding binding, boolean resultKnown) {
        synchronized (bindings) {
            synchronized (binding) {
                binding.submitting = false;
                if (!resultKnown) {
                    binding.needsReconcile = true;
                    // 「结果未知」统一恢复语义（与 abandon 逐字一致）：这一轮可能已经在网关侧跑起来，
                    // 保留 live id 会让下一轮直接提交到它上面（两条轮次并存、重试还会被 steer 进旧轮次）。
                    // 丢掉 live 后，下一轮只能经 session.resume + 空闲判定重新挂载，忙则 fail-closed。
                    binding.liveSessionId = null;
                }
                // 上一次 abandon 的原因只描述被放弃的那一轮，新的判定有自己的文案。
                binding.abandonedReason = null;
                boolean empty = binding.liveSessionId == null && binding.storedSessionId == null
                    && binding.inFlight == null && !binding.needsReconcile;
                if (empty) {
                    bindings.remove(key);
                }
            }
        }
    }

    /**
     * 上游失败的对外文案：只带方法、会话键与 JSON-RPC 顶层整数码，绝不搬运上游 payload（可能含凭据），
     * 也不把上游异常挂成 cause（cause 的 message 同样是原始回执）。
     */
    private static String describe(String action, String key, RuntimeException ex) {
        return "Hermes " + action + " 失败（会话 " + key + "，" + codeText(ex)
            + "）；上游原始回执不入异常消息，结果未知需对账";
    }

    private static String codeText(RuntimeException ex) {
        Integer code = unambiguousTopLevelCode(ex);
        return code == null ? "上游未给出顶层整数错误码" : "code=" + code;
    }

    /**
     * 从异常链里取「唯一可信的顶层整数错误码」：只认传输层解析出的结构化
     * {@link HermesGateway.UpstreamRpcError#topLevelCode()}，绝不扫描文本。
     *
     * <p>任何不可判定都返回 null（调用方必须 fail-closed）：链上没有结构化错误、某个节点没拿到顶层整数码、
     * 或链上出现两个互相矛盾的非空码。
     */
    private static Integer unambiguousTopLevelCode(Throwable failure) {
        Integer found = null;
        int depth = 0;
        for (Throwable cursor = failure; cursor != null && depth < 16; cursor = cursor.getCause(), depth++) {
            if (!(cursor instanceof HermesGateway.UpstreamRpcError error)) {
                continue;
            }
            Integer code = error.topLevelCode();
            if (code == null) {
                return null;
            }
            if (found != null && !found.equals(code)) {
                return null;
            }
            found = code;
        }
        return found;
    }

    /** 只有「顶层整数码恰好是 4001」才叫会话已回收；其余一律按未知失败处理。 */
    private static boolean sessionRecycled(Throwable failure) {
        return Integer.valueOf(SESSION_NOT_FOUND).equals(unambiguousTopLevelCode(failure));
    }

    private static String requireKey(String conversationKey) {
        if (conversationKey == null || conversationKey.isBlank()) {
            throw new IllegalArgumentException("业务会话键必填");
        }
        return conversationKey;
    }

    private static void requirePrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Hermes prompt 不能为空");
        }
    }

    /** 一条会话的绑定与互斥状态；只存 id 与状态位，不存 prompt、回复或任何凭据。 */
    private static final class Binding {
        private String liveSessionId;
        private String storedSessionId;
        private HermesAssistantRuntime.Submission inFlight;
        private boolean submitting;
        private boolean awaiting;
        private boolean needsReconcile;
        /** 上一个 {@link #abandon} 的固定原因文案（只进闸门提示，不携带上游回执）。 */
        private String abandonedReason;
    }

    /** 内部失败分类：{@code resultKnown} 决定这条会话是否需要先对账才能再提交。 */
    private static final class TurnFailure extends IllegalStateException {
        private final boolean resultKnown;

        TurnFailure(String message, boolean resultKnown) {
            super(message);
            this.resultKnown = resultKnown;
        }
    }
}
