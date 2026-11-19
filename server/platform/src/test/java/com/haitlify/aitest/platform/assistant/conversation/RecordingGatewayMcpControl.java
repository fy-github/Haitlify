package com.haitlify.aitest.platform.assistant.conversation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 假的 {@link GatewayMcpControl}：只记录，不接触任何真实网关。
 *
 * <p>它刻意实现<b>控制面</b>（写条目 + reload）而不是 {@link GatewayMcpConfigurator}：
 * 装配只接受 {@link GatewayMcpLeaseConfigurator}，所以用例必须让真实的租约配置器跑在假控制面上——
 * 这样「先写、后 reload」的顺序本身也在被测范围内，而不是被一个万能假实现掩盖掉。
 *
 * <p>记录四件事，正好对应要钉住的性质：
 * <ul>
 *   <li>{@link #writes()} —— 每次写入收到的 URL（{@code null} = 清除条目），据此断言「一次不多、
 *       一次不少」「非持有者一次都没写」「终态收尾真的清了一次」；</li>
 *   <li>{@link #publishedUrls()} —— 其中真正写出去的 URL（清除不计入），用来断言「这一轮发布的到底是
 *       哪一条租约」；</li>
 *   <li>{@link #currentEntry()} —— 网关条目<b>当前</b>的内容；{@code null} 同时是初始值，所以断言
 *       「被清除」时要配合 {@link #writeCount()}；</li>
 *   <li>{@link #reloads()} —— reload 次数，用来断言「写失败就不 reload」；</li>
 *   <li>{@link #failOnWrite(boolean)} / {@link #failWithErrorOnWrite(boolean)} —— 让写入抛
 *       {@link RuntimeException} / {@link Error}，分别验证「配置失败只影响本轮 MCP 可用性」与
 *       「非 {@code RuntimeException} 的出口也必须释放网关级许可」。</li>
 * </ul>
 *
 * <p>{@link #onNextWrite(Runnable)} 是并发用例的观察点：在<b>这一次</b>写入完成、reload 之前执行，
 * 用来把「A 已配置、尚未派发」这个真实窗口固定下来。{@link #onNextClear(Runnable)} 是<b>终态清条目</b>
 * 的观察点：只在写入值为 {@code null}（清除）时触发一次，用来断言「清条目发生时许可仍在本轮手里」。
 * 所有方法线程安全（写入发生在请求线程/回复线程上，采样可能发生在假 Hermes 的线程上）。
 */
final class RecordingGatewayMcpControl implements GatewayMcpControl {

    /** 让 {@link #writeServerEntry(String)} 抛出的致命错误（不是 RuntimeException）。 */
    static final class WriteError extends Error {
        private static final long serialVersionUID = 1L;

        WriteError(String message) {
            super(message);
        }
    }

    private final List<String> writes = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger reloadCount = new AtomicInteger();
    private volatile String currentEntry;
    private volatile boolean failOnWrite;
    private volatile boolean failWithError;
    /** 只让<b>清除</b>（{@code null} 写入，即终态 {@code apply(null)}）抛 {@link WriteError}。 */
    private volatile boolean failWithErrorOnClear;
    private volatile Runnable nextWriteHook;
    private volatile Runnable nextClearHook;

    @Override
    public void writeServerEntry(String leaseEndpoint) {
        // 先记录「尝试过」，再抛错：写失败时网关条目保持不变（绝不能把旧 URL 当成新配置）。
        writes.add(leaseEndpoint);
        if (leaseEndpoint == null && failWithErrorOnClear) {
            // 终态清除的致命出口：与终态「清条目成功」区分开，用来钉住「Error 也必须释放许可」。
            throw new WriteError("gateway MCP control refused the terminal clear with an Error");
        }
        if (failWithError) {
            throw new WriteError("gateway MCP control failed with an Error");
        }
        if (failOnWrite) {
            throw new IllegalStateException("gateway MCP configuration unavailable");
        }
        currentEntry = leaseEndpoint;
        if (leaseEndpoint == null) {
            Runnable clearHook = nextClearHook;
            if (clearHook != null) {
                nextClearHook = null;
                clearHook.run();
            }
        }
        Runnable hook = nextWriteHook;
        if (hook != null) {
            nextWriteHook = null;
            hook.run();
        }
    }

    @Override
    public void reload() {
        reloadCount.incrementAndGet();
    }

    void reset() {
        writes.clear();
        reloadCount.set(0);
        currentEntry = null;
        failOnWrite = false;
        failWithError = false;
        failWithErrorOnClear = false;
        nextWriteHook = null;
        nextClearHook = null;
    }

    void failOnWrite(boolean fail) {
        this.failOnWrite = fail;
    }

    void failWithErrorOnWrite(boolean fail) {
        this.failWithError = fail;
    }

    /** 只让终态清除（{@code null} 写入）抛 {@link WriteError}：配置照常成功，死在收尾那一次写入上。 */
    void failWithErrorOnClear(boolean fail) {
        this.failWithErrorOnClear = fail;
    }

    /** 只对下一次写入生效：写入完成之后、reload 之前执行（并发用例用来固定窗口）。 */
    void onNextWrite(Runnable hook) {
        this.nextWriteHook = hook;
    }

    /** 只对下一次<b>清除</b>（{@code null} 写入）生效：条目已被置空之后、reload 之前执行。 */
    void onNextClear(Runnable hook) {
        this.nextClearHook = hook;
    }

    /** 每一次写入收到的 URL；{@code null} 元素表示「本轮无端点 → 清除条目」。 */
    List<String> writes() {
        synchronized (writes) {
            return new ArrayList<>(writes);
        }
    }

    /** 只含真实写出去的 URL（清除条目不计入），见 {@link #writes()}。 */
    List<String> publishedUrls() {
        synchronized (writes) {
            List<String> published = new ArrayList<>();
            for (String written : writes) {
                if (written != null) {
                    published.add(written);
                }
            }
            return published;
        }
    }

    int writeCount() {
        return writes.size();
    }

    int reloads() {
        return reloadCount.get();
    }

    /** 网关条目当前值；{@code null} = 没有条目（从未写过或已清除，配合 {@link #writeCount()} 区分）。 */
    String currentEntry() {
        return currentEntry;
    }
}
