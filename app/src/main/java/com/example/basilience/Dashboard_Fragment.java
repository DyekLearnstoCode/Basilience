package com.example.basilience;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

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

        // Hide back button on dashboard
        View btnBack = view.findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setVisibility(View.VISIBLE);
            btnBack.setOnClickListener(v -> {
                androidx.navigation.Navigation.findNavController(view)
                        .navigate(R.id.DeviceManagementFragment);
            });
        }

        // Parameters Monitoring
        LinearLayout cardParameters = view.findViewById(R.id.cardParameters);
        cardParameters.setOnClickListener(v -> navController.navigate(R.id.action_home_to_parametersFragment));

        // User Guide
        LinearLayout cardUserGuide = view.findViewById(R.id.cardUserGuide);
        cardUserGuide.setOnClickListener(v -> navController.navigate(R.id.action_home_to_userGuideFragment));

        // System Reports
        LinearLayout cardReports = view.findViewById(R.id.cardReports);
        cardReports.setOnClickListener(v -> navController.navigate(R.id.action_home_to_reportschoiceFragment));

        // Cycle Details
        LinearLayout cardCycle = view.findViewById(R.id.cardCycle);
        cardCycle.setOnClickListener(v -> navController.navigate(R.id.action_home_to_cycleDetailsFragment));

        // Personnel Management
        LinearLayout cardPersonnel = view.findViewById(R.id.cardPersonnel);
        cardPersonnel.setOnClickListener(v -> navController.navigate(R.id.action_home_to_personnelFragment));

        // Role-based visibility - Use Prefs for immediate UI sync to avoid "glitch"
        SharedPreferences prefs = requireContext().getSharedPreferences("basilience_prefs", Context.MODE_PRIVATE);
        String savedRole = prefs.getString("user_role", "admin");
        
        if ("farmer".equals(savedRole)) {
            cardReports.setVisibility(View.GONE);
            cardPersonnel.setVisibility(View.GONE);
        } else {
            cardReports.setVisibility(View.VISIBLE);
            cardPersonnel.setVisibility(View.VISIBLE);
        }

        // Verify with Firestore in background (optional/robustness)
        String uid = dbHelper.getCurrentUid();
        if (uid != null) {
            dbHelper.getUserProfile(uid).addOnSuccessListener(document -> {
                if (isAdded() && document.exists()) {
                    String role = document.getString("role");
                    if ("farmer".equals(role)) {
                        cardReports.setVisibility(View.GONE);
                        cardPersonnel.setVisibility(View.GONE);
                    } else {
                        cardReports.setVisibility(View.VISIBLE);
                        cardPersonnel.setVisibility(View.VISIBLE);
                    }
                }
            });
        }
    }
}
