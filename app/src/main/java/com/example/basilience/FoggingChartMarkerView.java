package com.example.basilience;

import android.annotation.SuppressLint;
import android.content.Context;
import android.widget.TextView;

import com.github.mikephil.charting.components.MarkerView;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.utils.MPPointF;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Tap marker for the Daily Fogging Runtime bar chart.
 *
 * <p>Presentation only: derives the tapped bucket's date/time straight from
 * its X value (minutes elapsed since the chart's base timestamp - the same
 * encoding Parameter Report's marker uses), and the runtime from the bar's
 * own plotted Y value converted back to milliseconds - no additional data is
 * queried or recomputed.
 */
@SuppressLint("ViewConstructor")
public class FoggingChartMarkerView extends MarkerView {

    private final TextView tvLabel;
    private final TextView tvValue;
    private final SimpleDateFormat dateTimeFormat;
    private final long baseTimestampMs;

    /**
     * @param baseTimestampMs the chart's X origin (the report's effective start)
     * @param hourlyBuckets   true for hourly buckets (the "Today" filter), so
     *                        the marker shows time-of-day; false for daily
     *                        buckets, where only the date is meaningful.
     */
    public FoggingChartMarkerView(Context context, long baseTimestampMs, boolean hourlyBuckets, String timeZoneId) {
        super(context, R.layout.marker_chart_value);
        this.baseTimestampMs = baseTimestampMs;
        this.dateTimeFormat = new SimpleDateFormat(hourlyBuckets ? "h a" : "MMM d", Locale.getDefault());
        this.dateTimeFormat.setTimeZone(TimeZone.getTimeZone(timeZoneId));
        tvLabel = findViewById(R.id.tvMarkerTime);
        tvValue = findViewById(R.id.tvMarkerValue);
    }

    @Override
    public void refreshContent(Entry e, Highlight highlight) {
        if (e != null) {
            long timestampMs = baseTimestampMs + (long) (e.getX() * 60000f);
            tvLabel.setText(dateTimeFormat.format(new Date(timestampMs)));
            // The bar's value is runtime in minutes (see renderReport), so
            // convert back to ms purely to reuse the shared formatter.
            long durationMs = (long) (e.getY() * 60000f);
            tvValue.setText(DurationFormatter.formatMarkerDuration(durationMs));
        }
        super.refreshContent(e, highlight);
    }

    @Override
    public MPPointF getOffset() {
        return new MPPointF(-(getWidth() / 2f), -getHeight() - 8f);
    }
}
