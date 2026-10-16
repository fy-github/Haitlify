package com.haitlify.aitest.platform.ai.draft;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AiDraftExplorationBudgetTest {

    @Test
    void disablesMultiRoundExplorationWithSingleRoundRuntimeBudget() {
        var budget = AiDraftTestPointService.explorationBudgetFor(false, 90_000L);

        assertEquals(1, budget.maxRounds());
        assertEquals(90_000L, budget.maxDurationMs());
    }

    @Test
    void keepsFiveRoundBudgetWhenMultiRoundExplorationIsEnabled() {
        var budget = AiDraftTestPointService.explorationBudgetFor(true, 30_000L);

        assertEquals(5, budget.maxRounds());
        assertEquals(30_000L, budget.maxDurationMs());
    }
}
