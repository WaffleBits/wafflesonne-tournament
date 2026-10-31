package com.wafflesonne.tournamentplugin.activity;

import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.util.Locale;

/**
 * The week divided into 15-minute bins.
 *
 * <p>7 days x 96 bins = 672. Bin 0 is Monday 00:00, bin 671 is Sunday 23:45.
 * Everything downstream, the activity estimate, candidate scoring, the locked
 * occurrence, is expressed in these bins, so there is exactly one definition
 * of "when in the week" in the plugin.
 *
 * <p>15 minutes rather than an hour because a tournament start of 21:45 is a
 * meaningfully different proposition to 21:00, and rather than 5 minutes
 * because a bin needs several observations per visit to mean anything.
 */
public final class TimeOfWeek {

    public static final int BINS_PER_HOUR = 4;
    public static final int BINS_PER_DAY = 24 * BINS_PER_HOUR;
    public static final int BINS = 7 * BINS_PER_DAY;

    public static final int MINUTES_PER_BIN = 15;

    private static final String[] TR_DAYS = {
        "Pazartesi", "Sali", "Carsamba", "Persembe", "Cuma", "Cumartesi", "Pazar"
    };

    private TimeOfWeek() {
    }

    /** Bin containing this instant. */
    public static int binOf(ZonedDateTime when) {
        int weekday = when.getDayOfWeek().getValue() - 1; // 0 = Monday
        return weekday * BINS_PER_DAY
            + when.getHour() * BINS_PER_HOUR
            + when.getMinute() / MINUTES_PER_BIN;
    }

    public static int weekdayOf(int bin) {
        return Math.floorDiv(bin, BINS_PER_DAY);
    }

    public static int hourOf(int bin) {
        return (bin % BINS_PER_DAY) / BINS_PER_HOUR;
    }

    public static int minuteOf(int bin) {
        return (bin % BINS_PER_HOUR) * MINUTES_PER_BIN;
    }

    public static DayOfWeek dayOfWeek(int bin) {
        return DayOfWeek.of(weekdayOf(bin) + 1);
    }

    /** Wrap an arbitrary offset back into range, the week is a circle. */
    public static int wrap(int bin) {
        return Math.floorMod(bin, BINS);
    }

    /** e.g. "Persembe 21:45". */
    public static String label(int bin) {
        return String.format(Locale.ROOT, "%s %02d:%02d",
            TR_DAYS[weekdayOf(bin)], hourOf(bin), minuteOf(bin));
    }

    /**
     * The first occurrence of {@code bin} at or after {@code from}.
     *
     * <p>Used to turn a repeating time-of-week into the absolute timestamp the
     * scheduler commits to. Everything after selection works in real instants,
     * never in "day and hour", that comparison is what let a restart during
     * the scheduled hour silently skip a week.
     */
    public static ZonedDateTime nextOccurrence(int bin, ZonedDateTime from) {
        ZonedDateTime candidate = from
            .withHour(hourOf(bin))
            .withMinute(minuteOf(bin))
            .withSecond(0)
            .withNano(0);

        int daysAhead = Math.floorMod(weekdayOf(bin) - (from.getDayOfWeek().getValue() - 1), 7);
        candidate = candidate.plusDays(daysAhead);

        if (candidate.isBefore(from)) {
            candidate = candidate.plusWeeks(1);
        }
        return candidate;
    }
}
