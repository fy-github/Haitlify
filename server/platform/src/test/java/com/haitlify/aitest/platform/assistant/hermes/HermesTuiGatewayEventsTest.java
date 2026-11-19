package com.haitlify.aitest.platform.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * stdio 传输的事件消费（{@link HermesTuiGateway#drainEvents}）：真实 node 子进程、换行分隔帧。
 *
 * <p>为什么这条链必须有确定性用例：模型终态在 stdio 上是<b>通知帧</b>（没有 id），而会话类 RPC 只按 id
 * 回包。实现一旦把通知当噪声丢掉，{@link #terminalEventIsBufferedAndDrainedExactlyOnce()} 就不会红——
 * 它表现为「真实 E2E 每轮都 UNKNOWN」，而确定性套件全绿。所以这里用假 gateway 把三条纪律钉住：
 * <ol>
 *   <li><b>终态必须被缓冲并按到达顺序取出一次</b>（空列表只表示「此刻没有」，不表示完成）；</li>
 *   <li><b>有界缓冲只丢进度帧</b>：300 条 delta 之后终态仍在，条数不超过上限；</li>
 *   <li><b>事件流丢失必须显式报错</b>：终态取走 + 进程退出之后，空 drain 抛 {@link IllegalStateException}，
 *       绝不冒充「这一轮还在跑」。</li>
 * </ol>
 */
class HermesTuiGatewayEventsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SESSION = "stored-abc-123";

    private HermesTuiGateway gateway;
    /** 观测到的单次 drain 最大条数（{@code drainEvents} 是破坏性的，跨批次累积不能代表缓冲上界）。 */
    private int maxBatchSize;

    @AfterEach
    void stop() {
        if (gateway != null) {
            gateway.close();
            gateway = null;
        }
    }

    @Test
    void terminalEventIsBufferedAndDrainedExactlyOnce() throws Exception {
        gateway = stdio("delta+complete");
        assertThat(gateway.drainEvents(SESSION))
            .as("提交之前没有事件：空列表 ≠ 完成")
            .isEmpty();

        gateway.submitPrompt(SESSION, "请生成一个用例");
        var events = awaitEvents(2);

        assertThat(events).hasSize(2);
        assertThat(events.get(0).path("params").path("type").asString()).isEqualTo("message.delta");
        assertThat(events.get(1).path("params").path("type").asString())
            .as("终态必须是最后一条，且带 payload.status=complete")
            .isEqualTo("message.complete");
        assertThat(events.get(1).path("params").path("payload").path("status").asString()).isEqualTo("complete");
        assertThat(events.get(1).path("params").path("payload").path("text").asString()).isEqualTo("完成");
        assertThat(gateway.drainEvents(SESSION)).as("取走即清空：同一条终态不得被消费两次").isEmpty();
    }

    @Test
    void progressBacklogIsBoundedButNeverEvictsTheTerminalEvent() throws Exception {
        gateway = stdio("many+complete");
        gateway.submitPrompt(SESSION, "请生成一个用例");

        var events = awaitEvents(1);
        var last = events.get(events.size() - 1);
        assertThat(last.path("params").path("type").asString())
            .as("压力下被淘汰的只能是进度帧：终态必须在")
            .isEqualTo("message.complete");
        assertThat(maxBatchSize)
            .as("缓冲有界：单次 drain 取走的条数不超过上限")
            .isLessThanOrEqualTo(HermesTuiGateway.MAX_EVENTS);
        assertThat(events.size())
            .as("确实发生过淘汰（假网关推了 301 条，累积拿到的不可能是全部）")
            .isLessThan(301);
        assertThat(events.size()).as("至少还要留下进度帧，否则这条用例没覆盖到压力路径").isGreaterThan(1);
    }

    @Test
    void losingTheEventStreamFailsLoudlyInsteadOfPretendingTheTurnIsStillRunning() throws Exception {
        gateway = stdio("complete-then-exit");
        gateway.submitPrompt(SESSION, "请生成一个用例");

        var events = awaitEvents(1);
        assertThat(events.get(events.size() - 1).path("params").path("type").asString())
            .isEqualTo("message.complete");

        // 进程退出后：缓冲里的终态已经取走，再 drain 必须是「事件流已丢」，而不是空列表。
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (gateway.drainEvents(SESSION).isEmpty()) {
                    Thread.sleep(50);
                    continue;
                }
            } catch (IllegalStateException lost) {
                return;
            }
        }
        throw new AssertionError("子进程退出之后 drainEvents 仍然返回空列表：丢终态被伪装成「还没跑完」");
    }

    // ------------------------------------------------------------------ helpers

    private HermesTuiGateway stdio(String eventsMode) throws Exception {
        Path script = Path.of(HermesTuiGatewayEventsTest.class
            .getResource("/assistant/fake-tui-gateway.mjs").toURI());
        return new HermesTuiGateway("node " + script + " --events=" + eventsMode,
            System.getProperty("java.io.tmpdir"), 10, MAPPER);
    }

    /**
     * 事件是子进程异步推来的，而且 {@code drainEvents} 是<b>破坏性</b>的：一次 drain 可能只拿到 delta，
     * 下一次才拿到终态。所以这里按批次累积，直到「累积条数 ≥ atLeast 且其中已有终态」为止
     * （只看单次返回值会把已经取走的 delta 当成没来，然后在超时里红掉）。
     */
    private java.util.List<JsonNode> awaitEvents(int atLeast) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        java.util.List<JsonNode> collected = new java.util.ArrayList<>();
        maxBatchSize = 0;
        while (System.nanoTime() < deadline) {
            var batch = gateway.drainEvents(SESSION);
            maxBatchSize = Math.max(maxBatchSize, batch.size());
            collected.addAll(batch);
            boolean terminal = collected.stream().anyMatch(envelope ->
                "message.complete".equals(envelope.path("params").path("type").asString("")));
            if (collected.size() >= atLeast && terminal) {
                return collected;
            }
            Thread.sleep(25);
        }
        return collected;
    }

    @Test
    void aBlankSessionIdIsRejectedRatherThanMatchingEverything() throws Exception {
        gateway = stdio("none");
        assertThatThrownBy(() -> gateway.drainEvents(" "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 标记必须<b>按进程</b>：子进程死掉后 {@code drainEvents} 抛错是正确行为，但重新起一条子进程之后，
     * 它的空事件流必须重新算「健康」——否则一次崩溃会把后续（真正健康的）每一轮都变成「终态丢了」。
     */
    @Test
    void aFreshChildProcessStartsWithAHealthyEventStream() throws Exception {
        gateway = stdio("complete-then-exit");
        gateway.submitPrompt(SESSION, "请生成一个用例");
        assertThat(awaitEvents(1)).isNotEmpty();
        awaitEventStreamLost();

        assertThat(gateway.available()).as("子进程退出后必须能重新拉起一条").isTrue();
        assertThat(gateway.drainEvents(SESSION))
            .as("新子进程 = 新事件流：空 drain 不得沿用上一条流的「已丢」标记")
            .isEmpty();

        gateway.submitPrompt(SESSION, "第二轮");
        assertThat(awaitEvents(1))
            .as("新进程的事件必须能被正常消费")
            .isNotEmpty();
    }

    /** 等「事件流已丢」出现（子进程退出是异步的：EOF 由读线程发现）。 */
    private void awaitEventStreamLost() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (gateway.drainEvents(SESSION).isEmpty()) {
                    Thread.sleep(50);
                    continue;
                }
            } catch (IllegalStateException lost) {
                return;
            }
        }
        throw new AssertionError("子进程退出之后 drainEvents 仍然是空列表：丢终态被伪装成「还没跑完」");
    }
}
