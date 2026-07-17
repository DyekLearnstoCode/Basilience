package com.example.basilience;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class SystemReportsFragment extends Fragment {

    private Spinner spinnerParameter;
    private LineChart lineChart;
    private TextView tvAverage, tvHigh, tvLow;
    private Database_Helper dbHelper;

    private MaterialButton btnToday, btnWeek, btnMonth, btnYear;
    private ImageButton btnShare;
    private String currentSelectedFilter = "Today";
    private String selectedDeviceId;

    public SystemReportsFragment() { }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.reports_system, container, false);

        dbHelper = new Database_Helper();

        if (getArguments() != null) {
            selectedDeviceId = getArguments().getString("deviceId");
        }

        if (selectedDeviceId == null || selectedDeviceId.isEmpty()) {
            selectedDeviceId = "BSLN-9X2A-K47P";
        }

        spinnerParameter = view.findViewById(R.id.spinnerParameter);
        lineChart = view.findViewById(R.id.lineChart);
        tvAverage = view.findViewById(R.id.tvAverage);
        tvHigh = view.findViewById(R.id.tvHigh);
        tvLow = view.findViewById(R.id.tvLow);

        btnToday = view.findViewById(R.id.btnToday);
        btnWeek = view.findViewById(R.id.btnWeek);
        btnMonth = view.findViewById(R.id.btnMonth);
        btnYear = view.findViewById(R.id.btnYear);
        btnShare = view.findViewById(R.id.btnShare);

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
                loadReportData(parameter, currentSelectedFilter);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        btnToday.setOnClickListener(v -> updateFilterSelection("Today"));
        btnWeek.setOnClickListener(v -> updateFilterSelection("Week"));
        btnMonth.setOnClickListener(v -> updateFilterSelection("Month"));
        btnYear.setOnClickListener(v -> updateFilterSelection("Year"));

        // Dito tinatawag ang totoong CSV export functionality
        if (btnShare != null) {
            btnShare.setOnClickListener(v -> exportDataToCSV());
        }

        if (spinnerParameter.getSelectedItem() != null) {
            loadReportData(String.valueOf(spinnerParameter.getSelectedItem()), currentSelectedFilter);
        }

        return view;
    }

    private void updateFilterSelection(String selectedFilter) {
        currentSelectedFilter = selectedFilter;

        btnToday.setBackgroundColor(Color.TRANSPARENT);
        btnWeek.setBackgroundColor(Color.TRANSPARENT);
        btnMonth.setBackgroundColor(Color.TRANSPARENT);
        btnYear.setBackgroundColor(Color.TRANSPARENT);

        btnToday.setTextColor(Color.BLACK);
        btnWeek.setTextColor(Color.BLACK);
        btnMonth.setTextColor(Color.BLACK);
        btnYear.setTextColor(Color.BLACK);

        MaterialButton activeBtn;
        switch (selectedFilter) {
            case "Week": activeBtn = btnWeek; break;
            case "Month": activeBtn = btnMonth; break;
            case "Year": activeBtn = btnYear; break;
            default: activeBtn = btnToday; break;
        }

        activeBtn.setBackgroundColor(getResources().getColor(R.color.primary));
        activeBtn.setTextColor(Color.WHITE);

        if (spinnerParameter.getSelectedItem() != null) {
            loadReportData(String.valueOf(spinnerParameter.getSelectedItem()), currentSelectedFilter);
        }
    }

    // Helper method para kalkulahin ang simula ng time base sa filter
    private long getStartTimeForFilter(String filter, long endTime) {
        switch (filter) {
            case "Week": return endTime - (7L * 24 * 60 * 60 * 1000);
            case "Month": return endTime - (30L * 24 * 60 * 60 * 1000);
            case "Year": return endTime - (365L * 24 * 60 * 60 * 1000);
            default: return endTime - (24L * 60 * 60 * 1000); // Today
        }
    }

    private void loadReportData(String parameter, String filter) {
        long endTime = System.currentTimeMillis();
        long startTime = getStartTimeForFilter(filter, endTime);

        dbHelper.setTargetUid(selectedDeviceId);

        dbHelper.getParameterLogs(startTime, endTime)
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    List<Entry> entries = new ArrayList<>();
                    float sum = 0f;
                    float high = Float.NEGATIVE_INFINITY;
                    float low = Float.POSITIVE_INFINITY;

                    String dbFieldName = getFieldNameFromParameter(parameter);

                    int index = 0;
                    for (QueryDocumentSnapshot doc : queryDocumentSnapshots) {
                        Double value = doc.getDouble(dbFieldName);
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
                        tvAverage.setText("--");
                        tvHigh.setText("--");
                        tvLow.setText("--");
                        return;
                    }

                    LineDataSet dataSet = new LineDataSet(entries, parameter);
                    dataSet.setColor(getResources().getColor(R.color.primary));
                    dataSet.setCircleColor(getResources().getColor(R.color.primary));
                    dataSet.setValueTextColor(Color.BLACK);
                    dataSet.setLineWidth(2f);
                    dataSet.setDrawCircles(false);

                    lineChart.setData(new LineData(dataSet));
                    lineChart.getDescription().setEnabled(false);
                    lineChart.getLegend().setEnabled(true);
                    lineChart.invalidate();

                    float avg = sum / entries.size();
                    String unit = getUnitForParameter(parameter);

                    tvAverage.setText(String.format("%.1f%s", avg, unit));
                    tvHigh.setText(String.format("%.1f%s", high, unit));
                    tvLow.setText(String.format("%.1f%s", low, unit));
                })
                .addOnFailureListener(e -> {
                    Log.e("CHART_FETCH_ERROR", "Failed to fetch logs", e);
                    Toast.makeText(getContext(), "Error loading chart data", Toast.LENGTH_SHORT).show();
                });
    }

    /**
     * 🔥 BAGONG FEATURE: Kinukuha ang logs mula sa database, ginagawang CSV string,
     * at isinesend gamit ang Android Share Sheet.
     */
    private void exportDataToCSV() {
        if (getContext() == null) return;

        Toast.makeText(getContext(), "Preparing data for export...", Toast.LENGTH_SHORT).show();

        long endTime = System.currentTimeMillis();
        long startTime = getStartTimeForFilter(currentSelectedFilter, endTime);

        dbHelper.setTargetUid(selectedDeviceId);

        // Kuhanin ang parehong set ng data na makikita sa filter ngayon
        dbHelper.getParameterLogs(startTime, endTime)
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    if (queryDocumentSnapshots.isEmpty()) {
                        Toast.makeText(getContext(), "No data available to export.", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // 1. Gumawa ng CSV Header (Malinis at Readable)
                    StringBuilder csvBuilder = new StringBuilder();
                    csvBuilder.append("Date & Time,Air Temp (°C),Humidity (%),Water Temp (°C),Water Level (%),pH,EC (mS/cm)\n");

                    SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

                    // 2. I-loop ang lahat ng records para ipasok sa file rows
                    for (QueryDocumentSnapshot doc : queryDocumentSnapshots) {
                        Long timestamp = doc.getLong("timestamp");
                        String readableDate = (timestamp != null) ? dateFormat.format(new Date(timestamp)) : "N/A";

                        double airTemp = doc.contains("air_temp") && doc.getDouble("air_temp") != null ? doc.getDouble("air_temp") : 0.0;
                        double humidity = doc.contains("humidity") && doc.getDouble("humidity") != null ? doc.getDouble("humidity") : 0.0;
                        double waterTemp = doc.contains("water_temp") && doc.getDouble("water_temp") != null ? doc.getDouble("water_temp") : 0.0;
                        double waterLevel = doc.contains("water_level") && doc.getDouble("water_level") != null ? doc.getDouble("water_level") : 0.0;
                        double ph = doc.contains("ph") && doc.getDouble("ph") != null ? doc.getDouble("ph") : 0.0;
                        double ec = doc.contains("ec") && doc.getDouble("ec") != null ? doc.getDouble("ec") : 0.0;

                        csvBuilder.append(String.format(Locale.US, "%s,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f\n",
                                readableDate, airTemp, humidity, waterTemp, waterLevel, ph, ec));
                    }

                    // 3. I-save ang CSV string sa isang pansamantalang file sa Cache directory
                    try {
                        File cachePath = new File(getContext().getCacheDir(), "exports");
                        if (!cachePath.exists()) cachePath.mkdirs();

                        String filename = "Basilience_Report_" + selectedDeviceId + "_" + currentSelectedFilter + ".csv";
                        File csvFile = new File(cachePath, filename);
                        FileWriter writer = new FileWriter(csvFile);
                        writer.append(csvBuilder.toString());
                        writer.flush();
                        writer.close();

                        // 4. Tawagin ang Share Sheet gamit ang FileProvider URI
                        Uri contentUri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", csvFile);

                        if (contentUri != null) {
                            Intent shareIntent = new Intent(Intent.ACTION_SEND);
                            shareIntent.setType("text/csv");
                            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Basilience Sensor Data Logs - " + selectedDeviceId);
                            shareIntent.putExtra(Intent.EXTRA_TEXT, "Attached is the exported " + currentSelectedFilter + " report from the Basilience Fogponics Cultivation System.");
                            shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
                            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                            startActivity(Intent.createChooser(shareIntent, "Export Report via:"));
                        }

                    } catch (IOException e) {
                        Log.e("CSV_EXPORT_ERROR", "Error writing CSV file", e);
                        Toast.makeText(getContext(), "Failed to generate CSV file", Toast.LENGTH_SHORT).show();
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e("CSV_EXPORT_ERROR", "Failed to fetch logs for CSV", e);
                    Toast.makeText(getContext(), "Error gathering data from database", Toast.LENGTH_SHORT).show();
                });
    }

    private String getFieldNameFromParameter(String parameter) {
        if (parameter.equalsIgnoreCase("Air Temperature")) return "air_temp";
        if (parameter.equalsIgnoreCase("Humidity")) return "humidity";
        if (parameter.equalsIgnoreCase("Water Temperature")) return "water_temp";
        if (parameter.equalsIgnoreCase("Water Level")) return "water_level";
        if (parameter.equalsIgnoreCase("pH")) return "ph";
        if (parameter.equalsIgnoreCase("EC")) return "ec";
        return "air_temp";
    }

    private String getUnitForParameter(String parameter) {
        if (parameter.contains("Temperature")) return "°C";
        if (parameter.equalsIgnoreCase("Humidity") || parameter.equalsIgnoreCase("Water Level")) return "%";
        if (parameter.equalsIgnoreCase("EC")) return " mS/cm";
        return "";
    }
}