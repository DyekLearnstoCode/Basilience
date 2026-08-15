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
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Calendar;
import java.util.TimeZone;

public class SystemReportsFragment extends Fragment {

    private Spinner spinnerParameter;
    private LineChart lineChart;
    private TextView tvAverage, tvHigh, tvLow;
    private Database_Helper dbHelper;

    private MaterialButton btnToday, btnWeek, btnMonth, btnCycle;
    private ImageButton btnShare;
    private String currentSelectedFilter = "Today";
    private TextView tvInterpretation, tvInsightStatus;
    private String selectedDeviceId;
    private String userRole = RoleConstants.ROLE_FARMER;
    private long reportRequestGeneration = 0L;
    private Double minPhThreshold;
    private Double maxPhThreshold;
    private Double minEcThreshold;
    private Double refillStartThreshold;
    private Double highWaterTempThreshold;
    private Long activeCycleStartMs;
    private boolean activeCycleResolved;

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
            android.content.SharedPreferences prefs = requireContext().getSharedPreferences("basilience_prefs", android.content.Context.MODE_PRIVATE);
            selectedDeviceId = prefs.getString("selected_device_id", null);
        }

        if (selectedDeviceId == null || selectedDeviceId.isEmpty()) {
            Toast.makeText(getContext(), "Please select a device first", Toast.LENGTH_SHORT).show();
        } else {
            dbHelper.setSelectedDeviceId(selectedDeviceId);
        }

        spinnerParameter = view.findViewById(R.id.spinnerParameter);
        lineChart = view.findViewById(R.id.lineChart);
        tvAverage = view.findViewById(R.id.tvAverage);
        tvHigh = view.findViewById(R.id.tvHigh);
        tvLow = view.findViewById(R.id.tvLow);
        
        tvInterpretation = view.findViewById(R.id.tvInterpretation);
        tvInsightStatus = view.findViewById(R.id.tvInsightStatus);

        btnToday = view.findViewById(R.id.btnToday);
        btnWeek = view.findViewById(R.id.btnWeek);
        btnMonth = view.findViewById(R.id.btnMonth);
        btnCycle = view.findViewById(R.id.btnCycle);
        btnShare = view.findViewById(R.id.btnShare);
        ImageButton btnInfo = view.findViewById(R.id.btnInfo);
        if (btnInfo != null) {
            btnInfo.setOnClickListener(v -> showInfoDialog());
        }

        fetchUserInfo();

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
        btnWeek.setOnClickListener(v -> updateFilterSelection("7 Days"));
        btnMonth.setOnClickListener(v -> updateFilterSelection("30 Days"));
        btnCycle.setOnClickListener(v -> updateFilterSelection("Cycle"));

        if (btnShare != null) {
            btnShare.setOnClickListener(v -> showExportOptions());
        }

        if (spinnerParameter.getSelectedItem() != null) {
            loadReportData(String.valueOf(spinnerParameter.getSelectedItem()), currentSelectedFilter);
        }

        loadDeviceThresholds();
        loadActiveCycleStart();

        return view;
    }

    private void loadDeviceThresholds() {
        if (selectedDeviceId == null || selectedDeviceId.isEmpty()) return;
        FirebaseDatabase.getInstance("https://basilience-database-default-rtdb.asia-southeast1.firebasedatabase.app")
                .getReference("devices").child(selectedDeviceId).child("settings")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        minPhThreshold = numberValue(snapshot.child("minPH"));
                        maxPhThreshold = numberValue(snapshot.child("maxPH"));
                        minEcThreshold = numberValue(snapshot.child("minEC"));
                        refillStartThreshold = numberValue(snapshot.child("refillStartLevel"));
                        highWaterTempThreshold = numberValue(snapshot.child("highWaterTemp"));
                        if (spinnerParameter.getSelectedItem() != null) {
                            loadReportData(String.valueOf(spinnerParameter.getSelectedItem()), currentSelectedFilter);
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Log.w("REPORT_THRESHOLDS", "Unable to read configured device thresholds", error.toException());
                    }
                });
    }

    private Double numberValue(DataSnapshot snapshot) {
        Object value = snapshot.getValue();
        return value instanceof Number ? ((Number) value).doubleValue() : null;
    }

    private void loadActiveCycleStart() {
        activeCycleResolved = false;
        activeCycleStartMs = null;
        if (selectedDeviceId == null || selectedDeviceId.isEmpty()) {
            activeCycleResolved = true;
            return;
        }
        dbHelper.getActiveCycle(selectedDeviceId)
                .addOnSuccessListener(snapshot -> {
                    activeCycleResolved = true;
                    if (!snapshot.isEmpty()) {
                        Timestamp startDate = snapshot.getDocuments().get(0).getTimestamp("startDate");
                        if (startDate != null) activeCycleStartMs = startDate.toDate().getTime();
                    }
                    if ("Cycle".equals(currentSelectedFilter) && spinnerParameter.getSelectedItem() != null) {
                        loadReportData(String.valueOf(spinnerParameter.getSelectedItem()), currentSelectedFilter);
                    }
                })
                .addOnFailureListener(error -> {
                    activeCycleResolved = true;
                    Log.e("REPORT_CYCLE", "Unable to resolve active cycle start", error);
                    if ("Cycle".equals(currentSelectedFilter)) {
                        showEmptyReportState("Unable to load the active cycle.");
                    }
                });
    }

    private void fetchUserInfo() {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid != null) {
            dbHelper.getUserProfile(uid).addOnSuccessListener(documentSnapshot -> {
                if (documentSnapshot.exists()) {
                    userRole = documentSnapshot.getString("role");
                    updateUIForRole();
                }
            });
        }
    }

    private void updateUIForRole() {
        if (btnShare != null) {
            btnShare.setVisibility(RoleConstants.ROLE_ADMIN.equalsIgnoreCase(userRole) ? View.VISIBLE : View.GONE);
        }
    }

    private void showExportOptions() {
        String[] options = {"Share CSV", "Save as PDF"};
        NotificationHelper.showSelectionDialog(requireContext(), "Export Report", options, index -> {
            if (index == 0) {
                exportDataToCSV();
            } else {
                exportToPdf();
            }
        });
    }

    private void exportToPdf() {
        if (getContext() == null) return;
        if (!isFilterWindowReady()) return;
        
        Toast.makeText(getContext(), "Generating PDF...", Toast.LENGTH_SHORT).show();
        
        String parameter = String.valueOf(spinnerParameter.getSelectedItem());
        
        long endTime = System.currentTimeMillis();
        long startTime = getStartTimeForFilter(currentSelectedFilter, endTime);
        
        dbHelper.getParameterLogs(startTime, endTime).addOnSuccessListener(queryDocumentSnapshots -> {
            List<Entry> entries = new ArrayList<>();
            String dbFieldName = getFieldNameFromParameter(parameter);
            if (dbFieldName == null) return;
            int index = 0;
            float sum = 0f;
            float high = Float.NEGATIVE_INFINITY;
            float low = Float.POSITIVE_INFINITY;
            for (QueryDocumentSnapshot doc : queryDocumentSnapshots) {
                Double value = doc.getDouble(dbFieldName);
                if (isValidParameterValue(parameter, value)) {
                    float validValue = value.floatValue();
                    entries.add(new Entry(index++, validValue));
                    sum += validValue;
                    high = Math.max(high, validValue);
                    low = Math.min(low, validValue);
                }
            }
            if (entries.isEmpty()) {
                Toast.makeText(getContext(), "No valid data available to export.", Toast.LENGTH_SHORT).show();
                return;
            }
            float avg = sum / entries.size();
            
            try {
                CycleReportGenerator generator = new CycleReportGenerator(requireContext());
                String userName = "Basilience User"; 
                
                File pdfFile = generator.generateSensorReportPdf(
                    selectedDeviceId, parameter, currentSelectedFilter, 
                    entries, avg, high, low, getUnitForParameter(parameter),
                    getInterpretation(parameter, avg), userName
                );
                
                Uri contentUri = FileProvider.getUriForFile(requireContext(), requireContext().getPackageName() + ".fileprovider", pdfFile);
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(contentUri, "application/pdf");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(intent, "Open PDF Report"));
                
            } catch (IOException e) {
                Log.e("PDF_EXPORT_ERROR", "Error generating PDF", e);
                Toast.makeText(getContext(), "Failed to generate PDF", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateFilterSelection(String selectedFilter) {
        currentSelectedFilter = selectedFilter;

        btnToday.setBackgroundResource(R.drawable.bg_chip);
        btnWeek.setBackgroundResource(R.drawable.bg_chip);
        btnMonth.setBackgroundResource(R.drawable.bg_chip);
        btnCycle.setBackgroundResource(R.drawable.bg_chip);

        btnToday.setTextColor(Color.BLACK);
        btnWeek.setTextColor(Color.BLACK);
        btnMonth.setTextColor(Color.BLACK);
        btnCycle.setTextColor(Color.BLACK);

        MaterialButton activeBtn;
        switch (selectedFilter) {
            case "7 Days": activeBtn = btnWeek; break;
            case "30 Days": activeBtn = btnMonth; break;
            case "Cycle": activeBtn = btnCycle; break;
            default: activeBtn = btnToday; break;
        }

        activeBtn.setBackgroundResource(R.drawable.bg_chip_selected);
        activeBtn.setTextColor(Color.WHITE);

        if (spinnerParameter.getSelectedItem() != null) {
            loadReportData(String.valueOf(spinnerParameter.getSelectedItem()), currentSelectedFilter);
        }
    }

    private long getStartTimeForFilter(String filter, long endTime) {
        switch (filter) {
            case "7 Days": return endTime - (7L * 24 * 60 * 60 * 1000);
            case "30 Days": return endTime - (30L * 24 * 60 * 60 * 1000);
            case "Cycle": return activeCycleStartMs != null ? activeCycleStartMs : endTime;
            default:
                Calendar manila = Calendar.getInstance(TimeZone.getTimeZone("Asia/Manila"));
                manila.setTimeInMillis(endTime);
                manila.set(Calendar.HOUR_OF_DAY, 0);
                manila.set(Calendar.MINUTE, 0);
                manila.set(Calendar.SECOND, 0);
                manila.set(Calendar.MILLISECOND, 0);
                return manila.getTimeInMillis();
        }
    }

    private void loadReportData(String parameter, String filter) {
        final long requestGeneration = ++reportRequestGeneration;
        if ("Cycle".equals(filter)) {
            if (!activeCycleResolved) {
                showEmptyReportState("Loading active cycle data...");
                return;
            }
            if (activeCycleStartMs == null) {
                showEmptyReportState("No active cycle is available for this device.");
                return;
            }
        }
        long endTime = System.currentTimeMillis();
        long startTime = getStartTimeForFilter(filter, endTime);

        View layoutLoading = getView() != null ? getView().findViewById(R.id.layoutLoading) : null;
        if (layoutLoading != null) {
            layoutLoading.setVisibility(View.VISIBLE);
            layoutLoading.bringToFront();
        }

        dbHelper.getParameterLogs(startTime, endTime)
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    if (!isAdded() || requestGeneration != reportRequestGeneration) return;
                    if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
                    List<Entry> entries = new ArrayList<>();
                    float sum = 0f;
                    float high = Float.NEGATIVE_INFINITY;
                    float low = Float.POSITIVE_INFINITY;

                    String dbFieldName = getFieldNameFromParameter(parameter);
                    if (dbFieldName == null) {
                        showEmptyReportState("Unsupported parameter selection.");
                        return;
                    }

                    for (QueryDocumentSnapshot doc : queryDocumentSnapshots) {
                        Double value = doc.getDouble(dbFieldName);
                        Long timestamp = doc.getLong("timestamp");
                        if (timestamp != null && isValidParameterValue(parameter, value)) {
                            float val = value.floatValue();
                            entries.add(new Entry((timestamp - startTime) / 60000f, val));
                            sum += val;
                            if (val > high) high = val;
                            if (val < low) low = val;
                        }
                    }

                    if (entries.isEmpty()) {
                        showEmptyReportState("No valid observations in the selected time window.");
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
                    lineChart.getLegend().setYOffset(8f);
                    lineChart.setExtraBottomOffset(8f);
                    
                    com.github.mikephil.charting.components.XAxis xAxis = lineChart.getXAxis();
                    xAxis.setPosition(com.github.mikephil.charting.components.XAxis.XAxisPosition.BOTTOM);
                    xAxis.setValueFormatter(new com.github.mikephil.charting.formatter.ValueFormatter() {
                        private final SimpleDateFormat timeFormat = new SimpleDateFormat(
                                "Today".equals(filter) ? "HH:mm" : "MMM d", Locale.getDefault());
                        {
                            timeFormat.setTimeZone(TimeZone.getTimeZone("Asia/Manila"));
                        }
                        @Override public String getFormattedValue(float value) {
                            if ("Cycle".equals(filter)) {
                                return "Day " + (((long) value / (24L * 60L)) + 1L);
                            }
                            return timeFormat.format(new Date(startTime + (long) (value * 60000f)));
                        }
                    });
                    String unit = getUnitForParameter(parameter);
                    lineChart.getAxisLeft().resetAxisMinimum();
                    lineChart.getAxisLeft().resetAxisMaximum();
                    lineChart.getAxisLeft().setValueFormatter(new com.github.mikephil.charting.formatter.ValueFormatter() {
                        @Override public String getFormattedValue(float value) {
                            return String.format(Locale.getDefault(), "%.1f%s", value, unit);
                        }
                    });
                    lineChart.getAxisRight().setEnabled(false);
                    
                    lineChart.invalidate();

                    float avg = sum / entries.size();
                    tvAverage.setText(String.format(Locale.getDefault(), "%.1f%s", avg, unit));
                    tvHigh.setText(String.format(Locale.getDefault(), "%.1f%s", high, unit));
                    tvLow.setText(String.format(Locale.getDefault(), "%.1f%s", low, unit));
                    
                    if (tvInterpretation != null) {
                        String severity = getInsightSeverity(parameter, avg);
                        tvInsightStatus.setVisibility(View.VISIBLE);
                        tvInsightStatus.setText(severity);
                        tvInterpretation.setText(getInterpretation(parameter, avg));
                        tvInterpretation.setTextColor(ContextCompat.getColor(tvInterpretation.getContext(), R.color.text_dark));
                        int color;
                        if ("CRITICAL / ERROR".equals(severity)) {
                            color = android.R.color.holo_red_dark;
                        } else if ("WARNING".equals(severity)) {
                            color = android.R.color.holo_orange_dark;
                        } else if (!"NORMAL".equals(severity)) {
                            color = android.R.color.darker_gray;
                        } else {
                            color = android.R.color.holo_green_dark;
                        }
                        tvInsightStatus.setTextColor(ContextCompat.getColor(tvInsightStatus.getContext(), color));
                    }
                })
                .addOnFailureListener(e -> {
                    if (requestGeneration != reportRequestGeneration) return;
                    if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
                    showEmptyReportState("Unable to load report data.");
                    Log.e("CHART_FETCH_ERROR", "Failed to fetch logs", e);
                    Toast.makeText(getContext(), "Error loading chart data", Toast.LENGTH_SHORT).show();
                });
    }

    private void exportDataToCSV() {
        if (getContext() == null) return;
        if (!isFilterWindowReady()) return;

        Toast.makeText(getContext(), "Preparing data for export...", Toast.LENGTH_SHORT).show();

        long endTime = System.currentTimeMillis();
        long startTime = getStartTimeForFilter(currentSelectedFilter, endTime);

        dbHelper.getParameterLogs(startTime, endTime)
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    if (queryDocumentSnapshots.isEmpty()) {
                        Toast.makeText(getContext(), "No data available to export.", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    StringBuilder csvBuilder = new StringBuilder();
                    csvBuilder.append("Date & Time,Air Temp (°C),Humidity (%),Water Temp (°C),Water Level (%),pH,EC (mS/cm)\n");

                    SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                    dateFormat.setTimeZone(TimeZone.getTimeZone("Asia/Manila"));

                    for (QueryDocumentSnapshot doc : queryDocumentSnapshots) {
                        Long timestamp = doc.getLong("timestamp");
                        String readableDate = (timestamp != null) ? dateFormat.format(new Date(timestamp)) : "N/A";

                        csvBuilder.append(readableDate).append(',')
                                .append(csvValue(doc, "air_temp", "Air Temperature")).append(',')
                                .append(csvValue(doc, "humidity", "Humidity")).append(',')
                                .append(csvValue(doc, "water_temp", "Water Temperature")).append(',')
                                .append(csvValue(doc, "water_level", "Water Level")).append(',')
                                .append(csvValue(doc, "ph", "pH")).append(',')
                                .append(csvValue(doc, "ec", "EC")).append('\n');
                    }

                    try {
                        File cachePath = new File(getContext().getCacheDir(), "exports");
                        if (!cachePath.exists()) cachePath.mkdirs();

                        String filename = "Basilience_Report_" + selectedDeviceId + "_" + currentSelectedFilter + ".csv";
                        File csvFile = new File(cachePath, filename);
                        FileWriter writer = new FileWriter(csvFile);
                        writer.append(csvBuilder.toString());
                        writer.flush();
                        writer.close();

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
        return null;
    }

    private boolean isFilterWindowReady() {
        if (!"Cycle".equals(currentSelectedFilter)) return true;
        if (!activeCycleResolved) {
            Toast.makeText(getContext(), "Active cycle is still loading.", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (activeCycleStartMs == null) {
            Toast.makeText(getContext(), "No active cycle is available for this device.", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private String csvValue(QueryDocumentSnapshot document, String field, String parameter) {
        Double value = document.getDouble(field);
        return isValidParameterValue(parameter, value) ? String.format(Locale.US, "%.2f", value) : "";
    }

    private boolean isValidParameterValue(String parameter, Double value) {
        if (value == null || value.isNaN() || value.isInfinite()) return false;
        if (parameter.equalsIgnoreCase("pH")) return value >= 0 && value <= 14;
        if (parameter.equalsIgnoreCase("EC")) return value >= 0;
        if (parameter.equalsIgnoreCase("Air Temperature")) return value >= -40 && value <= 80;
        if (parameter.equalsIgnoreCase("Humidity")) return value >= 0 && value <= 100;
        if (parameter.equalsIgnoreCase("Water Temperature")) {
            return value >= -55 && value <= 125 && Math.abs(value + 127) > 0.0001;
        }
        if (parameter.equalsIgnoreCase("Water Level")) return value >= 0 && value <= 100;
        return false;
    }

    private String getUnitForParameter(String parameter) {
        if (parameter.contains("Temperature")) return "°C";
        if (parameter.equalsIgnoreCase("Humidity") || parameter.equalsIgnoreCase("Water Level")) return "%";
        if (parameter.equalsIgnoreCase("EC")) return " mS/cm";
        return "";
    }

    private String getInterpretation(String parameter, float avg) {
        if (parameter.equalsIgnoreCase("pH")) {
            if (minPhThreshold == null || maxPhThreshold == null) return "Configured pH thresholds are unavailable.";
            if (avg < minPhThreshold) return String.format(Locale.getDefault(), "Selected-window average is below configured minPH %.2f.", minPhThreshold);
            if (avg > maxPhThreshold) return String.format(Locale.getDefault(), "Selected-window average is above configured maxPH %.2f.", maxPhThreshold);
            return String.format(Locale.getDefault(), "Selected-window average is within configured pH range %.2f - %.2f.", minPhThreshold, maxPhThreshold);
        }
        if (parameter.equalsIgnoreCase("EC")) {
            if (minEcThreshold == null) return "Configured EC threshold is unavailable.";
            if (avg < minEcThreshold) return String.format(Locale.getDefault(), "Selected-window average is below configured minEC %.2f mS/cm.", minEcThreshold);
            return String.format(Locale.getDefault(), "Selected-window average meets configured minEC %.2f mS/cm.", minEcThreshold);
        }
        if (parameter.equalsIgnoreCase("Water Temperature")) {
            if (highWaterTempThreshold == null) return "Configured water-temperature threshold is unavailable.";
            if (avg > highWaterTempThreshold) return String.format(Locale.getDefault(), "Selected-window average is above configured highWaterTemp %.1f°C.", highWaterTempThreshold);
            return String.format(Locale.getDefault(), "Selected-window average is at or below configured highWaterTemp %.1f°C.", highWaterTempThreshold);
        }
        if (parameter.equalsIgnoreCase("Water Level")) {
            if (refillStartThreshold == null) return "Configured water-level threshold is unavailable.";
            if (avg < refillStartThreshold) return String.format(Locale.getDefault(), "Selected-window average is below configured refillStartLevel %.1f%%.", refillStartThreshold);
            return String.format(Locale.getDefault(), "Selected-window average is at or above configured refillStartLevel %.1f%%.", refillStartThreshold);
        }
        return "No device-configured insight threshold exists for this parameter.";
    }

    private String getInsightSeverity(String parameter, float value) {
        if (parameter.equalsIgnoreCase("pH")) {
            if (minPhThreshold == null || maxPhThreshold == null) return "THRESHOLD UNAVAILABLE";
            return value < minPhThreshold || value > maxPhThreshold ? "WARNING" : "NORMAL";
        }
        if (parameter.equalsIgnoreCase("EC")) {
            if (minEcThreshold == null) return "THRESHOLD UNAVAILABLE";
            return value < minEcThreshold ? "WARNING" : "NORMAL";
        }
        if (parameter.equalsIgnoreCase("Water Temperature")) {
            if (highWaterTempThreshold == null) return "THRESHOLD UNAVAILABLE";
            return value > highWaterTempThreshold ? "WARNING" : "NORMAL";
        }
        if (parameter.equalsIgnoreCase("Water Level")) {
            if (refillStartThreshold == null) return "THRESHOLD UNAVAILABLE";
            return value < refillStartThreshold ? "WARNING" : "NORMAL";
        }
        return "NO CONFIGURED THRESHOLD";
    }

    private void showEmptyReportState(String insightMessage) {
        lineChart.clear();
        lineChart.invalidate();
        tvAverage.setText("--");
        tvHigh.setText("--");
        tvLow.setText("--");
        if (tvInterpretation != null) {
            tvInsightStatus.setVisibility(View.GONE);
            tvInterpretation.setText(insightMessage);
            tvInterpretation.setTextColor(ContextCompat.getColor(tvInterpretation.getContext(), R.color.text_dark));
        }
    }

    private void showInfoDialog() {
        if (getContext() == null) return;
        NotificationHelper.showInfo(requireContext(), "How to use Parameter Reports",
                "What does this page do?\n" +
                    "This page lets you see how things like Temperature, Humidity, pH, and EC have changed over time in your system.\n\n" +
                    "Why is this useful?\n" +
                    "By looking at these trends, you can easily spot if anything goes out of the safe range, helping you keep the plants healthy.\n\n" +
                    "How to use it?\n" +
                    "• Tap the 'Parameter' dropdown to pick what you want to check (like pH or Water Level).\n" +
                    "• Use the buttons (Today, Week, Month) to see older data on the chart.\n" +
                    "• Below the chart, check the 'System Insights' box for simple advice on what the numbers mean for your plants.",
                "Got it");
    }
}
