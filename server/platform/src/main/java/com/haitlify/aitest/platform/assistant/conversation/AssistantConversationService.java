package com.haitlify.aitest.platform.assistant.conversation;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.haitlify.aitest.platform.assistant.hermes.HermesGateway;
import com.haitlify.aitest.platform.assistant.runtime.HermesAssistantRuntime;
import com.haitlify.aitest.platform.assistant.runtime.HermesConversationService;

/**
 * 助手对话的业务适配层：把「登录主体 + 项目授权 → 会话账本 → 会话级 Hermes 适配」串成一条链。
 *
 * <p>这一层是 {@link HermesConversationService} 的<b>真实调用方</b>，负责三件它刻意不做的事：
 * <ol>
 *   <li><b>身份</b>：actor/tenant/project 全部服务端派生（见 {@link AssistantConversationAccess}），
 *       请求体只允许 {@code requestId/text/projectId/title} 四个键；</li>
 *   <li><b>幂等</b>：同一 {@code requestId} 不会第二次打到网关（DB 唯一约束 + 会话内互斥）；</li>
 *   <li><b>对账语义</b>：只有拿到权威终态才写 COMPLETED；排队受理、超时、断连、空回复一律
 *       UNKNOWN/FAILED，绝不返回「看起来成功的空文本」。</li>
 * </ol>
 *
 * <p><b>写账本的顺序（崩溃窗口纪律）</b>：先以唯一键 {@code (conversation_id, request_id)} 插入
 * {@code SUBMITTED} 行，<b>再</b>提交到网关；受理成功后把 {@code accepted_at} 与 {@code stored_session_id}
 * 放在同一个事务里落账（{@code markAccepted}）；任何本地失败都把行推离 SUBMITTED（FAILED/UNKNOWN）。
 * 这样进程在提交的任意时刻死掉，账本里都有一行，且「是否真的被受理」有明确证据：
 * <ul>
 *   <li>{@code SUBMITTED} + {@code accepted_at} 非空 → 协议已受理、终态待取，重放返回 202 且<b>不再提交</b>；</li>
 *   <li>{@code SUBMITTED} + {@code accepted_at} 为空 → 尝试已记账、受理结果不可判定，重放只能落 UNKNOWN
 *       并要求对账，<b>绝不重投同一 requestId</b>；</li>
 *   <li>{@code SUBMITTED} 且 {@code updated_at} 早于「一轮最长链 + grace」，且本 JVM 已不再占有这一行
 *       → 崩溃遗留的孤儿，读取时有界归一化为 UNKNOWN（SUBMITTED 不是事实终态）。</li>
 * </ul>
 *
 * <p><b>受理之后本地失败（不许留占位）</b>：受理成功之后的本地失败（受理账落库失败、回复队列已满、
 * 甚至 {@link Error}）只改账本是不够的——会话服务里的「本轮占位」只能由终态消费者或对账清掉，留着它
 * 就会让该会话此后每一轮都被互斥闸门挡住。所以受理之后的窗口只剩<b>一个出口标记 + 一个 finally</b>：
 * 成功交给回复消费者时置位，其余任何出口（含 {@code Error}/{@code Throwable}）都在 finally 里调用
 * {@link HermesConversationService#abandon(String, String)}——丢掉占位与 live id、明确要求对账
 * （不推断终态），再落账 UNKNOWN。这样「忘记释放」在结构上不再可能：窗口里新增任何一步都不需要
 * 「记得」清理。下一轮凭账本证据（没有任何一行仍停在 SUBMITTED）自行对账并恢复，或用
 * {@code session.resume} 的空闲判定挡住并行轮次。
 *
 * <p><b>对账回路（闸门权威在账本）</b>：是否放行只看账本里「有没有别的行还停在 SUBMITTED」
 * （{@link AssistantConversationRepository#countOtherOpenTurns}）——没有，就说明上一轮在本端确实
 * 结束了，此时把运行时里可能残留的占位（{@code inFlight}/{@code needsReconcile}）当作缓存清掉；
 * 有，就拒绝本轮（UNKNOWN + 需对账），绝不在账本还开着的时候再打一次 wire。运行时的
 * {@code needsReconcile()} 只是「本进程是否还留着一个结果未知状态」的提示，<b>不是</b>放行条件：
 * 它会把「残句柄」读成「干净」，那正是「回合状态双权威」的来源。判据也不再读「最新一行是什么状态」
 * ——{@code (created_at, id)} 在毫秒并列时的走向与写入先后无关，而「还有没有开着的行」与排序无关。
 *
 * <p><b>本轮 MCP 端点的时序（unit → gate → permit → arm → configure → dispatch）</b>：网关只在<b>建立会话时</b>
 * 读 MCP 条目，而那个条目在<b>一个网关进程内</b>是唯一的，因此「武装」必须发生在派发之前（见
 * {@code mcp/README.md}）：解析本轮<b>执行单元</b>（{@link GatewayUnits}，键 =
 * {@code (tenantId, projectId, actorId)}）→ 本地闸门（只读账本/句柄，不动租约）→ 取<b>该单元的单一许可</b>
 * → arm（拿到本轮 {@code /mcp-lease/<leaseId>}）→ 交给该单元的 {@link GatewayMcpConfigurator}（写条目 +
 * reload）→ 派发。
 *
 * <p>单元化之后「唯一」的范围就是那个单元的配置文件：单个 {@code (tenant, project, actor)} 内部仍然必须
 * 串行（同一进程、同一条目），跨单元则天然并行。装配没有注册执行单元池时，{@link SingleGatewayUnits} 把
 * 整个 JVM 当作一个单元，行为与单元化之前逐条一致（包括 {@code MCP_PERMIT_BUSY} 的拒绝）。
 *
 * <p><b>单元占用与许可不是一件事</b>：{@link GatewayUnits#acquire} 在<b>返回之前</b>就取该单元的
 * <b>在飞租约</b>（{@link UnitLease}），服务层在终态与<b>所有</b>失败/异常出口归还它。回收与关闭只看租约，
 * 因此 {@code configure-gateway=false}（默认）时——一次许可都不取——在飞轮次同样不会被摘掉；许可只在
 * 「本轮要不要写网关条目」这件事上有意义。
 *
 * <p>为什么要多一道许可：条目在会话创建的那一刻被<b>绑定</b>到那个会话，之后改写条目不会追溯改变
 * 已建会话，所以两次改写交错（A 配置 → B 配置 → A 派发）会让 A 的会话拿到为 B 那一轮铸的 URL——
 * 那不是「降级成 401」，而是把对 B 的 generation 的能力交给了 A 的会话（能力错配）。因此从 arm 之前到
 * 本轮终态只有<b>持有者</b>可以 arm / configure。非持有者在许可被持期间的提交被<b>显式拒绝</b>
 * （账本落 {@code FAILED}、HTTP 503、痕迹 {@link #MCP_TRACE_PERMIT_BUSY}），既不武装、也不改写条目、
 * 更不派发——因此不会建立任何会话。「以无工具继续派发」<b>不是</b>安全的降级：那个新建会话在创建时
 * 照样会绑定持有者的条目（见 {@code mcp/README.md} 的探针结论），只是把能力错配换个方向放回来。
 *
 * <p>终态收尾的顺序是硬的：账本先落终态 → <b>仍持有许可时</b>用 {@code apply(null)} 清掉网关条目 →
 * 最后才释放许可（{@link #clearGatewayEntryAndReleasePermit(String)}）。先放许可会让下一轮在我们清条目
 * 之前就武装/配置，而我们的 {@code null} 随后会把它的条目一起抹掉；不清条目则会让终态之后的会话继续
 * 绑定一条已死租约，直到下一轮或 TTL 到点。释放写在 {@code finally} 里（不是清条目之后的下一句）：
 * {@code apply(null)} 抛 {@code Error} 时也必须归还许可，否则全局许可会被一个已经到终态的轮次永久占住。
 *
 * <p>没有端点时显式清除条目（fail-closed，不回退旧 URL）；配置失败只损失本轮 MCP 可用性并留下
 * 可对账痕迹；派发失败撤销本轮租约（不留孤儿端点）。这些动作都<b>不碰账本状态机</b>：
 * 账本仍只在受理之后写 {@code accepted_at}，终态只由模型回复决定。
 *
 * <p><b>部署假设（单 JVM）</b>：提交路径的互斥是<b>进程内</b>的（按会话 id 的 64 段锁 + 内存绑定表），
 * 幂等的跨进程部分只靠数据库唯一约束。执行单元把「网关 + 条目 + 许可」搬到子进程，但<b>会话绑定表与许可
 * 仍然是单元进程内状态</b>：单元被回收后，下一轮靠账本里的 {@code stored_session_id} 走 {@code session.resume}。
 * 因此本实现仍然只在<b>单容器/单副本</b>部署下正确：多副本共享同一数据库时，两个实例可能同时对同一对话
 * 发起提交（唯一约束能挡住重复 requestId，但挡不住两个不同 requestId 的并发轮次）。这里刻意不实现分布式锁
 * ——多副本部署前必须先补这一层，而不是把单 JVM 假设当成正确性。
 * <p><b>测试缝（不属于生产语义）</b>：唯一一个多带 {@code ThreadPoolExecutor} 参数的构造重载只供用例注入回复
 * 消费池，用来制造「受理窗口内 {@code replies.execute} 抛 {@link Error}」这个生产池无法自然产生的出口
 * （真实 {@link ThreadPoolExecutor} 拒绝时只会给 {@link RejectedExecutionException}）。<b>不得在生产接线中
 * 使用</b>：生产装配只走 {@link AssistantConversationConfiguration} 里的构造器；注入的池由同一个
 * {@link #shutdown()} 关闭，生命周期没有第二个出口。
 */
class AssistantConversationService {

    private static final Logger log = LoggerFactory.getLogger(AssistantConversationService.class);

    static final String STATE_SUBMITTED = "SUBMITTED";
    static final String STATE_COMPLETED = "COMPLETED";
    static final String STATE_UNKNOWN = "UNKNOWN";
    static final String STATE_FAILED = "FAILED";

    /** 本轮 MCP 配置的可对账痕迹（进程内）。只放固定标识，不放 URL / lease id。 */
    static final String MCP_TRACE_CONFIGURED = "MCP_CONFIGURED";
    static final String MCP_TRACE_NO_ENDPOINT = "MCP_NO_ENDPOINT";
    static final String MCP_TRACE_CONFIGURE_FAILED = "MCP_CONFIGURE_FAILED";
    static final String MCP_TRACE_REVOKED = "MCP_REVOKED";
    /**
     * 本轮没拿到网关级许可：网关条目被另一轮持有，本轮<b>没有</b>武装、<b>没有</b>改写条目、也<b>没有</b>
     * 派发，因而不存在任何「以无工具运行」的会话——本轮是被<b>显式拒绝</b>的。这是一条需要人看到的事实：
     * 那一轮缺的不是配置，而是一次被并发挡回的提交，客户端必须换 {@code requestId} 重试。
     */
    static final String MCP_TRACE_PERMIT_BUSY = "MCP_PERMIT_BUSY";
    /**
     * 本轮<b>拿不到自己的执行单元</b>（池满、基准 profile 不可用、单元子进程起不来）：本轮没有武装、
     * 没有写任何条目、也没有派发。与 {@link #MCP_TRACE_PERMIT_BUSY} 一样是需要人看到的固定事实，
     * 而不是「配置没成功」这种可以重试的偶发。
     */
    static final String MCP_TRACE_UNIT_UNAVAILABLE = "MCP_UNIT_UNAVAILABLE";

    private static final int MAX_TEXT = 8000;
    private static final int MAX_TITLE = 200;
    private static final int MAX_DETAIL = 480;
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    /** MCP 痕迹表上限：与租约表同阶，超出后淘汰最老的条目（痕迹只用于对账，不是账本）。 */
    static final int MAX_MCP_TRACES = 256;

    /** 回复消费者：8 个线程 + <b>有界</b>队列；队列满时拒绝而不是无限堆积（拒绝即 UNKNOWN）。 */
    private static final int REPLY_THREADS = 8;
    static final int REPLY_QUEUE_CAPACITY = 256;

    /**
     * adopt 路径最多经历的 RPC 往返次数：{@code session.resume} → 重读 {@code session.resume}
     * （等待空闲期间的再读）→ {@code prompt.submit}。受理之后还要再等 {@code replyTimeout} 的终态。
     * 陈旧预算必须覆盖这条最长链，否则「仍在派发」的正常轮次会被当成崩溃孤儿。
     */
    private static final int DISPATCH_RPC_ROUND_TRIPS = 3;

    /** 单次 Hermes RPC 的默认上限；与 {@code app.assistant.hermes.timeout-seconds} 的默认值一致（供测试构造器使用）。 */
    static final Duration DEFAULT_RPC_TIMEOUT = Duration.ofSeconds(60);

    /** 恢复后等待会话空闲的默认上限；与 {@code app.assistant.conversation.resume-idle-wait-seconds} 一致。 */
    static final Duration DEFAULT_RESUME_IDLE_WAIT = Duration.ofSeconds(10);

    /** 陈旧归一化的宽限期：覆盖最长链之后再多留一段，吸收调度抖动。 */
    private static final Duration STALE_GRACE = Duration.ofSeconds(30);

    /** 关闭时等待在途回复的上限；超时后中断并把这些轮次落成 UNKNOWN。 */
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(2);

    private static final String STALE_SUBMITTED_DETAIL =
        "SUBMITTED 已超过一轮最长链（派发 + 等待终态）仍未终结，且本进程不再占有该轮（进程中断遗留），"
            + "转为 UNKNOWN 需对账";
    private static final String DISPATCH_UNKNOWN_DETAIL = "Hermes 提交结果不可判定，未进入终态等待";
    private static final String ACCEPTANCE_UNRECORDED_DETAIL =
        "Hermes 已可能的受理未能落账（本地写入失败），结果不可判定需对账";
    private static final String UNPROVEN_SUBMIT_DETAIL =
        "该 requestId 的在途尝试没有受理证据，结果不可判定需对账（不重投）";
    private static final String WIRE_UNTOUCHED_DETAIL = "提交参数非法，未触达 Hermes（已知无副作用）";
    private static final String QUEUE_FULL_DETAIL = "回复消费者队列已满，结果未知需对账";
    private static final String ACCEPTANCE_WINDOW_ERROR_DETAIL =
        "受理窗口内发生致命错误（Error/Throwable），本轮结果不可判定需对账";
    private static final String ANOTHER_OPEN_TURN_DETAIL =
        "账本里仍有未终态的轮次（可能仍在运行），本轮拒绝并发提交，结果未知需对账";
    private static final String SHUTDOWN_DETAIL = "服务关闭时该轮仍无终态，结果未知需对账";
    private static final String GATEWAY_UNREACHABLE_DETAIL =
        "Hermes 执行单元不可达，未提交（未回退旧 AI 链路）";
    private static final String NO_HANDLE_WITH_HISTORY_DETAIL =
        "该对话已有历史轮次却没有持久会话句柄，拒绝新建会话冒充续聊（需对账）";
    /**
     * 许可被占时的拒绝对账文案。写进账本（{@code FAILED}）并原样回到响应里，因此客户端不需要看进程内
     * 痕迹就知道「这一轮根本没派发」；同时因为它落在账本行上，同一 {@code requestId} 的重放也不会被悄悄
     * 派发出去（重放只会看到这一行）。
     */
    static final String MCP_PERMIT_BUSY_DETAIL =
        "网关级 MCP 许可被另一轮持有（MCP_PERMIT_BUSY），本轮未派发、未建会话、未触碰网关条目，需换 requestId 重试";

    /**
     * 拿不到本轮的<b>执行单元</b>时的对账文案。落账本（{@code FAILED}）并原样回到响应里。
     *
     * <p>为什么是 {@code FAILED} 而不是 {@code UNKNOWN}：一帧都没发出去、一个会话都没建，没有任何结果需要
     * 推断。而且反过来必须是 FAILED——{@code UNKNOWN} 行会让这个对话此后每一轮都被「无句柄却有历史」
     * 挡住（见 {@link #admissionRefusal}），一次池满就变成永久砖死。
     *
     * <p>文案里明确写「未回退」：执行单元不可用时的唯一正确行为是本轮失败，<b>不是</b>借用别人的单元或
     * 旧 URL——那会把这一轮的会话绑定到别人的 MCP 条目上。
     */
    static final String EXECUTION_UNIT_UNAVAILABLE_DETAIL =
        "本轮的网关执行单元不可用（EXECUTION_UNIT_UNAVAILABLE），未派发、未建会话、未回退到共享单元或旧 URL，"
            + "需换 requestId 重试";

    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Set<String> CREATE_KEYS = Set.of("requestId", "projectId", "title");
    private static final Set<String> MESSAGE_KEYS = Set.of("requestId", "text");

    record ConversationView(String conversationId, long projectId, String requestId, String title, String createdAt,
                            boolean created) {}

    record SubmitReceipt(String conversationId, String messageId, String turnId, String state, String acceptance,
                         String upstreamTurnRef, boolean reconcileRequired, String detail) {}

    record MessageView(String messageId, String role, String state, String text, String turnId, String createdAt) {}

    record MessagePage(String conversationId, int limit, int offset, int total, List<MessageView> messages) {}

    private final AssistantConversationRepository repository;
    private final AssistantConversationAccess access;
    /**
     * <b>本轮的执行单元解析器</b>（{@link GatewayUnits}）：单进程装配下永远回同一个单元（行为与单元化之前
     * 逐条一致），池化装配下按 {@code (tenant, project, actor)} 给出各自的网关子进程。服务层只依赖这一个
     * 端口，因此「隔离」不是分支逻辑，而是装配选择。
     */
    private final GatewayUnits units;
    private final String workspace;
    private final Duration replyTimeout;
    /** 一行 SUBMITTED 在多长时间内仍可能是「正常在途」；超过它就只可能是崩溃孤儿。 */
    private final Duration staleAfter;
    private final ThreadPoolExecutor replies;
    /** 默认关闭的 MCP 租约接线；{@code null} 表示该开关关闭，行为与之前完全一致。 */
    private final AssistantConversationLeaseProvisioner leases;
    /**
     * 是否把本轮的租约写进<b>本单元</b>的网关条目（{@code configure-gateway}）。
     *
     * <p>与「有没有配置器」分开的原因：池化装配里每个单元天生带一个配置器，但「本轮要不要写条目」仍是
     * 部署开关。关闭时一次都不写（连 {@code apply(null)} 都不调），许可也不取——没有共享条目可争。
     */
    private final boolean configureGateway;
    /**
     * 本轮 MCP 配置的痕迹（{@code conversationId + requestId → 固定标识}）。受理之后网关侧发生了什么
     * 在账本里看不到，因此失败/无端点/无单元必须留一个进程内、可对账、且不含 URL 与 lease id 的记号。
     * 有界（{@value #MAX_MCP_TRACES}）：最老的条目在超限时被淘汰，不随对话数无限增长。
     */
    private final Map<String, String> mcpTraces = new LinkedHashMap<>();
    /**
     * 本轮等在终态上的那条记录：{@code 会话 id → (消息 id, 本轮单元)}。
     *
     * <p>单元必须和消息 id 一起记住：终态收尾要在<b>本轮那个单元</b>里清条目、放许可（见
     * {@link #awaitReply}）。只记消息 id 会让收尾时不得不重新解析单元——而那可能已经是一个新建的
     * 单元（旧单元被回收），于是清的是别人的条目、放的是别人的许可。
     */
    private final ConcurrentHashMap<String, ActiveTurn> activeTurns = new ConcurrentHashMap<>();
    /**
     * 本 JVM 仍占有的轮次（消息 id）：从落 SUBMITTED 行起，到终态被收尾为止——既包含「正在派发」，
     * 也包含 {@link #activeTurns} 里「正在等终态」的那一段。陈旧扫描必须跳过它们：一行之所以看起来
     * 很久没动，可能只是因为它还在等一次慢 RPC，而不是进程已经死了。
     */
    private final Set<String> liveTurns = ConcurrentHashMap.newKeySet();
    /** 分段提交锁：同一会话串行，不同会话并行；固定 64 段，不随会话数增长（单 JVM 假设，见类注释）。 */
    private final Object[] submitLocks = new Object[64];

    /** 一轮在等终态时的记录：消息 id 与它所属的单元（两者必须一起记，见 {@link #activeTurns}）。 */
    private record ActiveTurn(String messageId, GatewayUnits.TurnUnit unit) {}

    /** 生产装配请用带 {@code rpcTimeout}/{@code resumeIdleWait} 的构造器；这里用与配置默认值一致的短表。 */
    AssistantConversationService(AssistantConversationRepository repository, AssistantConversationAccess access,
                                 HermesConversationService conversations, HermesGateway gateway, String workspace,
                                 Duration replyTimeout) {
        this(repository, access, conversations, gateway, workspace, replyTimeout, DEFAULT_RPC_TIMEOUT,
            DEFAULT_RESUME_IDLE_WAIT);
    }

    /**
     * @param rpcTimeout     单次 Hermes RPC 的上限（{@code app.assistant.hermes.timeout-seconds}）
     * @param resumeIdleWait 恢复后等待会话空闲的上限（{@code resume-idle-wait-seconds}）；
     *                       两者都参与陈旧预算的推导，见 {@link #staleAfter}
     */
    AssistantConversationService(AssistantConversationRepository repository, AssistantConversationAccess access,
                                 HermesConversationService conversations, HermesGateway gateway, String workspace,
                                 Duration replyTimeout, Duration rpcTimeout, Duration resumeIdleWait) {
        this(repository, access, conversations, gateway, workspace, replyTimeout, rpcTimeout, resumeIdleWait, null);
    }

    /**
     * <b>测试缝，不得在生产接线中使用</b>（见类注释）。
     *
     * <p>唯一目的是让用例接管那一跳 {@code replies.execute}：生产池只会以
     * {@link RejectedExecutionException} 拒绝，而「受理窗口里冒出致命 {@link Error}」这条现实出口
     * （线程工厂/运行时致命错误）在生产形状下无法稳定构造，没有这个缝就只能靠反射改私有字段。
     *
     * <p>生命周期纪律：注入的池仍然只由 {@link #shutdown()} 关闭（{@code replies} 是同一个字段），
     * 因此这个重载不会让关闭逻辑漏掉任何池；调用方不得在服务之外另行关闭或复用该池。
     *
     * @param replyExecutor 回复消费池；{@code null} 表示按生产配置新建（等价于上面的构造器）
     */
    AssistantConversationService(AssistantConversationRepository repository, AssistantConversationAccess access,
                                 HermesConversationService conversations, HermesGateway gateway, String workspace,
                                 Duration replyTimeout, Duration rpcTimeout, Duration resumeIdleWait,
                                 ThreadPoolExecutor replyExecutor) {
        this(repository, access, conversations, gateway, workspace, replyTimeout, rpcTimeout, resumeIdleWait,
            replyExecutor, null);
    }

    /**
     * @param leases 默认关闭的 MCP 租约接线；{@code null} 时不铸租约，也不会有任何注册表被创建
     */
    AssistantConversationService(AssistantConversationRepository repository, AssistantConversationAccess access,
                                 HermesConversationService conversations, HermesGateway gateway, String workspace,
                                 Duration replyTimeout, Duration rpcTimeout, Duration resumeIdleWait,
                                 ThreadPoolExecutor replyExecutor, AssistantConversationLeaseProvisioner leases) {
        this(repository, access, conversations, gateway, workspace, replyTimeout, rpcTimeout, resumeIdleWait,
            replyExecutor, leases, null);
    }

    /**
     * <b>单进程装配的终点</b>：一个网关、一个条目、一个许可，等价于单元化之前的行为。
     *
     * <p>许可只在「会写条目」时创建（{@code gatewayMcp != null}）：配置器没接线时没有共享资源可争，
     * 取许可会让两轮无谓串行，且会让「无配置器」这个形状多出一个不可观察的状态位。
     *
     * @param gatewayMcp 默认关闭的网关 MCP 配置器；{@code null} 时一条命令都不会发给网关
     *                   （{@code app.assistant.conversation.mcp-lease.configure-gateway=false}）
     */
    AssistantConversationService(AssistantConversationRepository repository, AssistantConversationAccess access,
                                 HermesConversationService conversations, HermesGateway gateway, String workspace,
                                 Duration replyTimeout, Duration rpcTimeout, Duration resumeIdleWait,
                                 ThreadPoolExecutor replyExecutor, AssistantConversationLeaseProvisioner leases,
                                 GatewayMcpConfigurator gatewayMcp) {
        this(repository, access, leases, workspace, replyTimeout, rpcTimeout, resumeIdleWait, replyExecutor,
            new SingleGatewayUnits(gateway, conversations, gatewayMcp,
                gatewayMcp == null ? null : new GatewayMcpPermit()),
            gatewayMcp != null);
    }

    /**
     * <b>单元化装配</b>：{@code units} 决定本轮用哪个网关进程（池化时按 {@code (tenant, project, actor)}）。
     *
     * <p>{@code configureGateway} 与「有没有配置器」分开传：池化装配里每个单元天生带配置文件与配置器，
     * 而「本轮要不要把租约写进去」仍是部署开关。关闭时连 {@code apply(null)} 都不调用。
     *
     * @param units            本轮单元的解析器；非空。拿不到单元时服务层 fail-closed（不派发、不写条目）
     * @param configureGateway 是否把本轮租约写进本单元的网关条目（{@code mcp-lease.configure-gateway}）
     */
    AssistantConversationService(AssistantConversationRepository repository, AssistantConversationAccess access,
                                 AssistantConversationLeaseProvisioner leases, String workspace,
                                 Duration replyTimeout, Duration rpcTimeout, Duration resumeIdleWait,
                                 ThreadPoolExecutor replyExecutor, GatewayUnits units, boolean configureGateway) {
        requirePositive(replyTimeout, "回复等待上限");
        requirePositive(rpcTimeout, "Hermes RPC 上限");
        requirePositive(resumeIdleWait, "等待会话空闲的上限");
        if (units == null) {
            throw new IllegalArgumentException("执行单元解析器必填");
        }
        this.repository = repository;
        this.access = access;
        this.units = units;
        this.workspace = workspace;
        this.replyTimeout = replyTimeout;
        // 最长链：resume + 重新 resume + submit 三次 RPC，加上等待空闲与等待终态，再加宽限。
        // 用「实际最长链」而不是「2*replyTimeout」：后者覆盖不了 adopt 路径，会把仍在派发的轮次扫成孤儿。
        this.staleAfter = rpcTimeout.multipliedBy(DISPATCH_RPC_ROUND_TRIPS)
            .plus(resumeIdleWait).plus(replyTimeout).plus(STALE_GRACE);
        this.replies = replyExecutor == null ? newReplyPool() : replyExecutor;
        this.leases = leases;
        this.configureGateway = configureGateway;
        for (int index = 0; index < submitLocks.length; index++) {
            submitLocks[index] = new Object();
        }
    }

    /** 生产回复消费池：固定 8 线程 + 有界队列 + 拒绝即失败（拒绝由 {@link #dispatchReply} 落成 UNKNOWN）。 */
    private static ThreadPoolExecutor newReplyPool() {
        return new ThreadPoolExecutor(REPLY_THREADS, REPLY_THREADS, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(REPLY_QUEUE_CAPACITY), new ThreadFactory() {
                private final AtomicInteger seq = new AtomicInteger();

                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "assistant-conversation-reply-" + seq.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }
            }, new ThreadPoolExecutor.AbortPolicy());
    }

    /** 创建一个对话；同一 actor+project+requestId 重放返回既有对话（不新建、不报错）。 */
    ConversationView create(Map<String, Object> payload) {
        requireOnlyKeys(payload, CREATE_KEYS, Set.of("requestId", "projectId"));
        long projectId = positiveLong(payload.get("projectId"));
        String requestId = requestId(payload.get("requestId"));
        String title = title(payload.get("title"));
        AssistantConversationAccess.Actor actor = access.requireActor();
        long tenantId = access.requireProject(actor, projectId);
        AssistantConversationRepository.Conversation draft = new AssistantConversationRepository.Conversation(
            UUID.randomUUID().toString(), tenantId, projectId, actor.id(), requestId, title, null, Instant.now());
        return view(repository.insertConversationIfAbsent(draft));
    }

    /**
     * 提交一轮文本。
     *
     * <p>顺序是「先记账、后提交」：只有落了 {@code SUBMITTED} 行才会碰 wire，因此崩溃不会留下
     * 「网关已经受理但账本里没有」的窗口；受理结果靠 {@code accepted_at} 区分，重放绝不会二次提交。
     *
     * @return 状态为 {@code SUBMITTED} 时表示协议已受理（HTTP 202）；{@code UNKNOWN/FAILED} 表示
     *         结果不可判定或本地即失败（HTTP 503），两者都带 messageId 供对账。
     */
    SubmitReceipt submit(String conversationId, Map<String, Object> payload) {
        requireOnlyKeys(payload, MESSAGE_KEYS, Set.of("requestId", "text"));
        String requestId = requestId(payload.get("requestId"));
        String text = text(payload.get("text"));
        AssistantConversationAccess.Actor actor = access.requireActor();
        AssistantConversationRepository.Conversation conversation = ownedConversation(actor, conversationId);
        long tenantId = access.requireProject(actor, conversation.projectId());
        if (tenantId != conversation.tenantId()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "会话的租户归属已变化，拒绝提交");
        }
        synchronized (lockFor(conversationId)) {
            // SUBMITTED 不是事实终态：每次提交/读取前先把超过预算的孤儿归一化，避免它们冒充在途轮次。
            sweepStaleSubmitted(conversationId);
            Optional<AssistantConversationRepository.Message> replay =
                repository.findMessageByRequest(conversationId, requestId);
            if (replay.isPresent()) {
                return replay(replay.get(), text);
            }
            if (activeTurns.containsKey(conversationId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该对话上一轮尚未结束，拒绝并发提交（同一对话不得同时存在两轮）");
            }
            if (!units.available()) {
                // 侧效应为零：没有任何帧到达网关，因此这里的结果是「已知失败」，不需要对账。
                AssistantConversationRepository.Message refused = row(conversationId, actor, requestId, text,
                    STATE_FAILED, null, GATEWAY_UNREACHABLE_DETAIL);
                if (!insertOnce(refused)) {
                    return replay(requireRow(conversationId, requestId), text);
                }
                return receipt(refused, null, false, refused.failureDetail());
            }
            // 1) 先记账：唯一键 (conversation_id, request_id) 就是幂等去重，重放走既有行而不是再提交一次。
            AssistantConversationRepository.Message attempt = row(conversationId, actor, requestId, text,
                STATE_SUBMITTED, null, null);
            if (!insertOnce(attempt)) {
                return replay(requireRow(conversationId, requestId), text);
            }
            // 从这一刻起这一轮由本 JVM 占有：陈旧扫描不得把它当成崩溃孤儿。
            liveTurns.add(attempt.id());
            try {
                // 2) 再提交：从这一行之后，任何本地失败都必须把它推离 SUBMITTED。
                return submitAccepted(conversation, actor, attempt, text);
            } finally {
                // 兜底：成功受理的轮次由回复消费者持有（activeTurns 里还有它），其余路径都已收尾。
                if (!awaiting(attempt.id())) {
                    liveTurns.remove(attempt.id());
                }
            }
        }
    }

    /**
     * 受理之前的收尾：<b>解析本轮执行单元</b> → 闸门 → 取本单元许可 → 武装本轮租约 → 配置网关 → 派发
     * （顺序即 {@code mcp/README.md} 的要求，前面多了一步「本轮是哪个网关进程」）。
     *
     * <p>顺序不能换，四处各有各的理由：
     * <ul>
     *   <li><b>单元在最前面</b>：闸门要读的 {@code isBound} 是<b>该单元进程内</b>的会话绑定表；换一个单元
     *       去读，读到的只是另一个进程的缓存。拿不到单元就立刻失败（本轮零副作用），绝不借用别人的。
     *       {@code acquire} 在返回时已经取了该单元的<b>在飞租约</b>，所以从这一行起回收/关闭都不会碰这个
     *       单元——与 {@code configure-gateway} 和 {@code idle-ttl} 都无关；</li>
     *   <li><b>闸门在武装之前</b>：武装会作废同一对话上一轮的租约，所以一个注定被账本拒绝的轮次
     *       不得武装（否则它会把仍在跑的那一轮的端点顺手砍掉）；</li>
     *   <li><b>许可在武装之前</b>：本单元的条目在会话创建时被读取，非持有者一旦武装/配置，
     *       就会把持有者（或在建会话）的条目覆盖成自己的能力——那是能力错配，不是「降级成 401」；</li>
     *   <li><b>配置在派发之前</b>：会话建立时才读条目，端点必须在那之前就位。</li>
     * </ul>
     *
     * <p><b>许可被占时本轮显式失败</b>（{@link #MCP_PERMIT_BUSY_DETAIL} → 账本 {@code FAILED} → HTTP 503，
     * 痕迹 {@link #MCP_TRACE_PERMIT_BUSY}）：不 arm、不 configure、不派发，因此不建立任何会话、也不会让
     * 任何会话读到持有者的 URL。这不是保守，而是唯一正确的选择——「以无工具继续派发」并不成立：
     * 会话在创建时就把网关条目绑定到自身，那条条目正是持有者的 URL。
     *
     * <p><b>拿不到单元同样是显式失败</b>（{@link #EXECUTION_UNIT_UNAVAILABLE_DETAIL} → 账本 {@code FAILED}，
     * 痕迹 {@link #MCP_TRACE_UNIT_UNAVAILABLE}）：池满/基准 profile 坏了/子进程起不来时，本轮连租约都不铸。
     * 借用池里别人的单元或沿用上一次的 URL 都会把这一轮的会话绑到别人的条目上，所以没有降级路径。
     *
     * <p>这一段的失败都发生在 wire 受理之前或归属不可判定时：参数非法是「已知零副作用」，
     * 其余是「结果未知」，都必须把账本行推离 SUBMITTED。真正的受理窗口在 {@link #handOff}。
     */
    private SubmitReceipt submitAccepted(AssistantConversationRepository.Conversation conversation,
                                         AssistantConversationAccess.Actor actor,
                                         AssistantConversationRepository.Message attempt, String text) {
        String conversationId = conversation.id();
        String requestId = attempt.requestId();
        // 0) 本轮的执行单元：键全部来自账本行（服务端派生），请求体里没有任何身份字段能影响它。
        GatewayUnits.TurnUnit unit;
        try {
            unit = units.acquire(ExecutionUnitKey.of(conversation.tenantId(), conversation.projectId(),
                conversation.actorId()));
        } catch (ExecutionUnitUnavailableException unavailable) {
            recordMcpTrace(conversationId, requestId, MCP_TRACE_UNIT_UNAVAILABLE);
            log.warn("执行单元不可用（conversation={}, requestId={}, reason={}）：本轮不派发、不回退共享单元",
                conversationId, requestId, unavailable.reason());
            return refusedWithoutWire(attempt, EXECUTION_UNIT_UNAVAILABLE_DETAIL);
        }
        // 从这一行起本轮的<b>单元占用</b>已经生效：{@code acquire} 在返回之前就取了该单元的在飞租约
        // （{@link GatewayUnits.TurnUnit#lease()}），回收线程与池关闭都因此跳过这个单元——与
        // {@code configure-gateway} 无关，也与 {@code idle-ttl} 的长短无关。所以任何出口都必须归还它：
        // 闸门拒绝、许可被占、派发失败、参数非法、{@code Error} 全部走下面同一个 finally，
        // 没有任何一条「记得补一句」的逐路径清理。
        boolean handedToConsumer = false;
        try {
            // 1) 本地闸门：只读账本与该单元的句柄，不触达 wire、也不动租约。账本还开着就拒绝本轮——
            //    若先武装再判断，本轮会被拒而上一轮仍在跑的租约已经被 arm 作废，等于砍掉正在用的端点。
            String refusal = admissionRefusal(unit, conversation, attempt.id());
            if (refusal != null) {
                return failed(attempt, refusal);
            }
            // 2) 本单元内的单一许可：条目在会话创建时绑定，所以许可被占时本轮不得派发。
            //    「无工具跑一轮」是假的降级：新建会话照样绑定持有者的条目（能力错配），因此显式拒绝。
            //    这一步也不许调 apply(null)——那会把持有者正在用的条目清掉。
            //    注意许可<b>不是</b>「有一轮在飞」的标记（配置关闭时它一次都不会被取），那个标记是租约。
            if (!acquireGatewayMcpPermit(unit, attempt.id())) {
                recordMcpTrace(conversationId, requestId, MCP_TRACE_PERMIT_BUSY);
                return refusedWithoutWire(attempt, MCP_PERMIT_BUSY_DETAIL);
            }
            // 许可与租约的所有权在这里只有两种归宿：本方法的 finally（同步窗口的失败），或交给 handOff
            // 之后由终态消费者的 finally 归还。中途没有任何一条「记得释放」的逐路径清理。
            // 3) 先武装：网关必须在会话建立之前就能拿到本轮的端点（见 mcp/README.md 的顺序要求）。
            Optional<String> endpoint = armLease(conversationId, actor.id(), conversation.projectId(), requestId);
            // 4) 再配置：写<b>本单元</b>的条目 + reload；本轮没有端点也必须显式清除（fail-closed，不回退旧 URL）。
            configureGatewayMcp(unit, conversationId, requestId, endpoint);
            // 5) 最后才派发。失败时撤销本轮租约：一条谁也够不着的孤儿端点就是「多出来的能力」。
            HermesAssistantRuntime.Submission submission;
            try {
                submission = dispatchWire(unit, conversation, text);
            } catch (ResponseStatusException refusedByValidation) {
                repository.updateMessageStateIfSubmitted(attempt.id(), STATE_FAILED, WIRE_UNTOUCHED_DETAIL);
                disarmTurn(conversationId, requestId);
                throw refusedByValidation;
            } catch (RuntimeException localFailure) {
                disarmTurn(conversationId, requestId);
                return failed(attempt, DISPATCH_UNKNOWN_DETAIL);
            }
            // 交接之后许可与租约都归受理窗口所有（见 handOff 的 finally）；交接之前仍归这里。
            handedToConsumer = true;
            return handOff(unit, conversation, actor, attempt, submission);
        } finally {
            if (!handedToConsumer) {
                releaseTurnHold(unit, attempt.id());
            }
        }
    }

    /**
     * 受理之后的交接：<b>唯一的受理窗口</b>。
     *
     * <p>进入本方法时 wire 已经受理过本轮（{@code ACCEPTED_NOW}），会话服务里的占位也已置位；从这里到
     * {@code handedOff = true} 之间的<b>任何</b>出口——正常回执、受理账落库失败、回复队列拒绝、
     * {@link RuntimeException}，以及 {@link Error}/{@link Throwable}——都只会走同一个 finally：
     * 没有交接给消费者就 {@link HermesConversationService#abandon}（释放占位、丢掉 live id、要求对账）。
     *
     * <p>为什么必须是 finally 而不是逐路径清理：这个窗口的现实特征就是「还会被加东西」（有界回复队列就是
     * 后加的），逐路径清理要求每个新步骤的作者都记得看一眼上一处调了什么，漏一次就是整条对话砖死到
     * 进程重启（占位还在时 {@code needsReconcile()} 恒为 false，自愈入口永不触发）。finally 让「忘记」
     * 不再可能，也让 {@code Error} 这类以前完全没有处理的出口变成「≤ {@code staleAfter} 的有界阻塞」。
     */
    private SubmitReceipt handOff(GatewayUnits.TurnUnit unit,
                                  AssistantConversationRepository.Conversation conversation,
                                  AssistantConversationAccess.Actor actor,
                                  AssistantConversationRepository.Message attempt,
                                  HermesAssistantRuntime.Submission submission) {
        String conversationId = conversation.id();
        ActiveTurn active = new ActiveTurn(attempt.id(), unit);
        boolean handedOff = false;
        String abandonedReason = null;
        try {
            // 3) 受理落账：accepted_at 与 stored_session_id 同一事务，之后这一轮才有「已受理」的证据。
            repository.markAccepted(attempt.id(), conversationId, submission.turnRef(),
                submission.storedSessionId());
            activeTurns.put(conversationId, active);
            if (!dispatchReply(unit, conversationId, attempt.id(), actor.id())) {
                // 受理已经在 wire 上发生过，但本进程没有消费者能再取到终态。
                abandonedReason = QUEUE_FULL_DETAIL;
                return failed(attempt, QUEUE_FULL_DETAIL);
            }
            handedOff = true;
            return new SubmitReceipt(conversationId, attempt.id(), attempt.turnId(), STATE_SUBMITTED,
                submission.acceptance().name(), submission.turnRef(), false, null);
        } catch (RuntimeException unrecordedAcceptance) {
            abandonedReason = ACCEPTANCE_UNRECORDED_DETAIL;
            return failed(attempt, ACCEPTANCE_UNRECORDED_DETAIL);
        } finally {
            if (!handedOff) {
                // 这一轮没有被任何消费者持有：先撤销它的租约（不留孤儿端点），再释放两层闸门。
                disarmTurn(conversationId, attempt.requestId());
                // 同一出口必须释放<b>两层</b>闸门。只 abandon 运行时占位是不够的：本层 activeTurns 里若还留着
                // 这一轮，submit 的外层 finally 会因为 containsValue 仍为 true 而把消息 id 留在 liveTurns 里，
                // 于是一行早已放弃的 SUBMITTED 永久「看起来还在跑」——陈旧扫描永远跳过它，账本无法自愈，
                // 此后每次提交都在 activeTurns.containsKey 上 409 到进程重启。这就是「占位漏放」的同一类砖死，
                // 只是发生在 dispatchReply / activeTurns.put 这一跳上（Error 不是 RuntimeException，逐路径清理
                // 覆盖不到）。remove(k, v) 幂等，正常 handedOff 路径不走这里（消费者自己会 remove）。
                activeTurns.remove(conversationId, active);
                unit.conversations().abandon(conversationId,
                    abandonedReason == null ? ACCEPTANCE_WINDOW_ERROR_DETAIL : abandonedReason);
                // 网关级许可与单元在飞租约的释放点之一（受理窗口）：仍然只在 finally 里，因此
                // Error/Throwable 也覆盖。顺序纪律：先撤销本轮租约、再放许可、最后归还单元租约——
                // 放掉的顺序会让下一轮先去武装，而这一轮的端点还没撤。
                releaseTurnHold(unit, attempt.id());
            }
        }
    }

    MessagePage messages(String conversationId, Integer limit, Integer offset) {
        AssistantConversationAccess.Actor actor = access.requireActor();
        AssistantConversationRepository.Conversation conversation = ownedConversation(actor, conversationId);
        access.requireProject(actor, conversation.projectId());
        sweepStaleSubmitted(conversationId);
        int size = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
        int start = offset == null ? 0 : Math.max(0, offset);
        List<MessageView> rows = repository.listMessages(conversationId, size, start).stream()
            .map(row -> new MessageView(row.id(), row.role(), row.state(), row.body(), row.turnId(),
                row.createdAt().toString()))
            .toList();
        return new MessagePage(conversationId, size, start, repository.countMessages(conversationId), rows);
    }

    /**
     * 回复消费线程随上下文关闭：先礼后兵（shutdown + 有界等待），再把仍在等待终态的轮次落成 UNKNOWN。
     * 关闭后不允许留下「已受理、无终态、也无标记」的行——那会让重启后既看不到答案也看不到需对账。
     */
    void shutdown() {
        replies.shutdown();
        boolean drained = false;
        try {
            drained = replies.awaitTermination(SHUTDOWN_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (!drained) {
            replies.shutdownNow();
        }
        abandonActiveTurns();
    }

    /** 关闭收尾：在途轮次没有终态就不是成功，只能是 UNKNOWN。 */
    private void abandonActiveTurns() {
        for (Map.Entry<String, ActiveTurn> entry : activeTurns.entrySet()) {
            ActiveTurn active = entry.getValue();
            repository.updateMessageStateIfSubmitted(active.messageId(), STATE_UNKNOWN, SHUTDOWN_DETAIL);
            activeTurns.remove(entry.getKey(), active);
            liveTurns.remove(active.messageId());
            // 关闭也是终态（UNKNOWN）：与 awaitReply 同一个出口——先在<b>本轮那个单元</b>里清条目，
            // 再放许可、最后归还单元在飞租约（否则下一次提交换个对象拿到的仍是「占用中」，条目也会继续
            // 指向一条随进程消失的租约；而租约不还会让这个单元在池关闭时白等都等不到）。
            clearGatewayEntryAndReleasePermit(active.unit(), active.messageId());
        }
    }

    /**
     * 有界陈旧归一化：把「早已超过一轮最长链、而且本 JVM 已经不再占有」的 SUBMITTED 落成 UNKNOWN。
     *
     * <p>两个条件缺一不可：
     * <ul>
     *   <li><b>时间</b>：阈值按实际最长链推导（见 {@code staleAfter}），覆盖 adopt 的多次 RPC + 空闲等待 +
     *       终态等待。用 {@code 2*replyTimeout} 会把「还在派发」的正常轮次误判成孤儿。</li>
     *   <li><b>占有</b>：本 JVM 仍在处理的行（派发中或等终态中）一律跳过。哪怕时间上老了，只要
     *       还有人等着它，它就不是崩溃遗留——误判会直接把一个正在进行的回合判死。</li>
     * </ul>
     */
    private void sweepStaleSubmitted(String conversationId) {
        repository.normalizeStaleSubmitted(conversationId, staleCutoff(), STALE_SUBMITTED_DETAIL, liveTurns);
    }

    /**
     * 送到网关：DB 里有持久会话 id 且本进程尚未绑定（首次提交 / 重启 / 绑定被淘汰）时走
     * {@code adopt}（session.resume），绝不用 {@code session.create} 假装续聊。
     *
     * <p>更严格的一条：既没绑定、也没有持久 id，但对话里已经有<b>可能到达过网关</b>的历史轮次时，
     * 也一律 fail-closed。此时新建会话只会得到一条与历史无关的新会话，而把「续聊」伪装成成功。
     *
     * <p><b>闸门（权威在账本）</b>：提交之前先数「别的、仍停在 SUBMITTED 的用户轮次」。
     * <ul>
     *   <li>数量 &gt; 0 → 账本证实这个对话还有一轮开着（可能仍在跑）→ 拒绝本轮，且<b>不得</b>清运行时
     *       的任何状态位：账本说开着就是开着。</li>
     *   <li>数量 == 0 → 账本证实上一轮在本端确实结束了 → 运行时里的占位（{@code inFlight}、残留句柄、
     *       {@code needsReconcile}）只是缓存，复位它再提交，否则一个残句柄会顶替账本把对话永久挡住。</li>
     * </ul>
     * 这里刻意不读「最新一行是什么状态」：{@code (created_at, id)} 在毫秒并列时的走向与写入先后无关，
     * 而「还有没有开着的行」与排序无关，只会更严。
     *
     * @return 拿到受理时为 {@link DispatchOutcome#accepted}；否则 {@link DispatchOutcome#unknown}，
     *         并带上<b>各自</b>的原因（「无句柄却有历史」与「上游调用失败」必须能区分开）
     */
    private String admissionRefusal(GatewayUnits.TurnUnit unit,
                                    AssistantConversationRepository.Conversation conversation,
                                    String attemptMessageId) {
        String key = conversation.id();
        String stored = conversation.storedSessionId();
        if (!unit.conversations().isBound(key) && (stored == null || stored.isBlank())
                && repository.countTurnsThatMayHaveReachedWire(key, attemptMessageId) > 0) {
            return NO_HANDLE_WITH_HISTORY_DETAIL;
        }
        if (repository.countOtherOpenTurns(key, attemptMessageId) > 0) {
            // 账本还开着：本轮不得触达 wire，也不得替上一轮推断终态。
            return ANOTHER_OPEN_TURN_DETAIL;
        }
        return null;
    }

    /**
     * 真正的派发：闸门已过、本轮端点已武装并配置，这里只做 wire 调用。
     *
     * <p>与闸门分开的原因是顺序：武装会作废同一对话上一轮的租约（{@code arm} 的语义），
     * 因此必须<b>先判定「本轮真的会去碰 wire」再武装</b>；否则一个被账本拒绝的轮次会把仍在跑的那一轮
     * 的端点顺手砍掉。
     *
     * <p>{@link IllegalArgumentException} 是「参数非法、未触达执行单元」，翻译成 400；
     * 其余 {@link RuntimeException} 原样抛出，由调用方落 UNKNOWN 并撤销本轮租约。
     */
    private HermesAssistantRuntime.Submission dispatchWire(GatewayUnits.TurnUnit unit,
                                                           AssistantConversationRepository.Conversation conversation,
                                                           String text) {
        String key = conversation.id();
        String stored = conversation.storedSessionId();
        boolean bound = unit.conversations().isBound(key);
        releaseRuntimeGate(unit, key);
        try {
            if (!bound && stored != null && !stored.isBlank()) {
                return unit.conversations().adopt(key, stored, text);
            }
            return unit.conversations().submit(key, workspace, text);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "对话提交参数非法：未触达执行单元");
        }
    }

    /**
     * 把运行时里的「上一轮未知」闸门复位成缓存状态——<b>只由账本驱动</b>：调用点已经确认账本里没有任何
     * 别的行还停在 SUBMITTED（见 {@link #dispatch}），因此本端确实没有未终结的轮次，内存占位就只是缓存。
     *
     * <p>为什么必须复位而不是「等运行时自己说需要」：{@code needsReconcile()} 在 {@code inFlight != null}
     * 时恒为 false（那句表达式的本意是「有消费者在用时别抢」，但它同时让「被漏掉的残句柄」看起来像
     * 「干净」）。把复位条件交给它，就等于让缓存当权威：一次窗口内的致命错误（{@code Error}）会留下
     * {@code inFlight} 且 {@code needsReconcile=false}，此后每一轮都被互斥闸门挡住到进程重启。
     *
     * <p>{@link HermesConversationService#reconcile} 只清内存状态位、不碰 wire、不做推断，因此「没有
     * 待对账状态」时调用它是无副作用的；没有绑定（从未提交/已被容量淘汰）时它显式报错，这里不建绑定，
     * 保持保守拒绝并交给 {@code dispatch} 的 adopt/首次提交路径。
     */
    private void releaseRuntimeGate(GatewayUnits.TurnUnit unit, String conversationId) {
        if (!unit.conversations().isBound(conversationId)) {
            return;
        }
        try {
            unit.conversations().reconcile(conversationId);
        } catch (RuntimeException raced) {
            // 判定与复位之间绑定消失/变化：保持拒绝，dispatch 仍会被互斥闸门挡住并落 UNKNOWN。
        }
    }

    /** @return true 表示这一轮已经交给回复消费者；false 表示被拒绝（调用方必须落 UNKNOWN）。 */
    private boolean dispatchReply(GatewayUnits.TurnUnit unit, String conversationId, String messageId,
                                  long actorId) {
        try {
            replies.execute(() -> awaitReply(unit, conversationId, messageId, actorId));
            return true;
        } catch (RejectedExecutionException rejected) {
            // 只摘掉自己那一条（值相等即同一个 TurnUnit 实例）：队列拒绝不是「清空这个会话的占位」的理由。
            activeTurns.remove(conversationId, new ActiveTurn(messageId, unit));
            return false;
        }
    }

    /**
     * 只接受权威终态：超时、错误事件、断连都落 UNKNOWN（可对账），空文本落 FAILED（已知没有内容）。
     * 只有非空文本才会被写成 COMPLETED——前端永远看不到「成功但空」的消息。
     *
     * <p>终态等待与收尾都在<b>本轮那个单元</b>上：会话 id 是该进程内的 id，换一个单元去等终态会永远等不到；
     * 清条目与放许可同样必须落在同一个单元（{@code unit} 由调用方从 {@link #activeTurns} 带过来）。
     */
    private void awaitReply(GatewayUnits.TurnUnit unit, String conversationId, String messageId, long actorId) {
        try {
            String text = unit.conversations().awaitFinalText(conversationId, replyTimeout);
            if (text == null || text.isBlank()) {
                repository.updateMessageStateIfSubmitted(messageId, STATE_FAILED,
                    "Hermes 终态回复为空，不能当作成功");
                return;
            }
            repository.completeTurn(messageId, conversationId, actorId, text);
        } catch (RuntimeException failure) {
            repository.updateMessageStateIfSubmitted(messageId, STATE_UNKNOWN, failureDetail(failure));
        } finally {
            // 顺序纪律（四步，每一步都不能提前）：
            // ① 账本已经写成终态（上面的 try 里）——反过来的话会出现「内存说上一轮结束了、账本还停在
            //    SUBMITTED」的窗口，而账本是闸门的权威，下一次提交会在这个窗口里被「还有一行开着」拒绝。
            // ② 仍在许可内清掉网关条目：条目只应在「本轮会话创建」这一个时刻被读到，终态之后让它继续
            //    指向本轮的（即将到 TTL 的）租约，等于给后续会话留了一个已死的能力来源。
            // ③ 释放许可并归还单元的<b>在飞租约</b>：必须在 ② 之后，否则下一轮可能在我们的 apply(null)
            //    之前就配置好自己的 URL，而我们的 null 会把它的条目抹掉；租约放在最后，是因为它一归零
            //    这个单元就可以被回收，收尾的后续步骤不该落在一个可能已经被拆掉的单元上。
            // ④ 最后才放开本层的并发闸门（activeTurns）与占有标记（liveTurns）：这样同一个对话在收尾
            //    期间拿到的是 409（可安全重试），而不是「许可刚空出来、条目还没擦干净」的中间态。
            // 写库失败时本 finally 仍会走完 ②③④，那一行只能等陈旧归一化（≤ staleAfter）自愈——
            // 这是已知的有界代价，不是回退。
            try {
                clearGatewayEntryAndReleasePermit(unit, messageId);
            } finally {
                // ④ 两道本层闸门在<b>任何</b>出口都要松开：清条目抛 Error 时（许可已由 ③ 的 finally
                //    归还）若被它挡在这里，这个对话此后每次提交都在 activeTurns 上 409 到进程重启，
                //    而账本里那一行早已是终态——又是一次「占位漏放」。
                activeTurns.remove(conversationId, new ActiveTurn(messageId, unit));
                liveTurns.remove(messageId);
            }
        }
    }

    /** 以唯一键插入本轮的行；已存在（另一个实例/并发重放先写了）时返回 false，由调用方按重放处理。 */
    private boolean insertOnce(AssistantConversationRepository.Message message) {
        try {
            repository.insertMessage(message);
            return true;
        } catch (DuplicateKeyException concurrent) {
            if (repository.findMessageByRequest(message.conversationId(), message.requestId()).isPresent()) {
                return false;
            }
            throw concurrent;
        }
    }

    private AssistantConversationRepository.Message requireRow(String conversationId, String requestId) {
        return repository.findMessageByRequest(conversationId, requestId).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.CONFLICT, "该 requestId 的账本行读取不一致，拒绝提交"));
    }

    private AssistantConversationRepository.Message row(String conversationId,
                                                        AssistantConversationAccess.Actor actor,
                                                        String requestId, String text, String state,
                                                        String upstreamTurnRef, String failureDetail) {
        String id = UUID.randomUUID().toString();
        return new AssistantConversationRepository.Message(id, conversationId, actor.id(), requestId, "USER", text,
            state, id, upstreamTurnRef, failureDetail, Instant.now());
    }

    /**
     * 取网关级单一 MCP 许可。
     *
     * <p>为什么是<b>全局</b>许可而不是按会话：网关只有一个 MCP 服务器条目，而且条目在会话创建时就被
     * 绑定到那个会话。两次改写交错时，后写的那个 URL 会被先派发的会话读到——那不是「降级成 401」，
     * 而是把另一轮 generation 的能力交给了这个会话。所以「武装 + 配置 + 派发」这一段必须跨会话互斥，
     * 并且一直持有到本轮终态：终态之前任何改写都可能落在别人的在建会话上。
     *
     * <p>{@code gatewayMcp == null}（开关关闭或没有实现）时没有共享资源可争：直接返回 {@code true}、
     * 不登记持有者（{@code mcpPermit == null}），租约语义与以前完全一致（{@link #armLease} 照旧执行）。
     *
     * @param messageId 本轮消息 id（轮次身份，不是会话 id：同一会话的两轮也不得同时持有）
     * @return {@code true} = 本轮可以武装/配置/派发；{@code false} = 别人持有，本轮必须<b>显式拒绝</b>
     *         （不武装、不配置、也不派发，见 {@link #submitAccepted}）
     */
    private boolean acquireGatewayMcpPermit(GatewayUnits.TurnUnit unit, String messageId) {
        return !configureGateway || unit.permit() == null || unit.permit().acquire(messageId);
    }

    /**
     * 释放网关级单一 MCP 许可：<b>唯一的释放原语</b>（语义见 {@link GatewayMcpPermit}）。
     *
     * <p>调用点只有三处，且每一处都是结构上必然执行的 {@code finally}：同步窗口（{@code submitAccepted}）、
     * 受理窗口（{@code handOff}）、终态窗口（{@code awaitReply}），外加关闭清扫（{@code abandonActiveTurns}，
     * 它写的就是终态 UNKNOWN）。刻意不写「逐路径清理」：窗口里以后再加步骤，也不需要作者记得补一次释放。
     *
     * <p>两条安全性质：<b>幂等</b>（重复释放无害）与<b>只放自己的</b>（非持有者的轮次调用它不会清掉
     * 持有者的许可——那会让两轮同时改同一个条目）。终态窗口里释放<b>不是</b>第一步：先要在许可内把
     * 网关条目清掉，见 {@link #clearGatewayEntryAndReleasePermit(String)}。
     */
    private void releaseGatewayMcpPermit(GatewayUnits.TurnUnit unit, String messageId) {
        if (configureGateway && unit.permit() != null) {
            unit.permit().release(messageId);
        }
    }

    /**
     * <b>本轮对单元的两层占用一起归还</b>：先放 MCP 许可（配置关闭时是空操作），再归还单元在飞租约。
     *
     * <p>两者的顺序是「许可在前、租约在后」：租约一归零，回收线程/池关闭就可以摘掉这个单元，因此它必须
     * 是<b>最后</b>被放开的——否则会出现「许可还挂在我们名下、单元却已经被关掉」的孤儿状态（许可随单元
     * 一起消失，但那一刻的判定读到的仍是「有人在用」）。
     *
     * <p>幂等：许可只放自己的（{@link GatewayMcpPermit#release(String)}），租约句柄重复释放只减一次计数。
     * 因此终态、失败、异常出口可以放心地各自调用它，不需要协调谁先谁后。
     */
    private void releaseTurnHold(GatewayUnits.TurnUnit unit, String messageId) {
        try {
            releaseGatewayMcpPermit(unit, messageId);
        } finally {
            unit.lease().release();
        }
    }

    /**
     * 本进程是否由这一轮持有<b>它所在单元</b>的 MCP 许可。只读观察：终态清条目的前置判断与用例的顺序断言
     * 都用它，不改变任何状态。池化装配下会扫一遍在册单元（{@link GatewayUnits#holdsPermit}）。
     */
    boolean holdsGatewayMcpPermit(String messageId) {
        return configureGateway && units.holdsPermit(messageId);
    }

    /**
     * <b>终态收尾的唯一出口</b>：仍在持有许可时清掉网关条目，<b>然后</b>放许可，<b>最后</b>归还单元在飞租约。
     *
     * <p>顺序不能换：
     * <ul>
     *   <li><b>先清条目</b>：终态的会话已经读过条目了，条目继续挂着只会让<b>后续</b>会话绑定一条已死租约，
     *       一直挂到下一轮改写或 TTL 到点；</li>
     *   <li><b>清条目仍在许可内</b>：先放许可的话，下一轮可能在我们执行 {@code apply(null)} 之前就武装/
     *       配置好自己的 URL，而我们的 {@code null} 随后会把<b>它的</b>条目抹掉——那同样是一次能力错配
     *       （它以为配置成功，会话却读不到条目）；</li>
     *   <li><b>租约最后归还</b>：租约归零意味着这个单元可以被回收/关闭。放在最前面归还，会让一次回收在
     *       「条目还没擦干净」的窗口里把单元拆掉，收尾的后续步骤就落在一具死掉的对象上。</li>
     * </ul>
     *
     * <p>释放本身仍是幂等的 {@code finally} 语义，所以 {@code Error}/{@code Throwable} 出口也覆盖到——
     * 这一点由结构保证，而不是「清条目之后紧接着写一句 release」：{@code apply(null)} 抛出 {@code Error}
     * （OOM、栈溢出）时，那一句会被整段跳过，于是账本已经落终态、全局许可却永久留在这一轮名下，此后
     * <b>所有对话</b>的提交都是 {@code MCP_PERMIT_BUSY}/503 直到进程重启；单元租约漏还则更直接——那个
     * 单元永远回收不掉（子进程与目录泄漏到进程退出）。因此释放只能写在 {@code finally} 里：清条目失败
     * 最多损失「条目立即失效」，绝不能连带损失「许可归还」与「租约归还」。
     */
    private void clearGatewayEntryAndReleasePermit(GatewayUnits.TurnUnit unit, String messageId) {
        try {
            clearGatewayEntryForTerminalTurn(unit, messageId);
        } finally {
            releaseTurnHold(unit, messageId);
        }
    }

    /**
     * 终态时清除网关条目（{@code apply(null)}）：只在<b>本轮仍持有许可</b>时执行；非持有者一次都不许写——
     * 那会把持有者刚写进去的 URL（正是它自己的会话正在用的那一条）抹掉。
     *
     * <p>失败只损「条目立即失效」这一件事，因此只记日志（不含 URL / lease id / token）：条目里剩下的 URL
     * 属于本轮这条即将走到 TTL（≤5 分钟）的租约，同对话的下一轮武装会先撤销旧租约，别的对话的下一轮会
     * 直接改写条目。痕迹表刻意不动——它记录的是<b>配置时刻</b>的事实，终态清除不是重新配置。
     *
     * <p>只捕 {@link RuntimeException} 是刻意的：{@code Error} 照旧上抛（不吞致命错误、也不把它伪装成
     * 「清过了」），而「许可必须归还」由调用方的 {@code finally} 保证，两者互不依赖。
     */
    private void clearGatewayEntryForTerminalTurn(GatewayUnits.TurnUnit unit, String messageId) {
        if (!configureGateway || unit.configurator() == null || !holdsGatewayMcpPermit(messageId)) {
            return;
        }
        try {
            unit.configurator().apply(null);
        } catch (RuntimeException bestEffort) {
            log.warn("终态清除 MCP 网关条目失败（type={}）：条目可能仍指向本轮的租约，下一轮改写或 TTL 会覆盖",
                bestEffort.getClass().getSimpleName());
        }
    }

    /**
     * 尽力而为地准备这一轮的 MCP 租约，并把<i>本轮</i>的端点交给调用方。
     *
     * <p>武装现在发生在<b>派发之前</b>：网关配置必须在模型会话建立之前拿到本轮 URL，否则模型这一轮
     * 看到的是上一轮的（已撤销）URL，或者根本没有 MCP 条目。失败仍然只意味着「本轮没有 MCP 端点」，
     * 不改变对话轮次的账本状态；异常在此终止，且不记录任何内容（token / lease id 都不进日志）。
     *
     * <p><b>前置条件</b>：调用方必须已经持有网关级许可（{@link #acquireGatewayMcpPermit}）。武装会作废
     * 同一对话上一轮的租约，一个非持有者在这里武装，就等于在持有者的条目还挂着的时候换掉了自己的能力来源。
     *
     * <p>返回空并不区分「没有 ACTIVE binding」与「武装时抛错」——两者对调用方是同一件事：
     * 本轮没有端点，必须 fail-closed 地清除网关条目，而不是回退旧 URL。
     *
     * @return 本轮端点（{@code <base>/mcp-lease/<leaseId>}）；空 = 本轮没有可用端点
     */
    private Optional<String> armLease(String conversationId, long actorId, long projectId, String requestId) {
        if (leases == null) {
            return Optional.empty();
        }
        try {
            leases.arm(conversationId, actorId, projectId, requestId);
            // arm 之后同一对话只剩本轮的租约（新一轮会作废上一轮的），所以这里不会拿到别人的 URL。
            return leases.endpointFor(conversationId);
        } catch (RuntimeException bestEffort) {
            // 无租约 = 无 MCP 端点；调用方必须重新铸租约，而不是退回任何共享/长期凭据。
            return Optional.empty();
        }
    }

    /**
     * 把本轮端点（或「没有端点」）交给网关：写条目 + reload 由 {@link GatewayMcpConfigurator} 负责。
     *
     * <p>三条纪律：
     * <ul>
     *   <li>{@code gatewayMcp == null}（开关关闭）时一次都不调用——关闭的开关不允许有任何网关侧动作；</li>
     *   <li>本轮没有端点时传 {@code null}，即<b>显式清除</b>条目：既不回退旧 URL，也不跳过调用；</li>
     *   <li>抛错只损本轮 MCP 可用性：不改变账本、不改写响应为成功、不重试；留一条不含 URL 的痕迹
     *       （{@link #MCP_TRACE_CONFIGURE_FAILED}）供对账，并撤销这个谁也够不着的端点。</li>
     * </ul>
     */
    private void configureGatewayMcp(GatewayUnits.TurnUnit unit, String conversationId, String requestId,
                                     Optional<String> endpoint) {
        if (!configureGateway || unit.configurator() == null) {
            return;
        }
        try {
            unit.configurator().apply(endpoint.orElse(null));
            recordMcpTrace(conversationId, requestId,
                endpoint.isPresent() ? MCP_TRACE_CONFIGURED : MCP_TRACE_NO_ENDPOINT);
        } catch (RuntimeException failure) {
            recordMcpTrace(conversationId, requestId, MCP_TRACE_CONFIGURE_FAILED);
            log.warn("MCP 网关配置失败（conversation={}, requestId={}, type={}）：本轮无 MCP 端点，需对账",
                conversationId, requestId, failure.getClass().getSimpleName());
            disarmTurn(conversationId, requestId);
        }
    }

    /**
     * 撤销并遗忘本对话的租约（本轮不再有可用端点）。撤销本身就是 fail-closed：URL 即使还留在网关配置里，
     * 也只会得到 401 —— 但这只是「记下的事实」，不是任何场景下的安全网：条目在会话创建时就被绑定，
     * 所以「先让别人读到了错的 URL、事后撤销」已经太晚（详见 {@link #acquireGatewayMcpPermit}）。
     *
     * <p>痕迹优先级：{@link #MCP_TRACE_CONFIGURE_FAILED} 与 {@link #MCP_TRACE_PERMIT_BUSY} 不能被覆盖
     * ——它们是两条需要人看的固定事实（配置失败 / 提交因许可被占而被拒绝）；其余路径记为
     * {@link #MCP_TRACE_REVOKED}。（许可被占的轮次其实走不到这里：它在派发之前就被拒绝，也不会 disarm，
     * 这里保留该判定只是防止以后有人把两者接在一起时把事实盖掉。）
     */
    private void disarmTurn(String conversationId, String requestId) {
        if (leases == null) {
            return;
        }
        try {
            leases.disarm(conversationId);
        } catch (RuntimeException bestEffort) {
            // 撤销失败也不能影响账本；租约仍有 TTL 兜底（≤5 分钟）。
        }
        String recorded = mcpTrace(conversationId, requestId);
        if (!MCP_TRACE_CONFIGURE_FAILED.equals(recorded) && !MCP_TRACE_PERMIT_BUSY.equals(recorded)) {
            recordMcpTrace(conversationId, requestId, MCP_TRACE_REVOKED);
        }
    }

    /**
     * 本轮在网关侧发生了什么：{@link #MCP_TRACE_CONFIGURED} / {@link #MCP_TRACE_NO_ENDPOINT} /
     * {@link #MCP_TRACE_CONFIGURE_FAILED} / {@link #MCP_TRACE_PERMIT_BUSY} / {@link #MCP_TRACE_REVOKED}。
     *
     * <p>{@code null} 表示这一轮从未走过配置器（开关关闭或没有实现）。只含固定标识——URL、lease id
     * 与 token 都不进这张表，也就不会出现在日志或对账输出里。
     */
    String mcpTrace(String conversationId, String requestId) {
        if (conversationId == null || requestId == null) {
            return null;
        }
        synchronized (mcpTraces) {
            return mcpTraces.get(traceKey(conversationId, requestId));
        }
    }

    /**
     * 痕迹记账的唯一入口（包内可见只为了用例能验证有界淘汰；不是对外 API）。
     *
     * <p>只接受固定常量：{@link #MCP_TRACE_CONFIGURED} / {@link #MCP_TRACE_NO_ENDPOINT} /
     * {@link #MCP_TRACE_CONFIGURE_FAILED} / {@link #MCP_TRACE_PERMIT_BUSY} / {@link #MCP_TRACE_REVOKED}。
     * 键是 {@code (conversationId, requestId)}，值是常量，所以 URL、lease id 与 token 都进不来。
     */
    void recordMcpTrace(String conversationId, String requestId, String trace) {
        if (conversationId == null || requestId == null || trace == null) {
            return;
        }
        synchronized (mcpTraces) {
            mcpTraces.put(traceKey(conversationId, requestId), trace);
            while (mcpTraces.size() > MAX_MCP_TRACES) {
                var oldest = mcpTraces.keySet().iterator();
                oldest.next();
                oldest.remove();
            }
        }
    }

    /** 痕迹表当前条数（有界性证据：永远 ≤ {@value #MAX_MCP_TRACES}）。 */
    int mcpTraceCount() {
        synchronized (mcpTraces) {
            return mcpTraces.size();
        }
    }

    /** 痕迹表当前出现过的<b>全部</b>取值；用例据此断言「只可能是固定常量」。 */
    Set<String> mcpTraceValues() {
        synchronized (mcpTraces) {
            return Set.copyOf(mcpTraces.values());
        }
    }

    /** 该轮次是否仍由本进程持有（用于 {@code liveTurns} 与占位表的一致性判断）。 */
    private boolean awaiting(String messageId) {
        for (ActiveTurn active : activeTurns.values()) {
            if (active.messageId().equals(messageId)) {
                return true;
            }
        }
        return false;
    }

    private static String traceKey(String conversationId, String requestId) {
        return conversationId + '\u0000' + requestId;
    }

    /** 本地失败收尾：这一行必须离开 SUBMITTED（UNKNOWN = 结果不可判定，需对账）。 */
    private SubmitReceipt failed(AssistantConversationRepository.Message attempt, String detail) {
        repository.updateMessageStateIfSubmitted(attempt.id(), STATE_UNKNOWN, detail);
        removeActiveTurn(attempt.conversationId(), attempt.id());
        liveTurns.remove(attempt.id());
        return new SubmitReceipt(attempt.conversationId(), attempt.id(), attempt.turnId(), STATE_UNKNOWN, null,
            attempt.upstreamTurnRef(), true, detail);
    }

    /**
     * 摘掉「<b>本条消息</b>」在这一对话上的占位（若存在）。
     *
     * <p><b>为什么不能写成 {@code activeTurns.remove(conversationId, messageId)}</b>：{@code ConcurrentHashMap}
     * 只有 {@code remove(Object key, Object value)}，而这里的值是 {@link ActiveTurn}。传一个 {@code String}
     * 作 value 时永远不相等，调用编译通过、运行恒不命中——它看起来守住了「失败后不留占位」，实际上一次都
     * 没守住。这是一个只在出事时才暴露的陷阱（占位残留 = 该对话此后每次提交都 409 到进程重启），
     * 所以这里用 {@code computeIfPresent} 显式比较 {@link ActiveTurn#messageId()}：
     * <b>只摘自己的</b>，别人的占位（理论上同一对话不可能并存两轮，但跨实例/未来的改动可能打破这个前提）
     * 一次都不会被误删。
     *
     * <p>幂等：没有占位或占位属于别的消息时是空操作。
     */
    private void removeActiveTurn(String conversationId, String messageId) {
        activeTurns.computeIfPresent(conversationId,
            (key, active) -> active.messageId().equals(messageId) ? null : active);
    }

    /**
     * 未触达 wire 的<b>确定性</b>拒绝（许可被占）：账本落 {@code FAILED} 而不是 {@code UNKNOWN}——
     * 没有任何结果需要推断（一帧都没发出去、一个会话都没建），因此 {@code reconcileRequired=false}。
     * 与「网关不可达」同一类：零副作用、已知失败、{@code detail} 原样回到响应里。
     *
     * <p>落一行 {@code FAILED} 而不是只记痕迹，是这一条可以核对的地方：同一 {@code requestId} 的重放
     * 只会看到这一行（{@link #replay}），绝不会因为许可后来空出来而被悄悄派发出去。
     *
     * <p>而且<b>必须</b>是 {@code FAILED} 而不是 {@code UNKNOWN}：{@link #admissionRefusal} 把「除本轮
     * 之外任何不是 FAILED 的历史行」当作「可能已经打到过网关」，所以一行 UNKNOWN 会让这个对话此后的
     * 每一轮都被「无句柄却有历史」挡住——一次并发让位就变成永久砖死。这里没有任何结果需要推断，
     * 因此 FAILED 既诚实又不留毒。
     */
    private SubmitReceipt refusedWithoutWire(AssistantConversationRepository.Message attempt, String detail) {
        repository.updateMessageStateIfSubmitted(attempt.id(), STATE_FAILED, detail);
        // 同样是 remove(k, v) 陷阱：值类型是 ActiveTurn，必须按消息 id 匹配（见 removeActiveTurn）。
        removeActiveTurn(attempt.conversationId(), attempt.id());
        liveTurns.remove(attempt.id());
        return new SubmitReceipt(attempt.conversationId(), attempt.id(), attempt.turnId(), STATE_FAILED, null,
            attempt.upstreamTurnRef(), false, detail);
    }

    private SubmitReceipt replay(AssistantConversationRepository.Message existing, String text) {
        if (existing.body() == null || !existing.body().equals(text)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "同一 requestId 的文本不一致，拒绝覆盖");
        }
        if (STATE_SUBMITTED.equals(existing.state()) && !repository.accepted(existing.id())) {
            // 在途尝试没有受理证据：既不能当成已受理返回 202，也不能重投同一 requestId（那可能造成第二轮）。
            return failed(existing, UNPROVEN_SUBMIT_DETAIL);
        }
        return receipt(existing, null, STATE_UNKNOWN.equals(existing.state()), existing.failureDetail());
    }

    private SubmitReceipt receipt(AssistantConversationRepository.Message message, String acceptance,
                                  boolean reconcileRequired, String detail) {
        return new SubmitReceipt(message.conversationId(), message.id(), message.turnId(), message.state(),
            acceptance, message.upstreamTurnRef(), reconcileRequired, detail);
    }

    private ConversationView view(AssistantConversationRepository.InsertedConversation inserted) {
        AssistantConversationRepository.Conversation conversation = inserted.conversation();
        return new ConversationView(conversation.id(), conversation.projectId(), conversation.requestId(),
            conversation.title(), conversation.createdAt().toString(), inserted.created());
    }

    /** 只允许本人的对话；他人对话一律 404（不确认存在性）。 */
    private AssistantConversationRepository.Conversation ownedConversation(
            AssistantConversationAccess.Actor actor, String conversationId) {
        return repository.findConversation(conversationId)
            .filter(conversation -> conversation.actorId() == actor.id())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "对话不存在"));
    }

    private Object lockFor(String conversationId) {
        return submitLocks[Math.floorMod(conversationId.hashCode(), submitLocks.length)];
    }

    /** 超过这个时刻仍是 SUBMITTED、且本 JVM 已不再占有的行，就不可能还在一次正常的轮次之内。 */
    private Instant staleCutoff() {
        return Instant.now().minus(staleAfter);
    }

    private static void requireOnlyKeys(Map<String, Object> payload, Set<String> allowed, Set<String> required) {
        if (payload == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体必填");
        }
        for (String key : payload.keySet()) {
            if (!allowed.contains(key)) {
                // 身份字段不接受来自请求体的任何值。
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不接受的字段：" + key);
            }
        }
        for (String key : required) {
            if (!payload.containsKey(key)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少字段：" + key);
            }
        }
    }

    private static void requirePositive(Duration value, String what) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(what + "必须为正");
        }
    }

    private static long positiveLong(Object value) {
        if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "projectId 必须是正整数");
        }
        return ((Number) value).longValue();
    }

    private static String requestId(Object value) {
        if (!(value instanceof String text) || !REQUEST_ID.matcher(text).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "requestId 非法");
        }
        return text;
    }

    private static String text(Object value) {
        if (!(value instanceof String body) || body.isBlank() || body.length() > MAX_TEXT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text 必填且不超过 " + MAX_TEXT + " 字符");
        }
        return body;
    }

    private static String title(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String title) || title.length() > MAX_TITLE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title 不超过 " + MAX_TITLE + " 字符");
        }
        return title;
    }

    /**
     * 自己的失败文案；绝不把上游回执原文（可能含 payload/凭据）搬进账本与响应。
     *
     * <p>只保留两样可结构化的事实：上游 JSON-RPC 方法名与<b>顶层整数</b>错误码；其余一律用固定文案 +
     * 异常类型名。异常正文（{@code getMessage()}）与 cause 都不外泄——它们常常就是整段请求/回执。
     */
    private static String failureDetail(RuntimeException failure) {
        for (Throwable cursor = failure; cursor != null; cursor = cursor.getCause()) {
            if (cursor instanceof HermesGateway.UpstreamRpcError upstream) {
                return truncate("Hermes " + upstream.method() + " 拒绝（code=" + upstream.topLevelCode()
                    + "），结果未知需对账");
            }
        }
        return truncate("Hermes 调用失败（" + failure.getClass().getSimpleName() + "），结果未知需对账");
    }

    private static String truncate(String value) {
        String flat = value.replace('\n', ' ').replace('\r', ' ').trim();
        return flat.length() <= MAX_DETAIL ? flat : flat.substring(0, MAX_DETAIL);
    }
}
