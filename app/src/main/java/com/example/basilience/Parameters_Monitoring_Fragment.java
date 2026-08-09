package com.example.basilience;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;
import com.example.basilience.repository.SensorRepository;
import com.example.basilience.models.SensorData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    private TextView tvActuatorLoadingTitle;
    private TextView tvActuatorLoadingStatus;


    // ===== MAIN =====
    private TextView tvPH, tvEC, tvTemp, tvHumidity;
    private TextView tvWaterTemp, tvWaterLevel;

    // ===== ACTUATORS =====
    class Actuator {
        String name;
        String dbKey;
        int state;
        boolean physicalRunning;
        String physicalSource;
        boolean manualIntent;
        String reason;

        Actuator(String name, String dbKey) {
            this.name = name;
            this.dbKey = dbKey;
            this.state = 0;
            this.physicalRunning = false;
            this.physicalSource = "";
            this.manualIntent = false;
        }
    }

    private DatabaseReference sensorsRef;
    private ValueEventListener sensorsListener;

    private TextView tvConnectionStatus;
    private SensorRepository sensorRepository;
    private final MutableLiveData<SensorData> sensorLiveData = new MutableLiveData<>();
    private Long previousLastSeenValue = null;
    private boolean isCurrentlyOnline = false;

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
        if (actCirculationPump != null) {
            setupActuatorUI(actCirculationPump, circulationPump);
            SwitchMaterial circulationSwitch = actCirculationPump.findViewById(R.id.switchActuator);
            if (circulationSwitch != null) circulationSwitch.setVisibility(View.GONE);
        }

        updateActuatorControls();

        // Bind loading overlay views
        actuatorLoadingOverlay = view.findViewById(R.id.actuatorLoadingOverlay);
        tvActuatorLoadingTitle = view.findViewById(R.id.tvActuatorLoadingTitle);
        tvActuatorLoadingStatus = view.findViewById(R.id.tvActuatorLoadingStatus);

        tvConnectionStatus = view.findViewById(R.id.tvConnectionStatus);
        sensorRepository = new SensorRepository();

        sensorLiveData.observe(getViewLifecycleOwner(), new Observer<SensorData>() {
            @Override
            public void onChanged(SensorData sensorData) {
                updateSensorUI();
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
                    NotificationHelper.showConfirmation(requireContext(),
                            "Trigger Refill Operation",
                            "Are you sure you want to trigger an automated reservoir refill operation?",
                            "Yes", "No", () -> {
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
                                            Toast.makeText(getContext(), "Request failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
                                            Toast.makeText(getContext(), "Request failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
            TextView label = cardPH.findViewById(R.id.tvLabel);
            if (label != null) label.setText("pH");
        }
        if (cardEC != null) {
            tvEC = cardEC.findViewById(R.id.tvValue);
            TextView label = cardEC.findViewById(R.id.tvLabel);
            if (label != null) label.setText("EC");
        }
        if (cardTemp != null) {
            tvTemp = cardTemp.findViewById(R.id.tvValue);
            TextView label = cardTemp.findViewById(R.id.tvLabel);
            if (label != null) label.setText("Air Temp");
        }
        if (cardHumidity != null) {
            tvHumidity = cardHumidity.findViewById(R.id.tvValue);
            TextView label = cardHumidity.findViewById(R.id.tvLabel);
            if (label != null) label.setText("Humidity");
        }
        if (cardWaterTemp != null) {
            tvWaterTemp = cardWaterTemp.findViewById(R.id.tvValue);
            TextView label = cardWaterTemp.findViewById(R.id.tvLabel);
            if (label != null) label.setText("Water Temp");
        }
        if (cardWaterLevel != null) {
            tvWaterLevel = cardWaterLevel.findViewById(R.id.tvValue);
            TextView label = cardWaterLevel.findViewById(R.id.tvLabel);
            if (label != null) label.setText("Water Level");
        }
    }

    private void startRealTimeMonitoring() {
        if (getContext() == null) return;
        SharedPreferences prefs = requireContext().getSharedPreferences("basilience_prefs", android.content.Context.MODE_PRIVATE);
        String deviceId = prefs.getString("selected_device_id", null);

        if (deviceId == null) {
            Log.e("Monitoring", "No device selected");
            return;
        }

        dbHelper.setSelectedDeviceId(deviceId);
        sensorRepository.startListening(deviceId, sensorLiveData);

        View v = getView();
        if (v != null) {
            SwitchMaterial modeSwitch = v.findViewById(R.id.switchMode);
            if (modeSwitch != null) {
                modeSwitch.setEnabled(isCurrentlyOnline);
                modeSwitch.setAlpha(isCurrentlyOnline ? 1.0f : 0.6f);
            }
        }

        sensorsRef = dbHelper.getDeviceReference();
        if (sensorsRef == null) {
            Log.e("Monitoring", "Device reference is null. Ensure deviceId is set.");
            return;
        }
        sensorsListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!isAdded()) return;

                // Sync authoritative online status
                Boolean onlineStatus = snapshot.child("status").child("online").getValue(Boolean.class);
                if (onlineStatus != null) {
                    isCurrentlyOnline = onlineStatus;
                    updateConnectionUI();
                } else {
                    isCurrentlyOnline = false;
                    updateConnectionUI();
                }

                // Sync status: reservoirLocked & safetyLock
                Boolean reservoirLocked = snapshot.child("status").child("reservoirLocked").getValue(Boolean.class);
                if (reservoirLocked != null) isReservoirLocked = reservoirLocked;

                Boolean safetyLock = snapshot.child("status").child("safetyLock").getValue(Boolean.class);
                if (safetyLock != null) {
                    isSafetyLock = safetyLock;
                    updateConnectionUI();
                }

                // Sync Manual Mode from RTDB
                Boolean manualMode = snapshot.child("commands").child("manualMode").getValue(Boolean.class);
                if (manualMode != null) {
                    isManualMode = manualMode;
                    SwitchMaterial modeSwitch = getView() != null ? getView().findViewById(R.id.switchMode) : null;
                    if (modeSwitch != null) {
                        modeSwitch.setOnCheckedChangeListener(null);
                        modeSwitch.setChecked(isManualMode);
                        modeSwitch.setText("Manual Mode");
                        modeSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
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
                        });
                    }
                    updateActuatorControls();
                }

                // Sync Actuators from RTDB using the new actuatorStatus node
                DataSnapshot actuatorStatus = snapshot.child("actuatorStatus");
                if (!actuatorStatus.exists()) {
                    // Fallback to old 'actuators' node for backward compatibility during transition
                    actuatorStatus = snapshot.child("actuators");
                }
                if (actuatorStatus.exists()) {
                    syncActuatorState(waterPumpValve, actWaterPumpValve, actuatorStatus.child(waterPumpValve.dbKey));
                    syncActuatorState(canopyFan, actCanopyFan, actuatorStatus.child(canopyFan.dbKey));
                    syncActuatorState(growLights, actGrowLights, actuatorStatus.child(growLights.dbKey));
                    syncActuatorState(phUp, actPhUp, actuatorStatus.child(phUp.dbKey));
                    syncActuatorState(phDown, actPhDown, actuatorStatus.child(phDown.dbKey));
                    // nutrients uses both growPump + bloomPump — use combined sync
                    syncNutrientsState(actuatorStatus.child("growPump"), actuatorStatus.child("bloomPump"));
                    syncActuatorState(fogger, actFogger, actuatorStatus.child(fogger.dbKey));
                    syncActuatorState(reservoirFan, actReservoirFan, actuatorStatus.child(reservoirFan.dbKey));
                    syncActuatorState(peltier, actPeltier, actuatorStatus.child(peltier.dbKey));
                    syncActuatorState(circulationPump, actCirculationPump, actuatorStatus.child(circulationPump.dbKey));
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e("RTDB", error.getMessage());
            }
        };
        sensorsRef.addValueEventListener(sensorsListener);
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
            actuator.reason = stateSnap.hasChild("reason")
                    ? stateSnap.child("reason").getValue(String.class)
                    : null;
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

    // =========================================================
    // ACTUATOR UI SETUP — tap shows loading popup, not inline
    // =========================================================

    private void setupActuatorUI(View card, Actuator actuator) {
        TextView nameTv = card.findViewById(R.id.tvActuatorName);
        if (nameTv != null) nameTv.setText(actuator.name);

        SwitchMaterial toggle = card.findViewById(R.id.switchActuator);
        if (toggle == null) return;

        // Clear any previous listener first
        toggle.setOnCheckedChangeListener(null);
        toggle.setChecked(actuator.manualIntent
                && actuator.physicalRunning
                && "manual".equalsIgnoreCase(actuator.physicalSource));
        toggle.setEnabled(isManualMode && isCurrentlyOnline && !isSafetyLock && !isActuatorBusy);

        toggle.setOnCheckedChangeListener((buttonView, checked) -> {
            if (!isManualMode) {
                // Snap back immediately — no popup
                toggle.setOnCheckedChangeListener(null);
                toggle.setChecked(actuator.manualIntent
                        && actuator.physicalRunning
                        && "manual".equalsIgnoreCase(actuator.physicalSource));
                setupActuatorUI(card, actuator);
                return;
            }

            // Pre-validation: pH Up / pH Down mutual exclusivity
            if (checked) {
                if (actuator == phUp && phDown.physicalRunning) {
                    toggle.setOnCheckedChangeListener(null);
                    toggle.setChecked(false);
                    setupActuatorUI(card, actuator);
                    Toast.makeText(getContext(), "Cannot activate pH Up while pH Down is running.", Toast.LENGTH_LONG).show();
                    return;
                } else if (actuator == phDown && phUp.physicalRunning) {
                    toggle.setOnCheckedChangeListener(null);
                    toggle.setChecked(false);
                    setupActuatorUI(card, actuator);
                    Toast.makeText(getContext(), "Cannot activate pH Down while pH Up is running.", Toast.LENGTH_LONG).show();
                    return;
                }
            }

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
            toggle.setChecked(previousManualIntent
                    && actuator.physicalRunning
                    && "manual".equalsIgnoreCase(actuator.physicalSource));

            // Show loading popup
            showActuatorLoading("Sending command...", "");

            com.google.android.gms.tasks.Task<Void> updateTask;
            if (actuator == nutrients) {
                updateTask = com.google.android.gms.tasks.Tasks.whenAll(
                        dbHelper.updateActuatorState("growPump", checked),
                        dbHelper.updateActuatorState("bloomPump", checked)
                );
            } else {
                updateTask = dbHelper.updateActuatorState(actuator.dbKey, checked);
            }

            final boolean targetState = checked;
            updateTask.addOnCompleteListener(task -> {
                if (!isAdded() || generation != actuatorCommandGeneration || actuatorCommandFinished) return;
                if (!task.isSuccessful()) {
                    String reason = task.getException() != null ? task.getException().getMessage() : "Unknown error";
                    finishActuatorCommand(generation, actuator, previousManualIntent,
                            "Command Failed", "The actuator command could not be sent. " + reason,
                            false);
                } else {
                    // Command written — show Validating immediately and start polling
                    showActuatorLoading("Validating...", "");
                    monitorActuatorCommand(generation, actuator, previousManualIntent, targetState,
                            commandBaselineState, commandBaselineRunning, commandBaselineSource);
                }
            });
        });
    }

    /** Show the full-screen loading overlay with a title and optional subtitle */
    private void showActuatorLoading(String title, String subtitle) {
        if (actuatorLoadingOverlay == null || !isAdded()) return;
        actuatorLoadingOverlay.setVisibility(View.VISIBLE);
        actuatorLoadingOverlay.bringToFront();
        if (tvActuatorLoadingTitle != null) tvActuatorLoadingTitle.setText(title);
        if (tvActuatorLoadingStatus != null) {
            tvActuatorLoadingStatus.setText(subtitle);
            tvActuatorLoadingStatus.setVisibility(subtitle.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    /** Hide the overlay and unlock all switches */
    private void hideActuatorLoading() {
        if (actuatorLoadingOverlay == null || !isAdded()) return;
        actuatorLoadingOverlay.setVisibility(View.GONE);
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
            toggle.setChecked(actuator.manualIntent
                    && actuator.physicalRunning
                    && "manual".equalsIgnoreCase(actuator.physicalSource));
            toggle.setEnabled(isManualMode);
            // Re-register listener cleanly
            toggle.setOnCheckedChangeListener(null);
            setupActuatorUI(card, actuator);
        }
        
        if (status != null) {
            switch (actuator.state) {
                case 0:
                    status.setText("OFF");
                    status.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                    break;
                case 1:
                    status.setText("COMMAND RECEIVED" + actuatorSourceSuffix(actuator));
                    status.setTextColor(getResources().getColor(android.R.color.darker_gray));
                    break;
                case 2:
                    status.setText("VALIDATING" + actuatorSourceSuffix(actuator));
                    status.setTextColor(getResources().getColor(android.R.color.darker_gray));
                    break;
                case 3:
                    status.setText("REJECTED" + actuatorSourceSuffix(actuator));
                    status.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                    break;
                case 4:
                    status.setText("ACTIVATING" + actuatorSourceSuffix(actuator));
                    status.setTextColor(getResources().getColor(android.R.color.darker_gray));
                    break;
                case 5:
                    status.setText("RUNNING" + actuatorSourceSuffix(actuator));
                    status.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
                    break;
                case 6:
                    status.setText("STOPPING" + actuatorSourceSuffix(actuator));
                    status.setTextColor(getResources().getColor(android.R.color.darker_gray));
                    break;
            }
        }
    }

    private String actuatorSourceSuffix(Actuator actuator) {
        if ("automatic".equalsIgnoreCase(actuator.physicalSource)) return " · AUTO";
        if ("manual".equalsIgnoreCase(actuator.physicalSource)) return " · MANUAL";
        if ("android".equalsIgnoreCase(actuator.physicalSource)) return " · ANDROID";
        return "";
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
        boolean enabled = isManualMode && isCurrentlyOnline && !isSafetyLock;
        setActuatorEnabled(actWaterPumpValve, enabled);
        setActuatorEnabled(actCanopyFan, enabled);
        setActuatorEnabled(actGrowLights, enabled);
        setActuatorEnabled(actPhUp, enabled);
        setActuatorEnabled(actPhDown, enabled);
        setActuatorEnabled(actNutrients, enabled);
        setActuatorEnabled(actFogger, enabled);
        setActuatorEnabled(actReservoirFan, enabled);
        setActuatorEnabled(actPeltier, enabled);
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
                data != null ? data.ec : null, 0.0, Double.MAX_VALUE, 2, "", null));
        if (tvTemp != null) tvTemp.setText(formatSensor(
                data != null ? data.airTemperature : null, -40.0, 80.0, 1, "°C", null));
        if (tvHumidity != null) tvHumidity.setText(formatSensor(
                data != null ? data.humidity : null, 0.0, 100.0, 1, "%", null));
        if (tvWaterTemp != null) tvWaterTemp.setText(formatSensor(
                data != null ? data.waterTemperature : null, -55.0, 125.0, 1, "°C", -127.0));
        if (tvWaterLevel != null) tvWaterLevel.setText(formatSensor(
                data != null ? data.waterLevel : null, 0.0, 100.0, 1, "%", null));
    }

    private String formatSensor(Double value, double minimum, double maximum,
                                int decimalPlaces, String suffix, Double invalidSentinel) {
        if (value == null || value.isNaN() || value.isInfinite()) return "--";
        if (invalidSentinel != null && Math.abs(value - invalidSentinel) < 0.0001) return "--";
        if (value < minimum || value > maximum) return "--";
        return String.format(java.util.Locale.US, "%." + decimalPlaces + "f%s", value, suffix);
    }

    private void updateConnectionUI() {
        if (tvConnectionStatus == null || !isAdded()) return;

        if (!isCurrentlyOnline) {
            tvConnectionStatus.setText("Device Offline");
            tvConnectionStatus.setTextColor(android.graphics.Color.parseColor("#F44336")); // Red
        } else if (isSafetyLock) {
            tvConnectionStatus.setText("SAFETY LOCK ACTIVE");
            tvConnectionStatus.setTextColor(android.graphics.Color.parseColor("#F44336")); // Red
        } else {
            tvConnectionStatus.setText("Online");
            tvConnectionStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50")); // Green
        }

        View v = getView();
        if (v != null) {
            SwitchMaterial modeSwitch = v.findViewById(R.id.switchMode);
            if (modeSwitch != null) {
                modeSwitch.setEnabled(isCurrentlyOnline);
                modeSwitch.setAlpha(isCurrentlyOnline ? 1.0f : 0.6f);
            }
        }

        updateActuatorControls();
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
        isActuatorBusy = false;
        if (sensorRepository != null) {
            sensorRepository.stopListening();
        }
        if (sensorsRef != null && sensorsListener != null) {
            sensorsRef.removeEventListener(sensorsListener);
        }
        super.onDestroyView();
    }
}
