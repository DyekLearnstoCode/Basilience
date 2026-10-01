package com.example.basilience;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import androidx.core.content.ContextCompat;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;
import com.example.basilience.repository.SensorRepository;
import com.example.basilience.models.SensorData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;

import java.util.ArrayList;
import java.util.Arrays;
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

    private static final String SENSOR_UI_TAG = "SENSOR-UI";

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
    /**
     * Configured target range per parameter as {min, max} - what decides
     * whether a shown reading is red ("Below Range"/"Above Range"). Starts at
     * the compiled defaults, which mirror the firmware's own, and is replaced
     * by the device's settings/{minKey,maxKey} values as they load. Only ever
     * touched on the main thread (Firebase callbacks and updateSensorUI()).
     */
    private final java.util.EnumMap<ParameterTargetRanges, double[]> targetRanges =
            new java.util.EnumMap<>(ParameterTargetRanges.class);
    {
        for (ParameterTargetRanges parameter : ParameterTargetRanges.values()) {
            targetRanges.put(parameter, new double[]{parameter.defaultMin, parameter.defaultMax});
        }
    }
    private DatabaseReference targetRangesRef;
    private ValueEventListener targetRangesListener;

    // Directional firmware alert flags, kept so a manual action can tell which
    // way the parameter is actually off target. They no longer drive the
    // reading colour - that comes from the configured target range above.
    private final ManualOverrideAdvisor.AlertFlags overrideFlags = new ManualOverrideAdvisor.AlertFlags();
    private boolean alertsLoaded = false;
    /** Configured cooling ceiling from settings/maxWaterTemp; null when not configured. */
    private Double configuredHighWaterTemp = null;
    /** status/currentMode, phDirection, ecDirection and the subsystem/global locks - all from the same statusListener below. */
    private final ManualOverrideAdvisor.OperationContext operationContext = new ManualOverrideAdvisor.OperationContext();

    // status/currentMode ordinals - mirrors firmware's SystemMode enum
    // (Types.h), same values ManualOverrideAdvisor.java already duplicates
    // for its own currentMode checks (that copy is private to that class).
    private static final int MODE_REFILLING = 3;
    private static final int MODE_DOSING_PH = 4;
    private static final int MODE_STABILIZING_PH = 5;
    private static final int MODE_DOSING_EC = 6;
    private static final int MODE_STABILIZING_EC = 7;

    private StabilizeLoader phLoader, ecLoader, waterLevelLoader;

    /**
     * Drives one parameter card's "reading is currently untrustworthy"
     * loader: a one-time "Stabilizing"/"Refilling" popup the first time the
     * card goes unstable, then a static loader (a "Stabilizing..." label on
     * the pH/EC cards, a spinner on the water level row) replacing the
     * value/status text until it becomes trustworthy again. There is
     * deliberately no seconds countdown: the firmware's remaining-time
     * value is re-estimated as the correction proceeds, so it jumped
     * around (80s, 70s, 75s) instead of counting down. See the "Hide
     * unreliable readings during dosing/refilling" plan for the full design.
     */
    private final class StabilizeLoader {
        private final View valueView;
        private final View statusView;
        private final View loaderContainer;
        private final CircularProgressIndicator progress;
        private final String popupTitle;
        private final String popupMessage;

        private boolean active = false;
        private boolean popupShown = false;

        StabilizeLoader(View card, View valueView, View statusView,
                         String popupTitle, String popupMessage) {
            this.valueView = valueView;
            this.statusView = statusView;
            this.loaderContainer = card.findViewById(R.id.stabilizingLoader);
            this.progress = card.findViewById(R.id.progressStabilizing);
            this.popupTitle = popupTitle;
            this.popupMessage = popupMessage;
            if (progress != null) {
                progress.setIndeterminate(true);
            }
        }

        /** Called on every fresh /status snapshot with this parameter's current unstable state. */
        void update(boolean unstable) {
            if (loaderContainer == null) return;

            if (!unstable) {
                if (active) stop();
                popupShown = false;
                return;
            }

            if (!active) {
                active = true;
                if (!popupShown) {
                    popupShown = true;
                    // Never during the guided tour: NotificationHelper's dialog opens
                    // in its own Window, which always draws above CoachMarkTour's
                    // content-attached overlay, so it would interrupt/cover the tour
                    // instead of being blocked by it. Go straight to the loader
                    // instead - popupShown still latches true so this episode's
                    // acknowledgement doesn't appear later either, once the tour ends.
                    if (coachMarkTour != null && coachMarkTour.isActive()) {
                        show();
                    } else {
                        NotificationHelper.showAutomationAcknowledgement(
                                requireContext(), popupTitle, popupMessage, this::show);
                    }
                } else {
                    // Popup already shown earlier in this same episode (e.g.
                    // fragment view was recreated mid-episode) - go straight
                    // to the loader.
                    show();
                }
            }
        }

        private void show() {
            // !active: the popup can be dismissed after the reading already
            // became trustworthy again (stop() ran) - showing the loader then
            // would hide the value with nothing left to restore it.
            if (!isAdded() || !active) return;
            valueView.setVisibility(View.GONE);
            statusView.setVisibility(View.GONE);
            loaderContainer.setVisibility(View.VISIBLE);
        }

        private void stop() {
            active = false;
            loaderContainer.setVisibility(View.GONE);
            valueView.setVisibility(View.VISIBLE);
            statusView.setVisibility(View.VISIBLE);
        }
    }
    private DatabaseReference highWaterTempRef;
    private ValueEventListener highWaterTempListener;

    /**
     * Live pH candidate straight from the ADC sampler (debug/physicalSensors/ph
     * - the same source Developer Options' Sensor Test reads), independent of
     * whether the pH stability window has accepted it yet. UI status only -
     * see updateSensorUI()'s own comment. null when no candidate has ever
     * been produced (e.g. sensor genuinely unavailable), never used for any
     * automation/control decision.
     */
    private Double physicalPhCandidate = null;
    private DatabaseReference physicalSensorsPhRef;
    private ValueEventListener physicalSensorsPhListener;

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
        // Mirrors actuatorStatus/{key}/speed (0-100 PWM duty). Canopy Fan and
        // Reservoir Fan are PWM-capable, but their speed is decided entirely
        // by firmware now - there is no app-side speed control (see
        // setupActuatorUI()'s own comment) - so every actuator tracked here
        // just displays whatever speed firmware last published.
        int speed = 100;

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
    // The full Admin/Farmer-guarded switch listener rebuilt on every
    // manualModeListener RTDB echo (see below) - kept as a field, not just a
    // local, so onModeSwitchChanged() and writeManualMode()'s failure path
    // can always re-arm the switch back to this SAME guarded listener
    // instead of ever temporarily downgrading to a weaker one.
    private android.widget.CompoundButton.OnCheckedChangeListener guardedModeSwitchListener;
    private DatabaseReference actuatorStatusRef;
    private ValueEventListener actuatorStatusListener;
    private DatabaseReference manualControlGrantsRef;
    private ValueEventListener manualControlGrantsListener;
    private DatabaseReference manualModeEnabledAtRef;
    private ValueEventListener manualModeEnabledAtListener;
    // Admin side: which farmer uids already have an approval dialog showing/
    // shown for their current PENDING request, so the same request doesn't
    // re-prompt on every unrelated snapshot update to this node.
    private final java.util.Set<String> grantDialogShownForUid = new java.util.HashSet<>();
    // Farmer side: this device's own manual-control grant, kept live by
    // manualControlGrantsListener - null/"" status means no active request.
    private String myGrantStatus = "";
    private long myGrantResolvedAt = 0L;
    private long manualModeEnabledAt = 0L;
    // True once manualControlGrantsListener has delivered its first snapshot
    // for this Fragment instance - the very first callback is just catching
    // up on whatever the grant already was before this screen opened, not a
    // live change, so it must not trigger the DENIED/REVOKED/APPROVED
    // toasts below (myGrantStatus otherwise always starts at "" for a fresh
    // instance, making an already-APPROVED grant look like a brand new
    // transition every single time this screen is reopened).
    private boolean manualControlGrantsFirstLoadDone = false;

    private static final long SETUP_AP_RECHECK_INTERVAL_MS = 15_000L;
    private TextView tvConnectionStatus;
    private TextView tvConnectionDetail;
    private MaterialButton btnRetryWifiConfiguration;
    private SensorRepository sensorRepository;
    private final MutableLiveData<SensorData> sensorLiveData = new MutableLiveData<>();
    private final MutableLiveData<Boolean> sensorReadErrorLiveData = new MutableLiveData<>();
    private TextView tvSensorDataNotice;

    // Coherent initial sensor snapshot (see the real-time sensor
    // presentation task report). Individual sensor cards are withheld from
    // updateSensorUI() until the first reveal, so the screen never shows
    // pH/EC/temperature/humidity appearing one at a time as separate
    // Firebase reads settle - see sensorLiveData.observe()'s callback.
    // sensorsRevealed latches true permanently on the first reveal for this
    // fragment view's lifetime; later per-sensor staleness is handled by
    // updateSensorUI()'s own existing per-field "--"/"Stabilizing..." logic,
    // never by re-showing this overlay.
    // Quick-response refinement task: was 8s - firmware readiness no longer
    // waits out SENSOR_STABILIZATION_TIME (60s), so a fresh boot now
    // normally reports ready within ~1-2s (SENSOR_READY_MIN_MS/MAX_MS on
    // the firmware side); this is now purely the hard UI fallback for a
    // missing/stuck snapshot, not the expected normal wait.
    private static final long SENSOR_STABILIZING_TIMEOUT_MS = 3_000L;
    private View sensorStabilizingOverlay;
    private boolean sensorsRevealed = false;
    private Boolean lastLoggedSensorReady;
    private final Runnable sensorStabilizingTimeoutRunnable =
            () -> revealSensors("revealing after 3000ms fallback");
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

    private CoachMarkTour coachMarkTour;

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

        ImageView btnActuatorInfo = view.findViewById(R.id.btnActuatorInfo);
        if (btnActuatorInfo != null) {
            btnActuatorInfo.setOnClickListener(v -> showActuatorInfoDialog());
        }

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
        sensorStabilizingOverlay = view.findViewById(R.id.sensorStabilizingOverlay);
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
                // Coherent initial reveal (see sensorsRevealed's own
                // comment): before the first reveal, a snapshot only
                // triggers updateSensorUI() if it is itself the coherent
                // "ready" snapshot AND the device is actually online right
                // now - reusing the existing connectivity signal rather
                // than trying to judge freshness from firmware's
                // device-uptime updatedAt value on the app's own wall
                // clock (see Part G of the task report: no second,
                // competing freshness system). Any other incoming snapshot
                // before the first reveal is simply cached in
                // sensorLiveData for revealSensors()/the bounded timeout to
                // use later - never rendered piecemeal.
                boolean ready = sensorData != null && sensorData.sensorState != null
                        && Boolean.TRUE.equals(sensorData.sensorState.ready);
                if (sensorData != null && (lastLoggedSensorReady == null
                        || lastLoggedSensorReady != ready)) {
                    Log.d(SENSOR_UI_TAG, "[SENSOR-UI] sensor snapshot received: ready="
                            + ready + ", online=" + isCurrentlyOnline);
                    lastLoggedSensorReady = ready;
                }

                if (!sensorsRevealed) {
                    if (ready && isCurrentlyOnline) {
                        revealSensors("revealing early: firmware ready");
                    }
                    return;
                }
                updateSensorUI();
            }
        });

        sensorReadErrorLiveData.observe(getViewLifecycleOwner(), hasError -> {
            if (tvSensorDataNotice != null) {
                tvSensorDataNotice.setVisibility(Boolean.TRUE.equals(hasError) ? View.VISIBLE : View.GONE);
            }
            if (Boolean.TRUE.equals(hasError) && !sensorsRevealed) {
                revealSensors("sensor read failed; revealing cards with unavailable state");
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
                                            if (!isAdded()) return;
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
                    // Mirrors firmware's own reset-safety validation
                    // (FirebaseManager.cpp's operation-request validator,
                    // OperationType::RESET_SAFETY case) so a request firmware
                    // will predictably reject - because nothing is actually
                    // locked - never gets sent at all. Confirmed live bug:
                    // firmware correctly returns REJECTED with "No safety
                    // subsystem is locked.", but the app never checked this
                    // client-side, so the confirmation dialog and loading
                    // spinner appeared for a reset there was nothing to do.
                    if (!isSafetyLock && !operationContext.phSubsystemLocked
                            && !operationContext.ecSubsystemLocked
                            && !operationContext.refillSubsystemLocked
                            && !operationContext.coolingSubsystemLocked) {
                        NotificationHelper.showInfo(requireContext(), "Nothing to Reset",
                                "No safety lock is currently active.");
                        return;
                    }
                    NotificationHelper.showConfirmation(requireContext(),
                            "Reset Safety Lock",
                            "Are you sure you want to reset the safety lock? This will return the system to normal operations.",
                            "Yes", "No", () -> {
                                isActuatorBusy = true;
                                updateActuatorControls();
                                showActuatorLoading("Sending request...", "");
                                dbHelper.sendOperationRequest("RESET_SAFETY", "START")
                                        .addOnSuccessListener(requestId -> {
                                            pollOperationUntilDone(requestId, "Reset Safety", 0);
                                        })
                                        .addOnFailureListener(e -> {
                                            if (!isAdded()) return;
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

    /**
     * Shows the guided Monitoring walkthrough once, the first time this
     * screen is ever shown. Called from revealSensors() rather than
     * onViewCreated() - firing it immediately raced with
     * sensorStabilizingOverlay, which covers the whole screen for up to 3s
     * (or until sensor data is ready) on every fresh entry to this screen,
     * so the tour would start while its own spotlight target was still
     * hidden behind that loading state.
     */
    private void maybeShowCoachMarkTour() {
        View view = getView();
        if (view == null) return;

        SharedPreferences prefs = requireContext()
                .getSharedPreferences("basilience_prefs", android.content.Context.MODE_PRIVATE);
        if (prefs.getBoolean("has_seen_monitoring_tour", false)) {
            return;
        }
        prefs.edit().putBoolean("has_seen_monitoring_tour", true).apply();

        View sensorReadingsSection = view.findViewById(R.id.sensorReadingsSection);
        View actuatorListContainer = view.findViewById(R.id.actuatorListContainer);
        View btnActuatorInfo = view.findViewById(R.id.btnActuatorInfo);

        List<CoachMarkTour.Step> steps = Arrays.asList(
                new CoachMarkTour.Step(sensorReadingsSection, "Sensor readings",
                        "Live pH, EC, temperature, humidity, and more, updated automatically."),
                new CoachMarkTour.Step(actuatorListContainer, "Pumps, fans, and lights",
                        "See what's currently running. Your Admin can turn on Manual Mode to control these by hand."),
                new CoachMarkTour.Step(btnActuatorInfo, "Need more detail?",
                        "Tap this any time for a full explanation of actuator status."));

        coachMarkTour = new CoachMarkTour(requireActivity(), getViewLifecycleOwner(),
                requireActivity().getOnBackPressedDispatcher(), steps, null);
        coachMarkTour.start();
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
            phLoader = new StabilizeLoader(cardPH, tvPH, tvPHStatus,
                    "Stabilizing",
                    "pH is currently being corrected. The reading will be hidden until it settles. This usually takes about a minute and a half.");
        }
        if (cardEC != null) {
            tvEC = cardEC.findViewById(R.id.tvValue);
            tvECStatus = cardEC.findViewById(R.id.tvStatus);
            TextView label = cardEC.findViewById(R.id.tvLabel);
            if (label != null) label.setText("EC");
            ImageView icon = cardEC.findViewById(R.id.imgIcon);
            if (icon != null) icon.setImageResource(R.drawable.ic_ec);
            ecLoader = new StabilizeLoader(cardEC, tvEC, tvECStatus,
                    "Stabilizing",
                    "EC is currently being corrected. The reading will be hidden until it settles. This usually takes about a minute and a half.");
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
            waterLevelLoader = new StabilizeLoader(cardWaterLevel, tvWaterLevel, tvWaterLevelStatus,
                    "Refilling",
                    "The reservoir is currently refilling. The water level reading will be hidden until the refill finishes.");
        }
    }

    private void startRealTimeMonitoring() {
        if (getContext() == null) return;
        SharedPreferences prefs = requireContext().getSharedPreferences("basilience_prefs", android.content.Context.MODE_PRIVATE);
        String deviceId = prefs.getString("selected_device_id", null);
        selectedDeviceId = deviceId;

        // The latch belongs to the Fragment's view, not the Fragment instance.
        // A Fragment can retain its instance while Navigation destroys and
        // recreates the view, so every new monitoring view needs a fresh reveal
        // window and timeout. Arm it before validating the selected device so
        // a missing selection cannot leave the layout's overlay up forever.
        sensorsRevealed = false;
        lastLoggedSensorReady = null;
        Log.d(SENSOR_UI_TAG, "[SENSOR-UI] monitoring view created");

        if (deviceId == null) {
            // Previously logged only (Log.e, no toast, no exit) - the screen
            // then rendered as if functional with the reveal overlay/timeout
            // never armed and monitoring never started, leaving the user on
            // a permanently non-functional screen with zero explanation.
            // Matches the same recoverable-exit fix applied to
            // DevOptionsFragment/ParameterTargetRangesFragment.
            Log.e("Monitoring", "No device selected");
            if (getContext() != null) {
                Toast.makeText(getContext(), "No device selected", Toast.LENGTH_SHORT).show();
            }
            View currentView = getView();
            if (currentView != null) {
                Navigation.findNavController(currentView).popBackStack();
            }
            return;
        }

        dbHelper.setSelectedDeviceId(deviceId);
        if (getView() != null) {
            NotificationHelper.bindDeviceLabel(getView().findViewById(R.id.tvDeviceScopeLabel), deviceId);
        }

        // Defensive, not redundant: monitorDevice() only ever gets triggered
        // centrally from MainActivity (on its own onCreate() and on a
        // selected_device_id SharedPreferences change) - this screen has no
        // other way to (re-)establish it if that trigger was ever missed, and
        // no way to detect that it was. The call is a same-device no-op when
        // monitoring is already correctly running (see monitorDevice()'s own
        // guard), so this can never duplicate or restart a healthy listener -
        // it only self-heals a stuck one. Confirmed live-bug fix: this screen
        // was observed stuck on RECONNECTING while Device Management's own,
        // independent per-row listener already showed the true state.
        DeviceConnectionManager.getInstance().monitorDevice(deviceId);

        // Coherent initial reveal (see sensorsRevealed's own comment) -
        // shown once, here, before the first Firebase read can possibly
        // arrive; revealSensors() (via a ready snapshot or this bounded
        // timeout) is the only thing that ever hides it again.
        if (sensorStabilizingOverlay != null) {
            sensorStabilizingOverlay.setVisibility(View.VISIBLE);
            sensorStabilizingOverlay.bringToFront();
            setGlobalDimOverlayVisible(true);
        }
        mainHandler.removeCallbacks(sensorStabilizingTimeoutRunnable);
        mainHandler.postDelayed(sensorStabilizingTimeoutRunnable, SENSOR_STABILIZING_TIMEOUT_MS);
        Log.d(SENSOR_UI_TAG, "[SENSOR-UI] stabilization fallback armed: 3000ms");

        // Do not let a snapshot cached for a previous selected device satisfy
        // this view's fresh reveal window.
        sensorLiveData.setValue(null);
        sensorReadErrorLiveData.setValue(false);

        // deviceId is guaranteed non-null past the guard above - dropped a
        // second, unreachable duplicate of that same check that used to sit
        // here (deviceId is never reassigned in between).
        sensorRepository.startListening(deviceId, sensorLiveData, sensorReadErrorLiveData);
        if (!isCurrentlyOnline) confirmSetupApReachability(deviceId);

        View v = getView();
        if (v != null) {
            SwitchMaterial modeSwitch = v.findViewById(R.id.switchMode);
            if (modeSwitch != null) {
                // Admin's tap actually flips manualMode, which only makes sense
                // while the device is online. A Farmer's tap never changes
                // manualMode itself (the listener always snaps it back) - it only
                // requests/reports on their own grant, which is pure RTDB
                // bookkeeping independent of the device's current connectivity,
                // so their tap must always reach the listener regardless of
                // isCurrentlyOnline. Leaving this Android-disabled for a Farmer
                // (as it was before) would silently swallow their tap before it
                // ever reaches that listener.
                boolean modeSwitchEnabled = isAdminUser() ? isCurrentlyOnline : true;
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
        // Firmware derives its cooling ON threshold from maxWaterTemp on every
        // control tick. highWaterTemp is a legacy mirrored field and can lag
        // after target-range edits, so the advisor must read the authoritative
        // setting the controller actually consumes.
        highWaterTempRef = deviceRef.child("settings").child("maxWaterTemp");
        highWaterTempListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!isAdded()) return;
                Object value = snapshot.getValue();
                configuredHighWaterTemp = value instanceof Number ? ((Number) value).doubleValue() : null;
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                configuredHighWaterTemp = null;
                handleAccessRevoked("highWaterTemp", error);
            }
        };
        highWaterTempRef.addValueEventListener(highWaterTempListener);

        // Part B (real-hardware pre-integration follow-up): lets the pH card
        // show "Stabilizing..." instead of an indistinguishable "--" while a
        // live candidate exists but hasn't been accepted into /sensors/ph
        // yet - see updateSensorUI()'s own comment. Read-only, UI status
        // only; automation continues to use only /sensors/ph.
        physicalSensorsPhRef = deviceRef.child("debug").child("physicalSensors").child("ph");
        physicalSensorsPhListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!isAdded()) return;
                Object value = snapshot.getValue();
                physicalPhCandidate = value instanceof Number ? ((Number) value).doubleValue() : null;
                updateSensorUI();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                physicalPhCandidate = null;
                handleAccessRevoked("physicalSensors/ph", error);
            }
        };
        physicalSensorsPhRef.addValueEventListener(physicalSensorsPhListener);

        alertsRef = deviceRef.child("alerts");
        alertsListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!isAdded()) return;

                // Firmware-published alert truth, used only by the manual
                // override advisor to tell which way a parameter is off target.
                // It does NOT colour the readings: the firmware confirms an
                // alert over several samples and releases it with hysteresis,
                // so it lags the number on screen. The colour comes from the
                // configured target range instead - see targetRangesListener.
                // A flag that is present but the wrong type is unknown, not
                // "inactive": it keeps the last shown state so a malformed value
                // can never make an alert look recovered. A missing flag still
                // means not active, as before.
                overrideFlags.phLow = isAlertActive(snapshot, "phLow", overrideFlags.phLow);
                overrideFlags.phHigh = isAlertActive(snapshot, "phHigh", overrideFlags.phHigh);
                overrideFlags.ecLow = isAlertActive(snapshot, "ecLow", overrideFlags.ecLow);
                overrideFlags.lowWater = isAlertActive(snapshot, "lowWater", overrideFlags.lowWater);
                alertsLoaded = true;
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                handleAccessRevoked("alerts", error);
            }
        };
        alertsRef.addValueEventListener(alertsListener);

        // The configured min/max each reading is judged against. Live, so
        // editing a range in Settings recolours the cards straight away.
        targetRangesRef = deviceRef.child("settings");
        targetRangesListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!isAdded()) return;
                for (ParameterTargetRanges parameter : ParameterTargetRanges.values()) {
                    // A missing or malformed value falls back to the compiled
                    // default the firmware itself uses in that case.
                    Double min = FirebaseSafeRead.dbl(snapshot.child(parameter.minKey));
                    Double max = FirebaseSafeRead.dbl(snapshot.child(parameter.maxKey));
                    targetRanges.put(parameter, new double[]{
                            min != null ? min : parameter.defaultMin,
                            max != null ? max : parameter.defaultMax});
                }
                updateSensorUI();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                handleAccessRevoked("targetRanges", error);
            }
        };
        targetRangesRef.addValueEventListener(targetRangesListener);

        statusRef = dbHelper.getStatusReference();
        statusListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!isAdded()) return;

                // Sync status: reservoirLocked & safetyLock
                Boolean reservoirLocked = FirebaseSafeRead.bool(snapshot.child("reservoirLocked"));
                if (reservoirLocked != null) {
                    isReservoirLocked = reservoirLocked;
                    operationContext.reservoirLocked = reservoirLocked;
                }

                Boolean safetyLock = FirebaseSafeRead.bool(snapshot.child("safetyLock"));
                if (safetyLock != null) {
                    isSafetyLock = safetyLock;
                    operationContext.safetyLock = safetyLock;
                    updateConnectionUI();
                }

                // Operation-aware manual-command validation (ManualOverrideAdvisor.evaluateCommand)
                // reads the rest of this same node - none of these are a new
                // RTDB path, just fields on /status this listener wasn't
                // pulling out before.
                Integer currentMode = FirebaseSafeRead.integer(snapshot.child("currentMode"));
                operationContext.currentMode = currentMode != null ? currentMode : -1;
                operationContext.phDirection = FirebaseSafeRead.str(snapshot.child("phDirection"));
                operationContext.ecDirection = FirebaseSafeRead.str(snapshot.child("ecDirection"));
                operationContext.phSubsystemLocked = Boolean.TRUE.equals(FirebaseSafeRead.bool(snapshot.child("phSubsystemLocked")));
                operationContext.ecSubsystemLocked = Boolean.TRUE.equals(FirebaseSafeRead.bool(snapshot.child("ecSubsystemLocked")));
                operationContext.refillSubsystemLocked = Boolean.TRUE.equals(FirebaseSafeRead.bool(snapshot.child("refillSubsystemLocked")));
                operationContext.coolingSubsystemLocked = Boolean.TRUE.equals(FirebaseSafeRead.bool(snapshot.child("coolingSubsystemLocked")));

                // Stabilizing-loader state - see StabilizeLoader's own
                // comment. Same /status node, just fields this listener
                // wasn't pulling out before.
                int mode = operationContext.currentMode;
                boolean phWatchPhaseActive = Boolean.TRUE.equals(FirebaseSafeRead.bool(snapshot.child("phWatchPhaseActive")));
                boolean ecWatchPhaseActive = Boolean.TRUE.equals(FirebaseSafeRead.bool(snapshot.child("ecWatchPhaseActive")));

                boolean phUnstable = mode == MODE_DOSING_PH
                        || (mode == MODE_STABILIZING_PH && !phWatchPhaseActive);
                boolean ecUnstable = mode == MODE_DOSING_EC
                        || (mode == MODE_STABILIZING_EC && !ecWatchPhaseActive);
                boolean waterLevelUnstable = mode == MODE_REFILLING;

                if (phLoader != null) phLoader.update(phUnstable);
                if (ecLoader != null) ecLoader.update(ecUnstable);
                if (waterLevelLoader != null) waterLevelLoader.update(waterLevelUnstable);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                handleAccessRevoked("status", error);
            }
        };
        statusRef.addValueEventListener(statusListener);

        manualModeRef = deviceRef.child("commands").child("manualMode");
        manualModeListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!isAdded()) return;

                // Sync Manual Mode from RTDB
                Boolean manualMode = FirebaseSafeRead.bool(snapshot);
                if (manualMode != null) {
                    isManualMode = manualMode;
                    SwitchMaterial modeSwitch = getView() != null ? getView().findViewById(R.id.switchMode) : null;
                    if (modeSwitch != null) {
                        modeSwitch.setOnCheckedChangeListener(null);
                        modeSwitch.setChecked(isManualMode);
                        modeSwitch.setText("Manual Mode");

                        // A field (not a local), so every re-arm point below -
                        // including writeManualMode()'s failure-recovery path -
                        // can always restore this SAME guarded listener rather
                        // than ever temporarily downgrading to a weaker one.
                        guardedModeSwitchListener = (buttonView, checked) -> {
                            if (!isAdminUser()) {
                                // Turning Manual Mode ON is still Admin-only,
                                // always - Database_Helper.updateManualMode()
                                // and the RTDB commands/manualMode rule both
                                // still enforce that unconditionally. A Farmer
                                // with an active grant CAN turn it back OFF
                                // though, ending their own session without
                                // waiting on an Admin (see endManualModeSession()
                                // and the matching commands/manualMode rule
                                // clause: newData.val() === false only). Snap
                                // the switch back first either way - the real
                                // value only ever changes once the write this
                                // branch triggers actually lands and
                                // manualModeListener picks it up.
                                boolean attemptingTurnOff = isManualMode && !checked;
                                modeSwitch.setOnCheckedChangeListener(null);
                                modeSwitch.setChecked(isManualMode);
                                modeSwitch.setOnCheckedChangeListener(guardedModeSwitchListener);

                                if (attemptingTurnOff && hasActiveGrant()) {
                                    NotificationHelper.showConfirmation(requireContext(), "Turn Off Manual Mode",
                                            "End your manual control session? Pumps, fans, and lights will return to automatic control.",
                                            "Turn Off", "Cancel", () -> {
                                                dbHelper.endManualModeSession()
                                                        .addOnFailureListener(e -> {
                                                            if (!isAdded()) return;
                                                            NotificationHelper.showError(requireContext(),
                                                                    "Unable to turn off Manual Mode. Please try again.");
                                                        });
                                            });
                                } else if (isManualMode && hasActiveGrant()) {
                                    // hasActiveGrant() alone only means "my APPROVED
                                    // record is still valid for whatever session
                                    // manualModeEnabledAt currently points at" - it
                                    // says nothing about whether Manual Mode is
                                    // actually on right now. A grant can outlive a
                                    // session that already ended (e.g. this Farmer
                                    // just used endManualModeSession() above, or an
                                    // Admin turned it off) without being cleared,
                                    // since nothing resets manualControlGrants when
                                    // manualMode flips off. Gating on isManualMode
                                    // too is what stops that stale-but-technically-
                                    // valid record from telling the Farmer they
                                    // already have access when nothing is actually
                                    // on - the next real updateManualMode(true) always
                                    // stamps a fresh manualModeEnabledAt anyway, so
                                    // this old grant will need re-approval regardless.
                                    Toast.makeText(getContext(), "You already have manual control access.", Toast.LENGTH_SHORT).show();
                                } else if ("PENDING".equals(myGrantStatus)) {
                                    Toast.makeText(getContext(), "Your request is waiting for an Admin to approve it.", Toast.LENGTH_SHORT).show();
                                } else if (!isCurrentlyOnline) {
                                    // Matches Admin's own switch, which is
                                    // Android-disabled outright while offline -
                                    // no point asking for control of a device
                                    // that isn't there right now.
                                    Toast.makeText(getContext(), "Device is offline. Manual control isn't available right now.", Toast.LENGTH_LONG).show();
                                } else {
                                    NotificationHelper.showConfirmation(requireContext(), "Request Manual Control",
                                            "Ask an Admin to let you control pumps, fans, and lights by hand?",
                                            "Request", "Cancel", () -> {
                                                dbHelper.requestManualControlAccess()
                                                        .addOnFailureListener(e -> {
                                                            if (!isAdded()) return;
                                                            NotificationHelper.showError(requireContext(),
                                                                    "Unable to send the request. Please try again.");
                                                        });
                                                Toast.makeText(getContext(), "Request sent. Waiting for an Admin to approve it.", Toast.LENGTH_LONG).show();
                                            });
                                }
                                return;
                            }
                            if (checked) {
                                // Snap back and show confirmation
                                modeSwitch.setOnCheckedChangeListener(null);
                                modeSwitch.setChecked(false);
                                modeSwitch.setOnCheckedChangeListener(guardedModeSwitchListener);

                                String title = "Enable Manual Mode";
                                String message = "Manual Mode lets an Admin control system functions directly. Built-in safety checks remain active, so some actions may be prevented when conditions are unsafe. Are you sure you want to proceed?";

                                if (isReservoirLocked) {
                                    title = "Automatic Operation Active";
                                    message = "The system is currently performing an automatic operation (e.g. refilling, dosing). Enabling manual mode will abort it.\n\nDo you want to continue?";
                                }

                                NotificationHelper.showConfirmation(requireContext(), title, message, "Enable", "Cancel", () -> {
                                    isManualMode = true;
                                    modeSwitch.setOnCheckedChangeListener(null);
                                    modeSwitch.setChecked(true);
                                    modeSwitch.setOnCheckedChangeListener(guardedModeSwitchListener);
                                    updateActuatorControls();
                                    writeManualMode(modeSwitch, true);
                                });
                                return;
                            }

                            // Turning Manual Mode OFF (Admin): snap back and confirm
                            // first, same treatment as turning it ON above - this used
                            // to go straight through to onModeSwitchChanged() with no
                            // prompt at all.
                            modeSwitch.setOnCheckedChangeListener(null);
                            modeSwitch.setChecked(true);
                            modeSwitch.setOnCheckedChangeListener(guardedModeSwitchListener);

                            NotificationHelper.showConfirmation(requireContext(), "Turn Off Manual Mode",
                                    "Pumps, fans, and lights will return to automatic control. Continue?",
                                    "Turn Off", "Cancel", () -> {
                                        modeSwitch.setOnCheckedChangeListener(null);
                                        modeSwitch.setChecked(false);
                                        modeSwitch.setOnCheckedChangeListener(guardedModeSwitchListener);
                                        onModeSwitchChanged(modeSwitch, false);
                                    });
                        };
                        modeSwitch.setOnCheckedChangeListener(guardedModeSwitchListener);
                    }
                    updateActuatorControls();
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                handleAccessRevoked("commands/manualMode", error);
            }
        };
        manualModeRef.addValueEventListener(manualModeListener);

        manualControlGrantsRef = deviceRef.child("manualControlGrants");
        manualControlGrantsListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!isAdded()) return;

                if (isAdminUser()) {
                    // Show (once each) an approval prompt for every farmer's new
                    // PENDING request - retainAll lets a LATER new request from
                    // the same farmer prompt again once their current one resolves.
                    java.util.Set<String> stillPending = new java.util.HashSet<>();
                    for (DataSnapshot child : snapshot.getChildren()) {
                        String uid = child.getKey();
                        String status = FirebaseSafeRead.str(child.child("status"));
                        if (uid == null || !"PENDING".equals(status)) continue;
                        stillPending.add(uid);
                        if (grantDialogShownForUid.add(uid)) {
                            promptAdminForGrant(uid);
                        }
                    }
                    grantDialogShownForUid.retainAll(stillPending);
                } else {
                    String myUid = dbHelper.getCurrentUid();
                    DataSnapshot mine = myUid != null ? snapshot.child(myUid) : null;
                    String previousStatus = myGrantStatus;
                    String newStatus = mine != null ? FirebaseSafeRead.str(mine.child("status")) : null;
                    myGrantStatus = newStatus != null ? newStatus : "";
                    Long resolvedAt = mine != null ? FirebaseSafeRead.lng(mine.child("resolvedAt")) : null;
                    myGrantResolvedAt = resolvedAt != null ? resolvedAt : 0L;

                    if (manualControlGrantsFirstLoadDone && !myGrantStatus.equals(previousStatus)) {
                        if ("DENIED".equals(myGrantStatus)) {
                            NotificationHelper.showError(requireContext(), "Request Denied",
                                    "An Admin denied your manual control request.");
                        } else if ("REVOKED".equals(myGrantStatus)) {
                            NotificationHelper.showError(requireContext(), "Access Ended",
                                    "An Admin ended your manual control access.");
                        } else if (hasActiveGrant()) {
                            Toast.makeText(getContext(), "Manual control access approved.", Toast.LENGTH_LONG).show();
                        }
                    }
                    manualControlGrantsFirstLoadDone = true;
                    updateActuatorControls();
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                handleAccessRevoked("manualControlGrants", error);
            }
        };
        manualControlGrantsRef.addValueEventListener(manualControlGrantsListener);

        // Marks the start of the current Manual Mode "session" - a Farmer's
        // grant only counts while its resolvedAt is >= this (see
        // hasActiveGrant()), which is what makes a stale grant from an
        // earlier session stop working the instant Manual Mode is turned
        // off and back on, with no cleanup step needed on this side either.
        manualModeEnabledAtRef = deviceRef.child("commands").child("manualModeEnabledAt");
        manualModeEnabledAtListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!isAdded()) return;
                Long value = FirebaseSafeRead.lng(snapshot);
                // Absent means no known session start (0), as before. A present
                // but malformed value keeps the last known one: treating it as
                // 0 would read as "no session start, any grant counts".
                if (value != null) {
                    manualModeEnabledAt = value;
                } else if (!snapshot.exists()) {
                    manualModeEnabledAt = 0L;
                }
                updateActuatorControls();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                handleAccessRevoked("commands/manualModeEnabledAt", error);
            }
        };
        manualModeEnabledAtRef.addValueEventListener(manualModeEnabledAtListener);

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
                handleAccessRevoked("actuatorStatus", error);
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
            running = FirebaseSafeRead.bool(stateSnap);
            state = Boolean.TRUE.equals(running) ? 5 : 0;
        } else if (stateSnap.hasChild("state")) {
            state = FirebaseSafeRead.integer(stateSnap.child("state"));
            running = FirebaseSafeRead.bool(stateSnap.child("running"));
            String reportedSource = FirebaseSafeRead.str(stateSnap.child("source"));
            if (reportedSource != null) source = reportedSource;
        }

        if (state != null) {
            actuator.state = state;
            actuator.physicalRunning = running != null ? running : state == 5;
            actuator.physicalSource = source;
            actuator.strategy = stateSnap.hasChild("strategy")
                    ? FirebaseSafeRead.str(stateSnap.child("strategy"))
                    : "";
            actuator.reason = stateSnap.hasChild("reason")
                    ? FirebaseSafeRead.str(stateSnap.child("reason"))
                    : null;
            actuator.overrideActive = Boolean.TRUE.equals(
                    FirebaseSafeRead.bool(stateSnap.child("overrideActive")));
            DataSnapshot speedSnap = stateSnap.child("speed");
            Integer speed = FirebaseSafeRead.integer(speedSnap);
            // Absent speed means full speed, as before. A present but malformed
            // value keeps the last shown speed instead of showing a made-up 100.
            if (speed != null) {
                actuator.speed = speed;
            } else if (!speedSnap.exists()) {
                actuator.speed = 100;
            }
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

        Integer growStateOrNull = getSnapState(growSnap);
        Integer bloomStateOrNull = getSnapState(bloomSnap);
        if (growStateOrNull == null || bloomStateOrNull == null) {
            // A malformed state must not be shown as OFF; keep what is on screen.
            Log.w("ParametersMonitoring", "Nutrient pump state is malformed; leaving the card unchanged");
            return;
        }
        int growState  = growStateOrNull;
        int bloomState = bloomStateOrNull;

        int combinedState;
        String combinedReason = null;

        if (growState == 3 || bloomState == 3) {
            // Either pump rejected — show REJECTED and surface the reason
            combinedState = 3;
            if (growState == 3 && growSnap.hasChild("reason"))
                combinedReason = FirebaseSafeRead.str(growSnap.child("reason"));
            else if (bloomState == 3 && bloomSnap.hasChild("reason"))
                combinedReason = FirebaseSafeRead.str(bloomSnap.child("reason"));
        } else if (growState == 5 && bloomState == 5) {
            combinedState = 5; // Both fully RUNNING
        } else if (growState == 0 && bloomState == 0) {
            combinedState = 0; // Both fully OFF
        } else {
            // In-progress: show the highest non-terminal intermediate state
            combinedState = Math.max(growState, bloomState);
        }

        nutrients.state  = combinedState;
        Boolean growRunning = FirebaseSafeRead.bool(growSnap.child("running"));
        Boolean bloomRunning = FirebaseSafeRead.bool(bloomSnap.child("running"));
        nutrients.physicalRunning = growRunning != null || bloomRunning != null
                ? Boolean.TRUE.equals(growRunning) || Boolean.TRUE.equals(bloomRunning)
                : combinedState == 5;
        String growSource = FirebaseSafeRead.str(growSnap.child("source"));
        String bloomSource = FirebaseSafeRead.str(bloomSnap.child("source"));
        nutrients.physicalSource = "manual".equalsIgnoreCase(growSource) || "manual".equalsIgnoreCase(bloomSource)
                ? "manual"
                : ("automatic".equalsIgnoreCase(growSource) || "automatic".equalsIgnoreCase(bloomSource)
                    ? "automatic" : "");
        nutrients.reason = combinedReason;
        nutrients.overrideActive = Boolean.TRUE.equals(FirebaseSafeRead.bool(growSnap.child("overrideActive")))
                || Boolean.TRUE.equals(FirebaseSafeRead.bool(bloomSnap.child("overrideActive")));
        updateActuatorUI(actNutrients, nutrients);
    }

    /**
     * Reads the integer state from an actuatorStatus snapshot node. Returns 0 (OFF) if the
     * node is absent, and null if a value is present but malformed (so the caller can keep
     * the last shown state instead of showing OFF).
     */
    private Integer getSnapState(DataSnapshot snap) {
        if (snap == null || !snap.exists()) return 0;
        Object raw = snap.getValue();
        if (raw instanceof Boolean)
            return Boolean.TRUE.equals(FirebaseSafeRead.bool(snap)) ? 5 : 0;
        if (snap.hasChild("state")) {
            return FirebaseSafeRead.integer(snap.child("state"));
        }
        // A node with children but no state field reads as OFF, as before;
        // any other bare value (a string, a number) is malformed.
        return snap.hasChildren() ? Integer.valueOf(0) : null;
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

    /**
     * True only while this Farmer's own manual-control grant is APPROVED AND
     * still belongs to the CURRENT Manual Mode session - mirrors the RTDB
     * rule's own resolvedAt &gt;= manualModeEnabledAt check (Database_Helper's
     * checkAdminOrGrantTask() re-verifies server-side on every command
     * anyway; this is purely for deciding what the UI shows/enables).
     */
    private boolean hasActiveGrant() {
        return "APPROVED".equals(myGrantStatus) && myGrantResolvedAt > 0 && myGrantResolvedAt >= manualModeEnabledAt;
    }

    /** Resolves the requesting farmer's display name (never trust a client-supplied one) before prompting the Admin. */
    private void promptAdminForGrant(String farmerUid) {
        dbHelper.getUserProfile(farmerUid)
                .addOnSuccessListener(doc -> {
                    if (!isAdded()) return;
                    String name = (doc != null && doc.exists() && doc.getString("fullName") != null)
                            ? doc.getString("fullName") : "A farmer";
                    showGrantApprovalDialog(farmerUid, name);
                })
                .addOnFailureListener(e -> {
                    if (!isAdded()) return;
                    showGrantApprovalDialog(farmerUid, "A farmer");
                });
    }

    private void showGrantApprovalDialog(String farmerUid, String requesterName) {
        NotificationHelper.showTripleActionDialog(requireContext(), "Manual Control Request",
                requesterName + " requests manual control access - to operate pumps, fans, and lights by hand. Approve?",
                "Approve", "Deny", "Later",
                new NotificationHelper.TripleActionCallback() {
                    @Override
                    public void onAction1() {
                        dbHelper.resolveManualControlGrant(farmerUid, "APPROVED")
                                .addOnFailureListener(e -> {
                                    if (!isAdded()) return;
                                    Toast.makeText(getContext(), "Unable to approve - it may have already been resolved.", Toast.LENGTH_SHORT).show();
                                });
                    }

                    @Override
                    public void onAction2() {
                        dbHelper.resolveManualControlGrant(farmerUid, "DENIED")
                                .addOnFailureListener(e -> {
                                    if (!isAdded()) return;
                                    Toast.makeText(getContext(), "Unable to deny - it may have already been resolved.", Toast.LENGTH_SHORT).show();
                                });
                    }
                });
    }

    // =========================================================
    // ACTUATOR UI SETUP — tap shows loading popup, not inline
    // =========================================================

    // Canopy Fan and Reservoir Fan (Blower) get a plain on/off toggle here
    // like every other actuator - there is deliberately no speed slider.
    // Speed is decided entirely by firmware now, not the app (per the
    // adviser's direction to move that decision to the firmware side).
    private void setupActuatorUI(View card, Actuator actuator) {
        TextView nameTv = card.findViewById(R.id.tvActuatorName);
        if (nameTv != null) nameTv.setText(actuator.name);

        SwitchMaterial toggle = card.findViewById(R.id.switchActuator);
        if (toggle == null) return;

        // Manual actuator control is Admin, or a Farmer with an active
        // manual-control grant (see hasActiveGrant()) - Database_Helper.
        // updateActuatorState() gates every write behind checkAdminOrGrantTask(),
        // and the RTDB commands/$actuatorKey rule requires the same (Admin/
        // developerTester, or a live APPROVED grant for the current Manual
        // Mode session). Without this check the switch was enabled for any
        // signed-in Personnel/Farmer whenever Manual Mode was on, even
        // though every command they sent was guaranteed to fail server-side.
        boolean canControl = isAdminUser() || hasActiveGrant();

        // Clear any previous listener first
        toggle.setOnCheckedChangeListener(null);
        // Confirmed firmware state is authoritative for both AUTO and MANUAL.
        toggle.setChecked(actuator.physicalRunning);
        toggle.setEnabled(canControl && isManualMode && isCurrentlyOnline && !isSafetyLock && !isActuatorBusy);
        toggle.setAlpha(canControl ? 1.0f : 0.6f);

        toggle.setOnCheckedChangeListener((buttonView, checked) -> {
            if (!canControl) {
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

            // Manual chemistry dosing clarification: a manual ON for pH Up/pH
            // Down/Nutrients is a single bounded ~5-second pump pulse
            // (MANUAL_PUMP_RUNTIME - see ActuatorManager::validateCommand's
            // PH_UP_PUMP/PH_DOWN_PUMP/GROW_PUMP/BLOOM_PUMP case), not a
            // persistent ON state, and it does not run the automatic pH/EC
            // stabilization/reevaluation lifecycle. Confirmed here - reusing
            // the same confirmation dialog SOFT_CONFLICT already uses just
            // above, rather than a new dialog system - so the Admin knows
            // this before tapping, not just from watching the switch snap
            // back off a few seconds later. OFF is unaffected: stopping an
            // already-bounded pulse early needs no such framing.
            if (checked && (key == ManualOverrideAdvisor.ActuatorKey.PH_UP
                    || key == ManualOverrideAdvisor.ActuatorKey.PH_DOWN
                    || key == ManualOverrideAdvisor.ActuatorKey.NUTRIENTS)) {
                toggle.setOnCheckedChangeListener(null);
                toggle.setChecked(actuator.physicalRunning);
                setupActuatorUI(card, actuator);

                final String doseTitle;
                final String doseQuestion;
                if (key == ManualOverrideAdvisor.ActuatorKey.PH_UP) {
                    doseTitle = "Manual pH Up Dose";
                    doseQuestion = "Run pH Up dose for 5 seconds?";
                } else if (key == ManualOverrideAdvisor.ActuatorKey.PH_DOWN) {
                    doseTitle = "Manual pH Down Dose";
                    doseQuestion = "Run pH Down dose for 5 seconds?";
                } else {
                    doseTitle = "Manual Nutrient Dose";
                    doseQuestion = "Run nutrient dose for 5 seconds?";
                }

                NotificationHelper.showConfirmation(requireContext(),
                        doseTitle,
                        doseQuestion + " The pump stops automatically afterward and does not"
                                + " re-check or adjust the reading - this is a single manual"
                                + " pulse, not the automatic correction cycle.",
                        "Run Dose", "Cancel",
                        () -> sendActuatorCommand(card, actuator, true, false));
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
                    Exception exception = task.getException();
                    Log.e("Monitoring", "Actuator command failed for " + actuator.dbKey, exception);
                    // updateActuatorState() throws IllegalStateException
                    // specifically for the expected "manual mode isn't on"
                    // rejection - surface that reason directly instead of a
                    // generic "could not be sent" that doesn't explain why.
                    String message = exception instanceof IllegalStateException
                            ? exception.getMessage()
                            : "The actuator command could not be sent. Please try again.";
                    finishActuatorCommand(generation, actuator, previousManualIntent,
                            "Command Failed", message,
                            false);
                } else {
                    // Command written — show Validating immediately and start polling
                    showActuatorLoading("Validating...", "");
                    monitorActuatorCommand(generation, actuator, previousManualIntent, targetState,
                            commandBaselineState, commandBaselineRunning, commandBaselineSource);
                }
            });
    }

    // CONFIRMED BUG FIX (loading overlay not covering bottom nav): this
    // fragment's own actuatorLoadingOverlay/sensorStabilizingOverlay are
    // bounded to nav_host_fragment's area and can never visually reach
    // bottom_navigation, which lives in a separate sibling view in
    // activity_main.xml - see MainActivity.setGlobalDimOverlayVisible()'s
    // own comment for the full root cause. Called alongside every local
    // overlay show/hide below so the nav bar is dimmed and non-interactive
    // for the same duration instead of staying bright and clickable above it.
    private void setGlobalDimOverlayVisible(boolean visible) {
        if (!isAdded()) return;
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setGlobalDimOverlayVisible(visible);
        }
    }

    /** Show the full-screen loading overlay with a title and optional subtitle */
    private void showActuatorLoading(String title, String subtitle) {
        if (actuatorLoadingOverlay == null || !isAdded()) return;
        // CONFIRMED BUG FIX (nav bar stuck dimmed after a command finishes):
        // this method is called repeatedly while a command is in flight -
        // monitorActuatorCommand's poll loop calls it roughly every 500ms to
        // refresh the title/subtitle (Validating..., Activating..., ...) -
        // but hideActuatorLoading() only runs once at the end. Each call used
        // to unconditionally call setGlobalDimOverlayVisible(true), and that
        // request count is reference-counted in MainActivity (see its own
        // comment), so a multi-poll command could open 4-5 requests against
        // a single closing one, leaving the nav bar's dim scrim stuck on
        // forever. Gating the call behind the same "was it already visible"
        // check already used for actuatorLoadingShownAt keeps it to exactly
        // one open request per visible session, matching hideActuatorLoading's
        // one close.
        if (actuatorLoadingOverlay.getVisibility() != View.VISIBLE) {
            actuatorLoadingShownAt = SystemClock.elapsedRealtime();
            setGlobalDimOverlayVisible(true);
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
            setGlobalDimOverlayVisible(false);
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
        // 300 * 1000ms = 5 minutes, matching firmware's own OPERATION_TIMEOUT_MS
        // (Config.h) - a legitimately in-progress operation cannot still be
        // RUNNING/ACCEPTED past that, since firmware itself would have already
        // failed/completed it. Sized to the firmware's authoritative bound
        // (rather than the previous 60s) so this timeout can never clip a real
        // operation while still guaranteeing the spinner cannot hang forever
        // if firmware genuinely gets stuck - see the attempt-increment fix
        // below, which used to reset to 0 on every RUNNING/ACCEPTED tick and
        // so never reached this bound at all.
        final int MAX_ATTEMPTS = 300;
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
                Integer currentReqId = FirebaseSafeRead.integer(snap.child("requestId"));
                String state = FirebaseSafeRead.str(snap.child("state"));
                
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
                    String reason = FirebaseSafeRead.str(snap.child("reason"));
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
                    pollOperationUntilDone(requestId, opName, attempt + 1);
                    return;
                } else if ("ACCEPTED".equals(state)) {
                    showActuatorLoading("Accepted", "Starting " + opName + "...");
                    pollOperationUntilDone(requestId, opName, attempt + 1);
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
        TextView reasonView = card.findViewById(R.id.tvActuatorReason);

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

        // Own line, not tvStatus's row - see item_actuator.xml's own comment.
        // Only for the two terminal states where "why" is worth surfacing:
        // a forced-off (e.g. Peltier stopped when Circulation Pump is turned
        // off) and a rejected command.
        if (reasonView != null) {
            boolean showReason = (actuator.state == 0 || actuator.state == 3)
                    && actuator.reason != null && !actuator.reason.isEmpty();
            reasonView.setText(showReason ? actuator.reason : "");
            reasonView.setVisibility(showReason ? View.VISIBLE : View.GONE);
        }
    }

    private String actuatorSourceSuffix(Actuator actuator) {
        if ("automatic".equalsIgnoreCase(actuator.physicalSource)) return " · Auto";
        if ("manual".equalsIgnoreCase(actuator.physicalSource)) return " · Manual";
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
                    || "hot".equalsIgnoreCase(actuator.strategy)
                    || "night".equalsIgnoreCase(actuator.strategy))) {
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
        // Same Admin-or-granted-Farmer gate as setupActuatorUI() - without
        // it, this method (called from onModeSwitchChanged() and after
        // every command completes) would re-enable the switches for
        // someone who shouldn't have them right after setupActuatorUI()
        // correctly disabled them.
        // !isActuatorBusy is required too - this is the same call sendActuatorCommand()/
        // btnTriggerRefill/btnResetSafety use right after setting isActuatorBusy = true
        // to lock the panel. Without it here, every switch was being re-enabled the
        // instant a command started, so a second tap mid-command (while the "Sending
        // command..."/"Validating..." popup was showing) went through, hijacked the
        // shared actuatorCommandGeneration tracking, and orphaned the first command's polling.
        boolean enabled = (isAdminUser() || hasActiveGrant()) && isManualMode && isCurrentlyOnline && !isSafetyLock && !isActuatorBusy;
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
        writeManualMode(modeSwitch, checked);
    }

    /**
     * Writes commands/manualMode for an already-optimistically-applied switch
     * change (isManualMode/modeSwitch/actuator controls are updated by the
     * caller before this runs). Success needs no extra handling - the switch
     * already shows the right state, and manualModeListener's own RTDB echo
     * reconciles it normally either way. On failure, reverts the switch to
     * the last CONFIRMED state (the opposite of what was just requested,
     * since Manual Mode is a plain boolean) without re-triggering
     * guardedModeSwitchListener, and reports the error - same
     * NotificationHelper.showError pattern already used by
     * endManualModeSession()/requestManualControlAccess() above.
     */
    private void writeManualMode(SwitchMaterial modeSwitch, boolean requestedChecked) {
        dbHelper.updateManualMode(requestedChecked).addOnFailureListener(e -> {
            if (!isAdded()) return;
            boolean previousConfirmed = !requestedChecked;
            isManualMode = previousConfirmed;
            modeSwitch.setOnCheckedChangeListener(null);
            modeSwitch.setChecked(previousConfirmed);
            modeSwitch.setOnCheckedChangeListener(guardedModeSwitchListener);
            updateActuatorControls();
            NotificationHelper.showError(requireContext(), "Unable to update Manual Mode. Please try again.");
        });
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

    /**
     * Updates all sensor TextViews from the current sensorLiveData value.
     *
     * pH is a special case (quick-response refinement task; originally Part
     * B, real-hardware pre-integration follow-up). Firmware's pH temporal
     * step filter now publishes /sensors/ph as FAST TELEMETRY - the last
     * TRUSTED reading, held steady through an in-progress confirmation
     * (never blanked to hide it, never replaced by the unconfirmed
     * candidate) - so a present data.ph is shown directly even while a new
     * level is still being confirmed; data.phConfirming (always published,
     * see FirebaseManager::writeSensors()) is the primary signal for the
     * one remaining ambiguous case, "genuinely no sensor signal at all"
     * (data.ph absent) vs "a first reading is still being confirmed"
     * (phConfirming=true, "Stabilizing..."). physicalPhCandidate
     * (debug/physicalSensors/ph) is NOT used for this decision - despite an
     * earlier comment here claiming it's "only ever populated while
     * Developer Sensor Test is active", the firmware actually publishes it
     * unconditionally on every optional-job cycle regardless of mock mode
     * (FirebaseManager.cpp's writeDiagnosticSensors()), including a
     * disconnected/floating pH ADC's raw noise when no probe is wired at
     * all. Trusting it here previously made this card flash "Stabilizing…"
     * on mock-only test rigs with nothing physically connected. phConfirming
     * alone is a sufficient signal now (it's unconditionally published by
     * both the mock and physical sensor paths - see SensorManager.cpp's
     * applyEffectiveSensors()), so the debug candidate is not needed as a
     * fallback any more. This is display status only - it never feeds
     * automation, which continues to use only the accepted /sensors/ph
     * value and the existing stricter stability gate exactly as before.
     */
    private void updateSensorUI() {
        if (!isAdded() || getView() == null) return;
        SensorData data = sensorLiveData.getValue();
        // Rail-proximity hardware-fault state (see SensorData's own comment).
        // Checked ahead of the normal accepted/confirming branches below so a
        // confirmed fault always wins the value cell - a faulted probe has
        // nothing valid to confirm or display.
        boolean phFault = data != null && Boolean.TRUE.equals(data.phFault);
        boolean ecFault = data != null && Boolean.TRUE.equals(data.ecFault);
        if (tvPH != null) {
            boolean phAccepted = data != null && data.ph != null
                    && !data.ph.isNaN() && !data.ph.isInfinite();
            boolean phBeingConfirmed = data != null && Boolean.TRUE.equals(data.phConfirming);
            if (phFault) {
                tvPH.setText("Check pH sensor");
            } else if (!phAccepted && phBeingConfirmed) {
                tvPH.setText("Stabilizing…");
            } else {
                tvPH.setText(formatSensor(
                        data != null ? data.ph : null, 0.0, 14.0, 2, "", null));
            }
        }
        if (tvEC != null) {
            tvEC.setText(ecFault ? "Check EC sensor" : formatSensor(
                    data != null ? data.ec : null, 0.0, Double.MAX_VALUE, 2, " mS/cm", null));
        }
        // Retained last-known-good value while dhtStale is true (firmware
        // never blanks temperature/humidity for a stale hold - see
        // SensorData's own comment) - the NUMBER shown is unchanged either
        // way; applyParameterStateColor()'s stale parameter below is what
        // marks it, so a stale reading sitting inside the target range is
        // never colored/labelled "Normal".
        boolean dhtStale = data != null && Boolean.TRUE.equals(data.dhtStale);
        if (tvTemp != null) tvTemp.setText(formatSensor(
                data != null ? data.airTemperature : null, -40.0, 80.0, 1, "°C", null));
        if (tvHumidity != null) tvHumidity.setText(formatSensor(
                data != null ? data.humidity : null, 0.0, 100.0, 1, "%", null));
        if (tvWaterTemp != null) tvWaterTemp.setText(formatSensor(
                data != null ? data.waterTemperature : null, -55.0, 125.0, 1, "°C", -127.0));
        if (tvWaterLevel != null) {
            CharSequence waterLevelText = formatSensor(
                    data != null ? data.waterLevel : null, 0.0, 100.0, 1, "%", null);

            // Water-depth model: percentage stays the primary value in this
            // compact card, with the authoritative depth (cm) shown right
            // alongside it - never derived independently, always the same
            // published waterLevelCm control automation itself uses. Null
            // on pre-update records or an invalid sensor reading, in which
            // case only the percentage (or "--") is shown, same as before.
            Double waterLevelCm = data != null ? data.waterLevelCm : null;
            boolean percentValid = data != null && data.waterLevel != null
                    && !data.waterLevel.isNaN() && !data.waterLevel.isInfinite()
                    && data.waterLevel >= 0.0 && data.waterLevel <= 100.0;
            boolean depthValid = waterLevelCm != null
                    && !waterLevelCm.isNaN() && !waterLevelCm.isInfinite();

            if (percentValid && depthValid) {
                waterLevelText = new android.text.SpannableStringBuilder(waterLevelText)
                        .append(String.format(java.util.Locale.US, " (%.1f cm)", waterLevelCm));
            }

            tvWaterLevel.setText(waterLevelText);
        }

        // Red is decided here, from the number itself against the configured
        // target range - the reading turns red the moment it is below the
        // minimum or above the maximum, not when the firmware's slower
        // confirmed alert catches up.
        applyParameterStateColor(tvPH, tvPHStatus, ParameterTargetRanges.PH,
                data != null ? data.ph : null, false);
        applyParameterStateColor(tvEC, tvECStatus, ParameterTargetRanges.EC,
                data != null ? data.ec : null, false);
        // dhtStale takes priority over range coloring - a retained
        // last-known value that happens to fall inside the target range must
        // never read "Normal".
        applyParameterStateColor(tvTemp, tvTempStatus, ParameterTargetRanges.AIR_TEMPERATURE,
                data != null ? data.airTemperature : null, dhtStale);
        // Humidity now has a real target range instead of always reading Normal.
        applyParameterStateColor(tvHumidity, tvHumidityStatus, ParameterTargetRanges.HUMIDITY,
                data != null ? data.humidity : null, dhtStale);
        applyParameterStateColor(tvWaterTemp, tvWaterTempStatus, ParameterTargetRanges.WATER_TEMPERATURE,
                data != null ? data.waterTemperature : null, false);
        applyParameterStateColor(tvWaterLevel, tvWaterLevelStatus, ParameterTargetRanges.WATER_LEVEL,
                data != null ? data.waterLevel : null, false);
    }

    /**
     * Whether the reading is outside the configured target range, judged on
     * the value as displayed (rounded to the card's own decimal places) so
     * what the user reads always agrees with the colour: with a 5.5 - 6.5
     * range, 5.49 and 6.51 are red while 5.50 and 6.50 are not. The bounds
     * themselves are in range. Compared as whole hundredths/tenths so a
     * float such as 5.4999999 cannot flip the result.
     *
     * @return -1 below the minimum, +1 above the maximum, 0 inside (or unknown)
     */
    private int targetRangePosition(ParameterTargetRanges parameter, Double value) {
        if (value == null || value.isNaN() || value.isInfinite()) return 0;
        double[] range = targetRanges.get(parameter);
        if (range == null) return 0;

        double scale = Math.pow(10, parameter.decimals);
        long shown = Math.round(value * scale);
        if (shown < Math.round(range[0] * scale)) return -1;
        if (shown > Math.round(range[1] * scale)) return 1;
        return 0;
    }

    /**
     * Reveals every sensor card together, once, for this fragment view's
     * lifetime - see sensorsRevealed's own comment. Called either by a
     * coherent ready snapshot arriving (sensorLiveData.observe()) or by
     * SENSOR_STABILIZING_TIMEOUT_MS elapsing with no such snapshot yet; in
     * the timeout case updateSensorUI() below renders whatever is currently
     * cached in sensorLiveData - a sensor with no trustworthy value yet
     * falls through to its own existing "--" state (formatSensor()), never
     * a fabricated number. No artificial delay is added beyond this: the
     * overlay is hidden the instant this runs, not after some minimum
     * shown duration.
     */
    private void revealSensors(String diagnostic) {
        if (sensorsRevealed || !isAdded() || getView() == null) return;
        sensorsRevealed = true;
        mainHandler.removeCallbacks(sensorStabilizingTimeoutRunnable);
        if (sensorStabilizingOverlay != null) {
            sensorStabilizingOverlay.setVisibility(View.GONE);
            setGlobalDimOverlayVisible(false);
        }
        Log.d(SENSOR_UI_TAG, "[SENSOR-UI] " + diagnostic);
        updateSensorUI();
        maybeShowCoachMarkTour();
    }

    /**
     * TRUE/FALSE for a real boolean flag, FALSE when the flag is missing (the firmware's
     * "not active"), and null when the flag is present but the wrong type (unknown).
     */
    private Boolean alertFlag(DataSnapshot alerts, String key) {
        DataSnapshot flag = alerts.child(key);
        if (!flag.exists()) return Boolean.FALSE;
        return FirebaseSafeRead.bool(flag);
    }

    /** Same, collapsed to a boolean: an unknown (malformed) flag keeps lastKnown instead of reading as inactive. */
    private boolean isAlertActive(DataSnapshot alerts, String key, boolean lastKnown) {
        Boolean active = alertFlag(alerts, key);
        return active != null ? active : lastKnown;
    }

    /**
     * Renders one reading's state as the value's color plus a text status
     * label. A missing reading stays "No Data" and is never reported as out
     * of range; a reading outside the configured target range is red and
     * labelled with its direction (see targetRangePosition()).
     *
     * `stale` is for a sensor (currently DHT air temperature/humidity) whose
     * VALUE TEXT stays a normal-looking formatted number even while stale -
     * unlike pH/EC fault or the Stabilizing/No Data cases below, which are
     * already distinguishable by the literal text set in updateSensorUI(),
     * a stale-but-numeric reading needs its own signal so it is never
     * colored/labelled "Normal" just because the retained number happens to
     * sit inside the target range.
     */
    private void applyParameterStateColor(TextView valueView, TextView statusView,
                                          ParameterTargetRanges parameter, Double value,
                                          boolean stale) {
        if (valueView == null) return;
        final int position = targetRangePosition(parameter, value);
        final int colorRes;
        final String statusText;
        if (stale && !"--".contentEquals(valueView.getText())) {
            // Retained last-known value - reuses the same "Last known"
            // language already applied to actuator status text when a
            // device goes offline (see updateConnectionUI()), rather than
            // inventing a new visual pattern. Takes priority over every
            // other branch below so a stale reading is never shown as
            // Normal/Below/Above Range.
            colorRes = R.color.state_no_data;
            statusText = "Last known";
        } else if ("Check pH sensor".contentEquals(valueView.getText())
                || "Check EC sensor".contentEquals(valueView.getText())) {
            // Confirmed pH/EC hardware fault (see SensorData's own comment) -
            // distinct from a plain "--"/No Data (never read anything yet)
            // and from Below/Above Range (a valid, dosing-correctable
            // chemistry reading). Colored the same red since this needs
            // attention, but labelled with its own status text so it reads
            // as a hardware issue rather than an out-of-range reading.
            colorRes = R.color.state_critical;
            statusText = "Sensor unavailable";
        } else if ("Stabilizing…".contentEquals(valueView.getText())) {
            // See updateSensorUI()'s own comment - a live pH candidate is
            // actively being gathered/reconfirmed, distinct from "No Data"
            // (no candidate at all) and from a real out-of-range/warning
            // reading.
            colorRes = R.color.state_warning;
            statusText = "Stabilizing";
        } else if ("--".contentEquals(valueView.getText())) {
            colorRes = R.color.state_no_data;
            statusText = "No Data";
        } else if (position < 0) {
            colorRes = R.color.state_critical;
            statusText = "Below Range";
        } else if (position > 0) {
            colorRes = R.color.state_critical;
            statusText = "Above Range";
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

    // Firebase never retries a listener cancelled for PERMISSION_DENIED - none
    // of the six RTDB listeners in observeDeviceData() will ever fire
    // onDataChange again once that happens (e.g. an Admin unclaimed this
    // device mid-session). Whichever listener notices first drives the shared
    // connectivity state to ACCESS_REVOKED immediately, rather than each one
    // silently going quiet while the banner still says "Reconnecting..." or
    // "Online" from whatever it last knew. The stale sensor/alert/actuator
    // values already on screen are deliberately left as-is (same philosophy
    // as actuatorStatusListener's own onCancelled comment below) - a frozen
    // last-known reading under a banner that honestly says access was
    // revoked is not misleading the way it would be under "Reconnecting...".
    private void handleAccessRevoked(String listenerName, DatabaseError error) {
        Log.e("RTDB", listenerName + " listener cancelled: " + error.getMessage());
        if (error.getCode() != DatabaseError.PERMISSION_DENIED || !isAdded()) return;
        if (connectivityState == DeviceConnectivityState.ACCESS_REVOKED) return;
        connectivityState = DeviceConnectivityState.ACCESS_REVOKED;
        isCurrentlyOnline = false;
        updateConnectionUI();
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
            } else if (displayState == DeviceConnectivityState.ACCESS_REVOKED) {
                tvConnectionDetail.setText("You no longer have access to this device.\nAsk an Admin to claim it again to resume monitoring.");
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
                // Admin's tap actually flips manualMode, which only makes sense
                // while the device is online. A Farmer's tap never changes
                // manualMode itself (the listener always snaps it back) - it only
                // requests/reports on their own grant, which is pure RTDB
                // bookkeeping independent of the device's current connectivity,
                // so their tap must always reach the listener regardless of
                // isCurrentlyOnline. Leaving this Android-disabled for a Farmer
                // (as it was before) would silently swallow their tap before it
                // ever reaches that listener.
                boolean modeSwitchEnabled = isAdminUser() ? isCurrentlyOnline : true;
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
        // CONFIRMED BUG FIX (loading overlay not covering bottom nav): if
        // this view is torn down (e.g. back navigation) while either overlay
        // was still visible, its matching setGlobalDimOverlayVisible(true)
        // call below would otherwise never be balanced by a hide() call,
        // leaving MainActivity's global dim stuck over the whole app
        // (including the nav bar) forever. Released here, once per overlay
        // that was still showing, before either is nulled out below.
        if (actuatorLoadingOverlay != null && actuatorLoadingOverlay.getVisibility() == View.VISIBLE) {
            setGlobalDimOverlayVisible(false);
        }
        if (sensorStabilizingOverlay != null && sensorStabilizingOverlay.getVisibility() == View.VISIBLE) {
            setGlobalDimOverlayVisible(false);
        }

        // Confirmed live bug: this Fragment INSTANCE survives navigating away
        // and back (Jetpack Navigation keeps it on the back stack, only its
        // view gets destroyed/recreated), but sensorsRevealed is a plain
        // instance field with no per-view reset - so a stale true here made
        // revealSensors() early-return on the very first line for the entire
        // life of the next view, silently defeating BOTH the normal
        // ready-snapshot reveal AND its own 3-second hard-timeout fallback.
        // Reset here so every fresh view gets its own honest "not yet
        // revealed" state, matching sensorsRevealed's own doc comment ("this
        // fragment view's lifetime") instead of contradicting it.
        sensorsRevealed = false;
        actuatorCommandFinished = true;
        actuatorCommandGeneration++;
        if (actuatorCommandRunnable != null) {
            mainHandler.removeCallbacks(actuatorCommandRunnable);
            actuatorCommandRunnable = null;
        }
        mainHandler.removeCallbacksAndMessages(null);
        sensorsRevealed = false;
        lastLoggedSensorReady = null;
        sensorStabilizingOverlay = null;
        phLoader = null;
        ecLoader = null;
        waterLevelLoader = null;
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
        if (targetRangesRef != null && targetRangesListener != null) {
            targetRangesRef.removeEventListener(targetRangesListener);
        }
        if (statusRef != null && statusListener != null) {
            statusRef.removeEventListener(statusListener);
        }
        if (manualModeRef != null && manualModeListener != null) {
            manualModeRef.removeEventListener(manualModeListener);
        }
        guardedModeSwitchListener = null;
        if (manualControlGrantsRef != null && manualControlGrantsListener != null) {
            manualControlGrantsRef.removeEventListener(manualControlGrantsListener);
        }
        if (manualModeEnabledAtRef != null && manualModeEnabledAtListener != null) {
            manualModeEnabledAtRef.removeEventListener(manualModeEnabledAtListener);
        }
        if (actuatorStatusRef != null && actuatorStatusListener != null) {
            actuatorStatusRef.removeEventListener(actuatorStatusListener);
        }
        if (highWaterTempRef != null && highWaterTempListener != null) {
            highWaterTempRef.removeEventListener(highWaterTempListener);
        }
        if (physicalSensorsPhRef != null && physicalSensorsPhListener != null) {
            physicalSensorsPhRef.removeEventListener(physicalSensorsPhListener);
        }
        if (cycleListener != null) {
            cycleListener.remove();
            cycleListener = null;
        }
        if (coachMarkTour != null) {
            coachMarkTour.finish();
            coachMarkTour = null;
        }
        super.onDestroyView();
    }

    private void showActuatorInfoDialog() {
        if (getContext() == null) return;
        String[][] sections = {
                {"What does this section do?",
                        "Shows every pump, fan, light, and valve the system controls, and whether each is currently running under the automatic system (Auto) or being controlled manually through the Basilience mobile application (Manual)."},
                {"Automatic vs. Manual Mode",
                        "By default every actuator runs automatically from sensor readings. An Admin can turn on 'Manual Mode' to operate individual actuators by hand. A Farmer can ask an Admin for manual-control access. Manual control puts a hold on the actuator you control, and other eligible automatic operations may continue. Turning Manual Mode on can stop an automatic job already in progress, such as refilling or dosing. Turn Manual Mode off to return everything to automatic control. It also ends by itself after about 15 minutes of inactivity. Each status line shows how the actuator is controlled: Auto or Manual."},
                {"Start Reservoir Refill",
                        "Admin-only. Manually opens the water pump/valve to top up the reservoir, the same action the automatic system takes on its own when the water level runs low. Useful for topping off before a cycle or after inspecting the reservoir by hand."},
                {"Reset Safety",
                        "Admin-only. Clears an active safety lock (for example after a water-level or temperature limit tripped and stopped equipment) so the automatic system and manual controls can resume. Only use this after confirming the underlying issue is actually resolved."},
                {"The other actuators",
                        "Water Pump (Valve) refills the reservoir. Circulation Pump keeps nutrient solution mixed and readings representative. pH Up/pH Down and the Nutrients pumps dose small amounts to correct pH and EC. Fogger and Reservoir Fan (Blower) work together to raise humidity and cool the canopy. Peltier (Temp) cools the reservoir when water temperature runs high. Canopy Fan circulates air, and Grow Lights follow the automatic lighting schedule."},
                {"Fan speed",
                        "Canopy Fan and Reservoir Fan (Blower) run at a speed the device decides on its own - there is no speed control in the app. Turning them on or off here still works like every other actuator."},
                {"Rejected / blocked status",
                        "A 'Rejected' or 'blocked' status with a reason means the device refused that specific command - most often because a safety interlock is active, a sensor reading is invalid, or another operation currently owns that equipment. It clears on its own once the blocking condition ends. It does not mean the actuator is stuck."}
        };
        NotificationHelper.showGuideDialog(requireContext(), "How to use Actuator Control",
                sections, "Got it");
    }
}
