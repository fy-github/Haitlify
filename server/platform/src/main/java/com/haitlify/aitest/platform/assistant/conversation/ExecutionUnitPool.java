package com.haitlify.aitest.platform.assistant.conversation;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.ObjectMapper;

/**
 * <b>有界的执行单元池</b>：键 = {@link ExecutionUnitKey}，值 = 一个独立网关子进程。
 *
 * <p>四条硬性质，缺一条就不是隔离：
 * <ol>
 *   <li><b>按 key 复用</b>：同一 {@code (tenant, project, actor)} 的连续轮次复用同一个单元（同一份配置、
 *       同一把许可、同一张会话绑定表）。不复用会让每一轮都新建子进程，既慢又让「同键第二轮拿到自己的
 *       条目」变成偶然；</li>
 *   <li><b>有界容量</b>：在册单元数不超过 {@link ExecutionUnitSettings#capacity()}。满了就
 *       {@link ExecutionUnitUnavailableException.Reason#CAPACITY_EXHAUSTED} 显式失败——<b>不</b>驱逐
 *       在跑的单元（那会杀掉别人的回合），<b>不</b>共享既有单元（那是能力错配），也不排队；</li>
 *   <li><b>空闲回收</b>：超过 {@link ExecutionUnitSettings#idleTtl()} 未被使用、<b>且没有任何在飞租约</b>的
 *       单元由回收线程关闭：子进程终止、目录删除、许可撤销。有一轮在飞（{@link UnitLease} 计数 &gt; 0）
 *       的单元一律跳过——回收一个正在等待终态的单元，等于把那一轮的会话连同终态一起扔掉。这个判据
 *       <b>不依赖 MCP 许可</b>：{@code configure-gateway=false}（默认）时许可根本不会被取，用许可当标记
 *       会让默认配置下的在飞轮次被确定性回收；</li>
 *   <li><b>关闭即确定性回收</b>：{@link #close()} 停止回收线程、<b>有界等待在飞租约归还</b>、再逐个关闭单元
 *       （同 TTL 回收的清理动作），之后再 {@link #acquire(ExecutionUnitKey)} 一律 {@code CLOSED} 失败。
 *       子进程、临时目录、配置器锁与管道都由单元自己的 {@code close()} 收干净。</li>
 * </ol>
 *
 * <p><b>为什么回收在锁外做</b>：关闭一个单元要等子进程退出（有界），把它放在池锁里会让所有其它键的
 * 解析阻塞在一次回收后面。因此回收先在锁内「摘牌」，再在锁外关闭；摘牌之后同一键的新请求会建一个新单元，
 * 与正在被杀的旧单元并行、互不影响。
 *
 * <p><b>解析本身是串行的</b>：{@link #acquire(ExecutionUnitKey)} 在池锁内完成「查表 + 必要时建单元」
 * （复制两个小文件 + fork 一个子进程）。这是刻意的取舍——容量检查与建单元必须在同一个临界区里，否则
 * 「刚好满了」会被两个线程同时判成没满。真正需要并行的部分（武装 / 配置 / reload / 派发）都在锁外，
 * 每个单元各有一把许可与控制面锁。
 */
final class ExecutionUnitPool implements GatewayUnits, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ExecutionUnitPool.class);

    /**
     * {@link #close()} 等待在飞租约归还的总预算。
     *
     * <p>正常关闭顺序（服务先 shutdown、再关池）下这个预算一次都用不到；它只覆盖「还有人持着租约」的
     * 异常关闭，让那一轮有机会把终态写完。到期后强制回收并记 WARN——子进程比进程池活得更久是泄漏，
     * 而那一轮的账本已经落 UNKNOWN，不会出现假成功。
     */
    static final Duration CLOSE_LEASE_DRAIN_GRACE = Duration.ofMillis(2000);

    private final ExecutionUnitSettings settings;
    private final ObjectMapper mapper;
    private final Object lock = new Object();
    private final LinkedHashMap<ExecutionUnitKey, ExecutionUnit> units = new LinkedHashMap<>();
    private final ScheduledExecutorService reaper;
    private volatile boolean closed;

    /**
     * @throws IllegalStateException 基准 profile 不可用（启动即失败，不等到第一个用户提交）
     */
    ExecutionUnitPool(ExecutionUnitSettings settings, ObjectMapper mapper) {
        if (settings == null) {
            throw new IllegalArgumentException("执行单元参数必填");
        }
        if (mapper == null) {
            throw new IllegalArgumentException("ObjectMapper 必填");
        }
        // 启动即证明「第一个用户来得时候能建单元」：缺文件在上下文启动时就失败，而不是在受理链路里
        // 变成一个「本轮没有 MCP 端点」。
        try {
            settings.requireBaseProfile();
            Files.createDirectories(settings.unitsRoot());
        } catch (ExecutionUnitUnavailableException unavailable) {
            throw new IllegalStateException(unavailable.getMessage(), unavailable);
        } catch (IOException failure) {
            throw new IllegalStateException("无法创建执行单元目录根（" + failure.getClass().getSimpleName() + "）",
                failure);
        }
        this.settings = settings;
        this.mapper = mapper;
        this.reaper = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "execution-unit-reaper");
            thread.setDaemon(true);
            return thread;
        });
        long interval = Math.max(1L, settings.reapInterval().toMillis());
        // 回收线程本身不参与任何判定：它只调用 reapIdle，「多久没被使用」与「许可是否被持有」都在那里判。
        reaper.scheduleWithFixedDelay(this::reapQuietly, interval, interval,
            TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean available() {
        return !closed;
    }

    @Override
    public TurnUnit acquire(ExecutionUnitKey key) {
        if (key == null) {
            throw new IllegalArgumentException("执行单元键必填");
        }
        synchronized (lock) {
            if (closed) {
                throw new ExecutionUnitUnavailableException(ExecutionUnitUnavailableException.Reason.CLOSED,
                    "执行单元池已关闭（EXECUTION_UNIT_POOL_CLOSED）：不再接受新的轮次");
            }
            ExecutionUnit unit = units.get(key);
            if (unit != null && unit.isClosed()) {
                // 被回收/关闭但仍在表里（理论上不会发生，回收先摘牌）：当作没有，重新建。
                units.remove(key);
                unit = null;
            }
            if (unit == null) {
                if (units.size() >= settings.capacity()) {
                    // 不驱逐、不共享、不排队：容量是硬上限，超出的键必须显式失败。
                    throw new ExecutionUnitUnavailableException(
                        ExecutionUnitUnavailableException.Reason.CAPACITY_EXHAUSTED,
                        "执行单元池容量耗尽（CAPACITY_EXHAUSTED：" + settings.capacity()
                            + "）：不共享既有单元、不驱逐在跑的单元");
                }
                unit = ExecutionUnit.create(key, settings, mapper);
                units.put(key, unit);
            }
            // 在飞租约必须在<b>返回之前</b>生效：之后的「取许可 → 武装 → 配置 → 派发」全程都有标记，
            // 回收线程既不看时间戳也不看许可，只看这个计数（见 reapIdle）。
            UnitLease lease = unit.tryLease();
            if (lease == null) {
                // 只可能是「刚被判为非关闭、随即被关闭」的竞态（关闭只发生在摘牌之后）：本轮按池已关闭
                // 失败，绝不在一具死掉的子进程上排一轮。
                units.remove(key, unit);
                throw new ExecutionUnitUnavailableException(ExecutionUnitUnavailableException.Reason.CLOSED,
                    "执行单元已被关闭（EXECUTION_UNIT_POOL_CLOSED）：不再接受新的轮次");
            }
            unit.touch();
            return unit.turnUnit(lease);
        }
    }

    @Override
    public boolean holdsPermit(String messageId) {
        for (ExecutionUnit unit : snapshot()) {
            if (unit.permit().heldBy(messageId)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int liveUnits() {
        synchronized (lock) {
            return units.size();
        }
    }

    @Override
    public List<ExecutionUnitKey> keys() {
        synchronized (lock) {
            return List.copyOf(units.keySet());
        }
    }

    /** 在册单元（只读观察；测试断言「同键复用同一个单元」用它）。 */
    Optional<ExecutionUnit> unitFor(ExecutionUnitKey key) {
        synchronized (lock) {
            return Optional.ofNullable(units.get(key));
        }
    }

    /**
     * 回收所有「空闲超过 TTL <b>且没有任何在飞租约</b>」的单元。
     *
     * <p>先在锁内摘牌、再在锁外关闭：关闭要等子进程退出（有界但可能几十毫秒），把它放在锁里会让所有
     * 其它键的解析都排在一次回收后面。摘牌之后同一键的新请求会建一个新单元，与正在被杀的旧单元并行。
     *
     * <p><b>判据只有两个</b>：在飞租约计数与 {@code lastUsedAt}。许可<b>不是</b>判据（它只作为第二道
     * 佐证保留，见下）——许可在 {@code configure-gateway=false} 时一次都不会被取，把「有一轮在跑」
     * 寄托在它上面，默认配置下就是一个可被确定性复现的回收事故。
     *
     * @return 被回收的键（顺序不承诺）
     */
    List<ExecutionUnitKey> reapIdle(Instant now) {
        List<ExecutionUnit> victims = new ArrayList<>();
        List<ExecutionUnitKey> reclaimed = new ArrayList<>();
        long cutoff = now.toEpochMilli() - settings.idleTtl().toMillis();
        synchronized (lock) {
            if (closed) {
                return List.of();
            }
            var iterator = units.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                ExecutionUnit unit = entry.getValue();
                // 有一轮在飞（持有单元租约）就绝不回收：那会把它的会话连同终态一起扔掉。
                // 这个判据与 MCP 许可无关，因此 configure-gateway=false 时同样有效；
                // 许可被判为「仍持有」时也跳过（第二道：许可持有者必然也持有租约，这里只是不信任它）。
                if (unit.inFlightLeases() > 0 || unit.permit().held() || unit.lastUsedAt() > cutoff) {
                    continue;
                }
                iterator.remove();
                victims.add(unit);
                reclaimed.add(entry.getKey());
            }
        }
        for (ExecutionUnit victim : victims) {
            closeQuietly(victim);
        }
        return reclaimed;
    }

    /**
     * 关闭池：先拒绝新轮次、停回收线程，<b>再有界等待在飞租约归还</b>，最后逐个关闭单元。
     *
     * <p><b>在飞租约的策略是「等，不静默拆」</b>：
     * <ol>
     *   <li>正常关闭顺序里根本没有等待：{@code AssistantConversationService.shutdown()} 先把在途轮次落成
     *       {@code UNKNOWN} 并归还租约（服务 Bean 依赖池 Bean，Spring 先销毁依赖方），因此到这里租约通常
     *       已经是 0；</li>
     *   <li>仍有租约时最多等 {@link #CLOSE_LEASE_DRAIN_GRACE}：持租约的那一轮可能正在写终态，一个
     *       「先杀进程再让它写库」的关闭会让本就已受理的轮次变成无谓的 {@code UNKNOWN}；</li>
     *   <li>宽限到期才强制关闭，并留一条 WARN（含单元键与剩余租约数）：进程池已经关闭而子进程继续活着
     *       是不可接受的（它对宿主而言就是泄漏），而这一轮的账本行已经落 {@code UNKNOWN}（需对账），
     *       不会出现「看起来成功了」的假象。</li>
     * </ol>
     */
    @Override
    public void close() {
        List<ExecutionUnit> victims;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            victims = new java.util.ArrayList<>(units.values());
            units.clear();
        }
        reaper.shutdownNow();
        drainLeases(victims);
        // 逆序关闭：与创建顺序相反，读日志时能对上「最后建的先收」。
        for (int index = victims.size() - 1; index >= 0; index--) {
            closeQuietly(victims.get(index));
        }
    }

    /** 有界等待在飞租约归零（共享一个总预算，避免 N 个单元各等一次宽限）。 */
    private void drainLeases(List<ExecutionUnit> victims) {
        long deadline = System.nanoTime() + CLOSE_LEASE_DRAIN_GRACE.toNanos();
        for (ExecutionUnit unit : victims) {
            int inFlight = unit.inFlightLeases();
            if (inFlight == 0) {
                continue;
            }
            long remaining = deadline - System.nanoTime();
            boolean drained = unit.awaitLeaseDrain(java.time.Duration.ofNanos(Math.max(0L, remaining)));
            if (!drained) {
                log.warn("执行单元关闭时仍有在飞租约（{}，leases={}）：宽限 {}ms 已到，强制回收；"
                        + "该轮账本已由服务层落 UNKNOWN，需对账", unit.key(), unit.inFlightLeases(),
                    CLOSE_LEASE_DRAIN_GRACE.toMillis());
            }
        }
    }

    private void reapQuietly() {
        try {
            reapIdle(Instant.now());
        } catch (RuntimeException failure) {
            // 回收线程不得因为一次异常而静默死掉（后续空闲单元就再也回收不了）。
            log
                .warn("执行单元空闲回收失败（type={}）：下一拍继续", failure.getClass().getSimpleName());
        }
    }

    private static void closeQuietly(ExecutionUnit unit) {
        try {
            unit.close();
        } catch (RuntimeException failure) {
            log
                .warn("执行单元关闭失败（{}，type={}）：子进程与临时目录仍需人工确认",
                    unit.key(), failure.getClass().getSimpleName());
        }
    }

    private List<ExecutionUnit> snapshot() {
        synchronized (lock) {
            return List.copyOf(units.values());
        }
    }
}
