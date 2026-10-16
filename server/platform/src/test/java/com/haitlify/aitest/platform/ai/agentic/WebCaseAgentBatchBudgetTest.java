package com.haitlify.aitest.platform.ai.agentic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class WebCaseAgentBatchBudgetTest {

    @Test
    void capsPointBudgetAtNinetySecondsAndTracksRemainingAcrossPoints() {
        AtomicLong nanos = new AtomicLong(0L);
        WebCaseAgentDispatcher.BatchBudgetLedger ledger = WebCaseAgentDispatcher.BatchBudgetLedger.start(
            120_000L,
            Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC),
            nanos::get
        );

        assertEquals(90_000L, ledger.pointBudgetMs());
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(20));
        assertEquals(100_000L, ledger.remainingMs());
        assertEquals(90_000L, ledger.pointBudgetMs());

        nanos.addAndGet(TimeUnit.SECONDS.toNanos(35));
        assertEquals(65_000L, ledger.remainingMs());
        assertEquals(65_000L, ledger.pointBudgetMs());
    }

    @Test
    void returnsZeroWhenTheBatchBudgetIsExhausted() {
        AtomicLong nanos = new AtomicLong(0L);
        WebCaseAgentDispatcher.BatchBudgetLedger ledger = WebCaseAgentDispatcher.BatchBudgetLedger.start(
            30_000L,
            Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC),
            nanos::get
        );

        nanos.addAndGet(TimeUnit.SECONDS.toNanos(30));
        assertEquals(0L, ledger.remainingMs());
        assertEquals(0L, ledger.pointBudgetMs());
    }
}
