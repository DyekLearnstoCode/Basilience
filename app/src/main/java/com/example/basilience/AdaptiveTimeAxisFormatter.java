package com.example.basilience;

import com.github.mikephil.charting.formatter.ValueFormatter;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * X-axis label formatter for continuous time-series report charts whose X
 * values are minutes elapsed since a fixed origin (e.g. Parameter Report's
 * trend chart, Fogging Report's bucketed runtime chart). Chooses how
 * precisely to render each label - exact time, hour-of-day, or date - and how
 * far apart labels must be, from the chart's CURRENT VISIBLE range rather
 * than the original full filter range, so labels stay useful and never
 * overlap whether the farmer is looking at the whole period or has zoomed
 * into a few hours of it.
 *
 * <p>Label spacing is snapped to "nice" human time steps (5m, 15m, 1h, 4h,
 * 1d, 7d, ...) rather than dividing the visible span evenly by a fixed label
 * count: an evenly-divided step can land on an awkward interval (e.g. 83
 * minutes) that MPAndroidChart may still round up to a *higher* rendered
 * label count than requested once axis granularity isn't enforced, which is
 * what caused labels to overlap on the Today view. Combining a nice-stepped
 * {@link #getGranularityMinutes()} (a hard floor via
 * {@code XAxis.setGranularity()}/{@code setGranularityEnabled(true)}) with
 * {@link #suggestedLabelCount()} (a soft target) keeps rendered label count
 * predictable and evenly spaced at any zoom level.
 *
 * <p>Purely a label-format/spacing decision: it never changes what X values
 * exist or how many are plotted. The caller is responsible for calling
 * {@link #updateVisibleRange} after any zoom/pan gesture (and once with the
 * full range right after loading new data), applying the resulting
 * granularity/label count to the chart's XAxis, and re-invalidating.
 */
public final class AdaptiveTimeAxisFormatter extends ValueFormatter {

    private static final long MINUTE_MS = 60_000L;
    private static final float HOUR_MINUTES = 60f;
    private static final float DAY_MINUTES = 24 * 60f;

    // "Nice" label spacing steps, in minutes, tried in increasing order until
    // one is large enough to keep roughly the target label count across the
    // current visible span without crowding on a ~360dp-wide chart.
    private static final float[] NICE_STEPS_MINUTES = {
            1, 2, 5, 10, 15, 30,
            60, 120, 180, 240, 360, 480, 720,
            DAY_MINUTES, 2 * DAY_MINUTES, 3 * DAY_MINUTES, 7 * DAY_MINUTES,
            14 * DAY_MINUTES, 30 * DAY_MINUTES, 60 * DAY_MINUTES
    };

    private final long baseStartMs;
    private final SimpleDateFormat fineFormat;   // h:mm a  - granularity < 1h
    private final SimpleDateFormat hourlyFormat; // h a     - granularity < 1d
    private final SimpleDateFormat dateFormat;   // MMM d   - granularity >= 1d

    private float visibleSpanMinutes = DAY_MINUTES;
    private float granularityMinutes = 240f; // 4h, until the first updateVisibleRange()

    public AdaptiveTimeAxisFormatter(long baseStartMs, String timeZoneId) {
        this.baseStartMs = baseStartMs;
        TimeZone tz = TimeZone.getTimeZone(timeZoneId);
        fineFormat = new SimpleDateFormat("h:mm a", Locale.getDefault());
        hourlyFormat = new SimpleDateFormat("h a", Locale.getDefault());
        dateFormat = new SimpleDateFormat("MMM d", Locale.getDefault());
        fineFormat.setTimeZone(tz);
        hourlyFormat.setTimeZone(tz);
        dateFormat.setTimeZone(tz);
    }

    /**
     * Recomputes label spacing/format from the chart's current visible X
     * range (lowestVisibleX/highestVisibleX from the chart, same minutes-
     * since-baseStartMs unit the X values use). Call after zoom/pan or right
     * after loading new data with the full range, then apply
     * {@link #getGranularityMinutes()}/{@link #suggestedLabelCount()} to the
     * XAxis and re-invalidate the chart.
     */
    public void updateVisibleRange(float lowestVisibleX, float highestVisibleX) {
        visibleSpanMinutes = Math.max(1f, highestVisibleX - lowestVisibleX);
        granularityMinutes = niceStep(visibleSpanMinutes, targetLabelCount(visibleSpanMinutes));
    }

    private static int targetLabelCount(float spanMinutes) {
        // Wider label text ("1:00 PM", "MMM d") at small/very-large spans
        // needs a little more breathing room than short hour-only labels.
        if (spanMinutes <= 6 * HOUR_MINUTES) return 5;        // fine time
        if (spanMinutes <= 36 * HOUR_MINUTES) return 6;       // hour-of-day
        if (spanMinutes <= 10 * DAY_MINUTES) return 5;        // daily dates
        return 4;                                              // compact dates
    }

    private static float niceStep(float spanMinutes, int targetCount) {
        float idealStep = spanMinutes / Math.max(1, targetCount);
        for (float step : NICE_STEPS_MINUTES) {
            if (step >= idealStep) return step;
        }
        return NICE_STEPS_MINUTES[NICE_STEPS_MINUTES.length - 1];
    }

    /** Hard floor on label/gridline spacing, in the chart's minutes-based X unit - apply via {@code XAxis.setGranularity()} + {@code setGranularityEnabled(true)}. */
    public float getGranularityMinutes() {
        return granularityMinutes;
    }

    /** Soft upper bound on label count; granularity above is what actually prevents overlap. */
    public int suggestedLabelCount() {
        int count = Math.round(visibleSpanMinutes / granularityMinutes) + 1;
        return Math.max(2, Math.min(8, count));
    }

    @Override
    public String getFormattedValue(float value) {
        Date date = new Date(baseStartMs + (long) (value * MINUTE_MS));
        if (granularityMinutes < HOUR_MINUTES) return fineFormat.format(date);
        if (granularityMinutes < DAY_MINUTES) return hourlyFormat.format(date);
        return dateFormat.format(date);
    }
}
