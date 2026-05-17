package com.example.basilience;

import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.util.ArrayList;
import java.util.List;

public class SystemReportsFragment extends Fragment {

    private Spinner spinnerParameter;
    private LineChart lineChart;
    private TextView tvAverage, tvHigh, tvLow;
    private Database_Helper dbHelper;

    public SystemReportsFragment() { }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.reports_system, container, false);

        dbHelper = new Database_Helper();
        spinnerParameter = view.findViewById(R.id.spinnerParameter);
        lineChart = view.findViewById(R.id.lineChart);
        tvAverage = view.findViewById(R.id.tvAverage);
        tvHigh = view.findViewById(R.id.tvHigh);
        tvLow = view.findViewById(R.id.tvLow);

        View btnBack = view.findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setVisibility(View.VISIBLE);
            NavController navController = NavHostFragment.findNavController(this);
            btnBack.setOnClickListener(v -> navController.popBackStack());
        }

        spinnerParameter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                String parameter = String.valueOf(parent.getItemAtPosition(position));
                loadReportData(parameter);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        // Optional: load initial parameter so chart isn't blank
        if (spinnerParameter.getSelectedItem() != null) {
            loadReportData(String.valueOf(spinnerParameter.getSelectedItem()));
        }

        return view;
    }

    private void loadReportData(String parameter) {
        long endTime = System.currentTimeMillis();
        long startTime = endTime - (24 * 60 * 60 * 1000); // Last 24 hours

        dbHelper.resolveDataUid().addOnSuccessListener(uid -> {
            if (uid != null) {
                dbHelper.setTargetUid(uid);
                dbHelper.getParameterLogs(parameter, startTime, endTime)
                        .addOnSuccessListener(queryDocumentSnapshots -> {
                            List<Entry> entries = new ArrayList<>();
                            float sum = 0f;
                            float high = Float.NEGATIVE_INFINITY;
                            float low = Float.POSITIVE_INFINITY;

                            int index = 0;
                            for (QueryDocumentSnapshot doc : queryDocumentSnapshots) {
                                Double value = doc.getDouble("value");
                                if (value != null) {
                                    float val = value.floatValue();
                                    entries.add(new Entry(index++, val));
                                    sum += val;
                                    if (val > high) high = val;
                                    if (val < low) low = val;
                                }
                            }

                            if (entries.isEmpty()) {
                                lineChart.clear();
                                tvAverage.setText("Average: --");
                                tvHigh.setText("High: --");
                                tvLow.setText("Low: --");
                                return;
                            }

                            LineDataSet dataSet = new LineDataSet(entries, parameter);
                            dataSet.setColor(getResources().getColor(R.color.primary));
                            dataSet.setCircleColor(getResources().getColor(R.color.primary));
                            dataSet.setValueTextColor(Color.BLACK);
                            dataSet.setLineWidth(2f);
                            dataSet.setDrawCircles(true);

                            lineChart.setData(new LineData(dataSet));
                            lineChart.getDescription().setEnabled(false);
                            lineChart.getLegend().setEnabled(true);
                            lineChart.invalidate();

                            float avg = sum / entries.size();
                            String unit = "";
                            if (parameter.equalsIgnoreCase("Temperature")) unit = "°C";
                            else if (parameter.equalsIgnoreCase("Humidity")) unit = "%";
                            else if (parameter.equalsIgnoreCase("pH")) unit = "";
                            else if (parameter.equalsIgnoreCase("EC")) unit = " mS/cm";

                            tvAverage.setText(String.format("%.1f%s", avg, unit));
                            tvHigh.setText(String.format("%.1f%s", high, unit));
                            tvLow.setText(String.format("%.1f%s", low, unit));
                        });
            }
        });
    }
}