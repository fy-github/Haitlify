package com.haitlify.aitest.platform.assistant.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.server.ResponseStatusException;

import com.haitlify.aitest.platform.assistant.hermes.HermesGateway;
import com.haitlify.aitest.platform.assistant.runtime.HermesConversationService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 账本纪律的行为用例：崩溃窗口、陈旧 SUBMITTED、对账回路、无句柄续聊、回复队列与关闭、失败文案脱敏。
 *
 * <p>这些用例全部走 {@link AssistantConversationService} 的对外行为（提交回执 / 分页读取 / 账本行 / wire 帧数），
 * 不依赖被测类的私有字段——唯一例外是回复线程池的配置，因为「无界队列」本身没有别的可观察面。
 */
class AssistantConversationLedgerTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    // ------------------------------------------------------------------ fakes

    /**
     * 内存账本。刻意保留「新增仓储方法」但不加 {@code @Override}：这样用例在旧实现上也能编译，
     * 失败只会是断言（行为没做到），而不是编译错误。
     */
    static final class FakeRepository extends AssistantConversationRepository {

        final Map<String, Conversation> conversations = new ConcurrentHashMap<>();
        final Map<String, Message> messages = Collections.synchronizedMap(new LinkedHashMap<>());
        final Map<String, Instant> updatedAt = new ConcurrentHashMap<>();
        final Set<String> accepted = ConcurrentHashMap.newKeySet();
        final List<String> journal = Collections.synchronizedList(new ArrayList<>());
        final FakeGateway gateway;

        /** 模拟「进程在 wire 受理之后就死了」：此后任何账本写入都失败并冒泡。 */
        volatile boolean deadAfterWire;
        /** 模拟「状态写入没有生效」：用来证明账本没证实终态时不得 reconcile。 */
        volatile boolean freezeStates;
        /** 模拟「受理落库失败」（真实形状：DB 抖动 / 句柄超长）：wire 已受理，但受理证据写不进去。 */
        volatile boolean failMarkAccepted;
        /**
         * 模拟「受理窗口里冒出致命错误」（OOM / StackOverflow / 驱动致命错误）：{@link Error} 不是
         * {@link RuntimeException}，旧实现没有任何 catch 也没有 finally，占位会永久留在绑定上。
         *
         * <p>用自定义 {@link Error} 子类而不是真的 {@link OutOfMemoryError}：后者会让 surefire 的
         * 分叉进程把它当成致命 OOM 处理（整个套件会被判为 forked-process error），而这里要验证的只是
         * 「非 RuntimeException 的 Throwable 也走同一个释放点」。
         */
        volatile boolean failMarkAcceptedWithError;

        FakeRepository(FakeGateway gateway) {
            super(null, null);
            this.gateway = gateway;
        }

        private void dieIfDeadAfterWire(String where) {
            if (deadAfterWire && gateway != null && gateway.submits.get() > 0) {
                journal.add("death@" + where);
                throw new IllegalStateException("simulated process death at " + where);
            }
        }

        @Override
        Optional<Conversation> findConversation(String id) {
            return Optional.ofNullable(conversations.get(id));
        }

        @Override
        Optional<Conversation> findConversationByRequest(long actorId, long projectId, String requestId) {
            return conversations.values().stream()
                .filter(c -> c.actorId() == actorId && c.projectId() == projectId && c.requestId().equals(requestId))
                .findFirst();
        }

        @Override
        InsertedConversation insertConversationIfAbsent(Conversation conversation) {
            Conversation existing = conversations.putIfAbsent(conversation.id(), conversation);
            return new InsertedConversation(existing == null ? conversation : existing, existing == null);
        }

        @Override
        void rememberStoredSession(String conversationId, String storedSessionId) {
            dieIfDeadAfterWire("remember-stored");
            journal.add("remember-stored:" + conversationId);
            if (storedSessionId == null || storedSessionId.isBlank()) {
                return;
            }
            conversations.computeIfPresent(conversationId, (id, c) -> new Conversation(c.id(), c.tenantId(),
                c.projectId(), c.actorId(), c.requestId(), c.title(), storedSessionId, c.createdAt()));
        }

        @Override
        Optional<Message> findMessageByRequest(String conversationId, String requestId) {
            synchronized (messages) {
                return messages.values().stream()
                    .filter(m -> m.conversationId().equals(conversationId) && requestId.equals(m.requestId()))
                    .findFirst();
            }
        }

        @Override
        Message insertMessage(Message message) {
            dieIfDeadAfterWire("insert");
            journal.add("insert:" + message.role() + ":" + message.state());
            synchronized (messages) {
                if (message.requestId() != null) {
                    boolean duplicate = messages.values().stream().anyMatch(m ->
                        m.conversationId().equals(message.conversationId())
                            && message.requestId().equals(m.requestId()));
                    if (duplicate) {
                        throw new DuplicateKeyException("uk_assistant_conversation_message_request");
                    }
                }
                messages.put(message.id(), message);
                updatedAt.put(message.id(), message.createdAt());
            }
            return message;
        }

        @Override
        void updateMessageState(String messageId, String state, String failureDetail) {
            dieIfDeadAfterWire("state");
            if (freezeStates) {
                return;
            }
            journal.add("state:" + state);
            synchronized (messages) {
                Message m = messages.get(messageId);
                if (m != null) {
                    messages.put(messageId, new Message(m.id(), m.conversationId(), m.actorId(), m.requestId(),
                        m.role(), m.body(), state, m.turnId(), m.upstreamTurnRef(), failureDetail, m.createdAt()));
                    updatedAt.put(messageId, Instant.now());
                }
            }
        }

        /** 只更新仍处于 SUBMITTED 的行（真正的 SQL 也是这个条件）。 */
        void updateMessageStateIfSubmitted(String messageId, String state, String failureDetail) {
            Message current;
            synchronized (messages) {
                current = messages.get(messageId);
            }
            if (current == null || !"SUBMITTED".equals(current.state())) {
                return;
            }
            updateMessageState(messageId, state, failureDetail);
        }

        /** 受理落账：`accepted_at` 是「wire 已受理」的唯一证据（与真实仓储的事务等价）。 */
        void markAccepted(String messageId, String conversationId, String upstreamTurnRef, String storedSessionId) {
            dieIfDeadAfterWire("mark-accepted");
            if (failMarkAcceptedWithError) {
                journal.add("mark-accepted-error");
                throw new AcceptanceWindowError("simulated acceptance-window error");
            }
            if (failMarkAccepted) {
                journal.add("mark-accepted-failed");
                throw new IllegalStateException("simulated acceptance write failure");
            }
            journal.add("mark-accepted");
            accepted.add(messageId);
            synchronized (messages) {
                Message m = messages.get(messageId);
                if (m != null) {
                    messages.put(messageId, new Message(m.id(), m.conversationId(), m.actorId(), m.requestId(),
                        m.role(), m.body(), m.state(), m.turnId(), upstreamTurnRef, m.failureDetail(), m.createdAt()));
                    updatedAt.put(messageId, Instant.now());
                }
            }
            rememberStoredSession(conversationId, storedSessionId);
        }

        boolean accepted(String messageId) {
            return accepted.contains(messageId);
        }

        /**
         * 陈旧归一化：与真实 SQL 同一条判据——越过 cutoff <b>并且</b>不在本 JVM 占有集里。
         * 存活集为空表示（像旧实现那样）只看时间。
         */
        void normalizeStaleSubmitted(String conversationId, Instant cutoff, String detail,
                                    java.util.Collection<String> liveIds) {
            if (freezeStates) {
                return;
            }
            Set<String> owned = liveIds == null ? Set.of() : Set.copyOf(liveIds);
            List<String> stale = new ArrayList<>();
            synchronized (messages) {
                for (Message m : messages.values()) {
                    if (m.conversationId().equals(conversationId) && "SUBMITTED".equals(m.state())
                            && !owned.contains(m.id())
                            && updatedAt.getOrDefault(m.id(), m.createdAt()).isBefore(cutoff)) {
                        stale.add(m.id());
                    }
                }
            }
            for (String id : stale) {
                updateMessageState(id, "UNKNOWN", detail);
            }
        }

        /** 旧签名（回退验证用）：不排除任何本 JVM 占有的行。 */
        void normalizeStaleSubmitted(String conversationId, Instant cutoff, String detail) {
            normalizeStaleSubmitted(conversationId, cutoff, detail, Set.of());
        }

        /** 仍停在 SUBMITTED 的其它用户轮次数（真实 SQL 是 count(*)）。 */
        int countOtherOpenTurns(String conversationId, String excludingMessageId) {
            synchronized (messages) {
                return (int) messages.values().stream()
                    .filter(m -> m.conversationId().equals(conversationId) && "USER".equals(m.role()))
                    .filter(m -> excludingMessageId == null || !m.id().equals(excludingMessageId))
                    .filter(m -> "SUBMITTED".equals(m.state()))
                    .count();
            }
        }

        Optional<Message> findLastUserMessage(String conversationId, String excludingMessageId) {
            synchronized (messages) {
                Message last = null;
                for (Message m : messages.values()) {
                    if (!m.conversationId().equals(conversationId) || !"USER".equals(m.role())
                            || m.id().equals(excludingMessageId)) {
                        continue;
                    }
                    if (last == null || !m.createdAt().isBefore(last.createdAt())) {
                        last = m;
                    }
                }
                return Optional.ofNullable(last);
            }
        }

        /** 可能已经到达 wire 的历史轮次：FAILED 是「已知零副作用」，其余状态都算「可能有副作用」。 */
        int countTurnsThatMayHaveReachedWire(String conversationId, String excludingMessageId) {
            synchronized (messages) {
                return (int) messages.values().stream()
                    .filter(m -> m.conversationId().equals(conversationId) && "USER".equals(m.role()))
                    .filter(m -> excludingMessageId == null || !m.id().equals(excludingMessageId))
                    .filter(m -> !"FAILED".equals(m.state()))
                    .count();
            }
        }

        @Override
        void completeTurn(String userMessageId, String conversationId, long actorId, String replyText) {
            journal.add("complete-turn");
            updateMessageState(userMessageId, "COMPLETED", null);
            insertMessage(new Message(UUID.randomUUID().toString(), conversationId, actorId, null, "ASSISTANT",
                replyText, "COMPLETED", null, null, null, Instant.now()));
        }

        @Override
        List<Message> listMessages(String conversationId, int limit, int offset) {
            synchronized (messages) {
                return messages.values().stream().filter(m -> m.conversationId().equals(conversationId))
                    .skip(offset).limit(limit).toList();
            }
        }

        @Override
        int countMessages(String conversationId) {
            synchronized (messages) {
                return (int) messages.values().stream().filter(m -> m.conversationId().equals(conversationId)).count();
            }
        }

        /** 直接种一行历史（模拟上一进程留下的账本）。 */
        Message seed(String conversationId, String requestId, String role, String body, String state, Instant when) {
            return seed(conversationId, requestId, role, body, state, when, UUID.randomUUID().toString());
        }

        /** 同上，但 id 由用例指定：用来固定「毫秒并列时 (created_at, id) 定序会挑到哪一行」。 */
        Message seed(String conversationId, String requestId, String role, String body, String state, Instant when,
                     String id) {
            Message message = new Message(id, conversationId, 7L, requestId, role, body, state,
                "USER".equals(role) ? id : null, null, null, when);
            messages.put(id, message);
            updatedAt.put(id, when);
            return message;
        }

        List<Message> userRows(String conversationId) {
            synchronized (messages) {
                return messages.values().stream()
                    .filter(m -> m.conversationId().equals(conversationId) && "USER".equals(m.role())).toList();
            }
        }
    }

    static final class FakeAccess extends AssistantConversationAccess {
        FakeAccess() {
            super(null, null, null);
        }

        @Override
        Actor requireActor() {
            return new Actor(7L, "ledger-user", "ADMIN");
        }

        @Override
        long requireProject(Actor actor, long projectId) {
            return 1L;
        }
    }

    static final class FakeGateway implements HermesGateway {
        final AtomicInteger submits = new AtomicInteger();
        final AtomicInteger creates = new AtomicInteger();
        final Map<String, Deque<String>> pending = new ConcurrentHashMap<>();
        volatile boolean available = true;
        volatile boolean failNextSubmit;
        volatile boolean hang;
        /** 在触达 wire 之前就直接死掉（{@link Error} 不是 RuntimeException，模拟进程消失）。 */
        volatile boolean dieBeforeWire;
        final AtomicInteger drainCalls = new AtomicInteger();
        volatile RuntimeException drainFailure;
        /** 只从第 N 次 drain 开始失败：dispatch 的「旧终态屏障」那一次必须正常返回。 */
        volatile int failDrainAfterCalls = -1;
        /** 把一次 {@code prompt.submit} 停在 wire 之前：用来观察「仍在派发」的那一段。 */
        volatile CountDownLatch submitGate;

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public Session createSession(String cwd) {
            int seq = creates.incrementAndGet();
            return new Session("live-" + seq, "{\"result\":{\"session_id\":\"live-" + seq
                + "\",\"stored_session_id\":\"stored-" + seq + "\"}}");
        }

        @Override
        public Turn submitPrompt(String sessionId, String prompt) {
            CountDownLatch gate = submitGate;
            if (gate != null) {
                try {
                    gate.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            if (dieBeforeWire) {
                dieBeforeWire = false;
                throw new AssertionError("simulated process death before the wire accept");
            }
            int seq = submits.incrementAndGet();
            if (failNextSubmit) {
                failNextSubmit = false;
                throw new IllegalStateException("simulated transient transport failure");
            }
            if (!hang) {
                pending.computeIfAbsent(sessionId, key -> new ConcurrentLinkedDeque<>()).add("answer:" + seq);
            }
            return new Turn(null, "{\"result\":{\"status\":\"streaming\"}}");
        }

        @Override
        public boolean interrupt(String sessionId) {
            return true;
        }

        @Override
        public Resume resumeSession(String storedSessionId) {
            return new Resume(storedSessionId, "resumed-" + storedSessionId, storedSessionId, "idle", false, 0, "{}");
        }

        @Override
        public List<JsonNode> drainEvents(String sessionId) {
            int calls = drainCalls.incrementAndGet();
            RuntimeException failure = drainFailure;
            if (failure != null && calls > failDrainAfterCalls) {
                throw failure;
            }
            Deque<String> queue = pending.get(sessionId);
            String text = queue == null ? null : queue.poll();
            if (text == null) {
                return List.of();
            }
            ObjectNode envelope = MAPPER.createObjectNode();
            envelope.put("jsonrpc", "2.0");
            envelope.put("method", "event");
            ObjectNode params = envelope.putObject("params");
            params.put("session_id", sessionId);
            params.put("type", "message.complete");
            ObjectNode payload = params.putObject("payload");
            payload.put("status", "complete");
            payload.put("text", text);
            return List.of((JsonNode) envelope);
        }

        /** 让一条陈旧终态排在该 live 会话的缓冲里（上一轮迟到的答案）。 */
        void bufferTerminal(String sessionId, String text) {
            pending.computeIfAbsent(sessionId, key -> new ConcurrentLinkedDeque<>()).add(text);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static final String CONV = "conv-1";

    private static AssistantConversationRepository.Conversation conversation(String storedSessionId) {
        return new AssistantConversationRepository.Conversation(CONV, 1L, 1L, 7L, "create-1", null,
            storedSessionId, Instant.now());
    }

    private static AssistantConversationService service(FakeRepository repository, FakeGateway gateway) {
        return new AssistantConversationService(repository, new FakeAccess(),
            new HermesConversationService(gateway, MAPPER, 32, Duration.ofSeconds(1)), gateway, "/tmp",
            Duration.ofSeconds(1));
    }

    /**
     * 同时握住被测服务<b>与它使用的会话服务</b>：判定「会话服务里的本轮占位有没有被漏掉」必须看后者，
     * 只看账本会漏掉这一类砖化。
     */
    private record Harness(AssistantConversationService service, HermesConversationService conversations,
                           FakeRepository repository, FakeGateway gateway) {}

    private static Harness harness(Duration replyTimeout) {
        return harness(replyTimeout, Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    private static Harness harness(Duration replyTimeout, Duration rpcTimeout, Duration resumeIdleWait) {
        return harness(replyTimeout, rpcTimeout, resumeIdleWait, null);
    }

    /** 注入回复消费池（测试缝）：用来让 {@code replies.execute} 这一跳抛出生产池构造不出的出口。 */
    private static Harness harness(Duration replyTimeout, ThreadPoolExecutor replyExecutor) {
        return harness(replyTimeout, Duration.ofSeconds(1), Duration.ofSeconds(1), replyExecutor);
    }

    private static Harness harness(Duration replyTimeout, Duration rpcTimeout, Duration resumeIdleWait,
                                   ThreadPoolExecutor replyExecutor) {
        FakeGateway gateway = new FakeGateway();
        FakeRepository repository = new FakeRepository(gateway);
        repository.conversations.put(CONV, conversation(null));
        HermesConversationService conversations =
            new HermesConversationService(gateway, MAPPER, 32, resumeIdleWait);
        AssistantConversationService service = new AssistantConversationService(repository, new FakeAccess(),
            conversations, gateway, "/tmp", replyTimeout, rpcTimeout, resumeIdleWait, replyExecutor);
        return new Harness(service, conversations, repository, gateway);
    }

    private static ThreadPoolExecutor replyPool(AssistantConversationService service) throws Exception {
        var field = AssistantConversationService.class.getDeclaredField("replies");
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(service);
    }

    private static Map<String, Object> msg(String requestId, String text) {
        return Map.of("requestId", requestId, "text", text);
    }

    private static String stateOf(FakeRepository repository, String conversationId) {
        return repository.userRows(conversationId).getFirst().state();
    }

    private static void awaitTerminal(FakeRepository repository, String conversationId, int expectedRows) {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            if (repository.countMessages(conversationId) >= expectedRows) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        }
        throw new AssertionError("3s 内没有等到 " + expectedRows + " 条账本行");
    }

    // ------------------------------------------------- B1 崩溃窗口 / 幂等

    /**
     * B1：受理之后、账本更新之前进程死亡。
     *
     * <p>要求：账本里必须已经有这一轮的行（不能出现「wire 已受理但账本无行」），重启后同一 requestId
     * 不得再次提交到 wire；而且因为 stored id 没落账，后面的新轮次必须 fail-closed，不得 session.create 冒充续聊。
     */
    @Test
    void aCrashRightAfterTheWireAcceptLeavesTheRowAndNoSecondWireSubmit() {
        FakeGateway gateway = new FakeGateway();
        FakeRepository repository = new FakeRepository(gateway);
        repository.conversations.put(CONV, conversation(null));
        AssistantConversationService service = service(repository, gateway);
        repository.deadAfterWire = true;

        assertThatThrownBy(() -> service.submit(CONV, msg("req-1", "hello")))
            .isInstanceOf(RuntimeException.class);

        assertThat(gateway.submits.get()).as("wire 确实受理过这一轮").isEqualTo(1);
        assertThat(repository.userRows(CONV)).as("受理之后必须已经有一行账本").hasSize(1);
        assertThat(stateOf(repository, CONV)).isEqualTo("SUBMITTED");

        // 重启：新实例、内存绑定为空、进程健康
        repository.deadAfterWire = false;
        AssistantConversationService restarted = service(repository, gateway);

        AssistantConversationService.SubmitReceipt replay = restarted.submit(CONV, msg("req-1", "hello"));
        assertThat(gateway.submits.get()).as("同一 requestId 不得第二次打到 wire").isEqualTo(1);
        assertThat(replay.state()).as("没人能证明这一轮被受理过，只能是对账中的 UNKNOWN").isEqualTo("UNKNOWN");
        assertThat(replay.reconcileRequired()).isTrue();
        assertThat(repository.userRows(CONV)).hasSize(1);

        // 新的一轮：没有持久句柄却有历史，绝不能新建会话冒充续聊
        AssistantConversationService.SubmitReceipt next = restarted.submit(CONV, msg("req-2", "again"));
        assertThat(next.state()).isEqualTo("UNKNOWN");
        assertThat(gateway.creates.get()).as("不得新建 Hermes 会话冒充续聊").isEqualTo(1);
        assertThat(gateway.submits.get()).isEqualTo(1);
    }

    /** B1：dispatch 之前就死了——同样必须先有一行，且重启后不得补投同一 requestId。 */
    @Test
    void aCrashBeforeTheWireLeavesTheRowAndNeverRedispatchesTheSameRequestId() {
        FakeGateway gateway = new FakeGateway();
        FakeRepository repository = new FakeRepository(gateway);
        repository.conversations.put(CONV, conversation(null));
        AssistantConversationService service = service(repository, gateway);
        gateway.dieBeforeWire = true;

        assertThatThrownBy(() -> service.submit(CONV, msg("req-1", "hello"))).isInstanceOf(Error.class);

        assertThat(gateway.submits.get()).isZero();
        assertThat(repository.userRows(CONV)).as("尝试一旦开始就必须在账本里留行").hasSize(1);
        assertThat(stateOf(repository, CONV)).isEqualTo("SUBMITTED");

        AssistantConversationService restarted = service(repository, gateway);
        AssistantConversationService.SubmitReceipt replay = restarted.submit(CONV, msg("req-1", "hello"));

        assertThat(gateway.submits.get()).as("无法证明是否已触达 wire，不得重投").isZero();
        assertThat(replay.state()).isEqualTo("UNKNOWN");
        assertThat(replay.reconcileRequired()).isTrue();
    }

    /** B1：并发重放同一 requestId 只能产生一轮 wire 提交与一行账本。 */
    @Test
    void concurrentReplayOfOneRequestIdReachesTheWireOnce() throws Exception {
        FakeGateway gateway = new FakeGateway();
        FakeRepository repository = new FakeRepository(gateway);
        repository.conversations.put(CONV, conversation(null));
        AssistantConversationService service = service(repository, gateway);

        List<AssistantConversationService.SubmitReceipt> receipts = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    receipts.add(service.submit(CONV, msg("req-1", "same")));
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            }, "concurrent-replay-" + index);
            thread.start();
            threads.add(thread);
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join(5000);
        }

        assertThat(failures).isEmpty();
        assertThat(gateway.submits.get()).as("同一 requestId 只许一轮 wire 提交").isEqualTo(1);
        assertThat(gateway.creates.get()).isEqualTo(1);
        assertThat(receipts).hasSize(4);
        assertThat(receipts).extracting(AssistantConversationService.SubmitReceipt::messageId).containsOnly(
            receipts.getFirst().messageId());
        assertThat(repository.userRows(CONV)).hasSize(1);
    }

    // ------------------------------------------------- B2 陈旧 SUBMITTED

    /** B2：一小时前遗留的 SUBMITTED 不得作为事实终态被读出来。 */
    @Test
    void aStaleSubmittedRowIsNormalizedToUnknownOnRead() {
        FakeGateway gateway = new FakeGateway();
        FakeRepository repository = new FakeRepository(gateway);
        repository.conversations.put(CONV, conversation("stored-0"));
        repository.seed(CONV, "req-old", "USER", "old question", "SUBMITTED", Instant.now().minusSeconds(3600));

        AssistantConversationService service = service(repository, gateway);
        AssistantConversationService.MessagePage page = service.messages(CONV, null, null);

        assertThat(page.messages()).hasSize(1);
        assertThat(page.messages().getFirst().state()).as("陈旧 SUBMITTED 必须透出为 UNKNOWN").isEqualTo("UNKNOWN");
        assertThat(page.messages().getFirst().text()).isEqualTo("old question");
    }

    /** B2：陈旧一轮不得被后续轮次补成 COMPLETED（旧答案不能变成新答案）。 */
    @Test
    void aStaleSubmittedRowIsNeverCompletedByALaterTurn() {
        FakeGateway gateway = new FakeGateway();
        FakeRepository repository = new FakeRepository(gateway);
        repository.conversations.put(CONV, conversation("stored-0"));
        repository.seed(CONV, "req-old", "USER", "old question", "SUBMITTED", Instant.now().minusSeconds(3600));

        AssistantConversationService service = service(repository, gateway);
        AssistantConversationService.SubmitReceipt next = service.submit(CONV, msg("req-new", "new question"));
        assertThat(next.state()).isEqualTo("SUBMITTED");
        awaitTerminal(repository, CONV, 3);

        List<AssistantConversationRepository.Message> rows = repository.listMessages(CONV, 10, 0);
        AssistantConversationRepository.Message stale = rows.stream()
            .filter(row -> "old question".equals(row.body())).findFirst().orElseThrow();
        AssistantConversationRepository.Message fresh = rows.stream()
            .filter(row -> "new question".equals(row.body())).findFirst().orElseThrow();
        List<AssistantConversationRepository.Message> replies = rows.stream()
            .filter(row -> "ASSISTANT".equals(row.role())).toList();

        assertThat(stale.state()).as("陈旧一轮只能是 UNKNOWN，不能被补成 COMPLETED").isEqualTo("UNKNOWN");
        assertThat(fresh.state()).isEqualTo("COMPLETED");
        assertThat(replies).hasSize(1);
        assertThat(replies.getFirst().body()).as("本轮答案必须来自本轮").isEqualTo("answer:1");
    }

    // ------------------------------------------------- B3 对账回路

    /** B3：一次 UNKNOWN 之后，下一轮必须能恢复提交，而且不会把上一轮终态当本轮答案。 */
    @Test
    void anUnknownTurnIsReconciledBeforeTheNextSubmit() {
        FakeGateway gateway = new FakeGateway();
        FakeRepository repository = new FakeRepository(gateway);
        repository.conversations.put(CONV, conversation(null));
        AssistantConversationService service = service(repository, gateway);
        gateway.failNextSubmit = true;

        AssistantConversationService.SubmitReceipt first = service.submit(CONV, msg("req-a", "first"));
        assertThat(first.state()).isEqualTo("UNKNOWN");
        assertThat(first.reconcileRequired()).isTrue();
        assertThat(gateway.submits.get()).isEqualTo(1);

        // 上一轮迟到的终态排进同一个 live 会话的缓冲：它属于上一轮，绝不能被当成本轮答案
        gateway.bufferTerminal("live-1", "answer:STALE-FROM-PREVIOUS-TURN");

        AssistantConversationService.SubmitReceipt second = service.submit(CONV, msg("req-b", "second"));
        assertThat(second.state()).as("上一轮已在账本里证实结束，本轮必须能提交").isEqualTo("SUBMITTED");
        assertThat(gateway.submits.get()).as("本轮自己的一次提交").isEqualTo(2);

        awaitTerminal(repository, CONV, 3);
        AssistantConversationRepository.Message completed = repository.listMessages(CONV, 10, 0).stream()
            .filter(row -> "req-b".equals(row.requestId())).findFirst().orElseThrow();
        AssistantConversationRepository.Message reply = repository.listMessages(CONV, 10, 0).stream()
            .filter(row -> "ASSISTANT".equals(row.role())).findFirst().orElseThrow();

        assertThat(completed.state()).isEqualTo("COMPLETED");
        assertThat(reply.body()).as("不得消费上一轮遗留的终态").isEqualTo("answer:2");
    }

    /** B3：账本还不能证明上一轮结束时（状态没落账），不得 reconcile 放闸。 */
    @Test
    void reconcileIsNotAttemptedWhenTheLedgerCannotProveTheLastTurnIsOver() {
        FakeGateway gateway = new FakeGateway();
        FakeRepository repository = new FakeRepository(gateway);
        repository.conversations.put(CONV, conversation(null));
        AssistantConversationService service = service(repository, gateway);
        gateway.failNextSubmit = true;
        // 状态写入丢失：这一轮在账本里永远停在 SUBMITTED（没有任何「已结束」的证据）
        repository.freezeStates = true;

        AssistantConversationService.SubmitReceipt first = service.submit(CONV, msg("req-a", "first"));
        assertThat(first.state()).isEqualTo("UNKNOWN");
        assertThat(stateOf(repository, CONV)).isEqualTo("SUBMITTED");

        AssistantConversationService.SubmitReceipt second = service.submit(CONV, msg("req-b", "second"));

        assertThat(second.state()).as("没有证据就不得放闸").isEqualTo("UNKNOWN");
        assertThat(gateway.submits.get()).as("放闸后本不该有第二次提交").isEqualTo(1);
    }

    /** B3 对照组：上一轮确实还在跑时，只能拒绝（同一对话不得两轮并存）。 */
    @Test
    void aTurnThatIsStillRunningRejectsTheNextSubmit() throws Exception {
        FakeGateway gateway = new FakeGateway();
        FakeRepository repository = new FakeRepository(gateway);
        repository.conversations.put(CONV, conversation(null));
        AssistantConversationService service = service(repository, gateway);
        gateway.hang = true;

        assertThat(service.submit(CONV, msg("req-a", "first")).state()).isEqualTo("SUBMITTED");
        assertThatThrownBy(() -> service.submit(CONV, msg("req-b", "second")))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode().value()).isEqualTo(409));
        assertThat(gateway.submits.get()).isEqualTo(1);
        service.shutdown();
    }

    // ------------------------------------------------- B4 无句柄续聊

    /** B4：没有持久句柄、却有历史轮次时不得 session.create 冒充续聊。 */
    @Test
    void aConversationWithHistoryButNoHandleIsNotRestarted() {
        FakeGateway gateway = new FakeGateway();
        FakeRepository repository = new FakeRepository(gateway);
        repository.conversations.put(CONV, conversation(null));
        repository.seed(CONV, "req-old", "USER", "earlier question", "SUBMITTED", Instant.now().minusSeconds(60));

        AssistantConversationService service = service(repository, gateway);
        AssistantConversationService.SubmitReceipt receipt = service.submit(CONV, msg("req-new", "follow up"));

        assertThat(receipt.state()).isEqualTo("UNKNOWN");
        assertThat(receipt.reconcileRequired()).isTrue();
        assertThat(receipt.detail())
            .as("「无句柄却有历史」必须自成一类文案，不能被泛化成普通派发失败")
            .isEqualTo("该对话已有历史轮次却没有持久会话句柄，拒绝新建会话冒充续聊（需对账）");
        assertThat(gateway.creates.get()).as("不得新建会话冒充续聊").isZero();
        assertThat(gateway.submits.get()).isZero();
        assertThat(repository.userRows(CONV).getLast().failureDetail())
            .as("账本里也要留下同一类原因").isEqualTo(receipt.detail());
    }

    // ------------------------------------------------- N1 有界队列

    /** N1：回复队列必须有界；队列满时提交要得到明确的 UNKNOWN，而不是无限堆积。 */
    @Test
    void aFullReplyQueueTurnsTheSubmissionIntoAnExplicitUnknown() throws Exception {
        FakeGateway gateway = new FakeGateway();
        FakeRepository repository = new FakeRepository(gateway);
        repository.conversations.put(CONV, conversation(null));
        AssistantConversationService service = service(repository, gateway);

        var field = AssistantConversationService.class.getDeclaredField("replies");
        field.setAccessible(true);
        ThreadPoolExecutor executor = (ThreadPoolExecutor) field.get(service);

        assertThat(executor.getQueue().remainingCapacity())
            .as("回复队列必须是有界队列").isLessThanOrEqualTo(4096);

        CountDownLatch release = new CountDownLatch(1);
        Runnable blocker = () -> {
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        };
        int target = executor.getMaximumPoolSize() + Math.min(executor.getQueue().remainingCapacity(), 4096);
        int queued = 0;
        try {
            while (queued < target) {
                executor.execute(blocker);
                queued++;
            }
        } catch (RejectedExecutionException full) {
            // 队列已被填满
        }
        assertThat(executor.getQueue().remainingCapacity()).as("队列应当已被填满").isZero();

        AssistantConversationService.SubmitReceipt receipt = service.submit(CONV, msg("req-full", "overflow"));
        assertThat(receipt.state()).as("队列满时必须明确 UNKNOWN，不得静默排队").isEqualTo("UNKNOWN");
        assertThat(receipt.reconcileRequired()).isTrue();
        assertThat(repository.userRows(CONV)).hasSize(1);
        assertThat(stateOf(repository, CONV)).isEqualTo("UNKNOWN");

        release.countDown();
        service.shutdown();
    }

    // ------------------------------------------------- N2 关闭

    /** N2：关闭后不得留「已受理、无终态、也无标记」的行（包括还在队列里没跑的任务）。 */
    @Test
    void shutdownLeavesNoAcceptedButTerminalLessRow() {
        FakeGateway gateway = new FakeGateway();
        gateway.hang = true;
        FakeRepository repository = new FakeRepository(gateway);
        AssistantConversationService service = new AssistantConversationService(repository, new FakeAccess(),
            new HermesConversationService(gateway, MAPPER, 32, Duration.ofSeconds(1)), gateway, "/tmp",
            Duration.ofSeconds(5));

        for (int index = 0; index < 9; index++) {
            String conversationId = "conv-" + index;
            repository.conversations.put(conversationId, new AssistantConversationRepository.Conversation(
                conversationId, 1L, 1L, 7L, "create-" + index, null, null, Instant.now()));
            assertThat(service.submit(conversationId, msg("req-" + index, "text-" + index)).state())
                .isEqualTo("SUBMITTED");
        }
        assertThat(gateway.submits.get()).isEqualTo(9);

        service.shutdown();

        for (int index = 0; index < 9; index++) {
            String conversationId = "conv-" + index;
            assertThat(repository.userRows(conversationId)).hasSize(1);
            assertThat(stateOf(repository, conversationId))
                .as("关闭后不得留下无终态的 SUBMITTED 行：" + conversationId)
                .isIn("UNKNOWN", "FAILED", "COMPLETED");
        }
    }

    // ------------------------------------------------- N3 脱敏

    /** N3：上游原文（可能含 payload/凭据）绝不进账本与响应，只能用固定文案。 */
    @Test
    void upstreamPayloadNeverReachesTheLedgerOrTheResponse() {
        String canary = "CANARY-PAYLOAD-" + UUID.randomUUID();
        FakeGateway gateway = new FakeGateway();
        FakeRepository repository = new FakeRepository(gateway);
        repository.conversations.put(CONV, conversation(null));
        AssistantConversationService service = service(repository, gateway);

        gateway.failDrainAfterCalls = 1;
        gateway.drainFailure = new IllegalStateException(
            "{\"jsonrpc\":\"2.0\",\"error\":{\"message\":\"" + canary + "\",\"data\":{\"prompt\":\"secret\"}}}");

        assertThat(service.submit(CONV, msg("req-1", "hello")).state()).isEqualTo("SUBMITTED");
        waitForState(repository, "UNKNOWN");

        AssistantConversationRepository.Message row = repository.userRows(CONV).getFirst();
        assertThat(row.state()).isEqualTo("UNKNOWN");
        assertThat(row.failureDetail()).as("账本不得写入上游原文").doesNotContain(canary).isNotBlank();

        AssistantConversationService.MessagePage page = service.messages(CONV, null, null);
        assertThat(page.messages()).extracting(AssistantConversationService.MessageView::text)
            .allMatch(text -> !text.contains(canary));
    }

    private static void waitForState(FakeRepository repository, String state) {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            if (state.equals(repository.userRows(CONV).getFirst().state())) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        }
        throw new AssertionError("3s 内没有等到状态 " + state);
    }

    /** N3 补充：上游结构化拒绝只允许带方法名与顶层整数码。 */
    @Test
    void anUpstreamRejectionKeepsOnlyItsMethodAndTopLevelCode() {
        String canary = "CANARY-UPSTREAM-" + UUID.randomUUID();
        FakeGateway gateway = new FakeGateway();
        FakeRepository repository = new FakeRepository(gateway);
        repository.conversations.put(CONV, conversation(null));
        AssistantConversationService service = service(repository, gateway);

        gateway.failDrainAfterCalls = 1;
        gateway.drainFailure = new HermesGateway.UpstreamRpcError("prompt.submit", 4001, canary);

        assertThat(service.submit(CONV, msg("req-1", "hello")).state()).isEqualTo("SUBMITTED");
        waitForState(repository, "UNKNOWN");

        AssistantConversationRepository.Message row = repository.userRows(CONV).getFirst();
        assertThat(row.failureDetail()).contains("prompt.submit").contains("4001").doesNotContain(canary);
    }

    /** 回执字段：受理轮次的 upstreamTurnRef 只在传输真的回了 turn_id 时才非空。 */
    @Test
    void anAcceptedTurnCarriesOnlyWhatTheTransportActuallyReturned() {
        FakeGateway gateway = new FakeGateway();
        FakeRepository repository = new FakeRepository(gateway);
        repository.conversations.put(CONV, conversation(null));
        AssistantConversationService service = service(repository, gateway);

        AssistantConversationService.SubmitReceipt receipt = service.submit(CONV, msg("req-1", "hello"));

        assertThat(receipt.state()).isEqualTo("SUBMITTED");
        assertThat(receipt.acceptance()).isEqualTo("ACCEPTED_NOW");
        assertThat(receipt.upstreamTurnRef()).as("pin 不回 turn_id，不得伪造").isNull();
        assertThat(receipt.turnId()).isEqualTo(receipt.messageId());
        service.shutdown();
    }

    // ------------------------------------------------- 受理之后本地失败（不许漏占位）

    /**
     * 受理之后本地失败（受理账落库失败）：账本正确地落了 UNKNOWN，但会话服务里的「本轮占位」必须被放掉。
     *
     * <p>漏掉的后果不是「这一轮失败」，而是<b>此后每一轮都失败到进程重启</b>：占位只能由终态消费者或
     * 对账清除，而 {@code needsReconcile()} 在占位未清时恒为 false，自愈路径永远不触发。
     */
    @Test
    void aFailedAcceptanceWriteReleasesTheRuntimeSlotAndTheNextTurnRecovers() {
        Harness harness = harness(Duration.ofSeconds(5));
        harness.repository().failMarkAccepted = true;

        AssistantConversationService.SubmitReceipt first = harness.service().submit(CONV, msg("req-1", "第一问"));

        assertThat(first.state()).isEqualTo("UNKNOWN");
        assertThat(first.detail()).isEqualTo("Hermes 已可能的受理未能落账（本地写入失败），结果不可判定需对账");
        assertThat(harness.gateway().submits.get()).as("wire 确实受理过这一轮").isEqualTo(1);
        assertThat(harness.conversations().needsReconcile(CONV))
            .as("受理后本地失败必须明确要求对账，否则自愈路径永不可达").isTrue();

        harness.repository().failMarkAccepted = false;
        AssistantConversationService.SubmitReceipt second = harness.service().submit(CONV, msg("req-2", "第二问"));

        assertThat(second.state()).as("下一轮必须能自行恢复，而不是砖死到重启").isEqualTo("SUBMITTED");
        assertThat(harness.gateway().submits.get()).as("恢复轮自己的一次提交").isEqualTo(2);
        assertThat(harness.gateway().creates.get()).as("不得为恢复新建第二条会话").isEqualTo(1);
        awaitTerminal(harness.repository(), CONV, 3);
        assertThat(harness.conversations().needsReconcile(CONV)).isFalse();

        AssistantConversationService.SubmitReceipt third = harness.service().submit(CONV, msg("req-3", "第三问"));
        assertThat(third.state()).as("恢复之后不只是一轮能用").isEqualTo("SUBMITTED");
        harness.service().shutdown();
    }

    /**
     * 回复队列拒绝（N1 的有界队列）同样发生在受理之后：同一条漏占位路径，同一套要求。
     */
    @Test
    void aFullReplyQueueReleasesTheRuntimeSlotAndTheNextTurnRecovers() throws Exception {
        Harness harness = harness(Duration.ofSeconds(5));
        ThreadPoolExecutor pool = replyPool(harness.service());
        CountDownLatch release = new CountDownLatch(1);
        Runnable blocker = () -> {
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        };
        fillPool(pool, blocker);

        AssistantConversationService.SubmitReceipt first = harness.service().submit(CONV, msg("req-full", "溢出"));
        assertThat(first.state()).isEqualTo("UNKNOWN");
        assertThat(first.detail()).isEqualTo("回复消费者队列已满，结果未知需对账");
        assertThat(harness.gateway().submits.get()).as("受理已经在 wire 上发生过").isEqualTo(1);
        assertThat(harness.conversations().needsReconcile(CONV))
            .as("队列拒绝同样必须释放占位并要求对账").isTrue();

        release.countDown();
        awaitPoolDrained(pool);

        AssistantConversationService.SubmitReceipt second = harness.service().submit(CONV, msg("req-after", "队列恢复"));
        assertThat(second.state()).as("队列恢复后必须能继续").isEqualTo("SUBMITTED");
        assertThat(harness.gateway().submits.get()).isEqualTo(2);
        harness.service().shutdown();
    }

    private static void fillPool(ThreadPoolExecutor pool, Runnable blocker) {
        int target = pool.getMaximumPoolSize() + pool.getQueue().remainingCapacity();
        try {
            for (int index = 0; index < target; index++) {
                pool.execute(blocker);
            }
        } catch (RejectedExecutionException full) {
            // 队列已满
        }
        assertThat(pool.getQueue().remainingCapacity()).as("队列应当已被填满").isZero();
    }

    private static void awaitPoolDrained(ThreadPoolExecutor pool) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (pool.getQueue().isEmpty()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("10s 内回复队列没有排空");
    }

    // ------------------------------------------------- 陈旧阈值（不得误判在途回合）

    /**
     * 本 JVM 仍在等待终态的行：即使 {@code updated_at} 看起来很老，也不得被扫成 UNKNOWN。
     * 「老」可能只是因为它还在等一次慢回复，而不是进程已经死了。
     */
    @Test
    void aRowThisJvmIsStillWaitingOnIsNeverSweptToUnknown() {
        Harness harness = harness(Duration.ofSeconds(30));
        harness.gateway().hang = true;
        assertThat(harness.service().submit(CONV, msg("req-hang", "长时间等")).state()).isEqualTo("SUBMITTED");
        AssistantConversationRepository.Message row = harness.repository().userRows(CONV).getFirst();
        harness.repository().updatedAt.put(row.id(), Instant.now().minusSeconds(6 * 3600));

        AssistantConversationService.MessagePage page = harness.service().messages(CONV, null, null);

        assertThat(page.messages().getFirst().state())
            .as("仍在等终态的轮次不得被陈旧扫描判死").isEqualTo("SUBMITTED");
        assertThat(harness.repository().userRows(CONV).getFirst().state()).isEqualTo("SUBMITTED");
        harness.service().shutdown();
    }

    /**
     * 本 JVM 仍在派发（卡在一次慢 RPC 上）的行：同样不得被扫成 UNKNOWN——这一行连受理都还没落账，
     * 判死它就等于把一个正常回合直接变成需要人工对账的孤儿。
     */
    @Test
    void aRowThisJvmIsStillDispatchingIsNeverSweptToUnknown() throws Exception {
        Harness harness = harness(Duration.ofSeconds(5));
        CountDownLatch gate = new CountDownLatch(1);
        harness.gateway().submitGate = gate;
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        Thread dispatcher = new Thread(() -> {
            try {
                harness.service().submit(CONV, msg("req-dispatch", "派发中被卡住"));
            } catch (Throwable failure) {
                failures.add(failure);
            }
        }, "still-dispatching");
        dispatcher.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline && harness.repository().userRows(CONV).isEmpty()) {
            Thread.sleep(10);
        }
        assertThat(harness.repository().userRows(CONV)).hasSize(1);
        AssistantConversationRepository.Message row = harness.repository().userRows(CONV).getFirst();
        harness.repository().updatedAt.put(row.id(), Instant.now().minusSeconds(6 * 3600));

        harness.service().messages(CONV, null, null);

        assertThat(harness.repository().userRows(CONV).getFirst().state())
            .as("仍在派发的轮次不得被陈旧扫描判死").isEqualTo("SUBMITTED");
        gate.countDown();
        dispatcher.join(5000);
        assertThat(failures).isEmpty();
        harness.service().shutdown();
    }

    /**
     * 阈值按「实际最长链」推导：{@code session.resume + 空闲等待 + 重新 resume + prompt.submit + 终态等待}
     * 用出厂默认值算约 190s，旧的 {@code 2*replyTimeout+30s = 150s} 覆盖不了它。
     */
    @Test
    void theStaleBudgetCoversTheWholeAdoptChain() {
        Harness harness = harness(Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofSeconds(10));
        harness.repository().seed(CONV, "req-adopt-chain", "USER", "还在 adopt 链里", "SUBMITTED",
            Instant.now().minusSeconds(185));
        harness.repository().seed(CONV, "req-orphan", "USER", "真正的孤儿", "SUBMITTED",
            Instant.now().minusSeconds(3600));

        List<AssistantConversationService.MessageView> rows = harness.service().messages(CONV, null, null).messages();

        assertThat(rows).filteredOn(row -> "还在 adopt 链里".equals(row.text())).singleElement()
            .as("190s 的最长链之内不得被当成孤儿").extracting(AssistantConversationService.MessageView::state)
            .isEqualTo("SUBMITTED");
        assertThat(rows).filteredOn(row -> "真正的孤儿".equals(row.text())).singleElement()
            .as("越过整条链 + 宽限的 SUBMITTED 才是孤儿").extracting(AssistantConversationService.MessageView::state)
            .isEqualTo("UNKNOWN");
        harness.service().shutdown();
    }

    // ------------------------------------------------- 毫秒并列不得放过开着的轮次

    /**
     * 两行 {@code created_at} 完全相同：一行已结束（UNKNOWN），一行仍开着（SUBMITTED）。
     *
     * <p>按「最新一行」判定会挑到 UNKNOWN 那一行（{@code (created_at desc, id desc)} 在并列时看 id 文本），
     * 于是放闸、再打一次 wire——与仍开着的那一轮并行。必须改成「只要还有一行开着就不放闸」。
     */
    @Test
    void aMillisecondTieCannotLetAnotherOpenTurnThroughTheReconcileGate() {
        Harness harness = harness(Duration.ofSeconds(5));
        harness.gateway().failNextSubmit = true;
        assertThatThrownBy(() -> harness.conversations().submit(CONV, "/tmp", "预热失败，让闸门关上"))
            .isInstanceOf(IllegalStateException.class);
        assertThat(harness.conversations().needsReconcile(CONV)).isTrue();
        int submitsAfterWarmUp = harness.gateway().submits.get();
        assertThat(submitsAfterWarmUp).isEqualTo(1);

        Instant tie = Instant.now();
        harness.repository().seed(CONV, "req-open", "USER", "还开着", "SUBMITTED", tie, "aaa-open");
        // id 更大的一行已结束：(created_at desc, id desc) 会先挑到它，这正是并列的陷阱。
        harness.repository().seed(CONV, "req-closed", "USER", "已结束", "UNKNOWN", tie, "zzz-closed");

        AssistantConversationService.SubmitReceipt next = harness.service().submit(CONV, msg("req-next", "下一轮"));

        assertThat(next.state()).as("账本没能证实上一轮结束，不得放闸").isEqualTo("UNKNOWN");
        assertThat(harness.gateway().submits.get()).as("未对账前不得再打 wire").isEqualTo(submitsAfterWarmUp);
        harness.service().shutdown();
    }

    // ------------------------------------------------- 受理窗口里的致命错误（E3）
    // 受理窗口 = wire 已受理之后、交给回复消费者之前。窗口里任何出口都必须释放运行时占位；
    // 旧实现只逐路径调 abandon，Error（OOM/驱动致命错误）既不是 RuntimeException 也没有 finally，
    // 于是留下 inFlight != null 且 needsReconcile == false —— 闸门再也不会打开，整条对话砖死到重启。

    /**
     * E3：受理窗口内抛 {@link Error}。要求（1）Error 继续上抛、不被吞掉；（2）运行时占位被释放并显式
     * 要求对账（而不是变成一个「没有入口」的死状态）；（3）下一轮在账本还开着时 fail-closed，绝不并行；
     * （4）账本自愈（≤ staleAfter）之后必须能继续——即最坏是「有界阻塞」，不是「永久砖死」。
     */
    @Test
    void anErrorInsideTheAcceptanceWindowReleasesTheSlotInsteadOfBrickingTheConversation() {
        Harness harness = harness(Duration.ofSeconds(5));
        harness.repository().failMarkAcceptedWithError = true;

        assertThatThrownBy(() -> harness.service().submit(CONV, msg("req-1", "第一问")))
            .as("致命错误必须继续上抛，不得被静默吞掉")
            .isInstanceOf(AcceptanceWindowError.class);

        assertThat(harness.gateway().submits.get()).as("wire 确实受理过这一轮").isEqualTo(1);
        assertThat(harness.conversations().needsReconcile(CONV))
            .as("窗口内致命错误之后必须先明确「需对账」，否则自愈入口永远不可达（旧的永久砖死）")
            .isTrue();
        harness.repository().failMarkAcceptedWithError = false;

        // 同一时刻再提交：账本里那一行还开着，可能仍在跑 —— 只能 fail-closed，且不得再打 wire。
        AssistantConversationService.SubmitReceipt blocked = harness.service().submit(CONV, msg("req-2", "第二问"));
        assertThat(blocked.state()).isEqualTo("UNKNOWN");
        assertThat(blocked.detail()).isEqualTo("账本里仍有未终态的轮次（可能仍在运行），本轮拒绝并发提交，结果未知需对账");
        assertThat(harness.gateway().submits.get()).as("未证实上一轮结束前不得再打 wire").isEqualTo(1);

        // 模拟 staleAfter 到期（真实环境由陈旧扫描完成）：账本自愈后必须能继续。
        String erroredRowId = harness.repository().userRows(CONV).getFirst().id();
        harness.repository().updatedAt.put(erroredRowId, Instant.now().minusSeconds(3600));

        AssistantConversationService.SubmitReceipt recovered = harness.service().submit(CONV, msg("req-3", "第三问"));
        assertThat(recovered.state())
            .as("受理窗口内的致命错误只能是「≤ staleAfter 的有界阻塞」，不能砖死到进程重启")
            .isEqualTo("SUBMITTED");
        awaitTerminal(harness.repository(), CONV, 4);
        harness.service().shutdown();
    }

    // ------------------------------------------------- 受理窗口的最后一跳（E4：dispatchReply 抛 Error）
    // 受理窗口里还有一处「已经置位 activeTurns、但还没 handedOff」的极小窗口：把本轮交给回复消费者。
    // 这里抛出的是 Error（不是 RejectedExecutionException，也不是 RuntimeException），所以必须由 finally 收口；
    // 而 finally 里只 abandon 运行时占位是不够的 —— 本层的 activeTurns 同样是闸门。

    /**
     * E4：{@code replies.execute} 抛 {@link Error}。要求（1）Error 原样上抛、不被吞掉也不被换成别的异常；
     * （2）运行时占位被释放并要求对账；（3）<b>本层</b>{@code activeTurns} 也必须被释放——否则
     * {@code submit} 的外层 finally 看到 {@code containsValue} 仍为 true 就把这一轮留在 {@code liveTurns} 里，
     * 陈旧扫描永远跳过这一行，账本无法自愈，此后每次提交都在 {@code activeTurns.containsKey} 上 409
     * 抛 {@link ResponseStatusException}，砖死到进程重启（与占位漏放同一类，只是换了一层）；
     * （4）账本陈旧归一化之后必须能继续，且复用同一条会话（不得双开）。
     *
     * <p>「永久 409」这个形状与「占位没释放」不同：占位没释放时下一轮是 UNKNOWN/FAILED（闸门给文案），
     * 而这里 {@code submit} 直接抛 CONFLICT，连账本都碰不到——所以断言必须落在「下一轮不是抛异常，
     * 而是一条 UNKNOWN 回执」上，再落到「归一化后能继续」。
     */
    @Test
    void anErrorInsideTheDispatchReplyWindowReleasesBothGatesInsteadOfBrickingTheConversation() {
        ExplodingReplyPool pool = new ExplodingReplyPool();
        Harness harness = harness(Duration.ofSeconds(5), pool);
        pool.explode = true;

        assertThatThrownBy(() -> harness.service().submit(CONV, msg("req-1", "第一问")))
            .as("dispatchReply 里的致命错误必须原样上抛，不得被当成普通失败吞掉")
            .isInstanceOf(ReplyConsumerError.class);
        assertThat(harness.gateway().submits.get()).as("wire 确实受理过这一轮").isEqualTo(1);
        assertThat(harness.conversations().needsReconcile(CONV))
            .as("运行时槽位仍必须显式要求对账").isTrue();

        // （3）+（4 前半）：下一轮不得被永久 409 —— 账本还开着时只能 fail-closed 成 UNKNOWN。
        AssistantConversationService.SubmitReceipt blocked = harness.service().submit(CONV, msg("req-2", "第二问"));
        assertThat(blocked.state()).as("activeTurns 泄漏会让下一轮直接抛 409，而不是给出可对账的 UNKNOWN")
            .isEqualTo("UNKNOWN");
        assertThat(blocked.detail()).isEqualTo("账本里仍有未终态的轮次（可能仍在运行），本轮拒绝并发提交，结果未知需对账");
        assertThat(harness.gateway().submits.get()).as("未证实上一轮结束前不得再打 wire").isEqualTo(1);

        // （4 后半）：账本过了 staleAfter 之后必须能继续（真实环境由 sweepStaleSubmitted 完成归一化）。
        pool.explode = false;
        String erroredRowId = harness.repository().userRows(CONV).stream()
            .filter(row -> "req-1".equals(row.requestId())).findFirst().orElseThrow().id();
        harness.repository().updatedAt.put(erroredRowId, Instant.now().minusSeconds(3600));

        AssistantConversationService.SubmitReceipt recovered = harness.service().submit(CONV, msg("req-3", "第三问"));
        assertThat(recovered.state())
            .as("dispatchReply 里的致命错误只能是「≤ staleAfter 的有界阻塞」，不能砖死到进程重启")
            .isEqualTo("SUBMITTED");
        assertThat(harness.gateway().submits.get()).isEqualTo(2);
        assertThat(harness.gateway().creates.get()).as("恢复必须复用同一条会话，不得双开").isEqualTo(1);
        awaitTerminal(harness.repository(), CONV, 4);
        assertThat(harness.repository().userRows(CONV)).filteredOn(row -> "req-3".equals(row.requestId()))
            .singleElement().as("恢复轮自己必须能跑到终态").extracting(
                AssistantConversationRepository.Message::state).isEqualTo("COMPLETED");
        assertThat(harness.repository().userRows(CONV)).filteredOn(row -> "req-1".equals(row.requestId()))
            .singleElement().as("被放弃的那一轮只能是 UNKNOWN（不推断终态）").extracting(
                AssistantConversationRepository.Message::state).isEqualTo("UNKNOWN");
        harness.service().shutdown();
    }

    /**
     * 测试缝本身不得让关闭逻辑丢掉池的生命周期：注入的池必须仍然由 {@link AssistantConversationService#shutdown()}
     * 收掉，并且和内部池一样在关闭时把没终态的轮次落成 UNKNOWN。
     */
    @Test
    void theInjectedReplyPoolIsStillClosedByShutdown() {
        ExplodingReplyPool pool = new ExplodingReplyPool();
        Harness harness = harness(Duration.ofSeconds(5), pool);
        harness.gateway().hang = true;

        assertThat(harness.service().submit(CONV, msg("req-1", "卡在终态")).state()).isEqualTo("SUBMITTED");
        assertThat(pool.isShutdown()).isFalse();

        harness.service().shutdown();

        assertThat(pool.isShutdown()).as("测试缝不得把回复池的关闭责任丢给调用方").isTrue();
        assertThat(stateOf(harness.repository(), CONV))
            .as("关闭时无终态的轮次必须落成 UNKNOWN，而不是留 SUBMITTED").isEqualTo("UNKNOWN");
    }

    // ------------------------------------------------- 闸门权威在账本，不在内存
    // inFlight 只是句柄/并发/淘汰缓存。它一旦被意外残留（窗口内 fatal error、消费者线程死亡、
    // 未来新增的调用方），「以 inFlight 为准」的闸门就永远打不开；反过来内存干净而账本还开着时，
    // 「以内存为准」的闸门就会放过一条与仍在跑的轮次并行的提交。

    /**
     * 内存里残留一个没人消费的 {@code inFlight}（账本里一行都没有）：账本证实对话是干净的，因此下一轮
     * 必须能继续——这证明放行权威已经移到账本，缓存残留不再能挡住对话。
     *
     * <p>同时钉住安全面：残句柄那一轮遗留的终态必须先被提交前屏障丢掉，绝不能变成本轮答案。
     */
    @Test
    void aLeftoverInFlightHandleCannotBlockATurnTheLedgerProvesIsClear() {
        Harness harness = harness(Duration.ofSeconds(5));
        // 直接在会话服务上留下一轮「受理了但没人消费」的占位：账本里没有任何行。
        harness.conversations().submit(CONV, "/tmp", "残留占位");

        AssistantConversationService.SubmitReceipt receipt = harness.service().submit(CONV, msg("req-1", "账本干净的下一轮"));

        assertThat(receipt.state())
            .as("账本没有任何开着的回合：闸门必须从账本打开，而不是被内存里的残句柄挡住")
            .isEqualTo("SUBMITTED");
        awaitTerminal(harness.repository(), CONV, 2);

        List<AssistantConversationRepository.Message> rows = harness.repository().listMessages(CONV, 10, 0);
        assertThat(rows).filteredOn(row -> "req-1".equals(row.requestId())).singleElement()
            .extracting(AssistantConversationRepository.Message::state).isEqualTo("COMPLETED");
        assertThat(rows).filteredOn(row -> "ASSISTANT".equals(row.role())).singleElement()
            .extracting(AssistantConversationRepository.Message::body)
            .as("不得把残句柄那一轮遗留的终态当成本轮答案").isEqualTo("answer:2");
        assertThat(harness.gateway().submits.get()).isEqualTo(2);
        assertThat(harness.gateway().creates.get()).as("复用同一条 live 会话，不为恢复新建").isEqualTo(1);
        harness.service().shutdown();
    }

    /**
     * 反向：账本里还有一行停在 {@code SUBMITTED}（上一实例崩溃遗留、尚未越过 staleAfter），
     * 而运行时看起来「干净」（没有 inFlight、没有 needsReconcile）。此时提交必须被拒绝——权威是账本。
     *
     * <p>旧实现只看 {@code needsReconcile()}，于是会直接 adopt 并再打一次 wire，与那条可能仍在跑的
     * 轮次并行。账本一旦不再有开着的行（真实环境由陈旧扫描完成），下一轮必须能继续。
     */
    @Test
    void anOpenTurnInTheLedgerRejectsTheNextSubmitEvenThoughTheRuntimeLooksClean() {
        Harness harness = harness(Duration.ofSeconds(5));
        harness.repository().conversations.put(CONV, conversation("stored-0"));
        harness.repository().seed(CONV, "req-open", "USER", "上一轮还开着", "SUBMITTED", Instant.now());

        AssistantConversationService.SubmitReceipt refused = harness.service().submit(CONV, msg("req-1", "下一轮"));

        assertThat(refused.state()).isEqualTo("UNKNOWN");
        assertThat(refused.detail()).as("原因必须是「账本还开着」，不能被泛化成普通派发失败")
            .isEqualTo("账本里仍有未终态的轮次（可能仍在运行），本轮拒绝并发提交，结果未知需对账");
        assertThat(harness.gateway().submits.get()).as("账本说还开着，就不得再打一次 wire").isZero();
        assertThat(harness.gateway().creates.get()).isZero();

        String openRowId = harness.repository().userRows(CONV).stream()
            .filter(row -> "req-open".equals(row.requestId())).findFirst().orElseThrow().id();
        harness.repository().updatedAt.put(openRowId, Instant.now().minusSeconds(3600));

        AssistantConversationService.SubmitReceipt next = harness.service().submit(CONV, msg("req-2", "账本干净后"));
        assertThat(next.state()).as("账本证据变了，闸门就要跟着开").isEqualTo("SUBMITTED");
        awaitTerminal(harness.repository(), CONV, 4);
        harness.service().shutdown();
    }

    /**
     * 受理窗口内的致命错误。刻意用 {@link Error}（而不是 RuntimeException）：这正是旧实现漏掉的出口——
     * 逐路径清理只覆盖 {@code catch (RuntimeException)} 的两条路径，{@code Error} 会直接把占位留在绑定上。
     *
     * <p>用自定义子类而不是真的 {@link OutOfMemoryError}：后者会被 surefire 分叉当成致命 OOM 处理，
     * 让「一次注入」变成「整个套件夭折」，无法定位到具体断言。
     */
    static final class AcceptanceWindowError extends Error {
        AcceptanceWindowError(String message) {
            super(message);
        }
    }

    /**
     * 受理窗口最后一跳（{@code replies.execute}）里的致命错误。同样是自定义子类：真的 {@link OutOfMemoryError}
     * 会让 surefire 判定分叉进程致命失败，整份报告失去定位能力。
     */
    static final class ReplyConsumerError extends Error {
        ReplyConsumerError(String message) {
            super(message);
        }
    }

    /**
     * 只在 {@code execute} 这一跳上可爆炸的回复池：生产池的拒绝只会给 {@link RejectedExecutionException}
     * （被 {@code dispatchReply} 显式处理），构造不出「受理窗口内的致命 {@link Error}」这个真实形状。
     *
     * <p>{@code explode} 关闭时行为与普通池一致（同一个有界队列 + AbortPolicy），因此第一次爆炸之后
     * 还能用同一个池验证「恢复轮能继续」。
     */
    static final class ExplodingReplyPool extends ThreadPoolExecutor {

        volatile boolean explode;

        ExplodingReplyPool() {
            super(1, 1, 0L, TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(4),
                runnable -> {
                    Thread thread = new Thread(runnable, "exploding-reply-consumer");
                    thread.setDaemon(true);
                    return thread;
                }, new AbortPolicy());
        }

        @Override
        public void execute(Runnable command) {
            if (explode) {
                throw new ReplyConsumerError("simulated fatal error inside dispatchReply");
            }
            super.execute(command);
        }
    }

}
