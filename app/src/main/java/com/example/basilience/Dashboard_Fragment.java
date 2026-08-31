package com.example.basilience;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;

import com.google.android.material.button.MaterialButton;
import com.google.firebase.firestore.ListenerRegistration;

public class Dashboard_Fragment extends Fragment {

    private ListenerRegistration cycleListener;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dashboard_main, container, false);

    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        NavController navController = Navigation.findNavController(view);
        Database_Helper dbHelper = new Database_Helper();

        // Save selected device ID if passed from DeviceFragment
        if (getArguments() != null) {
            String deviceId = getArguments().getString("selected_device_id");
            if (deviceId != null) {
                SharedPreferences prefs = requireContext().getSharedPreferences("basilience_prefs", Context.MODE_PRIVATE);
                prefs.edit().putString("selected_device_id", deviceId).apply();
            }
        }

        // Show back button on dashboard to return to Device Management
        View btnBack = view.findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setVisibility(View.VISIBLE);
            btnBack.setOnClickListener(v -> {
                navController.navigate(R.id.DeviceManagementFragment, null, new androidx.navigation.NavOptions.Builder()
                        .setPopUpTo(R.id.home, true)
                        .build());
            });
        }

        // Hardware back button: also go back to Device Manager, don't exit app
        requireActivity().getOnBackPressedDispatcher().addCallback(
                getViewLifecycleOwner(),
                new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        navController.navigate(R.id.DeviceManagementFragment, null, new androidx.navigation.NavOptions.Builder()
                                .setPopUpTo(R.id.home, true)
                                .build());
                    }
                }
        );

        // Device status (reuses the same DeviceConnectionManager state Monitoring shows)
        SharedPreferences statusPrefs = requireContext().getSharedPreferences("basilience_prefs", Context.MODE_PRIVATE);
        TextView tvDeviceStatus = view.findViewById(R.id.tvDashboardDeviceStatus);
        String dashboardDeviceId = statusPrefs.getString("selected_device_id", null);
        if (tvDeviceStatus != null && dashboardDeviceId != null) {
            tvDeviceStatus.setVisibility(View.VISIBLE);
            // Defensive, not redundant - see the matching call/comment in
            // Parameters_Monitoring_Fragment.startRealTimeMonitoring(). This
            // screen only ever observed the singleton before, with nothing of
            // its own to (re-)establish monitoring if MainActivity's central
            // trigger was ever missed - confirmed live bug: this label was
            // observed stuck on RECONNECTING while Device Management's own
            // listener already showed the device correctly online/offline.
            DeviceConnectionManager.getInstance().monitorDevice(dashboardDeviceId);
            DeviceConnectionManager.getInstance().getConnectivityState().observe(
                    getViewLifecycleOwner(), state -> {
                        DeviceConnectivityState displayState = state == null
                                ? DeviceConnectivityState.RECONNECTING : state;
                        tvDeviceStatus.setText("● " + displayState.getLabel().toUpperCase(java.util.Locale.ROOT));
                        tvDeviceStatus.setTextColor(ContextCompat.getColor(
                                requireContext(), displayState.getColorRes()));
                    });
        }

        observeCycleState(view, navController);

        // Parameters Monitoring
        LinearLayout cardParameters = view.findViewById(R.id.cardParameters);
        cardParameters.setOnClickListener(v -> navController.navigate(R.id.action_home_to_parametersFragment));

        // User Guide
        LinearLayout cardUserGuide = view.findViewById(R.id.cardUserGuide);
        cardUserGuide.setOnClickListener(v -> navController.navigate(R.id.action_home_to_userGuideFragment));

        // Cycle Details
        LinearLayout cardCycle = view.findViewById(R.id.cardCycle);
        cardCycle.setOnClickListener(v -> navController.navigate(R.id.action_home_to_cycleDetailsFragment));

        // Verify with Firestore in background (optional/robustness)
        String uid = dbHelper.getCurrentUid();
        if (uid != null) {
            dbHelper.getUserProfile(uid).addOnSuccessListener(document -> {
                if (isAdded() && document.exists()) {
                    String role = document.getString("role");
                    // removed cardPersonnel logic here
                }
            });
        }
    }

    /**
     * Shows the no-active-cycle state, using the same cycles data every other
     * screen reads. The card stays hidden while loading and on a read error -
     * "we could not check" must never be presented as "there is no cycle".
     */
    private void observeCycleState(View view, NavController navController) {
        View card = view.findViewById(R.id.cardNoActiveCycle);
        TextView body = view.findViewById(R.id.tvNoCycleBody);
        MaterialButton btnCreate = view.findViewById(R.id.btnDashboardCreateCycle);
        if (card == null) return;

        SharedPreferences prefs = requireContext()
                .getSharedPreferences("basilience_prefs", Context.MODE_PRIVATE);
        String deviceId = prefs.getString("selected_device_id", null);

        if (btnCreate != null) {
            btnCreate.setOnClickListener(v ->
                    navController.navigate(R.id.action_home_to_cycleDetailsFragment));
        }

        cycleListener = CycleGateState.observe(new Database_Helper(), deviceId,
                (state, hasAnyCycle) -> {
                    if (!isAdded()) return;

                    if (state != CycleGateState.State.NONE) {
                        // ACTIVE, LOADING and ERROR all leave the card hidden.
                        card.setVisibility(View.GONE);
                        return;
                    }

                    if (body != null) {
                        // Admins and assigned Farmers can both start a cycle.
                        body.setText(hasAnyCycle
                                ? "The previous cycle has ended. Create a new cycle to begin the next cultivation period."
                                : "Create a growth cycle to begin cultivation automation.");
                    }
                    if (btnCreate != null) {
                        btnCreate.setVisibility(View.VISIBLE);
                    }
                    card.setVisibility(View.VISIBLE);
                });
    }

    @Override
    public void onDestroyView() {
        if (cycleListener != null) {
            cycleListener.remove();
            cycleListener = null;
        }
        super.onDestroyView();
    }
}
