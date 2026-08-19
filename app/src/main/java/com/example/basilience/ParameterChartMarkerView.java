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
 * Tap marker for the Parameter Report trend chart.
 *
 * <p>Presentation only: it renders the entry that was already plotted and
 * derives the reading's timestamp from the same x-axis encoding the chart
 * itself uses (x = minutes elapsed since the filter's effective start), so
 * no additional data is queried, stored, or recomputed to support it.
 */
@SuppressLint("ViewConstructor")
public class ParameterChartMarkerView extends MarkerView {

    private final TextView tvTime;
    private final TextView tvValue;
    private final SimpleDateFormat timeFormat;
    private final long baseTimestampMs;
    private final String unit;
    private final int decimals;

    /**
     * @param baseTimestampMs the filter's effective start, i.e. the same
     *                        origin the chart's x values are measured from
     * @param unit            display unit already resolved for the parameter
     * @param decimals        decimal places to match the parameter's precision
     */
    public ParameterChartMarkerView(Context context, long baseTimestampMs, String unit,
                                    int decimals, String timeZoneId) {
        super(context, R.layout.marker_chart_value);
        this.baseTimestampMs = baseTimestampMs;
        this.unit = unit;
        this.decimals = decimals;
        this.timeFormat = new SimpleDateFormat("MMM d, h:mm a", Locale.getDefault());
        this.timeFormat.setTimeZone(TimeZone.getTimeZone(timeZoneId));
        tvTime = findViewById(R.id.tvMarkerTime);
        tvValue = findViewById(R.id.tvMarkerValue);
    }

    @Override
    public void refreshContent(Entry e, Highlight highlight) {
        if (e != null) {
            long timestampMs = baseTimestampMs + (long) (e.getX() * 60000f);
            tvTime.setText(timeFormat.format(new Date(timestampMs)));
            tvValue.setText(String.format(Locale.getDefault(), "%." + decimals + "f%s",
                    e.getY(), unit));
        }
        super.refreshContent(e, highlight);
    }

    @Override
    public MPPointF getOffset() {
        // Centre the bubble above the tapped point, clear of the data line.
        return new MPPointF(-(getWidth() / 2f), -getHeight() - 8f);
    }
}
