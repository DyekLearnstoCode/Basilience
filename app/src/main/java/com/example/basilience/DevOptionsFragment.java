package com.example.basilience;

import android.app.TimePickerDialog;
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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
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
    private DatabaseReference automationTestModeCommandRef;
    private DatabaseReference automationTestModeStatusRef;
    private DatabaseReference manualModeCommandRef;
    private ValueEventListener manualModeStatusListener;

    private SwitchMaterial switchMockEnable;
    private SwitchMaterial switchDynamicMock;
    private SwitchMaterial switchIgnoreWaterLevel;
    private boolean loadingIgnoreWaterLevelState = true;
    private boolean suppressIgnoreWaterLevelSwitchCallback = false;
    private ValueEventListener ignoreWaterLevelStatusListener;
    private ValueEventListener automationTestModeStatusListener;
    private Spinner spinnerAutomationTestMode;
    private TextView tvAutomationTestModeStatus;
    private boolean automationTestModeStatusLoaded = false;
    private boolean suppressAutomationTestModeCallback = false;
    private String confirmedAutomationTestMode = "OFF";

    // Grow Light Schedule test mode only - see containerMockGrowLightTime's
    // own layout comment. Writes to the same commands/automationTestMode
    // node as automationTestModeCommandRef, never to a real-time/RTC path.
    private View containerMockGrowLightTime;
    private SwitchMaterial switchMockGrowLightTime;
    private MaterialButton btnMockGrowLightTime;
    private boolean loadingMockGrowLightTimeState = true;
    private boolean suppressMockGrowLightTimeSwitchCallback = false;
    private int mockGrowLightMinutes = 360; // 06:00 default, matches the production ON hour

    private static final String[] AUTOMATION_TEST_MODE_LABELS = {
            "Off / Full System",
            "Startup",
            "Water Refill",
            "pH Regulation",
            "EC Regulation",
            "Water Cooling",
            "Fogging",
            "Canopy Climate",
            "Grow Light Schedule"
    };
    private static final String[] AUTOMATION_TEST_MODE_VALUES = {
            "OFF", "STARTUP", "REFILL", "PH", "EC", "COOLING",
            "FOGGING", "CANOPY", "GROW_LIGHT"
    };
    private EditText etPh, etEc, etTemp, etHumidity, etWaterTemperature, etWaterLevel, etWaterLevelCm;
    private TextView tvSensorTestIndicator;
    private TextView tvIgnoreWaterLevelIndicator;
    private TextView tvDiagnosticPh, tvDiagnosticEc, tvDiagnosticAirTemperature;
    private TextView tvDiagnosticHumidity, tvDiagnosticWaterTemperature, tvDiagnosticWaterLevel;
    private TextView tvDiagnosticWaterLevelDistance;
    private TextView tvDiagnosticWaterDepth;

    private MaterialButton btnPush, btnEnableProvisioningAp, btnDisableDeveloperMode;
    private MaterialButton btnFilterSensorTest, btnFilterMock, btnFilterRefill, btnSensorTest;
    private MaterialButton btnSaveRefillThresholds;
    private TextInputLayout layoutRefillStart, layoutRefillStop;
    private TextInputEditText etRefillStart, etRefillStop;
    // Water-depth model (centimeters) - see firmware Config.h's "Water
    // Reservoir Geometry". Matches REFILL_START_CM/REFILL_STOP_CM.
    private float loadedRefillStart = 2.0f;
    private float loadedRefillStop = 3.0f;

    // EC Voltage diagnostic tile - raw signal behind the EC reading, useful
    // for Sensor Test hardware inspection. No calibration mechanism here;
    // the accepted EC calibration is unchanged.
    private TextView tvDiagnosticEcVoltage;

    private View containerSensorTest, containerMockData, containerRefill, containerCanopyPwm;
    private View cardAutomationTestMode, containerIgnoreWaterLevel;
    // Admin-only navigation link, not a chip-toggled tab like the containers
    // above - visible whenever this screen is in Device Configuration
    // (maintenanceMode) rather than Developer Options.
    private View rowTargetRangesLink;
    private MaterialButton btnFilterCanopyPwm;

    // Isolated Canopy Fan PWM diagnostic (real-hardware Canopy/Blower PWM
    // verification follow-up) - reuses the existing manual actuator command
    // path (Database_Helper.updateActuatorState) rather than a parallel test
    // mechanism, so it inherits manual ownership arbitration, admin
    // authorization, and the firmware's own [CANOPY-PWM] duty logging for
    // free. Does not touch automationTestMode, mockSensors, or any other
    // subsystem - only commands/canopyFan.
    private Database_Helper dbHelper;
    private MaterialButton btnCanopyPwm0, btnCanopyPwm30, btnCanopyPwm50, btnCanopyPwm75, btnCanopyPwm100;
    private TextView tvCanopyPwmStatus, tvCanopyPwmManualModeWarning;
    private boolean canopyManualModeOn = false;
    // True only while this screen has commanded a non-zero test percentage
    // and hasn't yet commanded it back off - drives the safe-state restore
    // in onDestroyView(), mirroring how sensorTestActive/Requested are
    // cleaned up on exit.
    private boolean canopyPwmTestActive = false;

    // True only for accounts with users/{uid}.isDeveloper == true in Firestore
    // (see Auth_Login_Activity, which caches it into this same "is_developer"
    // pref at login) - narrower than, and separate from, the ADMIN role check
    // above that gates entry to this screen at all. Never settable from
    // within the app (see firestore.rules); gates the subset of Developer
    // Options that can act on a live grow (Mock Sensors, Automation Test
    // Mode, the Ignore Water Level safety bypass, Canopy PWM raw commands),
    // while Sensor Test/Refill thresholds stay available to any Admin.
    private boolean isDeveloper = false;

    private boolean loadingMockState = true;
    private boolean suppressMockSwitchCallback = false;
    private boolean sensorTestActive = false;
    private boolean sensorTestRequested = false;
    private ValueEventListener sensorTestStatusListener;
    private ValueEventListener diagnosticSensorsListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final String PREFS_NAME = "basilience_prefs";
    private static final String KEY_DEVELOPER_MODE_ENABLED = "developer_mode_enabled";
    private boolean maintenanceMode;

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
        maintenanceMode = getArguments() != null
                && getArguments().getBoolean("maintenanceMode", false);
        boolean isAdmin = RoleConstants.ROLE_ADMIN.equalsIgnoreCase(
                accessPrefs.getString("user_role", ""));
        String authorizedDeviceId = accessPrefs.getString("selected_device_id", null);
        boolean developerAuthorized = RoleConstants.isDeveloperTester(accessPrefs)
                && accessPrefs.getBoolean(KEY_DEVELOPER_MODE_ENABLED, false)
                && authorizedDeviceId != null
                && authorizedDeviceId.equals(accessPrefs.getString(
                        RoleConstants.PREF_DEVELOPER_MODE_DEVICE_ID, null));
        if ((maintenanceMode && !isAdmin) || (!maintenanceMode && !developerAuthorized)) {
            Toast.makeText(requireContext(), maintenanceMode
                    ? "Device Configuration is available to Admin users only"
                    : "Developer Tester access is required", Toast.LENGTH_SHORT).show();
            navController.popBackStack();
            return;
        }
        isDeveloper = accessPrefs.getBoolean("is_developer", false);
        View btnBack = view.findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setVisibility(View.VISIBLE);
            btnBack.setOnClickListener(v -> navController.popBackStack());
        }

        // Filter chips
        btnFilterSensorTest = view.findViewById(R.id.btnFilterSensorTest);
        btnFilterMock = view.findViewById(R.id.btnFilterMock);
        btnFilterRefill = view.findViewById(R.id.btnFilterRefill);
        btnFilterCanopyPwm = view.findViewById(R.id.btnFilterCanopyPwm);

        // Containers
        containerSensorTest = view.findViewById(R.id.containerSensorTest);
        containerMockData = view.findViewById(R.id.containerMockData);
        containerRefill = view.findViewById(R.id.containerRefill);
        containerCanopyPwm = view.findViewById(R.id.containerCanopyPwm);
        cardAutomationTestMode = view.findViewById(R.id.cardAutomationTestMode);
        containerIgnoreWaterLevel = view.findViewById(R.id.containerIgnoreWaterLevel);
        rowTargetRangesLink = view.findViewById(R.id.rowTargetRangesLink);
        if (rowTargetRangesLink != null) {
            // Navigates by destination ID rather than a named <action>, since
            // this row is now only ever shown from the devOptionsFragment
            // entry point (see configureAccessMode()) but this same
            // onViewCreated() runs for the deviceMaintenanceFragment entry
            // point too - a destination-ID navigate works from either
            // without needing two near-duplicate actions defined in the nav
            // graph for the same target.
            rowTargetRangesLink.setOnClickListener(v ->
                    navController.navigate(R.id.parameterTargetRangesFragment));
        }

        // Canopy Fan PWM test components
        btnCanopyPwm0 = view.findViewById(R.id.btnCanopyPwm0);
        btnCanopyPwm30 = view.findViewById(R.id.btnCanopyPwm30);
        btnCanopyPwm50 = view.findViewById(R.id.btnCanopyPwm50);
        btnCanopyPwm75 = view.findViewById(R.id.btnCanopyPwm75);
        btnCanopyPwm100 = view.findViewById(R.id.btnCanopyPwm100);
        tvCanopyPwmStatus = view.findViewById(R.id.tvCanopyPwmStatus);
        tvCanopyPwmManualModeWarning = view.findViewById(R.id.tvCanopyPwmManualModeWarning);

        // Water level automation override
        switchIgnoreWaterLevel = view.findViewById(R.id.switchIgnoreWaterLevel);
        tvIgnoreWaterLevelIndicator = view.findViewById(R.id.tvIgnoreWaterLevelIndicator);
        spinnerAutomationTestMode = view.findViewById(R.id.spinnerAutomationTestMode);
        tvAutomationTestModeStatus = view.findViewById(R.id.tvAutomationTestModeStatus);
        containerMockGrowLightTime = view.findViewById(R.id.containerMockGrowLightTime);
        switchMockGrowLightTime = view.findViewById(R.id.switchMockGrowLightTime);
        btnMockGrowLightTime = view.findViewById(R.id.btnMockGrowLightTime);

        // Mock data components
        switchMockEnable = view.findViewById(R.id.switchMockEnable);
        switchDynamicMock = view.findViewById(R.id.switchDynamicMock);
        etPh = view.findViewById(R.id.etPh);
        etEc = view.findViewById(R.id.etEc);
        etTemp = view.findViewById(R.id.etTemp);
        etHumidity = view.findViewById(R.id.etHumidity);
        etWaterTemperature = view.findViewById(R.id.etWaterTemperature);
        etWaterLevel = view.findViewById(R.id.etWaterLevel);
        etWaterLevelCm = view.findViewById(R.id.etWaterLevelCm);
        btnPush = view.findViewById(R.id.btnPush);

        // Physical sensor test components
        btnSensorTest = view.findViewById(R.id.btnSensorTest);
        tvSensorTestIndicator = view.findViewById(R.id.tvSensorTestIndicator);
        tvDiagnosticEcVoltage = view.findViewById(R.id.tvDiagnosticEcVoltage);

        tvDiagnosticPh = view.findViewById(R.id.tvDiagnosticPh);
        tvDiagnosticEc = view.findViewById(R.id.tvDiagnosticEc);
        tvDiagnosticAirTemperature = view.findViewById(R.id.tvDiagnosticAirTemperature);
        tvDiagnosticHumidity = view.findViewById(R.id.tvDiagnosticHumidity);
        tvDiagnosticWaterTemperature = view.findViewById(R.id.tvDiagnosticWaterTemperature);
        tvDiagnosticWaterLevel = view.findViewById(R.id.tvDiagnosticWaterLevel);
        tvDiagnosticWaterLevelDistance = view.findViewById(R.id.tvDiagnosticWaterLevelDistance);
        tvDiagnosticWaterDepth = view.findViewById(R.id.tvDiagnosticWaterDepth);

        // Refill threshold components
        layoutRefillStart = view.findViewById(R.id.layoutRefillStart);
        layoutRefillStop = view.findViewById(R.id.layoutRefillStop);
        etRefillStart = view.findViewById(R.id.etRefillStart);
        etRefillStop = view.findViewById(R.id.etRefillStop);
        btnSaveRefillThresholds = view.findViewById(R.id.btnSaveRefillThresholds);

        // Always-visible actions below the tabs
        btnEnableProvisioningAp = view.findViewById(R.id.btnEnableProvisioningAp);
        btnDisableDeveloperMode = view.findViewById(R.id.btnDisableDeveloperMode);

        configureAccessMode(view);

        Database_Helper helper = new Database_Helper();
        dbHelper = helper;
        String currentDeviceId = helper.getSelectedDeviceId();
        if (currentDeviceId == null && getContext() != null) {
            android.content.SharedPreferences prefs = getContext().getSharedPreferences("basilience_prefs", android.content.Context.MODE_PRIVATE);
            currentDeviceId = prefs.getString("selected_device_id", null);
        }

        if (currentDeviceId == null) {
            Toast.makeText(getContext(), "No device selected", Toast.LENGTH_SHORT).show();
            return;
        }
        helper.setSelectedDeviceId(currentDeviceId);

        String rtdbUrl = "https://basilience-database-default-rtdb.asia-southeast1.firebasedatabase.app";
        deviceRef = FirebaseDatabase.getInstance(rtdbUrl).getReference("devices/" + currentDeviceId);
        mockSensorsRef = deviceRef.child("commands/mockSensors");
        settingsRef = deviceRef.child("settings");
        sensorTestCommandRef = deviceRef.child("commands/sensorTest/enabled");
        sensorTestStatusRef = deviceRef.child("status/sensorTest");
        diagnosticSensorsRef = deviceRef.child("debug/physicalSensors");
        ignoreWaterLevelCommandRef = deviceRef.child("commands/ignoreWaterLevelAutomation/enabled");
        ignoreWaterLevelStatusRef = deviceRef.child("status/ignoreWaterLevelAutomation");
        automationTestModeCommandRef = deviceRef.child("commands/automationTestMode");
        automationTestModeStatusRef = deviceRef.child("status/automationTestMode");
        manualModeCommandRef = deviceRef.child("commands/manualMode");

        // Confirmed live bug: this used to run whenever the ACCOUNT had ever
        // been granted developer status, regardless of which mode this
        // particular screen visit is - so an Admin who also happens to be a
        // Developer Tester got this whole block's Firebase listeners wired
        // even while viewing Device Configuration (maintenanceMode), where
        // every view it touches is hidden (see configureAccessMode()) but
        // was never actually skipped underneath. That's exactly what made
        // "Enable Mock Sensors?" pop up unprompted every time Device
        // Configuration opened: loadCurrentValues() below (inside this
        // block) is asynchronous, and the maintenanceMode branch further
        // down set loadingMockState back to false immediately after kicking
        // it off, without waiting - so by the time the async read of the
        // device's actual (already-enabled, from earlier mock testing) mock
        // state came back and called switchMockEnable.setChecked(true), the
        // suppression flag meant to guard exactly that programmatic sync
        // had already been cleared, so the checked-change listener fired as
        // if a user had tapped it.
        if (isDeveloper && !maintenanceMode) {
            ArrayAdapter<String> automationModeAdapter = new ArrayAdapter<>(requireContext(),
                    android.R.layout.simple_spinner_item, AUTOMATION_TEST_MODE_LABELS);
            automationModeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinnerAutomationTestMode.setAdapter(automationModeAdapter);
            spinnerAutomationTestMode.setEnabled(false);
            spinnerAutomationTestMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View selectedView, int position, long id) {
                    if (suppressAutomationTestModeCallback || !automationTestModeStatusLoaded) return;
                    String requestedMode = AUTOMATION_TEST_MODE_VALUES[position];
                    if (requestedMode.equals(confirmedAutomationTestMode)) return;
                    setAutomationTestModeCommand(requestedMode);
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                    // The selector always has an explicit Off / Full System item.
                }
            });

            if (switchMockGrowLightTime != null) {
                switchMockGrowLightTime.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (suppressMockGrowLightTimeSwitchCallback || loadingMockGrowLightTimeState) return;
                    setMockGrowLightTimeEnabled(isChecked);
                    if (btnMockGrowLightTime != null) btnMockGrowLightTime.setEnabled(isChecked);
                });
            }
            if (btnMockGrowLightTime != null) {
                btnMockGrowLightTime.setOnClickListener(v -> showMockGrowLightTimePicker());
            }

            loadCurrentValues();
            loadMockGrowLightTime();
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
                            "Ignore Water Level Safety?",
                            "Developer testing only. Automatic refill and low-water actuator protection will be ignored while enabled. The real water level and alerts will remain active. Running pumps, fogger, or dosing actuators with insufficient water may damage equipment or produce invalid results.",
                            "Enable", "Cancel", () -> setIgnoreWaterLevelCommand(true));
                    return;
                }
                setIgnoreWaterLevelCommand(false);
            });
        }

        if (maintenanceMode) {
            loadingMockState = false;
            loadRefillThresholds();
        } else {
            loadCurrentValues();
            loadMockGrowLightTime();
        }
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
        switchDynamicMock.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (loadingMockState || maintenanceMode) return;
            pushMockValues();
        });

        switchIgnoreWaterLevel.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressIgnoreWaterLevelSwitchCallback || loadingIgnoreWaterLevelState) return;
            if (isChecked) {
                suppressIgnoreWaterLevelSwitchCallback = true;
                switchIgnoreWaterLevel.setChecked(false);
                suppressIgnoreWaterLevelSwitchCallback = false;
                NotificationHelper.showConfirmation(requireContext(),
                        "Ignore Water Level Safety?",
                        "Developer testing only. Automatic refill and low-water actuator protection will be ignored while enabled. The real water level and alerts will remain active. Running pumps, fogger, or dosing actuators with insufficient water may damage equipment or produce invalid results.",
                        "Enable", "Cancel", () -> setIgnoreWaterLevelCommand(true));
                return;
            }
            setIgnoreWaterLevelCommand(false);
        });

        // Setup filter button listeners
        btnFilterSensorTest.setOnClickListener(v -> updateFilterSelection("Sensor"));
        btnFilterMock.setOnClickListener(v -> updateFilterSelection("Mock"));
        btnFilterRefill.setOnClickListener(v -> updateFilterSelection("Refill"));
        if (btnFilterCanopyPwm != null) {
            btnFilterCanopyPwm.setOnClickListener(v -> updateFilterSelection("CanopyPwm"));
        }

        if (isDeveloper && !maintenanceMode) {
            if (btnCanopyPwm0 != null) btnCanopyPwm0.setOnClickListener(v -> sendCanopyPwmTest(0));
            if (btnCanopyPwm30 != null) btnCanopyPwm30.setOnClickListener(v -> sendCanopyPwmTest(30));
            if (btnCanopyPwm50 != null) btnCanopyPwm50.setOnClickListener(v -> sendCanopyPwmTest(50));
            if (btnCanopyPwm75 != null) btnCanopyPwm75.setOnClickListener(v -> sendCanopyPwmTest(75));
            if (btnCanopyPwm100 != null) btnCanopyPwm100.setOnClickListener(v -> sendCanopyPwmTest(100));
            btnPush.setOnClickListener(v -> pushMockValues());
        }
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

        if (maintenanceMode) {
            observeSensorTest();
            updateFilterSelection("Sensor");
        } else {
            observeIgnoreWaterLevelOverride();
            observeAutomationTestMode();
            observeManualModeForCanopyPwm();
            updateFilterSelection("Mock");
        }
    }

    private void configureAccessMode(View view) {
        TextView title = view.findViewById(R.id.tvToolsTitle);
        TextView subtitle = view.findViewById(R.id.tvToolsSubtitle);

        if (maintenanceMode) {
            if (title != null) title.setText("Device Configuration");
            if (subtitle != null) subtitle.setText("Safe device diagnostics and production settings.");
            btnFilterMock.setVisibility(View.GONE);
            btnFilterCanopyPwm.setVisibility(View.GONE);
            containerMockData.setVisibility(View.GONE);
            containerCanopyPwm.setVisibility(View.GONE);
            cardAutomationTestMode.setVisibility(View.GONE);
            containerIgnoreWaterLevel.setVisibility(View.GONE);
            btnEnableProvisioningAp.setVisibility(View.GONE);
            btnDisableDeveloperMode.setVisibility(View.GONE);
            if (rowTargetRangesLink != null) rowTargetRangesLink.setVisibility(View.GONE);
        } else {
            if (title != null) title.setText("Developer Options");
            if (subtitle != null) subtitle.setText("For IT experts and developers - testing and maintenance tools only.");
            btnFilterSensorTest.setVisibility(View.GONE);
            btnFilterRefill.setVisibility(View.GONE);
            containerSensorTest.setVisibility(View.GONE);
            containerRefill.setVisibility(View.GONE);
            // Moved here from Device Configuration by request - target
            // ranges belong with the other developer/testing tools now.
            // ParameterTargetRangesFragment's own canEdit bounce-back still
            // refuses anyone who isn't an Admin, so this doesn't loosen who
            // can actually change ranges, only where the entry point lives.
            if (rowTargetRangesLink != null) rowTargetRangesLink.setVisibility(View.VISIBLE);
        }
    }

    private void setAutomationTestModeCommand(String subsystem) {
        if (automationTestModeCommandRef == null) return;

        Map<String, Object> command = new HashMap<>();
        command.put("enabled", !"OFF".equals(subsystem));
        command.put("subsystem", subsystem);

        showLoading("Changing Automation Test Mode...", "Waiting for ESP32 acknowledgement...");
        automationTestModeCommandRef.updateChildren(command).addOnFailureListener(error -> {
            hideLoading();
            selectConfirmedAutomationTestMode();
            if (isAdded()) {
                NotificationHelper.showError(requireContext(),
                        "Automation Test Mode Failed", error.getMessage());
            }
        });
    }

    private void observeAutomationTestMode() {
        automationTestModeStatusListener = automationTestModeStatusRef.addValueEventListener(
                new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        if (!snapshot.hasChild("enabled") || !snapshot.hasChild("subsystem")) {
                            if (tvAutomationTestModeStatus != null) {
                                tvAutomationTestModeStatus.setText("Firmware confirmed: waiting for device");
                            }
                            return;
                        }
                        boolean enabled = Boolean.TRUE.equals(snapshot.child("enabled").getValue(Boolean.class));
                        String subsystem = snapshot.child("subsystem").getValue(String.class);
                        confirmedAutomationTestMode = enabled && automationTestModeIndex(subsystem) > 0
                                ? subsystem.toUpperCase(Locale.US) : "OFF";
                        automationTestModeStatusLoaded = true;
                        spinnerAutomationTestMode.setEnabled(true);
                        selectConfirmedAutomationTestMode();
                        if (containerMockGrowLightTime != null) {
                            containerMockGrowLightTime.setVisibility(
                                    "GROW_LIGHT".equals(confirmedAutomationTestMode)
                                            ? View.VISIBLE : View.GONE);
                        }
                        if (tvAutomationTestModeStatus != null) {
                            tvAutomationTestModeStatus.setText("Firmware confirmed: "
                                    + AUTOMATION_TEST_MODE_LABELS[automationTestModeIndex(confirmedAutomationTestMode)]);
                        }
                        hideLoading();
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        automationTestModeStatusLoaded = true;
                        hideLoading();
                    }
                });
    }

    private void selectConfirmedAutomationTestMode() {
        if (spinnerAutomationTestMode == null) return;
        suppressAutomationTestModeCallback = true;
        spinnerAutomationTestMode.setSelection(automationTestModeIndex(confirmedAutomationTestMode), false);
        suppressAutomationTestModeCallback = false;
    }

    private int automationTestModeIndex(String subsystem) {
        if (subsystem == null) return 0;
        for (int i = 0; i < AUTOMATION_TEST_MODE_VALUES.length; i++) {
            if (AUTOMATION_TEST_MODE_VALUES[i].equalsIgnoreCase(subsystem)) return i;
        }
        return 0;
    }

    // Reads the currently-stored mock time so the switch/button reflect real
    // device-bound state on load, rather than resetting to a default every
    // time this screen opens. A single read, matching loadRefillThresholds()'s
    // own pattern - not a live listener, since this value only ever changes
    // from this same screen.
    private void loadMockGrowLightTime() {
        if (automationTestModeCommandRef == null) return;
        automationTestModeCommandRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!isAdded()) return;
                Boolean enabled = snapshot.child("mockGrowLightTimeEnabled").getValue(Boolean.class);
                Long minutes = snapshot.child("mockGrowLightMinutes").getValue(Long.class);
                if (minutes != null) {
                    mockGrowLightMinutes = (int) Math.max(0, Math.min(1439, minutes));
                }
                loadingMockGrowLightTimeState = false;
                suppressMockGrowLightTimeSwitchCallback = true;
                if (switchMockGrowLightTime != null) {
                    switchMockGrowLightTime.setChecked(Boolean.TRUE.equals(enabled));
                }
                suppressMockGrowLightTimeSwitchCallback = false;
                if (btnMockGrowLightTime != null) {
                    btnMockGrowLightTime.setEnabled(Boolean.TRUE.equals(enabled));
                    btnMockGrowLightTime.setText(formatMockGrowLightTime());
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                loadingMockGrowLightTimeState = false;
            }
        });
    }

    private String formatMockGrowLightTime() {
        int hour = mockGrowLightMinutes / 60;
        int minute = mockGrowLightMinutes % 60;
        return String.format(Locale.US, "Test Time: %02d:%02d", hour, minute);
    }

    // Standard Android TimePickerDialog (24-hour, to match the firmware's
    // HH:MM schedule fields directly with no AM/PM conversion). The picked
    // HH:MM converts to minutes-since-midnight before writing to Firebase -
    // see mockGrowLightMinutes' own comment on why that representation was
    // chosen (it matches getCurrentMinutes()'s internal representation
    // exactly, so the firmware does no conversion either).
    private void showMockGrowLightTimePicker() {
        int hour = mockGrowLightMinutes / 60;
        int minute = mockGrowLightMinutes % 60;
        new TimePickerDialog(requireContext(), (picker, selectedHour, selectedMinute) -> {
            mockGrowLightMinutes = selectedHour * 60 + selectedMinute;
            if (btnMockGrowLightTime != null) {
                btnMockGrowLightTime.setText(formatMockGrowLightTime());
            }
            setMockGrowLightMinutes(mockGrowLightMinutes);
        }, hour, minute, true).show();
    }

    // Writes only to commands/automationTestMode/mockGrowLightTimeEnabled -
    // the real clock/RTC and every other Firebase path are untouched. The
    // firmware only ever consults this while automationTestSubsystem ==
    // GROW_LIGHT (see AutomationManager::growLightMockTimeActive()), so
    // leaving it enabled after switching test modes away is safe by design;
    // no cleanup write is required here.
    private void setMockGrowLightTimeEnabled(boolean enabled) {
        if (automationTestModeCommandRef == null) return;
        automationTestModeCommandRef.child("mockGrowLightTimeEnabled").setValue(enabled);
    }

    // Writes only to commands/automationTestMode/mockGrowLightMinutes -
    // never to settings/ (the real lightOnHour/lightOnMinute/lightOffHour/
    // lightOffMinute schedule) and never to any RTC/time path.
    private void setMockGrowLightMinutes(int minutes) {
        if (automationTestModeCommandRef == null) return;
        automationTestModeCommandRef.child("mockGrowLightMinutes").setValue(minutes);
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
     * The shared layout has two authorized presentations: Device Maintenance
     * permits only Sensor Test/Refill, while Developer Options permits only
     * Mock Data/Canopy PWM. Wi-Fi Configuration remains in Device Management.
     */
    private void updateFilterSelection(String selectedFilter) {
        if (maintenanceMode && !"Sensor".equalsIgnoreCase(selectedFilter)
                && !"Refill".equalsIgnoreCase(selectedFilter)) return;
        if (!maintenanceMode && ("Sensor".equalsIgnoreCase(selectedFilter)
                || "Refill".equalsIgnoreCase(selectedFilter))) return;
        boolean sensor = "Sensor".equalsIgnoreCase(selectedFilter);
        boolean mock = "Mock".equalsIgnoreCase(selectedFilter);
        boolean refill = "Refill".equalsIgnoreCase(selectedFilter);
        boolean canopyPwm = "CanopyPwm".equalsIgnoreCase(selectedFilter);

        btnFilterSensorTest.setSelected(sensor);
        btnFilterMock.setSelected(mock);
        btnFilterRefill.setSelected(refill);
        if (btnFilterCanopyPwm != null) btnFilterCanopyPwm.setSelected(canopyPwm);

        containerSensorTest.setVisibility(sensor ? View.VISIBLE : View.GONE);
        containerMockData.setVisibility(mock ? View.VISIBLE : View.GONE);
        containerRefill.setVisibility(refill ? View.VISIBLE : View.GONE);
        if (containerCanopyPwm != null) containerCanopyPwm.setVisibility(canopyPwm ? View.VISIBLE : View.GONE);
    }

    /**
     * Read-only observation of commands/manualMode - this screen never
     * writes that flag itself (Manual Mode is owned by
     * Parameters_Monitoring_Fragment's own switch); it only gates whether
     * the Canopy PWM test buttons are allowed to send a command, since
     * Database_Helper.updateActuatorState() rejects manual actuator writes
     * outright when manual mode is off (see its own Javadoc).
     */
    private void observeManualModeForCanopyPwm() {
        if (manualModeCommandRef == null) return;
        manualModeStatusListener = manualModeCommandRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean enabled = snapshot.getValue(Boolean.class);
                canopyManualModeOn = Boolean.TRUE.equals(enabled);
                renderCanopyPwmManualModeState();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                canopyManualModeOn = false;
                renderCanopyPwmManualModeState();
            }
        });
    }

    private void renderCanopyPwmManualModeState() {
        if (tvCanopyPwmManualModeWarning != null) {
            tvCanopyPwmManualModeWarning.setVisibility(canopyManualModeOn ? View.GONE : View.VISIBLE);
        }
        boolean enable = canopyManualModeOn;
        if (btnCanopyPwm0 != null) btnCanopyPwm0.setEnabled(enable);
        if (btnCanopyPwm30 != null) btnCanopyPwm30.setEnabled(enable);
        if (btnCanopyPwm50 != null) btnCanopyPwm50.setEnabled(enable);
        if (btnCanopyPwm75 != null) btnCanopyPwm75.setEnabled(enable);
        if (btnCanopyPwm100 != null) btnCanopyPwm100.setEnabled(enable);
    }

    /**
     * Sends a Canopy Fan-only manual command at a fixed test percentage,
     * reusing the same commands/canopyFan path and manual-ownership
     * arbitration as the Monitoring screen's own actuator controls - this
     * does not invoke automation, does not require DHT (no sensor validity
     * check gates a manual command), and touches no other actuator. The
     * firmware's own change-detection logging (ActuatorManager.cpp) prints
     * "[CANOPY-PWM] requested=X% duty=<duty>/<max duty>" to Serial whenever
     * the commanded percentage changes - that is the only place the actual
     * PWM duty value can be confirmed, since there is no RTDB duty field and
     * no tachometer feedback to report real RPM.
     */
    private void sendCanopyPwmTest(int percent) {
        if (dbHelper == null) return;
        if (!canopyManualModeOn) {
            Toast.makeText(getContext(), "Enable Manual Mode on the Monitoring screen first", Toast.LENGTH_SHORT).show();
            return;
        }

        // Defensive re-resolution: onViewCreated() sets dbHelper's device ID
        // exactly once. If that happened to run before selected_device_id
        // was actually available (a real observed failure mode - "No device
        // selected" on every attempt to control the fan, for the rest of
        // this screen's life), dbHelper's device stayed permanently unset
        // with no way to recover short of leaving and re-entering the
        // screen. Re-check and self-heal here instead, the same defensive
        // pattern Parameters_Monitoring_Fragment already uses for its own
        // connectivity listener.
        if (dbHelper.getSelectedDeviceId() == null && getContext() != null) {
            String retryDeviceId = getContext()
                    .getSharedPreferences("basilience_prefs", android.content.Context.MODE_PRIVATE)
                    .getString("selected_device_id", null);
            if (retryDeviceId != null) dbHelper.setSelectedDeviceId(retryDeviceId);
        }

        canopyPwmTestActive = percent > 0;
        showLoading("Canopy PWM Test...", "Commanding Canopy Fan to " + percent + "%...");
        dbHelper.updateActuatorState("canopyFan", percent > 0, false, percent)
                .addOnSuccessListener(unused -> {
                    hideLoading();
                    if (!isAdded() || tvCanopyPwmStatus == null) return;
                    tvCanopyPwmStatus.setText("Commanded canopyFan=" + percent
                            + "%. Check the ESP32 Serial log for the matching [CANOPY-PWM] requested="
                            + percent + "% duty=... line to confirm the PWM output.");
                })
                .addOnFailureListener(error -> {
                    hideLoading();
                    if (isAdded()) {
                        NotificationHelper.showError(requireContext(), "Canopy PWM Test Failed", error.getMessage());
                    }
                });
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
                renderDiagnostic(tvDiagnosticEcVoltage, "EC Voltage", snapshot.child("ecVoltage").getValue(), " V");
                renderDiagnostic(tvDiagnosticAirTemperature, "Air Temperature", snapshot.child("airTemperature").getValue(), " °C");
                renderDiagnostic(tvDiagnosticHumidity, "Humidity", snapshot.child("humidity").getValue(), " %");
                renderDiagnostic(tvDiagnosticWaterTemperature, "Water Temperature",
                        snapshot.child("waterTemperature").getValue(), " °C", "Unavailable");
                renderDiagnostic(tvDiagnosticWaterLevel, "Water Level", snapshot.child("waterLevel").getValue(), " %");
                renderDiagnostic(tvDiagnosticWaterLevelDistance, "Water Level Distance", snapshot.child("waterLevelDistanceCm").getValue(), " cm");
                renderDiagnostic(tvDiagnosticWaterDepth, "Water Depth", snapshot.child("waterLevelCm").getValue(), " cm");
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
                renderIgnoreWaterLevelState(Boolean.TRUE.equals(enabled));
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                loadingIgnoreWaterLevelState = false;
                hideLoading();
            }
        });
    }

    /**
     * Firmware-confirmed state only (mirrors observeIgnoreWaterLevelOverride's
     * own rule: only the RTDB echo drives the switch) - makes it obvious on
     * the Developer Options screen itself, not just via the confirmation
     * dialog, that low-water actuator protection is currently suppressed
     * device-wide. Hidden entirely while off so the rest of the screen is
     * unchanged.
     */
    private void renderIgnoreWaterLevelState(boolean enabled) {
        if (tvIgnoreWaterLevelIndicator == null) return;
        tvIgnoreWaterLevelIndicator.setVisibility(enabled ? View.VISIBLE : View.GONE);
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
        renderDiagnostic(tvDiagnosticEcVoltage, "EC Voltage", null, " V");
        renderDiagnostic(tvDiagnosticAirTemperature, "Air Temperature", null, " °C");
        renderDiagnostic(tvDiagnosticHumidity, "Humidity", null, " %");
        renderDiagnostic(tvDiagnosticWaterTemperature, "Water Temperature", null, " °C", "Unavailable");
        renderDiagnostic(tvDiagnosticWaterLevel, "Water Level", null, " %");
        renderDiagnostic(tvDiagnosticWaterLevelDistance, "Water Level Distance", null, " cm");
        renderDiagnostic(tvDiagnosticWaterDepth, "Water Depth", null, " cm");
    }

    private void loadCurrentValues() {
        mockSensorsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    Boolean enabled = snapshot.child("enabled").getValue(Boolean.class);
                    if (enabled != null) switchMockEnable.setChecked(enabled);
                    Boolean dynamic = snapshot.child("dynamic").getValue(Boolean.class);
                    switchDynamicMock.setChecked(Boolean.TRUE.equals(dynamic));
                    
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

                    Double waterLevelCm = snapshot.child("waterLevelCm").getValue(Double.class);
                    if (waterLevelCm != null) etWaterLevelCm.setText(String.valueOf(waterLevelCm));
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
     * that class's own comment). Water-depth model (centimeters, see
     * firmware Config.h's "Water Reservoir Geometry") - this is the
     * AUTHORITATIVE refill control threshold; the legacy percentage-based
     * refillStartLevel/refillStopLevel fields are no longer read by any
     * firmware control path. A field missing from Firebase falls back to
     * the same defaults firmware itself compiles with (Config.h's
     * REFILL_START_CM/REFILL_STOP_CM) rather than showing a misleading 0.
     */
    private void loadRefillThresholds() {
        if (settingsRef == null) return;
        settingsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!isAdded()) return;
                Double start = snapshot.child("refillStartLevelCm").getValue(Double.class);
                Double stop = snapshot.child("refillStopLevelCm").getValue(Double.class);
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

    // Working depth is 0..6cm (MAX_WORKING_WATER_CM) - see firmware
    // Config.h's "Water Reservoir Geometry". The physical container height
    // (~29cm) is never a valid bound here.
    private static final float REFILL_THRESHOLD_MAX_CM = 6.0f;

    private void saveRefillThresholds() {
        if (settingsRef == null || etRefillStart == null || etRefillStop == null) return;

        if (layoutRefillStart != null) layoutRefillStart.setError(null);
        if (layoutRefillStop != null) layoutRefillStop.setError(null);

        Float start = parsePositiveFloat(etRefillStart);
        Float stop = parsePositiveFloat(etRefillStop);
        boolean valid = true;

        if (start == null || start < 0f || start > REFILL_THRESHOLD_MAX_CM) {
            if (layoutRefillStart != null) layoutRefillStart.setError("Enter a value between 0 and " + REFILL_THRESHOLD_MAX_CM + " cm");
            valid = false;
        }
        if (stop == null || stop < 0f || stop > REFILL_THRESHOLD_MAX_CM) {
            if (layoutRefillStop != null) layoutRefillStop.setError("Enter a value between 0 and " + REFILL_THRESHOLD_MAX_CM + " cm");
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
        updates.put("refillStartLevelCm", (double) finalStart);
        updates.put("refillStopLevelCm", (double) finalStop);

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
        updates.put("dynamic", switchDynamicMock.isChecked());
        
        try {
            if (!etPh.getText().toString().isEmpty()) updates.put("ph", Double.parseDouble(etPh.getText().toString()));
            if (!etEc.getText().toString().isEmpty()) updates.put("ec", Double.parseDouble(etEc.getText().toString()));
            if (!etTemp.getText().toString().isEmpty()) updates.put("airTemperature", Double.parseDouble(etTemp.getText().toString()));
            if (!etHumidity.getText().toString().isEmpty()) updates.put("humidity", Double.parseDouble(etHumidity.getText().toString()));
            if (!etWaterTemperature.getText().toString().isEmpty()) updates.put("waterTemperature", Double.parseDouble(etWaterTemperature.getText().toString()));
            if (!etWaterLevel.getText().toString().isEmpty()) updates.put("waterLevel", Double.parseDouble(etWaterLevel.getText().toString()));
            // Optional explicit override - firmware's readMockSensors() prefers this
            // over deriving depth from the percentage above when both are present.
            // updateChildren() merges rather than replaces, so an empty field must
            // explicitly null out any override left over from a previous push -
            // otherwise a stale cm value would keep silently overriding the percent
            // field with no way to tell from this screen that it was still active.
            updates.put("waterLevelCm", etWaterLevelCm.getText().toString().isEmpty()
                    ? null : Double.parseDouble(etWaterLevelCm.getText().toString()));

            showLoading("Pushing Mock Data...", "Writing values to ESP32...");

            mockSensorsRef.updateChildren(updates).addOnSuccessListener(unused ->
                    waitForMockAcknowledgement(
                            switchMockEnable.isChecked(), switchDynamicMock.isChecked()))
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
                .addOnSuccessListener(unused -> waitForMockAcknowledgement(false, false))
                .addOnFailureListener(error -> {
                    hideLoading();
                    if (isAdded()) NotificationHelper.showError(requireContext(), "Mock Data Failed", error.getMessage());
                });
    }

    private void waitForMockAcknowledgement(boolean expectedEnabled, boolean expectedDynamic) {
        DatabaseReference ackRef = deviceRef.child("status");
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
                Boolean acknowledged = snapshot.child("mockData").getValue(Boolean.class);
                Boolean dynamicAcknowledged = snapshot.child("mockDataDynamic").getValue(Boolean.class);
                if (acknowledged == null || acknowledged != expectedEnabled) return;
                if (expectedEnabled
                        && Boolean.TRUE.equals(dynamicAcknowledged) != expectedDynamic) return;
                ackRef.removeEventListener(this);
                mainHandler.removeCallbacks(timeout);
                hideLoading();
                if (!isAdded()) return;
                if (expectedEnabled) {
                    NotificationHelper.showSuccess(requireContext(), expectedDynamic
                            ? "ESP32 Dynamic Mock is active and automation is using the drifting effective values."
                            : "ESP32 static mock mode is active and automation is using the supplied sensor values.");
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
                .remove(RoleConstants.PREF_DEVELOPER_MODE_DEVICE_ID)
                .apply();
        if (settingsRef != null) settingsRef.child("devModeEnabled").setValue(false);
        // Best-effort safety net: a developer bypassing the water-level
        // automation gate must not leave it silently enabled once they've
        // exited Developer Mode. Fire-and-forget, matching devModeEnabled
        // above - this screen is being torn down either way.
        if (ignoreWaterLevelCommandRef != null) ignoreWaterLevelCommandRef.setValue(false);
        if (automationTestModeCommandRef != null) {
            Map<String, Object> off = new HashMap<>();
            off.put("enabled", false);
            off.put("subsystem", "OFF");
            automationTestModeCommandRef.updateChildren(off);
        }
        Toast.makeText(getContext(), "Developer Mode disabled", Toast.LENGTH_SHORT).show();
        navController.popBackStack();
    }

    @Override
    public void onDestroyView() {
        if (canopyPwmTestActive && dbHelper != null && canopyManualModeOn) {
            // Restore safe/off state on exit - a non-zero PWM test command is
            // never left standing after this screen is torn down.
            dbHelper.updateActuatorState("canopyFan", false, false, 0);
            canopyPwmTestActive = false;
        }
        if (manualModeCommandRef != null && manualModeStatusListener != null) {
            manualModeCommandRef.removeEventListener(manualModeStatusListener);
        }
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
        if (automationTestModeStatusRef != null && automationTestModeStatusListener != null) {
            automationTestModeStatusRef.removeEventListener(automationTestModeStatusListener);
        }
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroyView();
    }
}
