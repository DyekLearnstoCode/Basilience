package com.example.basilience;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import com.example.basilience.models.ParameterExportBundle;
import com.github.mikephil.charting.components.LimitLine;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.utils.Transformer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The one control-chart rendering path shared by both exports: CycleReportGenerator
 * (PDF, embeds a smaller version per parameter block) and ExcelReportGenerator
 * (XLSX, embeds a larger version as the parameter sheet's main focus). Both
 * call this instead of each having their own drawing implementation, so a
 * PDF chart and an XLSX chart for the same data can never look different -
 * same bucket data, same zone-band math (ThresholdZoneClassifier), same
 * ThresholdBandLineChart drawing code. Only the target pixel size (and
 * whether an excursion annotation is passed) differs per caller.
 *
 * <p>Deliberately does NOT bake report context (period, aggregation interval,
 * zone legend) into the bitmap - that lives in worksheet cells / PDF text
 * around the chart instead, so the figure itself stays a clean standalone
 * line graph: border, axes, grid, threshold bands with labels, dashed
 * min/max limit lines, the line, point markers, and an optional worst-
 * excursion callout.
 */
public final class ControlChartRenderer {

    private static final String TIMEZONE_ID = "Asia/Manila";
    private static final int LINE_COLOR = Color.parseColor("#116F59");
    private static final int RED_COLOR = Color.parseColor("#C0392B");
    private static final int AMBER_COLOR = Color.parseColor("#B8860B");

    // Markers are shown at a calculated interval so a dense series (hundreds
    // of buckets) doesn't turn into a solid smear of overlapping dots, while
    // a sparse one gets a marker at every point. The underlying LINE always
    // plots every point regardless - only which points also get a dot changes.
    private static final float MIN_MARKER_SPACING_PX = 34f;

    private ControlChartRenderer() {}

    /** Buckets a bundle's raw samples for charting, using the shared aggregation ladder (ChartAggregation). */
    public static List<ChartAggregation.Bucket> aggregateForChart(ParameterExportBundle bundle,
                                                                    long effectiveStartMs, long effectiveEndMs) {
        long spanMs = Math.max(1L, effectiveEndMs - effectiveStartMs);
        long bucketSizeMs = ChartAggregation.bucketSizeMsFor(spanMs);
        return ChartAggregation.aggregate(bundle.samples, effectiveStartMs, bucketSizeMs, bundle.rangeMin, bundle.rangeMax);
    }

    /**
     * How many points to skip between markers so they stay legibly spaced
     * across the available width, e.g. a 700-point chart on a 1500px canvas
     * shows a dot roughly every {@code stride} points while the line itself
     * still connects all 700. Shared by the export renderer and the live
     * app chart so both use the same spacing rule.
     */
    public static int markerStride(int pointCount, int widthPx) {
        if (pointCount <= 1) return 1;
        int maxMarkers = Math.max(2, (int) (widthPx / MIN_MARKER_SPACING_PX));
        return Math.max(1, (int) Math.ceil(pointCount / (double) maxMarkers));
    }

    /**
     * Off-screen render of an aggregated trend chart with threshold zone
     * bands, dashed limit lines, an adaptively-spaced marker overlay, and an
     * optional worst-excursion callout, at the given pixel size. Returns
     * null when there is nothing to plot.
     */
    public static Bitmap render(Context context, ParameterExportBundle bundle, List<ChartAggregation.Bucket> buckets,
                                 int widthPx, int heightPx, @Nullable ChartAggregation.Excursion topExcursion) {
        if (buckets.isEmpty()) return null;

        List<Entry> entries = new ArrayList<>(buckets.size());
        long baseMs = buckets.get(0).bucketStartMs;
        float dataLow = Float.POSITIVE_INFINITY;
        float dataHigh = Float.NEGATIVE_INFINITY;
        for (ChartAggregation.Bucket b : buckets) {
            float xMinutes = (b.bucketStartMs - baseMs) / 60000f;
            entries.add(new Entry(xMinutes, b.mean));
            // The axis is scaled to each bucket's actual min/max, not just its
            // plotted mean - a brief excursion that got averaged toward normal
            // must still fit on screen, or the whisker drawn for it later
            // would be clipped right when it matters most.
            if (b.min < dataLow) dataLow = b.min;
            if (b.max > dataHigh) dataHigh = b.max;
        }

        float axisTextSize = Math.max(18f, heightPx * 0.025f);
        float lineWidth = Math.max(2f, heightPx * 0.005f);
        float circleRadius = Math.max(3f, heightPx * 0.008f);

        ThresholdBandLineChart chart = new ThresholdBandLineChart(context);
        chart.setLayoutParams(new ViewGroup.LayoutParams(widthPx, heightPx));

        LineDataSet lineSet = new LineDataSet(entries, bundle.displayParameter);
        lineSet.setColor(LINE_COLOR);
        lineSet.setLineWidth(lineWidth);
        lineSet.setDrawCircles(false);
        lineSet.setDrawValues(false);
        lineSet.setHighlightEnabled(false);

        List<com.github.mikephil.charting.interfaces.datasets.ILineDataSet> dataSets = new ArrayList<>(2);
        dataSets.add(lineSet);

        int stride = markerStride(entries.size(), widthPx);
        List<Entry> markerEntries = new ArrayList<>();
        List<Integer> markerColors = new ArrayList<>();
        for (int i = 0; i < buckets.size(); i += stride) {
            markerEntries.add(entries.get(i));
            markerColors.add(zoneMarkerColor(buckets.get(i).zone));
        }
        // Always include the final point so the series doesn't visually end mid-air.
        int lastIndex = buckets.size() - 1;
        if ((lastIndex % stride) != 0) {
            markerEntries.add(entries.get(lastIndex));
            markerColors.add(zoneMarkerColor(buckets.get(lastIndex).zone));
        }
        LineDataSet markerSet = new LineDataSet(markerEntries, "");
        markerSet.setColor(Color.TRANSPARENT);
        markerSet.setLineWidth(0f);
        markerSet.setDrawCircles(true);
        markerSet.setCircleRadius(circleRadius);
        markerSet.setDrawCircleHole(false);
        markerSet.setCircleColors(markerColors);
        markerSet.setDrawValues(false);
        markerSet.setHighlightEnabled(false);
        dataSets.add(markerSet);

        chart.setData(new LineData(dataSets));
        chart.getDescription().setEnabled(false);
        chart.getLegend().setEnabled(false);
        chart.setTouchEnabled(false);
        chart.setDrawGridBackground(false);

        // Frame around the plot area - a proper report figure, not a
        // borderless screenshot of a floating line.
        chart.setDrawBorders(true);
        chart.setBorderColor(Color.parseColor("#B0B0B0"));
        chart.setBorderWidth(1f);

        YAxis axisLeft = chart.getAxisLeft();
        axisLeft.setTextSize(axisTextSize);
        chart.getAxisRight().setEnabled(false);

        if (bundle.rangeMin != null && bundle.rangeMax != null) {
            float margin = ThresholdZoneClassifier.marginFor(bundle.rangeMin, bundle.rangeMax);
            float axisFloor = Math.min(dataLow, bundle.rangeMin) - margin;
            float axisCeiling = Math.max(dataHigh, bundle.rangeMax) + margin;
            chart.setThresholdBands(buildThresholdBands(bundle.rangeMin, bundle.rangeMax));
            axisLeft.setAxisMinimum(axisFloor);
            axisLeft.setAxisMaximum(axisCeiling);
            addLimitLines(axisLeft, bundle, axisTextSize * 0.85f);
        } else {
            axisLeft.resetAxisMinimum();
            axisLeft.resetAxisMaximum();
        }

        XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setTextSize(axisTextSize);
        AdaptiveTimeAxisFormatter formatter = new AdaptiveTimeAxisFormatter(baseMs, TIMEZONE_ID);
        long lastMs = buckets.get(buckets.size() - 1).bucketStartMs;
        float fullSpanMinutes = Math.max(1f, (lastMs - baseMs) / 60000f);
        formatter.updateVisibleRange(0f, fullSpanMinutes);
        xAxis.setValueFormatter(formatter);
        xAxis.setGranularity(formatter.getGranularityMinutes());
        xAxis.setGranularityEnabled(true);
        xAxis.setLabelCount(formatter.suggestedLabelCount(), false);
        chart.setExtraBottomOffset(heightPx * 0.02f);
        chart.setExtraTopOffset(heightPx * 0.015f);
        chart.setExtraLeftOffset(heightPx * 0.01f);
        chart.setExtraRightOffset(heightPx * 0.02f);

        int widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY);
        chart.measure(widthSpec, heightSpec);
        chart.layout(0, 0, widthPx, heightPx);

        Bitmap bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888);
        Canvas bitmapCanvas = new Canvas(bitmap);
        bitmapCanvas.drawColor(Color.WHITE);
        chart.draw(bitmapCanvas);

        // A bucket's plotted point is its mean, which can sit well inside
        // the normal band even when the bucket genuinely contained a
        // near-threshold or out-of-range raw reading (that's what its zone
        // reflects). A whisker from the bucket's real min to its real max
        // makes that excursion visible instead of averaging it away.
        drawBucketWhiskers(bitmapCanvas, chart, buckets, baseMs);

        if (topExcursion != null) {
            drawExcursionAnnotation(bitmapCanvas, chart, bundle, baseMs, topExcursion, axisTextSize);
        }

        return bitmap;
    }

    private static void drawBucketWhiskers(Canvas canvas, ThresholdBandLineChart chart,
                                            List<ChartAggregation.Bucket> buckets, long baseMs) {
        Transformer transformer = chart.getTransformer(YAxis.AxisDependency.LEFT);
        android.graphics.RectF content = chart.getViewPortHandler().getContentRect();
        Paint whiskerPaint = new Paint();
        whiskerPaint.setAntiAlias(true);
        whiskerPaint.setStrokeWidth(Math.max(1.5f, content.height() * 0.004f));

        for (ChartAggregation.Bucket b : buckets) {
            if (b.zone != ThresholdZoneClassifier.Zone.YELLOW && b.zone != ThresholdZoneClassifier.Zone.RED) continue;
            if (b.max - b.min < 1e-6f) continue;

            float xMinutes = (b.bucketStartMs - baseMs) / 60000f;
            float[] topPt = {xMinutes, b.max};
            float[] bottomPt = {xMinutes, b.min};
            transformer.pointValuesToPixel(topPt);
            transformer.pointValuesToPixel(bottomPt);
            if (topPt[0] < content.left || topPt[0] > content.right) continue;

            whiskerPaint.setColor(b.zone == ThresholdZoneClassifier.Zone.RED ? RED_COLOR : AMBER_COLOR);
            canvas.drawLine(topPt[0], Math.max(topPt[1], content.top), bottomPt[0], Math.min(bottomPt[1], content.bottom), whiskerPaint);
        }
    }

    /** Dashed Min/Max lines with numeric labels, matching the on-screen chart's own limit lines. */
    private static void addLimitLines(YAxis axisLeft, ParameterExportBundle bundle, float textSize) {
        axisLeft.removeAllLimitLines();
        int decimals = bundle.decimals;
        String minLabel = "Min " + String.format(Locale.getDefault(), "%." + decimals + "f", bundle.rangeMin);
        String maxLabel = "Max " + String.format(Locale.getDefault(), "%." + decimals + "f", bundle.rangeMax);
        axisLeft.addLimitLine(buildLimitLine(bundle.rangeMin, minLabel, textSize));
        axisLeft.addLimitLine(buildLimitLine(bundle.rangeMax, maxLabel, textSize));
    }

    private static LimitLine buildLimitLine(float value, String label, float textSize) {
        LimitLine line = new LimitLine(value, label);
        line.setLineColor(RED_COLOR);
        line.setLineWidth(1f);
        line.enableDashedLine(6f, 4f, 0f);
        line.setTextColor(RED_COLOR);
        line.setTextSize(textSize);
        line.setLabelPosition(LimitLine.LimitLabelPosition.RIGHT_TOP);
        return line;
    }

    /** Same centralized margin (ThresholdZoneClassifier) every export/report output uses - a zone can never disagree between the PDF and XLSX, or with the on-screen chart's own coloring. */
    private static List<ThresholdBandLineChart.Band> buildThresholdBands(float min, float max) {
        List<ThresholdBandLineChart.Band> bands = new ArrayList<>();
        float margin = ThresholdZoneClassifier.marginFor(min, max);
        int red = Color.parseColor("#F8D7DA");
        int yellow = Color.parseColor("#FFF3CD");
        int green = Color.parseColor("#E8F5EA");
        bands.add(new ThresholdBandLineChart.Band(Float.NaN, min, red, "Out of Range"));
        bands.add(new ThresholdBandLineChart.Band(min, min + margin, yellow, "Near Threshold"));
        bands.add(new ThresholdBandLineChart.Band(min + margin, max - margin, green, "Normal"));
        bands.add(new ThresholdBandLineChart.Band(max - margin, max, yellow, "Near Threshold"));
        bands.add(new ThresholdBandLineChart.Band(max, Float.NaN, red, "Out of Range"));
        return bands;
    }

    private static int zoneMarkerColor(ThresholdZoneClassifier.Zone zone) {
        switch (zone) {
            case RED: return RED_COLOR;
            case YELLOW: return AMBER_COLOR;
            default: return LINE_COLOR;
        }
    }

    /** Highlights the single worst out-of-range reading directly on the figure - the "screenshot" complaint was partly that a real spike was invisible unless you already knew to look for it. */
    private static void drawExcursionAnnotation(Canvas canvas, ThresholdBandLineChart chart, ParameterExportBundle bundle,
                                                 long baseMs, ChartAggregation.Excursion excursion, float textSize) {
        float xMinutes = (excursion.peakTimestampMs - baseMs) / 60000f;
        Transformer transformer = chart.getTransformer(YAxis.AxisDependency.LEFT);
        float[] pts = {xMinutes, excursion.peakValue};
        transformer.pointValuesToPixel(pts);

        android.graphics.RectF content = chart.getViewPortHandler().getContentRect();
        if (!content.contains(pts[0], pts[1])) return;

        Paint ringPaint = new Paint();
        ringPaint.setAntiAlias(true);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(textSize * 0.18f);
        ringPaint.setColor(RED_COLOR);
        float ringRadius = textSize * 0.9f;
        canvas.drawCircle(pts[0], pts[1], ringRadius, ringPaint);

        String label = String.format(Locale.getDefault(), "Peak %." + bundle.decimals + "f%s", excursion.peakValue, bundle.unit);
        Paint textPaint = new Paint();
        textPaint.setAntiAlias(true);
        textPaint.setColor(RED_COLOR);
        textPaint.setFakeBoldText(true);
        textPaint.setTextSize(textSize);
        float labelX = Math.min(pts[0] + ringRadius + 6f, content.right - textPaint.measureText(label) - 4f);
        float labelY = Math.max(pts[1] - ringRadius, content.top + textSize);
        canvas.drawText(label, labelX, labelY, textPaint);
    }
}
