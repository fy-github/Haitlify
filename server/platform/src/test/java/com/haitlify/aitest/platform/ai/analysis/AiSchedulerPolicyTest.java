package com.haitlify.aitest.platform.ai.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class AiSchedulerPolicyTest {

    @Test
    void defaultsAreWithinPlatformCaps() {
        AiSchedulerPolicy policy = new AiSchedulerPolicy(60, 15, 30, 30, 1);
        assertEquals(Duration.ofSeconds(60), policy.leaseTtl());
        assertEquals(Duration.ofSeconds(15), policy.renewInterval());
        assertEquals(Duration.ofSeconds(30), policy.recoveryInterval());
        assertEquals(Duration.ofSeconds(30), policy.gracefulShutdownDeadline());
        assertEquals(1, policy.maxActiveRunsPerProject());
    }

    @Test
    void leaseTtlIsClampedByPlatformCaps() {
        assertThrows(IllegalStateException.class, () -> new AiSchedulerPolicy(10, 15, 30, 30, 1));
        assertThrows(IllegalStateException.class, () -> new AiSchedulerPolicy(500, 15, 30, 30, 1));
    }

    @Test
    void renewIntervalMustStayBelowHalfOfTtl() {
        assertThrows(IllegalStateException.class, () -> new AiSchedulerPolicy(60, 31, 30, 30, 1));
        assertThrows(IllegalStateException.class, () -> new AiSchedulerPolicy(60, 2, 30, 30, 1));
        new AiSchedulerPolicy(60, 30, 30, 30, 1);
    }

    @Test
    void recoveryAndGracefulDeadlineAreWithinCaps() {
        assertThrows(IllegalStateException.class, () -> new AiSchedulerPolicy(60, 15, 5, 30, 1));
        assertThrows(IllegalStateException.class, () -> new AiSchedulerPolicy(60, 15, 500, 30, 1));
        assertThrows(IllegalStateException.class, () -> new AiSchedulerPolicy(60, 15, 30, 5, 1));
        assertThrows(IllegalStateException.class, () -> new AiSchedulerPolicy(60, 15, 30, 200, 1));
    }

    @Test
    void perProjectConcurrencyIsCapped() {
        assertThrows(IllegalStateException.class, () -> new AiSchedulerPolicy(60, 15, 30, 30, 0));
        assertThrows(IllegalStateException.class, () -> new AiSchedulerPolicy(60, 15, 30, 30, 5));
        new AiSchedulerPolicy(60, 15, 30, 30, 4);
    }
}
