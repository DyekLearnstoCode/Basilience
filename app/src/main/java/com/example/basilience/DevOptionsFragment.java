package com.example.basilience;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class DevOptionsFragment extends Fragment {

    private DatabaseReference mockSensorsRef;
    private DatabaseReference settingsRef;
    private DatabaseReference deviceRef;
    private DatabaseReference sensorTestCommandRef;
    private DatabaseReference sensorTestStatusRef;
    private DatabaseReference diagnosticSensorsRef;
    private DatabaseReference ignoreWaterLevelCommandRef;
    private DatabaseReference ignoreWaterLevelStatusRef;

    private SwitchMaterial switchMockEnable;
    private SwitchMaterial switchIgnoreWaterLevel;
    private boolean loadingIgnoreWaterLevelState = true;
    private boolean suppressIgnoreWaterLevelSwitchCallback = false;
    private ValueEventListener ignoreWaterLevelStatusListener;
    private EditText etPh, etEc, etTemp, etHumidity, etWaterTemperature, etWaterLevel;
    private TextView tvSensorTestIndicator;
    private TextView tvDiagnosticPh, tvDiagnosticEc, tvDiagnosticAirTemperature;
    private TextView tvDiagnosticHumidity, tvDiagnosticWaterTemperature, tvDiagnosticWaterLevel;
    private TextView tvDiagnosticWaterLevelDistance;

    private MaterialButton btnPush, btnEnableProvisioningAp, btnDisableDeveloperMode;
    private MaterialButton btnFilterSensorTest, btnFilterMock, btnFilterRefill, btnSensorTest;
    private MaterialButton btnSaveRefillThresholds;
    private TextInputLayout layoutRefillStart, layoutRefillStop;
    private TextInputEditText etRefillStart, etRefillStop;
    private float loadedRefillStart = 20.0f;
    private float loadedRefillStop = 75.0f;

    private View containerSensorTest, containerMockData, containerRefill;

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

        // Containers
        containerSensorTest = view.findViewById(R.id.containerSensorTest);
        containerMockData = view.findViewById(R.id.containerMockData);
        containerRefill = view.findViewById(R.id.containerRefill);

        // Water level automation override
        switchIgnoreWaterLevel = view.findViewById(R.id.switchIgnoreWaterLevel);

        // Mock data components
        switchMockEnable = view.findViewById(R.id.switchMockEnable);
        etPh = view.findViewById(R.id.etPh);
        etEc = view.findViewById(R.id.etEc);
        etTemp = view.findViewById(R.id.etTemp);
        etHumidity = view.findViewById(R.id.etHumidity);
        etWaterTemperature = view.findViewById(R.id.etWaterTemperature);
        etWaterLevel = view.findViewById(R.id.etWaterLevel);
        btnPush = view.findViewById(R.id.btnPush);

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

        // Refill threshold components
        layoutRefillStart = view.findViewById(R.id.layoutRefillStart);
        layoutRefillStop = view.findViewById(R.id.layoutRefillStop);
        etRefillStart = view.findViewById(R.id.etRefillStart);
        etRefillStop = view.findViewById(R.id.etRefillStop);
        btnSaveRefillThresholds = view.findViewById(R.id.btnSaveRefillThresholds);

        // Always-visible actions below the tabs
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
        ignoreWaterLevelCommandRef = deviceRef.child("commands/ignoreWaterLevelAutomation/enabled");
        ignoreWaterLevelStatusRef = deviceRef.child("status/ignoreWaterLevelAutomation");

        loadCurrentValues();
        loadRefillThresholds();
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

        switchIgnoreWaterLevel.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressIgnoreWaterLevelSwitchCallback || loadingIgnoreWaterLevelState) return;
            if (isChecked) {
                suppressIgnoreWaterLevelSwitchCallback = true;
                switchIgnoreWaterLevel.setChecked(false);
                suppressIgnoreWaterLevelSwitchCallback = false;
                NotificationHelper.showConfirmation(requireContext(),
                        "Ignore Water Level Automation?",
                        "Automatic refill and the low-water automation lock will be ignored while this developer override is enabled. The real water-level reading and alerts will remain active. Use this only for testing.",
                        "Enable", "Cancel", () -> setIgnoreWaterLevelCommand(true));
                return;
            }
            setIgnoreWaterLevelCommand(false);
        });

        // Setup filter button listeners
        btnFilterSensorTest.setOnClickListener(v -> updateFilterSelection("Sensor"));
        btnFilterMock.setOnClickListener(v -> updateFilterSelection("Mock"));
        btnFilterRefill.setOnClickListener(v -> updateFilterSelection("Refill"));

        btnPush.setOnClickListener(v -> pushMockValues());
        if (btnSaveRefillThresholds != null) {
            btnSaveRefillThresholds.setOnClickListener(v -> saveRefillThresholds());
        }
        btnSensorTest.setOnClickListener(v -> handleSensorTestButton());
        if (btnEnableProvisioningAp != null) {
            btnEnableProvisioningAp.setOnClickListener(v -> enableProvisioningApMode());
        }
        if (btnDisableDeveloperMode != null) {
            btnDisableDeveloperMode.setOnClickListener(v -> disableDeveloperMode(navController));
        }

        observeSensorTest();
        observeIgnoreWaterLevelOverride();
        updateFilterSelection("Sensor");
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
     * Sensor Test, Mock Data, and Refill remain as tabs - Wi-Fi Config was
     * removed from Developer Options (moved to Device Management). Refill
     * threshold configuration was re-added here after previously having no
     * developer UI.
     */
    private void updateFilterSelection(String selectedFilter) {
        boolean sensor = "Sensor".equalsIgnoreCase(selectedFilter);
        boolean mock = "Mock".equalsIgnoreCase(selectedFilter);
        boolean refill = "Refill".equalsIgnoreCase(selectedFilter);

        btnFilterSensorTest.setSelected(sensor);
        btnFilterMock.setSelected(mock);
        btnFilterRefill.setSelected(refill);

        containerSensorTest.setVisibility(sensor ? View.VISIBLE : View.GONE);
        containerMockData.setVisibility(mock ? View.VISIBLE : View.GONE);
        containerRefill.setVisibility(refill ? View.VISIBLE : View.GONE);
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
                renderDiagnostic(tvDiagnosticWaterTemperature, "Water Temperature",
                        snapshot.child("waterTemperature").getValue(), " °C", "Unavailable");
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

    /**
     * Writes the developer water-level override command and waits for the
     * authoritative echo on {@code status/ignoreWaterLevelAutomation} -
     * observeIgnoreWaterLevelOverride()'s listener is what actually flips the
     * switch and hides the loading overlay on success, mirroring how
     * setSensorTestCommand()/observeSensorTest() split the same two concerns.
     * The switch is deliberately left unchanged on the optimistic path (not
     * set true just because the user tapped "Enable") - only the confirmed
     * Firebase value ever drives it, and a write failure explicitly restores
     * the prior checked state.
     */
    private void setIgnoreWaterLevelCommand(boolean enabled) {
        if (ignoreWaterLevelCommandRef == null) return;
        showLoading(enabled ? "Enabling Override..." : "Disabling Override...",
                "Waiting for ESP32 acknowledgement...");
        ignoreWaterLevelCommandRef.setValue(enabled).addOnFailureListener(error -> {
            hideLoading();
            suppressIgnoreWaterLevelSwitchCallback = true;
            switchIgnoreWaterLevel.setChecked(!enabled);
            suppressIgnoreWaterLevelSwitchCallback = false;
            if (isAdded()) {
                NotificationHelper.showError(requireContext(), "Water Level Override Failed", error.getMessage());
            }
        });
    }

    private void observeIgnoreWaterLevelOverride() {
        ignoreWaterLevelStatusListener = ignoreWaterLevelStatusRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean enabled = snapshot.getValue(Boolean.class);
                loadingIgnoreWaterLevelState = false;
                hideLoading();
                if (switchIgnoreWaterLevel == null) return;
                suppressIgnoreWaterLevelSwitchCallback = true;
                switchIgnoreWaterLevel.setChecked(Boolean.TRUE.equals(enabled));
                suppressIgnoreWaterLevelSwitchCallback = false;
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                loadingIgnoreWaterLevelState = false;
                hideLoading();
            }
        });
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
        renderDiagnostic(view, label, rawValue, unit, "NO VALID READING");
    }

    // Row stays visible either way - only the status line under the label
    // changes - so a dead/disconnected sensor reads as an explicit,
    // unmistakable status rather than a blank or missing row. unavailableText
    // lets one field (Water Temperature) use clearer wording for hardware
    // diagnosis without changing every other diagnostic row's existing style.
    private void renderDiagnostic(TextView view, String label, Object rawValue, String unit, String unavailableText) {
        if (view == null) return;
        if (!sensorTestActive) {
            view.setText(label + "\n--\n" + unavailableText);
            view.setTextColor(ContextCompat.getColor(requireContext(), R.color.sensor_no_data));
        } else if (rawValue instanceof Number) {
            view.setText(String.format(Locale.US, "%s\n%.2f%s\nREADING", label,
                    ((Number) rawValue).doubleValue(), unit));
            view.setTextColor(ContextCompat.getColor(requireContext(), R.color.sensor_reading));
        } else {
            view.setText(label + "\n--\n" + unavailableText);
            view.setTextColor(ContextCompat.getColor(requireContext(), R.color.sensor_no_data));
        }
    }

    private void renderAllDiagnosticsUnavailable() {
        renderDiagnostic(tvDiagnosticPh, "pH", null, "");
        renderDiagnostic(tvDiagnosticEc, "EC", null, " mS/cm");
        renderDiagnostic(tvDiagnosticAirTemperature, "Air Temperature", null, " °C");
        renderDiagnostic(tvDiagnosticHumidity, "Humidity", null, " %");
        renderDiagnostic(tvDiagnosticWaterTemperature, "Water Temperature", null, " °C", "Unavailable");
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
    }

    /**
     * Actuator hysteresis (when the solenoid opens/closes), not a target
     * range - deliberately absent from ParameterTargetRangesFragment (see
     * that class's own comment). A field missing from Firebase falls back to
     * the same defaults firmware itself compiles with (Config.h's
     * REFILL_START_LEVEL/REFILL_STOP_LEVEL) rather than showing a misleading 0.
     */
    private void loadRefillThresholds() {
        if (settingsRef == null) return;
        settingsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!isAdded()) return;
                Double start = snapshot.child("refillStartLevel").getValue(Double.class);
                Double stop = snapshot.child("refillStopLevel").getValue(Double.class);
                if (start != null) loadedRefillStart = start.floatValue();
                if (stop != null) loadedRefillStop = stop.floatValue();
                if (etRefillStart != null) etRefillStart.setText(String.format(Locale.US, "%.1f", loadedRefillStart));
                if (etRefillStop != null) etRefillStop.setText(String.format(Locale.US, "%.1f", loadedRefillStop));
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                if (!isAdded()) return;
                Toast.makeText(getContext(), "Failed to load refill thresholds", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void saveRefillThresholds() {
        if (settingsRef == null || etRefillStart == null || etRefillStop == null) return;

        if (layoutRefillStart != null) layoutRefillStart.setError(null);
        if (layoutRefillStop != null) layoutRefillStop.setError(null);

        Float start = parsePositiveFloat(etRefillStart);
        Float stop = parsePositiveFloat(etRefillStop);
        boolean valid = true;

        if (start == null || start < 0f || start > 100f) {
            if (layoutRefillStart != null) layoutRefillStart.setError("Enter a value between 0 and 100");
            valid = false;
        }
        if (stop == null || stop < 0f || stop > 100f) {
            if (layoutRefillStop != null) layoutRefillStop.setError("Enter a value between 0 and 100");
            valid = false;
        }
        if (valid && start >= stop) {
            if (layoutRefillStart != null) layoutRefillStart.setError("Start must be lower than Stop");
            valid = false;
        }

        if (!valid) {
            Toast.makeText(getContext(), "Please correct the highlighted values", Toast.LENGTH_SHORT).show();
            return;
        }

        final float finalStart = start;
        final float finalStop = stop;

        Map<String, Object> updates = new HashMap<>();
        updates.put("refillStartLevel", (double) finalStart);
        updates.put("refillStopLevel", (double) finalStop);

        showLoading("Saving Refill Thresholds...", "Writing values to ESP32...");
        settingsRef.updateChildren(updates)
                .addOnSuccessListener(unused -> {
                    hideLoading();
                    if (!isAdded()) return;
                    loadedRefillStart = finalStart;
                    loadedRefillStop = finalStop;
                    NotificationHelper.showSuccess(requireContext(), "Refill thresholds saved");
                })
                .addOnFailureListener(error -> {
                    hideLoading();
                    if (isAdded()) NotificationHelper.showError(requireContext(), "Save Failed", error.getMessage());
                });
    }

    private Float parsePositiveFloat(TextInputEditText field) {
        if (field.getText() == null) return null;
        String text = field.getText().toString().trim();
        if (text.isEmpty()) return null;
        try {
            float value = Float.parseFloat(text);
            if (Float.isNaN(value) || Float.isInfinite(value)) return null;
            return value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private long loadingShownAt;

    private void showLoading(String title, String status) {
        View view = getView();
        if (view == null || !isAdded()) return;
        View layoutLoading = view.findViewById(R.id.layoutLoading);
        TextView tvTitle = view.findViewById(R.id.tvLoadingTitle);
        TextView tvStatus = view.findViewById(R.id.tvLoadingStatus);

        loadingShownAt = SystemClock.elapsedRealtime();
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
        if (layoutLoading == null || layoutLoading.getVisibility() != View.VISIBLE) return;
        NotificationHelper.hideLoaderAfterMinimumDuration(loadingShownAt, () -> {
            if (isAdded() && getView() != null) {
                View overlay = getView().findViewById(R.id.layoutLoading);
                if (overlay != null) overlay.setVisibility(View.GONE);
            }
        });
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
        // Best-effort safety net: a developer bypassing the water-level
        // automation gate must not leave it silently enabled once they've
        // exited Developer Mode. Fire-and-forget, matching devModeEnabled
        // above - this screen is being torn down either way.
        if (ignoreWaterLevelCommandRef != null) ignoreWaterLevelCommandRef.setValue(false);
        Toast.makeText(getContext(), "Developer Mode disabled", Toast.LENGTH_SHORT).show();
        navController.popBackStack();
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
        if (ignoreWaterLevelStatusRef != null && ignoreWaterLevelStatusListener != null) {
            ignoreWaterLevelStatusRef.removeEventListener(ignoreWaterLevelStatusListener);
        }
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroyView();
    }
}
