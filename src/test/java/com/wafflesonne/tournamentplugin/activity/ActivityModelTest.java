package com.wafflesonne.tournamentplugin.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class ActivityModelTest {

    private static final double HALF_LIFE_DAYS = 28.0;
    private static final long MINUTE = 60_000L;
    private static final long WEEK = 7 * 24 * 60 * MINUTE;
    private static final long THURSDAY_21 = ZonedDateTime
        .of(2026, 11, 5, 21, 0, 0, 0, ActivityStore.ZONE).toInstant().toEpochMilli();
    private static final int BIN = TimeOfWeek.binOf(
        ZonedDateTime.of(2026, 11, 5, 21, 0, 0, 0, ActivityStore.ZONE));

    private final ActivityModel model = new ActivityModel(HALF_LIFE_DAYS);

    @Test
    void oneHalfLifeMovesTheEstimateHalfway() {
        assertEquals(15.0, ActivityModel.decayedMean(10, 20, 28, 28), 1e-9);
    }

    @Test
    void weeklyVisitCarriesSixteenPercentAtTheDefaultHalfLife() {
        assertEquals(1.59, ActivityModel.decayedMean(0, 10, 7, 28), 0.01);
    }

    @Test
    void longOutageLetsTheOldValueFade() {
        double afterOneWeek = ActivityModel.decayedMean(0, 10, 7, 28);
        double afterEightWeeks = ActivityModel.decayedMean(0, 10, 56, 28);

        assertEquals(7.5, afterEightWeeks, 1e-9);
        assertEquals(true, afterEightWeeks > afterOneWeek);
    }

    @Test
    void clockGoingBackwardsDoesNotAmplifyAnObservation() {
        assertEquals(10.0, ActivityModel.decayedMean(10, 20, -3, 28), 1e-9);
    }

    @Test
    void firstVisitIsTakenAsIs() {
        visit(THURSDAY_21, 6);

        assertEquals(6.0, model.predict(BIN), 1e-9);
        assertEquals(1, model.visits(BIN));
    }

    @Test
    void samplesInOneVisitCountOnceAsTheirMean() {
        model.observe(THURSDAY_21, 3);
        model.observe(THURSDAY_21 + 5 * MINUTE, 6);
        model.observe(THURSDAY_21 + 10 * MINUTE, 9);
        model.flushPending(THURSDAY_21 + 15 * MINUTE);

        assertEquals(6.0, model.predict(BIN), 1e-9);
        assertEquals(1, model.visits(BIN));
    }

    @Test
    void movingToTheNextBinFoldsThePreviousOne() {
        model.observe(THURSDAY_21, 4);
        model.observe(THURSDAY_21 + 15 * MINUTE, 8);

        assertEquals(4.0, model.predict(BIN), 1e-9);
        assertEquals(0.0, model.predict(BIN + 1), 1e-9);
    }

    @Test
    void discardedVisitTeachesTheModelNothing() {
        model.observe(THURSDAY_21, 40);
        model.discardPending();
        model.flushPending(THURSDAY_21 + 15 * MINUTE);

        assertEquals(false, model.hasData(BIN));
    }

    @Test
    void secondWeekBlendsWithTheFirst() {
        visit(THURSDAY_21, 0);
        visit(THURSDAY_21 + WEEK, 10);

        assertEquals(1.59, model.predict(BIN), 0.01);
        assertEquals(2, model.visits(BIN));
    }

    private void visit(long startMillis, int players) {
        model.observe(startMillis, players);
        model.flushPending(startMillis);
    }
}
