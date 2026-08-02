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

import android.os.Handler;
import android.os.Looper;
import java.util.HashMap;
import java.util.Map;

public class WifiConfigFragment extends Fragment {

    private TextInputEditText etSsid, etPassword;
    private TextView tvWifiStatus;
    private Button btnSaveWifi, btnCancelWifi;
    private ImageView btnBack;

    // Loading overlay
    private View wifiLoadingOverlay;
    private TextView tvWifiLoadingTitle, tvWifiLoadingStatus;

    private DatabaseReference deviceRef;
    private ValueEventListener statusListener;

    private Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private long lastUpdateTime = 0;
    private boolean isCurrentlyOnline = false;
    private static final String CREDENTIALS_KEY = "BasilienceSecureWiFiKey123";

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

        wifiLoadingOverlay = view.findViewById(R.id.wifiLoadingOverlay);
        tvWifiLoadingTitle = view.findViewById(R.id.tvWifiLoadingTitle);
        tvWifiLoadingStatus = view.findViewById(R.id.tvWifiLoadingStatus);

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

        deviceRef = FirebaseDatabase.getInstance("https://basilience-database-default-rtdb.asia-southeast1.firebasedatabase.app")
                .getReference("devices").child(deviceId);

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

        NotificationHelper.showConfirmation(requireContext(), 
                "Change Wi-Fi Credentials?", 
                "This will send the new Wi-Fi credentials to the ESP32. The device will disconnect and attempt to reconnect using these details. Proceed?", 
                "Yes", "Cancel", () -> {
                    if (isCurrentlyOnline) {
                        sendWifiCommand(ssid, password);
                    } else {
                        sendWifiCommandLocal(ssid, password);
                    }
                });
    }

    private void sendWifiCommand(String ssid, String password) {
        if (deviceRef == null) return;

        showLoading("Sending credentials...", "Writing to ESP32...");

        String encryptedSsid = encryptString(ssid, CREDENTIALS_KEY);
        String encryptedPassword = encryptString(password, CREDENTIALS_KEY);

        Map<String, Object> commandData = new HashMap<>();
        commandData.put("ssid", encryptedSsid);
        commandData.put("password", encryptedPassword);
        commandData.put("timestamp", System.currentTimeMillis());

        deviceRef.child("commands").child("wifiConfig").setValue(commandData)
                .addOnSuccessListener(aVoid -> {
                    showLoading("Credentials sent!", "Waiting for ESP32 to restart Wi-Fi...");
                    etSsid.setText("");
                    etPassword.setText("");
                    
                    // The ESP32 will disconnect shortly, and the heartbeat monitor will catch it.
                    // We can hide the loader after a few seconds so the user can see the status text.
                    new Handler(Looper.getMainLooper()).postDelayed(this::hideLoading, 3000);
                })
                .addOnFailureListener(e -> {
                    hideLoading();
                    Toast.makeText(getContext(), "Failed to send credentials: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
    }

    private void sendWifiCommandLocal(String ssid, String password) {
        showLoading("Connecting to ESP32...", "Sending credentials via Local Wi-Fi...");
        
        new Thread(() -> {
            try {
                java.net.URL url = new java.net.URL("http://192.168.4.1/setup");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                
                String postData = "ssid=" + java.net.URLEncoder.encode(ssid, "UTF-8") + 
                                  "&password=" + java.net.URLEncoder.encode(password, "UTF-8");
                
                java.io.OutputStream os = conn.getOutputStream();
                os.write(postData.getBytes("UTF-8"));
                os.flush();
                os.close();
                
                int responseCode = conn.getResponseCode();
                
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (responseCode == 200) {
                        showLoading("Credentials sent!", "ESP32 is restarting...");
                        etSsid.setText("");
                        etPassword.setText("");
                        new Handler(Looper.getMainLooper()).postDelayed(this::hideLoading, 3000);
                    } else {
                        hideLoading();
                        Toast.makeText(getContext(), "Failed to send to ESP32: HTTP " + responseCode, Toast.LENGTH_LONG).show();
                    }
                });
                
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    hideLoading();
                    Toast.makeText(getContext(), "Could not connect to ESP32. Are you connected to 'Basilience-Setup' Wi-Fi?", Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private String encryptString(String input, String key) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            char k = key.charAt(i % key.length());
            sb.append(String.format("%02x", (c ^ k)));
        }
        return sb.toString();
    }

    private void showLoading(String title, String status) {
        if (wifiLoadingOverlay != null) {
            wifiLoadingOverlay.setVisibility(View.VISIBLE);
            if (tvWifiLoadingTitle != null) tvWifiLoadingTitle.setText(title);
            if (tvWifiLoadingStatus != null) {
                tvWifiLoadingStatus.setText(status);
                tvWifiLoadingStatus.setVisibility(status.isEmpty() ? View.GONE : View.VISIBLE);
            }
        }
    }

    private void hideLoading() {
        if (wifiLoadingOverlay != null) {
            wifiLoadingOverlay.setVisibility(View.GONE);
        }
    }

    private void monitorEspStatus() {
        if (deviceRef == null) return;

        statusListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.hasChild("deviceInfo/lastSeen")) {
                    lastUpdateTime = snapshot.child("deviceInfo/lastSeen").getValue(Long.class);
                    isCurrentlyOnline = true;
                    updateStatusUI();
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                tvWifiStatus.setText("Error reading status");
            }
        };
        
        deviceRef.addValueEventListener(statusListener);
        heartbeatHandler.post(heartbeatRunnable);
    }

    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            // ESP32 uploads device info every 10-15 seconds. If we haven't seen an update in 20 seconds, it's offline.
            if (System.currentTimeMillis() - lastUpdateTime > 20000) {
                isCurrentlyOnline = false;
            }
            updateStatusUI();
            heartbeatHandler.postDelayed(this, 5000);
        }
    };

    private void updateStatusUI() {
        if (tvWifiStatus == null || !isAdded()) return;
        
        if (isCurrentlyOnline) {
            tvWifiStatus.setText("Online (Connected via Internet)");
            tvWifiStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50")); // Green
        } else {
            tvWifiStatus.setText("Offline. Please connect your phone to 'Basilience-Setup' Wi-Fi network.");
            tvWifiStatus.setTextColor(android.graphics.Color.parseColor("#F44336")); // Red
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        heartbeatHandler.removeCallbacks(heartbeatRunnable);
        if (deviceRef != null && statusListener != null) {
            deviceRef.removeEventListener(statusListener);
        }
    }
}
