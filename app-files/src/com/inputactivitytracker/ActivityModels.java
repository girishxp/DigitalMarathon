package com.inputactivitytracker;

import java.time.Instant;

final class ActivityModels {
    private ActivityModels() {}

    record Totals(double mousePixels, long keyPresses, long mouseClicks, double activeSeconds) {
        static Totals zero() { return new Totals(0.0, 0L, 0L, 0.0); }
        Totals plus(Totals other) {
            return new Totals(
                    mousePixels + other.mousePixels,
                    keyPresses + other.keyPresses,
                    mouseClicks + other.mouseClicks,
                    activeSeconds + other.activeSeconds);
        }
    }

    record Snapshot(
            Totals totals,
            boolean running,
            Instant sessionStart,
            String inputStatus,
            boolean globalKeysAvailable) {}

    record Group(String label, String shortLabel, Instant start, Instant end, Totals totals) {}
}
