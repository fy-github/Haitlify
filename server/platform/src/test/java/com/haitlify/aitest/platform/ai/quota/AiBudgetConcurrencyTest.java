package com.haitlify.aitest.platform.ai.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * T13 批次 1：预算护栏——并发争抢不超发、未知 usage/未知价格不免费、
 * 失败重试/备用路由/repair 逐 attempt 计入、无上界 fail-closed。
 */
class AiBudgetConcurrencyTest {

    private JdbcTemplate jdbcTemplate;
    private AiBudgetService budgetService;
    private final LocalDate day = LocalDate.of(2026, 10, 10);

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:budget-test;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
            create table if not exists ai_budget_ledger(
              ledger_id bigint auto_increment primary key, scope_key varchar(160) not null, window_day date not null,
              reserved_tokens bigint default 0, settled_tokens bigint default 0,
              reserved_calls int default 0, settled_calls int default 0,
              limit_tokens bigint not null, limit_calls int not null, updated_at timestamp,
              constraint uk_ai_budget_ledger_scope unique (scope_key, window_day))
            """);
        jdbcTemplate.execute("""
            create table if not exists ai_budget_reservations(
              reservation_id varchar(128) primary key, scope_key varchar(160) not null, window_day date not null,
              reserved_tokens bigint not null, reserved_calls int not null, settled_tokens bigint,
              settled_calls int, usage_known boolean default false, pricing_mode varchar(32) not null,
              state varchar(32) not null, attempt_ref varchar(160) default '', created_at timestamp, settled_at timestamp)
            """);
        jdbcTemplate.execute("delete from ai_budget_reservations");
        jdbcTemplate.execute("delete from ai_budget_ledger");
        budgetService = new AiBudgetService(jdbcTemplate);
    }

    @Test
    void concurrentReservationsNeverExceedTheLimit() throws Exception {
        AiBudgetService.BudgetLimit limit = new AiBudgetService.BudgetLimit(1000, 10);
        String scope = AiBudgetService.projectScope(7L);
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        AtomicInteger granted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try {
            for (int index = 0; index < threads; index++) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        budgetService.reserve(scope, day, limit, 100, 1, "attempt-race");
                        granted.incrementAndGet();
                    } catch (AiBudgetService.BudgetExceededException expected) {
                        rejected.incrementAndGet();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneGate.countDown();
                    }
                });
            }
            startGate.countDown();
            assertThat(doneGate.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        // 单语句 CAS：恰好 10 个成功、10 个被拒，预留绝不超过上限。
        assertThat(granted.get()).isEqualTo(10);
        assertThat(rejected.get()).isEqualTo(10);
        Map<String, Object> ledger = budgetService.snapshot(scope, day);
        assertThat(((Number) ledger.get("reserved_tokens")).longValue()).isEqualTo(1000);
        assertThat(((Number) ledger.get("reserved_calls")).intValue()).isEqualTo(10);
    }

    @Test
    void unknownUsageSettlesAtTheReservationAndIsNeverTreatedAsFree() {
        AiBudgetService.BudgetLimit limit = new AiBudgetService.BudgetLimit(10_000, 100);
        String scope = AiBudgetService.taskScope("bug_analysis");
        AiBudgetService.Reservation reservation = budgetService.reserve(scope, day, limit, 500, 1, "attempt-1");

        // usage 未知：按预留额全额结算。
        budgetService.settle(reservation.reservationId(), 0, 0, false,
            AiBudgetService.PricingMode.UNKNOWN_PRICE_CONSERVATIVE);
        Map<String, Object> ledger = budgetService.snapshot(scope, day);
        assertThat(((Number) ledger.get("reserved_tokens")).longValue()).isZero();
        assertThat(((Number) ledger.get("settled_tokens")).longValue()).isEqualTo(500);

        Map<String, Object> row = jdbcTemplate.queryForMap(
            "select * from ai_budget_reservations where reservation_id = ?", reservation.reservationId());
        assertThat(((Number) row.get("SETTLED_TOKENS")).longValue()).isEqualTo(500);
        assertThat((Boolean) row.get("USAGE_KNOWN")).isFalse();
    }

    @Test
    void knownUsageWithKnownPriceRefundsTheDifference() {
        AiBudgetService.BudgetLimit limit = new AiBudgetService.BudgetLimit(10_000, 100);
        String scope = AiBudgetService.userScope("qa-user");
        AiBudgetService.Reservation reservation = budgetService.reserve(scope, day, limit, 800, 1, "attempt-1");

        AiBudgetService.SettleOutcome outcome = budgetService.settle(reservation.reservationId(), 320, 1, true,
            AiBudgetService.PricingMode.KNOWN_PRICE);
        assertThat(outcome).isEqualTo(AiBudgetService.SettleOutcome.SETTLED);
        Map<String, Object> ledger = budgetService.snapshot(scope, day);
        assertThat(((Number) ledger.get("reserved_tokens")).longValue()).isZero();
        assertThat(((Number) ledger.get("settled_tokens")).longValue()).isEqualTo(320);
    }

    @Test
    void unknownPriceStaysConservativeEvenWhenUsageIsKnown() {
        AiBudgetService.BudgetLimit limit = new AiBudgetService.BudgetLimit(10_000, 100);
        String scope = AiBudgetService.globalScope();
        AiBudgetService.Reservation reservation = budgetService.reserve(scope, day, limit, 900, 1, "attempt-1");

        // 用量已知但模型价格未知：不得计为免费，按预留额结算。
        budgetService.settle(reservation.reservationId(), 100, 1, true,
            AiBudgetService.PricingMode.UNKNOWN_PRICE_CONSERVATIVE);
        Map<String, Object> ledger = budgetService.snapshot(scope, day);
        assertThat(((Number) ledger.get("settled_tokens")).longValue()).isEqualTo(900);
    }

    @Test
    void retriesFallbacksAndRepairsEachCountThroughTheirOwnReservation() {
        AiBudgetService.BudgetLimit limit = new AiBudgetService.BudgetLimit(100_000, 100);
        String scope = AiBudgetService.projectScope(11L);
        // 首次失败（未知用量）→ 重试（已知）→ 备用路由（未知价格）。
        AiBudgetService.Reservation first = budgetService.reserve(scope, day, limit, 400, 1, "attempt#1");
        budgetService.settle(first.reservationId(), 0, 0, false, AiBudgetService.PricingMode.UNKNOWN_PRICE_CONSERVATIVE);
        AiBudgetService.Reservation retry = budgetService.reserve(scope, day, limit, 400, 1, "attempt#2");
        budgetService.settle(retry.reservationId(), 250, 1, true, AiBudgetService.PricingMode.KNOWN_PRICE);
        AiBudgetService.Reservation fallback = budgetService.reserve(scope, day, limit, 400, 1, "attempt#3");
        budgetService.settle(fallback.reservationId(), 100, 1, true, AiBudgetService.PricingMode.UNKNOWN_PRICE_CONSERVATIVE);

        Map<String, Object> ledger = budgetService.snapshot(scope, day);
        // 400（未知全记）+ 250（实际）+ 400（未知价格全记）= 1050 —— 每个 attempt 都计入。
        assertThat(((Number) ledger.get("settled_tokens")).longValue()).isEqualTo(1050);
        assertThat(((Number) ledger.get("settled_calls")).intValue()).isEqualTo(3);
        assertThat(((Number) ledger.get("reserved_tokens")).longValue()).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from ai_budget_reservations where scope_key = ? and state = 'SETTLED'",
            Integer.class, scope)).isEqualTo(3);
    }

    @Test
    void aNegativeUpperBoundOrMissingScopeFailsClosed() {
        AiBudgetService.BudgetLimit limit = new AiBudgetService.BudgetLimit(1000, 10);
        assertThatThrownBy(() -> budgetService.reserve(AiBudgetService.projectScope(1L), day, limit, -1, 1, "a"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("fail-closed");
        assertThatThrownBy(() -> budgetService.reserve("", day, limit, 10, 1, "a"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void settleIsIdempotentAndExhaustedScopesKeepFailing() {
        AiBudgetService.BudgetLimit limit = new AiBudgetService.BudgetLimit(200, 2);
        String scope = AiBudgetService.projectScope(3L);
        AiBudgetService.Reservation first = budgetService.reserve(scope, day, limit, 100, 1, "a1");
        budgetService.reserve(scope, day, limit, 100, 1, "a2");
        // 第三次必然被拒（calls 上限）。
        assertThatThrownBy(() -> budgetService.reserve(scope, day, limit, 100, 1, "a3"))
            .isInstanceOf(AiBudgetService.BudgetExceededException.class);

        budgetService.settle(first.reservationId(), 50, 1, true, AiBudgetService.PricingMode.KNOWN_PRICE);
        assertThat(budgetService.settle(first.reservationId(), 50, 1, true, AiBudgetService.PricingMode.KNOWN_PRICE))
            .isEqualTo(AiBudgetService.SettleOutcome.ALREADY_SETTLED);
        assertThat(budgetService.settle("budget-does-not-exist", 1, 1, true,
            AiBudgetService.PricingMode.KNOWN_PRICE)).isEqualTo(AiBudgetService.SettleOutcome.NOT_FOUND);
        // 结算释放预留（并发上限语义）：完成后的调用可再次预留，但累计用量不丢失。
        budgetService.reserve(scope, day, limit, 50, 1, "a4");
        Map<String, Object> ledger = budgetService.snapshot(scope, day);
        assertThat(((Number) ledger.get("settled_calls")).intValue()).isEqualTo(1);
        assertThat(((Number) ledger.get("reserved_calls")).intValue()).isEqualTo(2);
        List<Map<String, Object>> reservations = jdbcTemplate.queryForList(
            "select * from ai_budget_reservations where scope_key = ? order by created_at asc", scope);
        assertThat(reservations).hasSize(3);
    }
}
