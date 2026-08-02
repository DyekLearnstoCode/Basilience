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

    private final Actuator waterPumpValve = new Actuator("Water Pump (Valve)", "solenoid");
    private final Actuator canopyFan = new Actuator("Canopy Fan", "canopyFan");
    private final Actuator growLights = new Actuator("Grow Lights", "growLight");
    private final Actuator phUp = new Actuator("pH Up", "phUpPump");
    private final Actuator phDown = new Actuator("pH Down", "phDownPump");
    private final Actuator nutrients = new Actuator("Nutrients (EC)", "growPump");
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
            modeSwitch.setEnabled(false); // Disable until device ID is resolved
            modeSwitch.setAlpha(0.5f);
            
            SharedPreferences prefs = requireContext().getSharedPreferences("basilience_prefs", android.content.Context.MODE_PRIVATE);
            String role = prefs.getString("user_role", "FARMER");
            boolean isAdmin = "ADMIN".equalsIgnoreCase(role);

            modeSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
                if (isAdmin) {
                    isManualMode = checked;
                    modeSwitch.setText("Manual Mode");
                    
                    // Optimistically turn off actuator switches in the UI when manual mode is disabled
                    if (!checked) {
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
            });
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

        View v = getView();
        if (v != null) {
            SwitchMaterial modeSwitch = v.findViewById(R.id.switchMode);
            if (modeSwitch != null) {
                SharedPreferences localPrefs = requireContext().getSharedPreferences("basilience_prefs", android.content.Context.MODE_PRIVATE);
                String role = localPrefs.getString("user_role", "FARMER");
                boolean isAdmin = "ADMIN".equalsIgnoreCase(role);
                
                modeSwitch.setEnabled(isAdmin);
                modeSwitch.setAlpha(isAdmin ? 1.0f : 0.6f);
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

                DataSnapshot sensors = snapshot.child("sensors");
                Double temp = sensors.child("airTemperature").getValue(Double.class);
                Double humidity = sensors.child("humidity").getValue(Double.class);
                Double waterTemp = sensors.child("waterTemperature").getValue(Double.class);
                Double ph = sensors.child("ph").getValue(Double.class);
                Double ec = sensors.child("ec").getValue(Double.class);
                Double waterLevel = sensors.child("waterLevel").getValue(Double.class);

                if (temp != null) tvTemp.setText(String.format("%.1f °C", temp));
                if (humidity != null) tvHumidity.setText(String.format("%.1f %%", humidity));
                if (waterTemp != null) tvWaterTemp.setText(String.format("%.1f °C", waterTemp));
                if (ph != null) tvPH.setText(String.format("%.2f", ph));
                if (ec != null) tvEC.setText(String.format("%.2f", ec));
                if (waterLevel != null) tvWaterLevel.setText(String.format("%.0f %%", waterLevel));

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
                            isManualMode = checked;
                            modeSwitch.setText("Manual Mode");
                            
                            // Optimistically turn off actuator switches in the UI when manual mode is disabled
                            if (!checked) {
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
                    syncActuatorState(nutrients, actNutrients, actuatorStatus.child(nutrients.dbKey));
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
            actuator.isOn = (state >= 1); // Consider it "on" in UI terms if it's processing or running
            if (state == 3 && stateSnap.hasChild("reason")) {
                actuator.reason = stateSnap.child("reason").getValue(String.class);
            } else {
                actuator.reason = null;
            }
            updateActuatorUI(card, actuator);
        }
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
        toggle.setEnabled(isManualMode && !isActuatorBusy);

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
        final int MAX_ATTEMPTS = 10; // 10 * 500ms = 5 seconds max
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
            if (targetState) {
                // Turning ON
                if (state == 0 || state == 1 || state == 2) showActuatorLoading("Validating...", "");
                else if (state == 4 || state == 6) showActuatorLoading("Activating...", "");
            } else {
                // Turning OFF
                if (state == 5 || state == 1 || state == 2) showActuatorLoading("Validating...", "");
                else if (state == 6 || state == 4) showActuatorLoading("Activating...", "");
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
            toggle.setChecked(actuator.isOn);
            boolean isTransitional = (actuator.state == 1 || actuator.state == 2 || actuator.state == 4 || actuator.state == 6);
            toggle.setEnabled(isManualMode && !isTransitional && !isActuatorBusy);
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
        setActuatorEnabled(actWaterPumpValve, isManualMode);
        setActuatorEnabled(actCanopyFan, isManualMode);
        setActuatorEnabled(actGrowLights, isManualMode);
        setActuatorEnabled(actPhUp, isManualMode);
        setActuatorEnabled(actPhDown, isManualMode);
        setActuatorEnabled(actNutrients, isManualMode);
        setActuatorEnabled(actFogger, isManualMode);
        setActuatorEnabled(actReservoirFan, isManualMode);
        setActuatorEnabled(actPeltier, isManualMode);
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
        NotificationHelper.showNotification(requireContext(), "System Alert", msg.toString());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (sensorsRef != null && sensorsListener != null) {
            sensorsRef.removeEventListener(sensorsListener);
        }
    }
}
