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
        boolean isOn;
        String reason;

        Actuator(String name, String dbKey) {
            this.name = name;
            this.dbKey = dbKey;
            this.state = 0;
            this.isOn = false;
        }
    }

    private DatabaseReference sensorsRef;
    private ValueEventListener sensorsListener;

    private TextView tvConnectionStatus;
    private SensorRepository sensorRepository;
    private final MutableLiveData<SensorData> sensorLiveData = new MutableLiveData<>();
    private Long previousLastSeenValue = null;
    private long lastUpdateTime = 0;
    private boolean isCurrentlyOnline = false;
    private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());

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

    private View actWaterPumpValve, actCanopyFan, actGrowLights, actPhUp, actPhDown, actNutrients, actFogger, actReservoirFan, actPeltier;

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

        if (actWaterPumpValve != null) setupActuatorUI(actWaterPumpValve, waterPumpValve);
        if (actCanopyFan != null) setupActuatorUI(actCanopyFan, canopyFan);
        if (actGrowLights != null) setupActuatorUI(actGrowLights, growLights);
        if (actPhUp != null) setupActuatorUI(actPhUp, phUp);
        if (actPhDown != null) setupActuatorUI(actPhDown, phDown);
        if (actNutrients != null) setupActuatorUI(actNutrients, nutrients);
        if (actFogger != null) setupActuatorUI(actFogger, fogger);
        if (actReservoirFan != null) setupActuatorUI(actReservoirFan, reservoirFan);
        if (actPeltier != null) setupActuatorUI(actPeltier, peltier);

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
                if (sensorData != null && isAdded()) {
                    if (tvTemp != null) tvTemp.setText(String.format("%.1f °C", sensorData.airTemperature));
                    if (tvHumidity != null) tvHumidity.setText(String.format("%.1f %%", sensorData.humidity));
                    if (tvWaterTemp != null) tvWaterTemp.setText(String.format("%.1f °C", sensorData.waterTemperature));
                    if (tvPH != null) tvPH.setText(String.format("%.2f", sensorData.ph));
                    if (tvEC != null) tvEC.setText(String.format("%.2f", sensorData.ec));
                    if (tvWaterLevel != null) tvWaterLevel.setText(String.format("%.0f %%", sensorData.waterLevel));
                }
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
                if (isCurrentlyOnline) {
                    NotificationHelper.showConfirmation(requireContext(),
                            "Trigger Refill Operation",
                            "Are you sure you want to trigger an automated reservoir refill operation?",
                            "Yes", "No", () -> {
                                dbHelper.sendOperationRequest("REFILL", "START")
                                        .addOnSuccessListener(aVoid -> Toast.makeText(getContext(), "Refill operation requested", Toast.LENGTH_SHORT).show())
                                        .addOnFailureListener(e -> Toast.makeText(getContext(), "Request failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                            });
                } else {
                    Toast.makeText(getContext(), "Device is offline", Toast.LENGTH_SHORT).show();
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
                if (isCurrentlyOnline) {
                    NotificationHelper.showConfirmation(requireContext(),
                            "Reset Safety Lock",
                            "Are you sure you want to reset the FSM safety lock? This will return the system to normal operations.",
                            "Yes", "No", () -> {
                                dbHelper.sendOperationRequest("RESET_SAFETY", "START")
                                        .addOnSuccessListener(aVoid -> Toast.makeText(getContext(), "Safety reset requested", Toast.LENGTH_SHORT).show())
                                        .addOnFailureListener(e -> Toast.makeText(getContext(), "Request failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                            });
                } else {
                    Toast.makeText(getContext(), "Device is offline", Toast.LENGTH_SHORT).show();
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
        heartbeatHandler.post(heartbeatRunnable);

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

                // Sync connection heartbeat
                if (snapshot.hasChild("deviceInfo/lastSeen")) {
                    Long lastSeen = snapshot.child("deviceInfo/lastSeen").getValue(Long.class);
                    if (lastSeen != null) {
                        if (!lastSeen.equals(previousLastSeenValue)) {
                            lastUpdateTime = System.currentTimeMillis();
                            previousLastSeenValue = lastSeen;
                        }
                        isCurrentlyOnline = (System.currentTimeMillis() - lastUpdateTime <= 30000);
                        updateConnectionUI();
                    }
                } else {
                    isCurrentlyOnline = false;
                    updateConnectionUI();
                }

                // Sync status: reservoirLocked
                Boolean reservoirLocked = snapshot.child("status").child("reservoirLocked").getValue(Boolean.class);
                if (reservoirLocked != null) isReservoirLocked = reservoirLocked;

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
        if (stateSnap.getValue() instanceof Boolean) {
            state = stateSnap.getValue(Boolean.class) ? 5 : 0; // Fallback: true -> RUNNING (5), false -> OFF (0)
        } else if (stateSnap.hasChild("state")) {
            state = stateSnap.child("state").getValue(Integer.class);
        }

        if (state != null) {
            actuator.state = state;
            actuator.isOn = (state == 1 || state == 2 || state == 4 || state == 5 || state == 6); 
            if (state == 3 && stateSnap.hasChild("reason")) {
                actuator.reason = stateSnap.child("reason").getValue(String.class);
            } else {
                actuator.reason = null;
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
        nutrients.isOn   = (combinedState == 1 || combinedState == 2 || combinedState == 4 || combinedState == 5 || combinedState == 6);
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
        toggle.setChecked(actuator.isOn);
        toggle.setEnabled(isManualMode);

        toggle.setOnCheckedChangeListener((buttonView, checked) -> {
            if (!isManualMode) {
                // Snap back immediately — no popup
                toggle.setOnCheckedChangeListener(null);
                toggle.setChecked(actuator.isOn);
                setupActuatorUI(card, actuator);
                Toast.makeText(getContext(), "Switch to Manual Mode to control actuators.", Toast.LENGTH_SHORT).show();
                return;
            }

            // Pre-validation: pH Up / pH Down mutual exclusivity
            if (checked) {
                if (actuator == phUp && phDown.isOn) {
                    toggle.setOnCheckedChangeListener(null);
                    toggle.setChecked(false);
                    setupActuatorUI(card, actuator);
                    Toast.makeText(getContext(), "Cannot activate pH Up while pH Down is running.", Toast.LENGTH_LONG).show();
                    return;
                } else if (actuator == phDown && phUp.isOn) {
                    toggle.setOnCheckedChangeListener(null);
                    toggle.setChecked(false);
                    setupActuatorUI(card, actuator);
                    Toast.makeText(getContext(), "Cannot activate pH Down while pH Up is running.", Toast.LENGTH_LONG).show();
                    return;
                }
            }

            // Lock all switches while processing
            isActuatorBusy = true;
            updateActuatorControls();

            // Revert toggle to original position — popup will be the feedback
            toggle.setOnCheckedChangeListener(null);
            toggle.setChecked(actuator.isOn);

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
                if (!isAdded()) return;
                if (!task.isSuccessful()) {
                    hideActuatorLoading();
                    isActuatorBusy = false;
                    updateActuatorControls();
                    Toast.makeText(getContext(), "Failed to send command: " + (task.getException() != null ? task.getException().getMessage() : "unknown error"), Toast.LENGTH_SHORT).show();
                } else {
                    // Command written — show Validating immediately and start polling
                    showActuatorLoading("Validating...", "");
                    pollActuatorUntilDone(actuator, card, targetState, 0);
                }
            });
        });
    }

    /** Show the full-screen loading overlay with a title and optional subtitle */
    private void showActuatorLoading(String title, String subtitle) {
        if (actuatorLoadingOverlay == null || !isAdded()) return;
        actuatorLoadingOverlay.setVisibility(View.VISIBLE);
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

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!isAdded()) return;

            int state = actuator.state;

            // Check if we've reached the expected terminal state
            boolean doneSuccess = (targetState && state == 5) || (!targetState && state == 0);

            if (doneSuccess) {
                showActuatorLoading("Done", "");
                // Small pause so user can see the final state label, then close
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
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
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
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
                showActuatorLoading("ESP32 Offline", "Device did not respond in time");
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
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
    }

    private void updateActuatorUI(View card, boolean isOn) {
        Actuator actuator = getActuatorFromCard(card);
        if (actuator != null) {
            actuator.isOn = isOn;
            actuator.state = isOn ? 5 : 0;
            updateActuatorUI(card, actuator);
        }
    }

    private void updateActuatorUI(View card, Actuator actuator) {
        if (card == null || !isAdded()) return;
        TextView status = card.findViewById(R.id.tvStatus);
        SwitchMaterial toggle = card.findViewById(R.id.switchActuator);

        if (toggle != null) {
            // Update switch position without triggering listener
            toggle.setOnCheckedChangeListener(null);
            toggle.setOnCheckedChangeListener(null);
            toggle.setChecked(actuator.isOn);
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
                case 2:
                case 4:
                case 6:
                    status.setText(actuator.state == 1 ? "Cmd Received" : 
                                   actuator.state == 2 ? "Validating..." : 
                                   actuator.state == 4 ? "Activating..." : "Stopping...");
                    status.setTextColor(getResources().getColor(android.R.color.darker_gray));
                    break;
                case 3:
                    status.setText("REJECTED");
                    status.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                    if (actuator.reason != null && !actuator.reason.isEmpty()) {
                        Toast.makeText(getContext(), actuator.name + " rejected: " + actuator.reason, Toast.LENGTH_LONG).show();
                        actuator.reason = null;
                    }
                    break;
                case 5:
                    status.setText("ON");
                    status.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
                    break;
            }
        }
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
        return peltier;
    }

    private void updateActuatorControls() {
        boolean enabled = isManualMode && isCurrentlyOnline;
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

        // Optimistically reset all actuator UIs off when disabling manual mode
        if (!checked) {
            showActuatorLoading("Restoring Safety Protocols", "Deactivating all manual overrides...");
            new Handler(Looper.getMainLooper()).postDelayed(this::hideActuatorLoading, 3000);

            updateActuatorUI(actWaterPumpValve, false);
            updateActuatorUI(actCanopyFan, false);
            updateActuatorUI(actGrowLights, false);
            updateActuatorUI(actPhUp, false);
            updateActuatorUI(actPhDown, false);
            updateActuatorUI(actNutrients, false);
            updateActuatorUI(actFogger, false);
            updateActuatorUI(actReservoirFan, false);
            updateActuatorUI(actPeltier, false);
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

    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (System.currentTimeMillis() - lastUpdateTime > 30000) {
                isCurrentlyOnline = false;
            }
            updateConnectionUI();
            heartbeatHandler.postDelayed(this, 5000);
        }
    };

    private void updateConnectionUI() {
        if (tvConnectionStatus == null || !isAdded()) return;

        if (isCurrentlyOnline) {
            tvConnectionStatus.setText("Online");
            tvConnectionStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50")); // Green
        } else {
            tvConnectionStatus.setText("Offline");
            tvConnectionStatus.setTextColor(android.graphics.Color.parseColor("#F44336")); // Red
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
        super.onDestroyView();
        heartbeatHandler.removeCallbacks(heartbeatRunnable);
        if (sensorRepository != null) {
            sensorRepository.stopListening();
        }
        if (sensorsRef != null && sensorsListener != null) {
            sensorsRef.removeEventListener(sensorsListener);
        }
    }
}
