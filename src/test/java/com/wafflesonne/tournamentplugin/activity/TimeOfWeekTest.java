package com.wafflesonne.tournamentplugin.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class TimeOfWeekTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Istanbul");

    private static ZonedDateTime at(int day, int hour, int minute) {
        // 2 November 2026 is a Monday.
        return ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, ZONE);
    }

    @Test
    void weekRunsFromMondayMidnightToSundayNight() {
        assertEquals(0, TimeOfWeek.binOf(at(2, 0, 0)));
        assertEquals(671, TimeOfWeek.binOf(at(8, 23, 59)));
        assertEquals(672, TimeOfWeek.BINS);
    }

    @Test
    void binRoundTripsThroughItsParts() {
        int bin = TimeOfWeek.binOf(at(5, 21, 45));

        assertEquals(3, TimeOfWeek.weekdayOf(bin));
        assertEquals(21, TimeOfWeek.hourOf(bin));
        assertEquals(45, TimeOfWeek.minuteOf(bin));
    }

    @Test
    void weekWrapsInBothDirections() {
        assertEquals(671, TimeOfWeek.wrap(-1));
        assertEquals(0, TimeOfWeek.wrap(TimeOfWeek.BINS));
    }

    @Test
    void nextOccurrenceIncludesTheCurrentInstant() {
        ZonedDateTime from = at(5, 21, 45);

        assertEquals(from, TimeOfWeek.nextOccurrence(TimeOfWeek.binOf(from), from));
    }

    @Test
    void passedSlotMovesToNextWeek() {
        int bin = TimeOfWeek.binOf(at(5, 21, 45));

        assertEquals(at(12, 21, 45), TimeOfWeek.nextOccurrence(bin, at(5, 22, 0)));
    }

    @Test
    void laterWeekdayStaysInThisWeek() {
        int bin = TimeOfWeek.binOf(at(7, 16, 0));

        assertEquals(at(7, 16, 0), TimeOfWeek.nextOccurrence(bin, at(2, 9, 0)));
    }
}
