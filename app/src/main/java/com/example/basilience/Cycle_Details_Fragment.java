package com.example.basilience;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.ArrayList;
import java.util.List;

public class Cycle_Details_Fragment extends Fragment {

    private static final String TAG = "Cycle_Details_Fragment";
    private final List<Cycle> cycles = new ArrayList<>();
    private CycleAdapter adapter;
    private Database_Helper dbHelper;
    private ListenerRegistration cycleListener;
    private TextView tvCyclesState;

    public Cycle_Details_Fragment() {
        super(R.layout.cycle_main);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        dbHelper = new Database_Helper();
        NavController navController = Navigation.findNavController(view);

        View btnBack = view.findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setVisibility(View.VISIBLE);
            btnBack.setOnClickListener(v -> navController.popBackStack());
        }

        RecyclerView rv = view.findViewById(R.id.recyclerCycles);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));

        tvCyclesState = view.findViewById(R.id.tvCyclesState);
        if (tvCyclesState != null) tvCyclesState.setVisibility(View.VISIBLE);

        adapter = new CycleAdapter(cycles, (cycle, pos) -> {
            Bundle args = new Bundle();
            args.putString("cycleId", cycle.getCycleId());
            args.putInt("cycleNumber", cycle.getCycleNumber());
            navController.navigate(R.id.action_cycleDetailsFragment_to_harvestLogFragment, args);
        });

        rv.setAdapter(adapter);

        // Starting a growth cycle is farm work, not administration: an Admin or
        // a Farmer assigned to this device may do it. Database_Helper and the
        // Firestore rules enforce the same policy on the write itself.
        SharedPreferences prefs = requireContext().getSharedPreferences("basilience_prefs", Context.MODE_PRIVATE);

        View btnAddCycle = view.findViewById(R.id.btnAddCycle);
        {
            if (btnAddCycle != null) {
                btnAddCycle.setVisibility(View.VISIBLE);
                btnAddCycle.setOnClickListener(v -> {
                    String deviceId = prefs.getString("selected_device_id", null);
                    if (deviceId == null) {
                        NotificationHelper.showError(requireContext(), "No device selected");
                        return;
                    }
                    btnAddCycle.setEnabled(false);
                    dbHelper.getCycles(deviceId).addOnCompleteListener(task -> {
                        btnAddCycle.setEnabled(true);
                        if (!isAdded()) return;
                        if (!task.isSuccessful()) {
                            // ERROR: the cycles could not be read at all.
                            Log.e(TAG, "Active cycle verification failed for deviceId=" + deviceId,
                                    task.getException());
                            NotificationHelper.showError(requireContext(), "Unable to verify the current growth cycle.");
                        } else if (CycleStatus.hasActive(task.getResult())) {
                            // INVALID: a second active cycle is not allowed.
                            NotificationHelper.showWarning(requireContext(), "Active Cycle Exists",
                                    "Complete the current active cycle before adding another cycle.");
                        } else {
                            // VALID: no active cycle - including none at all - so a
                            // new cycle may be started.
                            Bundle args = new Bundle();
                            args.putInt("cycleNo", cycles.size() + 1);
                            navController.navigate(R.id.action_cycleDetailsFragment_to_cycleaddFragment, args);
                        }
                    });
                });
            }
        }

        // Fetch Cycles in Real-time
        startListeningToCycles();
    }


    /**
     * Explains the current cycle situation in the list header.
     *
     * Three outcomes rather than the previous two, because "you have never
     * started a cycle" and "your last cycle finished" need different wording -
     * and completed cycles still show in the list underneath either way.
     * When a cycle is running, the header disappears entirely.
     */
    private void renderCyclesState(com.google.firebase.firestore.QuerySnapshot snapshot) {
        if (tvCyclesState == null) return;

        final boolean cycleRunning = CycleStatus.hasActive(snapshot);

        if (cycleRunning) {
            tvCyclesState.setVisibility(View.GONE);
            return;
        }

        // Admins and assigned Farmers can both start a cycle, so this copy no
        // longer differs by role.
        final String message = cycles.isEmpty()
                ? "No Growth Cycles Yet\nCreate your first growth cycle to begin cultivation."
                : "No Active Growth Cycle\nThe previous cycle has ended. Create a new cycle to begin the next cultivation period.";

        tvCyclesState.setText(message);
        tvCyclesState.setTextColor(androidx.core.content.ContextCompat.getColor(
                requireContext(), R.color.state_no_data));
        tvCyclesState.setVisibility(View.VISIBLE);
    }

    private void startListeningToCycles() {
        SharedPreferences prefs = requireContext().getSharedPreferences("basilience_prefs", Context.MODE_PRIVATE);
        String deviceId = prefs.getString("selected_device_id", null);

        if (deviceId != null) {
            dbHelper.setSelectedDeviceId(deviceId);
            cycleListener = dbHelper.listenToCycles((snapshot, e) -> {
                if (!isAdded()) return;
                if (e != null) {
                    Log.e(TAG, "Failed to load cycles for deviceId=" + deviceId, e);
                    if (cycles.isEmpty() && tvCyclesState != null) {
                        tvCyclesState.setText("Unable to load growth cycles. Please try again.");
                        tvCyclesState.setTextColor(androidx.core.content.ContextCompat.getColor(
                                requireContext(), R.color.state_critical));
                        tvCyclesState.setVisibility(View.VISIBLE);
                    }
                    NotificationHelper.showError(getContext(), "Unable to load growth cycles. Please try again.");
                    return;
                }

                if (snapshot != null) {
                    cycles.clear();
                    for (DocumentSnapshot doc : snapshot.getDocuments()) {
                        try {
                            Cycle cycle = doc.toObject(Cycle.class);
                            if (cycle != null) {
                                // Ensure ID is set even if not in doc fields (though usually it is)
                                if (cycle.getCycleId() == null) cycle.setCycleId(doc.getId());
                                cycles.add(cycle);
                            }
                        } catch (Exception ex) {
                            android.util.Log.e("CycleLoad", "Mapping error for doc " + doc.getId() + ": " + ex.getMessage());
                            
                            // Robust mapping fallback for legacy data or partial type mismatches
                            try {
                                Cycle fallback = new Cycle();
                                fallback.setCycleId(doc.getId());
                                fallback.setCycleName(doc.getString("cycleName"));
                                Long cycleNum = doc.getLong("cycleNumber");
                                fallback.setCycleNumber(cycleNum != null ? cycleNum.intValue() : 0);
                                fallback.setStatus(doc.getString("status"));
                                
                                // Dates - attempt to get as Timestamp
                                fallback.setStartDate(doc.getTimestamp("startDate"));
                                fallback.setExpectedHarvestDate(doc.getTimestamp("expectedHarvestDate"));
                                fallback.setEndDate(doc.getTimestamp("endDate"));
                                
                                // Aggregates
                                Double weight = doc.getDouble("totalHarvestWeight");
                                fallback.setTotalHarvestWeight(weight != null ? weight : 0.0);
                                Long count = doc.getLong("totalHarvestCount");
                                fallback.setTotalHarvestCount(count != null ? count.intValue() : 0);
                                
                                cycles.add(fallback);
                            } catch (Exception ex2) {
                                android.util.Log.e("CycleLoad", "Critical mapping failure: " + ex2.getMessage());
                            }
                        }
                    }
                    adapter.notifyDataSetChanged();
                    renderCyclesState(snapshot);
                }
            });
        } else {
            NotificationHelper.showError(getContext(), "No device selected. Redirecting...");
            // Redirect back to Device Management if no device is contextually active
            if (getView() != null) {
                Navigation.findNavController(getView()).popBackStack();
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (cycleListener != null) {
            cycleListener.remove();
        }
    }
}
