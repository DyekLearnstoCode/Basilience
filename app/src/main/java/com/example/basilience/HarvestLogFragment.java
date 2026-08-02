package com.example.basilience;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.EventListener;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;

public class HarvestLogFragment extends Fragment {

    private TextView tvCycleLabel, tvStatus, tvTotalWeight, tvHarvestCount, tvExpectedDate, tvFrequency, tvNextHarvestLabel;
    private android.widget.ImageView btnEditFrequency;
    private com.google.android.material.button.MaterialButton btnExportPdf;
    private com.google.android.material.floatingactionbutton.FloatingActionButton fabAddHarvest;
    private com.google.android.material.button.MaterialButton btnCompleteCycle;
    private RecyclerView recyclerHarvest;
    private LineChart harvestChart;
    
    private Database_Helper dbHelper;
    private String cycleId;
    private int cycleNumber;
    private HarvestAdapter adapter;
    private List<Harvest> harvestList = new ArrayList<>();
    private ListenerRegistration harvestListener;
    private ListenerRegistration cycleListener; // To listen to cycle summary updates
    private boolean isFirstLoad = true;
    private boolean isFirstChartLoad = true;
    
    private String userRole = RoleConstants.ROLE_FARMER;
    private String userName = "Anonymous";
    private String currentHarvestSource = "MANUAL";
    private Harvest editingHarvest = null;
    private Cycle currentCycle = null;

    public HarvestLogFragment() {
        // Required empty public constructor
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.cycle_harvest_log, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        dbHelper = new Database_Helper();
        NavController navController = Navigation.findNavController(view);

        if (getArguments() != null) {
            cycleId = getArguments().getString("cycleId");
            cycleNumber = getArguments().getInt("cycleNumber", 1);
        }

        // Summary Card Views
        tvCycleLabel = view.findViewById(R.id.tvCycleLabel);
        tvStatus = view.findViewById(R.id.tvStatus);
        tvTotalWeight = view.findViewById(R.id.tvTotalWeight);
        tvHarvestCount = view.findViewById(R.id.tvHarvestCount);
        tvExpectedDate = view.findViewById(R.id.tvExpectedDate);
        tvFrequency = view.findViewById(R.id.tvFrequency);
        tvNextHarvestLabel = view.findViewById(R.id.tvNextHarvestLabel);
        btnEditFrequency = view.findViewById(R.id.btnEditFrequency);
        btnExportPdf = view.findViewById(R.id.btnExportPdf);
        btnCompleteCycle = view.findViewById(R.id.btnCompleteCycle);

        tvCycleLabel.setText("Cycle #" + cycleNumber);

        recyclerHarvest = view.findViewById(R.id.recyclerHarvest);
        harvestChart = view.findViewById(R.id.harvestChart);
        fabAddHarvest = view.findViewById(R.id.fabAddHarvest);

        View btnBack = view.findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setVisibility(View.VISIBLE);
            btnBack.setOnClickListener(v -> navController.popBackStack());
        }

        fabAddHarvest.setOnClickListener(v -> {
            if (currentCycle != null) {
                checkHarvestReadinessAndShowDialog();
            } else {
                showHarvestDialog(null);
            }
        });

        if (btnCompleteCycle != null) {
            btnCompleteCycle.setOnClickListener(v -> showCompleteCycleConfirmation());
        }

        if (btnEditFrequency != null) {
            btnEditFrequency.setOnClickListener(v -> showEditFrequencyDialog());
        }

        if (btnExportPdf != null) {
            btnExportPdf.setOnClickListener(v -> exportPdf());
        }

        setupRecyclerView();
        loadHarvestData();
        loadChartData();
        loadCycleSummary();
    }

    @Override
    public void onStart() {
        super.onStart();
        fetchUserInfo();
    }

    private void fetchUserInfo() {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid != null) {
            dbHelper.getUserProfile(uid).addOnSuccessListener(documentSnapshot -> {
                if (documentSnapshot.exists()) {
                    userRole = documentSnapshot.getString("role");
                    userName = documentSnapshot.getString("fullName");
                    updateUIForRole();
                }
            });
        }
    }

    private void updateUIForRole() {
        if (adapter == null) {
            setupRecyclerView();
        } else {
            boolean isAdmin = RoleConstants.ROLE_ADMIN.equalsIgnoreCase(userRole);
            boolean isActive = currentCycle != null && "ACTIVE".equalsIgnoreCase(currentCycle.getStatus());
            adapter.setCanManage(isAdmin && isActive);
            adapter.notifyDataSetChanged();
        }

        if (currentCycle != null) {
            updateSummaryUI(currentCycle);
        }
    }

    private void setupRecyclerView() {
        // canManage is true only if user is Admin AND we'll check cycle status in updateSummaryUI
        // For initial load, we assume based on role and update later
        boolean canManage = RoleConstants.ROLE_ADMIN.equalsIgnoreCase(userRole);

        adapter = new HarvestAdapter(harvestList, canManage, new HarvestAdapter.OnHarvestActionListener() {
            @Override
            public void onEdit(Harvest harvest) {
                showHarvestDialog(harvest);
            }

            @Override
            public void onDelete(Harvest harvest) {
                confirmDelete(harvest);
            }
        });
        recyclerHarvest.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerHarvest.setAdapter(adapter);
    }

    private void checkHarvestReadinessAndShowDialog() {
        if (currentCycle == null) return;

        String status = currentCycle.getStatus();
        if ("COMPLETED".equalsIgnoreCase(status)) {
            NotificationHelper.showError(getContext(), "Cycle is completed. No more harvests allowed.");
            return;
        }

        Timestamp nextHarvest = currentCycle.getNextHarvestDate();
        Timestamp now = Timestamp.now();

        if (nextHarvest == null || now.compareTo(nextHarvest) >= 0) {
            showHarvestDialog(null);
            return;
        }

        // Calculate remaining time
        long diffMillis = nextHarvest.toDate().getTime() - now.toDate().getTime();
        long diffDays = (long) Math.ceil(diffMillis / (1000.0 * 60 * 60 * 24));
        String dateStr = DateUtils.formatDate(nextHarvest);
        String countdown = "(" + diffDays + " days remaining)\n\nRecording an early harvest will reset the schedule frequency from today.";

        NotificationHelper.showHarvestNotReadyDialog(requireContext(), dateStr, countdown, () -> showHarvestDialog(null));
    }

    private void showHarvestDialog(@Nullable Harvest harvest) {
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_harvest, null);
        
        TextView tvTitle = dialogView.findViewById(R.id.tvDialogTitle);
        // Hide the title inside the layout because NotificationHelper provides one
        tvTitle.setVisibility(View.GONE);

        TextInputEditText etWeight = dialogView.findViewById(R.id.etWeight);
        TextInputEditText etNotes = dialogView.findViewById(R.id.etNotes);
        Button btnSave = dialogView.findViewById(R.id.btnSaveHarvest);
        Button btnCancel = dialogView.findViewById(R.id.btnCancel);
        Button btnReadSensor = dialogView.findViewById(R.id.btnReadSensor);

        editingHarvest = harvest;
        currentHarvestSource = "MANUAL";
        String dialogTitle = "Log New Harvest";

        if (harvest != null) {
            dialogTitle = "Edit Harvest";
            etWeight.setText(String.valueOf(harvest.getWeight()));
            etNotes.setText(harvest.getNotes());
            btnSave.setText("Update");
            currentHarvestSource = harvest.getSource();
        }

        AlertDialog dialog = NotificationHelper.showCustomViewDialog(requireContext(), dialogTitle, dialogView);

        btnReadSensor.setOnClickListener(v -> {
            com.google.firebase.database.DatabaseReference sensorRef = dbHelper.getSensorsReference();
            if (sensorRef != null) {
                sensorRef.child("weight").get().addOnSuccessListener(snapshot -> {
                    if (snapshot.exists()) {
                        Double weight = snapshot.getValue(Double.class);
                        if (weight != null) {
                            etWeight.setText(String.valueOf(weight));
                            currentHarvestSource = "SENSOR";
                            NotificationHelper.showSuccess(getContext(), "Weight read from sensor");
                        }
                    }
                }).addOnFailureListener(e -> NotificationHelper.showError(getContext(), "Failed to read sensor: " + e.getMessage()));
            } else {
                NotificationHelper.showError(getContext(), "No active device context for sensors.");
            }
        });

        btnCancel.setOnClickListener(v -> {
            if (dialog != null) dialog.dismiss();
        });

        btnSave.setOnClickListener(v -> {
            String weightStr = etWeight.getText().toString().trim();
            if (weightStr.isEmpty()) {
                etWeight.setError("Required");
                return;
            }

            double weight = Double.parseDouble(weightStr);
            String notes = etNotes.getText().toString().trim();

            if (editingHarvest != null) {
                updateHarvest(weight, notes, dialog);
            } else {
                createHarvest(weight, notes, dialog);
            }
        });
    }

    private void confirmDelete(Harvest harvest) {
        NotificationHelper.showConfirmation(requireContext(), "Delete Harvest",
                "Are you sure you want to delete this harvest entry? This will update the cycle totals.",
                "Delete", "Cancel", () -> {
                    dbHelper.deleteHarvestTransaction(cycleId, harvest.getId(), harvest.getWeight())
                            .addOnSuccessListener(aVoid -> {
                                NotificationHelper.showSuccess(getContext(), "Harvest deleted");
                                loadChartData(); // Refresh chart manually
                            })
                            .addOnFailureListener(e -> NotificationHelper.showError(getContext(), "Error: " + e.getMessage()));
                });
    }

    private void loadCycleSummary() {
        if (cycleListener != null) cycleListener.remove();

        SharedPreferences prefs = requireContext().getSharedPreferences("basilience_prefs", Context.MODE_PRIVATE);
        String deviceId = prefs.getString("selected_device_id", null);

        if (deviceId != null && cycleId != null) {
            dbHelper.setSelectedDeviceId(deviceId);
            cycleListener = dbHelper.listenToCycleDetails(cycleId, (documentSnapshot, e) -> {
                if (e != null || documentSnapshot == null || !documentSnapshot.exists()) return;

                Cycle cycle = documentSnapshot.toObject(Cycle.class);
                if (cycle != null) {
                    updateSummaryUI(cycle);
                }
            });
        }
    }

    private void updateSummaryUI(Cycle cycle) {
        this.currentCycle = cycle;
        String rawStatus = cycle.getStatus();
        String status = (rawStatus == null || rawStatus.isEmpty()) ? "ACTIVE" : rawStatus.toUpperCase();
        tvStatus.setText(status);
        tvTotalWeight.setText(String.format(Locale.getDefault(), "%.1fg", cycle.getTotalHarvestWeight()));
        tvHarvestCount.setText(String.valueOf(cycle.getTotalHarvestCount()));

        boolean isActive = "ACTIVE".equalsIgnoreCase(status);
        boolean isCompleted = "COMPLETED".equalsIgnoreCase(status);

        // Update Labels and Dates based on status
        if (isCompleted) {
            tvNextHarvestLabel.setText("Completed Date");
            if (cycle.getEndDate() != null) {
                tvExpectedDate.setText(DateUtils.formatDate(cycle.getEndDate()));
            } else {
                tvExpectedDate.setText("Finished");
            }
            if (tvFrequency != null) tvFrequency.setVisibility(View.GONE);
        } else {
            tvNextHarvestLabel.setText("Next Harvest");
            if (tvFrequency != null) {
                tvFrequency.setVisibility(View.VISIBLE);
                tvFrequency.setText("Every " + cycle.getHarvestFrequencyDays() + " Days");
            }

            // Display Next Harvest if available, otherwise fallback to expected
            if (cycle.getNextHarvestDate() != null) {
                tvExpectedDate.setText(DateUtils.formatDate(cycle.getNextHarvestDate()));
            } else if (cycle.getExpectedHarvestDate() != null) {
                tvExpectedDate.setText(DateUtils.formatDate(cycle.getExpectedHarvestDate()));
            } else {
                tvExpectedDate.setText("Ongoing");
            }
        }

        // Update status background color
        int statusColor = getResources().getColor(R.color.primary);
        boolean isAdmin = RoleConstants.ROLE_ADMIN.equalsIgnoreCase(userRole);

        if (btnEditFrequency != null) {
            btnEditFrequency.setVisibility(isActive && isAdmin ? View.VISIBLE : View.GONE);
        }

        if (btnExportPdf != null) {
            btnExportPdf.setVisibility(isAdmin ? View.VISIBLE : View.GONE);
        }

        if ("COMPLETED".equalsIgnoreCase(status)) {
            statusColor = getResources().getColor(R.color.nav_inactive);
            fabAddHarvest.setVisibility(View.GONE);
            if (btnCompleteCycle != null) btnCompleteCycle.setVisibility(View.GONE);
        } else if (isActive) {
            statusColor = getResources().getColor(R.color.primary);
            fabAddHarvest.setVisibility(View.VISIBLE);
            if (btnCompleteCycle != null && isAdmin) {
                btnCompleteCycle.setVisibility(View.VISIBLE);
            }
        }
        
        // Update adapter management state based on cycle status
        if (adapter != null) {
            adapter.setCanManage(RoleConstants.ROLE_ADMIN.equalsIgnoreCase(userRole) && isActive);
            adapter.notifyDataSetChanged();
        }
        
        if (tvStatus.getBackground() != null) {
            tvStatus.getBackground().setTint(statusColor);
        }
    }

    private void exportPdf() {
        if (!RoleConstants.ROLE_ADMIN.equalsIgnoreCase(userRole)) {
            NotificationHelper.showError(getContext(), "Only administrators can export reports.");
            return;
        }

        if (currentCycle == null) {
            NotificationHelper.showError(getContext(), "Cycle data not loaded yet");
            return;
        }

        NotificationHelper.showConfirmation(requireContext(), "Export Production Report",
                "Generate a PDF report containing:\n\n• Cycle summary\n• Harvest trend chart\n• Harvest history records\n\nDo you want to continue?",
                "Export", "Cancel", this::performPdfGeneration);
    }

    private void performPdfGeneration() {
        try {
            CycleReportGenerator generator = new CycleReportGenerator(requireContext());
            android.graphics.Bitmap chartBitmap = (harvestChart != null && !harvestList.isEmpty()) ? harvestChart.getChartBitmap() : null;
            File pdfFile = generator.generateCycleSummaryPdf(currentCycle, chartBitmap, harvestList, userName);
            showExportSuccessDialog(pdfFile);
        } catch (Exception e) {
            showExportFailedDialog(e.getMessage());
            e.printStackTrace();
        }
    }

    private void showExportSuccessDialog(File file) {
        NotificationHelper.showTripleActionDialog(requireContext(), "Report Generated",
                "Your PDF report has been saved successfully.\n\nFile:\n" + file.getName(),
                "Share", "Open", "Close",
                new NotificationHelper.TripleActionCallback() {
                    @Override
                    public void onAction1() {
                        sharePdf(file);
                    }

                    @Override
                    public void onAction2() {
                        openPdf(file);
                    }
                });
    }

    private void showExportFailedDialog(String message) {
        NotificationHelper.showError(requireContext(), "Export Failed: " + message);
    }

    private void openPdf(File file) {
        Uri uri = FileProvider.getUriForFile(requireContext(),
                requireContext().getPackageName() + ".fileprovider", file);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/pdf");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (Exception e) {
            NotificationHelper.showError(getContext(), "No app found to open PDF");
        }
    }

    private void sharePdf(File file) {
        Uri uri = FileProvider.getUriForFile(requireContext(),
                requireContext().getPackageName() + ".fileprovider", file);

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("application/pdf");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        startActivity(Intent.createChooser(intent, "Share Production Report"));
    }


    private void showEditFrequencyDialog() {
        if (currentCycle == null) return;

        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_edit_frequency, null);
        TextInputEditText etFrequency = dialogView.findViewById(R.id.etFrequency);
        etFrequency.setText(String.valueOf(currentCycle.getHarvestFrequencyDays()));

        AlertDialog dialog = NotificationHelper.showCustomViewDialog(requireContext(), "Edit Harvest Frequency", dialogView);
        
        // Find buttons in the custom layout
        Button btnUpdate = dialogView.findViewById(R.id.btnUpdate);
        Button btnCancel = dialogView.findViewById(R.id.btnCancel);

        if (btnUpdate != null) {
            btnUpdate.setOnClickListener(v -> {
                String input = etFrequency.getText().toString().trim();
                if (input.isEmpty()) return;

                int newFreq = Integer.parseInt(input);
                if (newFreq < 1 || newFreq > 365) {
                    NotificationHelper.showError(getContext(), "Frequency must be between 1 and 365 days");
                    return;
                }

                dbHelper.updateHarvestFrequency(cycleId, newFreq)
                        .addOnSuccessListener(aVoid -> {
                            NotificationHelper.showSuccess(getContext(), "Frequency updated");
                            if (dialog != null) dialog.dismiss();
                        })
                        .addOnFailureListener(e -> NotificationHelper.showError(getContext(), "Update failed: " + e.getMessage()));
            });
        }

        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> {
                if (dialog != null) dialog.dismiss();
            });
        }
    }

    private void showCompleteCycleConfirmation() {
        NotificationHelper.showConfirmation(requireContext(), "Complete Cycle?",
                "This will:\n- Mark the cycle as COMPLETED\n- Record the completion date\n- Stop further harvest entries",
                "Complete", "Cancel", () -> {
                    dbHelper.completeCycle(cycleId).addOnSuccessListener(aVoid -> {
                        NotificationHelper.showSuccess(requireContext(), "Cycle completed");
                    }).addOnFailureListener(e -> {
                        NotificationHelper.showError(requireContext(), "Failed to complete cycle: " + e.getMessage());
                    });
                });
    }

    private void createHarvest(double weight, String notes, androidx.appcompat.app.AlertDialog dialog) {
        Harvest newHarvest = new Harvest(
                Timestamp.now(),
                weight,
                FirebaseAuth.getInstance().getUid(),
                userName,
                currentHarvestSource,
                notes
        );

        dbHelper.addHarvestTransaction(cycleId, newHarvest)
                .addOnSuccessListener(aVoid -> {
                    NotificationHelper.showSuccess(getContext(), "Harvest saved");
                    dialog.dismiss();
                    loadChartData(); // Refresh chart manually
                })
                .addOnFailureListener(e -> NotificationHelper.showError(getContext(), "Error: " + e.getMessage()));
    }

    private void updateHarvest(double newWeight, String notes, androidx.appcompat.app.AlertDialog dialog) {
        double oldWeight = editingHarvest.getWeight();
        Map<String, Object> updates = new HashMap<>();
        updates.put("weight", newWeight);
        updates.put("notes", notes);
        updates.put("source", currentHarvestSource);

        dbHelper.updateHarvestTransaction(cycleId, editingHarvest.getId(), oldWeight, newWeight, updates)
                .addOnSuccessListener(aVoid -> {
                    NotificationHelper.showSuccess(getContext(), "Harvest updated");
                    dialog.dismiss();
                    loadChartData(); // Refresh chart manually
                })
                .addOnFailureListener(e -> NotificationHelper.showError(getContext(), "Error: " + e.getMessage()));
    }

    private void loadHarvestData() {
        if (harvestListener != null) harvestListener.remove();

        SharedPreferences prefs = requireContext().getSharedPreferences("basilience_prefs", Context.MODE_PRIVATE);
        String deviceId = prefs.getString("selected_device_id", null);

        if (deviceId != null && cycleId != null) {
            dbHelper.setSelectedDeviceId(deviceId);
            // Real-time listener for the RecyclerView list (Newest First)
            harvestListener = dbHelper.listenToHarvestEntries(cycleId, (value, error) -> {
                if (error != null || value == null) return;

                if (isFirstLoad) {
                    harvestList.clear();
                    for (com.google.firebase.firestore.QueryDocumentSnapshot doc : value) {
                        Harvest entry = doc.toObject(Harvest.class);
                        entry.setId(doc.getId());
                        harvestList.add(entry);
                    }
                    adapter.notifyDataSetChanged();
                    isFirstLoad = false;
                } else {
                    for (com.google.firebase.firestore.DocumentChange dc : value.getDocumentChanges()) {
                        Harvest entry = dc.getDocument().toObject(Harvest.class);
                        entry.setId(dc.getDocument().getId());

                        int oldIndex = dc.getOldIndex();
                        int newIndex = dc.getNewIndex();

                        switch (dc.getType()) {
                            case ADDED:
                                harvestList.add(newIndex, entry);
                                adapter.notifyItemInserted(newIndex);
                                break;
                            case MODIFIED:
                                if (oldIndex == newIndex) {
                                    harvestList.set(newIndex, entry);
                                    adapter.notifyItemChanged(newIndex);
                                } else {
                                    harvestList.remove(oldIndex);
                                    harvestList.add(newIndex, entry);
                                    adapter.notifyItemMoved(oldIndex, newIndex);
                                    adapter.notifyItemChanged(newIndex);
                                }
                                break;
                            case REMOVED:
                                harvestList.remove(oldIndex);
                                adapter.notifyItemRemoved(oldIndex);
                                break;
                        }
                    }
                }
            });
        }
    }

    private void loadChartData() {
        if (cycleId == null) return;
        
        dbHelper.getHarvestHistoryForChart(cycleId).addOnSuccessListener(value -> {
            if (value == null || value.isEmpty()) {
                harvestChart.clear();
                return;
            }

            List<Entry> chartEntries = new ArrayList<>();
            List<String> dateLabels = new ArrayList<>();
            int i = 0;
            for (com.google.firebase.firestore.QueryDocumentSnapshot doc : value) {
                Harvest entry = doc.toObject(Harvest.class);
                chartEntries.add(new Entry(i++, (float) entry.getWeight()));
                dateLabels.add(DateUtils.formatShortDate(entry.getHarvestDate()));
            }
            updateChart(chartEntries, dateLabels, !isFirstChartLoad);
            isFirstChartLoad = false;
        });
    }

    private void updateChart(List<Entry> chartEntries, List<String> dateLabels, boolean shouldAnimate) {
        if (chartEntries.isEmpty() || getContext() == null) {
            harvestChart.clear();
            return;
        }

        LineData lineData = harvestChart.getData();
        if (lineData != null && lineData.getDataSetCount() > 0) {
            LineDataSet dataSet = (LineDataSet) lineData.getDataSetByIndex(0);
            dataSet.setValues(chartEntries);
            dataSet.setValueFormatter(new ValueFormatter() {
                @Override
                public String getFormattedValue(float value) {
                    return String.format(Locale.getDefault(), "%.0f", value);
                }
            });
            lineData.notifyDataChanged();
            harvestChart.notifyDataSetChanged();
        } else {
            LineDataSet dataSet = new LineDataSet(chartEntries, "Harvest Weight");
            int primaryColor = getResources().getColor(R.color.primary);
            
            dataSet.setColor(primaryColor);
            dataSet.setValueTextColor(getResources().getColor(R.color.black));
            dataSet.setCircleColor(primaryColor);
            dataSet.setLineWidth(2.5f);
            dataSet.setCircleRadius(5f);
            dataSet.setDrawCircleHole(true);
            dataSet.setCircleHoleColor(getResources().getColor(R.color.white));
            dataSet.setValueTextSize(10f);
            dataSet.setDrawFilled(true);
            dataSet.setMode(LineDataSet.Mode.LINEAR);
            dataSet.setFillColor(primaryColor);
            dataSet.setFillAlpha(40);
            dataSet.setValueFormatter(new ValueFormatter() {
                @Override
                public String getFormattedValue(float value) {
                    return String.format(Locale.getDefault(), "%.0f", value);
                }
            });

            lineData = new LineData(dataSet);
            harvestChart.setData(lineData);
        }
        
        harvestChart.setExtraOffsets(5f, 5f, 5f, 5f);

        XAxis xAxis = harvestChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawGridLines(false);
        xAxis.setGranularity(1f);
        
        // Dynamic Label Rotation
        if (dateLabels.size() > 5) {
            xAxis.setLabelRotationAngle(-35f);
            xAxis.setLabelCount(dateLabels.size());
        } else {
            xAxis.setLabelRotationAngle(0f);
            xAxis.setLabelCount(dateLabels.size());
        }

        if (dateLabels.size() == 1) {
            xAxis.setAxisMinimum(-0.5f);
            xAxis.setAxisMaximum(0.5f);
        } else {
            xAxis.resetAxisMinimum();
            xAxis.resetAxisMaximum();
        }

        xAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                int index = (int) value;
                if (index >= 0 && index < dateLabels.size()) {
                    return dateLabels.get(index);
                }
                return "";
            }
        });

        harvestChart.getAxisLeft().setDrawGridLines(true);
        harvestChart.getAxisLeft().setAxisMinimum(0f);
        harvestChart.getAxisLeft().setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.format(Locale.getDefault(), "%.0f", value);
            }
        });

        harvestChart.getAxisRight().setEnabled(false);
        harvestChart.getDescription().setEnabled(false);
        harvestChart.getLegend().setEnabled(false);
        
        if (shouldAnimate) {
            harvestChart.animateX(800);
        }
        harvestChart.invalidate();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (harvestListener != null) harvestListener.remove();
        if (cycleListener != null) cycleListener.remove();
    }
}
