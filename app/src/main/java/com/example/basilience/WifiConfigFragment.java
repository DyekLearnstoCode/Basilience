package com.example.basilience;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;

import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;

public class WifiConfigFragment extends Fragment {

    private TextInputEditText etSsid, etPassword;
    private TextView tvWifiStatus;
    private Button btnSaveWifi, btnCancelWifi;
    private ImageView btnBack;

    private DatabaseReference deviceRef;
    private ValueEventListener statusListener;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.settings_wifi_config, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        etSsid = view.findViewById(R.id.etSsid);
        etPassword = view.findViewById(R.id.etPassword);
        tvWifiStatus = view.findViewById(R.id.tvWifiStatus);
        btnSaveWifi = view.findViewById(R.id.btnSaveWifi);
        btnCancelWifi = view.findViewById(R.id.btnCancelWifi);
        btnBack = view.findViewById(R.id.btnBack);

        NavController navController = Navigation.findNavController(view);

        if (btnBack != null) {
            btnBack.setVisibility(View.VISIBLE);
            btnBack.setOnClickListener(v -> navController.popBackStack());
        }
        btnCancelWifi.setOnClickListener(v -> navController.popBackStack());

        SharedPreferences prefs = requireActivity().getSharedPreferences("basilience_prefs", android.content.Context.MODE_PRIVATE);
        String deviceId = prefs.getString("selected_device_id", "");

        if (deviceId.isEmpty()) {
            Toast.makeText(getContext(), "Device ID not found", Toast.LENGTH_SHORT).show();
            return;
        }

        deviceRef = FirebaseDatabase.getInstance().getReference("devices").child(deviceId);

        btnSaveWifi.setOnClickListener(v -> handleSaveCredentials());

        monitorEspStatus();
    }

    private void handleSaveCredentials() {
        String ssid = etSsid.getText() != null ? etSsid.getText().toString().trim() : "";
        String password = etPassword.getText() != null ? etPassword.getText().toString().trim() : "";

        if (ssid.isEmpty()) {
            etSsid.setError("SSID cannot be empty");
            return;
        }

        new AlertDialog.Builder(requireContext())
                .setTitle("Change Wi-Fi Credentials?")
                .setMessage("This will send the new Wi-Fi credentials to the ESP32. The device will disconnect and attempt to reconnect using these details. Proceed?")
                .setPositiveButton("Yes", (dialog, which) -> sendWifiCommand(ssid, password))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void sendWifiCommand(String ssid, String password) {
        if (deviceRef == null) return;

        Map<String, Object> commandData = new HashMap<>();
        commandData.put("ssid", ssid);
        commandData.put("password", password);
        commandData.put("timestamp", System.currentTimeMillis());

        deviceRef.child("commands").child("wifiConfig").setValue(commandData)
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(getContext(), "Wi-Fi credentials sent to ESP32", Toast.LENGTH_LONG).show();
                    etSsid.setText("");
                    etPassword.setText("");
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(getContext(), "Failed to send credentials: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
    }

    private void monitorEspStatus() {
        if (deviceRef == null) return;

        statusListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean isOnline = snapshot.child("deviceInfo").child("online").getValue(Boolean.class);
                Boolean wifiConnected = snapshot.child("status").child("wifiConnected").getValue(Boolean.class);
                
                // When ESP32 changes network, it goes offline, then online.
                if (Boolean.TRUE.equals(isOnline) && Boolean.TRUE.equals(wifiConnected)) {
                    tvWifiStatus.setText("Online (Connected)");
                    tvWifiStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50")); // Green
                } else {
                    tvWifiStatus.setText("Offline / Reconnecting...");
                    tvWifiStatus.setTextColor(android.graphics.Color.parseColor("#F44336")); // Red
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                tvWifiStatus.setText("Error reading status");
            }
        };
        
        deviceRef.addValueEventListener(statusListener);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (deviceRef != null && statusListener != null) {
            deviceRef.removeEventListener(statusListener);
        }
    }
}
