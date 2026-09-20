package com.example.basilience;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.mikephil.charting.data.Entry;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a sensor series into contiguous in-range and out-of-range runs so a
 * line chart can colour only the offending stretches.
 *
 * <p>MPAndroidChart cannot colour parts of one {@code LineDataSet}, so the
 * series is cut into runs and each run becomes its own dataset. Runs are
 * contiguous - never one dataset per point - so a long report stays a handful
 * of datasets rather than hundreds.
 *
 * <p>Where the line crosses a threshold between two samples, a synthetic point
 * is inserted exactly at the crossing. The colour therefore changes at the
 * threshold itself rather than at the next sample, and because the crossing
 * point is shared by the runs on both sides the line has no gap.
 *
 * <p>Range test is inclusive at both ends: a reading exactly equal to the
 * minimum or the maximum is in range. Only {@code value < min} or
 * {@code value > max} is out of range.
 */
public final class ChartRangeSegmenter {

    /** One contiguous stretch of the series. */
    public static final class Segment {
        public final List<Entry> entries;
        /** True when this stretch lies outside the configured range. */
        public final boolean outOfRange;

        Segment(List<Entry> entries, boolean outOfRange) {
            this.entries = entries;
            this.outOfRange = outOfRange;
        }
    }

    private ChartRangeSegmenter() {}

    /**
     * @param entries series in x order; missing samples are simply absent and
     *                are never treated as out of range
     * @param min     lower bound, or {@code null} when the parameter has none
     * @param max     upper bound, or {@code null} when the parameter has none
     * @return contiguous segments covering the whole series. A series with no
     *         bounds at all yields a single in-range segment.
     */
    @NonNull
    public static List<Segment> segment(@NonNull List<Entry> entries,
                                        @Nullable Float min,
                                        @Nullable Float max) {
        List<Segment> segments = new ArrayList<>();
        if (entries.isEmpty()) return segments;

        if ((min == null && max == null) || entries.size() == 1) {
            // Nothing to compare against, or nothing to draw a line between.
            segments.add(new Segment(new ArrayList<>(entries), isOutOfRange(entries.get(0).getY(), min, max)));
            return segments;
        }

        // Expand the series with a point at every threshold crossing, so no
        // drawn segment ever straddles a boundary.
        List<Entry> expanded = new ArrayList<>(entries.size() + 8);
        for (int i = 0; i < entries.size() - 1; i++) {
            Entry a = entries.get(i);
            Entry b = entries.get(i + 1);
            expanded.add(a);

            List<Entry> crossings = new ArrayList<>(2);
            addCrossing(crossings, a, b, min);
            addCrossing(crossings, a, b, max);
            if (crossings.size() == 2 && crossings.get(1).getX() < crossings.get(0).getX()) {
                crossings.add(crossings.remove(0)); // keep them in x order
            }
            expanded.addAll(crossings);
        }
        expanded.add(entries.get(entries.size() - 1));

        // Group neighbouring pairs by whether the stretch between them is out
        // of range, judged at the midpoint - unambiguous now that no stretch
        // spans a threshold.
        List<Entry> current = new ArrayList<>();
        current.add(expanded.get(0));
        boolean currentOut = isOutOfRange(midpointY(expanded.get(0), expanded.get(1)), min, max);

        for (int i = 1; i < expanded.size(); i++) {
            Entry point = expanded.get(i);
            boolean stretchOut = i < expanded.size() - 1
                    ? isOutOfRange(midpointY(point, expanded.get(i + 1)), min, max)
                    : currentOut;

            current.add(point);

            if (i < expanded.size() - 1 && stretchOut != currentOut) {
                segments.add(new Segment(current, currentOut));
                // The boundary point starts the next run too, so the line is
                // continuous across the colour change.
                current = new ArrayList<>();
                current.add(point);
                currentOut = stretchOut;
            }
        }

        if (current.size() > 1 || segments.isEmpty()) {
            segments.add(new Segment(current, currentOut));
        }
        return segments;
    }

    /**
     * Averages consecutive entries down to roughly {@code maxPoints} points
     * when the raw series is larger than that, so MPAndroidChart stays fast
     * to render and pan/zoom/tap on a long, frequently-logged report -
     * returned unchanged otherwise. For the plotted line only: callers must
     * keep computing stats (average/high/low) and exports from the original,
     * non-downsampled readings, never from this result.
     *
     * @param entries   series in x order
     * @param maxPoints series at or below this size passes through untouched
     */
    @NonNull
    public static List<Entry> downsample(@NonNull List<Entry> entries, int maxPoints) {
        int size = entries.size();
        if (maxPoints <= 0 || size <= maxPoints) {
            return entries;
        }

        int stride = (int) Math.ceil(size / (double) maxPoints);
        List<Entry> result = new ArrayList<>((size + stride - 1) / stride);

        for (int start = 0; start < size; start += stride) {
            int end = Math.min(start + stride, size);
            float sumX = 0f;
            float sumY = 0f;
            for (int i = start; i < end; i++) {
                Entry e = entries.get(i);
                sumX += e.getX();
                sumY += e.getY();
            }
            int count = end - start;
            result.add(new Entry(sumX / count, sumY / count));
        }

        return result;
    }

    /** Inclusive at both bounds - see the class note. */
    public static boolean isOutOfRange(float value, @Nullable Float min, @Nullable Float max) {
        if (min != null && value < min) return true;
        return max != null && value > max;
    }

    private static float midpointY(Entry a, Entry b) {
        return (a.getY() + b.getY()) / 2f;
    }

    /** Adds the point where a-&gt;b crosses {@code threshold}, if it strictly does. */
    private static void addCrossing(List<Entry> out, Entry a, Entry b, @Nullable Float threshold) {
        if (threshold == null) return;

        float ya = a.getY();
        float yb = b.getY();
        // Strictly straddling only: a segment merely touching the threshold at
        // an endpoint needs no extra point.
        if ((ya < threshold && yb > threshold) || (ya > threshold && yb < threshold)) {
            float t = (threshold - ya) / (yb - ya);
            out.add(new Entry(a.getX() + t * (b.getX() - a.getX()), threshold));
        }
    }
}
