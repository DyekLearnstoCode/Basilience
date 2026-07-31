package com.example.basilience;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;

public class Dashboard_Fragment extends Fragment {

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
            btnBack.setOnClickListener(v -> navController.popBackStack());
        }

        // Hardware back button: also go back to Device Manager, don't exit app
        requireActivity().getOnBackPressedDispatcher().addCallback(
                getViewLifecycleOwner(),
                new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        navController.popBackStack();
                    }
                }
        );

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
}
