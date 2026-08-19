package com.example.basilience;

import java.util.Locale;

/**
 * Display helpers for Harvest Reports.
 *
 * <p>Presentation only. Harvest weights are stored and calculated in grams
 * throughout (see the transactional totals on the cycle document); nothing
 * here alters a stored value or any arithmetic. These helpers exist so the
 * hero, chart marker, history rows and exported PDF cannot format the same
 * weight - or describe the same cycle - two different ways.
 */
public final class HarvestFormatter {

    private static final double GRAMS_PER_KILOGRAM = 1000.0;

    private HarvestFormatter() { }

    /**
     * Farmer-facing weight: grams below a kilogram ("850 g"), kilograms at or
     * above one ("4.85 kg"). Trailing zeros are trimmed so the value reads
     * naturally rather than as "850.0 g" or "4.00 kg".
     */
    public static String formatWeight(double grams) {
        double safeGrams = Math.max(0, grams);
        if (safeGrams >= GRAMS_PER_KILOGRAM) {
            return trimTrailingZeros(String.format(Locale.getDefault(), "%.2f",
                    safeGrams / GRAMS_PER_KILOGRAM)) + " kg";
        }
        return trimTrailingZeros(String.format(Locale.getDefault(), "%.1f", safeGrams)) + " g";
    }

    private static String trimTrailingZeros(String formatted) {
        if (formatted.indexOf('.') < 0 && formatted.indexOf(',') < 0) return formatted;
        String trimmed = formatted;
        while (trimmed.endsWith("0")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.endsWith(".") || trimmed.endsWith(",")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /** "7 harvest entries" / "1 harvest entry". */
    public static String formatEntryCount(int count) {
        return count == 1 ? "1 harvest entry" : count + " harvest entries";
    }

    /**
     * Plain-English production summary shown under "What This Means" on
     * screen and printed as the PDF's interpretation - one source, so the
     * two can never disagree.
     *
     * <p>Deliberately descriptive only. It reports what was recorded and
     * nothing more: there is no expected-yield baseline anywhere in this app
     * to compare against, so it never characterises production as good, bad,
     * high or low, and never comments on how the basil grew.
     *
     * @param status            cycle status, e.g. ACTIVE or COMPLETED
     * @param totalWeightGrams  the cycle's transactionally maintained total
     * @param harvestCount      the cycle's transactionally maintained count
     */
    public static String buildProductionSummary(String status, double totalWeightGrams, int harvestCount) {
        if (harvestCount <= 0 || totalWeightGrams <= 0) {
            return "No harvest has been recorded for this cycle yet.";
        }

        String weight = formatWeight(totalWeightGrams);
        String harvests = harvestCount == 1 ? "1 recorded harvest" : harvestCount + " recorded harvests";

        if ("COMPLETED".equalsIgnoreCase(status)) {
            return "This cycle produced a total of " + weight + " of harvested basil across "
                    + harvests + ".";
        }
        return "This cycle has recorded " + weight + " of harvested basil so far across "
                + formatEntryCount(harvestCount)
                + ". The total will continue to increase as more harvests are recorded.";
    }
}
