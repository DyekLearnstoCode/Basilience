package com.example.basilience;

import androidx.annotation.Nullable;

/**
 * Centralized near-threshold classification, reused by every report output
 * that needs to know whether a value is comfortably normal, approaching a
 * configured limit, or already outside it - the PDF's out-of-range findings,
 * the PDF's mini trend chart bands, and the XLSX control-chart zones all call
 * this one method instead of each inventing their own warning margin.
 *
 * <p>The only inputs are the same configured min/max bounds already driving
 * the on-screen dashed limit lines and red out-of-range coloring
 * (SystemReportsFragment#configuredRangeMin/Max) - this class never resolves
 * a threshold itself.
 */
public final class ThresholdZoneClassifier {

    /** Width of the yellow warning band, as a fraction of the acceptable range (max - min). */
    public static final float WARNING_MARGIN_RATIO = 0.15f;

    public enum Zone { GREEN, YELLOW, RED, NONE }

    private ThresholdZoneClassifier() {}

    /**
     * Classifies a value against the configured acceptable range.
     *
     * <p>Only the two-sided case (both min and max configured) currently
     * produces a warning band. Audited against the actual settings schema
     * (ParameterTargetRanges, ParameterTargetRangesFragment's Save
     * validation): every one of the six report parameters requires both a
     * min and a max before it can be saved, so none of them can genuinely
     * end up one-sided today. Returns NONE for a one-sided or unconfigured
     * range rather than inventing a one-sided warning rule with no real
     * requirement behind it - add that case if/when the settings schema
     * ever actually allows a single-bound parameter.
     */
    public static Zone classify(float value, @Nullable Float min, @Nullable Float max) {
        if (min == null || max == null) return Zone.NONE;
        if (value < min || value > max) return Zone.RED;

        float margin = marginFor(min, max);
        if (value < min + margin || value > max - margin) return Zone.YELLOW;
        return Zone.GREEN;
    }

    /** The yellow-band width for a two-sided range, shared by every renderer that draws zone boundaries. */
    public static float marginFor(float min, float max) {
        return (max - min) * WARNING_MARGIN_RATIO;
    }
}
