package com.example.basilience;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.example.basilience.models.FoggingEvent;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;
import com.google.android.material.button.MaterialButton;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import androidx.core.content.FileProvider;
import java.io.File;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class FoggingReportsFragment extends Fragment {

    private LineChart lineChart;
    private TextView tvTotalDuration, tvEventCount, tvPrediction;
    private List<FoggingEvent> currentEvents = new ArrayList<>();

    private MaterialButton btnToday, btnWeek, btnMonth, btnYear;
    private ImageButton btnShare;
    private String currentSelectedFilter = "Today";
    private String selectedDeviceId;

    private FirebaseFirestore db;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.reports_fogging, container, false);

        db = FirebaseFirestore.getInstance();

        if (getArguments() != null) {
            selectedDeviceId = getArguments().getString("deviceId");
        }

        if (selectedDeviceId == null || selectedDeviceId.isEmpty()) {
            SharedPreferences prefs = requireContext().getSharedPreferences("basilience_prefs", Context.MODE_PRIVATE);
            selectedDeviceId = prefs.getString("selected_device_id", null);
        }

        if (selectedDeviceId == null || selectedDeviceId.isEmpty()) {
            Toast.makeText(getContext(), "Please select a device first", Toast.LENGTH_SHORT).show();
        }

        lineChart = view.findViewById(R.id.lineChart);
        tvTotalDuration = view.findViewById(R.id.tvTotalDuration);
        tvEventCount = view.findViewById(R.id.tvEventCount);
        tvPrediction = view.findViewById(R.id.tvPrediction);

        btnToday = view.findViewById(R.id.btnToday);
        btnWeek = view.findViewById(R.id.btnWeek);
        btnMonth = view.findViewById(R.id.btnMonth);
        btnYear = view.findViewById(R.id.btnYear);
        btnShare = view.findViewById(R.id.btnShare);

        if (btnShare != null) {
            btnShare.setOnClickListener(v -> exportPdf());
        }

        setupFilterButtons();
        setupChart();

        View btnBack = view.findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setVisibility(View.VISIBLE);
            NavController navController = NavHostFragment.findNavController(this);
            btnBack.setOnClickListener(v -> navController.popBackStack());
        }

        loadData();

        return view;
    }

    private void setupFilterButtons() {
        View.OnClickListener filterListener = v -> {
            updateFilterUI((MaterialButton) v);
            loadData();
        };

        btnToday.setOnClickListener(filterListener);
        btnWeek.setOnClickListener(filterListener);
        btnMonth.setOnClickListener(filterListener);
        btnYear.setOnClickListener(filterListener);
    }

    private void updateFilterUI(MaterialButton selectedBtn) {
        currentSelectedFilter = selectedBtn.getText().toString();

        btnToday.setBackgroundResource(R.drawable.bg_chip);
        btnWeek.setBackgroundResource(R.drawable.bg_chip);
        btnMonth.setBackgroundResource(R.drawable.bg_chip);
        btnYear.setBackgroundResource(R.drawable.bg_chip);

        btnToday.setTextColor(Color.BLACK);
        btnWeek.setTextColor(Color.BLACK);
        btnMonth.setTextColor(Color.BLACK);
        btnYear.setTextColor(Color.BLACK);

        selectedBtn.setBackgroundResource(R.drawable.bg_chip_selected);
        selectedBtn.setTextColor(Color.WHITE);
    }

    private void setupChart() {
        lineChart.getDescription().setEnabled(false);
        lineChart.setDrawGridBackground(false);
        lineChart.getAxisRight().setEnabled(false);
        
        XAxis xAxis = lineChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawGridLines(false);
        xAxis.setGranularity(1f);
    }

    private void loadData() {
        if (selectedDeviceId == null || selectedDeviceId.isEmpty()) return;

        long startTime = getStartTimeForFilter(currentSelectedFilter);

        db.collection("devices")
                .document(selectedDeviceId)
                .collection("foggingLogs")
                .whereGreaterThanOrEqualTo("timestamp", startTime)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    List<FoggingEvent> events = new ArrayList<>();
                    for (DocumentSnapshot doc : queryDocumentSnapshots) {
                        FoggingEvent event = doc.toObject(FoggingEvent.class);
                        if (event != null) {
                            event.id = doc.getId();
                            events.add(event);
                        }
                    }
                    processEvents(events);
                })
                .addOnFailureListener(e -> {
                    Log.e("FoggingReports", "Error loading logs", e);
                    Toast.makeText(getContext(), "Failed to load reports", Toast.LENGTH_SHORT).show();
                });
    }

    private long getStartTimeForFilter(String filter) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        switch (filter) {
            case "Week":
                cal.add(Calendar.DAY_OF_YEAR, -7);
                break;
            case "Month":
                cal.add(Calendar.MONTH, -1);
                break;
            case "Year":
                cal.add(Calendar.YEAR, -1);
                break;
            case "Today":
            default:
                break;
        }
        return cal.getTimeInMillis();
    }

    private void processEvents(List<FoggingEvent> events) {
        this.currentEvents = events;
        
        if (events.isEmpty()) {
            lineChart.clear();
            tvTotalDuration.setText("0m");
            tvEventCount.setText("0");
            tvPrediction.setText("No fogging data to calculate prediction.");
            return;
        }

        tvEventCount.setText(String.valueOf(events.size()));

        // We process in chronological order for durations
        List<FoggingEvent> chronoEvents = new ArrayList<>(events);
        Collections.reverse(chronoEvents);

        long totalDurationMs = 0;
        long lastOnTime = -1;
        String lastReason = "automatic";
        boolean lastIsManual = false;

        Map<String, Long> dailyAuto = new HashMap<>();
        Map<String, Long> dailyManual = new HashMap<>();
        Map<String, Long> dailyAdaptive = new HashMap<>();
        
        SimpleDateFormat dayFormat;
        if ("Today".equals(currentSelectedFilter)) {
            dayFormat = new SimpleDateFormat("hh a", Locale.getDefault());
        } else {
            dayFormat = new SimpleDateFormat("MMM dd", Locale.getDefault());
        }
        
        List<String> xLabels = new ArrayList<>();

        for (FoggingEvent e : chronoEvents) {
            if ("ON".equals(e.event)) {
                lastOnTime = e.timestamp;
                lastReason = e.reason != null ? e.reason.toLowerCase() : "automatic";
                lastIsManual = e.isManual;
            } else if ("OFF".equals(e.event)) {
                if (lastOnTime != -1 && e.timestamp > lastOnTime) {
                    long duration = e.timestamp - lastOnTime;
                    totalDurationMs += duration;

                    String dayKey = dayFormat.format(new Date(e.timestamp));
                    if (!xLabels.contains(dayKey)) {
                        xLabels.add(dayKey);
                    }
                    
                    if (lastIsManual || "manual".equals(lastReason)) {
                        dailyManual.put(dayKey, dailyManual.getOrDefault(dayKey, 0L) + duration);
                    } else if ("adaptive".equals(lastReason)) {
                        dailyAdaptive.put(dayKey, dailyAdaptive.getOrDefault(dayKey, 0L) + duration);
                    } else {
                        dailyAuto.put(dayKey, dailyAuto.getOrDefault(dayKey, 0L) + duration);
                    }
                    
                    lastOnTime = -1;
                }
            }
        }

        long totalMins = totalDurationMs / (1000 * 60);
        tvTotalDuration.setText(totalMins + "m");

        // Prepare Chart Data
        List<Entry> autoEntries = new ArrayList<>();
        List<Entry> manualEntries = new ArrayList<>();
        List<Entry> adaptiveEntries = new ArrayList<>();
        
        for (int i = 0; i < xLabels.size(); i++) {
            String day = xLabels.get(i);
            
            float autoMins = dailyAuto.getOrDefault(day, 0L) / (1000f * 60f);
            float manualMins = dailyManual.getOrDefault(day, 0L) / (1000f * 60f);
            float adaptiveMins = dailyAdaptive.getOrDefault(day, 0L) / (1000f * 60f);
            
            autoEntries.add(new Entry(i, autoMins));
            manualEntries.add(new Entry(i, manualMins));
            adaptiveEntries.add(new Entry(i, adaptiveMins));
        }

        if (xLabels.isEmpty()) {
            lineChart.clear();
        } else {
            LineDataSet dsAuto = new LineDataSet(autoEntries, "Automatic");
            dsAuto.setColor(Color.parseColor("#4CAF50")); // Green
            dsAuto.setCircleColor(Color.parseColor("#4CAF50"));
            dsAuto.setLineWidth(2f);
            dsAuto.setCircleRadius(4f);
            dsAuto.setDrawValues(false);
            dsAuto.setMode(LineDataSet.Mode.CUBIC_BEZIER);

            LineDataSet dsManual = new LineDataSet(manualEntries, "Manual");
            dsManual.setColor(Color.parseColor("#2196F3")); // Blue
            dsManual.setCircleColor(Color.parseColor("#2196F3"));
            dsManual.setLineWidth(2f);
            dsManual.setCircleRadius(4f);
            dsManual.setDrawValues(false);
            dsManual.setMode(LineDataSet.Mode.CUBIC_BEZIER);

            LineDataSet dsAdaptive = new LineDataSet(adaptiveEntries, "Adaptive");
            dsAdaptive.setColor(Color.parseColor("#FF9800")); // Orange
            dsAdaptive.setCircleColor(Color.parseColor("#FF9800"));
            dsAdaptive.setLineWidth(2f);
            dsAdaptive.setCircleRadius(4f);
            dsAdaptive.setDrawValues(false);
            dsAdaptive.setMode(LineDataSet.Mode.CUBIC_BEZIER);
            
            LineData lineData = new LineData(dsAuto, dsManual, dsAdaptive);
            lineChart.getXAxis().setValueFormatter(new IndexAxisValueFormatter(xLabels));
            lineChart.setData(lineData);
            lineChart.getLegend().setEnabled(true);
            lineChart.invalidate(); // refresh
        }
        
        // Prediction Math
        calculatePrediction(totalDurationMs, xLabels.size());
    }

    private void calculatePrediction(long totalDurationMsInPeriod, int daysInPeriod) {
        if (daysInPeriod == 0) daysInPeriod = 1;
        float hoursFogged = totalDurationMsInPeriod / (1000f * 60f * 60f);
        float avgHoursPerDay = hoursFogged / daysInPeriod;
        
        float conversionRate = 4.8f; // Liters per hour
        float dailyConsumptionLiters = avgHoursPerDay * conversionRate;
        
        db.collection("devices").document(selectedDeviceId)
            .collection("parameterLogs")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(1)
            .get()
            .addOnSuccessListener(snapshots -> {
                if (!snapshots.isEmpty()) {
                    DocumentSnapshot latest = snapshots.getDocuments().get(0);
                    Double waterLevelPct = latest.getDouble("water_level");
                    if (waterLevelPct == null) {
                        waterLevelPct = latest.getDouble("waterLevel"); // fallback just in case
                    }
                    if (waterLevelPct != null) {
                        float tankVolumeLiters = 61.7f; // Estimated 61.7L capacity at 100% (65% height of 22x15.5x17 inches reservoir)
                        float currentLiters = tankVolumeLiters * (waterLevelPct.floatValue() / 100f);
                        
                        if (dailyConsumptionLiters > 0) {
                            float daysRemaining = currentLiters / dailyConsumptionLiters;
                            String text = String.format(Locale.getDefault(),
                                "Water Level: %.0f%% (%.1f Liters)\n" +
                                "Estimated Remaining: %.1f Days\n" +
                                "(Based on 61.7L tank capacity and an average consumption of %.1f Liters/day).",
                                waterLevelPct, currentLiters, daysRemaining, dailyConsumptionLiters);
                            tvPrediction.setText(text);
                        } else {
                            tvPrediction.setText(String.format(Locale.getDefault(), "Water Level: %.0f%% (%.1f Liters)\nNot enough fogging data to estimate consumption.", waterLevelPct, currentLiters));
                        }
                    } else {
                        tvPrediction.setText("Unable to fetch current water level for prediction.");
                    }
                } else {
                    tvPrediction.setText("No parameter logs available to calculate prediction.");
                }
            })
            .addOnFailureListener(e -> {
                tvPrediction.setText("Failed to load water level data.");
            });
    }

    private void exportPdf() {
        if (getContext() == null || currentEvents == null || currentEvents.isEmpty()) {
            Toast.makeText(getContext(), "No data to export", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(getContext(), "Generating PDF...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try {
                CycleReportGenerator generator = new CycleReportGenerator(getContext());
                
                SharedPreferences prefs = requireContext().getSharedPreferences("basilience_prefs", Context.MODE_PRIVATE);
                String userName = prefs.getString("user_name", "User");
                
                File pdfFile = generator.generateFoggingReportPdf(selectedDeviceId, currentSelectedFilter, currentEvents, userName);

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        try {
                            Uri contentUri = FileProvider.getUriForFile(requireContext(), requireContext().getPackageName() + ".fileprovider", pdfFile);
                            Intent intent = new Intent(Intent.ACTION_VIEW);
                            intent.setDataAndType(contentUri, "application/pdf");
                            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            startActivity(Intent.createChooser(intent, "Open PDF Report"));
                        } catch (Exception e) {
                            Log.e("FoggingReports", "Error opening PDF", e);
                            Toast.makeText(getContext(), "Failed to open PDF", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            } catch (Exception e) {
                Log.e("FoggingReports", "Error generating PDF", e);
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> Toast.makeText(getContext(), "Failed to generate PDF", Toast.LENGTH_SHORT).show());
                }
            }
        }).start();
    }
}