package com.example.basilience;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import androidx.core.content.ContextCompat;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;
import com.example.basilience.repository.SensorRepository;
import com.example.basilience.models.SensorData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;


public class Parameters_Monitoring_Fragment extends Fragment {

    public Parameters_Monitoring_Fragment() {
        // Required empty public constructor
    }

    private Database_Helper dbHelper;

    private boolean isDialogShowing = false;
    private boolean isManualMode = false;
    private boolean isActuatorBusy = false; // Prevents double-tap while popup is showing
    private boolean isReservoirLocked = false; // Tracks if an automatic operation is in progress
    private boolean isSafetyLock = false;
    private static final long ACTUATOR_INACTIVITY_TIMEOUT_MS = 12000L;
    private static final long ACTUATOR_POLL_INTERVAL_MS = 500L;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int actuatorCommandGeneration = 0;
    private boolean actuatorCommandFinished = true;
    private Runnable actuatorCommandRunnable;

    // Loading overlay views
    private View actuatorLoadingOverlay;
    // Set only on the show-transition (hidden -> visible), not on every
    // in-progress message update, so the minimum-visible-duration guard in
    // hideActuatorLoading() measures from when the overlay actually appeared.
    private long actuatorLoadingShownAt;
    private TextView tvActuatorLoadingTitle;
    private TextView tvActuatorLoadingStatus;


    // ===== MAIN =====
    private TextView tvPH, tvEC, tvTemp, tvHumidity;
    private TextView tvWaterTemp, tvWaterLevel;
    private TextView tvPHStatus, tvECStatus, tvTempStatus, tvHumidityStatus;
    private TextView tvWaterTempStatus, tvWaterLevelStatus;
    private boolean phAlertActive;
    private boolean ecAlertActive;
    private boolean airTemperatureAlertActive;
    private boolean waterTemperatureAlertActive;
    private boolean waterLevelAlertActive;
    private boolean humidityAlertActive;

    // Direction of the current excursion per parameter, so Monitoring can show
    // Below Range / Above Range instead of a single undirected warning.
    private boolean phBelowRange, phAboveRange;
    private boolean ecBelowRange, ecAboveRange;
    private boolean airTempBelowRange, airTempAboveRange;
    private boolean humidityBelowRange, humidityAboveRange;
    private boolean waterTempBelowRange, waterTempAboveRange;
    private boolean waterLevelBelowRange, waterLevelAboveRange;

    // Directional firmware alert flags, kept alongside the combined flags above
    // (which drive reading colour only) so a manual action can tell which way
    // the parameter is actually off target.
    private final ManualOverrideAdvisor.AlertFlags overrideFlags = new ManualOverrideAdvisor.AlertFlags();
    private boolean alertsLoaded = false;
    /** Configured cooling ceiling from settings/highWaterTemp; null when not configured. */
    private Double configuredHighWaterTemp = null;
    /** status/currentMode, phDirection, ecDirection and the subsystem/global locks - all from the same statusListener below. */
    private final ManualOverrideAdvisor.OperationContext operationContext = new ManualOverrideAdvisor.OperationContext();
    private DatabaseReference highWaterTempRef;
    private ValueEventListener highWaterTempListener;

    // ===== ACTUATORS =====
    class Actuator {
        String name;
        String dbKey;
        int state;
        boolean physicalRunning;
        String physicalSource;
        String strategy;
        boolean manualIntent;
        String reason;
        // Mirrors actuatorStatus/{key}/overrideActive - true only while this
        // actuator is running under a confirmed manual override (see
        // ManualOverrideAdvisor / ActuatorManager::validateCommand).
        boolean overrideActive;

        Actuator(String name, String dbKey) {
            this.name = name;
            this.dbKey = dbKey;
            this.state = 0;
            this.physicalRunning = false;
            this.physicalSource = "";
            this.strategy = "";
            this.manualIntent = false;
        }
    }

    private DatabaseReference alertsRef;
    private ValueEventListener alertsListener;
    private DatabaseReference statusRef;
    private ValueEventListener statusListener;
    private DatabaseReference manualModeRef;
    private ValueEventListener manualModeListener;
    private DatabaseReference actuatorStatusRef;
    private ValueEventListener actuatorStatusListener;

    private static final long SETUP_AP_RECHECK_INTERVAL_MS = 15_000L;
    private TextView tvConnectionStatus;
    private TextView tvConnectionDetail;
    private MaterialButton btnRetryWifiConfiguration;
    private SensorRepository sensorRepository;
    private final MutableLiveData<SensorData> sensorLiveData = new MutableLiveData<>();
    private final MutableLiveData<Boolean> sensorReadErrorLiveData = new MutableLiveData<>();
    private TextView tvSensorDataNotice;
    private View layoutCultivationPaused;
    private com.google.firebase.firestore.ListenerRegistration cycleListener;
    private Long previousLastSeenValue = null;
    private boolean isCurrentlyOnline = false;
    private DeviceConnectivityState connectivityState = DeviceConnectivityState.RECONNECTING;
    private boolean setupApReachable = false;
    private boolean setupApCheckInProgress = false;
    private String selectedDeviceId;
    private ExecutorService connectivityExecutor;
    private final Runnable setupApRecheck = () -> {
        if (!isAdded() || isCurrentlyOnline || selectedDeviceId == null) return;
        confirmSetupApReachability(selectedDeviceId);
    };

    private final Actuator waterPumpValve = new Actuator("Water Pump (Valve)", "solenoid");
    private final Actuator canopyFan = new Actuator("Canopy Fan", "canopyFan");
    private final Actuator growLights = new Actuator("Grow Lights", "growLight");
    private final Actuator phUp = new Actuator("pH Up", "phUpPump");
    private final Actuator phDown = new Actuator("pH Down", "phDownPump");
    private final Actuator nutrients = new Actuator("Nutrients (EC)", "growPump");
    // bloomPump is paired with growPump — not shown as a separate card, tracked for combined state
    private final Actuator bloomPump = new Actuator("Bloom Pump", "bloomPump");
    private final Actuator fogger = new Actuator("Fogger", "fogger");
    private final Actuator reservoirFan = new Actuator("Reservoir Fan (Blower)", "blower");
    private final Actuator peltier = new Actuator("Peltier (Temp)", "peltier");
    private final Actuator circulationPump = new Actuator("Circulation Pump", "circulationPump");

    private View actWaterPumpValve, actCanopyFan, actGrowLights, actPhUp, actPhDown, actNutrients, actFogger, actReservoirFan, actPeltier, actCirculationPump;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.parameters_monitoring, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        dbHelper = new Database_Helper();

        // Back button
        View back = view.findViewById(R.id.btnBack);
        if (back != null) {
            back.setVisibility(View.VISIBLE);
            back.setOnClickListener(v -> Navigation.findNavController(view).popBackStack());
        }

        // Initialize Views
        setupParameterCards(view);
        updateSensorUI();

        // ===== MODE SWITCH =====
        SwitchMaterial modeSwitch = view.findViewById(R.id.switchMode);
        if (modeSwitch != null) {
            modeSwitch.setEnabled(false); // disabled until RTDB snapshot arrives and re-attaches listener
            modeSwitch.setAlpha(0.5f);
            modeSwitch.setOnCheckedChangeListener(null); // listener set inside RTDB onDataChange only
        }

        // ===== ACTUATORS =====
        actWaterPumpValve = view.findViewById(R.id.actWaterPumpValve);
        actCanopyFan = view.findViewById(R.id.actCanopyFan);
        actGrowLights = view.findViewById(R.id.actGrowLights);
        actPhUp = view.findViewById(R.id.actPhUp);
        actPhDown = view.findViewById(R.id.actPhDown);
        actNutrients = view.findViewById(R.id.actNutrients);
        actFogger = view.findViewById(R.id.actFogger);
        actReservoirFan = view.findViewById(R.id.actReservoirFan);
        actPeltier = view.findViewById(R.id.actPeltier);
        actCirculationPump = view.findViewById(R.id.actCirculationPump);

        if (actWaterPumpValve != null) setupActuatorUI(actWaterPumpValve, waterPumpValve);
        if (actCanopyFan != null) setupActuatorUI(actCanopyFan, canopyFan);
        if (actGrowLights != null) setupActuatorUI(actGrowLights, growLights);
        if (actPhUp != null) setupActuatorUI(actPhUp, phUp);
        if (actPhDown != null) setupActuatorUI(actPhDown, phDown);
        if (actNutrients != null) setupActuatorUI(actNutrients, nutrients);
        if (actFogger != null) setupActuatorUI(actFogger, fogger);
        if (actReservoirFan != null) setupActuatorUI(actReservoirFan, reservoirFan);
        if (actPeltier != null) setupActuatorUI(actPeltier, peltier);
        if (actCirculationPump != null) setupActuatorUI(actCirculationPump, circulationPump);

        updateActuatorControls();

        // Bind loading overlay views
        actuatorLoadingOverlay = view.findViewById(R.id.actuatorLoadingOverlay);
        tvActuatorLoadingTitle = view.findViewById(R.id.tvActuatorLoadingTitle);
        tvActuatorLoadingStatus = view.findViewById(R.id.tvActuatorLoadingStatus);

        tvConnectionStatus = view.findViewById(R.id.tvConnectionStatus);
        tvConnectionDetail = view.findViewById(R.id.tvConnectionDetail);
        tvSensorDataNotice = view.findViewById(R.id.tvSensorDataNotice);
        layoutCultivationPaused = view.findViewById(R.id.layoutCultivationPaused);
        observeCultivationState();
        btnRetryWifiConfiguration = view.findViewById(R.id.btnRetryWifiConfiguration);
        connectivityExecutor = Executors.newSingleThreadExecutor();
        if (btnRetryWifiConfiguration != null) {
            btnRetryWifiConfiguration.setOnClickListener(v -> {
                androidx.navigation.NavController controller = Navigation.findNavController(v);
                if (controller.getCurrentDestination() != null
                        && controller.getCurrentDestination().getId() != R.id.wifiConfigFragment) {
                    controller.navigate(R.id.wifiConfigFragment, null,
                            new androidx.navigation.NavOptions.Builder().setLaunchSingleTop(true).build());
                }
            });
        }
        sensorRepository = new SensorRepository();

        DeviceConnectionManager.getInstance().getConnectivityState().observe(
                getViewLifecycleOwner(), state -> {
                    connectivityState = state == null
                            ? DeviceConnectivityState.RECONNECTING : state;
                    isCurrentlyOnline = connectivityState == DeviceConnectivityState.ONLINE;
                    if (isCurrentlyOnline) {
                        mainHandler.removeCallbacks(setupApRecheck);
                        setupApReachable = false;
                        if (selectedDeviceId != null) {
                            NotificationHelper.clearWifiConfigurationRequiredNotification(
                                    requireContext(), selectedDeviceId);
                        }
                    } else if (selectedDeviceId != null) {
                        confirmSetupApReachability(selectedDeviceId);
                    }
                    updateConnectionUI();
                });

        sensorLiveData.observe(getViewLifecycleOwner(), new Observer<SensorData>() {
            @Override
            public void onChanged(SensorData sensorData) {
                updateSensorUI();
            }
        });

        sensorReadErrorLiveData.observe(getViewLifecycleOwner(), hasError -> {
            if (tvSensorDataNotice != null) {
                tvSensorDataNotice.setVisibility(Boolean.TRUE.equals(hasError) ? View.VISIBLE : View.GONE);
            }
        });

        SharedPreferences localPrefs = requireContext().getSharedPreferences("basilience_prefs", android.content.Context.MODE_PRIVATE);
        String role = localPrefs.getString("user_role", "FARMER");
        boolean isAdmin = "ADMIN".equalsIgnoreCase(role);

        View btnTriggerRefill = view.findViewById(R.id.btnTriggerRefill);
        if (btnTriggerRefill != null) {
            if (!isAdmin) {
                btnTriggerRefill.setVisibility(View.GONE);
            }
            btnTriggerRefill.setEnabled(isAdmin);
            btnTriggerRefill.setAlpha(isAdmin ? 1.0f : 0.6f);
            btnTriggerRefill.setOnClickListener(v -> {
                if (isActuatorBusy) return;
                if (isCurrentlyOnline) {
                    // The existing "are you sure" prompt stays as the baseline
                    // guard. When the reservoir does not actually need water -
                    // or when Basilience cannot tell - the prompt says so
                    // instead, using the same advisor as the actuator toggles.
                    ManualOverrideAdvisor.Advice refillAdvice = ManualOverrideAdvisor.evaluate(
                            ManualOverrideAdvisor.Condition.WATER_LEVEL_FILL,
                            sensorLiveData.getValue(),
                            alertsLoaded ? overrideFlags : null, configuredHighWaterTemp);

                    String refillTitle = refillAdvice == ManualOverrideAdvisor.Advice.PROCEED
                            ? "Start Reservoir Refill"
                            : ManualOverrideAdvisor.CONFIRM_TITLE;
                    String refillMessage = refillAdvice == ManualOverrideAdvisor.Advice.PROCEED
                            ? "Are you sure you want to start an automated reservoir refill operation?"
                            : ManualOverrideAdvisor.messageFor(
                                    ManualOverrideAdvisor.Condition.WATER_LEVEL_FILL, refillAdvice);

                    NotificationHelper.showConfirmation(requireContext(),
                            refillTitle, refillMessage,
                            "Continue", "Cancel", () -> {
                                isActuatorBusy = true;
                                updateActuatorControls();
                                showActuatorLoading("Sending request...", "");
                                dbHelper.sendOperationRequest("REFILL", "START")
                                        .addOnSuccessListener(requestId -> {
                                            pollOperationUntilDone(requestId, "Refill", 0);
                                        })
                                        .addOnFailureListener(e -> {
                                            hideActuatorLoading();
                                            isActuatorBusy = false;
                                            updateActuatorControls();
                                            Log.e("Monitoring", "Refill request failed", e);
                                            Toast.makeText(getContext(), "Unable to send the refill request. Please try again.", Toast.LENGTH_SHORT).show();
                                        });
                            });
                } else {
                    NotificationHelper.showError(requireContext(), "Device Offline",
                            "The Basilience device is not currently connected.");
                }
            });
        }

        View btnResetSafety = view.findViewById(R.id.btnResetSafety);
        if (btnResetSafety != null) {
            if (!isAdmin) {
                btnResetSafety.setVisibility(View.GONE);
            }
            btnResetSafety.setEnabled(isAdmin);
            btnResetSafety.setAlpha(isAdmin ? 1.0f : 0.6f);
            btnResetSafety.setOnClickListener(v -> {
                if (isActuatorBusy) return;
                if (isCurrentlyOnline) {
                    NotificationHelper.showConfirmation(requireContext(),
                            "Reset Safety Lock",
                            "Are you sure you want to reset the FSM safety lock? This will return the system to normal operations.",
                            "Yes", "No", () -> {
                                isActuatorBusy = true;
                                updateActuatorControls();
                                showActuatorLoading("Sending request...", "");
                                dbHelper.sendOperationRequest("RESET_SAFETY", "START")
                                        .addOnSuccessListener(requestId -> {
                                            pollOperationUntilDone(requestId, "Reset Safety", 0);
                                        })
                                        .addOnFailureListener(e -> {
                                            hideActuatorLoading();
                                            isActuatorBusy = false;
                                            updateActuatorControls();
                                            Log.e("Monitoring", "Reset safety request failed", e);
                                            Toast.makeText(getContext(), "Unable to send the reset request. Please try again.", Toast.LENGTH_SHORT).show();
                                        });
                            });
                } else {
                    NotificationHelper.showError(requireContext(), "Device Offline",
                            "The Basilience device is not currently connected.");
                }
            });
        }

        startRealTimeMonitoring();
    }

    private void setupParameterCards(View view) {
        View cardPH = view.findViewById(R.id.cardPH);
        View cardEC = view.findViewById(R.id.cardEC);
        View cardTemp = view.findViewById(R.id.cardTemp);
        View cardHumidity = view.findViewById(R.id.cardHumidity);
        View cardWaterTemp = view.findViewById(R.id.cardWaterTemp);
        View cardWaterLevel = view.findViewById(R.id.cardWaterLevel);

        if (cardPH != null) {
            tvPH = cardPH.findViewById(R.id.tvValue);
            tvPHStatus = cardPH.findViewById(R.id.tvStatus);
            TextView label = cardPH.findViewById(R.id.tvLabel);
            if (label != null) label.setText("pH");
            ImageView icon = cardPH.findViewById(R.id.imgIcon);
            if (icon != null) icon.setImageResource(R.drawable.ic_ph);
        }
        if (cardEC != null) {
            tvEC = cardEC.findViewById(R.id.tvValue);
            tvECStatus = cardEC.findViewById(R.id.tvStatus);
            TextView label = cardEC.findViewById(R.id.tvLabel);
            if (label != null) label.setText("EC");
            ImageView icon = cardEC.findViewById(R.id.imgIcon);
            if (icon != null) icon.setImageResource(R.drawable.ic_ec);
        }
        if (cardTemp != null) {
            tvTemp = cardTemp.findViewById(R.id.tvValue);
            tvTempStatus = cardTemp.findViewById(R.id.tvStatus);
            TextView label = cardTemp.findViewById(R.id.tvLabel);
            if (label != null) label.setText("Air Temp");
            ImageView icon = cardTemp.findViewById(R.id.imgIcon);
            if (icon != null) icon.setImageResource(R.drawable.ic_temp);
        }
        if (cardHumidity != null) {
            tvHumidity = cardHumidity.findViewById(R.id.tvValue);
            tvHumidityStatus = cardHumidity.findViewById(R.id.tvStatus);
            TextView label = cardHumidity.findViewById(R.id.tvLabel);
            if (label != null) label.setText("Humidity");
            ImageView icon = cardHumidity.findViewById(R.id.imgIcon);
            if (icon != null) icon.setImageResource(R.drawable.ic_humidity);
        }
        if (cardWaterTemp != null) {
            tvWaterTemp = cardWaterTemp.findViewById(R.id.tvValue);
            tvWaterTempStatus = cardWaterTemp.findViewById(R.id.tvStatus);
            TextView label = cardWaterTemp.findViewById(R.id.tvLabel);
            if (label != null) label.setText("Water Temp");
        }
        if (cardWaterLevel != null) {
            tvWaterLevel = cardWaterLevel.findViewById(R.id.tvValue);
            tvWaterLevelStatus = cardWaterLevel.findViewById(R.id.tvStatus);
            TextView label = cardWaterLevel.findViewById(R.id.tvLabel);
            if (label != null) label.setText("Water Level");
        }
    }

    private void startRealTimeMonitoring() {
        if (getContext() == null) return;
        SharedPreferences prefs = requireContext().getSharedPreferences("basilience_prefs", android.content.Context.MODE_PRIVATE);
        String deviceId = prefs.getString("selected_device_id", null);
        selectedDeviceId = deviceId;

        if (deviceId == null) {
            Log.e("Monitoring", "No device selected");
            return;
        }

        dbHelper.setSelectedDeviceId(deviceId);
        sensorRepository.startListening(deviceId, sensorLiveData, sensorReadErrorLiveData);
        if (!isCurrentlyOnline) confirmSetupApReachability(deviceId);

        View v = getView();
        if (v != null) {
            SwitchMaterial modeSwitch = v.findViewById(R.id.switchMode);
            if (modeSwitch != null) {
                boolean modeSwitchEnabled = isAdminUser() && isCurrentlyOnline;
                modeSwitch.setEnabled(modeSwitchEnabled);
                modeSwitch.setAlpha(modeSwitchEnabled ? 1.0f : 0.6f);
            }
        }

        DatabaseReference deviceRef = dbHelper.getDeviceReference();
        if (deviceRef == null) {
            Log.e("Monitoring", "Device reference is null. Ensure deviceId is set.");
            return;
        }

        // Each RTDB subtree gets its own narrowly-scoped listener rather than one
        // listener on the whole device node, so each stays within what the RTDB
        // rules actually grant Android read access to, and so a failure on one
        // path (see onCancelled below) cannot wipe state owned by another path.

        // Read-only view of the one configured value the alert flags cannot
        // supply: waterTempOutOfRange carries no direction, so active cooling
        // needs the same ceiling the reports screen already reads. Nothing here
        // writes to settings.
        highWaterTempRef = deviceRef.child("settings").child("highWaterTemp");
        highWaterTempListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!isAdded()) return;
                Object value = snapshot.getValue();
                configuredHighWaterTemp = value instanceof Number ? ((Number) value).doubleValue() : null;
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e("RTDB", "highWaterTemp listener cancelled: " + error.getMessage());
                configuredHighWaterTemp = null;
            }
        };
        highWaterTempRef.addValueEventListener(highWaterTempListener);

        alertsRef = deviceRef.child("alerts");
        alertsListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!isAdded()) return;

                // Reuse firmware-published alert truth for presentation only.
                // No Android-side threshold or warning range is introduced.
                overrideFlags.phLow = isAlertActive(snapshot, "phLow");
                overrideFlags.phHigh = isAlertActive(snapshot, "phHigh");
                overrideFlags.ecLow = isAlertActive(snapshot, "ecLow");
                overrideFlags.lowWater = isAlertActive(snapshot, "lowWater");
                alertsLoaded = true;

                // Direction is kept, not collapsed, so each reading can say
                // Below Range / Normal / Above Range rather than just "Warning".
                phBelowRange = isAlertActive(snapshot, "phLow");
                phAboveRange = isAlertActive(snapshot, "phHigh");
                phAlertActive = phBelowRange || phAboveRange
                        || isAlertActive(snapshot, "phOutOfRange");

                ecBelowRange = isAlertActive(snapshot, "ecLow");
                ecAboveRange = isAlertActive(snapshot, "ecHigh");
                ecAlertActive = ecBelowRange || ecAboveRange;

                airTempBelowRange = isAlertActive(snapshot, "lowAirTemperature");
                airTempAboveRange = isAlertActive(snapshot, "highTemperature");
                airTemperatureAlertActive = airTempBelowRange || airTempAboveRange;

                humidityBelowRange = isAlertActive(snapshot, "humidityLow");
                humidityAboveRange = isAlertActive(snapshot, "humidityHigh");
                humidityAlertActive = humidityBelowRange || humidityAboveRange;

                waterTempBelowRange = isAlertActive(snapshot, "waterTempLow");
                waterTempAboveRange = isAlertActive(snapshot, "waterTempOutOfRange");
                waterTemperatureAlertActive = waterTempBelowRange || waterTempAboveRange;

                // Target-range classification for display. lowWater stays the
                // separate refill CONTROL signal and is not shown as a range.
                waterLevelBelowRange = isAlertActive(snapshot, "waterLevelLow");
                waterLevelAboveRange = isAlertActive(snapshot, "waterLevelHigh");
                waterLevelAlertActive = waterLevelBelowRange || waterLevelAboveRange;
                updateSensorUI();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e("RTDB", "alerts listener cancelled: " + error.getMessage());
            }
        };
        alertsRef.addValueEventListener(alertsListener);

        statusRef = dbHelper.getStatusReference();
        statusListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!isAdded()) return;

                // Sync status: reservoirLocked & safetyLock
                Boolean reservoirLocked = snapshot.child("reservoirLocked").getValue(Boolean.class);
                if (reservoirLocked != null) {
                    isReservoirLocked = reservoirLocked;
                    operationContext.reservoirLocked = reservoirLocked;
                }

                Boolean safetyLock = snapshot.child("safetyLock").getValue(Boolean.class);
                if (safetyLock != null) {
                    isSafetyLock = safetyLock;
                    operationContext.safetyLock = safetyLock;
                    updateConnectionUI();
                }

                // Operation-aware manual-command validation (ManualOverrideAdvisor.evaluateCommand)
                // reads the rest of this same node - none of these are a new
                // RTDB path, just fields on /status this listener wasn't
                // pulling out before.
                Integer currentMode = snapshot.child("currentMode").getValue(Integer.class);
                operationContext.currentMode = currentMode != null ? currentMode : -1;
                operationContext.phDirection = snapshot.child("phDirection").getValue(String.class);
                operationContext.ecDirection = snapshot.child("ecDirection").getValue(String.class);
                operationContext.phSubsystemLocked = Boolean.TRUE.equals(snapshot.child("phSubsystemLocked").getValue(Boolean.class));
                operationContext.ecSubsystemLocked = Boolean.TRUE.equals(snapshot.child("ecSubsystemLocked").getValue(Boolean.class));
                operationContext.refillSubsystemLocked = Boolean.TRUE.equals(snapshot.child("refillSubsystemLocked").getValue(Boolean.class));
                operationContext.coolingSubsystemLocked = Boolean.TRUE.equals(snapshot.child("coolingSubsystemLocked").getValue(Boolean.class));
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e("RTDB", "status listener cancelled: " + error.getMessage());
            }
        };
        statusRef.addValueEventListener(statusListener);

        manualModeRef = deviceRef.child("commands").child("manualMode");
        manualModeListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!isAdded()) return;

                // Sync Manual Mode from RTDB
                Boolean manualMode = snapshot.getValue(Boolean.class);
                if (manualMode != null) {
                    isManualMode = manualMode;
                    SwitchMaterial modeSwitch = getView() != null ? getView().findViewById(R.id.switchMode) : null;
                    if (modeSwitch != null) {
                        modeSwitch.setOnCheckedChangeListener(null);
                        modeSwitch.setChecked(isManualMode);
                        modeSwitch.setText("Manual Mode");

                        // Named (not inline) so the Admin guard below can restore
                        // this exact listener - including its confirmation-dialog
                        // logic - after reverting a blocked attempt, instead of
                        // permanently downgrading to the simpler post-confirm
                        // listener used at lines below.
                        final android.widget.CompoundButton.OnCheckedChangeListener[] fullListenerRef =
                                new android.widget.CompoundButton.OnCheckedChangeListener[1];
                        fullListenerRef[0] = (buttonView, checked) -> {
                            if (!isAdminUser()) {
                                // Enabling Manual Mode is Admin-only, same as the
                                // per-actuator switches - Database_Helper.updateManualMode()
                                // and the RTDB commands rule both already enforce this.
                                modeSwitch.setOnCheckedChangeListener(null);
                                modeSwitch.setChecked(isManualMode);
                                modeSwitch.setOnCheckedChangeListener(fullListenerRef[0]);
                                Toast.makeText(getContext(), "You do not have permission to control Manual Mode.", Toast.LENGTH_LONG).show();
                                return;
                            }
                            if (checked) {
                                // Snap back and show confirmation
                                modeSwitch.setOnCheckedChangeListener(null);
                                modeSwitch.setChecked(false);
                                modeSwitch.setOnCheckedChangeListener((btn, ch) -> onModeSwitchChanged(modeSwitch, ch));

                                String title = "Enable Manual Mode";
                                String message = "In Manual Mode, automated safety protocols and schedules are paused. Are you sure you want to proceed?";

                                if (isReservoirLocked) {
                                    title = "Automatic Operation Active";
                                    message = "The system is currently performing an automatic operation (e.g. refilling, dosing). Enabling manual mode will abort it.\n\nDo you want to continue?";
                                }

                                NotificationHelper.showConfirmation(requireContext(), title, message, "Enable", "Cancel", () -> {
                                    isManualMode = true;
                                    modeSwitch.setOnCheckedChangeListener(null);
                                    modeSwitch.setChecked(true);
                                    updateActuatorControls();
                                    dbHelper.updateManualMode(true);
                                    modeSwitch.setOnCheckedChangeListener((btn, ch) -> onModeSwitchChanged(modeSwitch, ch));
                                });
                                return;
                            }
                            onModeSwitchChanged(modeSwitch, checked);
                        };
                        modeSwitch.setOnCheckedChangeListener(fullListenerRef[0]);
                    }
                    updateActuatorControls();
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e("RTDB", "commands/manualMode listener cancelled: " + error.getMessage());
            }
        };
        manualModeRef.addValueEventListener(manualModeListener);

        // actuatorStatus is the sole authoritative runtime actuator state path.
        // The legacy 'actuators' node is never written by current firmware
        // (confirmed: no writer for that literal path in FirebaseManager.cpp),
        // so no fallback listener is created for it.
        actuatorStatusRef = deviceRef.child("actuatorStatus");
        actuatorStatusListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!isAdded() || !snapshot.exists()) return;

                syncActuatorState(waterPumpValve, actWaterPumpValve, snapshot.child(waterPumpValve.dbKey));
                syncActuatorState(canopyFan, actCanopyFan, snapshot.child(canopyFan.dbKey));
                syncActuatorState(growLights, actGrowLights, snapshot.child(growLights.dbKey));
                syncActuatorState(phUp, actPhUp, snapshot.child(phUp.dbKey));
                syncActuatorState(phDown, actPhDown, snapshot.child(phDown.dbKey));
                // nutrients uses both growPump + bloomPump — use combined sync
                syncNutrientsState(snapshot.child("growPump"), snapshot.child("bloomPump"));
                syncActuatorState(fogger, actFogger, snapshot.child(fogger.dbKey));
                syncActuatorState(reservoirFan, actReservoirFan, snapshot.child(reservoirFan.dbKey));
                syncActuatorState(peltier, actPeltier, snapshot.child(peltier.dbKey));
                syncActuatorState(circulationPump, actCirculationPump, snapshot.child(circulationPump.dbKey));
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                // Do not touch actuator.state on cancellation — leaves the last
                // confirmed physical state on screen instead of showing a false OFF.
                Log.e("RTDB", "actuatorStatus listener cancelled: " + error.getMessage());
            }
        };
        actuatorStatusRef.addValueEventListener(actuatorStatusListener);
    }

    private void syncActuatorState(Actuator actuator, View card, DataSnapshot stateSnap) {
        if (card == null || !stateSnap.exists()) return;

        Integer state = null;
        Boolean running = null;
        String source = "";
        if (stateSnap.getValue() instanceof Boolean) {
            running = stateSnap.getValue(Boolean.class);
            state = Boolean.TRUE.equals(running) ? 5 : 0;
        } else if (stateSnap.hasChild("state")) {
            state = stateSnap.child("state").getValue(Integer.class);
            running = stateSnap.child("running").getValue(Boolean.class);
            String reportedSource = stateSnap.child("source").getValue(String.class);
            if (reportedSource != null) source = reportedSource;
        }

        if (state != null) {
            actuator.state = state;
            actuator.physicalRunning = running != null ? running : state == 5;
            actuator.physicalSource = source;
            actuator.strategy = stateSnap.hasChild("strategy")
                    ? stateSnap.child("strategy").getValue(String.class)
                    : "";
            actuator.reason = stateSnap.hasChild("reason")
                    ? stateSnap.child("reason").getValue(String.class)
                    : null;
            actuator.overrideActive = Boolean.TRUE.equals(
                    stateSnap.child("overrideActive").getValue(Boolean.class));
            updateActuatorUI(card, actuator);
        }
    }

    /**
     * Combines growPump + bloomPump states into the single "nutrients" UI card.
     * Both pumps are commanded together, so the UI reflects the worst-case state:
     * - Either REJECTED(3)  → nutrients = REJECTED
     * - Both  RUNNING(5)    → nutrients = RUNNING (ON)
     * - Both  OFF(0)        → nutrients = OFF
     * - Otherwise           → highest intermediate state (whichever is further in the sequence)
     */
    private void syncNutrientsState(DataSnapshot growSnap, DataSnapshot bloomSnap) {
        if (actNutrients == null) return;

        int growState  = getSnapState(growSnap);
        int bloomState = getSnapState(bloomSnap);

        int combinedState;
        String combinedReason = null;

        if (growState == 3 || bloomState == 3) {
            // Either pump rejected — show REJECTED and surface the reason
            combinedState = 3;
            if (growState == 3 && growSnap.hasChild("reason"))
                combinedReason = growSnap.child("reason").getValue(String.class);
            else if (bloomState == 3 && bloomSnap.hasChild("reason"))
                combinedReason = bloomSnap.child("reason").getValue(String.class);
        } else if (growState == 5 && bloomState == 5) {
            combinedState = 5; // Both fully RUNNING
        } else if (growState == 0 && bloomState == 0) {
            combinedState = 0; // Both fully OFF
        } else {
            // In-progress: show the highest non-terminal intermediate state
            combinedState = Math.max(growState, bloomState);
        }

        nutrients.state  = combinedState;
        Boolean growRunning = growSnap.child("running").getValue(Boolean.class);
        Boolean bloomRunning = bloomSnap.child("running").getValue(Boolean.class);
        nutrients.physicalRunning = growRunning != null || bloomRunning != null
                ? Boolean.TRUE.equals(growRunning) || Boolean.TRUE.equals(bloomRunning)
                : combinedState == 5;
        String growSource = growSnap.child("source").getValue(String.class);
        String bloomSource = bloomSnap.child("source").getValue(String.class);
        nutrients.physicalSource = "manual".equalsIgnoreCase(growSource) || "manual".equalsIgnoreCase(bloomSource)
                ? "manual"
                : ("automatic".equalsIgnoreCase(growSource) || "automatic".equalsIgnoreCase(bloomSource)
                    ? "automatic" : "");
        nutrients.reason = combinedReason;
        nutrients.overrideActive = Boolean.TRUE.equals(growSnap.child("overrideActive").getValue(Boolean.class))
                || Boolean.TRUE.equals(bloomSnap.child("overrideActive").getValue(Boolean.class));
        updateActuatorUI(actNutrients, nutrients);
    }

    /** Reads the integer state from an actuatorStatus snapshot node. Returns 0 (OFF) if absent. */
    private int getSnapState(DataSnapshot snap) {
        if (snap == null || !snap.exists()) return 0;
        if (snap.getValue() instanceof Boolean)
            return Boolean.TRUE.equals(snap.getValue(Boolean.class)) ? 5 : 0;
        if (snap.hasChild("state")) {
            Integer v = snap.child("state").getValue(Integer.class);
            return v != null ? v : 0;
        }
        return 0;
    }

    /**
     * Same SharedPreferences role check already used for btnTriggerRefill/
     * btnResetSafety in this fragment - manual actuator control shares the
     * same Admin-only policy as those two.
     */
    private boolean isAdminUser() {
        SharedPreferences localPrefs = requireContext().getSharedPreferences("basilience_prefs", android.content.Context.MODE_PRIVATE);
        return "ADMIN".equalsIgnoreCase(localPrefs.getString("user_role", "FARMER"));
    }

    // =========================================================
    // ACTUATOR UI SETUP — tap shows loading popup, not inline
    // =========================================================

    private void setupActuatorUI(View card, Actuator actuator) {
        TextView nameTv = card.findViewById(R.id.tvActuatorName);
        if (nameTv != null) nameTv.setText(actuator.name);

        SwitchMaterial toggle = card.findViewById(R.id.switchActuator);
        if (toggle == null) return;

        // Manual actuator control is Admin-only - Database_Helper.updateActuatorState()
        // gates every write behind checkAdminTask(), and the RTDB commands node's
        // .write rule requires role === 'ADMIN'. Without this check the switch was
        // enabled for any signed-in Personnel/Farmer whenever Manual Mode was on,
        // even though every command they sent was guaranteed to fail server-side.
        boolean isAdmin = isAdminUser();

        // Clear any previous listener first
        toggle.setOnCheckedChangeListener(null);
        // Confirmed firmware state is authoritative for both AUTO and MANUAL.
        toggle.setChecked(actuator.physicalRunning);
        toggle.setEnabled(isAdmin && isManualMode && isCurrentlyOnline && !isSafetyLock && !isActuatorBusy);
        toggle.setAlpha(isAdmin ? 1.0f : 0.6f);

        toggle.setOnCheckedChangeListener((buttonView, checked) -> {
            if (!isAdmin) {
                // Defense in depth: the switch is disabled above, but this
                // mirrors the !isManualMode guard immediately below in case the
                // listener still fires (e.g. accessibility tooling).
                toggle.setOnCheckedChangeListener(null);
                toggle.setChecked(actuator.physicalRunning);
                setupActuatorUI(card, actuator);
                Toast.makeText(getContext(), "You do not have permission to manually control actuators.", Toast.LENGTH_LONG).show();
                return;
            }
            if (!isManualMode) {
                // Snap back immediately — no popup
                toggle.setOnCheckedChangeListener(null);
                toggle.setChecked(actuator.physicalRunning);
                setupActuatorUI(card, actuator);
                return;
            }

            // Every manual command - ON or OFF alike - is routed through the
            // one shared operation-aware validator: normal-condition rules,
            // active pH/EC dosing-or-stabilization interference, automation
            // ownership, and firmware's hard locks/interlocks are all folded
            // into a single SAFE / SOFT_CONFLICT / HARD_BLOCK verdict, never
            // a stack of separate checks or dialogs.
            ManualOverrideAdvisor.ActuatorKey key = keyFor(actuator);
            ManualOverrideAdvisor.Result result = ManualOverrideAdvisor.evaluateCommand(
                    key, checked, sensorLiveData.getValue(), alertsLoaded ? overrideFlags : null,
                    configuredHighWaterTemp, operationContext, buildActuatorSnapshots());

            if (result.decision == ManualOverrideAdvisor.Decision.HARD_BLOCK) {
                toggle.setOnCheckedChangeListener(null);
                toggle.setChecked(actuator.physicalRunning);
                setupActuatorUI(card, actuator);
                NotificationHelper.showError(requireContext(), result.title, result.message);
                return;
            }

            if (result.decision == ManualOverrideAdvisor.Decision.SOFT_CONFLICT) {
                // The switch must not sit visually at the requested state while
                // the user is still deciding, so it goes back to confirmed
                // device state before the dialog appears. Nothing is locked
                // and no request exists yet - Cancel simply leaves it as it was.
                toggle.setOnCheckedChangeListener(null);
                toggle.setChecked(actuator.physicalRunning);
                setupActuatorUI(card, actuator);

                final boolean targetChecked = checked;
                NotificationHelper.showConfirmation(requireContext(),
                        result.title, result.message,
                        "Continue", "Cancel",
                        () -> {
                            Log.d("Monitoring", "[MANUAL-APP] Override confirmed actuator=" + actuator.dbKey + " target=" + targetChecked);
                            sendActuatorCommand(card, actuator, targetChecked, true);
                        });
                return;
            }

            sendActuatorCommand(card, actuator, checked, false);
        });
    }

    /** Maps a fragment Actuator to its ManualOverrideAdvisor.ActuatorKey - both cover the same 10 UI cards. */
    private ManualOverrideAdvisor.ActuatorKey keyFor(Actuator actuator) {
        if (actuator == phUp) return ManualOverrideAdvisor.ActuatorKey.PH_UP;
        if (actuator == phDown) return ManualOverrideAdvisor.ActuatorKey.PH_DOWN;
        if (actuator == nutrients || actuator == bloomPump) return ManualOverrideAdvisor.ActuatorKey.NUTRIENTS;
        if (actuator == waterPumpValve) return ManualOverrideAdvisor.ActuatorKey.SOLENOID;
        if (actuator == peltier) return ManualOverrideAdvisor.ActuatorKey.PELTIER;
        if (actuator == circulationPump) return ManualOverrideAdvisor.ActuatorKey.CIRCULATION_PUMP;
        if (actuator == fogger) return ManualOverrideAdvisor.ActuatorKey.FOGGER;
        if (actuator == reservoirFan) return ManualOverrideAdvisor.ActuatorKey.BLOWER;
        if (actuator == canopyFan) return ManualOverrideAdvisor.ActuatorKey.CANOPY_FAN;
        return ManualOverrideAdvisor.ActuatorKey.GROW_LIGHT;
    }

    /** Snapshots actuatorStatus's already-synced running/source/reason for every actuator a validation rule might cross-check against. */
    private ManualOverrideAdvisor.ActuatorSnapshots buildActuatorSnapshots() {
        ManualOverrideAdvisor.ActuatorSnapshots snapshots = new ManualOverrideAdvisor.ActuatorSnapshots();
        copySnapshot(phUp, snapshots.phUp);
        copySnapshot(phDown, snapshots.phDown);
        copySnapshot(nutrients, snapshots.nutrients);
        copySnapshot(waterPumpValve, snapshots.solenoid);
        copySnapshot(peltier, snapshots.peltier);
        copySnapshot(circulationPump, snapshots.circulationPump);
        copySnapshot(fogger, snapshots.fogger);
        copySnapshot(reservoirFan, snapshots.blower);
        copySnapshot(canopyFan, snapshots.canopyFan);
        copySnapshot(growLights, snapshots.growLight);
        return snapshots;
    }

    private void copySnapshot(Actuator source, ManualOverrideAdvisor.ActuatorSnapshot target) {
        target.physicalRunning = source.physicalRunning;
        target.physicalSource = source.physicalSource;
        target.reason = source.reason;
    }

    /**
     * Sends the manual actuator request.
     *
     * @param overrideRequested true only when the user pressed Continue on a
     *                          ManualOverrideAdvisor confirmation for this
     *                          exact command; always false for a direct
     *                          PROCEED command or any OFF command. Threaded
     *                          straight through to Database_Helper so
     *                          firmware's soft-rule checks (already-in-range
     *                          pH/EC, refill-not-needed, temp-in-range) can
     *                          tell a confirmed override from an ordinary
     *                          manual command - see ActuatorManager::validateCommand.
     */
    private void sendActuatorCommand(View card, Actuator actuator, boolean checked, boolean overrideRequested) {
            Log.d("Monitoring", "[MANUAL-APP] sendActuatorCommand actuator=" + actuator.dbKey + " target=" + checked
                    + " override=" + overrideRequested
                    + " deviceId=" + selectedDeviceId + " isManualMode=" + isManualMode + " isCurrentlyOnline=" + isCurrentlyOnline);

            SwitchMaterial toggle = card.findViewById(R.id.switchActuator);
            if (toggle == null) return;

            final boolean previousManualIntent = actuator.manualIntent;
            final int commandBaselineState = actuator.state;
            final boolean commandBaselineRunning = actuator.physicalRunning;
            final String commandBaselineSource = actuator.physicalSource;
            final int generation = ++actuatorCommandGeneration;
            actuatorCommandFinished = false;

            // Lock all switches while processing
            isActuatorBusy = true;
            updateActuatorControls();

            // Revert toggle to original position — popup will be the feedback
            toggle.setOnCheckedChangeListener(null);
            toggle.setChecked(actuator.physicalRunning);

            // Show loading popup
            showActuatorLoading("Sending command...", "");

            com.google.android.gms.tasks.Task<Void> updateTask;
            if (actuator == nutrients) {
                updateTask = com.google.android.gms.tasks.Tasks.whenAll(
                        dbHelper.updateActuatorState("growPump", checked, overrideRequested),
                        dbHelper.updateActuatorState("bloomPump", checked, overrideRequested)
                );
            } else {
                updateTask = dbHelper.updateActuatorState(actuator.dbKey, checked, overrideRequested);
            }

            final boolean targetState = checked;
            updateTask.addOnCompleteListener(task -> {
                if (!isAdded() || generation != actuatorCommandGeneration || actuatorCommandFinished) return;
                if (!task.isSuccessful()) {
                    Log.e("Monitoring", "Actuator command failed for " + actuator.dbKey, task.getException());
                    finishActuatorCommand(generation, actuator, previousManualIntent,
                            "Command Failed", "The actuator command could not be sent. Please try again.",
                            false);
                } else {
                    // Command written — show Validating immediately and start polling
                    showActuatorLoading("Validating...", "");
                    monitorActuatorCommand(generation, actuator, previousManualIntent, targetState,
                            commandBaselineState, commandBaselineRunning, commandBaselineSource);
                }
            });
    }

    /** Show the full-screen loading overlay with a title and optional subtitle */
    private void showActuatorLoading(String title, String subtitle) {
        if (actuatorLoadingOverlay == null || !isAdded()) return;
        if (actuatorLoadingOverlay.getVisibility() != View.VISIBLE) {
            actuatorLoadingShownAt = SystemClock.elapsedRealtime();
        }
        actuatorLoadingOverlay.setVisibility(View.VISIBLE);
        actuatorLoadingOverlay.bringToFront();
        if (tvActuatorLoadingTitle != null) tvActuatorLoadingTitle.setText(title);
        if (tvActuatorLoadingStatus != null) {
            tvActuatorLoadingStatus.setText(subtitle);
            tvActuatorLoadingStatus.setVisibility(subtitle.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    /** Hide the overlay and unlock all switches, never sooner than the minimum visible duration */
    private void hideActuatorLoading() {
        if (actuatorLoadingOverlay == null || !isAdded()
                || actuatorLoadingOverlay.getVisibility() != View.VISIBLE) return;
        NotificationHelper.hideLoaderAfterMinimumDuration(actuatorLoadingShownAt, () -> {
            if (isAdded() && actuatorLoadingOverlay != null) actuatorLoadingOverlay.setVisibility(View.GONE);
        });
    }

    /**
     * Polls the actuator's state every 500ms until it reaches a terminal state (OFF=0 or RUNNING=5)
     * or REJECTED(3), or a 10s timeout. Shows real-time state labels in the popup.
     */
    private void pollActuatorUntilDone(Actuator actuator, View card, boolean targetState, int attempt) {
        final int MAX_ATTEMPTS = 10; // 10 * 500ms = 5 seconds — allows for 1500ms ESP32 read + write roundtrip
        if (!isAdded()) return;

        mainHandler.postDelayed(() -> {
            if (!isAdded()) return;

            int state = actuator.state;

            // Check if we've reached the expected terminal state
            boolean doneSuccess = (targetState && state == 5) || (!targetState && state == 0);

            if (doneSuccess) {
                showActuatorLoading("Done", "");
                // Small pause so user can see the final state label, then close
        mainHandler.postDelayed(() -> {
                    if (!isAdded()) return;
                    hideActuatorLoading();
                    isActuatorBusy = false;
                    updateActuatorControls();
                    refreshAllActuatorUI();
                }, 600);
                return;
            }

            if (state == 3) { // REJECTED — terminal
                String reason = (actuator.reason != null && !actuator.reason.isEmpty()) ? actuator.reason : "Command rejected by ESP32";
                showActuatorLoading("Error", reason);
        mainHandler.postDelayed(() -> {
                    if (!isAdded()) return;
                    hideActuatorLoading();
                    isActuatorBusy = false;
                    updateActuatorControls();
                    refreshAllActuatorUI();
                }, 2000);
                return;
            }

            // Update popup label for intermediate states
            if (state == 6) {
                showActuatorLoading("Stopping...", "");
            } else if (state == 4) {
                showActuatorLoading("Activating...", "");
            } else {
                showActuatorLoading("Validating...", "");
            }

            // Timeout
            if (attempt >= MAX_ATTEMPTS) {
                showActuatorLoading("Command Timeout", "The device is online, but the actuator did not confirm the command in time.");
        mainHandler.postDelayed(() -> {
                    if (!isAdded()) return;
                    hideActuatorLoading();
                    isActuatorBusy = false;
                    updateActuatorControls();
                    refreshAllActuatorUI();
                }, 2500);
                return;
            }

            // Keep polling
            pollActuatorUntilDone(actuator, card, targetState, attempt + 1);
        }, 500);
    }

    /**
     * Polls the operations/current node to track a requested operation.
     */
    private void monitorActuatorCommand(int generation, Actuator actuator,
                                        boolean previousManualIntent, boolean targetState,
                                        int baselineState, boolean baselineRunning,
                                        String baselineSource) {
        final int[] lastState = {baselineState};
        final boolean[] lastRunning = {baselineRunning};
        final String[] lastSource = {baselineSource};
        final long[] lastProgressAt = {SystemClock.elapsedRealtime()};
        final boolean[] sawRelevantProgress = {false};

        actuatorCommandRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isAdded() || actuatorCommandFinished || generation != actuatorCommandGeneration) return;

                if (!isCurrentlyOnline) {
                    finishActuatorCommand(generation, actuator, previousManualIntent,
                            "Device Offline", "The Basilience device is not currently connected.",
                            false);
                    return;
                }

                int state = actuator.state;
                boolean running = actuator.physicalRunning;
                String source = actuator.physicalSource == null ? "" : actuator.physicalSource;

                if (state != lastState[0] || running != lastRunning[0] || !source.equals(lastSource[0])) {
                    lastState[0] = state;
                    lastRunning[0] = running;
                    lastSource[0] = source;
                    lastProgressAt[0] = SystemClock.elapsedRealtime();
                    sawRelevantProgress[0] = true;
                }

                if (state == 3) {
                    String reason = actuator.reason != null && !actuator.reason.isEmpty()
                            ? actuator.reason : "The firmware rejected the command.";
                    finishActuatorCommand(generation, actuator, previousManualIntent,
                            "Command Rejected", reason, false);
                    return;
                }

                boolean sourceAcknowledged = "manual".equalsIgnoreCase(source)
                        && !source.equalsIgnoreCase(baselineSource);
                boolean success = targetState
                        ? state == 5 && running
                        : state == 0 && !running;
                if (success && (sawRelevantProgress[0] || sourceAcknowledged)) {
                    finishActuatorCommand(generation, actuator, targetState, null, null, true);
                    return;
                }

                updateActuatorProgress(state, targetState);

                if (SystemClock.elapsedRealtime() - lastProgressAt[0] >= ACTUATOR_INACTIVITY_TIMEOUT_MS) {
                    finishActuatorCommand(generation, actuator, previousManualIntent,
                            "Command Timeout",
                            "The device is online, but the actuator did not confirm the command in time.",
                            false);
                    return;
                }

                mainHandler.postDelayed(this, ACTUATOR_POLL_INTERVAL_MS);
            }
        };
        mainHandler.post(actuatorCommandRunnable);
    }

    private void updateActuatorProgress(int state, boolean targetState) {
        switch (state) {
            case 1:
                showActuatorLoading("Command received...", "");
                break;
            case 2:
                showActuatorLoading("Validating...", "");
                break;
            case 4:
                showActuatorLoading("Activating...", "");
                break;
            case 6:
                showActuatorLoading("Stopping...", "");
                break;
            default:
                showActuatorLoading(targetState ? "Waiting for RUNNING..." : "Waiting for OFF...", "");
                break;
        }
    }

    private void finishActuatorCommand(int generation, Actuator actuator,
                                       boolean resultingManualIntent, String title,
                                       String message, boolean success) {
        if (actuatorCommandFinished || generation != actuatorCommandGeneration) return;
        actuatorCommandFinished = true;
        if (actuatorCommandRunnable != null) {
            mainHandler.removeCallbacks(actuatorCommandRunnable);
            actuatorCommandRunnable = null;
        }

        actuator.manualIntent = resultingManualIntent;
        hideActuatorLoading();
        isActuatorBusy = false;
        updateActuatorControls();
        refreshAllActuatorUI();

        if (!success && isAdded() && title != null) {
            NotificationHelper.showError(requireContext(), title, message);
        }
    }

    private void pollOperationUntilDone(int requestId, String opName, int attempt) {
        final int MAX_ATTEMPTS = 60; // 60 * 1000ms = 60 seconds timeout
        if (!isAdded()) return;

        mainHandler.postDelayed(() -> {
            if (!isAdded()) return;

            dbHelper.getOperationsCurrentReference().get().addOnCompleteListener(task -> {
                if (!isAdded()) return;

                if (!task.isSuccessful() || !task.getResult().exists()) {
                    if (attempt >= MAX_ATTEMPTS) {
                        showActuatorLoading("Timeout", "Operation timed out or no response");
        mainHandler.postDelayed(() -> {
                            hideActuatorLoading();
                            isActuatorBusy = false;
                            updateActuatorControls();
                        }, 2500);
                    } else {
                        pollOperationUntilDone(requestId, opName, attempt + 1);
                    }
                    return;
                }

                DataSnapshot snap = task.getResult();
                Integer currentReqId = snap.child("requestId").getValue(Integer.class);
                String state = snap.child("state").getValue(String.class);
                
                // If the operation ID doesn't match yet, keep waiting
                if (currentReqId == null || currentReqId != requestId) {
                    if (attempt >= MAX_ATTEMPTS) {
                        showActuatorLoading("Timeout", "Operation timed out or no response");
        mainHandler.postDelayed(() -> {
                            hideActuatorLoading();
                            isActuatorBusy = false;
                            updateActuatorControls();
                        }, 2500);
                    } else {
                        pollOperationUntilDone(requestId, opName, attempt + 1);
                    }
                    return;
                }

                if ("COMPLETED".equals(state)) {
                    showActuatorLoading("Done", opName + " completed successfully.");
        mainHandler.postDelayed(() -> {
                        hideActuatorLoading();
                        isActuatorBusy = false;
                        updateActuatorControls();
                    }, 1500);
                    return;
                } else if ("FAILED".equals(state) || "REJECTED".equals(state)) {
                    String reason = snap.child("reason").getValue(String.class);
                    showActuatorLoading("Error", reason != null && !reason.isEmpty() ? reason : "Operation failed.");
        mainHandler.postDelayed(() -> {
                        hideActuatorLoading();
                        isActuatorBusy = false;
                        updateActuatorControls();
                    }, 2500);
                    return;
                }

                // In progress
                if ("RUNNING".equals(state)) {
                    showActuatorLoading("Running...", opName + " is in progress");
                    // Do not increment attempt if it's actively running to prevent timeout
                    pollOperationUntilDone(requestId, opName, 0); 
                    return;
                } else if ("ACCEPTED".equals(state)) {
                    showActuatorLoading("Accepted", "Starting " + opName + "...");
                    pollOperationUntilDone(requestId, opName, 0); 
                    return;
                } else {
                    showActuatorLoading("Validating...", "Waiting for ESP32 to validate");
                }

                if (attempt >= MAX_ATTEMPTS) {
                    showActuatorLoading("Timeout", "Operation timed out");
        mainHandler.postDelayed(() -> {
                        hideActuatorLoading();
                        isActuatorBusy = false;
                        updateActuatorControls();
                    }, 2500);
                } else {
                    pollOperationUntilDone(requestId, opName, attempt + 1);
                }
            });
        }, 1000); // 1 second intervals for operations
    }

    /** Re-draws all actuator switches to match current confirmed Firebase state */
    private void refreshAllActuatorUI() {
        if (!isAdded() || getView() == null) return;
        if (actWaterPumpValve != null) setupActuatorUI(actWaterPumpValve, waterPumpValve);
        if (actCanopyFan != null) setupActuatorUI(actCanopyFan, canopyFan);
        if (actGrowLights != null) setupActuatorUI(actGrowLights, growLights);
        if (actPhUp != null) setupActuatorUI(actPhUp, phUp);
        if (actPhDown != null) setupActuatorUI(actPhDown, phDown);
        if (actNutrients != null) setupActuatorUI(actNutrients, nutrients);
        if (actFogger != null) setupActuatorUI(actFogger, fogger);
        if (actReservoirFan != null) setupActuatorUI(actReservoirFan, reservoirFan);
        if (actPeltier != null) setupActuatorUI(actPeltier, peltier);
        if (actCirculationPump != null) setupActuatorUI(actCirculationPump, circulationPump);
    }

    private void updateActuatorUI(View card, Actuator actuator) {
        if (card == null || !isAdded()) return;
        TextView status = card.findViewById(R.id.tvStatus);
        SwitchMaterial toggle = card.findViewById(R.id.switchActuator);

        if (toggle != null) {
            // Update switch position without triggering listener
            toggle.setOnCheckedChangeListener(null);
            toggle.setChecked(actuator.physicalRunning);
            toggle.setEnabled(isManualMode);
            // Re-register listener cleanly
            toggle.setOnCheckedChangeListener(null);
            setupActuatorUI(card, actuator);
        }
        
        if (status != null) {
            int stateColorRes;
            switch (actuator.state) {
                case 0:
                    status.setText("Off");
                    stateColorRes = R.color.actuator_off;
                    break;
                case 1:
                    status.setText("Command Sent" + actuatorSourceSuffix(actuator));
                    stateColorRes = R.color.actuator_pending;
                    break;
                case 2:
                    status.setText("Validating" + actuatorSourceSuffix(actuator));
                    stateColorRes = R.color.actuator_pending;
                    break;
                case 3:
                    status.setText("Rejected" + actuatorSourceSuffix(actuator));
                    stateColorRes = R.color.actuator_rejected;
                    break;
                case 4:
                    status.setText("Starting" + actuatorSourceSuffix(actuator));
                    stateColorRes = R.color.actuator_pending;
                    break;
                case 5:
                    status.setText("Running" + runningActuatorSuffix(actuator));
                    stateColorRes = "manual".equalsIgnoreCase(actuator.physicalSource)
                            || "android".equalsIgnoreCase(actuator.physicalSource)
                            ? R.color.actuator_manual : R.color.actuator_auto;
                    break;
                case 6:
                    status.setText("Stopping" + actuatorSourceSuffix(actuator));
                    stateColorRes = R.color.actuator_pending;
                    break;
                default:
                    stateColorRes = R.color.actuator_off;
                    break;
            }
            // An unreachable device cannot confirm any of this, so the last
            // value is kept but clearly marked as no longer verified rather
            // than presented as live truth.
            if (!isCurrentlyOnline) {
                status.setText("Last known: " + status.getText());
                stateColorRes = R.color.state_no_data;
            }

            int stateColor = ContextCompat.getColor(requireContext(), stateColorRes);
            status.setTextColor(stateColor);
            if (toggle != null) {
                toggle.setThumbTintList(ColorStateList.valueOf(stateColor));
                toggle.setTrackTintList(ColorStateList.valueOf(stateColor));
            }
        }
    }

    private String actuatorSourceSuffix(Actuator actuator) {
        if ("automatic".equalsIgnoreCase(actuator.physicalSource)) return " · Auto";
        if ("manual".equalsIgnoreCase(actuator.physicalSource)) return " · Manual";
        if ("android".equalsIgnoreCase(actuator.physicalSource)) return " · App";
        return "";
    }

    private String runningActuatorSuffix(Actuator actuator) {
        String suffix = actuatorSourceSuffix(actuator);
        if (actuator.overrideActive && "manual".equalsIgnoreCase(actuator.physicalSource)) {
            suffix += " · Override";
        }
        if ("automatic".equalsIgnoreCase(actuator.physicalSource)
                && (actuator == fogger || actuator == reservoirFan)
                && ("cold".equalsIgnoreCase(actuator.strategy)
                    || "normal".equalsIgnoreCase(actuator.strategy)
                    || "hot".equalsIgnoreCase(actuator.strategy))) {
            String strategyLabel = actuator.strategy.substring(0, 1).toUpperCase(java.util.Locale.ROOT)
                    + actuator.strategy.substring(1).toLowerCase(java.util.Locale.ROOT);
            suffix += " · " + strategyLabel;
        }
        return suffix;
    }

    private Actuator getActuatorFromCard(View card) {
        if (card == actWaterPumpValve) return waterPumpValve;
        if (card == actCanopyFan) return canopyFan;
        if (card == actGrowLights) return growLights;
        if (card == actPhUp) return phUp;
        if (card == actPhDown) return phDown;
        if (card == actNutrients) return nutrients;
        if (card == actFogger) return fogger;
        if (card == actReservoirFan) return reservoirFan;
        if (card == actCirculationPump) return circulationPump;
        return peltier;
    }

    private void updateActuatorControls() {
        // Same Admin-only gate as setupActuatorUI() - without it, this method
        // (called from onModeSwitchChanged() and after every command
        // completes) would re-enable the switches for a non-Admin right
        // after setupActuatorUI() correctly disabled them.
        boolean enabled = isAdminUser() && isManualMode && isCurrentlyOnline && !isSafetyLock;
        setActuatorEnabled(actWaterPumpValve, enabled);
        setActuatorEnabled(actCanopyFan, enabled);
        setActuatorEnabled(actGrowLights, enabled);
        setActuatorEnabled(actPhUp, enabled);
        setActuatorEnabled(actPhDown, enabled);
        setActuatorEnabled(actNutrients, enabled);
        setActuatorEnabled(actFogger, enabled);
        setActuatorEnabled(actReservoirFan, enabled);
        setActuatorEnabled(actPeltier, enabled);
        setActuatorEnabled(actCirculationPump, enabled);
    }

    /** Shared logic for manual mode toggle — called both directly and from the confirmation dialog. */
    private void onModeSwitchChanged(SwitchMaterial modeSwitch, boolean checked) {
        isManualMode = checked;
        modeSwitch.setText("Manual Mode");

        if (!checked) {
            showActuatorLoading("Restoring Safety Protocols", "Resuming automatic operations...");
            mainHandler.postDelayed(this::hideActuatorLoading, 1500);
        }

        updateActuatorControls();
        dbHelper.updateManualMode(checked);
    }

    private void setActuatorEnabled(View card, boolean enabled) {
        if (card == null) return;
        View toggle = card.findViewById(R.id.switchActuator);
        if (toggle != null) toggle.setEnabled(enabled);
        card.setAlpha(enabled ? 1.0f : 0.6f);
    }

    private void showCombinedDialog(List<String> warnings, List<String> actions) {
        if (isDialogShowing || getContext() == null) return;
        isDialogShowing = true;
        StringBuilder msg = new StringBuilder();
        for (int i = 0; i < warnings.size(); i++) {
            msg.append("• ").append(warnings.get(i)).append(": ").append(actions.get(i)).append("\n");
        }
        NotificationHelper.showWarning(requireContext(), "System Alert", msg.toString());
    }

    /** Updates all sensor TextViews from the current sensorLiveData value. */
    private void updateSensorUI() {
        if (!isAdded() || getView() == null) return;
        SensorData data = sensorLiveData.getValue();
        if (tvPH != null) tvPH.setText(formatSensor(
                data != null ? data.ph : null, 0.0, 14.0, 2, "", null));
        if (tvEC != null) tvEC.setText(formatSensor(
                data != null ? data.ec : null, 0.0, Double.MAX_VALUE, 2, " mS/cm", null));
        if (tvTemp != null) tvTemp.setText(formatSensor(
                data != null ? data.airTemperature : null, -40.0, 80.0, 1, "°C", null));
        if (tvHumidity != null) tvHumidity.setText(formatSensor(
                data != null ? data.humidity : null, 0.0, 100.0, 1, "%", null));
        if (tvWaterTemp != null) tvWaterTemp.setText(formatSensor(
                data != null ? data.waterTemperature : null, -55.0, 125.0, 1, "°C", -127.0));
        if (tvWaterLevel != null) tvWaterLevel.setText(formatSensor(
                data != null ? data.waterLevel : null, 0.0, 100.0, 1, "%", null));

        applyParameterStateColor(tvPH, tvPHStatus, phAlertActive, phBelowRange, phAboveRange);
        applyParameterStateColor(tvEC, tvECStatus, ecAlertActive, ecBelowRange, ecAboveRange);
        applyParameterStateColor(tvTemp, tvTempStatus, airTemperatureAlertActive,
                airTempBelowRange, airTempAboveRange);
        // Humidity now has a real target range instead of always reading Normal.
        applyParameterStateColor(tvHumidity, tvHumidityStatus, humidityAlertActive,
                humidityBelowRange, humidityAboveRange);
        applyParameterStateColor(tvWaterTemp, tvWaterTempStatus, waterTemperatureAlertActive,
                waterTempBelowRange, waterTempAboveRange);
        applyParameterStateColor(tvWaterLevel, tvWaterLevelStatus, waterLevelAlertActive,
                waterLevelBelowRange, waterLevelAboveRange);
    }

    private boolean isAlertActive(DataSnapshot alerts, String key) {
        return Boolean.TRUE.equals(alerts.child(key).getValue(Boolean.class));
    }

    /** Applies the same Normal/Warning/No Data state to both the value's color and a text status label. */
    private void applyParameterStateColor(TextView valueView, TextView statusView, boolean alertActive) {
        applyParameterStateColor(valueView, statusView, alertActive, false, false);
    }

    /**
     * Renders one reading's state. A missing reading stays "No Data" and is
     * never reported as out of range; an excursion is labelled with its
     * direction when the device published one.
     */
    private void applyParameterStateColor(TextView valueView, TextView statusView,
                                          boolean alertActive, boolean belowRange, boolean aboveRange) {
        if (valueView == null) return;
        final int colorRes;
        final String statusText;
        if ("--".contentEquals(valueView.getText())) {
            colorRes = R.color.state_no_data;
            statusText = "No Data";
        } else if (belowRange) {
            colorRes = R.color.state_critical;
            statusText = "Below Range";
        } else if (aboveRange) {
            colorRes = R.color.state_critical;
            statusText = "Above Range";
        } else if (alertActive) {
            colorRes = R.color.state_critical;
            statusText = "Warning";
        } else {
            colorRes = R.color.state_success;
            statusText = "Normal";
        }
        int color = ContextCompat.getColor(requireContext(), colorRes);
        valueView.setTextColor(color);
        if (statusView != null) {
            statusView.setText(statusText);
            statusView.setTextColor(color);
        }
    }

    /**
     * Formats a sensor reading with its unit visually subordinate to the number
     * (unit shrunk to ~55% size), matching the convention already used for
     * report metric strips (see SystemReportsFragment#formatMetric).
     */
    private CharSequence formatSensor(Double value, double minimum, double maximum,
                                int decimalPlaces, String suffix, Double invalidSentinel) {
        if (value == null || value.isNaN() || value.isInfinite()) return "--";
        if (invalidSentinel != null && Math.abs(value - invalidSentinel) < 0.0001) return "--";
        if (value < minimum || value > maximum) return "--";
        String number = String.format(java.util.Locale.US, "%." + decimalPlaces + "f", value);
        if (suffix == null || suffix.isEmpty()) return number;
        android.text.SpannableString styled = new android.text.SpannableString(number + suffix);
        styled.setSpan(new android.text.style.RelativeSizeSpan(0.55f), number.length(), styled.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return styled;
    }


    /**
     * Shows the Cultivation Paused banner when no growth cycle is running.
     *
     * Reads the same cycles data as every other screen. Deliberately
     * independent of device reachability: an active cycle on an offline device
     * is still an active cycle, and must not be reported as paused.
     */
    private void observeCultivationState() {
        if (layoutCultivationPaused == null) return;

        android.content.SharedPreferences prefs = requireContext()
                .getSharedPreferences("basilience_prefs", android.content.Context.MODE_PRIVATE);
        String deviceId = prefs.getString("selected_device_id", null);

        cycleListener = CycleGateState.observe(new Database_Helper(), deviceId,
                (state, hasAnyCycle) -> {
                    if (!isAdded() || layoutCultivationPaused == null) return;
                    // Only a confirmed "no active cycle" shows the banner;
                    // loading and read errors leave it hidden.
                    layoutCultivationPaused.setVisibility(
                            state == CycleGateState.State.NONE ? View.VISIBLE : View.GONE);
                });
    }

    private void updateConnectionUI() {
        if (tvConnectionStatus == null || !isAdded()) return;

        // Actuator labels carry an offline marker, so they have to be redrawn
        // when reachability changes.
        refreshAllActuatorUI();

        DeviceConnectivityState displayState = setupApReachable
                ? DeviceConnectivityState.WIFI_CONFIGURATION_REQUIRED : connectivityState;
        tvConnectionStatus.setText("● "
                + displayState.getLabel().toUpperCase(java.util.Locale.ROOT));
        tvConnectionStatus.setTextColor(androidx.core.content.ContextCompat.getColor(
                requireContext(), displayState.getColorRes()));

        if (tvConnectionDetail != null) {
            if (displayState == DeviceConnectivityState.ONLINE) {
                tvConnectionDetail.setText("Connected to Basilience cloud");
            } else if (displayState == DeviceConnectivityState.WIFI_CONFIGURATION_REQUIRED) {
                tvConnectionDetail.setText("Device is powered and running locally.\nConnect it to Wi-Fi to restore cloud monitoring.");
            } else if (displayState == DeviceConnectivityState.OFFLINE) {
                tvConnectionDetail.setText("Basilience cannot communicate with the device.\nCheck its power or network connection.");
            } else {
                tvConnectionDetail.setText("Restoring Basilience cloud connection...");
            }
        }

        if (btnRetryWifiConfiguration != null) {
            btnRetryWifiConfiguration.setVisibility(setupApReachable ? View.VISIBLE : View.GONE);
        }

        View v = getView();
        if (v != null) {
            SwitchMaterial modeSwitch = v.findViewById(R.id.switchMode);
            if (modeSwitch != null) {
                boolean modeSwitchEnabled = isAdminUser() && isCurrentlyOnline;
                modeSwitch.setEnabled(modeSwitchEnabled);
                modeSwitch.setAlpha(modeSwitchEnabled ? 1.0f : 0.6f);
            }
        }

        updateActuatorControls();
    }

    private void confirmSetupApReachability(String deviceId) {
        if (setupApCheckInProgress || isCurrentlyOnline
                || connectivityExecutor == null || connectivityExecutor.isShutdown()) return;
        mainHandler.removeCallbacks(setupApRecheck);
        setupApCheckInProgress = true;
        android.content.Context appContext = requireContext().getApplicationContext();
        connectivityExecutor.execute(() -> {
            boolean reachable = LocalProvisioningClient.isSetupApReachable(appContext);
            mainHandler.post(() -> {
                setupApCheckInProgress = false;
                if (!isAdded() || isCurrentlyOnline || !isStillSelectedDevice(deviceId)) return;
                setupApReachable = reachable;
                if (reachable) {
                    NotificationHelper.showWifiConfigurationRequiredNotification(
                            requireContext(), deviceId);
                    MainActivity.onLocalSetupApConfirmed(deviceId);
                }
                updateConnectionUI();
                mainHandler.postDelayed(setupApRecheck, SETUP_AP_RECHECK_INTERVAL_MS);
            });
        });
    }

    private boolean isStillSelectedDevice(String deviceId) {
        if (deviceId == null || !deviceId.equals(selectedDeviceId)) return false;
        String current = requireContext().getSharedPreferences(
                "basilience_prefs", android.content.Context.MODE_PRIVATE)
                .getString("selected_device_id", null);
        return deviceId.equals(current);
    }

    @Override
    public void onDestroyView() {
        actuatorCommandFinished = true;
        actuatorCommandGeneration++;
        if (actuatorCommandRunnable != null) {
            mainHandler.removeCallbacks(actuatorCommandRunnable);
            actuatorCommandRunnable = null;
        }
        mainHandler.removeCallbacksAndMessages(null);
        if (connectivityExecutor != null) {
            connectivityExecutor.shutdownNow();
            connectivityExecutor = null;
        }
        isActuatorBusy = false;
        if (sensorRepository != null) {
            sensorRepository.stopListening();
        }
        if (alertsRef != null && alertsListener != null) {
            alertsRef.removeEventListener(alertsListener);
        }
        if (statusRef != null && statusListener != null) {
            statusRef.removeEventListener(statusListener);
        }
        if (manualModeRef != null && manualModeListener != null) {
            manualModeRef.removeEventListener(manualModeListener);
        }
        if (actuatorStatusRef != null && actuatorStatusListener != null) {
            actuatorStatusRef.removeEventListener(actuatorStatusListener);
        }
        if (highWaterTempRef != null && highWaterTempListener != null) {
            highWaterTempRef.removeEventListener(highWaterTempListener);
        }
        if (cycleListener != null) {
            cycleListener.remove();
            cycleListener = null;
        }
        super.onDestroyView();
    }
}
