package com.example.basilience;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
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

    private static final String TAG = "HarvestLogFragment";

    private TextView tvCycleLabel, tvStatus, tvTotalWeight, tvHarvestCount, tvExpectedDate, tvFrequency, tvNextHarvestLabel;
    private TextView tvCycleRange, tvHarvestInterpretation, tvEmptyHistory, tvEmptyChart;
    // Date labels currently plotted, so the tap marker can name the point
    // the farmer touched using the exact label the axis already shows.
    private final List<String> currentChartLabels = new ArrayList<>();
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
    private NotificationHelper.LoadingHandle loadingHandle;
    // Explicit re-entrancy guard for the Add/Edit Harvest save action - the
    // button's own setEnabled(false) is not by itself a guaranteed block
    // against a second click already dispatched before it takes effect.
    private boolean isHarvestSubmitting = false;
    // Gate repeat listener errors (e.g. on every reconnect retry) down to a
    // single Snackbar instead of spamming one per failed attempt.
    private boolean cycleSummaryErrorNotified = false;
    private boolean harvestListErrorNotified = false;

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
        tvCycleRange = view.findViewById(R.id.tvCycleRange);
        tvHarvestInterpretation = view.findViewById(R.id.tvHarvestInterpretation);
        tvEmptyHistory = view.findViewById(R.id.tvEmptyHistory);
        tvEmptyChart = view.findViewById(R.id.tvEmptyChart);

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
            if (currentCycle == null) {
                NotificationHelper.showError(getContext(), "Cycle data is still loading. Please try again in a moment.");
                return;
            }
            checkHarvestReadinessAndShowDialog();
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
        if (!RoleConstants.ROLE_ADMIN.equalsIgnoreCase(userRole)) {
            NotificationHelper.showError(requireContext(), "Next harvest is scheduled for " + dateStr + ".");
            return;
        }
        String countdown = "(" + diffDays + " days remaining)\n\nAn administrator override will reset the schedule frequency from today.";
        NotificationHelper.showHarvestNotReadyDialog(requireContext(), dateStr, countdown, () -> showHarvestDialog(null));
    }

    private void showHarvestDialog(@Nullable Harvest harvest) {
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_harvest, null);
        
        TextView tvTitle = dialogView.findViewById(R.id.tvDialogTitle);
        // Hide the title inside the layout because NotificationHelper provides one
        tvTitle.setVisibility(View.GONE);

        TextInputEditText etWeight = dialogView.findViewById(R.id.etWeight);
        TextInputEditText etNotes = dialogView.findViewById(R.id.etNotes);
        com.google.android.material.textfield.TextInputLayout layoutWeight = dialogView.findViewById(R.id.layoutWeight);
        TextView tvHarvestDate = dialogView.findViewById(R.id.tvHarvestDate);
        Button btnSave = dialogView.findViewById(R.id.btnSaveHarvest);
        Button btnCancel = dialogView.findViewById(R.id.btnCancel);
        Button btnReadSensor = dialogView.findViewById(R.id.btnReadSensor);

        // No load-cell/weight publisher exists in the ESP32 firmware. Do not
        // present a control that can only read a fabricated/stale RTDB field.
        btnReadSensor.setVisibility(View.GONE);

        editingHarvest = harvest;
        currentHarvestSource = "MANUAL";
        String dialogTitle = "Log New Harvest";

        if (harvest != null) {
            dialogTitle = "Edit Harvest";
            etWeight.setText(String.valueOf(harvest.getWeight()));
            etNotes.setText(harvest.getNotes());
            btnSave.setText("Update");
            currentHarvestSource = harvest.getSource();
            // Editing never changes the original recorded date.
            if (tvHarvestDate != null) {
                tvHarvestDate.setText("Harvest Date: " + DateUtils.formatDate(harvest.getHarvestDate()));
            }
        } else if (tvHarvestDate != null) {
            tvHarvestDate.setText("Harvest Date: " + DateUtils.formatDate(Timestamp.now()));
        }

        AlertDialog dialog = NotificationHelper.showCustomViewDialog(requireContext(), dialogTitle, dialogView);

        btnCancel.setOnClickListener(v -> {
            if (dialog != null) dialog.dismiss();
        });

        btnSave.setOnClickListener(v -> {
            if (isHarvestSubmitting) return;
            if (layoutWeight != null) layoutWeight.setError(null);

            String weightStr = etWeight.getText().toString().trim();
            if (weightStr.isEmpty()) {
                if (layoutWeight != null) layoutWeight.setError("Required");
                return;
            }

            final double weight;
            try {
                weight = Double.parseDouble(weightStr);
            } catch (NumberFormatException error) {
                if (layoutWeight != null) layoutWeight.setError("Enter a valid numeric weight");
                return;
            }
            if (!Double.isFinite(weight) || weight <= 0.0) {
                if (layoutWeight != null) layoutWeight.setError("Weight must be greater than zero");
                return;
            }
            String notes = etNotes.getText().toString().trim();
            isHarvestSubmitting = true;
            btnSave.setEnabled(false);

            if (editingHarvest != null) {
                updateHarvest(weight, notes, dialog, btnSave);
            } else {
                createHarvest(weight, notes, dialog, btnSave);
            }
        });
    }

    private void confirmDelete(Harvest harvest) {
        NotificationHelper.showDestructiveConfirmation(requireContext(), "Delete Harvest",
                "Are you sure you want to delete this harvest entry? This will update the cycle totals.",
                "Delete", () -> {
                    loadingHandle = NotificationHelper.showLoading(requireContext(), "Deleting harvest...", () -> {
                        if (!isAdded()) return;
                        NotificationHelper.showError(requireContext(), "Request timed out. Please refresh before trying again.");
                    });
                    dbHelper.deleteHarvestTransaction(cycleId, harvest.getId(), harvest.getWeight())
                            .addOnSuccessListener(aVoid -> {
                                if (!isAdded()) return;
                                dismissLoading();
                                NotificationHelper.showSuccess(requireContext(), "Harvest deleted");
                                loadChartData(); // Refresh chart manually
                            })
                            .addOnFailureListener(e -> {
                                if (!isAdded()) return;
                                dismissLoading();
                                Log.e(TAG, "Failed to delete harvest entry for cycleId=" + cycleId, e);
                                NotificationHelper.showError(requireContext(), "Unable to delete this harvest entry. Please try again.");
                            });
                });
    }

    private void loadCycleSummary() {
        if (cycleListener != null) cycleListener.remove();

        SharedPreferences prefs = requireContext().getSharedPreferences("basilience_prefs", Context.MODE_PRIVATE);
        String deviceId = prefs.getString("selected_device_id", null);

        if (deviceId != null && cycleId != null) {
            dbHelper.setSelectedDeviceId(deviceId);
            cycleListener = dbHelper.listenToCycleDetails(cycleId, (documentSnapshot, e) -> {
                if (e != null) {
                    Log.e(TAG, "Cycle summary listener error for cycleId=" + cycleId, e);
                    if (!cycleSummaryErrorNotified && isAdded() && getView() != null) {
                        cycleSummaryErrorNotified = true;
                        NotificationHelper.showSnackbar(getView(), "Unable to refresh cycle summary. Showing last known data.");
                    }
                    return;
                }
                cycleSummaryErrorNotified = false;
                if (documentSnapshot == null || !documentSnapshot.exists()) return;

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
        // Display formatting only - the stored grams and the transactional
        // totals behind them are untouched.
        tvTotalWeight.setText(HarvestFormatter.formatWeight(cycle.getTotalHarvestWeight()));
        tvHarvestCount.setText(String.valueOf(cycle.getTotalHarvestCount()));
        if (tvHarvestInterpretation != null) {
            // Same helper the PDF prints, so screen and export can't diverge.
            tvHarvestInterpretation.setText(HarvestFormatter.buildProductionSummary(
                    status, cycle.getTotalHarvestWeight(), cycle.getTotalHarvestCount()));
        }

        boolean isActive = "ACTIVE".equalsIgnoreCase(status);
        boolean isCompleted = "COMPLETED".equalsIgnoreCase(status);

        if (tvCycleRange != null) {
            String start = cycle.getStartDate() != null ? DateUtils.formatDate(cycle.getStartDate()) : "--";
            String end = isCompleted
                    ? (cycle.getEndDate() != null ? DateUtils.formatDate(cycle.getEndDate()) : "Finished")
                    : "Present";
            tvCycleRange.setText(start + " – " + end);
        }

        // Update Labels and Dates based on status
        if (isCompleted) {
            tvNextHarvestLabel.setText("Completed Date");
            tvExpectedDate.setTextColor(getResources().getColor(R.color.text_dark));
            if (cycle.getEndDate() != null) {
                tvExpectedDate.setText(DateUtils.formatDate(cycle.getEndDate()));
            } else {
                tvExpectedDate.setText("Finished");
            }
            if (tvFrequency != null) tvFrequency.setVisibility(View.GONE);
        } else {
            // Same readiness check checkHarvestReadinessAndShowDialog uses when the
            // FAB is tapped - evaluated here too so the summary can passively show
            // whether harvest entry is currently allowed, without changing when it
            // actually is.
            boolean harvestReady = cycle.getNextHarvestDate() == null
                    || Timestamp.now().compareTo(cycle.getNextHarvestDate()) >= 0;
            tvNextHarvestLabel.setText(harvestReady ? "Ready to Harvest" : "Next Harvest");
            tvExpectedDate.setTextColor(getResources().getColor(
                    harvestReady ? R.color.state_success : R.color.text_dark));
            if (tvFrequency != null) {
                tvFrequency.setVisibility(View.VISIBLE);
                // Date and frequency now occupy separate visual roles, so no
                // joining separator is needed. Display grammar only - the
                // stored frequency value is untouched.
                int frequencyDays = cycle.getHarvestFrequencyDays();
                tvFrequency.setText("Every " + frequencyDays + (frequencyDays == 1 ? " Day" : " Days"));
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
            // Completing a cycle is cultivation work: available to an Admin or
            // an assigned Farmer. Editing harvest frequency and exporting the
            // PDF above stay Admin-only.
            if (btnCompleteCycle != null) {
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
        btnExportPdf.setEnabled(false);
        final Context appContext = requireContext().getApplicationContext();
        // getChartBitmap() captures the chart exactly as drawn, so a marker
        // left over from a tap would otherwise be baked into the PDF. The
        // export must always reflect the full cycle range, not whatever the
        // farmer happens to be zoomed into on screen - so the viewport is
        // reset before capture and restored afterward, without requerying or
        // altering any data.
        android.graphics.Matrix savedHarvestMatrix = null;
        if (harvestChart != null) {
            harvestChart.highlightValue(null);
            savedHarvestMatrix = new android.graphics.Matrix(harvestChart.getViewPortHandler().getMatrixTouch());
            harvestChart.fitScreen();
        }
        final android.graphics.Bitmap chartBitmap =
                harvestChart != null && !harvestList.isEmpty() ? harvestChart.getChartBitmap() : null;
        if (harvestChart != null && savedHarvestMatrix != null) {
            harvestChart.getViewPortHandler().refresh(savedHarvestMatrix, harvestChart, true);
            harvestChart.invalidate();
        }
        final Cycle cycleSnapshot = currentCycle;
        final List<Harvest> harvestSnapshot = new ArrayList<>(harvestList);
        final String userSnapshot = userName;
        final SharedPreferences prefs = requireContext().getSharedPreferences("basilience_prefs", Context.MODE_PRIVATE);
        final String deviceIdSnapshot = prefs.getString("selected_device_id", null);
        final androidx.fragment.app.FragmentActivity hostActivity = requireActivity();
        loadingHandle = NotificationHelper.showLoading(requireContext(), "Generating report...", 30_000L, () -> {
            if (!isAdded()) return;
            btnExportPdf.setEnabled(true);
            NotificationHelper.showError(requireContext(), "Report generation is taking longer than expected.");
        });

        new Thread(() -> {
            File generated = null;
            Exception failure = null;
            try {
                generated = new CycleReportGenerator(appContext)
                        .generateCycleSummaryPdf(deviceIdSnapshot, cycleSnapshot, chartBitmap, harvestSnapshot, userSnapshot);
            } catch (Exception e) {
                failure = e;
            }
            final File result = generated;
            final Exception error = failure;
            hostActivity.runOnUiThread(() -> {
                if (!isAdded()) return;
                dismissLoading();
                btnExportPdf.setEnabled(true);
                if (error == null) {
                    showExportSuccessDialog(result);
                } else {
                    Log.e(TAG, "Failed to generate PDF report for cycleId=" + cycleId, error);
                    showExportFailedDialog("We couldn't generate the PDF report. Please try again.");
                }
            });
        }, "basilience-report-export").start();
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

                btnUpdate.setEnabled(false);
                if (btnCancel != null) btnCancel.setEnabled(false);
                loadingHandle = NotificationHelper.showLoading(requireContext(), "Saving harvest settings...", () -> {
                    if (!isAdded()) return;
                    btnUpdate.setEnabled(true);
                    if (btnCancel != null) btnCancel.setEnabled(true);
                    NotificationHelper.showError(requireContext(), "Request timed out. Please refresh before trying again.");
                });
                dbHelper.updateHarvestFrequency(cycleId, newFreq)
                        .addOnSuccessListener(aVoid -> {
                            if (!isAdded()) return;
                            dismissLoading();
                            btnUpdate.setEnabled(true);
                            if (btnCancel != null) btnCancel.setEnabled(true);
                            NotificationHelper.showSuccess(requireContext(), "Frequency updated");
                            if (dialog != null) dialog.dismiss();
                        })
                        .addOnFailureListener(e -> {
                            if (!isAdded()) return;
                            dismissLoading();
                            btnUpdate.setEnabled(true);
                            if (btnCancel != null) btnCancel.setEnabled(true);
                            Log.e(TAG, "Failed to update harvest frequency for cycleId=" + cycleId, e);
                            NotificationHelper.showError(requireContext(), "Unable to save the harvest frequency. Please try again.");
                        });
            });
        }

        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> {
                if (dialog != null) dialog.dismiss();
            });
        }
    }

    private void showCompleteCycleConfirmation() {
        NotificationHelper.showDestructiveConfirmation(requireContext(), "Complete Growth Cycle?",
                "Completing this cycle will end the current cultivation period and stop "
                        + "normal cultivation automation after the device receives the updated "
                        + "cycle status.\n\nNo further harvests can be recorded for this cycle.",
                "Complete Cycle", () -> {
                    btnCompleteCycle.setEnabled(false);
                    loadingHandle = NotificationHelper.showLoading(requireContext(), "Completing cycle...", () -> {
                        if (!isAdded()) return;
                        btnCompleteCycle.setEnabled(true);
                        NotificationHelper.showError(requireContext(), "Request timed out. Please refresh before trying again.");
                    });
                    dbHelper.completeCycle(cycleId).addOnSuccessListener(aVoid -> {
                        if (!isAdded()) return;
                        dismissLoading();
                        if (btnCompleteCycle != null) btnCompleteCycle.setEnabled(true);
                        NotificationHelper.showSuccess(requireContext(), "Cycle completed");
                    }).addOnFailureListener(e -> {
                        if (!isAdded()) return;
                        dismissLoading();
                        if (btnCompleteCycle != null) btnCompleteCycle.setEnabled(true);
                        Log.e(TAG, "Failed to complete cycle for cycleId=" + cycleId, e);
                        NotificationHelper.showError(requireContext(), "Unable to complete this cycle. Please try again.");
                    });
                });
    }

    private void createHarvest(double weight, String notes, androidx.appcompat.app.AlertDialog dialog, Button saveButton) {
        Harvest newHarvest = new Harvest(
                Timestamp.now(),
                weight,
                FirebaseAuth.getInstance().getUid(),
                userName,
                currentHarvestSource,
                notes
        );

        loadingHandle = NotificationHelper.showLoading(requireContext(), "Saving harvest...", () -> {
            isHarvestSubmitting = false;
            if (!isAdded()) return;
            saveButton.setEnabled(true);
            NotificationHelper.showError(requireContext(), "Request timed out. Please refresh before trying again.");
        });
        dbHelper.addHarvestTransaction(cycleId, newHarvest)
                .addOnSuccessListener(aVoid -> {
                    isHarvestSubmitting = false;
                    if (!isAdded()) return;
                    dismissLoading();
                    NotificationHelper.showSuccess(requireContext(), "Harvest saved");
                    dialog.dismiss();
                    loadChartData(); // Refresh chart manually
                })
                .addOnFailureListener(e -> {
                    isHarvestSubmitting = false;
                    if (!isAdded()) return;
                    dismissLoading();
                    saveButton.setEnabled(true);
                    Log.e(TAG, "Failed to save harvest for cycleId=" + cycleId, e);
                    NotificationHelper.showError(requireContext(), "Unable to save this harvest entry. Please try again.");
                });
    }

    private void updateHarvest(double newWeight, String notes, androidx.appcompat.app.AlertDialog dialog, Button saveButton) {
        double oldWeight = editingHarvest.getWeight();
        Map<String, Object> updates = new HashMap<>();
        updates.put("weight", newWeight);
        updates.put("notes", notes);
        updates.put("source", currentHarvestSource);

        loadingHandle = NotificationHelper.showLoading(requireContext(), "Saving harvest...", () -> {
            isHarvestSubmitting = false;
            if (!isAdded()) return;
            saveButton.setEnabled(true);
            NotificationHelper.showError(requireContext(), "Request timed out. Please refresh before trying again.");
        });
        dbHelper.updateHarvestTransaction(cycleId, editingHarvest.getId(), oldWeight, newWeight, updates)
                .addOnSuccessListener(aVoid -> {
                    isHarvestSubmitting = false;
                    if (!isAdded()) return;
                    dismissLoading();
                    NotificationHelper.showSuccess(requireContext(), "Harvest updated");
                    dialog.dismiss();
                    loadChartData(); // Refresh chart manually
                })
                .addOnFailureListener(e -> {
                    isHarvestSubmitting = false;
                    if (!isAdded()) return;
                    dismissLoading();
                    saveButton.setEnabled(true);
                    Log.e(TAG, "Failed to update harvest for cycleId=" + cycleId, e);
                    NotificationHelper.showError(requireContext(), "Unable to save your changes. Please try again.");
                });
    }

    private void loadHarvestData() {
        if (harvestListener != null) harvestListener.remove();

        SharedPreferences prefs = requireContext().getSharedPreferences("basilience_prefs", Context.MODE_PRIVATE);
        String deviceId = prefs.getString("selected_device_id", null);

        if (deviceId != null && cycleId != null) {
            dbHelper.setSelectedDeviceId(deviceId);
            // Real-time listener for the RecyclerView list (Newest First)
            harvestListener = dbHelper.listenToHarvestEntries(cycleId, (value, error) -> {
                if (error != null) {
                    Log.e(TAG, "Harvest list listener error for cycleId=" + cycleId, error);
                    if (!harvestListErrorNotified && isAdded() && getView() != null) {
                        harvestListErrorNotified = true;
                        NotificationHelper.showSnackbar(getView(), "Unable to refresh harvest list. Showing last known data.");
                    }
                    return;
                }
                if (value == null) return;
                harvestListErrorNotified = false;

                if (isFirstLoad) {
                    harvestList.clear();
                    for (com.google.firebase.firestore.QueryDocumentSnapshot doc : value) {
                        Harvest entry = doc.toObject(Harvest.class);
                        entry.setId(doc.getId());
                        harvestList.add(entry);
                    }
                    adapter.notifyDataSetChanged();
                    isFirstLoad = false;
                    updateHistoryEmptyState();
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
                    updateHistoryEmptyState();
                }
            });
        }
    }

    private void loadChartData() {
        if (cycleId == null) return;
        
        dbHelper.getHarvestHistoryForChart(cycleId).addOnSuccessListener(value -> {
            if (value == null || value.isEmpty()) {
                currentChartLabels.clear();
                harvestChart.clear();
                setChartEmptyState(true);
                return;
            }

            // Objective 3 requires the chart to visualize ACCUMULATED harvest
            // weight per cycle, not individual harvest values. value is
            // ordered ascending by harvestDate (oldest -> newest), so a
            // running sum here is guaranteed chronological; the stored
            // per-harvest records themselves are never modified.
            List<Entry> chartEntries = new ArrayList<>();
            List<String> dateLabels = new ArrayList<>();
            int i = 0;
            double cumulativeWeight = 0;
            for (com.google.firebase.firestore.QueryDocumentSnapshot doc : value) {
                Harvest entry = doc.toObject(Harvest.class);
                cumulativeWeight += entry.getWeight();
                chartEntries.add(new Entry(i++, (float) cumulativeWeight));
                dateLabels.add(DateUtils.formatShortDate(entry.getHarvestDate()));
            }

            // The final cumulative point must equal the transactionally
            // maintained cycle total for a consistent cycle. Never rewrite
            // stored data here - only log if they disagree, since that would
            // indicate a data issue elsewhere, not something this screen
            // should silently "fix".
            if (currentCycle != null) {
                double storedTotal = currentCycle.getTotalHarvestWeight();
                if (Math.abs(storedTotal - cumulativeWeight) > 0.01) {
                    Log.w(TAG, "Cumulative chart total (" + cumulativeWeight
                            + "g) does not match cycle.totalHarvestWeight (" + storedTotal
                            + "g) for cycleId=" + cycleId);
                }
            }

            updateChart(chartEntries, dateLabels, !isFirstChartLoad);
            isFirstChartLoad = false;
        }).addOnFailureListener(e -> {
            Log.e(TAG, "Failed to load harvest chart data for cycleId=" + cycleId, e);
            if (isAdded() && getView() != null) {
                NotificationHelper.showSnackbar(getView(), "Unable to load harvest chart data.");
            }
        });
    }

    // Toggles the chart's own empty state. No harvest yet is a legitimate
    // state, not an error - an empty plot area would otherwise read as a
    // flat zero-production trend.
    private void setChartEmptyState(boolean empty) {
        if (harvestChart != null) harvestChart.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (tvEmptyChart != null) tvEmptyChart.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void updateHistoryEmptyState() {
        boolean empty = harvestList.isEmpty();
        if (tvEmptyHistory != null) tvEmptyHistory.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (recyclerHarvest != null) recyclerHarvest.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void updateChart(List<Entry> chartEntries, List<String> dateLabels, boolean shouldAnimate) {
        if (chartEntries.isEmpty() || getContext() == null) {
            currentChartLabels.clear();
            harvestChart.clear();
            setChartEmptyState(true);
            return;
        }
        setChartEmptyState(false);

        LineData lineData = harvestChart.getData();
        if (lineData != null && lineData.getDataSetCount() > 0) {
            LineDataSet dataSet = (LineDataSet) lineData.getDataSetByIndex(0);
            dataSet.setValues(chartEntries);
            lineData.notifyDataChanged();
            harvestChart.notifyDataSetChanged();
        } else {
            LineDataSet dataSet = new LineDataSet(chartEntries, "Accumulated Harvest Weight (g)");
            int primaryColor = getResources().getColor(R.color.primary);

            dataSet.setColor(primaryColor);
            dataSet.setCircleColor(primaryColor);
            dataSet.setLineWidth(2.5f);
            // Small clean points rather than heavy markers.
            dataSet.setCircleRadius(3.5f);
            dataSet.setCircleHoleRadius(1.8f);
            dataSet.setDrawCircleHole(true);
            dataSet.setCircleHoleColor(getResources().getColor(R.color.white));
            dataSet.setDrawFilled(true);
            // LINEAR is kept deliberately. A curved mode would overshoot
            // between points, and on a cumulative total an overshoot dips
            // below the previous value - visually implying harvested weight
            // went down, which can never happen. Honesty beats smoothness.
            dataSet.setMode(LineDataSet.Mode.LINEAR);
            android.graphics.drawable.Drawable fill =
                    ContextCompat.getDrawable(requireContext(), R.drawable.ds_chart_fill_gradient);
            if (fill != null) {
                dataSet.setFillDrawable(fill);
            } else {
                dataSet.setFillColor(primaryColor);
                dataSet.setFillAlpha(40);
            }
            // Values appear on tap via the marker rather than being printed
            // permanently over every point.
            dataSet.setDrawValues(false);
            dataSet.setHighlightEnabled(true);
            dataSet.setHighLightColor(primaryColor);
            dataSet.setHighlightLineWidth(1f);
            dataSet.setDrawHorizontalHighlightIndicator(false);

            lineData = new LineData(dataSet);
            harvestChart.setData(lineData);
        }

        // Keep the marker's label source in step with what's plotted, so a
        // tapped point always names the harvest date the axis is showing.
        currentChartLabels.clear();
        currentChartLabels.addAll(dateLabels);
        if (harvestChart.getMarker() == null) {
            HarvestChartMarkerView marker = new HarvestChartMarkerView(requireContext(), currentChartLabels);
            marker.setChartView(harvestChart);
            harvestChart.setMarker(marker);
        }
        harvestChart.setHighlightPerTapEnabled(true);
        harvestChart.setHighlightPerDragEnabled(false);
        // Horizontal zoom/pan only - vertical scale isn't meaningful for a
        // fixed-value-axis cumulative chart and would just be confusing.
        harvestChart.setScaleYEnabled(false);
        harvestChart.highlightValue(null);

        // Rotated date labels need real room beneath the plot area. The old
        // uniform 5dp inset left the bottom edge too tight, so the rotated
        // Jul/Aug labels - and the first/last ones especially - were clipped
        // by the chart viewport. Give the bottom a dedicated allowance and
        // raise the minimum offset so the sides stay clear too.
        harvestChart.setExtraOffsets(8f, 8f, 8f, 18f);
        harvestChart.setMinOffset(16f);

        // Approved chart chrome: light grid, muted axis text, no outer
        // border. Visual only - plotted values and labels are untouched.
        int mutedAxisColor = android.graphics.Color.parseColor("#8A2E4F46");
        int hairlineColor = android.graphics.Color.parseColor("#F0F0F0");
        harvestChart.setDrawGridBackground(false);
        harvestChart.setDrawBorders(false);

        XAxis xAxis = harvestChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawGridLines(false);
        xAxis.setGranularity(1f);
        xAxis.setTextColor(mutedAxisColor);
        xAxis.setTextSize(11f);
        xAxis.setAxisLineColor(hairlineColor);
        xAxis.setAvoidFirstLastClipping(true);

        // Cap the label count on longer cycles so dates stay legible instead
        // of overlapping; short cycles still label every harvest.
        if (dateLabels.size() > 5) {
            // A shallower angle needs less vertical space than the previous
            // -35, which helps keep the labels inside the viewport without
            // eating further into the plot area.
            xAxis.setLabelRotationAngle(-30f);
            xAxis.setLabelCount(Math.min(dateLabels.size(), 6), false);
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

        com.github.mikephil.charting.components.YAxis axisLeft = harvestChart.getAxisLeft();
        axisLeft.setDrawGridLines(true);
        axisLeft.setAxisMinimum(0f);
        axisLeft.setTextColor(mutedAxisColor);
        axisLeft.setTextSize(11f);
        axisLeft.setGridColor(hairlineColor);
        axisLeft.setGridLineWidth(0.6f);
        axisLeft.setAxisLineColor(hairlineColor);
        // The horizontal grid alone carries the value reference; the axis
        // spine would just add a second vertical rule inside the surface.
        axisLeft.setDrawAxisLine(false);
        axisLeft.setLabelCount(5, false);
        // Same g/kg treatment as everywhere else, so the axis never reads in
        // a different unit from the hero or the marker.
        axisLeft.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return HarvestFormatter.formatWeight(value);
            }
        });

        harvestChart.getAxisRight().setEnabled(false);
        harvestChart.getDescription().setEnabled(false);
        harvestChart.getLegend().setEnabled(false);

        // A new cycle's chart always starts at its own full range - any zoom
        // left over from a previously viewed cycle's viewport must not carry
        // over onto this one.
        harvestChart.fitScreen();

        if (shouldAnimate) {
            harvestChart.animateX(800);
        }
        harvestChart.invalidate();
    }

    @Override
    public void onDestroyView() {
        dismissLoading();
        super.onDestroyView();
        if (harvestListener != null) harvestListener.remove();
        if (cycleListener != null) cycleListener.remove();
    }

    private void dismissLoading() {
        if (loadingHandle != null) loadingHandle.dismiss();
        loadingHandle = null;
    }
}
