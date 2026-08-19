package com.example.basilience;

import android.annotation.SuppressLint;
import android.content.Context;
import android.widget.TextView;

import com.github.mikephil.charting.components.MarkerView;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.utils.MPPointF;

import java.util.List;

/**
 * Tap marker for the Accumulated Harvest chart.
 *
 * <p>Reuses the Parameter/Fogging marker layout so all three report charts
 * share one visual language. Presentation only: the label comes from the
 * same date-label list the x-axis already renders, and the value is the
 * plotted cumulative total - no data is queried or recomputed.
 */
@SuppressLint("ViewConstructor")
public class HarvestChartMarkerView extends MarkerView {

    private final TextView tvLabel;
    private final TextView tvValue;
    private final List<String> dateLabels;

    public HarvestChartMarkerView(Context context, List<String> dateLabels) {
        super(context, R.layout.marker_chart_value);
        this.dateLabels = dateLabels;
        tvLabel = findViewById(R.id.tvMarkerTime);
        tvValue = findViewById(R.id.tvMarkerValue);
    }

    @Override
    public void refreshContent(Entry e, Highlight highlight) {
        if (e != null) {
            int index = Math.round(e.getX());
            if (dateLabels != null && index >= 0 && index < dateLabels.size()) {
                tvLabel.setText(dateLabels.get(index));
            } else {
                tvLabel.setText("");
            }
            // Plotted y is the running cumulative weight in grams.
            tvValue.setText(HarvestFormatter.formatWeight(e.getY()) + " total");
        }
        super.refreshContent(e, highlight);
    }

    @Override
    public MPPointF getOffset() {
        return new MPPointF(-(getWidth() / 2f), -getHeight() - 8f);
    }
}
