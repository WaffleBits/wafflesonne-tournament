package com.wafflesonne.tournamentplugin.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.IntToDoubleFunction;
import org.junit.jupiter.api.Test;

class SlotScorerTest {

    private static final int BEFORE = 30;
    private static final int AFTER = 90;
    private static final int PLATEAU = 30;
    private static final double EDGE = 0.3;

    private static double score(IntToDoubleFunction predict, int bin) {
        return SlotScorer.weightedMean(predict, bin, BEFORE, AFTER, PLATEAU, EDGE);
    }

    @Test
    void fullWeightFromTheStartThroughThePlateau() {
        assertEquals(1.0, SlotScorer.weightAt(0, BEFORE, AFTER, PLATEAU, EDGE), 1e-9);
        assertEquals(1.0, SlotScorer.weightAt(30, BEFORE, AFTER, PLATEAU, EDGE), 1e-9);
    }

    @Test
    void windowEdgesKeepTheEdgeWeight() {
        assertEquals(EDGE, SlotScorer.weightAt(-30, BEFORE, AFTER, PLATEAU, EDGE), 1e-9);
        assertEquals(EDGE, SlotScorer.weightAt(90, BEFORE, AFTER, PLATEAU, EDGE), 1e-9);
    }

    @Test
    void flatActivityScoresAsItsOwnLevel() {
        assertEquals(6.0, score(bin -> 6.0, 100), 1e-9);
    }

    @Test
    void oneFifteenMinuteSpikeLosesToASustainedEvening() {
        IntToDoubleFunction spike = bin -> bin == 100 ? 30.0 : 0.0;
        IntToDoubleFunction evening = bin -> 8.0;

        assertTrue(score(spike, 100) < score(evening, 100));
    }

    @Test
    void scoringCrossesTheEndOfTheWeek() {
        IntToDoubleFunction sundayNight = bin -> TimeOfWeek.wrap(bin) >= 668 ? 10.0 : 0.0;

        assertTrue(score(sundayNight, 671) > 0);
    }
}
