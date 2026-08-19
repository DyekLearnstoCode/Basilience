package com.example.basilience;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.firebase.database.FirebaseDatabase;

public class AboutFragment extends Fragment {
    private static final String PREFS_NAME = "basilience_prefs";
    private static final String KEY_DEVELOPER_MODE_ENABLED = "developer_mode_enabled";
    private static final int DEV_MODE_TAP_TARGET = 7;

    private int developerModeTapCount = 0;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.settings_about, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        View btnBack = view.findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setVisibility(View.VISIBLE);
            btnBack.setOnClickListener(v -> {
                androidx.navigation.Navigation.findNavController(view).popBackStack();
            });
        }

        TextView tvVersion = view.findViewById(R.id.tvVersion);
        if (tvVersion != null) {
            tvVersion.setOnClickListener(v -> handleVersionTap());
        }
    }

    private void handleVersionTap() {
        if (getContext() == null) return;

        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!"ADMIN".equalsIgnoreCase(prefs.getString("user_role", ""))) {
            developerModeTapCount = 0;
            return;
        }
        if (prefs.getBoolean(KEY_DEVELOPER_MODE_ENABLED, false)) {
            Toast.makeText(getContext(), "Developer Mode is already enabled", Toast.LENGTH_SHORT).show();
            return;
        }

        developerModeTapCount++;
        int remaining = DEV_MODE_TAP_TARGET - developerModeTapCount;

        if (remaining <= 0) {
            developerModeTapCount = 0;
            String deviceId = prefs.getString("selected_device_id", null);
            if (deviceId == null || deviceId.trim().isEmpty()) {
                Toast.makeText(getContext(), "Select a device before enabling Developer Mode", Toast.LENGTH_LONG).show();
                return;
            }
            FirebaseDatabase.getInstance("https://basilience-database-default-rtdb.asia-southeast1.firebasedatabase.app")
                    .getReference("devices").child(deviceId).child("settings").child("devModeEnabled")
                    .setValue(true)
                    .addOnSuccessListener(unused -> {
                        prefs.edit().putBoolean(KEY_DEVELOPER_MODE_ENABLED, true).apply();
                        if (isAdded()) Toast.makeText(getContext(), "Developer Mode enabled", Toast.LENGTH_LONG).show();
                    })
                    .addOnFailureListener(error -> {
                        if (isAdded()) Toast.makeText(getContext(), "Unable to enable Developer Mode", Toast.LENGTH_LONG).show();
                    });
        } else if (remaining <= 3) {
            Toast.makeText(getContext(), remaining + " more taps to enable Developer Mode", Toast.LENGTH_SHORT).show();
        }
    }
}
