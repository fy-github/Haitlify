package com.haitlify.aitest.platform.assistant.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.ObjectMapper;

/**
 * <b>执行单元池</b>的隔离、复用、有界与回收语义。
 *
 * <p>每个用例都用真实子进程（{@code python3} 桩）+ 临时基准 profile：
 * <ul>
 *   <li>隔离：两个键的单元目录、配置文件、{@code HERMES_HOME} 各不相同，且各自只出现自己的 lease URL；</li>
 *   <li>复用：同一个键第二次解析拿到<b>同一个</b>单元（同一进程、同一份配置）；</li>
 *   <li>有界：容量耗尽时显式失败，且不会把新键塞进既有单元；</li>
 *   <li>回收：空闲单元的子进程真的被杀掉、目录真的被删除、许可真的被撤销；</li>
 *   <li>并行：两个单元同时 reload 能会合（桩只有在两次 reload 重叠时才回成功），
 *       因此「一个单元的许可/控制面锁」不会串行化另一个单元。</li>
 * </ul>
 *
 * <p>任何一步都不触碰真实 profile：基准 profile 由用例自己写在 {@link TempDir} 下。
 */
class ExecutionUnitPoolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String LEASE_A = "https://assistant.internal/mcp-lease/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String LEASE_B = "https://assistant.internal/mcp-lease/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @TempDir
    Path temp;

    private ExecutionUnitHarness harness;
    private ExecutionUnitPool pool;

    @AfterEach
    void close() {
        if (pool != null) {
            pool.close();
            pool = null;
        }
        if (harness != null) {
            harness.close();
            harness = null;
        }
    }

    @Test
    void eachKeyGetsItsOwnUnitAndOnlyItsOwnLeaseUrl() throws Exception {
        harness = ExecutionUnitHarness.create(temp.resolve("harness"), ExecutionUnitHarness.defaultProfileConfig());
        pool = new ExecutionUnitPool(harness.settings(), MAPPER);

        ExecutionUnitKey alice = ExecutionUnitKey.of(11L, 22L, 33L);
        ExecutionUnitKey bob = ExecutionUnitKey.of(11L, 22L, 44L);
        GatewayUnits.TurnUnit aliceUnit = finishedTurn(alice);
        GatewayUnits.TurnUnit bobUnit = finishedTurn(bob);

        aliceUnit.configurator().apply(LEASE_A);
        bobUnit.configurator().apply(LEASE_B);

        Path aliceHome = pool.unitFor(alice).orElseThrow().home();
        Path bobHome = pool.unitFor(bob).orElseThrow().home();
        assertThat(aliceHome).isNotEqualTo(bobHome);
        assertThat(pool.liveUnits()).isEqualTo(2);

        String aliceConfig = ExecutionUnitHarness.read(aliceHome.resolve("config.yaml"));
        String bobConfig = ExecutionUnitHarness.read(bobHome.resolve("config.yaml"));
        assertThat(aliceConfig).contains(LEASE_A).doesNotContain(LEASE_B);
        assertThat(bobConfig).contains(LEASE_B).doesNotContain(LEASE_A);

        // 子进程真的看到了自己的 HERMES_HOME（隔离的物理边界），而不是继承平台的 HOME。
        ExecutionUnitHarness.await("两个子进程写出各自的 HERMES_HOME", Duration.ofSeconds(5),
            () -> Files.isRegularFile(aliceHome.resolve("hermes-home"))
                && Files.isRegularFile(bobHome.resolve("hermes-home")));
        assertThat(ExecutionUnitHarness.read(aliceHome.resolve("hermes-home"))).isEqualTo(aliceHome.toString());
        assertThat(ExecutionUnitHarness.read(bobHome.resolve("hermes-home"))).isEqualTo(bobHome.toString());

        // 目录 0700、profile 0600：单元目录里有 .env 与本轮租约 URL，非属主不得读。
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(aliceHome))).isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(aliceHome.resolve("config.yaml"))))
            .isEqualTo("rw-------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(aliceHome.resolve(".env"))))
            .isEqualTo("rw-------");

        // 单元目录里的 profile 是副本，不是指向基准 profile 的链接。
        assertThat(Files.isSymbolicLink(aliceHome.resolve("config.yaml"))).isFalse();
        assertThat(aliceHome.resolve("config.yaml").toRealPath())
            .isNotEqualTo(harness.profile().resolve("config.yaml").toRealPath());
    }

    @Test
    void theSameKeyReusesTheSameUnit() {
        harness = ExecutionUnitHarness.create(temp.resolve("harness"), ExecutionUnitHarness.defaultProfileConfig());
        pool = new ExecutionUnitPool(harness.settings(), MAPPER);
        ExecutionUnitKey key = ExecutionUnitKey.of(1L, 2L, 3L);

        ExecutionUnit first = pool.unitFor(key).orElse(null);
        assertThat(first).isNull();
        GatewayUnits.TurnUnit one = finishedTurn(key);
        GatewayUnits.TurnUnit two = finishedTurn(key);

        assertThat(two.gateway()).isSameAs(one.gateway());
        assertThat(two.conversations()).isSameAs(one.conversations());
        assertThat(two.permit()).isSameAs(one.permit());
        assertThat(pool.liveUnits()).isEqualTo(1);
        assertThat(pool.keys()).containsExactly(key);
    }

    @Test
    void capacityExhaustionFailsExplicitlyInsteadOfSharingAUnit() {
        harness = ExecutionUnitHarness.create(temp.resolve("harness"), ExecutionUnitHarness.defaultProfileConfig());
        pool = new ExecutionUnitPool(harness.settings(2, Duration.ofMinutes(5), Duration.ofSeconds(1), Map.of()),
            MAPPER);
        ExecutionUnitKey first = ExecutionUnitKey.of(1L, 1L, 1L);
        ExecutionUnitKey second = ExecutionUnitKey.of(1L, 1L, 2L);
        ExecutionUnitKey third = ExecutionUnitKey.of(1L, 1L, 3L);
        finishedTurn(first);
        finishedTurn(second);

        assertThatThrownBy(() -> pool.acquire(third))
            .isInstanceOf(ExecutionUnitUnavailableException.class)
            .satisfies(failure -> assertThat(((ExecutionUnitUnavailableException) failure).reason())
                .isEqualTo(ExecutionUnitUnavailableException.Reason.CAPACITY_EXHAUSTED))
            .hasMessageContaining("CAPACITY_EXHAUSTED");

        // 没有「悄悄塞进第一个单元」这种降级：第三个键不在册，前两个单元仍然各归各的键。
        assertThat(pool.liveUnits()).isEqualTo(2);
        assertThat(pool.keys()).containsExactly(first, second);
        // 已经有的键照旧可用（容量只挡新建）。
        assertThat(finishedTurn(second).key()).isEqualTo(second);
    }

    @Test
    void idleReclaimTerminatesTheSubprocessAndDeletesTheDirectory() {
        harness = ExecutionUnitHarness.create(temp.resolve("harness"), ExecutionUnitHarness.defaultProfileConfig());
        Duration ttl = Duration.ofMillis(200);
        pool = new ExecutionUnitPool(harness.settings(2, ttl, Duration.ofSeconds(30), Map.of()), MAPPER);
        ExecutionUnitKey key = ExecutionUnitKey.of(7L, 8L, 9L);
        // 这一轮已结束：在飞租约归还之后，单元才重新「可回收」（回收判据是租约计数 + lastUsedAt）。
        pool.acquire(key).lease().release();
        Path home = pool.unitFor(key).orElseThrow().home();
        ExecutionUnitHarness.await("子进程写出 pid", Duration.ofSeconds(5),
            () -> ExecutionUnitHarness.pidOf(home) > 0);
        long pid = ExecutionUnitHarness.pidOf(home);
        assertThat(ExecutionUnitHarness.alive(pid)).isTrue();

        List<ExecutionUnitKey> reclaimed = pool.reapIdle(Instant.now().plus(ttl).plusSeconds(1));

        assertThat(reclaimed).containsExactly(key);
        ExecutionUnitHarness.await("子进程已终止", Duration.ofSeconds(5), () -> !ExecutionUnitHarness.alive(pid));
        assertThat(Files.exists(home)).isFalse();
        assertThat(pool.liveUnits()).isZero();
        assertThat(pool.keys()).isEmpty();
    }

    @Test
    void theBackgroundReaperReclaimsIdleUnitsWithoutBeingAsked() {
        harness = ExecutionUnitHarness.create(temp.resolve("harness"), ExecutionUnitHarness.defaultProfileConfig());
        pool = new ExecutionUnitPool(
            harness.settings(2, Duration.ofMillis(100), Duration.ofMillis(50), Map.of()), MAPPER);
        ExecutionUnitKey key = ExecutionUnitKey.of(4L, 5L, 6L);
        pool.acquire(key).lease().release();
        Path home = pool.unitFor(key).orElseThrow().home();
        ExecutionUnitHarness.await("回收线程删掉空闲单元目录", Duration.ofSeconds(10), () -> !Files.exists(home));
        assertThat(pool.liveUnits()).isZero();
    }

    /**
     * <b>在飞租约</b>（不是许可）挡住回收：TTL 早就过期、也没有任何许可被取过（默认
     * {@code configure-gateway=false} 的形状），只要那一轮还没归还租约，单元就必须原样留着——子进程活着、
     * 目录还在、键还在册。探针里的 7a 就是这个形状：以许可为判据时它会被确定性回收，那一轮只能落 UNKNOWN。
     */
    @Test
    void anInFlightLeaseKeepsTheUnitEvenWhenTheTtlIsLongGone() {
        harness = ExecutionUnitHarness.create(temp.resolve("harness"), ExecutionUnitHarness.defaultProfileConfig());
        // TTL 1ms + 20ms 节拍：任何「靠 lastUsedAt 或靠许可」的保护在这里都不成立。
        pool = new ExecutionUnitPool(
            harness.settings(2, Duration.ofMillis(1), Duration.ofMillis(20), Map.of()), MAPPER);
        ExecutionUnitKey key = ExecutionUnitKey.of(10L, 11L, 12L);
        GatewayUnits.TurnUnit turn = pool.acquire(key);
        ExecutionUnit unit = pool.unitFor(key).orElseThrow();
        assertThat(unit.inFlightLeases()).as("acquire 返回即持有一份在飞租约").isEqualTo(1);
        assertThat(turn.lease().released()).isFalse();
        assertThat(unit.permit().held()).as("许可根本没被取过：它是条件性的，不能当在飞标记").isFalse();
        Path home = unit.home();
        ExecutionUnitHarness.await("子进程写出 pid", Duration.ofSeconds(5),
            () -> ExecutionUnitHarness.pidOf(home) > 0);
        long pid = ExecutionUnitHarness.pidOf(home);

        // 让后台回收线程自己跑几十拍，再显式回收一次：两条路径都必须跳过。
        assertThat(pool.reapIdle(Instant.now().plusSeconds(600))).isEmpty();
        ExecutionUnitHarness.await("回收线程没有拆掉在飞单元", Duration.ofMillis(300),
            () -> Files.exists(home) && ExecutionUnitHarness.alive(pid) && pool.liveUnits() == 1);

        assertThat(pool.liveUnits()).isEqualTo(1);
        assertThat(Files.exists(home)).isTrue();
        assertThat(ExecutionUnitHarness.alive(pid)).isTrue();
        assertThat(unit.isClosed()).isFalse();

        // 终态：租约归还之后，同一个单元立刻回到「可回收」。
        turn.lease().release();
        assertThat(unit.inFlightLeases()).isZero();
        assertThat(pool.reapIdle(Instant.now())).containsExactly(key);
        ExecutionUnitHarness.await("子进程已终止", Duration.ofSeconds(5), () -> !ExecutionUnitHarness.alive(pid));
        assertThat(Files.exists(home)).isFalse();
    }

    /**
     * 每份 {@link UnitLease} 都是<b>独立的一份计数</b>，而且释放幂等：重复 release 同一个句柄不会把别人的
     * 那一份也减掉（那会让一个仍在跑的单元变成「空闲」）。
     */
    @Test
    void eachLeaseIsCountedSeparatelyAndReleaseIsIdempotent() {
        harness = ExecutionUnitHarness.create(temp.resolve("harness"), ExecutionUnitHarness.defaultProfileConfig());
        pool = new ExecutionUnitPool(
            harness.settings(2, Duration.ofMillis(1), Duration.ofSeconds(30), Map.of()), MAPPER);
        ExecutionUnitKey key = ExecutionUnitKey.of(13L, 14L, 15L);
        GatewayUnits.TurnUnit first = pool.acquire(key);
        GatewayUnits.TurnUnit second = pool.acquire(key);
        ExecutionUnit unit = pool.unitFor(key).orElseThrow();
        assertThat(unit.inFlightLeases()).isEqualTo(2);

        first.lease().release();
        first.lease().release();
        first.lease().release();
        assertThat(first.lease().released()).isTrue();
        assertThat(unit.inFlightLeases()).as("重复释放同一个句柄只减一次").isEqualTo(1);
        assertThat(pool.reapIdle(Instant.now().plusSeconds(600))).as("还有一轮在飞").isEmpty();

        second.lease().release();
        assertThat(unit.inFlightLeases()).isZero();
        assertThat(pool.reapIdle(Instant.now().plusSeconds(600))).containsExactly(key);
    }

    /** 跨单元互不影响：A 的在飞租约只保护 A，B 该回收就回收。 */
    @Test
    void oneUnitsLeaseDoesNotProtectAnotherUnit() {
        harness = ExecutionUnitHarness.create(temp.resolve("harness"), ExecutionUnitHarness.defaultProfileConfig());
        pool = new ExecutionUnitPool(
            harness.settings(4, Duration.ofMillis(1), Duration.ofSeconds(30), Map.of()), MAPPER);
        ExecutionUnitKey alice = ExecutionUnitKey.of(16L, 17L, 18L);
        ExecutionUnitKey bob = ExecutionUnitKey.of(16L, 17L, 19L);
        GatewayUnits.TurnUnit aliceTurn = pool.acquire(alice);
        pool.acquire(bob).lease().release();
        ExecutionUnit aliceUnit = pool.unitFor(alice).orElseThrow();
        ExecutionUnit bobUnit = pool.unitFor(bob).orElseThrow();
        Path bobHome = bobUnit.home();

        assertThat(pool.reapIdle(Instant.now().plusSeconds(600))).containsExactly(bob);
        assertThat(Files.exists(bobHome)).isFalse();
        assertThat(aliceUnit.inFlightLeases()).isEqualTo(1);
        assertThat(aliceUnit.isClosed()).isFalse();
        assertThat(pool.liveUnits()).isEqualTo(1);

        aliceTurn.lease().release();
        assertThat(pool.reapIdle(Instant.now().plusSeconds(600))).containsExactly(alice);
    }

    /**
     * <b>持有在飞租约时 close() 不得静默拆掉它</b>：策略是「等」（见
     * {@link ExecutionUnitPool#CLOSE_LEASE_DRAIN_GRACE}）。关闭线程必须在租约归还之前一直阻塞，
     * 期间子进程、目录、单元都还活着；归还之后才真正回收。
     */
    @Test
    void closingThePoolWaitsForTheInFlightLeaseInsteadOfTearingTheUnitDown() throws Exception {
        harness = ExecutionUnitHarness.create(temp.resolve("harness"), ExecutionUnitHarness.defaultProfileConfig());
        pool = new ExecutionUnitPool(harness.settings(), MAPPER);
        ExecutionUnitKey key = ExecutionUnitKey.of(24L, 25L, 26L);
        GatewayUnits.TurnUnit turn = pool.acquire(key);
        ExecutionUnit unit = pool.unitFor(key).orElseThrow();
        Path home = unit.home();
        ExecutionUnitHarness.await("子进程写出 pid", Duration.ofSeconds(5),
            () -> ExecutionUnitHarness.pidOf(home) > 0);
        long pid = ExecutionUnitHarness.pidOf(home);

        ExecutorService closer = Executors.newSingleThreadExecutor();
        try {
            Future<Long> closing = closer.submit(() -> {
                long began = System.nanoTime();
                pool.close();
                return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
            });
            Thread.sleep(300L);
            assertThat(closing.isDone()).as("持租约时 close() 必须等待，而不是拆掉在飞单元").isFalse();
            assertThat(unit.isClosed()).isFalse();
            assertThat(Files.exists(home)).isTrue();
            assertThat(ExecutionUnitHarness.alive(pid)).isTrue();

            turn.lease().release();
            long elapsed = closing.get(10, TimeUnit.SECONDS);
            assertThat(elapsed).as("close 至少要等过我们持有的那段时间").isGreaterThanOrEqualTo(250L);
        } finally {
            closer.shutdownNow();
        }

        ExecutionUnitHarness.await("租约归还后子进程已终止", Duration.ofSeconds(5),
            () -> !ExecutionUnitHarness.alive(pid));
        assertThat(Files.exists(home)).isFalse();
        assertThat(unit.isClosed()).isTrue();
        assertThat(pool.available()).isFalse();
    }

    /**
     * 宽限到期仍未归还时，close() 仍然<b>有界返回</b>并强制回收（子进程比进程池活得更久是泄漏），
     * 但这不是静默：日志里带单元与剩余租约数，且那一轮的账本已由服务层落 UNKNOWN。
     */
    @Test
    void closingThePoolIsBoundedEvenWhenTheLeaseIsNeverReleased() {
        harness = ExecutionUnitHarness.create(temp.resolve("harness"), ExecutionUnitHarness.defaultProfileConfig());
        pool = new ExecutionUnitPool(harness.settings(), MAPPER);
        ExecutionUnitKey key = ExecutionUnitKey.of(27L, 28L, 29L);
        pool.acquire(key);
        ExecutionUnit unit = pool.unitFor(key).orElseThrow();
        Path home = unit.home();
        ExecutionUnitHarness.await("子进程写出 pid", Duration.ofSeconds(5),
            () -> ExecutionUnitHarness.pidOf(home) > 0);

        long began = System.nanoTime();
        pool.close();
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);

        assertThat(elapsed).as("最多等一个宽限，不能无限挂着")
            .isGreaterThanOrEqualTo(ExecutionUnitPool.CLOSE_LEASE_DRAIN_GRACE.toMillis() - 100L)
            .isLessThan(ExecutionUnitPool.CLOSE_LEASE_DRAIN_GRACE.toMillis() + 3_000L);
        assertThat(unit.isClosed()).isTrue();
        assertThat(Files.exists(home)).isFalse();
    }

    @Test
    void closingThePoolKillsEverySubprocessDeletesEveryDirectoryAndReleasesPermits() {
        harness = ExecutionUnitHarness.create(temp.resolve("harness"), ExecutionUnitHarness.defaultProfileConfig());
        pool = new ExecutionUnitPool(harness.settings(), MAPPER);
        ExecutionUnitKey key = ExecutionUnitKey.of(21L, 22L, 23L);
        GatewayUnits.TurnUnit unit = finishedTurn(key);
        assertThat(unit.permit().acquire("message-1")).isTrue();
        assertThat(pool.holdsPermit("message-1")).isTrue();
        Path home = pool.unitFor(key).orElseThrow().home();
        ExecutionUnitHarness.await("子进程写出 pid", Duration.ofSeconds(5),
            () -> ExecutionUnitHarness.pidOf(home) > 0);
        long pid = ExecutionUnitHarness.pidOf(home);

        pool.close();

        ExecutionUnitHarness.await("子进程已终止", Duration.ofSeconds(5), () -> !ExecutionUnitHarness.alive(pid));
        assertThat(Files.exists(home)).isFalse();
        assertThat(unit.permit().heldBy("message-1")).isFalse();
        assertThat(pool.holdsPermit("message-1")).isFalse();
        assertThat(pool.available()).isFalse();
        assertThatThrownBy(() -> pool.acquire(key))
            .isInstanceOf(ExecutionUnitUnavailableException.class)
            .satisfies(failure -> assertThat(((ExecutionUnitUnavailableException) failure).reason())
                .isEqualTo(ExecutionUnitUnavailableException.Reason.CLOSED));
    }

    /**
     * 跨单元并行：桩只有在收到<b>两个</b> {@code reload.mcp} 时才回成功（会合目录里的两个 start 文件）。
     * 如果两个单元共用一把锁/一个控制面，第一个 reload 只能等到 5 秒后超时失败，本用例就会红。
     */
    @Test
    void twoUnitsReloadInParallelWithoutBlockingEachOther() throws Exception {
        harness = ExecutionUnitHarness.create(temp.resolve("harness"), ExecutionUnitHarness.defaultProfileConfig());
        Map<String, String> environment =
            ExecutionUnitHarness.environment(ExecutionUnitHarness.RENDEZVOUS_ENV, temp.resolve("rendezvous").toString());
        pool = new ExecutionUnitPool(
            harness.settings(2, Duration.ofMinutes(5), Duration.ofSeconds(1), environment), MAPPER);

        GatewayUnits.TurnUnit alice = finishedTurn(ExecutionUnitKey.of(1L, 1L, 1L));
        GatewayUnits.TurnUnit bob = finishedTurn(ExecutionUnitKey.of(1L, 1L, 2L));
        // 许可必须<b>每单元一把</b>：同一时刻两个单元各自都能持有自己的许可（共享一把会把跨单元并行串行化）。
        assertThat(alice.permit()).isNotSameAs(bob.permit());
        assertThat(alice.permit().acquire("alice-turn")).isTrue();
        assertThat(bob.permit().acquire("bob-turn")).isTrue();
        alice.permit().release("alice-turn");
        bob.permit().release("bob-turn");

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            Future<?> aliceApply = threads.submit(() -> {
                await(start);
                try {
                    alice.configurator().apply(LEASE_A);
                } catch (RuntimeException problem) {
                    failure.compareAndSet(null, problem);
                }
            });
            Future<?> bobApply = threads.submit(() -> {
                await(start);
                try {
                    bob.configurator().apply(LEASE_B);
                } catch (RuntimeException problem) {
                    failure.compareAndSet(null, problem);
                }
            });
            long began = System.nanoTime();
            start.countDown();
            aliceApply.get(20, TimeUnit.SECONDS);
            bobApply.get(20, TimeUnit.SECONDS);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);

            assertThat(failure.get()).as("两个单元必须都能完成配置").isNull();
            // 会合成功本身就证明两次 reload 重叠；时间上界只是第二道证据（串行会 ≥ 5s 的桩超时）。
            assertThat(elapsedMillis).isLessThan(4_000L);
            assertThat(ExecutionUnitHarness.read(pool.unitFor(ExecutionUnitKey.of(1L, 1L, 1L)).orElseThrow()
                .home().resolve("config.yaml"))).contains(LEASE_A).doesNotContain(LEASE_B);
            assertThat(ExecutionUnitHarness.read(pool.unitFor(ExecutionUnitKey.of(1L, 1L, 2L)).orElseThrow()
                .home().resolve("config.yaml"))).contains(LEASE_B).doesNotContain(LEASE_A);
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    void thePoolRefusesToStartWhenTheBaseProfileIsIncomplete() throws IOException {
        harness = ExecutionUnitHarness.create(temp.resolve("harness"), ExecutionUnitHarness.defaultProfileConfig());
        Files.delete(harness.profile().resolve(".env"));

        assertThatThrownBy(() -> new ExecutionUnitPool(harness.settings(), MAPPER))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(".env");

        try (var entries = Files.list(harness.unitsRoot())) {
            assertThat(entries.toList()).as("启动失败不得留下单元目录").isEmpty();
        }
    }

    /**
     * 基准 profile 的 {@code config.yaml} 是<b>符号链接</b>时拒绝启动：那可以把真实用户的
     * 默认 Hermes 配置文件复制进单元目录，正是本功能明确禁止的事。
     */
    @Test
    void thePoolRefusesASymlinkedBaseProfile() throws IOException {
        harness = ExecutionUnitHarness.create(temp.resolve("harness"), ExecutionUnitHarness.defaultProfileConfig());
        Path elsewhere = temp.resolve("elsewhere.yaml");
        Files.writeString(elsewhere, "mcp_servers: {}\n");
        Files.delete(harness.profile().resolve("config.yaml"));
        Files.createSymbolicLink(harness.profile().resolve("config.yaml"), elsewhere);

        assertThatThrownBy(() -> new ExecutionUnitPool(harness.settings(), MAPPER))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("config.yaml");
    }

    /**
     * 取一个单元并<b>立刻归还</b>它的在飞租约：即「这一轮已经结束」的状态。
     *
     * <p>这些用例关心的是隔离/复用/容量/并行，不是「有一轮在飞」；用一个显式命名的辅助方法表达
     * 「一轮已结束」比到处写 {@code .lease().release()} 更难被读成「租约可以不管」。
     */
    private GatewayUnits.TurnUnit finishedTurn(ExecutionUnitKey key) {
        GatewayUnits.TurnUnit turn = pool.acquire(key);
        turn.lease().release();
        return turn;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}