package com.example.basilience;

import java.util.Locale;

/**
 * Human-readable duration formatting for Fogging Reports.
 *
 * <p>Display only. These helpers never alter the stored millisecond values
 * or any calculation - they exist so a runtime of 1,428 minutes reads as
 * "23h 48m" instead of "1428m". The same helpers are used by the report
 * screen, the Recent Activity list and the exported PDF, so a duration can
 * never be formatted one way on screen and another way in the export.
 */
public final class DurationFormatter {

    private DurationFormatter() { }

    /**
     * Total/aggregate runtime: "45m", "23h 48m", "3d 4h".
     * Used for total runtime, per-strategy runtime and elapsed running time.
     */
    public static String formatRuntime(long durationMs) {
        long totalMinutes = Math.max(0, durationMs) / 60000;
        if (totalMinutes < 60) {
            return totalMinutes + "m";
        }
        long totalHours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (totalHours < 24) {
            return totalHours + "h " + minutes + "m";
        }
        long days = totalHours / 24;
        long hours = totalHours % 24;
        return days + "d " + hours + "h";
    }

    /**
     * A single session's length: "42s", "5m 0s", and - once a session runs
     * past an hour - the same compact form as {@link #formatRuntime}, so an
     * unusually long session never renders as an unreadable minute count.
     */
    public static String formatSession(long durationMs) {
        long totalSeconds = Math.max(0, durationMs) / 1000;
        long totalMinutes = totalSeconds / 60;
        if (totalMinutes >= 60) {
            return formatRuntime(durationMs);
        }
        long seconds = totalSeconds % 60;
        return totalMinutes > 0
                ? String.format(Locale.getDefault(), "%dm %ds", totalMinutes, seconds)
                : String.format(Locale.getDefault(), "%ds", seconds);
    }

    /**
     * Compact Y-axis tick label for the Fogging Report's daily runtime chart:
     * "30 min", "1 hr", "1.5 hr", "2.25 hr". Unambiguous unit words ("min"/
     * "hr") rather than bare "m"/"h", which reads as metres. Daily fogging
     * runtime is bounded well under 24h by the existing anomaly handling, so
     * this never needs a day unit - hours is always the ceiling here.
     */
    public static String formatAxisMinutes(float minutesValue) {
        long totalMinutes = Math.round(Math.max(0f, minutesValue));
        if (totalMinutes < 60) {
            return totalMinutes + " min";
        }
        // Round to the nearest quarter-hour so the label never shows a long
        // decimal (e.g. 1.333333 hr) regardless of the tick value MPAndroidChart
        // picked; whole/half hours render with no more precision than needed.
        long quarterHours = Math.round(totalMinutes / 15.0);
        long wholeHours = quarterHours / 4;
        long remainder = quarterHours % 4;
        switch ((int) remainder) {
            case 1: return String.format(Locale.getDefault(), "%.2f hr", wholeHours + 0.25f);
            case 2: return String.format(Locale.getDefault(), "%.1f hr", wholeHours + 0.5f);
            case 3: return String.format(Locale.getDefault(), "%.2f hr", wholeHours + 0.75f);
            default: return wholeHours + " hr";
        }
    }

    /**
     * Explicit, farmer-readable duration for the Fogging chart's tap marker:
     * "42 min", "1 hr 18 min", "2 hr". Deliberately more verbose than
     * {@link #formatAxisMinutes} - the marker has room to spell out both
     * parts instead of rounding to a compact tick label.
     */
    public static String formatMarkerDuration(long durationMs) {
        long totalMinutes = Math.max(0, durationMs) / 60000;
        if (totalMinutes < 60) {
            return totalMinutes + " min";
        }
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        return minutes == 0
                ? hours + " hr"
                : hours + " hr " + minutes + " min";
    }
}
