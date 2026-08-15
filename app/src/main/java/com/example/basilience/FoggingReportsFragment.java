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
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.basilience.models.FoggingEvent;
import com.example.basilience.models.FoggingSession;
import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

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

import com.example.basilience.models.FoggingSession;
import com.example.basilience.models.FoggingReportSummary;

public class FoggingReportsFragment extends Fragment {

    private BarChart barChart;
    private TextView tvTotalDuration, tvEventCount, tvAvgDuration;
    private TextView tvBreakdownAuto, tvBreakdownAutoDetails, tvBreakdownManual;
    private TextView tvWaterLevel, tvRefillTime, tvRefillThreshold;
    private RecyclerView rvRecentActivity;
    private TextView tvEmptyActivity;
    private View layoutLoading;
    
    private FoggingEventAdapter adapter;

    private List<FoggingEvent> rawEvents = new ArrayList<>();
    private List<FoggingSession> processedSessions = new ArrayList<>();

    private MaterialButton btnToday, btnWeek, btnMonth, btnYear;
    private ImageButton btnShare;
    private String currentSelectedFilter = "Today";
    private String selectedDeviceId;

    private FirebaseFirestore db;
    private double refillStartLevel = 0.0;
    private boolean cycleLoading = false;

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

        barChart = view.findViewById(R.id.barChart);
        ImageButton btnInfo = view.findViewById(R.id.btnInfo);
        if (btnInfo != null) {
            btnInfo.setOnClickListener(v -> showInfoDialog());
        }
        tvTotalDuration = view.findViewById(R.id.tvTotalDuration);
        tvEventCount = view.findViewById(R.id.tvEventCount);
        tvAvgDuration = view.findViewById(R.id.tvAvgDuration);
        tvBreakdownAuto = view.findViewById(R.id.tvBreakdownAuto);
        tvBreakdownAutoDetails = view.findViewById(R.id.tvBreakdownAutoDetails);
        tvBreakdownManual = view.findViewById(R.id.tvBreakdownManual);
        
        tvWaterLevel = view.findViewById(R.id.tvWaterLevel);
        tvRefillTime = view.findViewById(R.id.tvRefillTime);
        tvRefillThreshold = view.findViewById(R.id.tvRefillThreshold);
        
        rvRecentActivity = view.findViewById(R.id.rvRecentActivity);
        tvEmptyActivity = view.findViewById(R.id.tvEmptyActivity);
        layoutLoading = view.findViewById(R.id.layoutLoading);

        rvRecentActivity.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new FoggingEventAdapter(new ArrayList<>());
        rvRecentActivity.setAdapter(adapter);

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

        fetchConfigAndLoadData();

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
        barChart.getDescription().setEnabled(false);
        barChart.setDrawGridBackground(false);
        barChart.getAxisRight().setEnabled(false);
        
        XAxis xAxis = barChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawGridLines(false);
        xAxis.setGranularity(1f);
        barChart.getLegend().setYOffset(8f);
        barChart.setExtraBottomOffset(8f);
    }

    private void fetchConfigAndLoadData() {
        if (selectedDeviceId == null || selectedDeviceId.isEmpty()) return;
        
        layoutLoading.setVisibility(View.VISIBLE);
        layoutLoading.bringToFront();
        
        FirebaseDatabase.getInstance("https://basilience-database-default-rtdb.asia-southeast1.firebasedatabase.app").getReference("devices").child(selectedDeviceId).child("settings").child("refillStartLevel")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snapshot) {
                    if (snapshot.exists()) {
                        Double val = snapshot.getValue(Double.class);
                        if (val != null) refillStartLevel = val;
                    } else {
                        refillStartLevel = 25.0; // Fallback
                    }
                    tvRefillThreshold.setText("Refill threshold: " + refillStartLevel + "%");
                    loadData();
                }

                @Override
                public void onCancelled(@NonNull DatabaseError error) {
                    refillStartLevel = 25.0; // Fallback
                    tvRefillThreshold.setText("Refill threshold: " + refillStartLevel + "% (Offline Fallback)");
                    loadData();
                }
            });
    }

    private void loadData() {
        if (selectedDeviceId == null || selectedDeviceId.isEmpty()) return;
        layoutLoading.setVisibility(View.VISIBLE);
        layoutLoading.bringToFront();

        if ("Cycle".equals(currentSelectedFilter)) {
            cycleLoading = true;
            db.collection("devices").document(selectedDeviceId).collection("cycles")
                .whereEqualTo("status", "ACTIVE")
                .limit(1)
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    if (!queryDocumentSnapshots.isEmpty()) {
                        DocumentSnapshot doc = queryDocumentSnapshots.getDocuments().get(0);
                        com.google.firebase.Timestamp ts = doc.getTimestamp("startDate");
                        if (ts != null) {
                            fetchFoggingLogs(ts.toDate().getTime(), ts.toDate().getTime());
                        } else {
                            fetchFoggingLogs(0, 0); // fallback
                        }
                    } else {
                        Toast.makeText(getContext(), "No active cycle found", Toast.LENGTH_SHORT).show();
                        fetchFoggingLogs(System.currentTimeMillis(), System.currentTimeMillis()); // Will return empty
                    }
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(getContext(), "Failed to fetch cycle", Toast.LENGTH_SHORT).show();
                    layoutLoading.setVisibility(View.GONE);
                });
        } else {
            cycleLoading = false;
            fetchFoggingLogs(getStartTimeForFilter(currentSelectedFilter), 0);
        }
    }

    private void fetchFoggingLogs(long startTime, long cycleStartForBuckets) {
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
                    processEvents(events, cycleStartForBuckets);
                })
                .addOnFailureListener(e -> {
                    Log.e("FoggingReports", "Error loading logs", e);
                    Toast.makeText(getContext(), "Failed to load reports", Toast.LENGTH_SHORT).show();
                    layoutLoading.setVisibility(View.GONE);
                });
    }

    private long getStartTimeForFilter(String filter) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        switch (filter) {
            case "7 Days":
                cal.add(Calendar.DAY_OF_YEAR, -6);
                break;
            case "30 Days":
                cal.add(Calendar.DAY_OF_YEAR, -29);
                break;
            case "Today":
            default:
                break;
        }
        return cal.getTimeInMillis();
    }

    private void processEvents(List<FoggingEvent> events, long cycleStartForBuckets) {
        this.rawEvents = events;
        
        long reportStartTime = getStartTimeForFilter(currentSelectedFilter);
        if ("Cycle".equals(currentSelectedFilter)) {
            reportStartTime = cycleStartForBuckets;
        }
        
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long todayMidnight = cal.getTimeInMillis();
        if (reportStartTime > todayMidnight) {
            reportStartTime = todayMidnight; 
        }
        
        cal.set(Calendar.HOUR_OF_DAY, 23);
        cal.set(Calendar.MINUTE, 59);
        cal.set(Calendar.SECOND, 59);
        cal.set(Calendar.MILLISECOND, 999);
        long reportEndTime = cal.getTimeInMillis();
        long bucketSizeMs = "Today".equals(currentSelectedFilter) ? (1000L * 60 * 60) : (1000L * 60 * 60 * 24);

        final long finalStartTime = reportStartTime;
        final long finalEndTime = reportEndTime;

        FirebaseDatabase.getInstance("https://basilience-database-default-rtdb.asia-southeast1.firebasedatabase.app").getReference("devices").child(selectedDeviceId).child("actuatorStatus").child("fogger").child("running")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snapshot) {
                    boolean isRunning = false;
                    if (snapshot.exists() && snapshot.getValue(Boolean.class) != null) {
                        isRunning = snapshot.getValue(Boolean.class);
                    }
                    continueProcessingEvents(events, finalStartTime, finalEndTime, bucketSizeMs, isRunning);
                }

                @Override
                public void onCancelled(@NonNull DatabaseError error) {
                    continueProcessingEvents(events, finalStartTime, finalEndTime, bucketSizeMs, false);
                }
            });
    }

    private void continueProcessingEvents(List<FoggingEvent> events, long startTime, long endTime, long bucketSizeMs, boolean isRunning) {
        FoggingReportSummary summary = FoggingReportProcessor.process(events, startTime, endTime, bucketSizeMs, isRunning);
        this.processedSessions = summary.getCompletedSessions();

        // Update Recent Activity
        List<FoggingSession> recentList = new ArrayList<>();
        if (summary.getCurrentlyRunningSession() != null) {
            recentList.add(summary.getCurrentlyRunningSession());
        }
        for (int i = summary.getCompletedSessions().size() - 1; i >= 0 && recentList.size() < 10; i--) {
            recentList.add(summary.getCompletedSessions().get(i));
        }
        adapter.updateData(recentList);
        
        if (recentList.isEmpty()) {
            tvEmptyActivity.setVisibility(View.VISIBLE);
            rvRecentActivity.setVisibility(View.GONE);
        } else {
            tvEmptyActivity.setVisibility(View.GONE);
            rvRecentActivity.setVisibility(View.VISIBLE);
        }

        long totalMins = summary.getTotalDurationMs() / 60000;
        tvTotalDuration.setText(totalMins + "m");
        tvEventCount.setText(String.valueOf(summary.getCompletedSessions().size()));
        tvBreakdownAuto.setText((summary.getTotalAutoDurationMs() / 60000) + "m");
        tvBreakdownAutoDetails.setText(formatAutomaticBreakdown(summary));
        tvBreakdownManual.setText((summary.getTotalManualDurationMs() / 60000) + "m");

        long reportWindowDays = 1;
        if ("7 Days".equals(currentSelectedFilter)) reportWindowDays = 7;
        else if ("30 Days".equals(currentSelectedFilter)) reportWindowDays = 30;
        else if ("Cycle".equals(currentSelectedFilter)) {
            reportWindowDays = (endTime - startTime) / (1000L * 60 * 60 * 24);
            if (reportWindowDays == 0) reportWindowDays = 1;
        }

        long avgMins = (summary.getTotalDurationMs() / 60000) / reportWindowDays;
        tvAvgDuration.setText(avgMins + "m");

        // Bucket Generation
        List<String> xLabels = new ArrayList<>();
        List<BarEntry> entries = new ArrayList<>();
        
        Map<Long, Long> buckets = summary.getBucketAggregations();
        List<Long> bucketKeys = new ArrayList<>(buckets.keySet());
        Collections.sort(bucketKeys);

        SimpleDateFormat fmt = "Today".equals(currentSelectedFilter) 
            ? new SimpleDateFormat("ha", Locale.getDefault())
            : new SimpleDateFormat("MMM dd", Locale.getDefault());

        for (int i = 0; i < bucketKeys.size(); i++) {
            long bTime = bucketKeys.get(i);
            xLabels.add(fmt.format(new Date(bTime)));
            float mins = buckets.get(bTime) / 60000f;
            entries.add(new BarEntry(i, mins));
        }
        
        BarDataSet ds = new BarDataSet(entries, "Duration (mins)");
        ds.setColor(Color.parseColor("#4CAF50"));
        ds.setDrawValues(false);
        
        BarData barData = new BarData(ds);
        barData.setBarWidth(0.6f);
        
        barChart.getXAxis().setValueFormatter(new IndexAxisValueFormatter(xLabels));
        barChart.setData(barData);
        barChart.invalidate();

        calculatePrediction(summary.getTotalDurationMs(), summary.getObservedDays());
    }

    private String formatAutomaticBreakdown(FoggingReportSummary summary) {
        Map<String, Long> strategyDurations = summary.getAutoStrategyDurationMs();
        if (strategyDurations.isEmpty()) {
            return "No strategy details";
        }

        String[] order = {"startup", "normal", "hot", "cold"};
        String[] labels = {"Startup", "Normal", "Hot", "Cold"};
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < order.length; i++) {
            Long durationMs = strategyDurations.get(order[i]);
            if (durationMs != null && durationMs > 0) {
                parts.add(labels[i] + " " + (durationMs / 60000) + "m");
            }
        }

        return parts.isEmpty() ? "No strategy details" : String.join(" · ", parts);
    }

    private void calculatePrediction(long totalDurationMsInPeriod, int observedDays) {
        if (observedDays == 0) observedDays = 1;
        float hoursFogged = totalDurationMsInPeriod / (1000f * 60f * 60f);
        float avgHoursPerDay = hoursFogged / observedDays;
        
        float conversionRate = 4.8f; // L/hr
        float dailyConsumptionLiters = avgHoursPerDay * conversionRate;
        
        db.collection("devices").document(selectedDeviceId)
            .collection("parameterLogs")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(1)
            .get()
            .addOnSuccessListener(snapshots -> {
                layoutLoading.setVisibility(View.GONE);
                if (!snapshots.isEmpty()) {
                    DocumentSnapshot latest = snapshots.getDocuments().get(0);
                    Double waterLevelPct = latest.getDouble("water_level");
                    if (waterLevelPct == null) {
                        waterLevelPct = latest.getDouble("waterLevel");
                    }
                    if (waterLevelPct != null) {
                        tvWaterLevel.setText(String.format(Locale.getDefault(), "%.0f%%", waterLevelPct));
                        
                        float tankVolumeLiters = 61.7f;
                        float currentLiters = tankVolumeLiters * (waterLevelPct.floatValue() / 100f);
                        float thresholdLiters = tankVolumeLiters * ((float) refillStartLevel / 100f);
                        
                        float consumableLiters = currentLiters - thresholdLiters;
                        
                        if (consumableLiters <= 0) {
                            tvRefillTime.setText("Refill overdue");
                            tvRefillTime.setTextColor(ContextCompat.getColor(tvRefillTime.getContext(), R.color.alert_orange));
                        } else if (dailyConsumptionLiters > 0) {
                            float daysRemaining = consumableLiters / dailyConsumptionLiters;
                            tvRefillTime.setTextColor(ContextCompat.getColor(tvRefillTime.getContext(), R.color.text_dark));
                            if (daysRemaining >= 1.0f) {
                                tvRefillTime.setText(String.format(Locale.getDefault(), "~%.1f days", daysRemaining));
                            } else {
                                float hours = daysRemaining * 24f;
                                tvRefillTime.setText(String.format(Locale.getDefault(), "~%.1f hours", hours));
                            }
                        } else {
                            tvRefillTime.setText("Insufficient usage data for estimate");
                            tvRefillTime.setTextColor(ContextCompat.getColor(tvRefillTime.getContext(), R.color.nav_inactive));
                        }
                    } else {
                        tvWaterLevel.setText("-");
                        tvRefillTime.setText("Unknown");
                        tvRefillTime.setTextColor(ContextCompat.getColor(tvRefillTime.getContext(), R.color.nav_inactive));
                    }
                } else {
                    tvWaterLevel.setText("-");
                    tvRefillTime.setText("Insufficient usage data for estimate");
                    tvRefillTime.setTextColor(ContextCompat.getColor(tvRefillTime.getContext(), R.color.nav_inactive));
                }
            })
            .addOnFailureListener(e -> {
                layoutLoading.setVisibility(View.GONE);
                tvWaterLevel.setText("-");
                tvRefillTime.setText("Unknown (Error loading data)");
                tvRefillTime.setTextColor(ContextCompat.getColor(tvRefillTime.getContext(), R.color.nav_inactive));
            });
    }

    private void exportPdf() {
        if (getContext() == null || processedSessions == null || processedSessions.isEmpty()) {
            Toast.makeText(getContext(), "No data to export", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(getContext(), "Generating PDF...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try {
                CycleReportGenerator generator = new CycleReportGenerator(getContext());
                
                SharedPreferences prefs = requireContext().getSharedPreferences("basilience_prefs", Context.MODE_PRIVATE);
                String userName = prefs.getString("user_name", "User");
                
                // Pass the processed sessions to the generator
                File pdfFile = generator.generateFoggingReportPdf(selectedDeviceId, currentSelectedFilter, processedSessions, userName);

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

    private void showInfoDialog() {
        if (getContext() == null) return;
        NotificationHelper.showInfo(requireContext(), "How to use Fogging Reports",
                "What does this page do?\n" +
                    "This page shows you exactly when and how long the fogger ran. It helps you track water usage and tells you when the reservoir needs to be refilled.\n\n" +
                    "Why is this useful?\n" +
                    "By seeing how much water the fogger uses each day, the system predicts when the tank will run low, so you can refill it before the plants run out of water.\n\n" +
                    "How to use it?\n" +
                    "• Use the buttons (Today, 7 Days, etc.) to see past fogging times.\n" +
                    "• The 'Water Outlook' section estimates your remaining water based on recent usage.\n" +
                    "• 'Recent Activity' lists the exact times the fogger turned on.",
                "Got it");
    }
}
