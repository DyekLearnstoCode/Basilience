package com.example.basilience;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared time-bucket aggregation for report charts (the PDF's mini trend
 * images and the XLSX control charts) and for finding the most notable
 * out-of-range excursions to call out in the PDF's findings list.
 *
 * <p>Bucket size is chosen from a fixed ladder anchored to the same
 * breakpoints already used by the on-screen period chips (Today = 5-minute
 * raw readings, 7 Days = 15-minute averages, 30 Days = 1-hour averages),
 * extended for longer Entire Cycle/Custom spans so a multi-month report
 * never produces thousands of chart points.
 *
 * <p>Aggregation only ever affects what gets DRAWN. Raw readings and every
 * stat (average/high/low/% compliance) are computed separately from the full
 * unaggregated set - see SystemReportsFragment's currentReadings/
 * currentParameterSamples.
 */
public final class ChartAggregation {

    private ChartAggregation() {}

    private static final long MINUTE_MS = 60_000L;
    private static final long HOUR_MS = 60 * MINUTE_MS;
    private static final long DAY_MS = 24 * HOUR_MS;

    /** One raw (timestamp, value) sample to aggregate. */
    public static final class Sample {
        public final long timestampMs;
        public final float value;

        public Sample(long timestampMs, float value) {
            this.timestampMs = timestampMs;
            this.value = value;
        }
    }

    /** One aggregated, calendar-aligned bucket ready to plot. */
    public static final class Bucket {
        public final long bucketStartMs;
        public final float mean;
        public final float min;
        public final float max;
        public final int count;
        /** Worst zone among the RAW samples in this bucket, not the averaged mean - a brief spike must not be smoothed away. */
        public final ThresholdZoneClassifier.Zone zone;

        Bucket(long bucketStartMs, float mean, float min, float max, int count, ThresholdZoneClassifier.Zone zone) {
            this.bucketStartMs = bucketStartMs;
            this.mean = mean;
            this.min = min;
            this.max = max;
            this.count = count;
            this.zone = zone;
        }
    }

    /** One contiguous run of out-of-range raw readings. */
    public static final class Excursion {
        public final long startMs;
        public final long endMs;
        public final float peakValue;
        /** How far the worst reading in this run got outside the configured bound. */
        public final float peakDeviation;
        /** Timestamp of the specific raw sample that produced peakValue/peakDeviation - for placing a chart annotation exactly at the worst reading, not just somewhere in the run. */
        public final long peakTimestampMs;

        Excursion(long startMs, long endMs, float peakValue, float peakDeviation, long peakTimestampMs) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.peakValue = peakValue;
            this.peakDeviation = peakDeviation;
            this.peakTimestampMs = peakTimestampMs;
        }
    }

    /**
     * The bucket size for a report spanning spanMs, per the documented
     * ladder: &lt;=1 day -&gt; 5 min, &lt;=7 days -&gt; 15 min, &lt;=30 days -&gt; 1 hr,
     * &lt;=90 days -&gt; 3 hr, &lt;=180 days -&gt; 6 hr, &lt;=365 days -&gt; 1 day,
     * otherwise 3 days.
     */
    // The finest rung of the ladder equals the raw logging interval
    // (SENSOR_LOG_INTERVAL_MS in functions/index.js) - a bucket this size
    // holds at most one raw reading, so it is never actually an average.
    // describeInterval() below uses this same constant to decide whether to
    // say "readings" or "averages", so the two can never disagree.
    private static final long FINEST_BUCKET_MS = 5 * MINUTE_MS;

    public static long bucketSizeMsFor(long spanMs) {
        if (spanMs <= DAY_MS) return FINEST_BUCKET_MS;
        if (spanMs <= 7 * DAY_MS) return 15 * MINUTE_MS;
        if (spanMs <= 30 * DAY_MS) return HOUR_MS;
        if (spanMs <= 90 * DAY_MS) return 3 * HOUR_MS;
        if (spanMs <= 180 * DAY_MS) return 6 * HOUR_MS;
        if (spanMs <= 365 * DAY_MS) return DAY_MS;
        return 3 * DAY_MS;
    }

    /**
     * A human description of a bucket size, e.g. "5-minute readings" or
     * "15-minute averages" - "readings" only at the finest (raw-resolution)
     * rung, where a bucket holds at most one raw sample and calling it an
     * "average" would overstate what actually happened; every coarser rung
     * genuinely groups multiple raw readings, so "averages" is accurate there.
     */
    public static String describeInterval(long bucketSizeMs) {
        long minutes = bucketSizeMs / MINUTE_MS;
        String magnitude;
        if (minutes < 60) {
            magnitude = minutes + "-minute";
        } else if (minutes < 24 * 60) {
            magnitude = (minutes / 60) + "-hour";
        } else {
            magnitude = (minutes / (24 * 60)) + "-day";
        }
        String noun = bucketSizeMs <= FINEST_BUCKET_MS ? "readings" : "averages";
        return magnitude + " " + noun;
    }

    /**
     * Buckets raw samples into calendar-aligned windows of bucketSizeMs
     * starting at rangeStartMs, averaging each bucket's value and keeping its
     * min/max and worst-case threshold zone from the RAW samples it
     * contains - never from the averaged mean, so a bucket whose average
     * looks fine but contains one out-of-range spike still renders as such.
     * Empty buckets are omitted (nothing to plot). Samples need not be
     * pre-sorted.
     */
    public static List<Bucket> aggregate(List<Sample> samples, long rangeStartMs, long bucketSizeMs,
                                         @Nullable Float rangeMin, @Nullable Float rangeMax) {
        List<Bucket> result = new ArrayList<>();
        if (samples.isEmpty() || bucketSizeMs <= 0) return result;

        List<Sample> sorted = new ArrayList<>(samples);
        sorted.sort((a, b) -> Long.compare(a.timestampMs, b.timestampMs));

        int i = 0;
        int n = sorted.size();
        while (i < n) {
            long bucketIndex = Math.floorDiv(sorted.get(i).timestampMs - rangeStartMs, bucketSizeMs);
            long bucketStart = rangeStartMs + bucketIndex * bucketSizeMs;
            long bucketEnd = bucketStart + bucketSizeMs;

            float sum = 0f;
            float min = Float.POSITIVE_INFINITY;
            float max = Float.NEGATIVE_INFINITY;
            int count = 0;
            ThresholdZoneClassifier.Zone worst = ThresholdZoneClassifier.Zone.NONE;

            while (i < n && sorted.get(i).timestampMs < bucketEnd) {
                float v = sorted.get(i).value;
                sum += v;
                if (v < min) min = v;
                if (v > max) max = v;
                count++;
                ThresholdZoneClassifier.Zone z = ThresholdZoneClassifier.classify(v, rangeMin, rangeMax);
                if (rank(z) > rank(worst)) worst = z;
                i++;
            }

            if (count > 0) {
                result.add(new Bucket(bucketStart, sum / count, min, max, count, worst));
            }
        }
        return result;
    }

    /** RED &gt; YELLOW &gt; GREEN &gt; NONE, so one bad raw reading in a bucket is never masked by the rest. */
    private static int rank(ThresholdZoneClassifier.Zone z) {
        switch (z) {
            case RED: return 3;
            case YELLOW: return 2;
            case GREEN: return 1;
            default: return 0;
        }
    }

    /**
     * The most notable out-of-range excursions in the raw samples, ranked by
     * how far outside the configured range the worst reading in each run got
     * - not just how long it lasted, since a brief severe spike is at least
     * as reportable as a long mild one. Capped at maxResults so the PDF's
     * findings list can never grow unbounded on a long report. Returns an
     * empty list when no range is configured (nothing to be out of range of).
     */
    public static List<Excursion> findExcursions(List<Sample> samples, @Nullable Float min, @Nullable Float max,
                                                  int maxResults) {
        List<Excursion> excursions = new ArrayList<>();
        if (min == null || max == null || samples.isEmpty()) return excursions;

        List<Sample> sorted = new ArrayList<>(samples);
        sorted.sort((a, b) -> Long.compare(a.timestampMs, b.timestampMs));

        long runStart = -1;
        long runEnd = -1;
        float peakValue = 0f;
        float peakDeviation = -1f;
        long peakTimestampMs = -1L;

        for (Sample s : sorted) {
            boolean outOfRange = ThresholdZoneClassifier.classify(s.value, min, max) == ThresholdZoneClassifier.Zone.RED;
            if (outOfRange) {
                float deviation = deviationFrom(s.value, min, max);
                if (runStart < 0) {
                    runStart = s.timestampMs;
                    peakDeviation = -1f;
                }
                runEnd = s.timestampMs;
                if (deviation > peakDeviation) {
                    peakDeviation = deviation;
                    peakValue = s.value;
                    peakTimestampMs = s.timestampMs;
                }
            } else if (runStart >= 0) {
                excursions.add(new Excursion(runStart, runEnd, peakValue, peakDeviation, peakTimestampMs));
                runStart = -1;
            }
        }
        if (runStart >= 0) {
            excursions.add(new Excursion(runStart, runEnd, peakValue, peakDeviation, peakTimestampMs));
        }

        excursions.sort((a, b) -> Float.compare(b.peakDeviation, a.peakDeviation));
        if (excursions.size() > maxResults) {
            excursions = new ArrayList<>(excursions.subList(0, maxResults));
        }
        return excursions;
    }

    private static float deviationFrom(float value, float min, float max) {
        float dev = 0f;
        if (value < min) dev = Math.max(dev, min - value);
        if (value > max) dev = Math.max(dev, value - max);
        return dev;
    }
}
