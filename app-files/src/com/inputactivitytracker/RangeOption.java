package com.inputactivitytracker;

import java.time.*;
import java.time.temporal.TemporalAdjusters;

record RangeOption(String label, Kind kind) {
    enum Kind {
        SESSION, LAST_1_HOUR, LAST_2_HOURS, LAST_6_HOURS, LAST_12_HOURS,
        TODAY, YESTERDAY, THIS_WEEK, LAST_7_DAYS, LAST_30_DAYS,
        LAST_6_MONTHS, LAST_1_YEAR, LAST_2_YEARS, LAST_5_YEARS, CUSTOM
    }

    static RangeOption[] defaults() {
        return new RangeOption[] {
                new RangeOption("Current session", Kind.SESSION),
                new RangeOption("Last 1 hour", Kind.LAST_1_HOUR),
                new RangeOption("Last 2 hours", Kind.LAST_2_HOURS),
                new RangeOption("Last 6 hours", Kind.LAST_6_HOURS),
                new RangeOption("Last 12 hours", Kind.LAST_12_HOURS),
                new RangeOption("Today", Kind.TODAY),
                new RangeOption("Yesterday", Kind.YESTERDAY),
                new RangeOption("This week", Kind.THIS_WEEK),
                new RangeOption("Last 7 days", Kind.LAST_7_DAYS),
                new RangeOption("Last 30 days", Kind.LAST_30_DAYS),
                new RangeOption("Last 6 months", Kind.LAST_6_MONTHS),
                new RangeOption("Last 1 year", Kind.LAST_1_YEAR),
                new RangeOption("Last 2 years", Kind.LAST_2_YEARS),
                new RangeOption("Last 5 years", Kind.LAST_5_YEARS),
                new RangeOption("Custom range", Kind.CUSTOM)
        };
    }

    static RangeOption find(Kind kind) {
        for (RangeOption option : defaults()) {
            if (option.kind == kind) return option;
        }
        return defaults()[0];
    }

    @Override public String toString() { return label; }

    TimeWindow resolve(ZoneId zone, Instant customStart, Instant customEnd) {
        Instant now = Instant.now();
        ZonedDateTime localNow = now.atZone(zone);
        return switch (kind) {
            case SESSION -> new TimeWindow(Instant.EPOCH, now);
            case LAST_1_HOUR -> new TimeWindow(now.minus(Duration.ofHours(1)), now);
            case LAST_2_HOURS -> new TimeWindow(now.minus(Duration.ofHours(2)), now);
            case LAST_6_HOURS -> new TimeWindow(now.minus(Duration.ofHours(6)), now);
            case LAST_12_HOURS -> new TimeWindow(now.minus(Duration.ofHours(12)), now);
            case TODAY -> {
                Instant start = localNow.toLocalDate().atStartOfDay(zone).toInstant();
                yield new TimeWindow(start, now);
            }
            case YESTERDAY -> {
                LocalDate date = localNow.toLocalDate().minusDays(1);
                yield new TimeWindow(date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant());
            }
            case THIS_WEEK -> {
                LocalDate startDate = localNow.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                yield new TimeWindow(startDate.atStartOfDay(zone).toInstant(), now);
            }
            case LAST_7_DAYS -> new TimeWindow(now.minus(Duration.ofDays(7)), now);
            case LAST_30_DAYS -> new TimeWindow(now.minus(Duration.ofDays(30)), now);
            case LAST_6_MONTHS -> new TimeWindow(localNow.minusMonths(6).toInstant(), now);
            case LAST_1_YEAR -> new TimeWindow(localNow.minusYears(1).toInstant(), now);
            case LAST_2_YEARS -> new TimeWindow(localNow.minusYears(2).toInstant(), now);
            case LAST_5_YEARS -> new TimeWindow(localNow.minusYears(5).toInstant(), now);
            case CUSTOM -> {
                Instant start = customStart == null ? now.minus(Duration.ofDays(1)) : customStart;
                Instant end = customEnd == null ? now : customEnd;
                if (!end.isAfter(start)) end = start.plus(Duration.ofMinutes(1));
                yield new TimeWindow(start, end);
            }
        };
    }

    record TimeWindow(Instant start, Instant end) {}
}
