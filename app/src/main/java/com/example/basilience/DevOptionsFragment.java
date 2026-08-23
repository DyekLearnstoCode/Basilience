package com.example.basilience;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.WriteBatch;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

public class DevOptionsFragment extends Fragment {

    private DatabaseReference mockSensorsRef;
    private DatabaseReference settingsRef;
    private DatabaseReference deviceRef;
    private DatabaseReference sensorTestCommandRef;
    private DatabaseReference sensorTestStatusRef;
    private DatabaseReference diagnosticSensorsRef;

    private SwitchMaterial switchMockEnable;
    private EditText etPh, etEc, etTemp, etHumidity, etWaterTemperature, etWaterLevel;
    private EditText etMinWaterLevel, etMaxWaterLevel;
    private EditText etSsid, etPassword;
    private TextView tvWifiStatus, tvSensorTestIndicator;
    private TextView tvDiagnosticPh, tvDiagnosticEc, tvDiagnosticAirTemperature;
    private TextView tvDiagnosticHumidity, tvDiagnosticWaterTemperature, tvDiagnosticWaterLevel;
    private TextView tvDiagnosticWaterLevelDistance;

    private MaterialButton btnPush, btnPushSettings, btnInjectLogs, btnPushWifi, btnEnableProvisioningAp, btnDisableDeveloperMode;
    private MaterialButton btnFilterSensorTest, btnFilterMock, btnFilterRefill, btnFilterWifi, btnSensorTest;

    private View containerSensorTest, containerMockData, containerRefillLevels, containerWifiConfig;

    private boolean isCurrentlyOnline = false;
    private DeviceConnectivityState connectivityState = DeviceConnectivityState.RECONNECTING;
    private Boolean lastReportedWifiConnected = null;
    private boolean setupApReachable = false;
    private boolean loadingMockState = true;
    private boolean suppressMockSwitchCallback = false;
    private boolean sensorTestActive = false;
    private boolean sensorTestRequested = false;
    private ValueEventListener sensorTestStatusListener;
    private ValueEventListener diagnosticSensorsListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final String PREFS_NAME = "basilience_prefs";
    private static final String KEY_DEVELOPER_MODE_ENABLED = "developer_mode_enabled";

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_dev_options, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        NavController navController = Navigation.findNavController(view);
        SharedPreferences accessPrefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!"ADMIN".equalsIgnoreCase(accessPrefs.getString("user_role", ""))) {
            Toast.makeText(requireContext(), "Developer Mode is available to Admin users only", Toast.LENGTH_SHORT).show();
            navController.popBackStack();
            return;
        }
        View btnBack = view.findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setVisibility(View.VISIBLE);
            btnBack.setOnClickListener(v -> navController.popBackStack());
        }

        // Filter chips
        btnFilterSensorTest = view.findViewById(R.id.btnFilterSensorTest);
        btnFilterMock = view.findViewById(R.id.btnFilterMock);
        btnFilterRefill = view.findViewById(R.id.btnFilterRefill);
        btnFilterWifi = view.findViewById(R.id.btnFilterWifi);

        // Containers
        containerSensorTest = view.findViewById(R.id.containerSensorTest);
        containerMockData = view.findViewById(R.id.containerMockData);
        containerRefillLevels = view.findViewById(R.id.containerRefillLevels);
        containerWifiConfig = view.findViewById(R.id.containerWifiConfig);

        // Mock data components
        switchMockEnable = view.findViewById(R.id.switchMockEnable);
        etPh = view.findViewById(R.id.etPh);
        etEc = view.findViewById(R.id.etEc);
        etTemp = view.findViewById(R.id.etTemp);
        etHumidity = view.findViewById(R.id.etHumidity);
        etWaterTemperature = view.findViewById(R.id.etWaterTemperature);
        etWaterLevel = view.findViewById(R.id.etWaterLevel);
        btnPush = view.findViewById(R.id.btnPush);
        btnInjectLogs = view.findViewById(R.id.btnInjectLogs);

        // Physical sensor test components
        btnSensorTest = view.findViewById(R.id.btnSensorTest);
        tvSensorTestIndicator = view.findViewById(R.id.tvSensorTestIndicator);
        tvDiagnosticPh = view.findViewById(R.id.tvDiagnosticPh);
        tvDiagnosticEc = view.findViewById(R.id.tvDiagnosticEc);
        tvDiagnosticAirTemperature = view.findViewById(R.id.tvDiagnosticAirTemperature);
        tvDiagnosticHumidity = view.findViewById(R.id.tvDiagnosticHumidity);
        tvDiagnosticWaterTemperature = view.findViewById(R.id.tvDiagnosticWaterTemperature);
        tvDiagnosticWaterLevel = view.findViewById(R.id.tvDiagnosticWaterLevel);
        tvDiagnosticWaterLevelDistance = view.findViewById(R.id.tvDiagnosticWaterLevelDistance);

        // Refill thresholds components
        etMinWaterLevel = view.findViewById(R.id.etMinWaterLevel);
        etMaxWaterLevel = view.findViewById(R.id.etMaxWaterLevel);
        btnPushSettings = view.findViewById(R.id.btnPushSettings);

        // Wi-Fi Config components
        etSsid = view.findViewById(R.id.etSsid);
        etPassword = view.findViewById(R.id.etPassword);
        tvWifiStatus = view.findViewById(R.id.tvWifiStatus);
        btnPushWifi = view.findViewById(R.id.btnPushWifi);
        btnEnableProvisioningAp = view.findViewById(R.id.btnEnableProvisioningAp);
        btnDisableDeveloperMode = view.findViewById(R.id.btnDisableDeveloperMode);

        Database_Helper helper = new Database_Helper();
        String currentDeviceId = helper.getSelectedDeviceId();
        if (currentDeviceId == null && getContext() != null) {
            android.content.SharedPreferences prefs = getContext().getSharedPreferences("basilience_prefs", android.content.Context.MODE_PRIVATE);
            currentDeviceId = prefs.getString("selected_device_id", null);
        }

        if (currentDeviceId == null) {
            Toast.makeText(getContext(), "No device selected", Toast.LENGTH_SHORT).show();
            return;
        }

        String rtdbUrl = "https://basilience-database-default-rtdb.asia-southeast1.firebasedatabase.app";
        deviceRef = FirebaseDatabase.getInstance(rtdbUrl).getReference("devices/" + currentDeviceId);
        mockSensorsRef = deviceRef.child("commands/mockSensors");
        settingsRef = deviceRef.child("settings");
        sensorTestCommandRef = deviceRef.child("commands/sensorTest/enabled");
        sensorTestStatusRef = deviceRef.child("status/sensorTest");
        diagnosticSensorsRef = deviceRef.child("debug/physicalSensors");

        loadCurrentValues();
        switchMockEnable.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressMockSwitchCallback || loadingMockState) return;
            if (isChecked) {
                suppressMockSwitchCallback = true;
                switchMockEnable.setChecked(false);
                suppressMockSwitchCallback = false;
                NotificationHelper.showConfirmation(requireContext(),
                        "Enable Mock Sensors?",
                        "Mock values will replace physical sensor readings used by automatic control until Mock Sensors are disabled.",
                        "Enable", "Cancel", () -> {
                            suppressMockSwitchCallback = true;
                            switchMockEnable.setChecked(true);
                            suppressMockSwitchCallback = false;
                        });
                return;
            }
            if (!loadingMockState && !isChecked) {
                disableMockMode();
            }
        });

        // Setup filter button listeners
        btnFilterSensorTest.setOnClickListener(v -> updateFilterSelection("Sensor"));
        btnFilterMock.setOnClickListener(v -> updateFilterSelection("Mock"));
        btnFilterRefill.setOnClickListener(v -> updateFilterSelection("Refill"));
        btnFilterWifi.setOnClickListener(v -> updateFilterSelection("Wifi"));

        btnPush.setOnClickListener(v -> pushMockValues());
        btnSensorTest.setOnClickListener(v -> handleSensorTestButton());
        btnPushSettings.setOnClickListener(v -> pushSettings());
        btnPushWifi.setOnClickListener(v -> handleSaveWifiCredentials());
        if (btnEnableProvisioningAp != null) {
            btnEnableProvisioningAp.setOnClickListener(v -> enableProvisioningApMode());
        }
        if (btnDisableDeveloperMode != null) {
            btnDisableDeveloperMode.setOnClickListener(v -> disableDeveloperMode(navController));
        }
        
        final String fDeviceId = currentDeviceId;
        btnInjectLogs.setOnClickListener(v -> confirmAndInjectMockFirestoreLogs(fDeviceId));

        observeSensorTest();
        updateFilterSelection("Sensor");

        // Observe online status for Wi-Fi config UI
        DeviceConnectionManager.getInstance().getConnectivityState().observe(getViewLifecycleOwner(), state -> {
            connectivityState = state == null
                    ? DeviceConnectivityState.RECONNECTING : state;
            isCurrentlyOnline = connectivityState == DeviceConnectivityState.ONLINE;
            renderWifiStatusUI();
            checkSetupApReachability();
        });

        deviceRef.child("status").child("wifiConnected").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                lastReportedWifiConnected = snapshot.getValue(Boolean.class);
                renderWifiStatusUI();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                lastReportedWifiConnected = null;
                renderWifiStatusUI();
            }
        });
        checkSetupApReachability();
    }

    /**
     * Switches the visible Developer Options tab.
     *
     * The chips are MaterialButtons, which manage their own background drawable
     * and ignore setBackgroundResource() - those calls silently did nothing,
     * which is why the tabs showed the Material default colour. Selection is
     * now carried by the view's selected state and resolved by the colour state
     * lists on DeveloperFilterChip.
     *
     * Which tab is shown is unchanged: the same filter names select the same
     * containers as before.
     */
    private void updateFilterSelection(String selectedFilter) {
        boolean sensor = "Sensor".equalsIgnoreCase(selectedFilter);
        boolean refill = "Refill".equalsIgnoreCase(selectedFilter);
        boolean wifi = "Wifi".equalsIgnoreCase(selectedFilter);
        boolean mock = !sensor && !refill && !wifi;

        btnFilterSensorTest.setSelected(sensor);
        btnFilterMock.setSelected(mock);
        btnFilterRefill.setSelected(refill);
        btnFilterWifi.setSelected(wifi);

        containerSensorTest.setVisibility(sensor ? View.VISIBLE : View.GONE);
        containerMockData.setVisibility(mock ? View.VISIBLE : View.GONE);
        containerRefillLevels.setVisibility(refill ? View.VISIBLE : View.GONE);
        containerWifiConfig.setVisibility(wifi ? View.VISIBLE : View.GONE);
    }

    private void handleSensorTestButton() {
        if (sensorTestActive || sensorTestRequested) {
            setSensorTestCommand(false);
            return;
        }

        NotificationHelper.showConfirmation(requireContext(),
                "Start Sensor Test?",
                "Automatic cultivation control will pause while physical sensors are tested. Notifications caused by test readings will be suppressed.",
                "Start Test", "Cancel", () -> setSensorTestCommand(true));
    }

    private void setSensorTestCommand(boolean enabled) {
        if (sensorTestCommandRef == null) return;
        sensorTestRequested = enabled;
        renderSensorTestState();
        showLoading(enabled ? "Starting Sensor Test..." : "Stopping Sensor Test...",
                "Waiting for ESP32 acknowledgement...");
        sensorTestCommandRef.setValue(enabled).addOnFailureListener(error -> {
            sensorTestRequested = sensorTestActive;
            hideLoading();
            renderSensorTestState();
            if (isAdded()) NotificationHelper.showError(requireContext(), "Sensor Test Failed", error.getMessage());
        });
    }

    private void observeSensorTest() {
        sensorTestStatusListener = sensorTestStatusRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean active = snapshot.getValue(Boolean.class);
                sensorTestActive = Boolean.TRUE.equals(active);
                sensorTestRequested = sensorTestActive;
                hideLoading();
                renderSensorTestState();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                hideLoading();
            }
        });

        diagnosticSensorsListener = diagnosticSensorsRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                renderDiagnostic(tvDiagnosticPh, "pH", snapshot.child("ph").getValue(), "");
                renderDiagnostic(tvDiagnosticEc, "EC", snapshot.child("ec").getValue(), " mS/cm");
                renderDiagnostic(tvDiagnosticAirTemperature, "Air Temperature", snapshot.child("airTemperature").getValue(), " °C");
                renderDiagnostic(tvDiagnosticHumidity, "Humidity", snapshot.child("humidity").getValue(), " %");
                renderDiagnostic(tvDiagnosticWaterTemperature, "Water Temperature", snapshot.child("waterTemperature").getValue(), " °C");
                renderDiagnostic(tvDiagnosticWaterLevel, "Water Level", snapshot.child("waterLevel").getValue(), " %");
                renderDiagnostic(tvDiagnosticWaterLevelDistance, "Water Level Distance", snapshot.child("waterLevelDistanceCm").getValue(), " cm");
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                renderAllDiagnosticsUnavailable();
            }
        });
        renderSensorTestState();
    }

    private void renderSensorTestState() {
        if (tvSensorTestIndicator == null || btnSensorTest == null) return;
        boolean pendingStart = sensorTestRequested && !sensorTestActive;
        tvSensorTestIndicator.setText(sensorTestActive ? "SENSOR TEST ACTIVE" :
                (pendingStart ? "STARTING SENSOR TEST" : "SENSOR TEST INACTIVE"));
        tvSensorTestIndicator.setTextColor(ContextCompat.getColor(requireContext(),
                sensorTestActive || pendingStart ? R.color.sensor_test_active : R.color.state_off));
        btnSensorTest.setText(sensorTestActive || sensorTestRequested ? "Stop Sensor Test" : "Start Sensor Test");
        btnSensorTest.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(),
                        sensorTestActive || sensorTestRequested
                                ? R.color.action_destructive : R.color.primary)));
        if (!sensorTestActive) renderAllDiagnosticsUnavailable();
    }

    private void renderDiagnostic(TextView view, String label, Object rawValue, String unit) {
        if (view == null) return;
        if (!sensorTestActive) {
            view.setText(label + "\n--\nNO VALID READING");
            view.setTextColor(ContextCompat.getColor(requireContext(), R.color.sensor_no_data));
        } else if (rawValue instanceof Number) {
            view.setText(String.format(Locale.US, "%s\n%.2f%s\nREADING", label,
                    ((Number) rawValue).doubleValue(), unit));
            view.setTextColor(ContextCompat.getColor(requireContext(), R.color.sensor_reading));
        } else {
            view.setText(label + "\n--\nNO VALID READING");
            view.setTextColor(ContextCompat.getColor(requireContext(), R.color.sensor_no_data));
        }
    }

    private void renderAllDiagnosticsUnavailable() {
        renderDiagnostic(tvDiagnosticPh, "pH", null, "");
        renderDiagnostic(tvDiagnosticEc, "EC", null, " mS/cm");
        renderDiagnostic(tvDiagnosticAirTemperature, "Air Temperature", null, " °C");
        renderDiagnostic(tvDiagnosticHumidity, "Humidity", null, " %");
        renderDiagnostic(tvDiagnosticWaterTemperature, "Water Temperature", null, " °C");
        renderDiagnostic(tvDiagnosticWaterLevel, "Water Level", null, " %");
        renderDiagnostic(tvDiagnosticWaterLevelDistance, "Water Level Distance", null, " cm");
    }

    private void loadCurrentValues() {
        mockSensorsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    Boolean enabled = snapshot.child("enabled").getValue(Boolean.class);
                    if (enabled != null) switchMockEnable.setChecked(enabled);
                    
                    Double ph = snapshot.child("ph").getValue(Double.class);
                    if (ph != null) etPh.setText(String.valueOf(ph));

                    Double ec = snapshot.child("ec").getValue(Double.class);
                    if (ec != null) etEc.setText(String.valueOf(ec));

                    Double temp = snapshot.child("airTemperature").getValue(Double.class);
                    if (temp != null) etTemp.setText(String.valueOf(temp));

                    Double humidity = snapshot.child("humidity").getValue(Double.class);
                    if (humidity != null) etHumidity.setText(String.valueOf(humidity));

                    Double waterTemperature = snapshot.child("waterTemperature").getValue(Double.class);
                    if (waterTemperature != null) etWaterTemperature.setText(String.valueOf(waterTemperature));

                    Double waterLevel = snapshot.child("waterLevel").getValue(Double.class);
                    if (waterLevel != null) etWaterLevel.setText(String.valueOf(waterLevel));
                }
                loadingMockState = false;
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                loadingMockState = false;
                if (getContext() != null) {
                    Toast.makeText(getContext(), "Failed to load mock data", Toast.LENGTH_SHORT).show();
                }
            }
        });
        
        settingsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    Double minLevel = snapshot.child("refillStartLevel").getValue(Double.class);
                    if (minLevel != null) etMinWaterLevel.setText(String.valueOf(minLevel));

                    Double maxLevel = snapshot.child("refillStopLevel").getValue(Double.class);
                    if (maxLevel != null) etMaxWaterLevel.setText(String.valueOf(maxLevel));
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                if (getContext() != null) {
                    Toast.makeText(getContext(), "Failed to load settings data", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void showLoading(String title, String status) {
        View view = getView();
        if (view == null || !isAdded()) return;
        View layoutLoading = view.findViewById(R.id.layoutLoading);
        TextView tvTitle = view.findViewById(R.id.tvLoadingTitle);
        TextView tvStatus = view.findViewById(R.id.tvLoadingStatus);

        if (layoutLoading != null) {
            layoutLoading.setVisibility(View.VISIBLE);
            layoutLoading.bringToFront();
        }
        if (tvTitle != null) tvTitle.setText(title);
        if (tvStatus != null) tvStatus.setText(status);
    }

    private void hideLoading() {
        View view = getView();
        if (view == null || !isAdded()) return;
        View layoutLoading = view.findViewById(R.id.layoutLoading);
        if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
    }

    private void pushMockValues() {
        Map<String, Object> updates = new HashMap<>();
        updates.put("enabled", switchMockEnable.isChecked());
        
        try {
            if (!etPh.getText().toString().isEmpty()) updates.put("ph", Double.parseDouble(etPh.getText().toString()));
            if (!etEc.getText().toString().isEmpty()) updates.put("ec", Double.parseDouble(etEc.getText().toString()));
            if (!etTemp.getText().toString().isEmpty()) updates.put("airTemperature", Double.parseDouble(etTemp.getText().toString()));
            if (!etHumidity.getText().toString().isEmpty()) updates.put("humidity", Double.parseDouble(etHumidity.getText().toString()));
            if (!etWaterTemperature.getText().toString().isEmpty()) updates.put("waterTemperature", Double.parseDouble(etWaterTemperature.getText().toString()));
            if (!etWaterLevel.getText().toString().isEmpty()) updates.put("waterLevel", Double.parseDouble(etWaterLevel.getText().toString()));

            showLoading("Pushing Mock Data...", "Writing values to ESP32...");

            mockSensorsRef.updateChildren(updates).addOnSuccessListener(unused ->
                    waitForMockAcknowledgement(switchMockEnable.isChecked()))
                    .addOnFailureListener(error -> {
                        hideLoading();
                        if (isAdded()) NotificationHelper.showError(requireContext(), "Mock Data Failed", error.getMessage());
                    });
        } catch (NumberFormatException e) {
            hideLoading();
            Toast.makeText(getContext(), "Please enter valid numbers", Toast.LENGTH_SHORT).show();
        }
    }

    private void disableMockMode() {
        showLoading("Disabling Mock Data...", "Returning firmware to physical sensors...");
        mockSensorsRef.child("enabled").setValue(false)
                .addOnSuccessListener(unused -> waitForMockAcknowledgement(false))
                .addOnFailureListener(error -> {
                    hideLoading();
                    if (isAdded()) NotificationHelper.showError(requireContext(), "Mock Data Failed", error.getMessage());
                });
    }

    private void waitForMockAcknowledgement(boolean expectedEnabled) {
        DatabaseReference ackRef = deviceRef.child("status").child("mockData");
        final ValueEventListener[] listenerHolder = new ValueEventListener[1];
        Runnable timeout = () -> {
            if (listenerHolder[0] != null) ackRef.removeEventListener(listenerHolder[0]);
            hideLoading();
            if (isAdded()) NotificationHelper.showWarning(requireContext(), "ESP32 Confirmation Pending",
                    "Firebase accepted the mock command, but the ESP32 did not confirm it yet.");
        };

        listenerHolder[0] = ackRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean acknowledged = snapshot.getValue(Boolean.class);
                if (acknowledged == null || acknowledged != expectedEnabled) return;
                ackRef.removeEventListener(this);
                mainHandler.removeCallbacks(timeout);
                hideLoading();
                if (!isAdded()) return;
                if (expectedEnabled) {
                    NotificationHelper.showSuccess(requireContext(), "ESP32 mock mode is active and automation is using the supplied sensor values.");
                } else {
                    NotificationHelper.showSuccess(requireContext(), "ESP32 mock mode is off and physical sensors are authoritative.");
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                ackRef.removeEventListener(this);
                mainHandler.removeCallbacks(timeout);
                hideLoading();
                if (isAdded()) NotificationHelper.showError(requireContext(), "Mock Data Failed", error.getMessage());
            }
        });
        mainHandler.postDelayed(timeout, 15000L);
    }

    private void pushSettings() {
        Map<String, Object> updates = new HashMap<>();
        try {
            if (!etMinWaterLevel.getText().toString().isEmpty()) updates.put("refillStartLevel", Double.parseDouble(etMinWaterLevel.getText().toString()));
            if (!etMaxWaterLevel.getText().toString().isEmpty()) updates.put("refillStopLevel", Double.parseDouble(etMaxWaterLevel.getText().toString()));

            showLoading("Pushing Thresholds...", "Writing settings to ESP32...");

            settingsRef.updateChildren(updates).addOnCompleteListener(task -> {
                hideLoading();
                if (task.isSuccessful() && getContext() != null) {
                    Toast.makeText(getContext(), "Refill thresholds pushed to ESP32", Toast.LENGTH_SHORT).show();
                } else if (getContext() != null) {
                    Toast.makeText(getContext(), "Failed to push settings", Toast.LENGTH_SHORT).show();
                }
            });
        } catch (NumberFormatException e) {
            hideLoading();
            Toast.makeText(getContext(), "Please enter valid numbers", Toast.LENGTH_SHORT).show();
        }
    }

    private void handleSaveWifiCredentials() {
        String ssid = etSsid.getText() != null ? etSsid.getText().toString().trim() : "";
        String password = etPassword.getText() != null ? etPassword.getText().toString().trim() : "";

        if (ssid.isEmpty()) {
            etSsid.setError("SSID cannot be empty");
            return;
        }

        NotificationHelper.showConfirmation(requireContext(),
                "Change Wi-Fi Credentials?",
                "Connect this phone to the ESP32 'Basilience-Setup' Wi-Fi network, then Basilience will send the credentials directly to the ESP32 over the local setup page. Firebase is not required for this step.",
                "Send Locally", "Cancel", () -> sendWifiCommandLocal(ssid, password));
    }

    private void sendWifiCommandLocal(String ssid, String password) {
        showLoading("Connecting to ESP32...", "Sending credentials through Basilience-Setup Wi-Fi...");
        
        new Thread(() -> {
            try {
                int responseCode = LocalProvisioningClient.sendCredentials(requireContext().getApplicationContext(), ssid, password);
                
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (responseCode == 200) {
                        showLoading("Credentials Sent!", "ESP32 is restarting...");
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
                    if (isAdded()) {
                        NotificationHelper.showError(requireContext(), "Wi-Fi Configuration Failed",
                                "Wi-Fi configuration could not be sent.\nReconnect to Basilience-Setup and try again.");
                    }
                });
            }
        }).start();
    }

    private void enableProvisioningApMode() {
        if (deviceRef == null) return;

        NotificationHelper.showConfirmation(requireContext(),
                "Enable Provisioning/AP Mode?",
                "This sends a developer command to the online ESP32 to start the local Basilience-Setup access point. No Wi-Fi credentials are sent through Firebase.",
                "Enable AP", "Cancel", () -> {
                    showLoading("Starting AP Mode...", "Sending developer command to ESP32...");
                    deviceRef.child("commands").child("startProvisioning").setValue(System.currentTimeMillis())
                            .addOnSuccessListener(aVoid -> {
                                showLoading("AP command sent", "Connect this phone to Basilience-Setup, then send Wi-Fi credentials locally.");
                                new Handler(Looper.getMainLooper()).postDelayed(this::hideLoading, 3000);
                            })
                            .addOnFailureListener(e -> {
                                hideLoading();
                                Toast.makeText(getContext(), "Unable to trigger AP mode: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            });
                });
    }

    private void disableDeveloperMode(NavController navController) {
        if (getContext() == null) return;

        requireContext()
                .getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_DEVELOPER_MODE_ENABLED, false)
                .apply();
        if (settingsRef != null) settingsRef.child("devModeEnabled").setValue(false);
        Toast.makeText(getContext(), "Developer Mode disabled", Toast.LENGTH_SHORT).show();
        navController.popBackStack();
    }

    private void checkSetupApReachability() {
        new Thread(() -> {
            boolean reachable = LocalProvisioningClient.isSetupApReachable(requireContext().getApplicationContext());

            boolean finalReachable = reachable;
            new Handler(Looper.getMainLooper()).post(() -> {
                setupApReachable = finalReachable;
                renderWifiStatusUI();
            });
        }).start();
    }

    private void renderWifiStatusUI() {
        if (tvWifiStatus == null || !isAdded()) return;

        if (setupApReachable) {
            tvWifiStatus.setText("● Provisioning");
            tvWifiStatus.setTextColor(androidx.core.content.ContextCompat.getColor(
                    requireContext(), R.color.device_status_reconnecting));
        } else {
            tvWifiStatus.setText("● " + connectivityState.getLabel());
            tvWifiStatus.setTextColor(androidx.core.content.ContextCompat.getColor(
                    requireContext(), connectivityState.getColorRes()));
        }
    }

    // This button permanently replaces a device's Reports history and is
    // meant only for exercising the Reports UI against simulated data, so it
    // requires (a) an explicit destructive confirmation before it can run at
    // all, and (b) that the device's own existing Mock Sensors state - not a
    // new permission concept - already indicates this device is currently in
    // a testing configuration rather than serving real production readings.
    private void confirmAndInjectMockFirestoreLogs(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) return;

        if (loadingMockState) {
            if (getContext() != null) {
                Toast.makeText(getContext(), "Still loading Mock Sensors state, please try again in a moment.", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if (!switchMockEnable.isChecked()) {
            NotificationHelper.showError(requireContext(), "Mock Sensors Required",
                    "Injecting simulated report data deletes this device's existing parameter and fogging "
                            + "history, so it's only allowed while Mock Sensors are enabled for this device. "
                            + "Enable Mock Sensors above first.");
            return;
        }

        NotificationHelper.showDestructiveConfirmation(requireContext(), "Replace Report History?",
                "This will permanently delete ALL existing parameter and fogging history for device \""
                        + deviceId + "\" and replace it with 30 days of simulated data.\n\n"
                        + "This cannot be casually undone.",
                "Delete & Inject",
                () -> injectMockFirestoreLogs(deviceId));
    }

    private void injectMockFirestoreLogs(String deviceId) {
        showLoading("Injecting Mock Logs...", "Clearing previous logs...");

        if (btnInjectLogs != null) {
            btnInjectLogs.setEnabled(false);
            btnInjectLogs.setText("Injecting...");
        }

        FirebaseFirestore db = FirebaseFirestore.getInstance();
        
        db.collection("devices").document(deviceId).collection("foggingLogs").get().addOnSuccessListener(fogSnaps -> {
            WriteBatch deleteBatch = db.batch();
            for (DocumentSnapshot doc : fogSnaps) {
                deleteBatch.delete(doc.getReference());
            }
            db.collection("devices").document(deviceId).collection("parameterLogs").get().addOnSuccessListener(paramSnaps -> {
                for (DocumentSnapshot doc : paramSnaps) {
                    deleteBatch.delete(doc.getReference());
                }
                deleteBatch.commit().addOnSuccessListener(aVoid -> {
                    injectFreshMockData(deviceId);
                });
            });
        }).addOnFailureListener(e -> {
            hideLoading();
            if (btnInjectLogs != null) {
                btnInjectLogs.setEnabled(true);
                btnInjectLogs.setText("Inject Mock Firestore Logs");
            }
            if (getContext() != null) {
                Toast.makeText(getContext(), "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void injectFreshMockData(String deviceId) {
        showLoading("Injecting Mock Logs...", "Writing 30 days of simulation logs...");

        FirebaseFirestore db = FirebaseFirestore.getInstance();
        WriteBatch batch = db.batch();
        Random random = new Random();
        long now = System.currentTimeMillis();
        
        for (int i = 0; i < 180; i++) {
            long timestamp = now - (i * 14400000L);
            Map<String, Object> log = new HashMap<>();
            log.put("timestamp", timestamp);
            log.put("ph", 5.5 + random.nextDouble() * 1.5);
            log.put("ec", 1.0 + random.nextDouble() * 1.0);
            log.put("air_temp", 22.0 + random.nextDouble() * 6.0);
            log.put("humidity", 45.0 + random.nextDouble() * 25.0);
            log.put("water_temp", 20.0 + random.nextDouble() * 4.0);
            log.put("water_level", 40.0 + random.nextDouble() * 50.0);

            DocumentReference ref = db.collection("devices").document(deviceId)
                    .collection("parameterLogs").document("mock_param_" + timestamp);
            batch.set(ref, log);
        }

        long currentSimTime = now;
        for (int i = 0; i < 60; i++) {
            long gap = (long)(28800000L + random.nextDouble() * 28800000L); 
            long offTime = currentSimTime - gap;
            
            long duration = (long)(120000 + random.nextDouble() * 300000); 
            long onTime = offTime - duration;
            
            boolean isManual = random.nextBoolean();

            Map<String, Object> onEvent = new HashMap<>();
            onEvent.put("event", "ON");
            onEvent.put("timestamp", onTime);
            onEvent.put("isManual", isManual);
            DocumentReference refOn = db.collection("devices").document(deviceId)
                    .collection("foggingLogs").document("mock_fog_on_" + onTime);
            batch.set(refOn, onEvent);

            Map<String, Object> offEvent = new HashMap<>();
            offEvent.put("event", "OFF");
            offEvent.put("timestamp", offTime);
            offEvent.put("isManual", isManual);
            DocumentReference refOff = db.collection("devices").document(deviceId)
                    .collection("foggingLogs").document("mock_fog_off_" + offTime);
            batch.set(refOff, offEvent);
            
            currentSimTime = onTime;
        }

        batch.commit().addOnSuccessListener(aVoid -> {
            hideLoading();
            if (getContext() != null) {
                Toast.makeText(getContext(), "Mock logs injected! Check Reports.", Toast.LENGTH_SHORT).show();
            }
            if (btnInjectLogs != null) {
                btnInjectLogs.setEnabled(true);
                btnInjectLogs.setText("Inject Mock Firestore Logs");
            }
        }).addOnFailureListener(e -> {
            hideLoading();
            if (getContext() != null) {
                Toast.makeText(getContext(), "Failed to inject: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
            if (btnInjectLogs != null) {
                btnInjectLogs.setEnabled(true);
                btnInjectLogs.setText("Inject Mock Firestore Logs");
            }
        });
    }

    @Override
    public void onDestroyView() {
        if ((sensorTestActive || sensorTestRequested) && sensorTestCommandRef != null) {
            sensorTestCommandRef.setValue(false);
        }
        if (sensorTestStatusRef != null && sensorTestStatusListener != null) {
            sensorTestStatusRef.removeEventListener(sensorTestStatusListener);
        }
        if (diagnosticSensorsRef != null && diagnosticSensorsListener != null) {
            diagnosticSensorsRef.removeEventListener(diagnosticSensorsListener);
        }
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroyView();
    }
}
