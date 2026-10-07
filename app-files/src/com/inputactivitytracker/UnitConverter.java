package com.inputactivitytracker;

import java.text.DecimalFormat;
import java.util.Locale;

final class UnitConverter {
    enum Unit {
        PIXELS("px"), MILLIMETRES("mm"), CENTIMETRES("cm"), METRES("m"), KILOMETRES("km"),
        INCHES("in"), FEET("ft"), YARDS("yd"), MILES("mi");

        private final String symbol;
        Unit(String symbol) { this.symbol = symbol; }
        String symbol() { return symbol; }
        @Override public String toString() {
            return switch (this) {
                case PIXELS -> "Pixels (px)";
                case MILLIMETRES -> "Millimetres (mm)";
                case CENTIMETRES -> "Centimetres (cm)";
                case METRES -> "Metres (m)";
                case KILOMETRES -> "Kilometres (km)";
                case INCHES -> "Inches (in)";
                case FEET -> "Feet (ft)";
                case YARDS -> "Yards (yd)";
                case MILES -> "Miles (mi)";
            };
        }
    }

    private UnitConverter() {}

    static double convert(double pixels, Unit unit, double ppi) {
        if (unit == Unit.PIXELS) return pixels;
        double safePpi = Math.max(1.0, ppi);
        double inches = pixels / safePpi;
        return switch (unit) {
            case PIXELS -> pixels;
            case MILLIMETRES -> inches * 25.4;
            case CENTIMETRES -> inches * 2.54;
            case METRES -> inches * 0.0254;
            case KILOMETRES -> inches * 0.0000254;
            case INCHES -> inches;
            case FEET -> inches / 12.0;
            case YARDS -> inches / 36.0;
            case MILES -> inches / 63360.0;
        };
    }

    static String formatDistance(double pixels, Unit unit, double ppi) {
        double value = convert(pixels, unit, ppi);
        double abs = Math.abs(value);
        String pattern;
        if (unit == Unit.PIXELS) pattern = abs >= 1000 ? "#,##0" : "0";
        else if (abs >= 1000) pattern = "#,##0.0";
        else if (abs >= 100) pattern = "0.0";
        else if (abs >= 10) pattern = "0.00";
        else if (abs >= 1) pattern = "0.000";
        else pattern = "0.0000";
        DecimalFormat format = (DecimalFormat) DecimalFormat.getNumberInstance(Locale.getDefault());
        format.applyPattern(pattern);
        return format.format(value) + " " + unit.symbol();
    }

    static String formatDuration(double seconds) {
        long total = Math.max(0L, Math.round(seconds));
        long hours = total / 3600;
        long minutes = (total % 3600) / 60;
        long secs = total % 60;
        if (hours > 0) return String.format(Locale.ROOT, "%dh %02dm %02ds", hours, minutes, secs);
        if (minutes > 0) return String.format(Locale.ROOT, "%dm %02ds", minutes, secs);
        return secs + "s";
    }
}
