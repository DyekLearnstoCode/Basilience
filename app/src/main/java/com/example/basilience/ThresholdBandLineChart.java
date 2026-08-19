package com.example.basilience;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.utils.Transformer;

import java.util.ArrayList;
import java.util.List;

/**
 * LineChart subclass that can paint very subtle, fixed-to-Y-value background
 * bands (e.g. "outside acceptable range" / "acceptable range") behind the
 * chart's normal grid/data/marker drawing.
 *
 * <p>Presentation only: bands are drawn directly onto the canvas before
 * MPAndroidChart's own onDraw() runs, so they always sit strictly beneath
 * grid lines, threshold LimitLines, the data line, and the marker - and are
 * clipped to the chart's content rect, so they never extend under axis
 * labels, the legend, or the title. No dataset/data is touched, and since
 * bands are part of the view's normal draw pass, getChartBitmap() (used for
 * PDF export) captures them automatically.
 *
 * <p>Bands are anchored to Y data-values, not X - they always span the full
 * visible plot width and are unaffected by X zoom/pan, matching the "min/max
 * thresholds don't move" expectation.
 */
public class ThresholdBandLineChart extends LineChart {

    /** One horizontal band, in data Y-coordinates. Use Float.NaN for an open (unbounded) edge. */
    public static final class Band {
        public final float yMin;
        public final float yMax;
        public final int color;

        public Band(float yMin, float yMax, int color) {
            this.yMin = yMin;
            this.yMax = yMax;
            this.color = color;
        }
    }

    private final List<Band> bands = new ArrayList<>();
    private final Paint bandPaint = new Paint();

    public ThresholdBandLineChart(Context context) {
        super(context);
    }

    public ThresholdBandLineChart(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ThresholdBandLineChart(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    /** Replaces the current bands. Pass null/empty to clear (e.g. no configured thresholds for this parameter). */
    public void setThresholdBands(List<Band> newBands) {
        bands.clear();
        if (newBands != null) bands.addAll(newBands);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        drawThresholdBands(canvas);
        super.onDraw(canvas);
    }

    private void drawThresholdBands(Canvas canvas) {
        if (bands.isEmpty() || getData() == null) return;

        RectF content = getViewPortHandler().getContentRect();
        if (content.width() <= 0 || content.height() <= 0) return;

        YAxis axisLeft = getAxisLeft();
        float axisTop = axisLeft.getAxisMaximum();
        float axisBottom = axisLeft.getAxisMinimum();
        if (axisTop <= axisBottom) return;

        Transformer transformer = getTransformer(YAxis.AxisDependency.LEFT);

        canvas.save();
        canvas.clipRect(content);
        for (Band band : bands) {
            float dataTop = Float.isNaN(band.yMax) ? axisTop : Math.min(band.yMax, axisTop);
            float dataBottom = Float.isNaN(band.yMin) ? axisBottom : Math.max(band.yMin, axisBottom);
            if (dataTop <= dataBottom) continue;

            float[] points = {0f, dataTop, 0f, dataBottom};
            transformer.pointValuesToPixel(points);

            bandPaint.setColor(band.color);
            bandPaint.setStyle(Paint.Style.FILL);
            canvas.drawRect(content.left, points[1], content.right, points[3], bandPaint);
        }
        canvas.restore();
    }
}
