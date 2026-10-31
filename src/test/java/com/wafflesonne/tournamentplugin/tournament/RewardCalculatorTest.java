package com.wafflesonne.tournamentplugin.tournament;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RewardCalculatorTest {

    private static double multiplier(int count) {
        return RewardCalculator.payoutMultiplier(count, 3, 40, 2.0);
    }

    @Test
    void curveRunsFromATenthToFull() {
        assertEquals(0.1, multiplier(3), 1e-9);
        assertEquals(1.0, multiplier(40), 1e-9);
    }

    @Test
    void entrantsOutsideTheRangeAreClamped() {
        assertEquals(0.1, multiplier(1), 1e-9);
        assertEquals(1.0, multiplier(500), 1e-9);
    }

    @Test
    void eachExtraEntrantIsWorthMoreThanTheLast() {
        double early = multiplier(5) - multiplier(4);
        double late = multiplier(35) - multiplier(34);

        assertTrue(late > early);
    }

    @Test
    void tinyServerFallsBackToTheMinimum() {
        assertEquals(0.1, RewardCalculator.payoutMultiplier(5, 3, 3, 2.0), 1e-9);
    }
}
